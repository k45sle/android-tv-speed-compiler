package dev.speedcompiler.tv.ui.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import dev.speedcompiler.tv.AppGraph
import dev.speedcompiler.tv.SpeedApp
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Label and artwork for a package; falls back to the package name when PackageManager does not know it. */
class AppLook(val label: String, val icon: ImageBitmap? = null, val banner: ImageBitmap? = null)

@Composable
fun rememberGraph(): AppGraph = LocalContext.current.let { c -> remember { (c.applicationContext as SpeedApp).graph } }

/** Loads on Dispatchers.IO and caches for the screen's life. */
@Composable
fun rememberLook(): (String) -> AppLook {
    val context = LocalContext.current
    val cache = remember { mutableStateMapOf<String, AppLook>() }
    val pending = remember { HashSet<String>() }
    val scope = rememberCoroutineScope()
    // Package name until the IO load lands, then the real label and art.
    return remember {
        { p ->
            cache[p] ?: AppLook(p).also { if (pending.add(p)) scope.launch(Dispatchers.IO) { cache[p] = lookup(context, p) } }
        }
    }
}

internal fun lookup(context: Context, pkg: String): AppLook = runCatching {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(pkg, 0)
    AppLook(
        pm.getApplicationLabel(info).toString(),
        pm.getApplicationIcon(info).toBitmap(64, 64).asImageBitmap(),
        (pm.getApplicationBanner(info) ?: pm.getApplicationIcon(info)).toBitmap(160, 90).asImageBitmap(),
    )
}.getOrElse { AppLook(pkg) }

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val date = DateTimeFormatter.ofPattern("EEE MMM d")
private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm")

fun clockText(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    stamp.format(Instant.ofEpochMilli(millis).atZone(zone))

/** Never a percentage. Empty when there is nothing to say (running or finished). */
fun etaText(eta: Eta, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String = when (eta) {
    Eta.None -> ""
    is Eta.StartsAt -> {
        val at = eta.at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        when (at.toLocalDate()) {
            today -> "Starts at ${clock.format(at)}"
            today.plusDays(1) -> "Starts tomorrow ${clock.format(at)}"
            else -> "Starts ${date.format(at)} ${clock.format(at)}"
        }
    }
    Eta.WaitingForIdle -> "Waiting for the TV to be idle"
    Eta.WaitingForPlayback -> "Waiting for playback to stop"
    Eta.Paused -> "Auto-optimize is off"
    is Eta.InQueue -> "#${eta.position + 1} in queue, about ${(eta.approxMillis / 60_000).coerceAtLeast(1)} min"
}

fun stateText(state: QueueState) = when (state) {
    QueueState.RUNNING -> "Running"
    QueueState.QUEUED -> "Queued"
    QueueState.WAITING_GATE -> "Waiting"
    QueueState.DONE -> "Done"
    QueueState.FAILED -> "Failed"
    QueueState.SKIPPED -> "Skipped"
}
