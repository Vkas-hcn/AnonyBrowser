package com.anony.bro.wser.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.*
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.security.KeyStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class LibboxEngine(private val vpnService: WebloraVpnService) {

    companion object {
        private const val TAG = "LibboxEngine"
        private var isLibboxSetup = false
    }

    private var commandServer: CommandServer? = null
    private var isRunning = false
    var fileDescriptor: ParcelFileDescriptor? = null
        private set

    private val connectivityManager by lazy {
        vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var defaultNetwork: Network? = null
    private var interfaceListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start(configJson: String) {
        if (isRunning) {
            Log.w(TAG, "Engine is already running")
            return
        }

        try {
            val version = Libbox.version()
            Log.d(TAG, "Libbox version: $version")

            setupDirectories()

            val platformInterface = createPlatformInterface()
            val serverHandler = createServerHandler()

            commandServer = CommandServer(serverHandler, platformInterface).also {
                it.start()
            }

            startNetworkMonitor()

            // 应用分应用代理配置
            val overrideOptions = createOverrideOptions()
            commandServer?.startOrReloadService(configJson, overrideOptions)

            isRunning = true
            Log.d(TAG, "LibboxEngine started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}", e)
            stopNetworkMonitor()
            commandServer = null
            throw e
        }
    }

    fun stop() {
        if (!isRunning) return

        try {
            stopNetworkMonitor()

            commandServer?.let { server ->
                runCatching { server.closeService() }
                runCatching { server.close() }
            }

            fileDescriptor?.let {
                it.close()
                fileDescriptor = null
            }

            commandServer = null
            isRunning = false
            Log.d(TAG, "LibboxEngine stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}", e)
        }
    }

    fun isRunning(): Boolean = isRunning

    // --- Libbox Setup ---

    private fun setupDirectories() {
        if (isLibboxSetup) return

        val baseDir = vpnService.filesDir.apply { mkdirs() }
        val workingDir = (vpnService.getExternalFilesDir(null) ?: vpnService.filesDir).apply { mkdirs() }
        val tempDir = vpnService.cacheDir.apply { mkdirs() }

        Libbox.setup(SetupOptions().apply {
            basePath = baseDir.path
            workingPath = workingDir.path
            tempPath = tempDir.path
        })

        isLibboxSetup = true
    }

    // --- PlatformInterface ---

    private fun createPlatformInterface(): PlatformInterface {
        return object : PlatformInterface {

            override fun autoDetectInterfaceControl(fd: Int) {
                vpnService.protect(fd)
            }

            override fun openTun(options: TunOptions?): Int {
                if (options == null) return -1
                if (VpnService.prepare(vpnService) != null) {
                    error("android: missing vpn permission")
                }

                val builder = vpnService.createVpnBuilder()
                    .setSession("sing-box")
                    .setMtu(options.mtu)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                options.inet4Address.let { iter ->
                    while (iter.hasNext()) {
                        val addr = iter.next()
                        builder.addAddress(addr.address(), addr.prefix())
                    }
                }
                options.inet6Address.let { iter ->
                    while (iter.hasNext()) {
                        val addr = iter.next()
                        builder.addAddress(addr.address(), addr.prefix())
                    }
                }

                if (options.autoRoute) {
                    builder.addDnsServer(options.dnsServerAddress.value)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setupRoutesApi33(builder, options)
                    } else {
                        setupRoutesLegacy(builder, options)
                    }

                    setupAppFilters(builder, options)
                }

                val pfd = builder.establish()
                    ?: error("android: the application is not prepared or is revoked")

                fileDescriptor = pfd
                Log.d(TAG, "VPN interface established, fd: ${pfd.fd}")
                return pfd.fd
            }

            override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
            override fun underNetworkExtension(): Boolean = false
            override fun includeAllNetworks(): Boolean = false
            override fun clearDNSCache() {}
            override fun readWIFIState(): WIFIState? = null
            override fun registerMyInterface(name: String?) {}

            override fun findConnectionOwner(
                ipProtocol: Int,
                sourceAddress: String?,
                sourcePort: Int,
                destinationAddress: String?,
                destinationPort: Int
            ): ConnectionOwner? {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        val uid = connectivityManager.getConnectionOwnerUid(
                            ipProtocol,
                            InetSocketAddress(sourceAddress, sourcePort),
                            InetSocketAddress(destinationAddress, destinationPort)
                        )
                        return ConnectionOwner().apply {
                            userId = uid
                            userName = vpnService.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
                        }
                    }
                }
                return null
            }

            override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

            override fun sendNotification(notification: Notification?) {}

            override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
                if (listener != null) {
                    interfaceListener = listener
                    notifyInterfaceUpdate(defaultNetwork)
                }
            }

            override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
                interfaceListener = null
            }

            override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
            override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

            override fun getInterfaces(): NetworkInterfaceIterator? {
                return buildNetworkInterfaceList()
            }

            override fun localDNSTransport(): LocalDNSTransport? = null

            @OptIn(ExperimentalEncodingApi::class)
            override fun systemCertificates(): StringIterator? {
                return runCatching {
                    val certs = mutableListOf<String>()
                    val keyStore = KeyStore.getInstance("AndroidCAStore")
                    keyStore.load(null, null)
                    val aliases = keyStore.aliases()
                    while (aliases.hasMoreElements()) {
                        val cert = keyStore.getCertificate(aliases.nextElement())
                        certs.add(
                            "-----BEGIN CERTIFICATE-----\n" +
                                    Base64.encode(cert.encoded) +
                                    "\n-----END CERTIFICATE-----"
                        )
                    }
                    IteratorStringArray(certs.iterator())
                }.getOrNull()
            }
        }
    }

    // --- Route helpers ---

    private fun setupRoutesApi33(builder: VpnService.Builder, options: TunOptions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val inet4Route = options.inet4RouteAddress
        if (inet4Route.hasNext()) {
            while (inet4Route.hasNext()) {
                val r = inet4Route.next()
                builder.addRoute(r.address(), r.prefix())
            }
        } else if (options.inet4Address.hasNext()) {
            builder.addRoute("0.0.0.0", 0)
        }

        val inet6Route = options.inet6RouteAddress
        if (inet6Route.hasNext()) {
            while (inet6Route.hasNext()) {
                val r = inet6Route.next()
                builder.addRoute(r.address(), r.prefix())
            }
        } else if (options.inet6Address.hasNext()) {
            builder.addRoute("::", 0)
        }

        options.inet4RouteExcludeAddress.let { iter ->
            while (iter.hasNext()) {
                val r = iter.next()
                builder.excludeRoute(
                    IpPrefix(InetAddress.getByName(r.address()), r.prefix())
                )
            }
        }
        options.inet6RouteExcludeAddress.let { iter ->
            while (iter.hasNext()) {
                val r = iter.next()
                builder.excludeRoute(
                    IpPrefix(InetAddress.getByName(r.address()), r.prefix())
                )
            }
        }
    }

    private fun setupRoutesLegacy(builder: VpnService.Builder, options: TunOptions) {
        options.inet4RouteRange.let { iter ->
            while (iter.hasNext()) {
                val a = iter.next()
                builder.addRoute(a.address(), a.prefix())
            }
        }
        options.inet6RouteRange.let { iter ->
            while (iter.hasNext()) {
                val a = iter.next()
                builder.addRoute(a.address(), a.prefix())
            }
        }
    }

    private fun setupAppFilters(builder: VpnService.Builder, options: TunOptions) {
        options.includePackage.let { iter ->
            while (iter.hasNext()) {
                runCatching { builder.addAllowedApplication(iter.next()) }
            }
        }
        options.excludePackage.let { iter ->
            while (iter.hasNext()) {
                runCatching { builder.addDisallowedApplication(iter.next()) }
            }
        }
    }

    // --- Network Interface enumeration ---

    private fun buildNetworkInterfaceList(): NetworkInterfaceIterator? {
        return runCatching {
            val networks = connectivityManager.allNetworks
            val systemInterfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val result = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()

            for (network in networks) {
                val lp = connectivityManager.getLinkProperties(network) ?: continue
                val nc = connectivityManager.getNetworkCapabilities(network) ?: continue
                val sysIf = systemInterfaces.find { it.name == lp.interfaceName } ?: continue

                result.add(NetworkInterface().apply {
                    name = lp.interfaceName
                    index = sysIf.index
                    runCatching { mtu = sysIf.mtu }
                    type = when {
                        nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                        nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                        nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    addresses = IteratorStringArray(
                        sysIf.interfaceAddresses.map { it.toPrefix() }.iterator()
                    )
                    dnsServer = IteratorStringArray(
                        lp.dnsServers.mapNotNull { it.hostAddress }.iterator()
                    )
                    var f = 0
                    if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        f = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                    }
                    if (sysIf.isLoopback) f = f or OsConstants.IFF_LOOPBACK
                    if (sysIf.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
                    if (sysIf.supportsMulticast()) f = f or OsConstants.IFF_MULTICAST
                    flags = f
                    metered = !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                })
            }
            IteratorInterfaceArray(result.iterator())
        }.getOrNull()
    }

    // --- Default Network Monitor ---

    private fun startNetworkMonitor() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                defaultNetwork = connectivityManager.activeNetwork
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                defaultNetwork = network
                notifyInterfaceUpdate(network)
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                if (network == defaultNetwork) notifyInterfaceUpdate(network)
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    interfaceListener?.updateDefaultInterface("", -1, false, false)
                }
            }
        }
        networkCallback = cb

        when {
            Build.VERSION.SDK_INT >= 31 ->
                connectivityManager.registerBestMatchingNetworkCallback(
                    request, cb, Handler(Looper.getMainLooper())
                )
            Build.VERSION.SDK_INT >= 28 ->
                connectivityManager.requestNetwork(request, cb, Handler(Looper.getMainLooper()))
            Build.VERSION.SDK_INT >= 26 ->
                connectivityManager.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
            Build.VERSION.SDK_INT >= 24 ->
                connectivityManager.registerDefaultNetworkCallback(cb)
            else ->
                connectivityManager.requestNetwork(request, cb)
        }
    }

    private fun stopNetworkMonitor() {
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        interfaceListener = null
        defaultNetwork = null
    }

    private fun notifyInterfaceUpdate(network: Network?) {
        val listener = interfaceListener ?: return
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        runCatching {
            val ifName = connectivityManager.getLinkProperties(network)?.interfaceName ?: return
            for (i in 0 until 10) {
                runCatching {
                    val netIf = NetworkInterface.getByName(ifName) ?: return@runCatching
                    listener.updateDefaultInterface(ifName, netIf.index, false, false)
                    return
                }
                Thread.sleep(100)
            }
        }
    }

    // --- CommandServerHandler ---

    private fun createServerHandler(): CommandServerHandler {
        return object : CommandServerHandler {
            override fun serviceReload() {}
            override fun serviceStop() {}
            override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
            override fun setSystemProxyEnabled(enabled: Boolean) {}
            override fun writeDebugMessage(message: String?) {
                if (message != null) Log.d(TAG, "[libbox] $message")
            }
        }
    }

    // --- OverrideOptions ---

    private fun createOverrideOptions(): OverrideOptions {
        val options = OverrideOptions()
        val (mode, packages) = VpnManager.getEffectiveProxyConfig()

        when (mode) {
            PerAppProxyMode.DISABLED -> {
                // 全局代理，不设置包名过滤
            }
            PerAppProxyMode.INCLUDE -> {
                // 白名单模式：只有列表中的应用走代理
                if (packages.isNotEmpty()) {
                    // 添加自己，确保 VPN 服务本身能正常工作
                    val allPackages = packages + vpnService.packageName
                    options.includePackage = IteratorStringArray(allPackages.iterator())
                }
            }
            PerAppProxyMode.EXCLUDE -> {
                // 黑名单模式：列表中的应用不走代理
                if (packages.isNotEmpty()) {
                    // 移除自己（如果误加），确保 VPN 服务本身能正常工作
                    val filteredPackages = packages - vpnService.packageName
                    if (filteredPackages.isNotEmpty()) {
                        options.excludePackage = IteratorStringArray(filteredPackages.iterator())
                    }
                }
            }
        }

        return options
    }

    // --- Iterator wrappers ---

    private class IteratorStringArray(private val iter: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iter.hasNext()
        override fun next(): String = iter.next()
    }

    private class IteratorInterfaceArray(
        private val iter: Iterator<io.nekohasekai.libbox.NetworkInterface>
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iter.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface = iter.next()
    }

    private fun InterfaceAddress.toPrefix(): String {
        return if (address is Inet6Address) {
            "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
        } else {
            "${address.hostAddress}/$networkPrefixLength"
        }
    }
}
