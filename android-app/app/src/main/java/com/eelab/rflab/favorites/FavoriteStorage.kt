package com.eelab.rflab.favorites

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.eelab.rflab.queue.SendQueueItem
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistence seam for [FavoritesRepository].
 *
 * Production uses [SharedPreferencesFavoriteStorage]; JVM tests substitute an
 * in-memory fake so the repository's overwrite/delete/restart behaviour can be
 * exercised without Android's SharedPreferences stubs.
 */
interface FavoriteStorage {

    /** Every stored favorite, in display order. Empty when none were saved. */
    fun loadAll(): List<FavoriteQueue>

    /** Replaces the stored copy; called after every repository mutation. */
    fun saveAll(favorites: List<FavoriteQueue>)
}

/**
 * [FavoriteStorage] backed by a dedicated SharedPreferences file.
 *
 * The whole list is one JSON document under [KEY_FAVORITES] in the prefs file
 * [PREFERENCES_NAME]. A single string keeps writes atomic (one key) and makes
 * the on-disk format easy to evolve via [KEY_VERSION].
 *
 * Writes use [SharedPreferences.Editor.commit] rather than `apply()`: saves
 * happen only on an explicit user action, and commit guarantees the bytes are
 * on disk before returning. That is what makes favorites survive a process
 * kill immediately after saving, which is the point of this feature.
 *
 * A missing document is an empty list. A corrupt document is logged and also
 * treated as empty rather than crashing the Send screen.
 */
class SharedPreferencesFavoriteStorage(
    private val preferences: SharedPreferences,
) : FavoriteStorage {

    override fun loadAll(): List<FavoriteQueue> = decode(
        preferences.getString(KEY_FAVORITES, null),
    )

    override fun saveAll(favorites: List<FavoriteQueue>) {
        val committed = preferences.edit()
            .putString(KEY_FAVORITES, encode(favorites))
            .commit()
        if (!committed) {
            Log.w(TAG, "Failed to persist ${favorites.size} favorite(s)")
        }
    }

    private fun encode(favorites: List<FavoriteQueue>): String {
        val root = JSONObject()
        root.put(KEY_VERSION, FORMAT_VERSION)

        val favoriteArray = JSONArray()
        for (favorite in favorites) {
            val itemArray = JSONArray()
            for (item in favorite.items) {
                itemArray.put(
                    JSONObject()
                        .put(KEY_HOPPING, item.hopping)
                        .put(KEY_SERIAL, item.serial)
                        .put(KEY_BUTTON, item.button)
                        .put(KEY_FRAME_COUNT, item.frameCount),
                )
            }
            favoriteArray.put(
                JSONObject()
                    .put(KEY_ID, favorite.id)
                    .put(KEY_NAME, favorite.name)
                    .put(KEY_ITEMS, itemArray),
            )
        }
        root.put(KEY_FAVORITES, favoriteArray)
        return root.toString()
    }

    private fun decode(raw: String?): List<FavoriteQueue> {
        if (raw.isNullOrBlank()) return emptyList()

        return try {
            val root = JSONObject(raw)
            val favoriteArray = root.optJSONArray(KEY_FAVORITES) ?: return emptyList()
            buildList {
                for (index in 0 until favoriteArray.length()) {
                    val favoriteObject = favoriteArray.optJSONObject(index) ?: continue
                    val itemArray = favoriteObject.optJSONArray(KEY_ITEMS) ?: JSONArray()
                    val items = buildList {
                        for (itemIndex in 0 until itemArray.length()) {
                            val itemObject = itemArray.optJSONObject(itemIndex) ?: continue
                            add(
                                FavoriteItem(
                                    hopping = itemObject.optLong(KEY_HOPPING),
                                    serial = itemObject.optLong(KEY_SERIAL),
                                    button = itemObject.optInt(KEY_BUTTON),
                                    frameCount = itemObject.optInt(
                                        KEY_FRAME_COUNT,
                                        SendQueueItem.DEFAULT_FRAME_COUNT,
                                    ),
                                ),
                            )
                        }
                    }
                    add(
                        FavoriteQueue(
                            id = favoriteObject.getLong(KEY_ID),
                            name = favoriteObject.getString(KEY_NAME),
                            items = items,
                        ),
                    )
                }
            }
        } catch (error: JSONException) {
            Log.w(TAG, "Discarding corrupt favorites document", error)
            emptyList()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Discarding favorites with out-of-range fields", error)
            emptyList()
        }
    }

    companion object {
        /** Dedicated prefs file, independent of any future app settings. */
        const val PREFERENCES_NAME = "favorites"

        /** Single key holding the serialized list of favorites. */
        const val KEY_FAVORITES = "favorites_json"

        private const val KEY_VERSION = "version"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_ITEMS = "items"
        private const val KEY_HOPPING = "hopping"
        private const val KEY_SERIAL = "serial"
        private const val KEY_BUTTON = "button"
        private const val KEY_FRAME_COUNT = "frameCount"

        /** Bump if the JSON shape changes; no migrations exist yet. */
        private const val FORMAT_VERSION = 1

        private const val TAG = "FavoriteStorage"

        /** Creates a storage backed by the app's private `favorites` prefs file. */
        fun create(context: Context): SharedPreferencesFavoriteStorage =
            SharedPreferencesFavoriteStorage(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
    }
}
