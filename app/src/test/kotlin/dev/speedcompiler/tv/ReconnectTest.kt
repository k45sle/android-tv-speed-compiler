package dev.speedcompiler.tv

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.speedcompiler.tv.adb.AdbFailure
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.watch.BootReceiver
import dev.speedcompiler.tv.watch.RECONNECT_WORK
import dev.speedcompiler.tv.watch.enqueueReconnect
import dev.speedcompiler.tv.watch.reconnectWithRetry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

private val NOT_PAIRED = KeeperStatus.Failed(AdbFailure.NotPaired(), Fix.QuickRepair)
private val TIMEOUT = KeeperStatus.Failed(AdbFailure.Timeout(), Fix.Retry)

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReconnectTest {
    private val log = mutableListOf<String>()
    private val pauses = mutableListOf<Long>()

    private suspend fun run(network: Boolean, vararg results: KeeperStatus, turnOnWifiAdb: Boolean = true) {
        val queue = results.toMutableList()
        reconnectWithRetry(
            turnOnWifiAdb = turnOnWifiAdb,
            waitForNetwork = { log += "net"; network },
            enableWifiAdb = { log += "wifi" },
            reconnect = { log += "connect"; queue.removeAt(0) },
            pause = { pauses += it },
            backoff = listOf(1L, 2L, 3L),
        )
    }

    @Test fun connectsFirstTry() = runTest {
        run(true, KeeperStatus.Connected)
        assertEquals(listOf("net", "wifi", "connect"), log)
    }

    @Test fun settingOffNeverEnablesWirelessDebugging() = runTest {
        run(true, TIMEOUT, KeeperStatus.Connected, turnOnWifiAdb = false)
        assertEquals(0, log.count { it == "wifi" })
    }

    @Test fun enablesWirelessDebuggingOncePerRun() = runTest {
        run(true, TIMEOUT, KeeperStatus.Connected)
        assertEquals(1, log.count { it == "wifi" })
    }

    @Test fun retriesThenConnects() = runTest {
        run(true, TIMEOUT, TIMEOUT, KeeperStatus.Connected)
        assertEquals(3, log.count { it == "connect" })
        assertEquals(listOf(1L, 2L), pauses)
    }

    @Test fun givesUpAfterAllTries() = runTest {
        run(true, TIMEOUT, TIMEOUT, TIMEOUT, TIMEOUT)
        assertEquals(4, log.count { it == "connect" })
        assertEquals(listOf(1L, 2L, 3L), pauses)
    }

    @Test fun stopsEarlyWhenNotPaired() = runTest {
        run(true, NOT_PAIRED)
        assertEquals(1, log.count { it == "connect" })
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test fun triesOnceEvenWithoutNetwork() = runTest {
        run(false, KeeperStatus.Connected)
        assertEquals(listOf("net", "wifi", "connect"), log)
    }

    @Test fun bootReceiverEnqueuesUniqueReconnect() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().build())
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(RECONNECT_WORK).get().size)
    }

    @Test fun secondReconnectKeepsTheQueuedOne() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().build())
        val wm = WorkManager.getInstance(ctx)
        enqueueReconnect(ctx)
        val first = wm.getWorkInfosForUniqueWork(RECONNECT_WORK).get().single().id
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(first, wm.getWorkInfosForUniqueWork(RECONNECT_WORK).get().single().id)
    }
}
