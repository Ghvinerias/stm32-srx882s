package com.eelab.rflab.ota.update

/**
 * The firmware web server connection settings typed on the Firmware screen.
 *
 * The password lives here only as long as it takes to reach the committed
 * SharedPreferences store (`FirmwareServerSettingsStore`), which — like the
 * OTA secret in `com.eelab.rflab.ota.OtaSecretStore` — is a dedicated
 * app-private file written with `commit()`. It is never logged, never
 * embedded in the APK, and never put in a URL.
 */
data class FirmwareServerSettings(
    /** Base URL, e.g. `https://10.10.10.24:8443` (no trailing slash). */
    val baseUrl: String,
    /** HTTP Basic user name (default `flash`, matching `serve_flash.py`). */
    val username: String = FirmwareServerRules.DEFAULT_USERNAME,
    /** HTTP Basic password (the one in `~/.keeloq-flash-pass` on the server). */
    val password: String,
) {

    /** URL without a trailing slash, for building request paths. */
    val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')

    companion object {

        /** A settings snapshot with the default user and empty credentials. */
        val EMPTY = FirmwareServerSettings(
            baseUrl = "",
            username = FirmwareServerRules.DEFAULT_USERNAME,
            password = "",
        )
    }
}

/**
 * Validation rules for the server settings (JVM-tested; no Android types).
 *
 * Only `https://` URLs are accepted, on purpose: the server is TLS-only, and
 * images that carry the baked-in OTA secret plus the Basic-auth password must
 * never travel in the clear. Non-TLS addresses are rejected with an
 * explanation rather than silently allowed.
 */
object FirmwareServerRules {

    /** Default Basic-auth user, matching `serve_flash.py`. */
    const val DEFAULT_USERNAME = "flash"

    /** Shown as the URL field's placeholder, matching the lab's usual host. */
    const val URL_PLACEHOLDER = "https://10.10.10.24:8443"

    /**
     * The validation error for a base URL as typed, or null when it can be
     * used. A blank value is an error here because actions need an address;
     * the URL field only surfaces this message when an action runs (see the
     * ViewModel, which keeps the field quiet while the user types).
     */
    fun baseUrlError(text: String): String? = when {
        text.isBlank() ->
            "Enter the update server address (e.g. $URL_PLACEHOLDER)"

        text.trim().startsWith("http://") ->
            "HTTP is not allowed — use https:// so the image and password are " +
                "never sent in the clear"

        SCHEME_AND_HOST.matchEntire(text.trim()) == null ->
            "Enter the server address as https://<host>:<port> (e.g. $URL_PLACEHOLDER)"

        else -> null
    }

    /** The validation error for a username, or null when one is present. */
    fun usernameError(text: String): String? =
        if (text.isBlank()) "The server user name is required" else null

    /** The validation error for a password, or null when one is present. */
    fun passwordError(text: String): String? =
        if (text.isEmpty()) "Enter the flash server password" else null

    private val SCHEME_AND_HOST = Regex("https://\\S+")
}
