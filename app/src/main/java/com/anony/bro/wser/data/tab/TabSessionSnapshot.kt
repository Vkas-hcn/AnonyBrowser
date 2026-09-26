package com.anony.bro.wser.data.tab

/**
 * 可持久化的单 Tab 快照（不含 WebView / Fragment 运行时引用）。
 */
data class PersistedTab(
    val id: String,
    val title: String,
    val url: String?,
    val loadState: TabLoadState = TabLoadState.IDLE,
)

/**
 * 整个多 Tab 会话快照。
 *
 * @param selectedTabId 当前激活 Tab 的唯一标识
 * @param selectedTabIndex 当前激活 Tab 在列表中的索引（与 id 互为备份）
 * @param showingHome 退出时是否停在首页
 * @param tabs 所有已打开 Tab（顺序即展示顺序）
 */
data class TabSessionSnapshot(
    val selectedTabId: String?,
    val selectedTabIndex: Int,
    val showingHome: Boolean,
    val tabs: List<PersistedTab>,
) {
    val tabCount: Int
        get() = tabs.size

    companion object {
        val EMPTY = TabSessionSnapshot(
            selectedTabId = null,
            selectedTabIndex = -1,
            showingHome = true,
            tabs = emptyList(),
        )

        fun defaultBlank(tabId: String, title: String): TabSessionSnapshot =
            TabSessionSnapshot(
                selectedTabId = tabId,
                selectedTabIndex = 0,
                showingHome = true,
                tabs = listOf(
                    PersistedTab(
                        id = tabId,
                        title = title,
                        url = null,
                        loadState = TabLoadState.IDLE,
                    ),
                ),
            )
    }
}
