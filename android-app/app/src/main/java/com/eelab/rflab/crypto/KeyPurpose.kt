package com.eelab.rflab.crypto

/**
 * What a stored [KeeLoqKey] is for.
 *
 * This is a labeling/misuse-prevention hint, not an authorization control:
 * the app has no way to verify that the user holding a key of either kind
 * is actually entitled to it. It exists so a [DEVICE] key (used directly
 * with [com.eelab.rflab.decode.KeeLoqCipher]/[KeeLoqHop] to talk to one
 * receiver) and a [MANUFACTURER] key (an input to [KeeLoqLearning], never
 * itself fed to the cipher) cannot be silently swapped for each other by
 * code that forgot which kind it was holding.
 */
enum class KeyPurpose {
    /** A device's own 64-bit KeeLoq key — usable directly for hop decrypt/encode. */
    DEVICE,

    /**
     * A manufacturer master key — an input to [KeeLoqLearning]'s derivation
     * functions, not a value the cipher or hop code should ever receive
     * directly.
     */
    MANUFACTURER,
}

/**
 * What a key of this [KeyPurpose], if selected, is actually used for by the
 * app's existing receive/decode/send paths — shown next to the selected key
 * on the Keys screen so the connection between "this key is active" and
 * "these screens use it" isn't left for the user to infer.
 *
 * A [KeyPurpose.MANUFACTURER] key names [KeeLoqLearning] here only as a fact
 * about what that *kind* of key is for; it is not a path through this app —
 * [KeeLoqLearning] stays isolated from the UI (see
 * `KeeLoqLearningIsolationTest`).
 */
fun KeyPurpose.usageDescription(): String = when (this) {
    KeyPurpose.DEVICE ->
        "Used directly: Receive decrypts this remote's hop counter with it, " +
            "and Send's \"next code\" encrypts with it for this receiver."
    KeyPurpose.MANUFACTURER ->
        "Not used by Receive or Send. A manufacturer key is only an input to " +
            "the (UI-disconnected) key-derivation utility, not a value the " +
            "cipher or hop code ever takes directly."
}
