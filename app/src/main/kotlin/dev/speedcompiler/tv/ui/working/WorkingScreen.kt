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
import dev.speedcompiler.tv.ui.home.rememberLook
import dev.speedcompiler.tv.watch.DEFAULT_AVG_DURATION_MILLIS
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

/** What the job for this package is doing. Never a percentage: the engine cannot report one. */
sealed interface JobView {
    /** Requested but not in the queue yet. */
    data object Starting : JobView
    data class Waiting(val eta: Eta) : JobView
    data object Running : JobView
    data object Done : JobView
    data class Failed(val reason: String) : JobView

    val finished get() = this is Done || this is Failed
}

/** [openedAt] hides results from earlier runs: a finished item counts only if it ended about when this screen opened or later. */
fun jobView(pkg: String, queue: List<QueueEntry>, history: List<QueueItem>, openedAt: Long): JobView {
    queue.firstOrNull { it.item.packageName == pkg }?.let {
        return if (it.state == QueueState.RUNNING) JobView.Running else JobView.Waiting(it.eta)
    }
    val last = history.firstOrNull { it.packageName == pkg && (it.finishedAt ?: Long.MAX_VALUE) >= openedAt - 5_000 }
        ?: return JobView.Starting
    return when (last.state) {
        QueueState.DONE -> JobView.Done
        QueueState.SKIPPED -> JobView.Failed("It was cancelled.")
        else -> JobView.Failed(last.failureReason ?: "No reason was recorded.")
    }
}

/** Mean of the last five successful runs, or the default when there are none. */
fun averageDuration(history: List<QueueItem>): Long =
    history.filter { it.state == QueueState.DONE && it.startedAt != null && it.finishedAt != null }
        .take(5).map { it.finishedAt!! - it.startedAt!! }.filter { it > 0 }
        .let { if (it.isEmpty()) DEFAULT_AVG_DURATION_MILLIS else it.average().toLong() }

fun elapsedText(millis: Long): String {
    val s = millis / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

class WorkingState(val label: String, val art: ImageBitmap?, val job: JobView, val elapsedMillis: Long, val avgMillis: Long)

@Composable
fun WorkingScreen(pkg: String, back: () -> Unit) {
    val graph = rememberGraph()
    val flows = remember(graph) { graph.scheduler.queue to graph.scheduler.history }
    WorkingHost(pkg, rememberLook()(pkg), flows.first, flows.second, back)
}

/** Observes the job (it runs in WorkManager, so leaving never cancels it) and counts elapsed time while it is unfinished. */
@Composable
fun WorkingHost(pkg: String, look: AppLook, queue: Flow<List<QueueEntry>>, history: Flow<List<QueueItem>>, back: () -> Unit) {
    val openedAt = remember { System.currentTimeMillis() }
    val q by queue.collectAsState(emptyList())
    val h by history.collectAsState(emptyList())
    val job = jobView(pkg, q, h, openedAt)
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(job.finished) {
        while (!job.finished) { delay(1000); elapsed += 1000 }
    }
    WorkingContent(WorkingState(look.label, look.banner ?: look.icon, job, elapsed, averageDuration(h)), back)
}

@Composable
fun WorkingContent(s: WorkingState, back: () -> Unit) {
    var playing by remember { mutableStateOf<GameKind?>(null) }
    val done = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    LaunchedEffect(s.job.finished) { if (s.job.finished) done.requestFocus() }
    LaunchedEffect(playing) { if (playing == null && !s.job.finished) runCatching { first.requestFocus() } }
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
                when (val j = s.job) {
                    is JobView.Done -> Text("Optimized. You can leave now.", style = MaterialTheme.typography.titleLarge, color = LocalSpeedColors.current.ok)
                    is JobView.Failed -> Text("Didn't finish: ${j.reason}", style = MaterialTheme.typography.titleLarge, color = LocalSpeedColors.current.error)
                    is JobView.Waiting -> Text(etaText(j.eta).ifEmpty { "Waiting for its turn" }, style = MaterialTheme.typography.titleMedium)
                    JobView.Starting -> Text("Starting…", style = MaterialTheme.typography.titleMedium)
                    JobView.Running -> Text("Working on it. This keeps going if you leave.", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    elapsedText(s.elapsedMillis), style = MonoStyle,
                    modifier = Modifier.semantics { contentDescription = "Elapsed ${elapsedText(s.elapsedMillis)}" },
                )
                if (!s.job.finished) {
                    Text(
                        "Usually about ${(s.avgMillis / 60_000).coerceAtLeast(1)} min",
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
            if (s.job.finished) Text("Game paused. Press Done when you are ready.", style = MaterialTheme.typography.bodyMedium)
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
