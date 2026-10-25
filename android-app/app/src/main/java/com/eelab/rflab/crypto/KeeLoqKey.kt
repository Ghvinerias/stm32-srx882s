package com.eelab.rflab.crypto

/**
 * One user-entered KeeLoq key, for a system the user owns.
 *
 * A key is a raw 64-bit value the user already possesses (e.g. the device key
 * of their own receiver, or a manufacturer key they are entitled to use). The
 * app never derives a key from a captured signal and ships none; keys only
 * come from here. Multiple keys are kept so the user can hold one per system
 * and pick the active one per operation.
 *
 * @property id stable identifier, assigned by [KeeLoqKeyRepository].
 * @property name user-facing label, e.g. "Garage" or "Front gate".
 * @property key the 64-bit KeeLoq key.
 */
data class KeeLoqKey(
    val id: Long,
    val name: String,
    val key: Long,
)
