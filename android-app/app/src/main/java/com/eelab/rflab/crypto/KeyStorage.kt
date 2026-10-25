package com.eelab.rflab.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistence seam for [KeeLoqKeyRepository].
 *
 * Production uses [SharedPreferencesKeyStorage], an app-private prefs file
 * (the same sandboxed storage the OTA secret already uses). JVM tests
 * substitute an in-memory fake.
 */
interface KeyStorage {

    /** Every stored key, in display order. Empty when none were saved. */
    fun loadAll(): List<KeeLoqKey>

    /** Replaces the stored copy; called after every repository mutation. */
    fun saveAll(keys: List<KeeLoqKey>)
}

/**
 * [KeyStorage] backed by a dedicated, app-private SharedPreferences file.
 *
 * The whole list is one JSON document. Writes use `commit()` so a key the user
 * just entered survives an immediate process kill. A corrupt document is
 * logged and treated as empty rather than crashing.
 *
 * App-private `MODE_PRIVATE` storage is readable only by this app's UID; it is
 * not world-readable. Keys are kept only on the phone and are never sent to a
 * board or written into the repo.
 */
class SharedPreferencesKeyStorage(
    private val preferences: SharedPreferences,
) : KeyStorage {

    override fun loadAll(): List<KeeLoqKey> = decode(preferences.getString(KEY_KEYS, null))

    override fun saveAll(keys: List<KeeLoqKey>) {
        val committed = preferences.edit().putString(KEY_KEYS, encode(keys)).commit()
        if (!committed) {
            Log.w(TAG, "Failed to persist ${keys.size} key(s)")
        }
    }

    private fun encode(keys: List<KeeLoqKey>): String {
        val root = JSONObject()
        root.put(KEY_VERSION, FORMAT_VERSION)
        val array = JSONArray()
        for (k in keys) {
            array.put(
                JSONObject()
                    .put(KEY_ID, k.id)
                    .put(KEY_NAME, k.name)
                    // Stored as a hex string so the full 64-bit value round-trips
                    // without JSON number/precision surprises.
                    .put(KEY_VALUE, java.lang.Long.toHexString(k.key)),
            )
        }
        root.put(KEY_KEYS, array)
        return root.toString()
    }

    private fun decode(raw: String?): List<KeeLoqKey> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(raw)
            val array = root.optJSONArray(KEY_KEYS) ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        KeeLoqKey(
                            id = o.getLong(KEY_ID),
                            name = o.getString(KEY_NAME),
                            key = java.lang.Long.parseUnsignedLong(o.getString(KEY_VALUE), 16),
                        ),
                    )
                }
            }
        } catch (error: JSONException) {
            Log.w(TAG, "Discarding corrupt key document", error)
            emptyList()
        } catch (error: NumberFormatException) {
            Log.w(TAG, "Discarding key with an unparseable value", error)
            emptyList()
        }
    }

    companion object {
        const val PREFERENCES_NAME = "keeloq_keys"
        const val KEY_KEYS = "keys_json"
        private const val KEY_VERSION = "version"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_VALUE = "value_hex"
        private const val FORMAT_VERSION = 1
        private const val TAG = "KeyStorage"

        fun create(context: Context): SharedPreferencesKeyStorage =
            SharedPreferencesKeyStorage(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
    }
}
