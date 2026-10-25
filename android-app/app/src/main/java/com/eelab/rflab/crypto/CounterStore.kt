package com.eelab.rflab.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistence seam for [CounterTracker]: the highest decrypted sync counter
 * seen for each serial, so "send the next code" can default to last + 1 across
 * app restarts.
 *
 * Production uses [SharedPreferencesCounterStore] (app-private); JVM tests
 * substitute an in-memory fake. This stores only counters keyed by serial — no
 * key material.
 */
interface CounterStore {

    /** Map of serial -> highest counter observed. Empty when nothing seen. */
    fun loadAll(): Map<Long, Int>

    /** Replaces the stored copy; called after every change. */
    fun saveAll(counters: Map<Long, Int>)
}

/** [CounterStore] backed by an app-private SharedPreferences file. */
class SharedPreferencesCounterStore(
    private val preferences: SharedPreferences,
) : CounterStore {

    override fun loadAll(): Map<Long, Int> {
        val raw = preferences.getString(KEY_COUNTERS, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            buildMap {
                for (serialKey in root.keys()) {
                    // Keys are serials as decimal strings; values are counters.
                    val serial = serialKey.toLongOrNull() ?: continue
                    put(serial, root.getInt(serialKey))
                }
            }
        } catch (error: JSONException) {
            Log.w(TAG, "Discarding corrupt counter document", error)
            emptyMap()
        }
    }

    override fun saveAll(counters: Map<Long, Int>) {
        val root = JSONObject()
        for ((serial, counter) in counters) {
            root.put(serial.toString(), counter)
        }
        val committed = preferences.edit().putString(KEY_COUNTERS, root.toString()).commit()
        if (!committed) {
            Log.w(TAG, "Failed to persist ${counters.size} counter(s)")
        }
    }

    companion object {
        const val PREFERENCES_NAME = "keeloq_counters"
        const val KEY_COUNTERS = "counters_json"
        private const val TAG = "CounterStore"

        fun create(context: Context): SharedPreferencesCounterStore =
            SharedPreferencesCounterStore(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
    }
}
