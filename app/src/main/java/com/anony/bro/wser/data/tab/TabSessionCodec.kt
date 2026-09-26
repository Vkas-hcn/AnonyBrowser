package com.anony.bro.wser.data.tab

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tab 会话 JSON 编解码。格式版本化，损坏数据返回 null 由调用方降级。
 */
object TabSessionCodec {

    const val CURRENT_VERSION = 1

    fun encode(snapshot: TabSessionSnapshot): String {
        val root = JSONObject()
        root.put("version", CURRENT_VERSION)
        root.put("selectedTabId", snapshot.selectedTabId ?: JSONObject.NULL)
        root.put("selectedTabIndex", snapshot.selectedTabIndex)
        root.put("showingHome", snapshot.showingHome)
        root.put("tabCount", snapshot.tabCount)
        val tabsArray = JSONArray()
        snapshot.tabs.forEach { tab ->
            tabsArray.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("title", tab.title)
                    .put("url", tab.url ?: JSONObject.NULL)
                    .put("loadState", tab.loadState.name),
            )
        }
        root.put("tabs", tabsArray)
        return root.toString()
    }

    /**
     * @return 解析成功的快照；JSON 损坏 / 字段非法时返回 null
     */
    fun decode(raw: String?): TabSessionSnapshot? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            val version = root.optInt("version", 0)
            if (version <= 0 || version > CURRENT_VERSION) return null

            val tabsJson = root.optJSONArray("tabs") ?: return null
            val tabs = buildList {
                for (i in 0 until tabsJson.length()) {
                    val item = tabsJson.optJSONObject(i) ?: return null
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
                    val title = item.optString("title").ifBlank { "" }
                    val url = when {
                        !item.has("url") || item.isNull("url") -> null
                        else -> item.optString("url").takeIf { it.isNotBlank() }
                    }
                    val loadState = TabLoadState.parse(item.optString("loadState"))
                    add(PersistedTab(id = id, title = title, url = url, loadState = loadState))
                }
            }

            val selectedTabId = when {
                !root.has("selectedTabId") || root.isNull("selectedTabId") -> null
                else -> root.optString("selectedTabId").takeIf { it.isNotBlank() }
            }
            val selectedTabIndex = root.optInt("selectedTabIndex", -1)
            val showingHome = root.optBoolean("showingHome", true)

            TabSessionSnapshot(
                selectedTabId = selectedTabId,
                selectedTabIndex = selectedTabIndex,
                showingHome = showingHome,
                tabs = tabs,
            )
        } catch (_: Exception) {
            null
        }
    }
}
