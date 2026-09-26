package com.anony.bro.wser.data.guide

import android.content.Context
import androidx.core.content.edit

object LeadStore {

    private const val PREFS_NAME = "lead_prefs"
    private const val KEY_COMPLETED = "lead_completed"

    fun shouldShow(context: Context): Boolean =
        !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_COMPLETED, true)
            }
    }
}
