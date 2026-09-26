package com.anony.bro.wser.view.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.anony.bro.wser.R
import com.anony.bro.wser.base.BaseActivity
import com.anony.bro.wser.data.DataHubTool
import com.anony.bro.wser.data.vpn.VpnServer
import com.anony.bro.wser.data.vpn.VpnServerCatalogParser
import com.anony.bro.wser.databinding.ActivityVpnNodeListBinding
import com.anony.bro.wser.vpn.VpnManager
import com.anony.bro.wser.vpn.VpnState

class VpnNodeListActivity : BaseActivity<ActivityVpnNodeListBinding, VpnNodeListViewModel>() {

    override val viewModel: VpnNodeListViewModel by viewModels()

    override val applySystemBarPadding: Boolean = false

    private val adapter = VpnNodeListAdapter(::onNodeClicked)
    private var selectedServer: VpnServer? = null
    private var lastClickUptimeMs = 0L

    override fun inflateBinding(inflater: LayoutInflater): ActivityVpnNodeListBinding =
        ActivityVpnNodeListBinding.inflate(inflater)

    override fun initViews(savedInstanceState: Bundle?) {
        setupSystemBars()
        selectedServer = readSelectedServerExtra()

        binding.btnBack.setOnClickListener { finish() }
        binding.rvNodes.layoutManager = LinearLayoutManager(this)
        binding.rvNodes.adapter = adapter

        loadServers()
    }

    override fun observeData() = Unit

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

    private fun loadServers() {
        val servers = runCatching {
            VpnServerCatalogParser.parse(DataHubTool.vpnData).endpointCluster
        }.onFailure {
            showToast(it.message ?: getString(R.string.vpn_config_unavailable))
        }.getOrDefault(emptyList())

        val hasServers = servers.isNotEmpty()
        binding.rvNodes.visibility = if (hasServers) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (hasServers) View.GONE else View.VISIBLE
        adapter.submit(servers, selectedServer)
    }

    private fun onNodeClicked(server: VpnServer) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastClickUptimeMs < CLICK_DEBOUNCE_MS) return
        lastClickUptimeMs = now

        if (VpnManager.state == VpnState.CONNECTED && !server.isSameEndpoint(selectedServer)) {
            showToast(getString(R.string.vpn_node_switch_blocked))
            return
        }

        if (VpnManager.state != VpnState.CONNECTED) {
            selectedServer = server
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_SELECTED_SERVER, server),
            )
            finish()
            return
        }

        finish()
    }

    @Suppress("DEPRECATION")
    private fun readSelectedServerExtra(): VpnServer? =
        intent.getSerializableExtra(EXTRA_SELECTED_SERVER) as? VpnServer

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SELECTED_SERVER = "extra_selected_server"
        private const val CLICK_DEBOUNCE_MS = 1000L

        fun createIntent(context: Context, selectedServer: VpnServer?): Intent =
            Intent(context, VpnNodeListActivity::class.java).apply {
                putExtra(EXTRA_SELECTED_SERVER, selectedServer)
            }
    }
}
