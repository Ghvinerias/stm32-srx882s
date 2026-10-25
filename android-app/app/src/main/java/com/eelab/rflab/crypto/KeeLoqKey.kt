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
 * [purpose] distinguishes the two kinds this docstring always allowed but
 * the type didn't track until a manufacturer-key consumer ([KeeLoqLearning])
 * existed: a [KeyPurpose.DEVICE] key is for the cipher/hop code directly; a
 * [KeyPurpose.MANUFACTURER] key is only an input to [KeeLoqLearning]. It is a
 * labeling hint against accidental mix-up, not an authorization check — see
 * [KeyPurpose]'s doc comment.
 *
 * @property id stable identifier, assigned by [KeeLoqKeyRepository].
 * @property name user-facing label, e.g. "Garage" or "Front gate".
 * @property key the 64-bit KeeLoq key.
 * @property purpose what [key] is for; defaults to [KeyPurpose.DEVICE] so
 *   every key entered before this field existed is read back unchanged.
 */
data class KeeLoqKey(
    val id: Long,
    val name: String,
    val key: Long,
    val purpose: KeyPurpose = KeyPurpose.DEVICE,
)
