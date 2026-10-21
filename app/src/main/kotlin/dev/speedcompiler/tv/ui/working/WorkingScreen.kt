package dev.speedcompiler.tv.ui.working

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.MonoStyle
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.ui.home.AppLook
import dev.speedcompiler.tv.ui.home.etaText
import dev.speedcompiler.tv.ui.home.rememberGraph
import dev.speedcompiler.tv.ui.home.lookup
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.speedcompiler.tv.watch.DEFAULT_AVG_DURATION_MILLIS
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueState
import kotlinx.coroutines.delay
import dev.speedcompiler.tv.adb.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

/** What the job for this package is doing. Never a percentage: the engine cannot report one. */
sealed interface JobView {
    /** Requested but not in the queue yet. */
    data object Starting : JobView
    data class Waiting(val eta: Eta) : JobView
    data object Running : JobView
    data object Done : JobView
    data class Failed(val reason: String) : JobView
    /** No run showed up in time. */
    data object CouldntStart : JobView

    val finished get() = this is Done || this is Failed || this is CouldntStart
}

const val RUN_WINDOW_MILLIS = 30_000L

/**
 * This screen's run: any active job for [pkg] (optimize now reuses an existing row, so runNow and enqueuedAt can be old),
 * else the newest finished one that ended at most 30 s before the screen opened.
 */
fun thisRun(pkg: String, queue: List<QueueEntry>, history: List<QueueItem>, openedAt: Long): QueueItem? =
    queue.firstOrNull { it.item.packageName == pkg }?.item
        ?: history.filter { it.packageName == pkg && (it.finishedAt ?: 0) >= openedAt - RUN_WINDOW_MILLIS }
            .maxWithOrNull(compareBy({ it.finishedAt }, { it.id }))

fun jobView(pkg: String, queue: List<QueueEntry>, history: List<QueueItem>, openedAt: Long, now: Long): JobView {
    val run = thisRun(pkg, queue, history, openedAt)
        ?: return if (now - openedAt >= RUN_WINDOW_MILLIS) JobView.CouldntStart else JobView.Starting
    return when (run.state) {
        QueueState.RUNNING -> JobView.Running
        QueueState.QUEUED, QueueState.WAITING_GATE -> JobView.Waiting(queue.firstOrNull { it.item.id == run.id }?.eta ?: Eta.None)
        QueueState.DONE -> JobView.Done
        QueueState.SKIPPED -> JobView.Failed("It was cancelled.")
        QueueState.FAILED -> JobView.Failed(run.failureReason ?: "No reason was recorded.")
    }
}

/** Mean of the last five successful runs, or the default when there are none. */
fun averageDuration(history: List<QueueItem>): Long =
    history.filter { it.state == QueueState.DONE && it.startedAt != null && it.finishedAt != null }
        .take(5).map { it.finishedAt!! - it.startedAt!! }.filter { it > 0 }
        .let { if (it.isEmpty()) DEFAULT_AVG_DURATION_MILLIS else it.average().toLong() }

/** Nearest whole minute, at least 1. */
fun usuallyMinutes(avgMillis: Long) = ((avgMillis + 30_000) / 60_000).coerceAtLeast(1)

fun waitingForConnectionText(c: ConnectionState) =
    if (c == ConnectionState.Unpaired) "Waiting for setup. Finish setup, then this starts."
    else "Waiting for the TV's debugging connection. It retries on its own."

