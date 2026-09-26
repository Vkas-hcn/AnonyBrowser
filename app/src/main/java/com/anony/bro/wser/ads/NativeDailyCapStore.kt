package com.anony.bro.wser.ads

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NativeDailyCapStore {
    private const val PREF_NAME = "native_daily_cap"
    private const val KEY_DAY = "day"
    private const val KEY_LIST_COUNT = "count_native_list"
    private const val KEY_COMMON_COUNT = "count_native_common"

    fun canLoadOrShow(context: Context?, slot: String, dailyLimit: Int): Boolean {
        if (dailyLimit <= 0) return true
        if (context == null) return true
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        ensureDay(prefs)
        return getCount(prefs, slot) < dailyLimit
    }

    fun onPresented(context: Context?, slot: String, dailyLimit: Int) {
        if (dailyLimit <= 0) return
        if (context == null) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        ensureDay(prefs)
        val next = getCount(prefs, slot) + 1
        prefs.edit().putInt(resolveSlotKey(slot), next).apply()
    }

    fun getTodayCount(context: Context?, slot: String): Int {
        if (context == null) return 0
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        ensureDay(prefs)
        return getCount(prefs, slot)
    }

    private fun ensureDay(prefs: android.content.SharedPreferences) {
        val today = dayStamp()
        val saved = prefs.getString(KEY_DAY, null)
        if (saved == today) return
        prefs.edit()
            .putString(KEY_DAY, today)
            .putInt(KEY_LIST_COUNT, 0)
            .putInt(KEY_COMMON_COUNT, 0)
            .apply()
    }

    private fun getCount(prefs: android.content.SharedPreferences, slot: String): Int {
        return prefs.getInt(resolveSlotKey(slot), 0)
    }

    private fun resolveSlotKey(slot: String): String {
        return when (slot) {
            "native_list" -> KEY_LIST_COUNT
            "native_common" -> KEY_COMMON_COUNT
            else -> KEY_LIST_COUNT
        }
    }

    private fun dayStamp(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }
}
