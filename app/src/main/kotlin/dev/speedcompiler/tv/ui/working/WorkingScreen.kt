package dev.speedcompiler.tv.ui.working

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import dev.speedcompiler.tv.ui.working.games.GameColors
import kotlin.math.max
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.speedcompiler.tv.R
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import dev.speedcompiler.tv.theme.ArcadeButton
import dev.speedcompiler.tv.theme.PixelText
import dev.speedcompiler.tv.theme.RoomBackdrop
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.Alignment
import dev.speedcompiler.tv.theme.CardRadius
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.PixelBanner
import dev.speedcompiler.tv.theme.MonoStyle
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.ui.home.AppLook
import dev.speedcompiler.tv.ui.home.JobResult
import dev.speedcompiler.tv.ui.home.jobResult
import dev.speedcompiler.tv.ui.home.aggregateLine
import dev.speedcompiler.tv.ui.home.batchDone
import dev.speedcompiler.tv.ui.home.etaText
import dev.speedcompiler.tv.ui.home.storedText
import dev.speedcompiler.tv.ui.home.rememberGraph
import dev.speedcompiler.tv.ui.home.lookup
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import android.content.res.Resources

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

fun jobView(res: Resources, pkg: String, queue: List<QueueEntry>, history: List<QueueItem>, openedAt: Long, now: Long): JobView {
    val run = thisRun(pkg, queue, history, openedAt)
        ?: return if (now - openedAt >= RUN_WINDOW_MILLIS) JobView.CouldntStart else JobView.Starting
    return when (run.state) {
        QueueState.RUNNING -> JobView.Running
        QueueState.QUEUED, QueueState.WAITING_GATE -> JobView.Waiting(queue.firstOrNull { it.item.id == run.id }?.eta ?: Eta.None)
        QueueState.DONE -> JobView.Done
        QueueState.SKIPPED -> JobView.Failed(res.getString(R.string.working_cancelled))
        QueueState.FAILED -> JobView.Failed(run.failureReason ?: res.getString(R.string.working_no_reason))
    }
}

/** Mean of the last five successful runs, or the default when there are none. */
fun averageDuration(history: List<QueueItem>): Long =
    history.filter { it.state == QueueState.DONE && it.startedAt != null && it.finishedAt != null }
        .take(5).map { it.finishedAt!! - it.startedAt!! }.filter { it > 0 }
        .let { if (it.isEmpty()) DEFAULT_AVG_DURATION_MILLIS else it.average().toLong() }

/** Nearest whole minute, at least 1. */
fun usuallyMinutes(avgMillis: Long) = ((avgMillis + 30_000) / 60_000).coerceAtLeast(1)

fun waitingForConnectionText(res: Resources, c: ConnectionState) =
    if (c == ConnectionState.Unpaired) res.getString(R.string.working_waiting_setup)
    else res.getString(R.string.working_waiting_debugging)

