package com.eelab.rflab.ota.update

import java.io.File

/**
 * The raw server operations the update flow needs — the seam that makes the
 * whole "check / download / verify" logic JVM-testable: production is
 * `FirmwareServerClient` (HttpsURLConnection + TOFU TLS, not unit-tested),
 * tests inject a fake.
 *
 * Paths served by `serve_flash.py`: `/fw/SHA256SUMS` and `/fw/<image>.bin`,
 * behind HTTP Basic auth and TLS with a self-signed certificate that must be
 * pinned (trust on first use) before anything works.
 */
interface FirmwareUpdateApi {

    /** The raw text of the server's SHA256SUMS manifest. */
    suspend fun fetchSums(settings: FirmwareServerSettings): String

    /**
     * The Content-Length the server reports for an image file, from a GET
     * whose body is abandoned after the headers (the server has no HEAD
     * endpoint worth trusting), or null when the header is missing.
     */
    suspend fun imageSize(
        settings: FirmwareServerSettings,
        fileName: String,
    ): Long?

    /**
     * Downloads [fileName] into [destination], reporting progress as
     * `(bytesRead, totalBytesOrNull)`, and returns the SHA-256 hex of the
     * received bytes.
     */
    suspend fun download(
        settings: FirmwareServerSettings,
        fileName: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): String
}
