package dev.speedcompiler.tv.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.speedcompiler.tv.SpeedApp
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.Scheduler
import dev.speedcompiler.tv.watch.WatchedApp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String = "",
    val updatedAt: Long = 0,
    val banner: Drawable? = null,
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
)

private val clock = DateTimeFormatter.ofPattern("HH:mm")

/** Wait text for a queued job. Never a percentage; a clock time only when the user set hours. */
fun etaText(eta: Eta, now: Long, zone: ZoneId): String? = when (eta) {
    Eta.None -> null
    is Eta.StartsAt -> {
        val at = eta.at.atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        (if (at.toLocalDate() == today) "Starts at " else "Starts tomorrow ") + clock.format(at)
    }
    Eta.WaitingForIdle -> "Waiting for the TV to be idle"
    Eta.WaitingForPlayback -> "Waiting for playback to stop"
    Eta.Paused -> "Auto-optimize is off"
    is Eta.InQueue ->
        if (eta.position < 1) "Next in queue"
        else "#${eta.position} in queue, about ${(eta.approxMillis / 60_000.0).roundToLong().coerceAtLeast(1)} min"
}

/** Row pill: Running, Queued (with ETA; gate-held jobs read Queued too), Optimized, or none. */
fun pillFor(entry: QueueEntry?, watched: WatchedApp?, now: Long, zone: ZoneId): Pair<String, Tone>? = when {
    entry?.state == QueueState.RUNNING -> "Running" to Tone.Info
    entry != null -> ("Queued" + (etaText(entry.eta, now, zone)?.let { " · $it" } ?: "")) to Tone.Warn
    watched?.lastOptimizedAt != null -> "Optimized" to Tone.Ok
    else -> null
}

fun agoText(thenMillis: Long, now: Long): String {
    val min = ((now - thenMillis) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} d ago"
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
            loadApps = { loadUserApps(context, graph.packageSource.installed().map { it.packageName }) },
            scheduler = graph.scheduler,
            watched = dao.all(),
            musicActive = { context.getSystemService(AudioManager::class.java).isMusicActive },
            setAutoOptimize = { pkg, on -> dao.get(pkg)?.let { dao.upsert(it.copy(autoOptimize = on)) } },
        )
    }
}

private suspend fun loadUserApps(context: Context, packages: List<String>): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    packages.filter { it != context.packageName }.mapNotNull { p ->
        runCatching {
            val ai = pm.getApplicationInfo(p, 0)
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (system) return@runCatching null
            val pi = pm.getPackageInfo(p, 0)
            AppInfo(p, ai.loadLabel(pm).toString(), pi.versionName.orEmpty(), pi.lastUpdateTime, ai.loadBanner(pm) ?: ai.loadIcon(pm))
        }.getOrNull()
    }
}
