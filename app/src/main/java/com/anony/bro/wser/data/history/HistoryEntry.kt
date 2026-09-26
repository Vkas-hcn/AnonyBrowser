package com.anony.bro.wser.data.history

/**
 * 单条浏览历史。
 */
data class HistoryEntry(
    val id: String,
    val title: String,
    val url: String,
    val visitedAt: Long,
    val faviconPath: String? = null,
)
