package dev.speedcompiler.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.PixelText
import dev.speedcompiler.tv.theme.RoomBackdrop
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.theme.toneColor
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.RowRadius
import dev.speedcompiler.tv.theme.focusFx
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.speedcompiler.tv.theme.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.theme.MonoStyle
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

@Composable
fun ActivityScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val graph = rememberGraph()
    // Built once: a new Flow per recomposition would restart collection.
    val queueFlow = remember(graph) { graph.scheduler.queue }
    val historyFlow = remember(graph) { graph.scheduler.history }
    val eventsFlow = remember(graph) { graph.database.events().recent(200) }
    val queue by queueFlow.collectAsState(emptyList())
    val history by historyFlow.collectAsState(emptyList())
    val events by eventsFlow.collectAsState(emptyList())
    ActivityContent(
        queue, history, events, rememberLook(),
        onCancel = { id -> graph.appScope.launch { graph.scheduler.cancel(id) } },
        onRetry = { id -> graph.appScope.launch { graph.scheduler.retry(id) } },
        onBack = onBack,
    )
}

@Composable
fun ActivityContent(
    queue: List<QueueEntry>,
    history: List<QueueItem>,
    events: List<Event>,
    look: (String) -> AppLook,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    onBack: () -> Unit = {},
) {
    var open by remember { mutableStateOf<Long?>(null) }
    val queueHeader = remember { FocusRequester() }
    val historyHeader = remember { FocusRequester() }
    var refocus by remember { mutableStateOf<FocusRequester?>(null) }
    // The acted-on row leaves its list; park focus on that section's header so D-pad focus is not lost.
    LaunchedEffect(queue, history) {
        refocus?.let { r -> runCatching { r.requestFocus() }.onFailure { runCatching { queueHeader.requestFocus() } }; refocus = null }
    }
    val cs = MaterialTheme.colorScheme
    val sc = LocalSpeedColors.current
    RoomBackdrop {
      Column(Modifier.padding(horizontal = 48.dp).padding(top = 12.dp, bottom = 12.dp)) {
        Marquee("Activity", compact = true, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(cs.background).border(2.dp, sc.hairline),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.x1),
        ) {
        item { PixelText("Queue", Modifier.focusRequester(queueHeader).focusable(), sc.accent, 36.sp) }
        if (queue.isEmpty()) item { Text("Nothing waiting", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(queue, key = { "q${it.item.id}" }) { e ->
            val l = look(e.item.packageName)
            val eta = etaText(e.eta, now, zone)
            ListRow(
                l.label,
                Modifier.semantics { contentDescription = "${l.label}, ${stateText(e.state)}" },
                subtitle = listOf(stateText(e.state), eta).filter { it.isNotEmpty() }.joinToString(" · "),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        Icon(l)
                        Button({ refocus = queueHeader; onCancel(e.item.id) }, Modifier.semantics { contentDescription = "Cancel ${l.label}" }) { Text("Cancel") }
                    }
                },
            )
        }
        item { PixelText("History", Modifier.padding(top = Space.x2).focusRequester(historyHeader).focusable(), sc.accent, 36.sp) }
        if (history.isEmpty()) item { Text("Nothing has run yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(history, key = { "h${it.id}" }) { h ->
            val l = look(h.packageName)
            val reason = h.failureReason?.takeIf { h.state == QueueState.FAILED || h.state == QueueState.SKIPPED }
            val time = clockText(h.finishedAt ?: h.enqueuedAt, zone)
            Column {
                Row(
                    Modifier.fillMaxWidth().focusFx(1.02f, RowRadius) { open = if (open == h.id) null else h.id }
                        .padding(horizontal = Space.x2, vertical = Space.x1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.x2),
                ) {
                    PixelText(time, Modifier.width(80.dp), sc.accent)
                    Icon(l)
                    Column(Modifier.weight(1f)) {
                        Text(l.label, style = MaterialTheme.typography.titleMedium)
                        if (reason != null) Text("${stateText(h.state)}: $reason", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    PixelText(stateText(h.state).uppercase(), color = toneColor(when (h.state) {
                        QueueState.DONE -> Tone.Ok
                        QueueState.FAILED -> Tone.Error
                        QueueState.SKIPPED -> Tone.Warn
                        else -> Tone.Neutral
                    }))
                    if (h.state == QueueState.FAILED) Button({ refocus = historyHeader; onRetry(h.id) }, Modifier.semantics { contentDescription = "Retry ${l.label}" }) { Text("Retry") }
                }
                if (open == h.id) {
                    val from = h.startedAt ?: h.enqueuedAt
                    val to = h.finishedAt ?: Long.MAX_VALUE
                    val out = events.filter { it.packageName == h.packageName && it.timeMillis in from..to }
                        .joinToString("\n") { it.message }.ifEmpty { reason ?: "No output recorded" }
                    Text(out, style = MonoStyle.copy(fontSize = 14.sp), modifier = Modifier.padding(horizontal = Space.x2, vertical = Space.x1))
                }
            }
        }
        }
      }
    }
}

@Composable
private fun Icon(l: AppLook) {
    l.icon?.let { Image(it, null, Modifier.size(32.dp)) }
}
