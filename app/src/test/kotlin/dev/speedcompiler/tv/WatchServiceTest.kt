package dev.speedcompiler.tv

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import android.os.PowerManager
import dev.speedcompiler.tv.watch.ACTION_IDLE_CHECK
import dev.speedcompiler.tv.watch.ACTION_SAMPLE
import dev.speedcompiler.tv.watch.WakeReceiver
import dev.speedcompiler.tv.watch.WatchService
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.speedcompiler.tv.watch.startWatchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

/**
 * Android defers SCREEN_OFF to a cached app and freezes it, so the screen-off receiver only works while a foreground
 * service keeps its process (":watch", apart from the UI) uncached. Robolectric cannot cache a process, so these check the pieces that keep it alive.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WatchServiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theAppStartsTheServiceWhenItsProcessStarts() {
        val started = shadowOf(ctx as android.app.Application).nextStartedService
        assertEquals(WatchService::class.java.name, started.component!!.className)
    }

    @Test fun theServiceGoesForegroundAndStaysStarted() {
        val service = Robolectric.buildService(WatchService::class.java).create().get()
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
        assertNotNull(shadowOf(service).lastForegroundNotification)
    }

    @Test fun theServiceIsDeclaredAsAPrivateSpecialUseForegroundService() {
        val info = ctx.packageManager.getServiceInfo(ComponentName(ctx, WatchService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        assertEquals("${ctx.packageName}:watch", info.processName) // its process holds no UI or GPU memory
    }

    @Test fun theServiceForwardsScreenOffAndAMinuteTickToTheMainProcess() {
        val app = shadowOf(ctx as android.app.Application)
        val watch = Robolectric.buildService(WatchService::class.java).create()
        ctx.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        val idle = app.broadcastIntents.single { it.action == ACTION_IDLE_CHECK }
        assertEquals(WakeReceiver::class.java.name, idle.component!!.className)
        shadowOf(ctx.getSystemService(PowerManager::class.java)).turnScreenOn(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertEquals(2, app.broadcastIntents.count { it.action == ACTION_SAMPLE && it.component?.className == WakeReceiver::class.java.name })
        shadowOf(ctx.getSystemService(PowerManager::class.java)).turnScreenOn(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertEquals(2, app.broadcastIntents.count { it.action == ACTION_SAMPLE }) // no ticks with the screen off
        watch.destroy()
    }

    @Test fun aRefusedBackgroundStartDoesNotCrash() {
        var tried = false
        startWatchService(object : ContextWrapper(ctx) {
            override fun startForegroundService(service: Intent): ComponentName? {
                tried = true
                throw IllegalStateException("app is in the background")
            }
        })
        assertTrue(tried)
    }

    @Test fun aSampleBroadcastRunsOneTickAndSkipsOverlaps() = runBlocking {
        val app = ctx as SpeedApp
        val release = CompletableDeferred<Unit>()
        var ticks = 0
        app.sampleTick = { ticks++; release.await() }
        val wake = WakeReceiver().also { ctx.registerReceiver(it, IntentFilter(ACTION_SAMPLE), Context.RECEIVER_NOT_EXPORTED) }
        ctx.sendBroadcast(Intent(ACTION_SAMPLE).setClass(ctx, WakeReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(5_000) { while (ticks == 0) delay(10) }
        app.sampleOnce() // the first tick still runs, so this one is skipped
        assertEquals(1, ticks)
        release.complete(Unit)
        ctx.unregisterReceiver(wake)
    }

    @Test fun aHungOrFailingTickIsCutOffAndSwallowed() = runBlocking {
        val app = ctx as SpeedApp
        app.sampleTick = { awaitCancellation() }
        withTimeout(SAMPLE_TIMEOUT_MS + 5_000) { app.sampleOnce() } // returns at the cap instead of hanging
        app.sampleTick = { error("adb broke") }
        app.sampleOnce() // no throw
        var ran = false
        app.sampleTick = { ran = true }
        app.sampleOnce() // the lock was released both times
        assertTrue(ran)
    }
}
