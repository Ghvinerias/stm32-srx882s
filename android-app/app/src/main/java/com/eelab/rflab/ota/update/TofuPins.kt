package com.eelab.rflab.ota.update

/**
 * Outcome of comparing a server certificate against the pinned fingerprint
 * (the trust-on-first-use decision, pure Kotlin and JVM-tested).
 *
 * The decision is intentionally separate from the `X509TrustManager` that
 * enforces it inside the TLS handshake, so every rule runs in unit tests
 * without sockets or certificates.
 */
sealed interface PinDecision {

    /** A pin exists and one certificate in the chain matches it. */
    data object Accept : PinDecision

    /**
     * No pin exists yet for that server: the connection must not be trusted
     * until the user has seen the certificate's fingerprint and confirmed it.
     */
    data class FirstUse(val fingerprint: String) : PinDecision

    /**
     * A pin exists but no certificate in the chain matches it — the server's
     * certificate changed (renewal, re-provisioning, or interception). The
     * old pin must be cleared explicitly ("reset trust") before trusting
     * again; failing loudly is the whole point of the pin.
     */
    data class Mismatch(val pinned: String, val observed: String) : PinDecision
}

/**
 * Fingerprint helpers: the SHA-256 trust-on-first-use rules shared by the
 * trust manager and the pin store.
 */
object TofuPins {

    /**
     * Compares [pinnedSha256] (the fingerprint persisted for the base URL, or
     * null on first use) against the SHA-256 fingerprints of the observed
     * certificate chain, leaf first.
     *
     * @throws IllegalArgumentException when [observed] has no certificates —
     *   callers (the trust manager) reject an empty chain before deciding.
     */
    fun decide(pinnedSha256: String?, observed: List<String>): PinDecision {
        require(observed.isNotEmpty()) { "a certificate chain is required" }

        if (pinnedSha256 == null) {
            return PinDecision.FirstUse(fingerprint = observed.first().lowercase())
        }
        val matched = observed.firstOrNull { it.equals(pinnedSha256, ignoreCase = true) }
        return if (matched != null) {
            PinDecision.Accept
        } else {
            PinDecision.Mismatch(
                pinned = pinnedSha256.lowercase(),
                observed = observed.first().lowercase(),
            )
        }
    }

    /**
     * The fingerprint for display, lowercase hex grouped in pairs ("aa bb …")
     * so the user can compare it against a printed or pass-along value.
     */
    fun formatFingerprint(sha256Hex: String): String =
        sha256Hex.lowercase().chunked(2).joinToString(" ")

    /** The short hash shown next to "Update available" (first 8 hex chars). */
    fun shortHash(sha256Hex: String): String = sha256Hex.take(SHORT_HASH_LENGTH)

    private const val SHORT_HASH_LENGTH = 8
}
