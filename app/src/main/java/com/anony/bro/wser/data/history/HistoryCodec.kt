package com.anony.bro.wser.data.history

import org.json.JSONArray
import org.json.JSONObject

/**
 * 浏览历史 JSON 编解码。损坏数据返回 null 由调用方降级。
 */
object HistoryCodec {

    const val CURRENT_VERSION = 1

    fun encode(entries: List<HistoryEntry>): String {
        val root = JSONObject()
        root.put("version", CURRENT_VERSION)
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("url", entry.url)
                    .put("visitedAt", entry.visitedAt)
                    .put("faviconPath", entry.faviconPath ?: JSONObject.NULL),
            )
        }
        root.put("entries", array)
        return root.toString()
    }

    fun decode(raw: String?): List<HistoryEntry>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            val version = root.optInt("version", 0)
            if (version <= 0 || version > CURRENT_VERSION) return null
            val array = root.optJSONArray("entries") ?: return null
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: return null
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
                    val url = item.optString("url").takeIf { it.isNotBlank() } ?: return null
                    val title = item.optString("title").ifBlank { url }
                    val visitedAt = item.optLong("visitedAt", 0L)
                    if (visitedAt <= 0L) return null
                    val faviconPath = when {
                        !item.has("faviconPath") || item.isNull("faviconPath") -> null
                        else -> item.optString("faviconPath").takeIf { it.isNotBlank() }
                    }
                    add(
                        HistoryEntry(
                            id = id,
                            title = title,
                            url = url,
                            visitedAt = visitedAt,
                            faviconPath = faviconPath,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
