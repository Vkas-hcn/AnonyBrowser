package com.anony.bro.wser.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class VpnStatsSnapshot(
    val connected: Boolean = false,
    val connectionRemainingSeconds: Int = 0,
    val dataProtected: String = "0 B",
    val adsBlocked: Int = 0,
    val malwareBlocked: Int = 0,
    val realIpHidden: String = "Hidden",
)

object VpnStatsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _stats = MutableStateFlow(VpnStatsSnapshot())

    val stats: StateFlow<VpnStatsSnapshot> = _stats.asStateFlow()

    private var initialized = false
    private var simulationJob: Job? = null
    private var latestTrafficBytes = 0L
    private var adsBlocked = 0
    private var malwareBlocked = 0
    private var nextAdsIncrementInSeconds = 0
    private var nextMalwareIncrementInSeconds = 0
    private var elapsedSeconds = 0

    private val callback = object : VpnCallback {
        override fun onStateChanged(state: VpnState) {
            when (state) {
                VpnState.CONNECTED -> start()
                VpnState.DISCONNECTED -> stop(reset = true)
                VpnState.CONNECTING,
                VpnState.DISCONNECTING -> Unit
            }
        }

        override fun onTrafficUpdate(
            uploadSpeed: Long,
            downloadSpeed: Long,
            totalUpload: Long,
            totalDownload: Long,
        ) {
            latestTrafficBytes = (totalUpload + totalDownload).coerceAtLeast(0L)
            publish()
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        VpnManager.addCallback(callback)
        if (VpnManager.state == VpnState.CONNECTED) {
            start()
        }
    }

    private fun start() {
        simulationJob?.cancel()
        latestTrafficBytes = 0L
        adsBlocked = 0
        malwareBlocked = 0
        elapsedSeconds = 0
        nextAdsIncrementInSeconds = randomStatsWindow()
        nextMalwareIncrementInSeconds = randomStatsWindow()
        publish(connected = true)

        simulationJob = scope.launch {
            while (isActive && VpnManager.state == VpnState.CONNECTED) {
                delay(STATS_UPDATE_MS)
                elapsedSeconds += 1

                if (elapsedSeconds >= nextAdsIncrementInSeconds) {
                    adsBlocked += Random.nextInt(1, 6)
                    nextAdsIncrementInSeconds = elapsedSeconds + randomStatsWindow()
                }
                if (elapsedSeconds >= nextMalwareIncrementInSeconds) {
                    malwareBlocked += Random.nextInt(1, 4)
                    nextMalwareIncrementInSeconds = elapsedSeconds + randomStatsWindow()
                }

                publish(connected = true)
            }
        }
    }

    private fun stop(reset: Boolean) {
        simulationJob?.cancel()
        simulationJob = null
        if (reset) {
            latestTrafficBytes = 0L
            adsBlocked = 0
            malwareBlocked = 0
            elapsedSeconds = 0
            publish(connected = false)
        }
    }

    private fun publish(connected: Boolean = _stats.value.connected) {
        val protectedBytes = if (latestTrafficBytes < MIN_PROTECTED_BYTES) 0L else latestTrafficBytes
        _stats.value = VpnStatsSnapshot(
            connected = connected,
            connectionRemainingSeconds = if (connected) VpnManager.getConnectionRemainingSeconds() else 0,
            dataProtected = VpnManager.formatBytes(protectedBytes),
            adsBlocked = adsBlocked,
            malwareBlocked = malwareBlocked,
            realIpHidden = "Hidden",
        )
    }

    private fun randomStatsWindow(): Int = Random.nextInt(30, 61)

    private const val STATS_UPDATE_MS = 1_000L
    private const val MIN_PROTECTED_BYTES = 1024L
}
