package com.anony.bro.wser.data.search

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import java.util.Locale

class SearchHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<String> = read()

    fun add(query: String): List<String> {
        val next = prepend(read(), query)
        write(next)
        return next
    }

    fun remove(query: String): List<String> {
        val next = read().filterNot { it.equals(query, ignoreCase = true) }
        write(next)
        return next
    }

    fun clear(): List<String> {
        write(emptyList())
        return emptyList()
    }

    private fun read(): List<String> = runCatching {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val array = JSONArray(raw)
        prepend(
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            },
            "",
        )
    }.getOrDefault(emptyList())

    private fun write(items: List<String>) {
        val array = JSONArray()
        items.take(MAX_SIZE).forEach { array.put(it) }
        prefs.edit { putString(KEY_ITEMS, array.toString()) }
    }

    companion object {
        const val MAX_SIZE = 100
        const val COLLAPSED_COUNT = 5
        private const val PREFS_NAME = "search_history"
        private const val KEY_ITEMS = "items"

        fun prepend(existing: List<String>, query: String): List<String> {
            val cleaned = query.trim()
            val head = if (cleaned.isEmpty()) emptyList() else listOf(cleaned)
            val rest = existing.map { it.trim() }.filter { it.isNotEmpty() }
            val merged = if (head.isEmpty()) rest else head + rest.filterNot {
                it.equals(cleaned, ignoreCase = true)
            }
            return merged.distinctBy { it.lowercase(Locale.US) }.take(MAX_SIZE)
        }
    }
}
