package dev.speedcompiler.tv.ui.apps

import dev.speedcompiler.tv.engine.RESET_UNSUPPORTED
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Bitmap
import dev.speedcompiler.tv.engine.OptimizeResult
import androidx.core.graphics.drawable.toBitmap
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import dev.speedcompiler.tv.watch.QueueItem
import androidx.compose.ui.platform.LocalContext
import dev.speedcompiler.tv.R
import dev.speedcompiler.tv.SpeedApp
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.FrameStat
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.Scheduler
import dev.speedcompiler.tv.watch.WatchedApp
import dev.speedcompiler.tv.watch.dropRefresh
import java.time.Instant
import dev.speedcompiler.tv.ui.home.JobResult
import dev.speedcompiler.tv.ui.home.etaText
import dev.speedcompiler.tv.ui.home.jobResult
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String = "",
    val updatedAt: Long = 0,
    /** Decodes the banner on demand (call off the main thread); keeps no Drawable alive. */
    val banner: (() -> Bitmap?)? = null,
)

/** What the Apps screens need, as plain functions so tests can fake it. */
class AppsDeps(
    val loadApps: suspend () -> List<AppInfo>,
    val scheduler: Scheduler,
    val watched: Flow<List<WatchedApp>>,
    val musicActive: suspend () -> Boolean,
    val setAutoOptimize: suspend (String, Boolean) -> Unit,
    val now: () -> Long = System::currentTimeMillis,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** One package, for the detail screen; falls back to scanning the list. */
    val loadApp: suspend (String) -> AppInfo? = { p -> loadApps().firstOrNull { it.packageName == p } },
    /** Undoes speed compile for one package. */
    val reset: suspend (String) -> OptimizeResult = { OptimizeResult(false, RESET_UNSUPPORTED) },
    /** Successful resets, so a reset app no longer reads as compiled. */
    val resets: Flow<List<Event>> = flowOf(emptyList()),
    /** Measured smoothness for every watched app. */
    val frameStats: Flow<List<FrameStat>> = flowOf(emptyList()),
    /** Smoother mode: compile this package with `speed` from now on. */
    val setSmoother: suspend (String) -> Unit = {},
    /** The installed app's fingerprint now, which changes on an update before any recompile. */
    val fingerprint: (String) -> String? = { null },
)

/** The app's status word: live queue state first, then the stored result; a finished job with no stored result counts as Compiled. */
fun resultFor(entry: QueueEntry?, lastResult: String?, optimizedAt: Long?): JobResult? =
    jobResult(lastResult, entry?.state) ?: if (optimizedAt != null) JobResult.Unverified else null

/** Row pill: the [JobResult] word; Queued also carries the ETA (gate-held jobs read Queued too). */
fun pillFor(res: Resources, entry: QueueEntry?, lastResult: String?, optimizedAt: Long?, now: Long, zone: ZoneId): Pair<String, Tone>? {
    val r = resultFor(entry, lastResult, optimizedAt) ?: return null
    if (r != JobResult.Queued) return res.getString(r.label) to r.tone
    val eta = etaText(res, entry!!.eta, Instant.ofEpochMilli(now), zone)
    val label = res.getString(r.label)
    return (if (eta.isEmpty()) label else res.getString(R.string.apps_pill_eta, label, eta)) to Tone.Warn
}

/** Latest optimize time per package, from the watched row or the newest finished job (covers unwatched apps); a later Reset clears it. */
fun optimizedAtMap(watched: List<WatchedApp>, history: List<QueueItem>, resets: List<Event> = emptyList()): Map<String, Long> {
    val m = mutableMapOf<String, Long>()
    fun put(p: String, t: Long?) { if (t != null && t > (m[p] ?: Long.MIN_VALUE)) m[p] = t }
    watched.forEach { put(it.packageName, it.lastOptimizedAt) }
    history.filter { it.state == QueueState.DONE }.forEach { put(it.packageName, it.finishedAt) }
    resets.forEach { r -> val p = r.packageName ?: return@forEach; if ((m[p] ?: Long.MAX_VALUE) <= r.timeMillis) m.remove(p) }
    return m
}

/** Current time that refreshes every minute so relative texts do not go stale. */
@Composable
fun rememberNow(deps: AppsDeps): Long {
    var now by remember(deps) { mutableLongStateOf(deps.now()) }
    LaunchedEffect(deps) { while (true) { delay(60_000); now = deps.now() } }
    return now
}

fun agoText(res: Resources, thenMillis: Long, now: Long): String {
    val min = ((now - thenMillis) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> res.getString(R.string.apps_ago_just_now)
        min < 60 -> res.getString(R.string.apps_ago_minutes, min)
        min < 24 * 60 -> res.getString(R.string.apps_ago_hours, min / 60)
        else -> res.getString(R.string.apps_ago_days, min / (24 * 60))
    }
}

fun updatedText(millis: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochMilli(millis).atZone(zone))

@Composable
fun rememberAppsDeps(): AppsDeps {
    val context = LocalContext.current.applicationContext
    return remember {
        val graph = (context as SpeedApp).graph
        val dao = graph.database.watchedApps()
        AppsDeps(
            loadApps = { loadUserApps(context, graph.packageSystem.installed().map { it.packageName }) },
            loadApp = { p -> loadUserApps(context, listOf(p)).firstOrNull() },
            scheduler = graph.scheduler,
            watched = dao.all(),
            musicActive = { context.getSystemService(AudioManager::class.java).isMusicActive },
            setAutoOptimize = dao::setAutoOptimize,
            reset = { pkg -> graph.optimizer.reset(pkg).also { if (it.ok) dropRefresh(graph, pkg) } },
            resets = graph.database.events().resets(),
            frameStats = graph.database.frameStats().all(),
            setSmoother = { dao.setUseSpeed(it); dropRefresh(graph, it) },
            fingerprint = graph.packageSystem::fingerprint,
        )
    }
}

/** Non-system apps among [packages] (updated system apps count as user apps), read on IO. Shared with Setup. */
internal suspend fun loadUserApps(context: Context, packages: List<String>): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    packages.filter { it != context.packageName }.mapNotNull { p ->
        runCatching {
            val ai = pm.getApplicationInfo(p, 0)
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (system) return@runCatching null
            val pi = pm.getPackageInfo(p, 0)
            AppInfo(p, ai.loadLabel(pm).toString(), pi.versionName.orEmpty(), pi.lastUpdateTime) { loadBanner(context, p) }
        }.getOrNull()
    }
}

/** Fits the banner (or a square icon) inside 160x90 keeping its aspect ratio. */
private fun loadBanner(context: Context, p: String): Bitmap? = runCatching {
    val pm = context.packageManager
    val ai = pm.getApplicationInfo(p, 0)
    val d = ai.loadBanner(pm) ?: ai.loadIcon(pm)
    val iw = d.intrinsicWidth.coerceAtLeast(1)
    val ih = d.intrinsicHeight.coerceAtLeast(1)
    val scale = minOf(160f / iw, 90f / ih)
    d.toBitmap((iw * scale).toInt().coerceAtLeast(1), (ih * scale).toInt().coerceAtLeast(1))
}.getOrNull()
