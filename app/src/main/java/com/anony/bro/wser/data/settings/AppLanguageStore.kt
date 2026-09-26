package com.anony.bro.wser.data.settings

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

object AppLanguageStore {

    const val DEFAULT_LANGUAGE_TAG = "en"

    private const val PREFS_NAME = "app_language"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun selectedTag(context: Context): String =
        storageContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, DEFAULT_LANGUAGE_TAG)
            ?: DEFAULT_LANGUAGE_TAG

    fun set(context: Context, languageTag: String) {
        storageContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, languageTag)
            .apply()
    }

    fun wrap(context: Context): Context {
        val tag = selectedTag(context)
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isBlank()) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    fun needsRecreate(context: Context): Boolean {
        val selected = selectedTag(context)
        val current = context.resources.configuration.locales[0].toLanguageTag()
        return current != selected && !current.startsWith("$selected-")
    }

    private fun storageContext(context: Context): Context =
        context.applicationContext ?: context
}
