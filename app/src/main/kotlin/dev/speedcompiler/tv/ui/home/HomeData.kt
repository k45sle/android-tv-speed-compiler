package dev.speedcompiler.tv.ui.home

import dev.speedcompiler.tv.engine.RESET_UNSUPPORTED
import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
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
import dev.speedcompiler.tv.R

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
fun etaText(res: Resources, eta: Eta, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String = when (eta) {
    Eta.None -> ""
    is Eta.StartsAt -> {
        val at = eta.at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        when (at.toLocalDate()) {
            today -> res.getString(R.string.home_eta_starts_today, clock.format(at))
            today.plusDays(1) -> res.getString(R.string.home_eta_starts_tomorrow, clock.format(at))
            else -> res.getString(R.string.home_eta_starts_on, date.format(at), clock.format(at))
        }
    }
    Eta.WaitingForIdle -> res.getString(R.string.home_eta_waiting_idle)
    Eta.WaitingForPlayback -> res.getString(R.string.home_eta_waiting_playback)
    Eta.Paused -> res.getString(R.string.home_auto_off)
    Eta.WaitingForStorage -> res.getString(R.string.home_eta_waiting_storage)
    Eta.WaitingForConnection -> res.getString(R.string.home_eta_waiting_connection)
    Eta.Connecting -> res.getString(R.string.home_connecting_to_tv)
    Eta.Checking -> res.getString(R.string.home_eta_checking)
    Eta.Unknown -> res.getString(R.string.home_eta_unknown)
    is Eta.InQueue -> res.getString(R.string.home_eta_in_queue, eta.position + 1, (eta.approxMillis / 60_000).coerceAtLeast(1))
}

/**
 * "k of N, about M min left" for a batch of two or more jobs: [done] finished ones plus the active [queue]. Null for a single job.
 * The minutes are left out when any job waits on something with no time (gate, pairing). Per-job time comes from the queue's own
 * [Eta.InQueue] figures, else the default.
 */
fun aggregateLine(res: Resources, done: Int, queue: List<QueueEntry>): String? {
    val total = done + queue.size
    if (total < 2 || queue.isEmpty()) return null
    val blind = queue.any { it.eta != Eta.None && it.eta !is Eta.InQueue }
    val per = queue.firstNotNullOfOrNull { (it.eta as? Eta.InQueue)?.takeIf { e -> e.position > 0 }?.let { e -> e.approxMillis / e.position } }
        ?: DEFAULT_AVG_DURATION_MILLIS
    val minutes = (per * queue.size / 60_000).coerceAtLeast(1)
    return if (blind) res.getString(R.string.home_batch_progress, done, total)
    else res.getString(R.string.home_batch_progress_left, done, total, minutes)
}

/** Jobs that ran (done or failed, not cancelled) since the oldest active one was queued. */
// shortcut: jobs that finished while an old job sat at the gate count toward its batch; store a batch id if that misleads.
fun batchDone(queue: List<QueueEntry>, history: List<QueueItem>): Int {
    val since = queue.minOfOrNull { it.item.enqueuedAt } ?: return 0
    return history.count { it.state != QueueState.SKIPPED && (it.finishedAt ?: 0) >= since }
}

fun stateText(res: Resources, state: QueueState): String = res.getString(
    when (state) {
        QueueState.RUNNING -> R.string.home_running
        QueueState.QUEUED -> R.string.home_queued
        QueueState.WAITING_GATE -> R.string.home_waiting
        QueueState.DONE -> R.string.common_done
        QueueState.FAILED -> R.string.home_failed
        QueueState.SKIPPED -> R.string.home_skipped
    },
)

/** Where "Fix connection" goes for each [Fix]; only a forgotten pairing needs the repair flow. */
fun fixRoute(fix: Fix) = if (fix == Fix.QuickRepair) Routes.QuickRepair else Routes.Reconnect

/** What the user should do, shown next to a failed connection (B-09). */
fun fixText(res: Resources, fix: Fix): String = res.getString(
    when (fix) {
        Fix.QuickRepair -> R.string.home_fix_forgot
        Fix.TurnOnWirelessDebugging -> R.string.home_fix_wireless
        Fix.AllowPrompt -> R.string.home_fix_allow
        Fix.Retry -> R.string.home_fix_retry
    },
)

/** What happened to an app's latest job (B-22). */
enum class JobResult(@StringRes val label: Int, val tone: Tone) {
    Verified(R.string.home_optimized, Tone.Ok),
    Unverified(R.string.home_compiled, Tone.Warn),
    Failed(R.string.home_didnt_finish, Tone.Error),
    Running(R.string.home_running, Tone.Info),
    Queued(R.string.home_queued, Tone.Neutral),
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

private val StoredFixed = mapOf(
    "Didn't finish: that is not a valid app name." to R.string.stored_finish_invalid_app,
    "Didn't reset: that is not a valid app name." to R.string.stored_reset_invalid_app,
    "Didn't reset: the TV took too long." to R.string.stored_reset_too_long,
    "Didn't reset: the TV no longer trusts this app. Choose Fix connection in Settings." to R.string.stored_reset_not_paired,
    "Didn't reset: the TV has not allowed this app. Accept the debugging prompt on the TV." to R.string.stored_reset_not_allowed,
    "Didn't reset: lost the connection to the TV." to R.string.stored_reset_lost,
    "Didn't reset: the TV refused." to R.string.stored_reset_refused,
    RESET_UNSUPPORTED to R.string.stored_reset_unsupported,
    "Reset done. The TV chooses how to run this app again." to R.string.stored_reset_done,
    "Didn't finish: the TV no longer trusts this app. Choose Fix connection in Settings." to R.string.stored_finish_not_paired,
    "Didn't finish: the TV has not allowed this app. Accept the debugging prompt on the TV." to R.string.stored_finish_not_allowed,
    "Didn't finish: lost the connection to the TV." to R.string.stored_finish_lost,
    "Didn't finish: the TV refused to optimize this app." to R.string.stored_finish_refused,
    "Optimized (this TV doesn't report the result)" to R.string.stored_optimized_unverified,
    "Optimized (checked)" to R.string.stored_optimized_checked,
    "The TV did not report this app's compiler filter." to R.string.stored_verify_unknown,
    "Not installed" to R.string.stored_not_installed,
)

private val StoredDynamic = listOf(
    Regex("Didn't reset: the TV refused\\. It said: (.*)", RegexOption.DOT_MATCHES_ALL) to R.string.stored_reset_refused_said,
    Regex("Didn't finish: the TV refused to optimize this app\\. It said: (.*)", RegexOption.DOT_MATCHES_ALL) to R.string.stored_finish_refused_said,
    Regex("Didn't finish: the TV took more than (\\d+) minutes\\.") to R.string.stored_finish_timeout,
    Regex("Didn't finish: unexpected error \\((.*)\\)\\.") to R.string.stored_finish_unexpected,
    Regex("Didn't finish: (.*), not speed\\.", RegexOption.DOT_MATCHES_ALL) to R.string.stored_finish_not_speed,
)

/** Display text for a message the engine stored in Room (always English); anything unknown comes back as stored. */
fun storedText(res: Resources, text: String): String {
    StoredFixed[text]?.let { return res.getString(it) }
    for ((re, id) in StoredDynamic) {
        val arg = re.matchEntire(text)?.groupValues?.get(1) ?: continue
        return if (id == R.string.stored_finish_timeout) res.getString(id, arg.toInt()) else res.getString(id, arg)
    }
    return text
}
