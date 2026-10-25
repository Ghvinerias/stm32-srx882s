package com.eelab.rflab.ota.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists the TLS trust-on-first-use pin: the SHA-256 of the server
 * certificate the user confirmed, keyed per base URL.
 *
 * Storage follows the app's other secrets (`com.eelab.rflab.ota.OtaSecretStore`):
 * a dedicated app-private SharedPreferences file written with
 * [SharedPreferences.Editor.commit]. The pinned fingerprint is not secret
 * (it is shown to the user in the trust dialog), but it is security state and
 * must survive process death exactly.
 */
interface TofuPinStore {

    /** The pinned fingerprint for [baseUrl], lowercase hex, or null (no pin yet). */
    fun pinnedFor(baseUrl: String): String?

    /** Records [fingerprintSha256] as the pin for [baseUrl]. */
    fun pin(baseUrl: String, fingerprintSha256: String)

    /** Drops the pin: the next connection becomes a first use again. */
    fun clear(baseUrl: String)
}

/**
 * SharedPreferences-backed implementation of [TofuPinStore].
 *
 * The key for a base URL is a deterministic hex escaping of the URL (plain
 * `:` and `.` in string keys have surprised XML round-trips before), so no
 * two URLs collide and the mapping is stable across versions.
 */
class SharedPreferencesTofuPinStore(
    private val preferences: SharedPreferences,
) : TofuPinStore {

    override fun pinnedFor(baseUrl: String): String? =
        preferences.getString(keyFor(baseUrl), null)

    override fun pin(baseUrl: String, fingerprintSha256: String) {
        val committed = preferences.edit()
            .putString(keyFor(baseUrl), fingerprintSha256.lowercase())
            .commit()
        if (!committed) {
            Log.w(TAG, "Failed to persist the TLS pin for $baseUrl")
        }
    }

    override fun clear(baseUrl: String) {
        val committed = preferences.edit()
            .remove(keyFor(baseUrl))
            .commit()
        if (!committed) {
            Log.w(TAG, "Failed to clear the TLS pin for $baseUrl")
        }
    }

    companion object {

        private const val TAG = "TofuPinStore"

        /** Dedicated prefs file, independent of the other settings stores. */
        const val PREFERENCES_NAME = "firmware_tls"

        /** Creates storage backed by the app's private `firmware_tls` prefs. */
        fun create(context: Context): SharedPreferencesTofuPinStore =
            SharedPreferencesTofuPinStore(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        /**
         * The prefs key for [baseUrl]: every non-alphanumeric byte becomes
         * `_xx_` with its 2-position hex code, so URLs differing anywhere
         * (scheme, host, port, path) map to different keys.
         */
        fun keyFor(baseUrl: String): String = buildString {
            append("pin_")
            for (char in baseUrl) {
                if (char.isLetterOrDigit()) {
                    append(char)
                } else {
                    append('_')
                    append(char.code.toString(16))
                    append('_')
                }
            }
        }
    }
}