fun elapsedText(millis: Long): String {
    val s = millis / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

class WorkingState(
    val label: String, val art: ImageBitmap?, val job: JobView, val elapsedMillis: Long, val avgMillis: Long,
    /** Null until the first reading, so a disconnected open never flashes the raw job state. */
    val connection: ConnectionState? = ConnectionState.Connected,
    /** Best score per [GameKind] name. */
    val bests: Map<String, Int> = emptyMap(),
    /** The TV reported the result as fully compiled; without it a finished run is only "compiled". */
    val verified: Boolean = false,
    /** "k of N, about M min left" while several apps are being optimized. */
    val batch: String? = null,
)

/** Running for real: a running job without a connection is only waiting to retry. */
private val WorkingState.running get() = job == JobView.Running && connection == ConnectionState.Connected

@Composable
fun WorkingScreen(pkg: String, back: () -> Unit) {
    val graph = rememberGraph()
    val flows = remember(graph) { graph.scheduler.queue to graph.scheduler.history }
    val connection = remember(graph) { graph.connectionMonitor.state }
    // Loaded in an effect, not from composition: the label must land after the first read of it, or it never shows.
    val context = LocalContext.current
    val look by produceState(AppLook(pkg), pkg) { value = withContext(Dispatchers.IO) { lookup(context, pkg) } }
    val bests = remember(graph) { graph.settings.values.map { it.bests } }
    // The run's own event, not watched_app: a manual run on an unwatched app has no row there.
    val verified = remember(graph, pkg) { graph.database.events().recent(50).map { l -> jobResult(l.firstOrNull { it.packageName == pkg && it.kind.startsWith("optimize_") }?.message, null) == JobResult.Verified } }
    WorkingHost(
        pkg, look, flows.first, flows.second, connection = connection, bests = bests, verified = verified,
        saveBest = { game, score -> graph.appScope.launch { graph.settings.update { it.copy(bests = it.bests + (game to max(score, it.bests[game] ?: 0))) } } },
        back = back,
    )
}

/** Observes the job (it runs in WorkManager, so leaving never cancels it). Elapsed comes from timestamps, not counted ticks. */
@Composable
fun WorkingHost(
    pkg: String, look: AppLook, queue: Flow<List<QueueEntry>>, history: Flow<List<QueueItem>>,
    clock: () -> Long = System::currentTimeMillis,
    connection: Flow<ConnectionState> = flowOf(ConnectionState.Connected),
    bests: Flow<Map<String, Int>> = flowOf(emptyMap()), verified: Flow<Boolean> = flowOf(false), saveBest: (String, Int) -> Unit = { _, _ -> }, back: () -> Unit,
) {
    val best by bests.collectAsState(emptyMap())
    val checked by verified.collectAsState(false)
    val openedAt = rememberSaveable { clock() }
    val q by queue.collectAsState(emptyList())
    val h by history.collectAsState(emptyList())
    val conn by connection.collectAsState<ConnectionState, ConnectionState?>(null)
    var now by remember { mutableLongStateOf(openedAt) }
    val job = jobView(LocalResources.current, pkg, q, h, openedAt, now)
    val run = thisRun(pkg, q, h, openedAt)
    LaunchedEffect(job.finished) {
        now = clock()
        while (!job.finished) { delay(1000); now = clock() }
    }
    val end = if (job.finished) run?.finishedAt ?: now else now
    val elapsed = (end - (run?.startedAt ?: openedAt)).coerceAtLeast(0)
    WorkingContent(WorkingState(look.label, look.banner ?: look.icon, job, elapsed, averageDuration(h), conn, best, checked, aggregateLine(LocalResources.current, batchDone(q, h), q)), back, saveBest)
}

@Composable
fun WorkingContent(s: WorkingState, back: () -> Unit, saveBest: (String, Int) -> Unit = { _, _ -> }) {
    var playing by remember { mutableStateOf<GameKind?>(null) }
    var last by remember { mutableStateOf(GameKind.entries.first()) } // Back from a game lands on its cabinet
    val done = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    LaunchedEffect(s.job.finished, playing) {
        // The header (and its buttons) is gone while a game fills the screen.
        if (playing == null) runCatching { if (s.job.finished) done.requestFocus() else first.requestFocus() }
    }
    playing?.let { kind ->
        Box(Modifier.fillMaxSize()) {
            GamePlayer(kind, paused = s.job.finished, best = s.bests[kind.name] ?: 0, onBest = { saveBest(kind.name, it) }, onExit = { playing = null })
            StatusLine(s)
        }
        return
    }
    RoomBackdrop {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 48.dp, top = 6.dp, end = 48.dp), // header 104 (grows for a 2-line status) + gap 6 + room 380 + top 6 fits the 500 dp above the floor bar
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WorkingHeader(s, back, done, first)
            ArcadePicker({ last = it; playing = it }, first, last)
        }
    }
}

