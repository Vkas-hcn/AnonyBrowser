package com.anony.bro.wser.base

/**
 * 通用 UI 消息，适配浏览器与 VPN 场景的不同提示需求。
 */
data class UiMessage(
    val text: String,
    val type: Type = Type.ERROR,
    val tag: String? = null,
) {
    enum class Type {
        /** 通用错误提示 */
        ERROR,
        /** 信息提示（如 VPN 状态变更） */
        INFO,
        /** 成功提示（如书签保存成功） */
        SUCCESS,
        /** 浏览器网页加载相关提示 */
        BROWSER,
        /** VPN 连接相关提示 */
        VPN,
    }
}

/**
 * 页面加载状态。
 */
sealed class LoadingState {
    data object Idle : LoadingState()
    data class Loading(val message: String? = null) : LoadingState()
}

/**
 * 跨页面全局状态事件，供浏览器 Tab / VPN 连接等场景订阅与分发。
 */
sealed class GlobalEvent {
    /** 浏览器当前激活 Tab 切换 */
    data class BrowserTabSwitched(
        val tabId: String,
        val url: String? = null,
        val title: String? = null,
    ) : GlobalEvent()

    /** 浏览器网页加载进度同步 */
    data class BrowserPageProgress(
        val tabId: String,
        val progress: Int,
        val isLoading: Boolean,
    ) : GlobalEvent()

    /** 浏览器网页导航状态（前进/后退可用性） */
    data class BrowserNavigationState(
        val tabId: String,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
    ) : GlobalEvent()

    /** VPN 连接状态变更 */
    data class VpnConnectionStateChanged(
        val state: VpnConnectionState,
        val message: String? = null,
    ) : GlobalEvent()

    /** 自定义扩展事件，业务方可自行携带 payload */
    data class Custom(val key: String, val payload: Any? = null) : GlobalEvent()
}

/**
 * VPN 连接状态枚举，预留给后续 VPN 功能使用。
 */
enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}
