package com.anony.bro.wser.base.adaptation

import android.app.Activity
import android.content.res.Configuration
import android.os.Build

/**
 * 设备形态适配宿主接口。
 *
 * 预留给折叠屏、大屏等设备的浏览器多窗口布局适配。
 * [com.anony.bro.wser.base.BaseActivity] 默认实现空操作，业务 Activity 可按需覆写。
 */
interface DeviceAdaptationHost {

    /** 当前是否处于大屏 / 展开态，可用于切换多栏浏览器布局。 */
    fun isExpandedLayout(): Boolean = false

    /** 配置变更时回调（屏幕旋转、折叠态变化等）。 */
    fun onDeviceConfigurationChanged(newConfig: Configuration) = Unit

    /**
     * 折叠屏折叠特征变化回调。
     * 需要 WindowManager 依赖时由业务层接入；基类仅预留入口。
     */
    fun onFoldingFeatureChanged(isSeparating: Boolean, orientation: Int) = Unit

    /**
     * 根据设备形态应用浏览器多窗口布局。
     * 例如：展开态左右分栏（书签/历史 | WebView），折叠态单栏。
     */
    fun applyBrowserMultiWindowLayout(expanded: Boolean) = Unit
}

/**
 * VPN 系统权限与后台服务联动适配接口。
 *
 * 预留给后续 VPN 连接、断开、状态监听等功能的快速开发。
 * 不同系统版本的权限差异（通知、前台服务、VPN 授权）在此统一收敛。
 */
interface VpnAdaptationHost {

    /** 检查是否已获得 VPN 授权（VpnService.prepare 返回 null）。 */
    fun hasVpnPermission(): Boolean = false

    /** 发起 VPN 授权请求。返回 true 表示已跳转系统授权页。 */
    fun requestVpnPermission(requestCode: Int = REQUEST_VPN_PERMISSION): Boolean = false

    /** VPN 授权结果回调。 */
    fun onVpnPermissionResult(granted: Boolean) = Unit

    /** 检查通知权限（Android 13+ 前台服务通知所需）。 */
    fun hasPostNotificationPermission(): Boolean = true

    /** 请求通知权限。 */
    fun requestPostNotificationPermission(requestCode: Int = REQUEST_POST_NOTIFICATION) = Unit

    /**
     * 绑定 / 解绑 VPN 后台服务的扩展入口。
     * 后续接入 libbox / VpnService 时在此实现。
     */
    fun bindVpnService() = Unit

    fun unbindVpnService() = Unit

    /** 按系统版本执行权限适配逻辑。 */
    fun adaptVpnPermissionsForApiLevel(apiLevel: Int = Build.VERSION.SDK_INT) = Unit

    companion object {
        const val REQUEST_VPN_PERMISSION = 0x0F01
        const val REQUEST_POST_NOTIFICATION = 0x0F02
    }
}

/**
 * 简易大屏 / 横屏检测辅助，不强制依赖 androidx.window。
 */
object DeviceAdaptationHelper {

    fun isLargeScreen(activity: Activity): Boolean {
        val metrics = activity.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        return widthDp >= 600f
    }

    fun isLandscape(activity: Activity): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}
