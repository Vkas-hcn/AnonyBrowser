package com.anony.bro.wser.base

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.anony.bro.wser.base.adaptation.DeviceAdaptationHelper
import com.anony.bro.wser.base.adaptation.DeviceAdaptationHost
import com.anony.bro.wser.base.adaptation.VpnAdaptationHost
import com.anony.bro.wser.data.settings.AppLanguageStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 项目级 Activity 基类，封装：
 * - ViewBinding 与 [BaseViewModel] 的绑定与初始化流程
 * - 统一加载框、错误 Toast 等通用 UI 交互
 * - ViewModel 与 Activity 生命周期自动联动（不可见时暂停非必要更新）
 * - 折叠屏 / 大屏布局适配、VPN 权限适配预留入口
 *
 * 业务 Activity 只需：
 * 1. 继承本类并指定 VB / VM 泛型
 * 2. 通过 `by viewModels()` 提供 [viewModel]
 * 3. 实现 [inflateBinding]、[initViews]、[observeData]
 *
 * @param VB ViewBinding 类型
 * @param VM 对应业务 ViewModel，必须继承 [BaseViewModel]
 */
abstract class BaseActivity<VB : ViewBinding, VM : BaseViewModel> :
    AppCompatActivity(),
    DeviceAdaptationHost,
    VpnAdaptationHost {

    private var _binding: VB? = null

    /** 当前页面的 ViewBinding，仅在 onCreate~onDestroy 之间有效 */
    protected val binding: VB
        get() = _binding
            ?: error("ViewBinding is only valid between onCreate and onDestroy")

    /** 业务 ViewModel，子类通过 `override val viewModel: XxxViewModel by viewModels()` 提供 */
    protected abstract val viewModel: VM

    private var loadingDialog: Dialog? = null
    private var stateCollectJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageStore.wrap(newBase))
    }

    /** 是否在页面不可见时自动调用 ViewModel.onPauseUpdates */
    protected open val pauseUpdatesWhenStopped: Boolean = true

    /**
     * 是否启用 edge-to-edge（内容延伸至系统栏下方）。
     * 与 [applySystemBarPadding] 分离，便于全屏页自定义沉浸式 insets。
     */
    protected open val edgeToEdgeEnabled: Boolean = true

    /** 是否自动为根布局添加 systemBars padding（避免内容与状态栏/导航栏重叠） */
    protected open val applySystemBarPadding: Boolean = true

    /** @deprecated 使用 [edgeToEdgeEnabled] + [applySystemBarPadding] */
    protected open val enableEdgeToEdgePadding: Boolean
        get() = edgeToEdgeEnabled && applySystemBarPadding

    // ─────────────────────────── 子类必须实现 ───────────────────────────

    /** inflate 布局，例如 `ActivityMainBinding.inflate(inflater)` */
    protected abstract fun inflateBinding(inflater: LayoutInflater): VB

    /** 视图初始化（点击事件、RecyclerView 等） */
    protected abstract fun initViews(savedInstanceState: Bundle?)

    /** 观察 ViewModel 业务状态（除基类已处理的 loading / message 外） */
    protected abstract fun observeData()

    // ─────────────────────────── 生命周期 ───────────────────────────

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (edgeToEdgeEnabled) {
            enableEdgeToEdge()
        }
        _binding = inflateBinding(layoutInflater)
        setContentView(binding.root)
        applyWindowInsetsIfNeeded()
        bindViewModelLifecycle()
        initViews(savedInstanceState)
        observeData()
        onAdaptationReady()
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        if (pauseUpdatesWhenStopped) {
            viewModel.onResumeUpdates()
        }
    }

    @CallSuper
    override fun onResume() {
        super.onResume()
        if (!isChangingConfigurations && AppLanguageStore.needsRecreate(this)) {
            recreate()
        }
    }

    @CallSuper
    override fun onStop() {
        if (pauseUpdatesWhenStopped) {
            viewModel.onPauseUpdates()
        }
        super.onStop()
    }

    @CallSuper
    override fun onDestroy() {
        stateCollectJob?.cancel()
        stateCollectJob = null
        dismissLoading()
        loadingDialog = null
        _binding = null
        unbindVpnService()
        super.onDestroy()
    }

    @CallSuper
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onDeviceConfigurationChanged(newConfig)
        val expanded = isExpandedLayout()
        applyBrowserMultiWindowLayout(expanded)
    }

    // ─────────────────────────── ViewModel 绑定 ───────────────────────────

    /**
     * 自动收集 loading / uiMessage，并在 STARTED 生命周期内生效，
     * 页面不可见时暂停收集，销毁时随 lifecycleScope 取消。
     */
    private fun bindViewModelLifecycle() {
        stateCollectJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.loadingState.collect { state ->
                        when (state) {
                            is LoadingState.Loading -> showLoading(state.message)
                            LoadingState.Idle -> dismissLoading()
                        }
                    }
                }
                launch {
                    viewModel.uiMessage.collect { message ->
                        handleUiMessage(message)
                    }
                }
            }
        }
    }

    // ─────────────────────────── 通用 UI ───────────────────────────

    /**
     * 显示加载框。子类可覆写以使用自定义样式（浏览器进度条 / VPN 连接动画等）。
     */
    protected open fun showLoading(message: String? = null) {
        if (isFinishing || isDestroyed) return
        val dialog = loadingDialog ?: createLoadingDialog(message).also { loadingDialog = it }
        if (!dialog.isShowing) {
            dialog.show()
        }
    }

    protected open fun dismissLoading() {
        loadingDialog?.takeIf { it.isShowing }?.dismiss()
    }

    /**
     * 默认加载对话框；子类可覆写。
     */
    protected open fun createLoadingDialog(message: String?): Dialog {
        val builder = MaterialAlertDialogBuilder(this)
            .setCancelable(false)
            .setMessage(message ?: getString(com.anony.bro.wser.R.string.loading))
        return builder.create()
    }

    /**
     * 统一处理 UI 消息。可按 [UiMessage.Type] 区分浏览器 / VPN 提示样式。
     */
    protected open fun handleUiMessage(message: UiMessage) {
        val prefix = when (message.type) {
            UiMessage.Type.BROWSER -> getString(com.anony.bro.wser.R.string.ui_message_prefix_browser)
            UiMessage.Type.VPN -> getString(com.anony.bro.wser.R.string.ui_message_prefix_vpn)
            UiMessage.Type.SUCCESS -> ""
            UiMessage.Type.INFO -> ""
            UiMessage.Type.ERROR -> ""
        }
        showToast(prefix + message.text)
    }

    protected open fun showToast(text: String, long: Boolean = false) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(
            this,
            text,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }

    // ─────────────────────────── 窗口 / 设备适配 ───────────────────────────

    private fun applyWindowInsetsIfNeeded() {
        if (!applySystemBarPadding) return
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    /**
     * 适配入口就绪回调：检测大屏并应用初始布局，执行 VPN 权限版本适配。
     */
    protected open fun onAdaptationReady() {
        applyBrowserMultiWindowLayout(isExpandedLayout())
        adaptVpnPermissionsForApiLevel()
    }

    override fun isExpandedLayout(): Boolean =
        DeviceAdaptationHelper.isLargeScreen(this)

    override fun onDeviceConfigurationChanged(newConfig: Configuration) = Unit

    override fun applyBrowserMultiWindowLayout(expanded: Boolean) = Unit

    // ─────────────────────────── VPN 权限适配默认实现 ───────────────────────────

    override fun hasPostNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun requestPostNotificationPermission(requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPostNotificationPermission()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            requestCode,
        )
    }

    /**
     * 默认按 API 级别做通知权限适配；VPN 授权需业务层结合 VpnService.prepare 实现。
     */
    override fun adaptVpnPermissionsForApiLevel(apiLevel: Int) {
        if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 展示通知需要 POST_NOTIFICATIONS；业务层在连接 VPN 前申请
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VpnAdaptationHost.REQUEST_POST_NOTIFICATION) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                showToast(getString(com.anony.bro.wser.R.string.notification_permission_required))
            }
        }
    }
}
