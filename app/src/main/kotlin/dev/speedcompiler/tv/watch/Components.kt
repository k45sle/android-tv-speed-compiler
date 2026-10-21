package dev.speedcompiler.tv.watch

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Stub: chunk for boot handling fills this in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Stub: pairing foreground service, filled in by the pairing chunk. */
class PairingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
