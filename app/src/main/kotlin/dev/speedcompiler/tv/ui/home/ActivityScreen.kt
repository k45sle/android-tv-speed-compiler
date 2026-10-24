package dev.speedcompiler.tv.ui.home

import dev.speedcompiler.tv.theme.scrollbar
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.speedcompiler.tv.theme.upper
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.speedcompiler.tv.R
import dev.speedcompiler.tv.theme.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.ui.apps.SimpleDialog
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
    // The ids shown when the dialog opened, so a job added meanwhile is not cancelled unasked.
    var confirmAll by remember { mutableStateOf<List<Long>?>(null) }
    val queueHeader = remember { FocusRequester() }
    val historyHeader = remember { FocusRequester() }
    var refocus by remember { mutableStateOf<FocusRequester?>(null) }
    // The acted-on row leaves its list; park focus on that section's header so D-pad focus is not lost.
    LaunchedEffect(queue, history) {
        refocus?.let { r -> runCatching { r.requestFocus() }.onFailure { runCatching { queueHeader.requestFocus() } }; refocus = null }
    }
    // Start inside the board, not on the Marquee BACK chip.
    LaunchedEffect(Unit) { runCatching { queueHeader.requestFocus() } }
    val cs = MaterialTheme.colorScheme
    val sc = LocalSpeedColors.current
    val res = LocalResources.current
    confirmAll?.let { ids ->
        val keep = remember { FocusRequester() }
        LaunchedEffect(Unit) { keep.requestFocus() }
        SimpleDialog({ confirmAll = null }) {
            Text(pluralStringResource(R.plurals.activity_confirm_cancel, ids.size, ids.size))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x1)) {
                // Focus starts on the safe choice.
                Button({ confirmAll = null }, Modifier.focusRequester(keep)) { Text(stringResource(R.string.activity_keep).upper()) }
                Button({ confirmAll = null; refocus = queueHeader; ids.forEach(onCancel) }) { Text(stringResource(R.string.activity_cancel_all).upper()) }
            }
        }
    }
    RoomBackdrop {
      Column(Modifier.padding(horizontal = 48.dp).padding(top = 12.dp, bottom = 12.dp)) {
        Marquee(stringResource(R.string.nav_activity), compact = true, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        val list = rememberLazyListState()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(cs.background).border(2.dp, sc.hairline).scrollbar(list),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.x1),
        ) {
        item { PixelText(stringResource(R.string.activity_queue), Modifier.focusRequester(queueHeader).focusFx(1f, RowRadius), sc.accent, 36.sp) }
        aggregateLine(res, batchDone(queue, history), queue)?.let { line -> item { Text(line, color = cs.onSurfaceVariant) } }
        if (queue.isNotEmpty()) item { Button({ confirmAll = queue.map { it.item.id } }) { Text(stringResource(R.string.activity_cancel_all).upper()) } }
        if (queue.isEmpty()) item { Text(stringResource(R.string.home_nothing_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(queue, key = { "q${it.item.id}" }) { e ->
            val l = look(e.item.packageName)
            val eta = etaText(res, e.eta, now, zone)
            val rowDesc = stringResource(R.string.activity_row_desc, l.label, stateText(res, e.state))
            val cancelDesc = stringResource(R.string.activity_cancel_app, l.label)
            ListRow(
                l.label,
                Modifier.semantics { contentDescription = rowDesc },
                subtitle = stateText(res, e.state).let { st -> if (eta.isEmpty()) st else res.getString(R.string.common_join, st, eta) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        Icon(l)
                        Button({ refocus = queueHeader; onCancel(e.item.id) }, Modifier.semantics { contentDescription = cancelDesc }) { Text(stringResource(R.string.common_cancel).upper()) }
                    }
                },
            )
        }
        item { PixelText(stringResource(R.string.activity_history), Modifier.padding(top = Space.x2).focusRequester(historyHeader).focusFx(1f, RowRadius), sc.accent, 36.sp) }
        if (history.isEmpty()) item { Text(stringResource(R.string.activity_nothing_run), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(history, key = { "h${it.id}" }) { h ->
            val l = look(h.packageName)
            val reason = h.failureReason?.takeIf { h.state == QueueState.FAILED || h.state == QueueState.SKIPPED }?.let { storedText(res, it) }
            val time = clockText(h.finishedAt ?: h.enqueuedAt, zone)
            val retryDesc = stringResource(R.string.activity_retry_app, l.label)
            Column {
              // Retry sits beside the focusable row, not inside it: inside, RIGHT never reached it and OK only toggled the details.
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).semantics { contentDescription = listOfNotNull(l.label, stateText(res, h.state), time, reason).joinToString(", ") }
                        .focusFx(1.02f, RowRadius) { open = if (open == h.id) null else h.id }
                        .padding(horizontal = Space.x2, vertical = Space.x1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.x2),
                ) {
                    PixelText(time, Modifier.width(140.dp), sc.accent)
                    Icon(l)
                    Column(Modifier.weight(1f)) {
                        Text(l.label, style = MaterialTheme.typography.titleMedium)
                        if (reason != null) Text(stringResource(R.string.activity_failure_reason, stateText(res, h.state), reason), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    PixelText(stateText(res, h.state).upper(), color = toneColor(when (h.state) {
                        QueueState.DONE -> Tone.Ok
                        QueueState.FAILED -> Tone.Error
                        QueueState.SKIPPED -> Tone.Warn
                        else -> Tone.Neutral
                    }))
                }
                if (h.state == QueueState.FAILED) Button({ refocus = historyHeader; onRetry(h.id) }, Modifier.padding(end = Space.x2).semantics { contentDescription = retryDesc }) { Text(stringResource(R.string.common_retry).upper()) }
              }
                if (open == h.id) {
                    val from = h.startedAt ?: h.enqueuedAt
                    val to = h.finishedAt ?: Long.MAX_VALUE
                    val out = events.filter { it.packageName == h.packageName && it.timeMillis in from..to }
                        .joinToString("\n") { storedText(res, it.message) }.ifEmpty { reason ?: stringResource(R.string.activity_no_output) }
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
