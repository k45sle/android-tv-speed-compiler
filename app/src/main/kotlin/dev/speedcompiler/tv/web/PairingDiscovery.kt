package dev.speedcompiler.tv.web

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.NetworkInterface

/** Finds the TV's own wireless-debugging pairing endpoint (_adb-tls-pairing._tcp). */
class PairingDiscovery(private val nsd: NsdManager) {
    @Volatile var port: Int? = null
        private set
    @Volatile var host: String? = null
        private set
    @Volatile private var name: String? = null

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    // Another advertiser on the LAN must not replace this TV's own host or port.
                    val own = resolved.host?.takeIf(::isOwnAddress) ?: return
                    name = resolved.serviceName
                    port = resolved.port
                    host = own.hostAddress
                }
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            })
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            if (info.serviceName == name) { port = null; host = null }
        }
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() = nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, listener)

    fun stop() = runCatching { nsd.stopServiceDiscovery(listener) }

    companion object {
        /** Only this device's own interface addresses are trusted; a spoofed mDNS answer must not redirect pairing. */
        fun isOwnAddress(a: InetAddress): Boolean = runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().any { ni -> ni.inetAddresses.asSequence().any { it == a } }
        }.getOrDefault(false)
    }
}
