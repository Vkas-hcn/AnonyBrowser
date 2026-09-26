package com.anony.bro.wser.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

/**
 * 项目级 ViewModel 基类，封装：
 * - 生命周期感知的加载 / 错误状态管理
 * - 统一协程作用域（页面销毁时随 [viewModelScope] 自动取消）
 * - 全局事件总线订阅
 * - 浏览器网页状态同步、VPN 网络状态切换等扩展钩子
 *
 * 所有业务 ViewModel 应继承此类。
 */
abstract class BaseViewModel : ViewModel() {

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    /** 加载状态，供 Activity 观察以显示/隐藏加载框 */
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    private val _uiMessage = MutableSharedFlow<UiMessage>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    /** 一次性 UI 消息（Toast / Snackbar） */
    val uiMessage: SharedFlow<UiMessage> = _uiMessage.asSharedFlow()

    /**
     * 未捕获协程异常处理器：统一转为错误消息，避免崩溃。
     */
    protected val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        onUnhandledException(throwable)
    }

    /**
     * 带异常处理的业务协程作用域，绑定 [viewModelScope]，
     * ViewModel 清除时自动取消所有子协程（含 VPN 轮询、网络请求等）。
     */
    protected val safeScope: CoroutineScope =
        viewModelScope + exceptionHandler

    /** 当前是否处于加载中 */
    val isLoading: Boolean
        get() = _loadingState.value is LoadingState.Loading

    // ─────────────────────────── 状态管理 ───────────────────────────

    protected fun showLoading(message: String? = null) {
        _loadingState.value = LoadingState.Loading(message)
    }

    protected fun hideLoading() {
        _loadingState.value = LoadingState.Idle
    }

    protected fun setLoading(loading: Boolean, message: String? = null) {
        _loadingState.value = if (loading) LoadingState.Loading(message) else LoadingState.Idle
    }

    /**
     * 发送 UI 消息（错误 / 信息 / 浏览器 / VPN 等）。
     */
    protected fun postMessage(message: UiMessage) {
        _uiMessage.tryEmit(message)
    }

    protected fun postError(text: String, tag: String? = null) {
        postMessage(UiMessage(text, UiMessage.Type.ERROR, tag))
    }

    protected fun postInfo(text: String, tag: String? = null) {
        postMessage(UiMessage(text, UiMessage.Type.INFO, tag))
    }

    protected fun postBrowserMessage(text: String, tag: String? = null) {
        postMessage(UiMessage(text, UiMessage.Type.BROWSER, tag))
    }

    protected fun postVpnMessage(text: String, tag: String? = null) {
        postMessage(UiMessage(text, UiMessage.Type.VPN, tag))
    }

    // ─────────────────────────── 协程封装 ───────────────────────────

    /**
     * 在主线程启动协程，自动绑定生命周期，销毁时取消。
     */
    protected fun launch(
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = safeScope.launch(dispatcher, block = block)

    /**
     * 在 IO 线程启动协程（适合网络请求、VPN 状态轮询、磁盘读写）。
     */
    protected fun launchIO(block: suspend CoroutineScope.() -> Unit): Job =
        launch(Dispatchers.IO, block)

    /**
     * 带加载框的挂起任务封装。
     */
    protected suspend fun <T> withLoading(
        message: String? = null,
        block: suspend () -> T,
    ): T {
        showLoading(message)
        return try {
            block()
        } finally {
            hideLoading()
        }
    }

    /**
     * 在 IO 调度器执行并自动管理加载状态。
     */
    protected fun launchWithLoading(
        message: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = launchIO {
        withLoading(message) { block() }
    }

    /**
     * 切换到主线程执行 UI 相关逻辑。
     */
    protected suspend fun <T> onMain(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }

    // ─────────────────────────── 全局事件 ───────────────────────────

    /**
     * 订阅全局事件总线。默认在 [safeScope] 中收集，页面销毁时自动取消。
     */
    protected fun observeGlobalEvents(
        collector: suspend (GlobalEvent) -> Unit,
    ): Job = launch {
        GlobalEventBus.events.collect { event ->
            onGlobalEvent(event)
            collector(event)
        }
    }

    /**
     * 分发全局事件。
     */
    protected fun emitGlobalEvent(event: GlobalEvent) {
        GlobalEventBus.emit(event)
    }

    /**
     * 全局事件回调钩子，子类可覆写做统一处理。
     */
    protected open fun onGlobalEvent(event: GlobalEvent) = Unit

    // ─────────────────────────── 扩展接口（浏览器 / VPN） ───────────────────────────

    /**
     * 浏览器网页状态同步扩展点。
     * 后续多 Tab 场景可覆写，将 URL / 标题 / 进度同步到 UI 或持久化层。
     */
    open fun onBrowserPageStateSync(
        tabId: String,
        url: String?,
        title: String?,
        progress: Int,
    ) = Unit

    /**
     * VPN 网络状态切换扩展点。
     * 后续可覆写以联动浏览器代理、刷新网络栈等。
     */
    open fun onVpnNetworkStateChanged(
        state: VpnConnectionState,
        message: String? = null,
    ) = Unit

    /**
     * 页面从可见变为不可见时，由 [BaseActivity] 调用，
     * 用于暂停非必要的轮询 / 刷新任务。
     */
    open fun onPauseUpdates() = Unit

    /**
     * 页面重新可见时恢复更新任务。
     */
    open fun onResumeUpdates() = Unit

    // ─────────────────────────── 资源清理 ───────────────────────────

    /**
     * 未捕获异常的默认处理：转为错误 Toast。
     * 子类可覆写以接入 Crashlytics 等。
     */
    protected open fun onUnhandledException(throwable: Throwable) {
        postError(throwable.message ?: throwable.javaClass.simpleName)
    }

    /**
     * ViewModel 被清除时回调，子类在此释放自定义资源
     * （监听器、回调引用、缓存等）。协程已由 [viewModelScope] 自动取消。
     */
    protected open fun onClearedResources() = Unit

    final override fun onCleared() {
        onClearedResources()
        super.onCleared()
    }
}
