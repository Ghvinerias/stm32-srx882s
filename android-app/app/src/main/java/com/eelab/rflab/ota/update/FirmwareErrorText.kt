package com.eelab.rflab.ota.update

/**
 * User-facing messages for server failures (pure Kotlin, JVM-tested so the
 * wording is pinned down along with the rules).
 *
 * The ViewModel maps whatever a check/download threw into one of these
 * strings; the certificate cases are handled *before* this (FirstUse opens
 * the trust dialog), so the text here covers only what reaches the error
 * surface.
 */
object FirmwareErrorText {

    /** The best user-facing message for [error]; never returns raw stack text. */
    fun describe(error: Throwable): String = when (error) {
        is FirmwareServerException.HttpUrlRejected -> error.message ?: UNSPECIFIED
        is FirmwareServerException.BadCredentials -> error.message ?: UNSPECIFIED
        is FirmwareServerException.ServerError -> error.message ?: UNSPECIFIED
        is FirmwareServerException.Unreachable -> error.message ?: UNSPECIFIED
        is FirmwareServerException.Timeout -> error.message ?: UNSPECIFIED
        is FirmwareServerException.CertificateChanged -> error.message ?: UNSPECIFIED
        is FirmwareServerException.HashMismatch -> error.message ?: UNSPECIFIED
        is FirmwareServerException.ImageNotListed -> error.message ?: UNSPECIFIED

        // Anything else (cancellation is rethrown before this, never shown):
        // keep the exception's own text, or a generic line.
        else -> error.message?.takeIf { it.isNotBlank() } ?: UNSPECIFIED
    }

    private const val UNSPECIFIED = "The update check failed"
}
