package com.eelab.rflab.ota.update

import java.io.IOException

/**
 * Every failure the server-side update flow can report, each carrying a
 * user-readable message. Extends [IOException] so plain I/O plumbing
 * (cancellation, dispatcher rules) treats it as a normal transport failure.
 *
 * Mapping from raw JDK exceptions happens in `FirmwareServerClient`; the UI
 * shows the messages through `FirmwareErrorText`.
 */
sealed class FirmwareServerException(message: String) : IOException(message) {

    /** A non-https base URL was used — rejected before any socket is opened. */
    class HttpUrlRejected(message: String) : FirmwareServerException(message)

    /** The server answered 401: wrong Basic user/password. */
    class BadCredentials :
        FirmwareServerException(
            "The server rejected the login (401) — check the username and " +
                "the password in ~/.keeloq-flash-pass",
        )

    /** Any other non-2xx answer. */
    class ServerError(val statusCode: Int) :
        FirmwareServerException("The server answered with status $statusCode")

    /** DNS/connect/read failures, hostname unknown, port closed, … */
    class Unreachable(cause: Exception) :
        FirmwareServerException(describeUnreachable(cause))

    /** A request exceeded the connect or read timeout. */
    class Timeout :
        FirmwareServerException(
            "The server did not answer in time — check that it is reachable",
        )

    /**
     * First contact with this server: the connection was refused because no
     * fingerprint is pinned yet. [fingerprint] is the observed leaf cert's
     * SHA-256, shown to the user for confirmation (trust on first use).
     */
    class FirstUseCertificate(val fingerprint: String) :
        FirmwareServerException("The server's certificate is not trusted yet")

    /**
     * The pinned certificate's fingerprint does not match what the server
     * presented — it was renewed, re-provisioned, or intercepted. The pin is
     * never overwritten silently; the user must reset trust explicitly.
     */
    class CertificateChanged(val pinned: String, val observed: String) :
        FirmwareServerException(
            "The server's certificate changed since it was trusted — reject " +
                "the connection until you reset trust and re-confirm",
        )

    /** Downloaded image hash does not match the SHA256SUMS entry. */
    class HashMismatch(val expected: String, val observed: String) :
        FirmwareServerException(
            "The downloaded image's SHA-256 does not match the server's " +
                "SHA256SUMS — the transfer is refused (corrupted download " +
                "or wrong manifest)",
        )

    /** The manifest does not list the image needed for this unit. */
    class ImageNotListed(fileName: String) :
        FirmwareServerException(
            "SHA256SUMS does not list $fileName — the server's dist is out " +
                "of date",
        )

    private companion object {

        /** Readable DNS/connect failures instead of raw exception stacks. */
        fun describeUnreachable(cause: Exception): String = when (cause) {
            is java.net.UnknownHostException ->
                "The server host is unknown — check the address and the network"

            else ->
                "Could not reach the server (${cause.javaClass.simpleName}) — " +
                    "check the address and the network"
        }
    }
}