fun elapsedText(millis: Long): String {
    val s = millis / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

class WorkingState(
    val label: String, val art: ImageBitmap?, val job: JobView, val elapsedMillis: Long, val avgMillis: Long,
    /** Null until the first reading, so a disconnected open never flashes the raw job state. */
    val connection: ConnectionState? = ConnectionState.Connected,
)

@Composable
fun WorkingScreen(pkg: String, back: () -> Unit) {
    val graph = rememberGraph()
    val flows = remember(graph) { graph.scheduler.queue to graph.scheduler.history }
    val connection = remember(graph) { graph.connectionMonitor.state }
    // Loaded in an effect, not from composition: the label must land after the first read of it, or it never shows.
    val context = LocalContext.current
    val look by produceState(AppLook(pkg), pkg) { value = withContext(Dispatchers.IO) { lookup(context, pkg) } }
    WorkingHost(pkg, look, flows.first, flows.second, connection = connection, back = back)
}

/** Observes the job (it runs in WorkManager, so leaving never cancels it). Elapsed comes from timestamps, not counted ticks. */
@Composable
fun WorkingHost(
    pkg: String, look: AppLook, queue: Flow<List<QueueEntry>>, history: Flow<List<QueueItem>>,
    clock: () -> Long = System::currentTimeMillis,
    connection: Flow<ConnectionState> = flowOf(ConnectionState.Connected), back: () -> Unit,
) {
    val openedAt = rememberSaveable { clock() }
    val q by queue.collectAsState(emptyList())
    val h by history.collectAsState(emptyList())
    val conn by connection.collectAsState<ConnectionState, ConnectionState?>(null)
    var now by remember { mutableLongStateOf(openedAt) }
    val job = jobView(pkg, q, h, openedAt, now)
    val run = thisRun(pkg, q, h, openedAt)
    LaunchedEffect(job.finished) {
        now = clock()
        while (!job.finished) { delay(1000); now = clock() }
    }
    val end = if (job.finished) run?.finishedAt ?: now else now
    val elapsed = (end - (run?.startedAt ?: openedAt)).coerceAtLeast(0)
    WorkingContent(WorkingState(look.label, look.banner ?: look.icon, job, elapsed, averageDuration(h), conn), back)
}

@Composable
fun WorkingContent(s: WorkingState, back: () -> Unit) {
    var playing by remember { mutableStateOf<GameKind?>(null) }
    val done = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    LaunchedEffect(s.job.finished, playing) {
        if (s.job.finished) done.requestFocus() else if (playing == null) runCatching { first.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(Space.x2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
            BouncingArt(s.art, s.job.finished)
            Column(verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                Text(
                    if (s.job.finished) s.label else "Optimizing ${s.label}",
                    style = MaterialTheme.typography.headlineMedium,
                )
                // Without a connection the worker loops running -> retry; say what it is really waiting on.
                if (!s.job.finished && s.connection != ConnectionState.Connected) {
                    Text(s.connection?.let(::waitingForConnectionText) ?: "Starting…", style = MaterialTheme.typography.titleMedium)
                } else when (val j = s.job) {
                    is JobView.Done -> Text("Optimized. You can leave now.", style = MaterialTheme.typography.titleLarge, color = LocalSpeedColors.current.ok)
                    is JobView.Failed -> Text("Didn't finish: ${j.reason}", style = MaterialTheme.typography.titleLarge, color = LocalSpeedColors.current.error)
                    is JobView.Waiting -> Text(etaText(j.eta).ifEmpty { "Waiting for its turn" }, style = MaterialTheme.typography.titleMedium)
                    JobView.CouldntStart -> Text("Couldn't start. Try again from the app.", style = MaterialTheme.typography.titleLarge, color = LocalSpeedColors.current.error)
                    JobView.Starting -> Text("Starting…", style = MaterialTheme.typography.titleMedium)
                    JobView.Running -> Text("Working on it. This keeps going if you leave.", style = MaterialTheme.typography.titleMedium)
                }
                if (s.job != JobView.CouldntStart) Text(
                    elapsedText(s.elapsedMillis), style = MonoStyle,
                    modifier = Modifier.semantics { contentDescription = "Elapsed ${elapsedText(s.elapsedMillis)}" },
                )
                if (!s.job.finished) {
                    Text(
                        "Usually about ${usuallyMinutes(s.avgMillis)} min",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(back, Modifier.focusRequester(done).semantics { contentDescription = if (s.job.finished) "Done, leave this screen" else "Leave, optimizing continues" }) {
                    Text(if (s.job.finished) "Done" else "Leave")
                }
            }
        }
        Text("Play while you wait", style = MaterialTheme.typography.titleMedium)
        val kind = playing
        if (kind == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                GameKind.entries.forEachIndexed { i, k ->
                    Button(
                        { playing = k },
                        (if (i == 0) Modifier.focusRequester(first) else Modifier).semantics { contentDescription = "Play ${k.title}. ${k.hint}" },
                    ) {
                        Column { Text(k.title); Text(k.hint, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else {
            GamePlayer(kind, paused = s.job.finished, onExit = { playing = null })
            Text(
                if (s.job.finished) "Game paused. Press Done when you are ready." else "Arrows to move, Select to act, Back to stop playing",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The app's banner (or a plain tile while it loads off the main thread) hopping up and down. */
@Composable
private fun BouncingArt(art: ImageBitmap?, stopped: Boolean) {
    val t by rememberInfiniteTransition(label = "bounce").animateFloat(
        0f, 1f, infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "hop",
    )
    val hop = if (stopped) 0f else t
    val mod = Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(12.dp))
        .offset { IntOffset(0, -(hop * 16.dp.toPx()).roundToInt()) }
    if (art != null) Image(art, null, mod) else Box(mod.background(LocalSpeedColors.current.accent))
}
