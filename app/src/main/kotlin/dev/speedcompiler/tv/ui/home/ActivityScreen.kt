package dev.speedcompiler.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
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
    val queue by graph.scheduler.queue.collectAsState(emptyList())
    val history by graph.scheduler.history.collectAsState(emptyList())
    val events by graph.database.events().recent(200).collectAsState(emptyList())
    ActivityContent(
        queue, history, events, rememberLook(),
        onCancel = { id -> graph.appScope.launch { graph.scheduler.cancel(id) } },
        onRetry = { id -> graph.appScope.launch { graph.scheduler.retry(id) } },
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
) {
    var open by remember { mutableStateOf<Long?>(null) }
    LazyColumn(
        Modifier.padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(Space.x1),
    ) {
        item { Text("Queue", style = MaterialTheme.typography.headlineMedium) }
        if (queue.isEmpty()) item { Text("Nothing waiting", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(queue, key = { "q${it.item.id}" }) { e ->
            val l = look(e.item.packageName)
            val eta = etaText(e.eta, now, zone)
            ListRow(
                l.label,
                subtitle = listOf(stateText(e.state), eta).filter { it.isNotEmpty() }.joinToString(" · "),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        Icon(l)
                        Button({ onCancel(e.item.id) }) { Text("Cancel") }
                    }
                },
            )
        }
        item { Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Space.x2)) }
        if (history.isEmpty()) item { Text("Nothing has run yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(history, key = { "h${it.id}" }) { h ->
            val l = look(h.packageName)
            val reason = h.failureReason?.takeIf { h.state == QueueState.FAILED || h.state == QueueState.SKIPPED }
            val time = clockText(h.finishedAt ?: h.enqueuedAt, zone)
            Column {
                val shape = RoundedCornerShape(RowRadius)
                Row(
                    Modifier.fillMaxWidth().focusFx(1.025f, RowRadius) { open = if (open == h.id) null else h.id }.clip(shape)
                        .border(1.dp, LocalSpeedColors.current.hairline, shape).padding(horizontal = Space.x2, vertical = Space.x1 + 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.x2),
                ) {
                    Text(time, style = MonoStyle.copy(fontSize = 14.sp))
                    Icon(l)
                    Column(Modifier.weight(1f)) {
                        Text(l.label, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(stateText(h.state), reason).joinToString(": "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (h.state == QueueState.FAILED) Button({ onRetry(h.id) }) { Text("Retry") }
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

@Composable
private fun Icon(l: AppLook) {
    l.icon?.let { Image(it, null, Modifier.size(32.dp)) }
}
