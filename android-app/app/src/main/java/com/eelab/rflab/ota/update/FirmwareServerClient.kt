package com.eelab.rflab.ota.update

import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * HTTPS client for the lab's firmware web server (`serve_flash.py`), the
 * production [FirmwareUpdateApi].
 *
 * ### TLS without a trust-all weakness
 *
 * The server runs a self-signed certificate, so the stock trust store cannot
 * validate it. Instead of disabling verification, this client enforces
 * *trust-on-first-use pinning* through [TofuTrustManager]: with a pin stored
 * for the base URL, only the pinned certificate is accepted anywhere in the
 * presented chain; with no pin the handshake aborts with
 * [FirmwareServerException.FirstUseCertificate] carrying the observed
 * fingerprint so the UI can ask the user to confirm it (the pin is written by
 * the ViewModel only after that confirmation — never automatically).
 *
 * Hostname verification is pin-driven too ([TofuHostnameVerifier]): the lab's
 * self-signed cert is regenerated per host and typically carries no matching
 * IP SANs, so after the fingerprint matched, a name lookup would only weaken
 * the binding that the pin already establishes.
 *
 * `HttpsURLConnection` (java.net) is used directly; a full HTTP client
 * library such as OkHttp buys nothing for three fixed GETs and would need the
 * same trust manager wiring anyway, so no dependency is added.
 *
 * All calls run on [ioDispatcher]. Cooperation with cancellation is done via
 * [ensureActive] between buffer reads; the socket disconnect runs in the
 * `finally` of each call whatever the outcome.
 *
 * @param pinStore where pinned fingerprints live, per base URL.
 * @param connectTimeoutMs socket connect timeout (the brief prescribes 10 s).
 * @param readTimeoutMs per-read timeout (the brief prescribes 30 s).
 */
