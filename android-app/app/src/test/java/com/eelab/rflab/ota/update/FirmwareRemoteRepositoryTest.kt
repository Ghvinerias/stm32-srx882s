package com.eelab.rflab.ota.update

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The server-side update flows over a fake [FirmwareUpdateApi] (no network,
 * no TLS): manifest-driven image selection, the up-to-date decision, size
 * probing robustness, and the download's verify-before-flash guarantee — a
 * corrupted download throws and never leaves bytes behind.
 */
class FirmwareRemoteRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val unitAHash =
        "0659c7992ea26a6d07e3a0b6b1e0b8c2c955eb3dbd1a1d8b4e4f1b5b9c7d3a1f"

    private val unitBHash =
        "56d98c4d82f85c2b2693baa6ca5ae92a218589f7378c7ff53de5e5e757714cb8"

    /** The manifest as the real server serves it (app + a merged decoy). */
    private val manifest = """
        $unitAHash  esp32c3-cc1101-lab-unit1-app.bin
        924373f51ad0a639172de369ba55b2dd9b2269c3e2defafc24c0d2b5e90115fe  esp32c3-cc1101-lab-unit1-merged.bin
        $unitBHash  esp32c3-cc1101-lab-unit2-app.bin
    """.trimIndent()

    /**
     * The API fake: canned manifest and image bytes, whose real SHA-256 is
     * reported by [FirmwareUpdateApi.download] — or a forced wrong digest, to
     * drive the mismatch rejection.
     */
    private class FakeApi(
        private val manifest: String,
        private val imageBytes: ByteArray,
        private val reportedSize: Long? = imageBytes.size.toLong(),
        private val reportedDigest: String = imageDigestHex(imageBytes),
        private val sumsError: Exception? = null,
        private val sizeError: Exception? = null,
    ) : FirmwareUpdateApi {

        val downloadedTo = mutableListOf<File>()

        override suspend fun fetchSums(settings: FirmwareServerSettings): String {
            sumsError?.let { throw it }
            return manifest
        }

        override suspend fun imageSize(
            settings: FirmwareServerSettings,
            fileName: String,
        ): Long? {
            sizeError?.let { throw it }
            return reportedSize
        }

        override suspend fun download(
            settings: FirmwareServerSettings,
            fileName: String,
            destination: File,
            onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
        ): String {
            destination.writeBytes(imageBytes)
            downloadedTo += destination
            for (read in 1..imageBytes.size) {
                onProgress(read.toLong(), reportedSize)
            }
            return reportedDigest
        }
    }

    // --- checkForUpdate -------------------------------------------------------

    @Test
    fun checkPicksTheUnitImageAndReportsUpdateAvailable() = runBlocking {
        val repository = repository(FakeApi(manifest, imageBytes))

        val state = repository.checkForUpdate(
            settings = settings(),
            unit = FirmwareUnit.UNIT_A,
            lastFlashedSha256 = "ffff",
        )

        val available = state as UpdateCheckState.UpdateAvailable
        assertEquals("esp32c3-cc1101-lab-unit1-app.bin", available.image.fileName)
        assertEquals(unitAHash, available.image.sha256Hex)
        assertEquals(imageBytes.size.toLong(), available.image.sizeBytes)
    }

    @Test
    fun checkReportsUpToDateAgainstTheLastFlashedHash() = runBlocking {
        val repository = repository(FakeApi(manifest, imageBytes))

        val state = repository.checkForUpdate(
            settings = settings(),
            unit = FirmwareUnit.UNIT_A,
            lastFlashedSha256 = unitAHash.uppercase(),
        )

        assertEquals(UpdateCheckState.UpToDate(unitAHash), state)
    }

    @Test
    fun checkReportsUnknownBaselineWhenTheUnitWasNeverFlashedHere() = runBlocking {
        val repository = repository(FakeApi(manifest, imageBytes))

        val state = repository.checkForUpdate(
            settings = settings(),
            unit = FirmwareUnit.UNIT_A,
            lastFlashedSha256 = null,
        )

        val unknown = state as UpdateCheckState.UnknownBaseline
        assertEquals(unitAHash, unknown.image.sha256Hex)
    }

    @Test
    fun unitBSelectsUnitBsFileFromTheSameManifest() = runBlocking {
        val repository = repository(FakeApi(manifest, imageBytes))

        val state = repository.checkForUpdate(
            settings = settings(),
            unit = FirmwareUnit.UNIT_B,
            lastFlashedSha256 = unitBHash,
        )

        assertEquals(
            UpdateCheckState.UpToDate(unitBHash),
            state,
        )
    }

    @Test
    fun checkFailsWhenTheManifestDoesNotListTheUnitImage() = runBlocking {
        val withoutUnitA = manifest
            .lines()
            .filter { !it.contains("esp32c3-cc1101-lab-unit1-app.bin") }
            .joinToString("\n")
        val repository = repository(FakeApi(withoutUnitA, imageBytes))

        val state = repository.checkForUpdate(
            settings = settings(),
            unit = FirmwareUnit.UNIT_A,
            lastFlashedSha256 = null,
        )

        assertTrue(state is UpdateCheckState.Failed)
        assertTrue(
            "esp32c3-cc1101-lab-unit1-app.bin" in (state as UpdateCheckState.Failed).message,
        )
    }

    @Test
    fun sumsAndSizeFailuresSurfaceAsThrownAndMissingSize() = runBlocking {
        val repository = repository(
            FakeApi(manifest, imageBytes, sizeError = java.io.IOException("no size")),
        )

        val state = repository.checkForUpdate(
            settings = settings(),
            unit = FirmwareUnit.UNIT_A,
            lastFlashedSha256 = null,
        )

        // A blocked/absent size header must not block the update decision.
        assertNull((state as UpdateCheckState.UnknownBaseline).image.sizeBytes)
    }

    @Test
    fun checkPropagatesNetworkErrorsForTheViewModelToMap() {
        val repository = repository(
            FakeApi(manifest, imageBytes, sumsError = java.io.IOException("host down")),
        )

        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                repository.checkForUpdate(
                    settings = settings(),
                    unit = FirmwareUnit.UNIT_A,
                    lastFlashedSha256 = null,
                )
            }
        }
    }

    // --- fetchImage -----------------------------------------------------------

    @Test
    fun fetchImageReturnsVerifiedBytes() {
        val bytes = imageBytes
        val remote = RemoteImage(
            fileName = "esp32c3-cc1101-lab-unit1-app.bin",
            sha256Hex = sha256Hex(bytes),
            sizeBytes = bytes.size.toLong(),
        )
        val progress = mutableListOf<Pair<Long, Long?>>()
        val repository = repository(FakeApi(manifest, bytes))

        val verified = runBlocking {
            repository.fetchImage(settings(), remote) { read, total ->
                progress += read to total
            }
        }

        assertTrue(verified.contentEquals(bytes))
        assertEquals(bytes.size.toLong(), progress.last().first)
        assertEquals(bytes.size.toLong(), progress.last().second)

        // The scratch copy is gone; nothing lingers in the cache dir.
        assertTrue(temp.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun fetchImageRefusesAMismatchingDownload() {
        val bytes = imageBytes
        val repository = repository(
            FakeApi(
                manifest,
                bytes,
                reportedDigest =
                    "c0ffee0000000000000000000000000000000000000000000000000000000000",
            ),
        )
        val remote = RemoteImage(
            fileName = "esp32c3-cc1101-lab-unit1-app.bin",
            sha256Hex = sha256Hex(bytes),
            sizeBytes = bytes.size.toLong(),
        )

        val error = assertThrows(
            FirmwareServerException.HashMismatch::class.java,
        ) {
            runBlocking {
                repository.fetchImage(settings(), remote) { _, _ -> }
            }
        }

        assertEquals(sha256Hex(bytes), error.expected)
        assertTrue(error.observed.startsWith("c0ffee"))
        // The refused download left no bytes behind.
        assertTrue(temp.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun fetchImageLeavesNoScratchFileWhenTheTransferFails() {
        val bytes = imageBytes
        val failingApi = object : FirmwareUpdateApi {
            override suspend fun fetchSums(settings: FirmwareServerSettings): String {
                error("not used in this test")
            }

            override suspend fun imageSize(
                settings: FirmwareServerSettings,
                fileName: String,
            ): Long? = null

            override suspend fun download(
                settings: FirmwareServerSettings,
                fileName: String,
                destination: File,
                onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
            ): String {
                destination.writeBytes(bytes)
                throw java.io.IOException("connection reset mid-download")
            }
        }
        val repository = repository(failingApi)
        val remote = RemoteImage(
            fileName = "esp32c3-cc1101-lab-unit1-app.bin",
            sha256Hex = sha256Hex(bytes),
            sizeBytes = null,
        )

        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                repository.fetchImage(settings(), remote) { _, _ -> }
            }
        }

        assertTrue(temp.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun fetchImageUsesTheScratchSuffixSoPartialFilesNeverExistUnderTheRealName() {
        val bytes = imageBytes
        val seen = mutableListOf<File>()
        val api = FakeApi(manifest, bytes)
        val wrapped = object : FirmwareUpdateApi by api {
            override suspend fun download(
                settings: FirmwareServerSettings,
                fileName: String,
                destination: File,
                onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
            ): String {
                seen += destination
                return api.download(settings, fileName, destination, onProgress)
            }
        }
        val repository = repository(wrapped)
        val remote = RemoteImage(
            fileName = "esp32c3-cc1101-lab-unit1-app.bin",
            sha256Hex = sha256Hex(bytes),
            sizeBytes = null,
        )

        runBlocking {
            repository.fetchImage(settings(), remote) { _, _ -> }
        }

        assertTrue(seen.single().name.endsWith(".downloading"))
    }

    // --- helpers --------------------------------------------------------------

    private fun repository(api: FirmwareUpdateApi): FirmwareRemoteRepository =
        FirmwareRemoteRepository(api = api, cacheDir = temp.root)

    private fun settings(): FirmwareServerSettings = FirmwareServerSettings(
        baseUrl = "https://10.10.10.24:8443",
        password = "secret",
    )

    /** Valid-looking app image: ESP magic byte then a checksum-friendly tail. */
    private val imageBytes: ByteArray =
        byteArrayOf(0xE9.toByte(), 0x14, 0x00, 0x20) +
            ByteArray(64) { it.toByte() }

    private fun sha256Hex(bytes: ByteArray): String = imageDigestHex(bytes)
}

/** Top-level so the fake's constructor default argument can reference it. */
private fun imageDigestHex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(Locale.US, byte)
    }
