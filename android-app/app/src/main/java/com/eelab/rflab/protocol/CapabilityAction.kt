package com.eelab.rflab.protocol

/**
 * Where a [ProtocolCapability]'s primary action should take the user, if
 * anywhere. Deliberately framework-free (no Compose/`NavController`
 * dependency) so this stays the single source of truth for "does this
 * capability have a real action, and which one" — the UI layer only maps
 * each case to a route (see `ui.capabilityRoute`), it does not decide which
 * capabilities get a button.
 *
 * [NONE] covers two different reasons, both honest: a capability may be
 * present in source but deliberately not connected to any screen (the
 * manufacturer-learning rows), or not implemented at all. Either way, [NONE]
 * means "no button" — never a disabled button with no explanation, never a
 * button to a screen that doesn't actually do this.
 */
enum class CapabilityAction {
    NONE,
    OPEN_CONNECT,
    OPEN_RECEIVE,
    OPEN_SEND,
    OPEN_KEYS,
    OPEN_STATIC_CODES,
}
