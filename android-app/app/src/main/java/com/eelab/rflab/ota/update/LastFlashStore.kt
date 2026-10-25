package com.eelab.rflab.ota.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Remembers which image was last flashed to each unit through "Update to
 * latest" (or the manual picker), so a later update check can decide
 * up-to-date vs update-available without asking the device.
 *
 * The OTA protocol's responses carry no running-image hash (HELLO answers
 * caps/max_chunk/slot_size/nonce only), which is why this record is the only
 * baseline available; it is written only after the engine reached DONE, i.e.
 * the device accepted and is rebooting into the new image.
 */
interface LastFlashStore {

    /** The SHA-256 (lowercase hex) last flashed to [unit], or null. */
    fun load(unit: FirmwareUnit): String?

    /** Records [sha256Hex] as the image last flashed to [unit]. */
    fun save(unit: FirmwareUnit, sha256Hex: String)
}

/**
 * SharedPreferences-backed implementation of [LastFlashStore], one key per
 * unit in the dedicated `ota_last_flash` file (same storage rules as the
 * other firmware settings: commit so a process kill never loses the record).
 */
class SharedPreferencesLastFlashStore(
    private val preferences: SharedPreferences,
) : LastFlashStore {

    override fun load(unit: FirmwareUnit): String? =
        preferences.getString(keyFor(unit), null)

    override fun save(unit: FirmwareUnit, sha256Hex: String) {
        val committed = preferences.edit()
            .putString(keyFor(unit), sha256Hex.lowercase())
            .commit()
        if (!committed) {
            Log.w(TAG, "Failed to persist the last flashed hash for ${unit.name}")
        }
    }

    companion object {

        private const val TAG = "LastFlashStore"

        /** Dedicated prefs file. */
        const val PREFERENCES_NAME = "ota_last_flash"

        /** Creates storage backed by the app's private `ota_last_flash` prefs. */
        fun create(context: Context): SharedPreferencesLastFlashStore =
            SharedPreferencesLastFlashStore(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        /** The prefs key for a unit's last flashed hash. */
        fun keyFor(unit: FirmwareUnit): String = "last_flash_${unit.name}"
    }
}
