package com.eelab.rflab.ota

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists the typed OTA secret in its own SharedPreferences file.
 *
 * Storage mechanism follows [com.eelab.rflab.favorites.SharedPreferencesFavoriteStorage]:
 * a dedicated app-private prefs file ("ota"), one string key, and
 * [SharedPreferences.Editor.commit] (not `apply()`) so the value is on disk
 * before returning — the user should never have to retype the secret after a
 * process kill.
 *
 * The secret is stored as the typed 64-character hex string and only entered
 * into ui state after it validates ([OtaSecrets.isWellFormed]); the field is
 * masked on screen (password visual transformation), so the stored value is
 * no more exposed than before.
 */
class OtaSecretStore(private val preferences: SharedPreferences) {

    /** The persisted secret hex, or "" when none was stored yet. */
    fun load(): String = preferences.getString(KEY_SECRET, null) ?: ""

    /** Replaces the stored secret with the validated [hex] string. */
    fun save(hex: String) {
        val committed = preferences.edit()
            .putString(KEY_SECRET, hex)
            .commit()
        if (!committed) {
            Log.w(TAG, "Failed to persist the OTA secret")
        }
    }

    companion object {
        private const val TAG = "OtaSecretStore"

        /** Dedicated prefs file, independent of favorites or future settings. */
        const val PREFERENCES_NAME = "ota"

        /** Single key holding the validated 64-hex-character secret. */
        const val KEY_SECRET = "ota_secret_hex"

        /** Creates storage backed by the app's private `ota` prefs file. */
        fun create(context: Context): OtaSecretStore =
            OtaSecretStore(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
    }
}

/**
 * Pure-Kotlin helpers for the 64-hex-character secret; separated from
 * [OtaSecretStore] so parsing/validation is unit-testable without Android.
 */
object OtaSecrets {

    private val VALID_SECRET_HEX = Regex("^[0-9a-fA-F]{64}$")

    /** Validation error for [text], or null when it is a well-formed secret. */
    fun validationError(text: String): String? = when {
        text.isEmpty() -> "The OTA secret is required"
        !text.all { it.isHexDigit() } -> "The OTA secret must be hex (0-9, a-f)"
        text.length != 64 -> "The OTA secret must be exactly 64 hex characters " +
            "(32 bytes), got ${text.length}"

        else -> null
    }

    /** True when [text] parses as exactly 32 bytes of hex. */
    fun isWellFormed(text: String): Boolean = validationError(text) == null

    /**
     * Decodes a validated hex secret to its 32 bytes.
     *
     * @throws IllegalArgumentException when [text] is malformed — callers
     *   validate first via [isWellFormed] so this is a programming-error trap.
     */
    fun decode(text: String): ByteArray {
        require(isWellFormed(text)) { "not a well-formed 64-hex secret" }
        return text.chunked(2).map { token ->
            token.toInt(16).toByte()
        }.toByteArray()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
