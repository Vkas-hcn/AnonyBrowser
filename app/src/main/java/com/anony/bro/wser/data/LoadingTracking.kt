package com.anony.bro.wser.data

import android.content.Context
import androidx.core.content.edit

object LoadingTracking {
    const val EVENT = "loading_show"
    const val SOURCE_SYSTEM = "system_notification"
    const val SOURCE_FCM = "fcm_notification"
    const val SOURCE_OL = "ol_notification"
    const val SOURCE_OTHER = "other"
    const val KEY_SOURCE = "source"
    const val KEY_APP_FIRST_OPEN = "app_first_open"
    const val EXTRA_FROM_FCM = "extra_from_fcm"
    const val EXTRA_FROM_SYSTEM = "extra_from_system_notification"

    private const val PREFS_NAME = "loading_tracking"
    private const val KEY_HAS_OPENED = "has_opened"

    fun resolveSource(
        fromFcm: Boolean,
        fromSystemNotification: Boolean,
    ): String = when {
        fromFcm -> SOURCE_FCM
        fromSystemNotification -> SOURCE_SYSTEM
        else -> SOURCE_OTHER
    }

    fun contextParams(source: String, firstOpen: Boolean): Map<String, String> = mapOf(
        KEY_SOURCE to source,
        KEY_APP_FIRST_OPEN to firstOpen.toString(),
    )

    fun consumeIsFirstOpen(alreadyOpened: Boolean): Boolean = !alreadyOpened

    fun consumeIsFirstOpen(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val firstOpen = consumeIsFirstOpen(prefs.getBoolean(KEY_HAS_OPENED, false))
        if (firstOpen) {
            prefs.edit { putBoolean(KEY_HAS_OPENED, true) }
        }
        return firstOpen
    }
}
