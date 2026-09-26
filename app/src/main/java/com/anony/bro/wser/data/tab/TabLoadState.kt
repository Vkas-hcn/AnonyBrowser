package com.anony.bro.wser.data.tab

/**
 * Tab 页加载状态，用于跨会话持久化。
 */
enum class TabLoadState {
    /** 空白页 / 尚未加载 */
    IDLE,

    /** 加载中 */
    LOADING,

    /** 已完成加载 */
    COMPLETED,

    /** 加载失败 */
    FAILED;

    val isLoading: Boolean
        get() = this == LOADING

    companion object {
        fun from(
            isLoading: Boolean,
            hasError: Boolean,
            hasUrl: Boolean,
        ): TabLoadState = when {
            isLoading -> LOADING
            hasError -> FAILED
            hasUrl -> COMPLETED
            else -> IDLE
        }

        fun parse(raw: String?): TabLoadState {
            if (raw.isNullOrBlank()) return IDLE
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: IDLE
        }
    }
}
