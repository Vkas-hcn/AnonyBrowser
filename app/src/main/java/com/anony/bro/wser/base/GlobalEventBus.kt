package com.anony.bro.wser.base

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * 跨页面通用状态事件总线。
 *
 * 支持浏览器 Tab 切换、VPN 连接状态等全局状态的订阅与分发。
 * 使用 [SharedFlow] 保证无粘性、可多订阅者并发消费。
 *
 * 用法：
 * ```
 * // 分发
 * GlobalEventBus.emit(GlobalEvent.VpnConnectionStateChanged(VpnConnectionState.CONNECTED))
 *
 * // 订阅（在 ViewModel 中）
 * observeGlobalEvents { event -> ... }
 * // 或按类型过滤
 * GlobalEventBus.filteredEvents<GlobalEvent.BrowserTabSwitched>().collect { ... }
 * ```
 */
object GlobalEventBus {

    private val _events = MutableSharedFlow<GlobalEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /** 全局事件流，所有订阅者均可接收 */
    val events: SharedFlow<GlobalEvent> = _events.asSharedFlow()

    /**
     * 分发全局事件。
     * @return 是否成功投递到缓冲区
     */
    fun emit(event: GlobalEvent): Boolean = _events.tryEmit(event)

    /**
     * 挂起式分发，在缓冲区满时挂起等待。
     */
    suspend fun emitSuspend(event: GlobalEvent) {
        _events.emit(event)
    }

    /**
     * 按事件类型过滤的 Flow。
     */
    inline fun <reified T : GlobalEvent> filteredEvents() =
        events.filter { it is T }
}
