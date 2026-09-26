package com.anony.bro.wser.data.track

import android.content.Context
import androidx.core.content.edit

/**
 * 基于 SharedPreferences 的持久化实现（Android 上等价于 Web 的 localStorage）。
 * 每个平台各占一个 key，存储序列化后的 JSON 数组字符串。
 */
class SharedPrefsTrackEventPersistence(
    context: Context,
) : TrackEventPersistence {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(platform: TrackPlatform): String? =
        prefs.getString(keyOf(platform), null)

    override fun write(platform: TrackPlatform, json: String) {
        prefs.edit { putString(keyOf(platform), json) }
    }

    private fun keyOf(platform: TrackPlatform): String = KEY_PREFIX + platform.id

    companion object {
        private const val PREFS_NAME = "track_event_cache"
        private const val KEY_PREFIX = "queue_"
    }
}