/** Compact marquee: dashed bulb border, logo, banner in a bezel, title, status, timer, usual-duration blocks and Leave. */
@Composable
private fun WorkingHeader(s: WorkingState, back: () -> Unit, done: FocusRequester, picker: FocusRequester) {
    val cs = MaterialTheme.colorScheme
    val c = LocalSpeedColors.current
    // The dashes chase in 4 steps per 664 ms, on the picker's tick budget; still on low-RAM devices. Read in draw only.
    var chase by remember { mutableIntStateOf(0) }
    val still = rememberLowRam()
    LaunchedEffect(still) { if (!still) while (true) { delay(166); chase = (chase + 1) % 4 } }
    val line = @Composable { text: String, color: Color -> Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    val res = LocalResources.current
    val bannerDesc = if (s.job.finished) stringResource(R.string.common_done) else stringResource(R.string.working_working_desc)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 104.dp).background(cs.background).drawBehind {
            val sw = 4.dp.toPx()
            val period = 12.dp.toPx()
            drawRect(
                c.accent, Offset(sw, sw), Size(size.width - 2 * sw, size.height - 2 * sw),
                style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw, period - sw), chase / 4f * period)),
            )
        }.padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelBanner(s.job.finished || still, Modifier.semantics { contentDescription = bannerDesc }, size = 64.dp)
        val bezel = Modifier.size(96.dp, 54.dp).border(2.dp, c.hairline)
        if (s.art != null) Image(s.art, null, bezel.padding(2.dp)) else Box(bezel.padding(2.dp).background(c.accent))
        Column(Modifier.weight(1f)) {
            val running = s.running
            PixelText(
                if (s.job.finished) s.label else if (running) stringResource(R.string.working_optimizing, s.label) else stringResource(R.string.working_waiting_to_optimize, s.label),
                color = c.accent, size = 28.sp, overflow = TextOverflow.Ellipsis,
            )
            // Without a connection the worker loops running -> retry; say what it is really waiting on.
            if (!s.job.finished && s.connection != ConnectionState.Connected) {
                line(s.connection?.let { waitingForConnectionText(res, it) } ?: stringResource(R.string.working_starting), cs.onSurface)
            } else when (val j = s.job) {
                is JobView.Done -> line(stringResource(if (s.verified) R.string.working_optimized_leave else R.string.working_compiled_leave), c.ok)
                is JobView.Failed -> line(stringResource(R.string.working_failed, storedText(res, j.reason)), c.error)
                is JobView.Waiting -> line(etaText(res, j.eta).ifEmpty { stringResource(R.string.working_waiting_turn) }, cs.onSurface)
                JobView.CouldntStart -> line(stringResource(R.string.working_couldnt_start), c.error)
                JobView.Starting -> line(stringResource(R.string.working_starting), cs.onSurface)
                JobView.Running -> line(stringResource(R.string.working_optimizing_continues), cs.onSurface)
            }
            if (s.batch != null) Text(s.batch, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val elapsedDesc = stringResource(R.string.working_elapsed, elapsedText(s.elapsedMillis))
                if (s.job != JobView.CouldntStart) PixelText(
                    elapsedText(s.elapsedMillis), color = cs.onSurface,
                    modifier = Modifier.semantics { contentDescription = elapsedDesc },
                )
                if (!s.job.finished) {
                    val blocks = 10
                    val filled = (if (running) s.elapsedMillis * blocks / s.avgMillis.coerceAtLeast(1) else 0).coerceIn(0, blocks.toLong()).toInt()
                    val blocksDesc = pluralStringResource(R.plurals.working_blocks_desc, blocks, filled, blocks)
                    Row(Modifier.semantics { contentDescription = blocksDesc }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(blocks) { i ->
                            Box(Modifier.size(18.dp, 12.dp).then(if (i < filled) Modifier.background(c.accent) else Modifier.border(2.dp, c.hairline)))
                        }
                    }
                    Text(
                        stringResource(R.string.working_estimate, usuallyMinutes(s.avgMillis)),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
        }
        val leaveDesc = stringResource(if (s.job.finished) R.string.working_done_leave_desc else R.string.working_leave_desc)
        ArcadeButton(
            stringResource(if (s.job.finished) R.string.common_done else R.string.working_leave), back,
            Modifier.focusRequester(done).focusProperties { down = picker }.semantics { contentDescription = leaveDesc },
        )
    }
}

/** Slim bar over the top edge of the full-screen game: what is compiling, the clock, and how to leave. */
@Composable
private fun StatusLine(s: WorkingState) {
    val ok = s.job is JobView.Done
    val text = when (val j = s.job) {
        is JobView.Done -> stringResource(if (s.verified) R.string.working_status_optimized else R.string.working_status_compiled, s.label)
        is JobView.Failed, JobView.CouldntStart -> stringResource(R.string.working_status_failed, s.label)
        else -> if (s.running) stringResource(R.string.working_optimizing, s.label) else stringResource(R.string.working_waiting_to_optimize, s.label)
    }
    val color = if (ok) GameColors.green3 else GameColors.cream
    Row(
        Modifier.fillMaxWidth().height(30.dp).background(if (ok) GameColors.logo else GameColors.outline).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.app_name), color = color, style = MaterialTheme.typography.labelLarge)
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(elapsedText(s.elapsedMillis), color = color, style = MonoStyle)
        Text(stringResource(if (s.job.finished) R.string.working_back_leave else R.string.working_back_stop), color = GameColors.gold, style = MaterialTheme.typography.labelLarge)
    }
}
