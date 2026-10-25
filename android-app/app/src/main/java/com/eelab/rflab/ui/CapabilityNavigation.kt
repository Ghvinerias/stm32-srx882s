package com.eelab.rflab.ui

import com.eelab.rflab.protocol.CapabilityAction

/**
 * The one place [CapabilityAction] values are turned into bottom-nav routes.
 *
 * This is wiring, not a support-status decision: whether a capability *has*
 * an action at all is decided once, in `ProtocolCapabilities.kt`. This
 * function only says which existing screen each possible action opens, so
 * that decision isn't duplicated (and risking drift) in every place that
 * renders a capability action.
 *
 * @return the route to navigate to, or null for [CapabilityAction.NONE].
 */
fun CapabilityAction.route(): String? = when (this) {
    CapabilityAction.NONE -> null
    CapabilityAction.OPEN_CONNECT -> Destination.Connect.route
    CapabilityAction.OPEN_RECEIVE -> Destination.Receive.route
    CapabilityAction.OPEN_SEND -> Destination.Send.route
    CapabilityAction.OPEN_KEYS -> Destination.Keys.route
    // Not a bottom-nav tab, same as the protocol-detail route: reachable
    // from a protocol-detail action button, not from the nav bar.
    CapabilityAction.OPEN_STATIC_CODES -> STATIC_CODES_ROUTE
}

/** Display label for the button a [CapabilityAction] gets, or null for [CapabilityAction.NONE]. */
fun CapabilityAction.label(): String? = when (this) {
    CapabilityAction.NONE -> null
    CapabilityAction.OPEN_CONNECT -> "Open Connect"
    CapabilityAction.OPEN_RECEIVE -> "Open Receive"
    CapabilityAction.OPEN_SEND -> "Open Send"
    CapabilityAction.OPEN_KEYS -> "Open Keys"
    CapabilityAction.OPEN_STATIC_CODES -> "Open Static Codes"
}

/** The route [RfLabApp] registers [com.eelab.rflab.ui.screens.StaticCodesScreen] under. */
const val STATIC_CODES_ROUTE = "static-codes"
