package com.quickshare.app.data

import android.content.Context
import com.quickshare.core.data.HistoryStore
import com.quickshare.core.util.Logs

/**
 * [HistoryStore] backed by [android.content.SharedPreferences].
 *
 * History is a single JSON string: it is one ordered list that is always read and written whole, so
 * per-key storage would add nothing and make the trim-on-write logic harder to reason about.
 */
class PrefsHistoryStore(context: Context, name: String) : HistoryStore {

    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun load(): String? = try {
        prefs.getString(KEY_HISTORY, null)
    } catch (e: Exception) {
        Logs.w(TAG, "Could not read transfer history", e)
        null
    }

    override fun save(json: String) {
        try {
            prefs.edit().putString(KEY_HISTORY, json).apply()
        } catch (e: Exception) {
            Logs.w(TAG, "Could not write transfer history", e)
            throw e
        }
    }

    private companion object {
        const val TAG = "PrefsHistoryStore"
        const val KEY_HISTORY = "transfer_history_json"
    }
}
