package com.eelab.rflab.ota.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists the firmware web server settings typed on the Firmware screen.
 *
 * Follows `com.eelab.rflab.ota.OtaSecretStore` exactly: a dedicated
 * app-private SharedPreferences file ("firmware_server"), values written with
 * [SharedPreferences.Editor.commit] so a process death never loses them. The
 * password is stored in plain app-private storage like the OTA secret — no
 * worse, no logs, no resources, no git — and the UX keeps it masked with a
 * show/hide toggle like the secret field.
 */
class FirmwareServerSettingsStore(private val preferences: SharedPreferences) {

    /** The persisted settings (user name defaults to `flash` when absent). */
    fun load(): FirmwareServerSettings = FirmwareServerSettings(
        baseUrl = preferences.getString(KEY_BASE_URL, null) ?: "",
        username = preferences.getString(KEY_USERNAME, null)
            ?: FirmwareServerRules.DEFAULT_USERNAME,
        password = preferences.getString(KEY_PASSWORD, null) ?: "",
    )

    /** Replaces the stored settings wholesale. */
    fun save(settings: FirmwareServerSettings) {
        val committed = preferences.edit()
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_USERNAME, settings.username)
            .putString(KEY_PASSWORD, settings.password)
            .commit()
        if (!committed) {
            Log.w(TAG, "Failed to persist the firmware server settings")
        }
    }

    companion object {

        private const val TAG = "FirmwareServerSettingsStore"

        /** Dedicated prefs file, independent of the `ota` secret file. */
        const val PREFERENCES_NAME = "firmware_server"

        private const val KEY_BASE_URL = "server_base_url"

        private const val KEY_USERNAME = "server_username"

        private const val KEY_PASSWORD = "server_password"

        /** Creates storage backed by the app's private `firmware_server` prefs. */
        fun create(context: Context): FirmwareServerSettingsStore =
            FirmwareServerSettingsStore(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
    }
}
