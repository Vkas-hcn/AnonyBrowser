package com.anony.bro.wser.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器多 Tab 场景下的独立 [ViewModelStore] 管理器。
 *
 * 每个 Tab 拥有独立的 ViewModel 作用域，避免不同 Tab 的网页状态、
 * 加载进度互相干扰。Tab 关闭时调用 [clearTab] 释放对应资源。
 *
 * 示例：
 * ```
 * val owner = BrowserTabViewModelStore.ownerFor("tab-1")
 * val vm = ViewModelProvider(owner)[BrowserTabViewModel::class.java]
 * // Tab 关闭
 * BrowserTabViewModelStore.clearTab("tab-1")
 * ```
 */
object BrowserTabViewModelStore {

    private val stores = ConcurrentHashMap<String, TabViewModelStoreOwner>()

    /**
     * 获取或创建指定 Tab 的 [ViewModelStoreOwner]。
     */
    fun ownerFor(tabId: String): ViewModelStoreOwner {
        require(tabId.isNotBlank()) { "tabId must not be blank" }
        return stores.getOrPut(tabId) { TabViewModelStoreOwner(tabId) }
    }

    /**
     * 便捷方法：在指定 Tab 作用域下获取 ViewModel。
     */
    inline fun <reified VM : ViewModel> getViewModel(
        tabId: String,
        factory: ViewModelProvider.Factory? = null,
    ): VM {
        val owner = ownerFor(tabId)
        return if (factory != null) {
            ViewModelProvider(owner, factory)[VM::class.java]
        } else {
            ViewModelProvider(owner)[VM::class.java]
        }
    }

    /**
     * 关闭 Tab 时清理其 ViewModelStore，触发所有 ViewModel.onCleared()。
     */
    fun clearTab(tabId: String) {
        stores.remove(tabId)?.clear()
    }

    /**
     * 清理全部 Tab（例如浏览器进程退出、清空所有标签页）。
     */
    fun clearAll() {
        stores.keys.toList().forEach { clearTab(it) }
    }

    /** 当前存活的 Tab 数量（便于调试 / LeakCanary 对照） */
    fun activeTabCount(): Int = stores.size

    /** 是否存在指定 Tab */
    fun contains(tabId: String): Boolean = stores.containsKey(tabId)

    private class TabViewModelStoreOwner(
        val tabId: String,
    ) : ViewModelStoreOwner {
        private val store = ViewModelStore()
        override val viewModelStore: ViewModelStore get() = store
        fun clear() = store.clear()
    }
}
