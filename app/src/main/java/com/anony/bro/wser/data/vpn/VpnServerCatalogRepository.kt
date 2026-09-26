package com.anony.bro.wser.data.vpn

import com.anony.bro.wser.data.DataHubTool

/** Provides the latest dispatch catalog loaded from the local cache or network. */
object VpnServerCatalogRepository {
    fun cachedOrEmpty(): VpnServerCatalog = runCatching {
        VpnServerCatalogParser.parse(DataHubTool.vpnData)
    }.getOrElse {
        VpnServerCatalog(
            cacheLifetimeSec = "",
            failoverSwitch = false,
            fallbackDns = "8.8.8.8",
            pingSwitch = "",
            fastIndex = -1,
            fbid = "",
            notice = null,
            adConfig = "",
            endpointCluster = emptyList(),
        )
    }
}
