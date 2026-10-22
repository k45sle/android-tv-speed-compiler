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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "Pairing", android.app.NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            1,
            android.app.Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Pairing in progress")
                .build(),
        )
        if (server == null) {
            val graph = (application as dev.speedcompiler.tv.SpeedApp).graph
            val d = dev.speedcompiler.tv.web.PairingDiscovery(getSystemService(android.net.nsd.NsdManager::class.java))
            val s = dev.speedcompiler.tv.web.PairingWebServer(
                session = dev.speedcompiler.tv.web.PairingSession(graph.clock),
                pairing = graph.pairing,
                host = { d.host ?: localIp() ?: "127.0.0.1" },
                pairingPort = { d.port },
                onFinished = { stopSelf() },
            )
            s.start()
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

        /** Page URL while pairing is running, else null. */
        val url = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
}
