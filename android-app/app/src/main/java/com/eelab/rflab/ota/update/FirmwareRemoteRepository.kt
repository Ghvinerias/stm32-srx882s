package com.eelab.rflab.ota.update

import java.io.File

/**
 * The update flows on top of [FirmwareUpdateApi]: SHA256SUMS parsing,
 * unit→image selection, the up-to-date decision, and a download that is
 * verified against the manifest *before* the bytes reach the OTA engine.
 *
 * The class is plain Kotlin over the API seam plus a scratch [cacheDir], so
 * JVM tests inject a fake API and a temp folder (the production API is
 * `FirmwareServerClient`, not unit-tested because it needs the TLS stack).
 *
 * Download discipline: the image is streamed to a file in [cacheDir] while a
 * SHA-256 is computed; on success the digest must equal the manifest entry's
 * ([RemoteImage.sha256Hex]) — on mismatch the file is deleted and
 * [FirmwareServerException.HashMismatch] is thrown, so a corrupted download
 * can never be handed to the device. The verified bytes are then returned
 * whole, ready to go into the existing `OtaEngine.flash` path exactly like a
 * manually picked image.
 */
class FirmwareRemoteRepository(
    private val api: FirmwareUpdateApi,
    private val cacheDir: File,
) {

    /**
     * Runs one update check for [unit]: downloads and parses the server's
     * SHA256SUMS, picks the unit's app image entry, best-effort probes its
     * size, and evaluates against [lastFlashedSha256].
     *
     * Network/TLS failures are thrown at the caller (the ViewModel maps them
     * into user-facing text, including the first-use certificate dialog);
     * content problems that are check-scoped — e.g. the manifest not listing
     * the unit's image — come back as [UpdateCheckState.Failed].
     */
    suspend fun checkForUpdate(
        settings: FirmwareServerSettings,
        unit: FirmwareUnit,
        lastFlashedSha256: String?,
    ): UpdateCheckState {
        val fileName = unit.appImageFileName

        val sumsText = api.fetchSums(settings)
        val entries = Sha256Sums.parse(sumsText)
        val entry = Sha256Sums.entryFor(entries, fileName)
            ?: return UpdateCheckState.Failed(
                "SHA256SUMS does not list $fileName — the server's dist is " +
                    "out of date",
            )

        // Congested/odd servers must not block the update choice on headers:
        // a missing size is shown as "size unknown" instead of a failure.
        val sizeBytes: Long? = try {
            api.imageSize(settings, fileName)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }

        val remote = RemoteImage(
            fileName = fileName,
            sha256Hex = entry.sha256Hex,
            sizeBytes = sizeBytes,
        )
        return UpdateEvaluator.evaluate(lastFlashedSha256, remote)
    }

    /**
     * Streams the image [image] promises into [cacheDir], verifies its
     * SHA-256 against the manifest entry, and returns the verified bytes.
     * Scratch files are removed on both the success and failure paths, so a
     * corrupted download can never be handed to the device.
     *
     * @param onProgress download progress snapshots
     *   (`bytesRead`, `totalBytesOrNull`), called on the IO context.
     */
    suspend fun fetchImage(
        settings: FirmwareServerSettings,
        image: RemoteImage,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): ByteArray {
        val scratch = File(cacheDir, image.fileName + SCRATCH_SUFFIX)

        // Leftovers of a killed earlier attempt must never stand in for the
        // freshly verified bytes.
        scratch.delete()

        try {
            val digestHex = api.download(
                settings = settings,
                fileName = image.fileName,
                destination = scratch,
                onProgress = onProgress,
            )

            if (!digestHex.equals(image.sha256Hex, ignoreCase = true)) {
                throw FirmwareServerException.HashMismatch(
                    expected = image.sha256Hex,
                    observed = digestHex,
                )
            }

            val verified = scratch.readBytes()
            scratch.delete()
            return verified
        } finally {
            // Whatever the outcome (hash mismatch, cancellation, exception),
            // only fully verified downloads ever exist under the real name.
            scratch.delete()
        }
    }

    private companion object {

        /**
         * Suffix marking the in-flight file: only after verification is the
         * copy handed out, and the scratch file never wears the plain name.
         */
        private const val SCRATCH_SUFFIX = ".downloading"
    }
}
