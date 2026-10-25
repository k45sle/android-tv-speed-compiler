package dev.speedcompiler.tv

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import dev.speedcompiler.tv.watch.WatchService
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
 * service keeps the process uncached. Robolectric cannot cache a process, so these check the pieces that keep it alive.
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
}
