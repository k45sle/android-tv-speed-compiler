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
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.watch.DEFAULT_AVG_DURATION_MILLIS
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
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
    Eta.Unknown -> "Unknown"
    is Eta.InQueue -> "#${eta.position + 1} in queue, about ${(eta.approxMillis / 60_000).coerceAtLeast(1)} min"
}

/**
 * "k of N, about M min left" for a batch of two or more jobs: [done] finished ones plus the active [queue]. Null for a single job.
 * The minutes are left out when any job waits on something with no time (gate, pairing). Per-job time comes from the queue's own
 * [Eta.InQueue] figures, else the default.
 */
fun aggregateLine(done: Int, queue: List<QueueEntry>): String? {
    val total = done + queue.size
    if (total < 2 || queue.isEmpty()) return null
    val blind = queue.any { it.eta != Eta.None && it.eta !is Eta.InQueue }
    val per = queue.firstNotNullOfOrNull { (it.eta as? Eta.InQueue)?.takeIf { e -> e.position > 0 }?.let { e -> e.approxMillis / e.position } }
        ?: DEFAULT_AVG_DURATION_MILLIS
    val left = if (blind) "" else ", about ${(per * queue.size / 60_000).coerceAtLeast(1)} min left"
    return "$done of $total$left"
}

/** Jobs that ran (done or failed, not cancelled) since the oldest active one was queued. */
// shortcut: jobs that finished while an old job sat at the gate count toward its batch; store a batch id if that misleads.
fun batchDone(queue: List<QueueEntry>, history: List<QueueItem>): Int {
    val since = queue.minOfOrNull { it.item.enqueuedAt } ?: return 0
    return history.count { it.state != QueueState.SKIPPED && (it.finishedAt ?: 0) >= since }
}

fun stateText(state: QueueState) = when (state) {
    QueueState.RUNNING -> "Running"
    QueueState.QUEUED -> "Queued"
    QueueState.WAITING_GATE -> "Waiting"
    QueueState.DONE -> "Done"
    QueueState.FAILED -> "Failed"
    QueueState.SKIPPED -> "Skipped"
}

/** The one label for every repair button (B-12). */
const val FIX_LABEL = "Fix connection"

/** Where "Fix connection" goes for each [Fix]; only a forgotten pairing needs the repair flow. */
fun fixRoute(fix: Fix) = if (fix == Fix.QuickRepair) Routes.QuickRepair else Routes.Reconnect

/** What the user should do, shown next to a failed connection (B-09). */
fun fixText(fix: Fix) = when (fix) {
    Fix.QuickRepair -> "The TV forgot this app. Approve it again on the TV."
    Fix.TurnOnWirelessDebugging -> "Turn on Wireless debugging in Developer options on the TV."
    Fix.AllowPrompt -> "Press Allow on the prompt on the TV."
    Fix.Retry -> "The TV did not answer. Try again."
}

/** What happened to an app's latest job (B-22). */
enum class JobResult(val label: String, val tone: Tone) {
    Verified("Optimized", Tone.Ok),
    Unverified("Compiled", Tone.Warn),
    Failed("Didn't finish", Tone.Error),
    Running("Running", Tone.Info),
    Queued("Queued", Tone.Neutral),
}

/** Live queue state wins; otherwise the stored result text. Null when the app has never been optimized. */
fun jobResult(lastResult: String?, queueState: QueueState?): JobResult? = when {
    queueState == QueueState.RUNNING -> JobResult.Running
    queueState == QueueState.QUEUED || queueState == QueueState.WAITING_GATE -> JobResult.Queued
    lastResult == null -> null
    lastResult.startsWith("Optimized (checked)") -> JobResult.Verified
    lastResult.startsWith("Optimized") -> JobResult.Unverified
    else -> JobResult.Failed
}
