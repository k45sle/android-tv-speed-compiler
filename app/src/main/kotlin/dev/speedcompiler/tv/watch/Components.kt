package dev.speedcompiler.tv.watch

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.speedcompiler.tv.web.localIp

/** Starts the reconnect and change-detection jobs after boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            enqueueReconnect(context)
            enqueueDetectNow(context)
        }
    }
}

/** Foreground service that runs the pairing web server only while pairing; [url] feeds the QR code. */
class PairingService : Service() {
    private var server: dev.speedcompiler.tv.web.PairingWebServer? = null
    private var discovery: dev.speedcompiler.tv.web.PairingDiscovery? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, getString(dev.speedcompiler.tv.R.string.notif_pairing_channel), android.app.NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            1,
            android.app.Notification.Builder(this, CHANNEL)
                .setSmallIcon(dev.speedcompiler.tv.R.drawable.ic_stat_pairing)
                .setContentTitle(getString(dev.speedcompiler.tv.R.string.notif_pairing_title))
                .build(),
        )
        if (server == null) {
            val graph = (application as dev.speedcompiler.tv.SpeedApp).graph
            val d = dev.speedcompiler.tv.web.PairingDiscovery(getSystemService(android.net.nsd.NsdManager::class.java))
            val s = dev.speedcompiler.tv.web.PairingWebServer(
                res = resources,
                session = dev.speedcompiler.tv.web.PairingSession(graph.clock),
                pairing = graph.pairing,
                host = { d.host ?: localIp() ?: "127.0.0.1" },
                pairingPort = { d.port },
                onFinished = { stopSelf() },
                onPaired = { graph.connectionMonitor.connectNow() },
            )
            try {
                bind(s)
            } catch (e: java.io.IOException) { // no socket to serve the page on
                stopSelf()
                return START_NOT_STICKY
            }
            d.start()
            discovery = d
            server = s
            url.value = "http://${localIp() ?: "127.0.0.1"}:${s.port}/${s.token}"
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        url.value = null
        discovery?.stop()
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "pairing"

        /** Seam for tests: starts the web server. */
        internal var bind: (dev.speedcompiler.tv.web.PairingWebServer) -> Unit = { it.start() }

        /** Page URL while pairing is running, else null. */
        val url = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
}
