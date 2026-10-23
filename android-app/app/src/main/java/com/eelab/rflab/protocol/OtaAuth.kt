package com.eelab.rflab.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Digests and session MACs for the OTA protocol, matching `mac()` in the
 * normative encoder `esp32c3-cc1101-lab/ota/ota_ref.py` byte-for-byte:
 *
 * ```
 * mac(BEGIN)      = HMAC-SHA256(secret, "OTA-BEGIN" || nonce || size_le32 || sha256)
 * mac(BOOTLOADER) = HMAC-SHA256(secret, "OTA-DL" || nonce)
 * ```
 *
 * The secret is the 32 bytes typed into the app (64 hex characters). The
 * device consumes its HELLO nonce on the first BEGIN or BOOTLOADER *attempt*,
 * good or bad, so a fresh nonce is required before every authenticated
 * command (the OTA engine re-runs HELLO instead of retrying those commands).
 */
object OtaAuth {

    private val SOURCE_ASCII = "OTA-BEGIN".toByteArray(Charsets.US_ASCII)
    private val DOWNLOAD_ASCII = "OTA-DL".toByteArray(Charsets.US_ASCII)

    /** Computes the SHA-256 digest of [data] with [java.security.MessageDigest]. */
    fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * Computes `HMAC-SHA256(secret, "OTA-BEGIN" || nonce || size_le32 || sha256)`.
     *
     * @param secret the device's 32-byte OTA secret.
     * @param nonce the 16 bytes handed out by the HELLO response.
     * @param imageSize image size in bytes, serialised little-endian u32.
     * @param imageSha256 the image digest that was put into the BEGIN packet.
     */
    fun beginMac(
        secret: ByteArray,
        nonce: ByteArray,
        imageSize: Long,
        imageSha256: ByteArray,
    ): ByteArray = hmac(
        secret,
        SOURCE_ASCII +
            nonce +
            byteArrayOf(
                (imageSize and 0xFF).toByte(),
                ((imageSize ushr 8) and 0xFF).toByte(),
                ((imageSize ushr 16) and 0xFF).toByte(),
                ((imageSize ushr 24) and 0xFF).toByte(),
            ) +
            imageSha256,
    )

    /** Computes `HMAC-SHA256(secret, "OTA-DL" || nonce)` for a BOOTLOADER command. */
    fun bootloaderMac(secret: ByteArray, nonce: ByteArray): ByteArray =
        hmac(secret, DOWNLOAD_ASCII + nonce)

    private fun hmac(secret: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
