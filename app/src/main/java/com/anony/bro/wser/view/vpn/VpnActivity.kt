package com.anony.bro.wser.view.vpn

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.anony.bro.wser.R
import com.anony.bro.wser.ads.AdMobManager
import com.anony.bro.wser.base.BaseActivity
import com.anony.bro.wser.ads.VpnAdController
import com.anony.bro.wser.app.GateBrowserApplication
import com.anony.bro.wser.data.DataHubTool
import com.anony.bro.wser.data.UpDataTool
import com.anony.bro.wser.data.vpn.VpnConfigFactory
import com.anony.bro.wser.data.vpn.VpnServer
import com.anony.bro.wser.data.vpn.VpnServerCatalog
import com.anony.bro.wser.data.vpn.VpnServerCatalogParser
import com.anony.bro.wser.data.vpn.connectionDurationSeconds
import com.anony.bro.wser.data.vpn.countryName
import com.anony.bro.wser.data.vpn.flagIconRes
import com.anony.bro.wser.databinding.ActivityVpnBinding
import com.anony.bro.wser.vpn.VpnCallback
import com.anony.bro.wser.vpn.VpnManager
import com.anony.bro.wser.vpn.VpnState
import com.anony.bro.wser.vpn.VpnStatsSnapshot
import com.anony.bro.wser.vpn.VpnStatsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VpnActivity : BaseActivity<ActivityVpnBinding, VpnViewModel>() {

    override val viewModel: VpnViewModel by viewModels()

    override val applySystemBarPadding: Boolean = false

    private var catalog: VpnServerCatalog? = null
    private var selectedServer: VpnServer? = null
    private var pendingOperation: PendingOperation? = null
    private var operationBlocker: View? = null
    private var rotationAnimator: ObjectAnimator? = null
    private var stateTextAnimator: ValueAnimator? = null
    private var operationJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var connectAdJob: Job? = null
    private var cancelConnectAdFlow: (() -> Unit)? = null
    private var cancelDisconnectAdFlow: (() -> Unit)? = null
    private var connectAdFlowId = 0L
    private var disconnectAdFlowId = 0L
    private var disconnectAdFlowActive = false
    private var disconnectAdPresented = false

    private val backBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    private val connectGuideBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
        }
    }

    private val nodeListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val server = readSelectedServer(result.data) ?: return@registerForActivityResult
        selectedServer = server
        renderSelectedServer()
    }

    private val vpnCallback = object : VpnCallback {
        override fun onStateChanged(state: VpnState) {
            renderVpnState(state)
        }

        override fun onTrafficUpdate(
            uploadSpeed: Long,
            downloadSpeed: Long,
            totalUpload: Long,
            totalDownload: Long,
        ) = Unit

        override fun onError(message: String) {
            showToast(message)
            cancelPendingConnectAdFlow()
            cancelPendingDisconnectAdFlow(renderCurrentState = false)
            finishFlow()
            renderVpnState(VpnManager.state)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityVpnBinding =
        ActivityVpnBinding.inflate(inflater)

    override fun initViews(savedInstanceState: Bundle?) {
        setupSystemBars()
        onBackPressedDispatcher.addCallback(this, backBlocker)
        onBackPressedDispatcher.addCallback(this, connectGuideBackCallback)
        VpnManager.init(applicationContext)
        VpnStatsStore.init()
        VpnManager.notificationTitle = getString(R.string.app_name)
        VpnManager.notificationIcon = R.drawable.ic_nav_vpn
        VpnManager.notificationActivityClass = VpnActivity::class.java
        VpnManager.addCallback(vpnCallback)
        DataHubTool.refreshAsync(this)
        binding.btnBack.setOnClickListener {
            if (pendingOperation == null) finish()
        }
        binding.materialCardViewSwich.setOnClickListener {
            toggleVpn()
        }
        binding.materialCardViewList.setOnClickListener {
            if (pendingOperation != null) return@setOnClickListener
            nodeListLauncher.launch(
                VpnNodeListActivity.createIntent(this, selectedServer),
            )
        }

        loadDefaultServer()
        renderSelectedServer()
        renderVpnState(VpnManager.state)
        renderStats(VpnStatsStore.stats.value)
        showFirstConnectGuideIfNeeded()
        observeNativeAd()
    }

    override fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VpnStatsStore.stats.collect(::renderStats)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次进入/返回本页，强制刷新重新展示 connect_native（有缓存则复用缓存）
        AdMobManager.updateNativeAdContainer(
            binding.nativeAd,
            AdMobManager.NativePlacement.CONNECT,
            loadWhenMissing = true,
            preloadAfterDisplay = true,
            forceRefresh = true,
        )
    }

    private fun observeNativeAd() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var forceRefresh = true
                while (true) {
                    AdMobManager.updateNativeAdContainer(
                        binding.nativeAd,
                        AdMobManager.NativePlacement.CONNECT,
                        loadWhenMissing = true,
                        preloadAfterDisplay = true,
                        forceRefresh = forceRefresh,
                    )
                    forceRefresh = false
                    delay(NATIVE_AD_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun setupSystemBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = systemBars.top)
            binding.topBar.layoutParams = binding.topBar.layoutParams.apply {
                height = dp(56) + systemBars.top
            }
            binding.root.updatePadding(
                left = systemBars.left,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        cancelPendingConnectAdFlow()
        cancelPendingDisconnectAdFlow(renderCurrentState = false)
        operationJob?.cancel()
        connectionTimeoutJob?.cancel()
        stopProcessingAnimation()
        removeInteractionBlocker()
        VpnManager.removeCallback(vpnCallback)
        AdMobManager.releaseNativeAdContainer(
            binding.nativeAd,
            AdMobManager.NativePlacement.CONNECT,
        )
        super.onDestroy()
    }

    override fun onStop() {
        // A disconnect has not happened yet while its interstitial is pending. Keep the VPN
        // connected when the activity leaves foreground before the ad is actually presented.
        if (disconnectAdFlowActive && !disconnectAdPresented) {
            cancelPendingDisconnectAdFlow()
        }
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (VpnManager.handleActivityResult(requestCode, resultCode)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (VpnManager.handlePermissionsResult(this, requestCode, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun loadDefaultServer() {
        val parsedCatalog = runCatching {
            VpnServerCatalogParser.parse(DataHubTool.vpnData)
        }.onFailure {
            showToast(it.message ?: getString(R.string.vpn_config_unavailable))
        }.getOrNull()

        catalog = parsedCatalog
        selectedServer = parsedCatalog
            ?.takeIf { it.endpointCluster.isNotEmpty() }
            ?.let(::selectDefaultServer)
    }

    private fun selectDefaultServer(catalog: VpnServerCatalog): VpnServer {
        val fastIndex = catalog.fastIndex
        return if (fastIndex >= 0 && fastIndex < catalog.endpointCluster.size) {
            catalog.endpointCluster[fastIndex]
        } else {
            catalog.endpointCluster.random()
        }
    }

    private fun renderSelectedServer() {
        val server = selectedServer
        binding.tvVpnCountry.text = server?.countryName()
        binding.tvVpnNameList.text = server?.locationLabel ?: getString(R.string.vpn_no_server_available)
        binding.imgVpnFlag.setImageResource(server?.flagIconRes() ?: R.drawable.ic_b_vpn)
    }

    private fun toggleVpn() {
        if (pendingOperation != null) return
        when (VpnManager.state) {
            VpnState.DISCONNECTED -> requestConnectFlow()
            VpnState.CONNECTED -> startDisconnectCountdown()
            VpnState.CONNECTING,
            VpnState.DISCONNECTING -> Unit
        }
    }

    private fun showFirstConnectGuideIfNeeded() {
        if (hasShownConnectGuide()) return
        if (VpnManager.state != VpnState.DISCONNECTED) return

        markConnectGuideShown()
    }


    private fun hasShownConnectGuide(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONNECT_GUIDE_SHOWN, false)

    private fun markConnectGuideShown() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECT_GUIDE_SHOWN, true)
            .apply()
    }

    private fun requestConnectFlow() {
        val catalog = catalog
        val server = selectedServer
        if (catalog == null || server == null) {
            showToast(getString(R.string.vpn_server_unavailable))
            renderVpnState(VpnState.DISCONNECTED)
            return
        }

        pendingOperation = PendingOperation.CONNECT
        addInteractionBlocker()
        UpDataTool.trackEvent("vpn_start_connect")
        VpnManager.notificationTitle = server.locationLabel
        VpnManager.setConfig(
            json = VpnConfigFactory.create(server, catalog),
            connectionDurationSeconds = catalog.connectionDurationSeconds(),
        )
        val accepted = VpnManager.prepareConnect(this) {
            startConnectCountdown()
        }
        if (!accepted) {
            finishFlow()
        }
    }

    private fun startConnectCountdown() {
        startCountdownAnimation(getString(R.string.vpn_state_connecting)) {
            VpnManager.startVpn()
            startConnectionTimeout()
        }
    }

    private fun startDisconnectCountdown() {
        pendingOperation = PendingOperation.DISCONNECT
        addInteractionBlocker()
        binding.tvVpnState.setText(R.string.vpn_state_disconnecting)
        startProcessingAnimation()
        startDisconnectAdFlow()
    }

    private fun startDisconnectAdFlow() {
        val flowId = ++disconnectAdFlowId
        disconnectAdFlowActive = true
        disconnectAdPresented = false
        val cancellation = VpnAdController.showInterstitialThen(
            activity = this,
            minDelayMs = DISCONNECT_AD_MIN_DELAY_MS,
            skipPostShowPreload = true,
            onAdPresented = {
                if (disconnectAdFlowActive && flowId == disconnectAdFlowId) {
                    disconnectAdPresented = true
                    stopProcessingAnimation()
                }
            },
            onAdClosed = {
                if (!GateBrowserApplication.get().isAppForeground()) {
                    cancelPendingDisconnectAdFlow()
                } else {
                    disconnectAfterAd(flowId)
                }
            },
        ) {
            disconnectAfterAd(flowId)
        }
        if (disconnectAdFlowActive && flowId == disconnectAdFlowId) {
            cancelDisconnectAdFlow = cancellation
        } else {
            cancellation()
        }
    }

    private fun disconnectAfterAd(flowId: Long) {
        if (!disconnectAdFlowActive || flowId != disconnectAdFlowId) return
        disconnectAdFlowActive = false
        disconnectAdPresented = false
        cancelDisconnectAdFlow = null
        stopProcessingAnimation()
        VpnManager.disconnect()
    }

    private fun cancelPendingDisconnectAdFlow(renderCurrentState: Boolean = true) {
        if (!disconnectAdFlowActive) return
        disconnectAdFlowActive = false
        disconnectAdPresented = false
        disconnectAdFlowId++
        cancelDisconnectAdFlow?.invoke()
        cancelDisconnectAdFlow = null
        finishFlow()
        if (renderCurrentState && !isFinishing && !isDestroyed) {
            renderVpnState(VpnManager.state)
        }
    }

    private fun startCountdownAnimation(label: String, onFinished: () -> Unit) {
        operationJob?.cancel()
        binding.tvVpnState.text = label
        startProcessingAnimation()
        operationJob = lifecycleScope.launch {
            delay(OPERATION_DELAY_MS)
            onFinished()
        }
    }

    private fun startProcessingAnimation() {
        stopProcessingAnimation()
        binding.imgSwitch.setImageResource(R.drawable.ic_vpn_loading_ring)
        rotationAnimator =
            ObjectAnimator.ofFloat(binding.imgSwitch, View.ROTATION, 0f, 360f).apply {
                duration = 700L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        stateTextAnimator = ValueAnimator.ofFloat(14f, 18f, 14f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                binding.tvVpnState.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    animator.animatedValue as Float,
                )
            }
            start()
        }
    }

    private fun stopProcessingAnimation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        stateTextAnimator?.cancel()
        stateTextAnimator = null
        binding.imgSwitch.rotation = 0f
        binding.tvVpnState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun addInteractionBlocker() {
        setOperationControlsEnabled(false)
        if (operationBlocker == null) {
            operationBlocker = View(this).apply {
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            binding.root.addView(
                operationBlocker,
                ConstraintLayout.LayoutParams(0, 0).apply {
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                },
            )
        }
        operationBlocker?.bringToFront()
        backBlocker.isEnabled = true
    }

    private fun removeInteractionBlocker() {
        operationBlocker?.let { blocker ->
            binding.root.removeView(blocker)
        }
        operationBlocker = null
        backBlocker.isEnabled = false
        setOperationControlsEnabled(true)
    }

    private fun setOperationControlsEnabled(enabled: Boolean) {
        binding.btnBack.isEnabled = enabled
        binding.materialCardViewSwich.isEnabled = enabled
        binding.materialCardViewList.isEnabled = enabled
    }

    private fun finishFlow() {
        operationJob?.cancel()
        operationJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        stopProcessingAnimation()
        pendingOperation = null
        removeInteractionBlocker()
    }

    private fun startConnectedAdFlow() {
        val flowId = ++connectAdFlowId
        AdMobManager.preloadIntersConnectOnVpnConnected(
            applicationContext,
            VpnAdController.LOCAL_IP_KEY,
        )
        connectAdJob?.cancel()
        connectAdJob = lifecycleScope.launch {
            delay(CONNECTED_AD_TRIGGER_DELAY_MS)
            if (flowId != connectAdFlowId ||
                VpnManager.state != VpnState.CONNECTED ||
                isFinishing ||
                isDestroyed
            ) {
                return@launch
            }
            val cancellation = VpnAdController.showInterstitialThen(
                activity = this@VpnActivity,
                onAdPresented = {
                    if (flowId == connectAdFlowId && pendingOperation == PendingOperation.CONNECT) {
                        stopProcessingAnimation()
                    }
                },
            ) {
                finishConnectedAdFlow(flowId)
            }
            if (flowId == connectAdFlowId && pendingOperation == PendingOperation.CONNECT) {
                cancelConnectAdFlow = cancellation
            } else {
                cancellation()
            }
        }
    }

    private fun finishConnectedAdFlow(flowId: Long) {
        if (flowId != connectAdFlowId || VpnManager.state != VpnState.CONNECTED) return
        connectAdFlowId++
        cancelConnectAdFlow = null
        finishFlow()
        renderVpnState(VpnState.CONNECTED)
    }

    private fun cancelPendingConnectAdFlow() {
        connectAdFlowId++
        connectAdJob?.cancel()
        connectAdJob = null
        cancelConnectAdFlow?.invoke()
        cancelConnectAdFlow = null
    }

    private fun startConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = lifecycleScope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (pendingOperation == PendingOperation.CONNECT && VpnManager.state != VpnState.CONNECTED) {
                showToast(getString(R.string.vpn_connection_timed_out))
                VpnManager.disconnect()
                finishFlow()
                renderVpnState(VpnState.DISCONNECTED)
            }
        }
    }

    private fun renderVpnState(state: VpnState) {
        if (pendingOperation != null && (state == VpnState.CONNECTING || state == VpnState.DISCONNECTING)) {
            return
        }
        if (pendingOperation == PendingOperation.CONNECT && state == VpnState.CONNECTED) {
            AdMobManager.onVpnConnectedStable()
            startConnectedAdFlow()
            return
        }
        binding.tvVpnState.setText(state.labelRes())
        binding.imgSwitch.setImageResource(
            when (state) {
                VpnState.CONNECTING,
                VpnState.CONNECTED -> R.drawable.ic_swich

                VpnState.DISCONNECTING,
                VpnState.DISCONNECTED -> R.drawable.ic_disswich
            },
        )

        when (state) {
            VpnState.CONNECTED -> {
                AdMobManager.onVpnConnectedStable()
                AdMobManager.preloadIntersConnectOnVpnConnected(
                    applicationContext,
                    VpnAdController.LOCAL_IP_KEY,
                )
                finishFlow()
            }

            VpnState.DISCONNECTED -> {
                cancelPendingConnectAdFlow()
                cancelPendingDisconnectAdFlow(renderCurrentState = false)
                AdMobManager.clearAllAdsForRealDisconnect()
                AdMobManager.onVpnDisconnected()
                finishFlow()
            }

            VpnState.CONNECTING,
            VpnState.DISCONNECTING -> Unit
        }
    }

    private fun renderStats(stats: VpnStatsSnapshot) {
        binding.vpnTimerSection.visibility = if (stats.connected) View.VISIBLE else View.GONE
        if (stats.connected) {
            val remaining = stats.connectionRemainingSeconds
            binding.tvTimerHours.text = "%02d".format(remaining / 3_600)
            binding.tvTimerMinutes.text = "%02d".format((remaining % 3_600) / 60)
            binding.tvTimerSeconds.text = "%02d".format(remaining % 60)
        }
        binding.cardStatsEmpty.visibility = if (stats.connected) View.GONE else View.VISIBLE
        binding.statsCard.visibility = if (stats.connected) View.VISIBLE else View.GONE
        binding.tvDataProtected.text = stats.dataProtected
        binding.tvAdsBlocked.text = stats.adsBlocked.toString()
        binding.tvMalwareBlocked.text = stats.malwareBlocked.toString()
        binding.tvRealIpHidden.text = stats.realIpHidden
    }

    @Suppress("DEPRECATION")
    private fun readSelectedServer(data: Intent?): VpnServer? =
        data?.getSerializableExtra(VpnNodeListActivity.EXTRA_SELECTED_SERVER) as? VpnServer

    private enum class PendingOperation {
        CONNECT,
        DISCONNECT,
    }

    private fun VpnState.labelRes(): Int = when (this) {
        VpnState.CONNECTED -> R.string.vpn_state_connected
        VpnState.CONNECTING -> R.string.vpn_state_connecting
        VpnState.DISCONNECTING -> R.string.vpn_state_disconnecting
        VpnState.DISCONNECTED -> R.string.vpn_state_disconnected
    }

    companion object {
        private const val OPERATION_DELAY_MS = 2_000L
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val CONNECTED_AD_TRIGGER_DELAY_MS = 1_000L
        private const val DISCONNECT_AD_MIN_DELAY_MS = 2_000L
        private const val NATIVE_AD_POLL_INTERVAL_MS = 300L
        private const val PREFS_NAME = "vpn_activity_prefs"
        private const val KEY_CONNECT_GUIDE_SHOWN = "connect_guide_shown"
    }
}
