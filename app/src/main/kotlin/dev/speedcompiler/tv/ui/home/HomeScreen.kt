package dev.speedcompiler.tv.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.theme.BannerCard
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.MonoStyle
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.theme.remembersFocus
import dev.speedcompiler.tv.theme.rememberFocusMemory
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.WatchedApp
import java.time.Instant
import java.time.ZoneId

/** Plain state so tests can drive the screen without a graph. */
class HomeState(
    val connection: ConnectionState,
    val autoOn: Boolean,
    val watched: List<WatchedApp>,
    val queue: List<QueueEntry>,
    val events: List<Event>,
    val look: (String) -> AppLook,
    val setupDone: Boolean = true,
)

@Composable
fun toneColor(tone: Tone) = LocalSpeedColors.current.let {
    when (tone) {
        Tone.Ok -> it.ok
        Tone.Warn -> it.warn
        Tone.Error -> it.error
        Tone.Info -> it.accent
        Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun dotTone(app: WatchedApp, queue: List<QueueEntry>) = when {
    queue.any { it.item.packageName == app.packageName } -> Tone.Info
    app.lastResult == null -> Tone.Neutral
    app.lastResult.startsWith("Optimized") -> Tone.Ok
    else -> Tone.Warn
}

private fun dotLabel(tone: Tone) = when (tone) {
    Tone.Info -> "In the queue"
    Tone.Ok -> "Optimized"
    Tone.Warn -> "Needs a look"
    Tone.Error -> "Failed"
    Tone.Neutral -> "Not optimized yet"
}

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val graph = rememberGraph()
    val look = rememberLook()
    // Flows are built once: a new Flow per recomposition would restart collection (and the gate probe behind queue).
    val flows = remember(graph) {
        listOf(graph.connectionMonitor.state, graph.settings.values, graph.database.watchedApps().all(), graph.scheduler.queue, graph.database.events().recent(4))
    }
    @Suppress("UNCHECKED_CAST")
    val connection by (flows[0] as Flow<ConnectionState>).collectAsState(ConnectionState.Disconnected)
    @Suppress("UNCHECKED_CAST")
    val settings by (flows[1] as Flow<SettingsValues>).collectAsState(SettingsValues())
    @Suppress("UNCHECKED_CAST")
    val watched by (flows[2] as Flow<List<WatchedApp>>).collectAsState(emptyList())
    @Suppress("UNCHECKED_CAST")
    val queue by (flows[3] as Flow<List<QueueEntry>>).collectAsState(emptyList())
    @Suppress("UNCHECKED_CAST")
    val events by (flows[4] as Flow<List<Event>>).collectAsState(emptyList())
    HomeContent(HomeState(connection, !settings.paused && settings.mode == Mode.Auto, watched, queue, events, look, settings.setupDone), go)
}

@Composable
fun HomeContent(s: HomeState, go: (String) -> Unit, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
    val waiting = s.queue.count { it.state != QueueState.RUNNING }
    val mem = rememberFocusMemory()
    val onOff = if (s.autoOn) "Auto-optimize is on" else "Auto-optimize is off"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(Space.x2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            val (text, action) = if (!s.setupDone) "Not set up yet" to "Set up" else when (s.connection) {
                ConnectionState.Connected -> "$onOff · Connected" to null
                ConnectionState.Connecting -> "$onOff · Connecting" to null
                ConnectionState.Disconnected -> "Disconnected · $waiting waiting" to "Reconnect"
                ConnectionState.Unpaired -> "The TV forgot this app. Approve it again." to "Approve"
            }
            Text(text, style = MaterialTheme.typography.headlineMedium)
            if (action != null) Button(
                { go(if (!s.setupDone) Routes.Setup else if (s.connection == ConnectionState.Unpaired) Routes.QuickRepair else Routes.Reconnect) },
                Modifier.remembersFocus(mem, "status"),
            ) { Text(action) }
        }
        if (s.setupDone && (s.connection == ConnectionState.Connected || s.connection == ConnectionState.Connecting)) {
            val next = s.queue.filter { it.state != QueueState.RUNNING }.firstNotNullOfOrNull { etaText(it.eta, now, zone).ifEmpty { null } }.orEmpty()
            val line = when {
                waiting == 0 -> "Nothing waiting"
                next.isEmpty() -> "$waiting waiting"
                else -> "$waiting waiting · next: $next"
            }
            Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.watched.isNotEmpty()) LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
            contentPadding = PaddingValues(vertical = Space.x1),
        ) {
            items(s.watched, key = { it.packageName }) { app ->
                val look = s.look(app.packageName)
                val tone = dotTone(app, s.queue)
                BannerCard(look.label, Modifier.width(260.dp).remembersFocus(mem, app.packageName), dotLabel(tone), { go(Routes.appDetail(app.packageName)) }) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                        look.banner?.let { Image(it, null, Modifier.size(64.dp, 36.dp)) }
                        Box(Modifier.size(6.dp).background(toneColor(tone), CircleShape))
                    }
                }
            }
        }
        if (s.events.isNotEmpty()) {
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
            s.events.forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                    Text(clockText(e.timeMillis, zone), style = MonoStyle.copy(fontSize = 14.sp))
                    Text(
                        e.packageName?.let { s.look(it).label + ": " }.orEmpty() + e.message,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            Button({ go(Routes.Apps) }, Modifier.remembersFocus(mem, "apps")) { Text("Apps") }
            Button({ go(Routes.Activity) }, Modifier.remembersFocus(mem, "activity")) { Text("Activity") }
            Button({ go(Routes.Settings) }, Modifier.remembersFocus(mem, "settings")) { Text("Settings") }
            Button({ go(Routes.Setup) }, Modifier.remembersFocus(mem, "setup")) { Text("Setup") }
        }
    }
}
