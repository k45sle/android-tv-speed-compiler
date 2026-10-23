package dev.speedcompiler.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.speedcompiler.tv.watch.PairingService
import java.io.IOException
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

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PairingServiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun bootReceiverIsNotExported() {
        val receivers = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_RECEIVERS).receivers!!
        assertFalse(receivers.single { it.name.endsWith("BootReceiver") }.exported)
    }

    @Test fun aBindFailureStopsTheServiceInsteadOfCrashing() {
        val old = PairingService.bind
        PairingService.bind = { throw IOException("address in use") }
        try {
            val service = Robolectric.buildService(PairingService::class.java).create().get()
            service.onStartCommand(Intent(), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { PairingService.bind = old }
    }

    @Test fun usesAnAppIconAndOneChannel() {
        val service = Robolectric.buildService(PairingService::class.java).create().get()
        service.onStartCommand(Intent(), 0, 1)
        service.onStartCommand(Intent(), 0, 2)
        val icon = shadowOf(service).lastForegroundNotification.smallIcon
        assertEquals(R.drawable.ic_stat_pairing, icon.resId)
        assertNotNull(ctx.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("pairing"))
        service.onDestroy()
    }
}
