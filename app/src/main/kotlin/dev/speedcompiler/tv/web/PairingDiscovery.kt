package dev.speedcompiler.tv.web

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Finds the TV's own wireless-debugging pairing endpoint (_adb-tls-pairing._tcp). */
class PairingDiscovery(private val nsd: NsdManager) {
    @Volatile var port: Int? = null
        private set
    @Volatile var host: String? = null
        private set

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    port = resolved.port
                    host = resolved.host?.hostAddress
                }
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            })
        }
        override fun onServiceLost(info: NsdServiceInfo) { port = null }
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() = nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, listener)

    fun stop() = runCatching { nsd.stopServiceDiscovery(listener) }
}