class FirmwareServerClient(
    private val pinStore: TofuPinStore,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FirmwareUpdateApi {

    override suspend fun fetchSums(settings: FirmwareServerSettings): String =
        fetchText(settings, SUMS_PATH)

    override suspend fun imageSize(
        settings: FirmwareServerSettings,
        fileName: String,
    ): Long? = withContext(ioDispatcher) {
        val connection = connect(settings, imagePath(fileName))
        try {
            val statusCode = responseCodeOf(connection)
            ensureInRange(statusCode)
            connection.contentLengthFromHeader()
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(
        settings: FirmwareServerSettings,
        fileName: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): String = withContext(ioDispatcher) {
        val connection = connect(settings, imagePath(fileName))
        try {
            val statusCode = responseCodeOf(connection)
            ensureInRange(statusCode)
            val totalFromHeader = connection.contentLengthFromHeader()
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L

            destination.outputStream().use { sink ->
                connection.inputStream.use { source: InputStream ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        read += count
                        onProgress(read, totalFromHeader)
                    }
                }
            }
            digestHexOf(digest)
        } finally {
            connection.disconnect()
        }
    }

    // --- Connection plumbing ------------------------------------------------

    /** GET for a text endpoint, returning the body decoded as UTF-8. */
    private suspend fun fetchText(
        settings: FirmwareServerSettings,
        path: String,
    ): String = withContext(ioDispatcher) {
        val connection = connect(settings, path)
        try {
            val statusCode = responseCodeOf(connection)
            ensureInRange(statusCode)
            connection.inputStream.use(InputStream::readBytes).decodeToString()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens the GET connection for a URL built from [settings] + [path],
     * mapping every failure class to its [FirmwareServerException] subclass
     * (thrown, not wrapped, so callers can special-case first use vs a
     * changed certificate without string matching).
     */
    private fun connect(settings: FirmwareServerSettings, path: String): HttpURLConnection {
        val base = settings.normalizedBaseUrl
        if (!base.startsWith("https://")) {
            throw FirmwareServerException.HttpUrlRejected(
                "The server address must be https:// — HTTP is refused so the " +
                    "image and password never travel in the clear",
            )
        }

        val pinned = pinStore.pinnedFor(base)
        return try {
            val url = URL("$base$path")
            (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = this@FirmwareServerClient.connectTimeoutMs
                readTimeout = this@FirmwareServerClient.readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(
                    /* keyManagers = */ null,
                    arrayOf(TofuTrustManager(pinned)),
                    SecureRandom(),
                )
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = TofuHostnameVerifier(pinned)
                setAuthorizationHeader(settings)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FirmwareServerException) {
            throw rejected
        } catch (error: Exception) {
            throw mapIoException(error)
        }
    }

    /**
     * Sets the HTTP Basic header for the settings' credentials (omitted when
     * both parts are empty, letting the server's own 401 explain the problem
     * instead of a client-side guess). The header is built per connection and
     * never logged or persisted.
     */
    private fun HttpURLConnection.setAuthorizationHeader(settings: FirmwareServerSettings) {
        if (settings.username.isEmpty() && settings.password.isEmpty()) return
        val raw = "${settings.username}:${settings.password}".toByteArray(Charsets.UTF_8)
        setRequestProperty(
            "Authorization",
            "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP),
        )
    }

    /** Runs the TLS handshake + request, surfacing typed trust failures. */
    private fun responseCodeOf(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw mapIoException(error)
    }

    /** Maps a 2xx-or-die status to a readable exception. */
    private fun ensureInRange(statusCode: Int): Unit = when (statusCode) {
        in HTTP_OK..HTTP_OK_END -> Unit
        HTTP_UNAUTHORIZED -> throw FirmwareServerException.BadCredentials()
        else -> throw FirmwareServerException.ServerError(statusCode)
    }

    private fun mapIoException(error: Exception): IOException = when {
        // The TLS stack wraps exceptions thrown by our TrustManager (first
        // use / changed certificate) inside an SSLHandshakeException, so look
        // through the cause chain for the typed exception first.
        generateSequence<Throwable>(error) { it.cause }
            .firstOrNull { it is FirmwareServerException } != null ->
            generateSequence<Throwable>(error) { it.cause }
                .first { it is FirmwareServerException } as FirmwareServerException
        else -> mapPlainIoException(error)
    }

    private fun mapPlainIoException(error: Exception): IOException = when (error) {
        is FirmwareServerException -> error
        is SocketTimeoutException -> FirmwareServerException.Timeout()
        is UnknownHostException -> FirmwareServerException.Unreachable(error)
        is MalformedURLException ->
            FirmwareServerException.HttpUrlRejected("Not a usable server address")

        is IOException -> FirmwareServerException.Unreachable(error)
        else -> FirmwareServerException.Unreachable(IOException(error))
    }

    // --- TLS pieces ----------------------------------------------------------

    /**
     * The X509TrustManager enforcing the pin: [TofuPins.decide] maps the
     * pinned/observed fingerprint pair to trust or to a typed rejection whose
     * classes distinguish "ask the user" from "fail loudly". No chain
     * validation beyond the pin happens by design — TOFU replaces the CA
     * with the user's one-time confirmation. (The lint rule
     * `CustomX509TrustManager` warns about exactly this shape; the
     * alternative — a trust-all manager — is never used here.)
     */
    @Suppress("CustomX509TrustManager")
    internal class TofuTrustManager(
        private val pinnedSha256: String?,
    ) : X509TrustManager {

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            // This app never authenticates itself with a client certificate.
            throw CertificateException("Client certificates are not used")
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            require(chain.isNotEmpty()) { "the server presented no certificate chain" }
            val fingerprints = chain.map(::certificateFingerprint)
            when (val decision = TofuPins.decide(pinnedSha256, fingerprints)) {
                PinDecision.Accept -> Unit

                is PinDecision.FirstUse ->
                    throw FirmwareServerException.FirstUseCertificate(decision.fingerprint)

                is PinDecision.Mismatch ->
                    throw FirmwareServerException.CertificateChanged(
                        pinned = decision.pinned,
                        observed = decision.observed,
                    )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = EMPTY_ISSUERS

        private companion object {

            val EMPTY_ISSUERS: Array<X509Certificate> = arrayOf()
        }
    }

    /**
     * The pin-driven counterpart of the platform's default hostname check,
     * used after the trust manager accepted the pinned certificate: the peers
     * must still be readable and the pinned fingerprint must still be among
     * them (belt-and-braces against a session carrying foreign certificates).
     */
    internal class TofuHostnameVerifier(
        private val pinnedSha256: String?,
    ) : HostnameVerifier {

        override fun verify(hostname: String?, session: SSLSession): Boolean {
            if (pinnedSha256 == null) return false
            val peers: List<Certificate> = try {
                session.peerCertificates.toList()
            } catch (unverified: SSLPeerUnverifiedException) {
                return false
            }
            return peers.any {
                certificateFingerprint(it).equals(pinnedSha256, ignoreCase = true)
            }
        }
    }

    companion object {

        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000

        const val DEFAULT_READ_TIMEOUT_MS = 30_000

        /** Server-side path of the SHA256SUMS manifest (never built from user input). */
        private const val SUMS_PATH = "/fw/SHA256SUMS"

        private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024

        private const val HTTP_OK = 200

        private const val HTTP_OK_END = 299

        private const val HTTP_UNAUTHORIZED = 401

        /** Server-side path of one firmware image file. */
        fun imagePath(fileName: String): String = "/fw/$fileName"

        /** The connection's Content-Length header, or null when absent/unknown. */
        private fun HttpURLConnection.contentLengthFromHeader(): Long? =
            getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }

        /** SHA-256 of [certificate]'s DER encoding, lowercase hex. */
        internal fun certificateFingerprint(certificate: Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(certificate.encoded).joinToString("") { byte ->
                "%02x".format(Locale.US, byte)
            }
        }

        private fun digestHexOf(digest: MessageDigest): String =
            digest.digest().joinToString("") { byte ->
                "%02x".format(Locale.US, byte)
            }
    }
}
