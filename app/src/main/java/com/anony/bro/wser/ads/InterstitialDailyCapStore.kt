package com.anony.bro.wser.ads

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InterstitialDailyCapStore {
    private const val PREF_NAME = "interstitial_daily_cap"
    private const val KEY_DAY = "day"
    private const val KEY_COUNT = "count_interstitial"
    private const val DEFAULT_SLOT = "interstitial"

    fun canLoadOrShow(
        context: Context?,
        dailyLimit: Int,
        slot: String = DEFAULT_SLOT,
    ): Boolean {
        if (dailyLimit <= 0) return true
        if (context == null) return true
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        ensureDay(prefs, slot)
        return prefs.getInt(countKey(slot), 0) < dailyLimit
    }

    fun getTodayCount(context: Context?, slot: String = DEFAULT_SLOT): Int {
        if (context == null) return 0
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        ensureDay(prefs, slot)
        return prefs.getInt(countKey(slot), 0)
    }

    fun onPresented(
        context: Context?,
        dailyLimit: Int,
        slot: String = DEFAULT_SLOT,
    ) {
        if (dailyLimit <= 0) return
        if (context == null) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        ensureDay(prefs, slot)
        val next = prefs.getInt(countKey(slot), 0) + 1
        prefs.edit().putInt(countKey(slot), next).apply()
    }

    private fun ensureDay(prefs: android.content.SharedPreferences, slot: String) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val saved = prefs.getString(dayKey(slot), null)
        if (saved == today) return
        prefs.edit()
            .putString(dayKey(slot), today)
            .putInt(countKey(slot), 0)
            .apply()
    }

    private fun dayKey(slot: String): String =
        if (slot == DEFAULT_SLOT) KEY_DAY else "${slot}_day"

    private fun countKey(slot: String): String =
        if (slot == DEFAULT_SLOT) KEY_COUNT else "count_${slot}"
}
