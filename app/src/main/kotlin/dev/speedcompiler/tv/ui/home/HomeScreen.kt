package dev.speedcompiler.tv.ui.home

import dev.speedcompiler.tv.theme.upper
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.theme.Button
import androidx.tv.material3.Text
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.R
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.theme.ArcadeButton
import dev.speedcompiler.tv.theme.BannerCard
import dev.speedcompiler.tv.theme.Cabinet
import dev.speedcompiler.tv.theme.Cartridge
import dev.speedcompiler.tv.theme.FocusMemory
import dev.speedcompiler.tv.theme.Lamp
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.PixelText
import dev.speedcompiler.tv.theme.RoomBackdrop
import dev.speedcompiler.tv.theme.ScoreRow
import dev.speedcompiler.tv.theme.ScoreTable
import dev.speedcompiler.tv.theme.toneColor
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.theme.remembersFocus
import dev.speedcompiler.tv.theme.rememberFocusMemory
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
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
    /** False until settings have loaded; the status row waits so it never shows a wrong first state. */
    val loaded: Boolean = true,
    val history: List<QueueItem> = emptyList(),
)

private fun appResult(app: WatchedApp, queue: List<QueueEntry>) =
    jobResult(app.lastResult, queue.firstOrNull { it.item.packageName == app.packageName }?.state)

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val graph = rememberGraph()
    val look = rememberLook()
    val connection by remember(graph) { graph.connectionMonitor.state }.collectAsState(ConnectionState.Disconnected)
    val loadedSettings by remember(graph) { graph.settings.values }.collectAsState(null)
    val watched by remember(graph) { graph.database.watchedApps().all() }.collectAsState(emptyList())
    val queue by remember(graph) { graph.scheduler.queue }.collectAsState(emptyList())
    val events by remember(graph) { graph.database.events().recent(4) }.collectAsState(emptyList())
    val history by remember(graph) { graph.scheduler.history }.collectAsState(emptyList())
    val settings = loadedSettings ?: SettingsValues()
    HomeContent(HomeState(connection, !settings.paused && settings.mode == Mode.Auto, watched, queue, events, look, settings.setupDone, loadedSettings != null, history), go)
}

@Composable
fun HomeContent(s: HomeState, go: (String) -> Unit, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
    val waiting = s.queue.count { it.state != QueueState.RUNNING }
    val mem = rememberFocusMemory(if (!s.setupDone || s.connection == ConnectionState.Disconnected || s.connection == ConnectionState.Unpaired) "status" else "apps")
    val res = LocalResources.current
    val onOff = stringResource(if (s.autoOn) R.string.home_auto_on else R.string.home_auto_off)
    val online = s.setupDone && (s.connection == ConnectionState.Connected || s.connection == ConnectionState.Connecting)
    val next = s.queue.filter { it.state != QueueState.RUNNING }.firstNotNullOfOrNull { etaText(res, it.eta, now, zone).ifEmpty { null } }.orEmpty()
    val batch = aggregateLine(res, batchDone(s.queue, s.history), s.queue)
    val queueLine = when {
        waiting == 0 -> batch ?: stringResource(R.string.home_nothing_waiting)
        next.isEmpty() -> pluralStringResource(R.plurals.home_waiting_count, waiting, waiting)
        else -> pluralStringResource(R.plurals.home_waiting_next, waiting, waiting, next)
    }.let { if (batch == null || waiting == 0) it else stringResource(R.string.common_join, it, batch) }
    // The sign is short, so the long "forgot this app" sentence moves to the subtitle.
    val (title, subtitle) = when {
        !s.loaded -> "" to null
        !s.setupDone -> stringResource(R.string.home_not_set_up) to null
        s.connection == ConnectionState.Disconnected -> stringResource(R.string.home_disconnected) to if (waiting == 0) stringResource(R.string.home_nothing_waiting) else pluralStringResource(R.plurals.home_waiting_count, waiting, waiting)
        s.connection == ConnectionState.Unpaired -> stringResource(R.string.home_not_paired) to stringResource(R.string.home_forgot_subtitle)
        else -> onOff to queueLine
    }
    val action = if (!s.loaded) null else if (!s.setupDone) stringResource(R.string.home_set_up) else when (s.connection) {
        ConnectionState.Disconnected, ConnectionState.Unpaired -> stringResource(R.string.common_fix_connection)
        else -> null
    }
    RoomBackdrop {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Marquee(title, subtitle = subtitle, trailing = {
                if (action != null) Button(
                    { go(if (!s.setupDone) Routes.Setup else fixRoute(if (s.connection == ConnectionState.Unpaired) Fix.QuickRepair else Fix.Retry)) },
                    Modifier.remembersFocus(mem, "status"),
                ) { Text(action.upper()) }
                else if (s.loaded && online) {
                    val connected = s.connection == ConnectionState.Connected
                    StatusChip(stringResource(if (connected) R.string.home_connected else R.string.home_connecting), if (connected) Tone.Ok else Tone.Info)
                }
            })
            if (s.watched.isNotEmpty()) LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Space.x2),
                contentPadding = PaddingValues(start = Space.x1, top = Space.x1, end = Space.x1, bottom = Space.x1),
            ) {
                items(s.watched, key = { it.packageName }) { app ->
                    val look = s.look(app.packageName)
                    val result = appResult(app, s.queue)
                    BannerCard(look.label, Modifier.width(200.dp).remembersFocus(mem, app.packageName), result?.let { stringResource(it.label) } ?: stringResource(R.string.home_not_optimized), { go(Routes.appDetail(app.packageName)) }, result?.tone ?: Tone.Neutral) {
                        look.banner?.let { Cartridge { Image(it, null, Modifier.size(64.dp, 36.dp)) } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                if (s.events.isNotEmpty()) ScoreTable(
                    stringResource(R.string.home_recent_activity),
                    s.events.take(2).map { e ->
                        val (word, tone) = when {
                            "fail" in e.kind -> stringResource(R.string.home_failed) to Tone.Error
                            "verified" in e.kind -> stringResource(R.string.home_optimized) to Tone.Ok
                            else -> stringResource(R.string.home_note) to Tone.Neutral
                        }
                        ScoreRow(clockText(e.timeMillis, zone), e.packageName?.let { s.look(it).label } ?: storedText(LocalResources.current, e.message), word, tone)
                    },
                    Modifier.weight(1f),
                ) else Spacer(Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        HomeButton(stringResource(R.string.nav_apps), Cabinet.BLUE, mem, "apps") { go(Routes.Apps) }
                        HomeButton(stringResource(R.string.nav_activity), Cabinet.ORANGE, mem, "activity") { go(Routes.Activity) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        HomeButton(stringResource(R.string.nav_settings), Cabinet.PLUM, mem, "settings") { go(Routes.Settings) }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HomeButton(label: String, fill: Int, mem: FocusMemory, id: String, onClick: () -> Unit) =
    ArcadeButton(label, onClick, Modifier.weight(1f).remembersFocus(mem, id), fill = Color(fill), contentColor = Color(Cabinet.CREAM))

/** Outlined chip with a lamp and a status word, for the sign's right side. */
@Composable
private fun StatusChip(text: String, tone: Tone) {
    Row(
        Modifier.border(2.dp, toneColor(tone)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(toneColor(tone))
        PixelText(text, color = toneColor(tone))
    }
}
