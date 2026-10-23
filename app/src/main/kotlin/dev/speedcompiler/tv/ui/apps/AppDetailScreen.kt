package dev.speedcompiler.tv.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import dev.speedcompiler.tv.ui.home.JobResult
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import dev.speedcompiler.tv.theme.ArcadeButton
import dev.speedcompiler.tv.theme.CabinetCard
import dev.speedcompiler.tv.theme.Cartridge
import dev.speedcompiler.tv.theme.DipSwitch
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.OperatorRow
import dev.speedcompiler.tv.theme.RoomBackdrop
import dev.speedcompiler.tv.theme.ScoreRow
import dev.speedcompiler.tv.theme.ScoreTable
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.rememberFocusMemory
import dev.speedcompiler.tv.theme.remembersFocus
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.WatchedApp
import java.time.Instant
import java.time.ZoneId
import dev.speedcompiler.tv.ui.home.etaText
import kotlinx.coroutines.launch

@Composable
fun AppDetailScreen(packageName: String, go: (String) -> Unit, onBack: () -> Unit, deps: AppsDeps = rememberAppsDeps()) {
    val loaded by produceState<AppInfo?>(null, deps, packageName) { value = deps.loadApp(packageName) }
    val queue by remember(deps) { deps.scheduler.queue }.collectAsState(emptyList())
    val watched by remember(deps) { deps.watched }.collectAsState(emptyList())
    val history by remember(deps) { deps.scheduler.history }.collectAsState(emptyList())
    val now = rememberNow(deps)
    val scope = rememberCoroutineScope()
    val optimizeNow = rememberOptimizeNow(deps, go)
    val mine = watched.firstOrNull { it.packageName == packageName }
    AppDetailContent(
        loaded ?: AppInfo(packageName, packageName),
        mine, optimizedAtMap(watched, history)[packageName], queue.firstOrNull { it.item.packageName == packageName }, now, deps.zone,
        onOptimize = { optimizeNow(packageName) },
        onToggleWatch = { scope.launch { if (mine != null) deps.scheduler.unwatch(packageName) else deps.scheduler.watch(packageName) } },
        onAuto = { scope.launch { deps.setAutoOptimize(packageName, it) } },
        onBack = onBack,
        runs = history.filter { it.packageName == packageName },
    )
}

@Composable
fun AppDetailContent(
    app: AppInfo,
    watched: WatchedApp?,
    optimizedAt: Long?,
    entry: QueueEntry?,
    now: Long,
    zone: ZoneId,
    onOptimize: () -> Unit,
    onToggleWatch: () -> Unit,
    onAuto: (Boolean) -> Unit,
    onBack: () -> Unit = {},
    runs: List<QueueItem> = emptyList(),
) {
    val mem = rememberFocusMemory("optimize")
    val result = resultFor(entry, watched?.lastResult, optimizedAt)
    val state = when {
        result == JobResult.Running -> "Optimizing now"
        result == JobResult.Queued -> etaText(entry!!.eta, Instant.ofEpochMilli(now), zone).ifEmpty { "Queued" }
        result == JobResult.Failed -> "Didn't finish"
        result == JobResult.Verified -> "Optimized for speed · checked ${agoText(optimizedAt ?: now, now)}"
        result != null -> "${result.label} · ${agoText(optimizedAt ?: now, now)}"
        else -> "Not optimized yet"
    }
    val rows = runs.filter { it.state == QueueState.DONE || it.state == QueueState.FAILED }
        .sortedByDescending { it.finishedAt ?: it.enqueuedAt }.take(3)
        .map {
            val ok = it.state == QueueState.DONE
            ScoreRow(agoText(it.finishedAt ?: it.enqueuedAt, now), if (it.source == QueueSource.AUTO) "Automatic" else "Manual", if (ok) "Done" else "Failed", if (ok) Tone.Ok else Tone.Error)
        }
    val tone = result?.tone ?: Tone.Neutral
    val word = result?.label ?: "New"
    RoomBackdrop {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            Marquee(app.label, compact = true, onBack = onBack)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.x4)) {
                // Display only: the Optimize button beside it is the one control, so the card takes no focus.
                CabinetCard(app.label, Modifier.width(280.dp).focusProperties { canFocus = false }.clearAndSetSemantics { contentDescription = "${app.label}, $word" }, word, tone, screenHeight = 140.dp) {
                    // The Apps list cartridge blown up whole: its frame is fixed dp, so a bigger banner alone would widen its shape.
                    Cartridge(Modifier.scale(1.6f)) { AppBanner(app, Modifier.size(width = 72.dp, height = 40.dp)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                    Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state, style = MaterialTheme.typography.titleMedium)
                    if (app.updatedAt > 0) {
                        Text(
                            "Version ${app.versionName.ifEmpty { "unknown" }} · Updated ${updatedText(app.updatedAt, zone)}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        ArcadeButton("Optimize now", onOptimize, Modifier.remembersFocus(mem, "optimize"))
                        ArcadeButton(if (watched != null) "Stop watching" else "Watch", onToggleWatch, Modifier.remembersFocus(mem, "watch"))
                    }
                    if (watched != null) {
                        OperatorRow(
                            "Auto-optimize this app", onClick = { onAuto(!watched.autoOptimize) },
                            modifier = Modifier.semantics {
                                role = Role.Switch
                                toggleableState = ToggleableState(watched.autoOptimize)
                            },
                            sublabel = "Optimize again after each update",
                        ) { DipSwitch(watched.autoOptimize) }
                    }
                    if (rows.isNotEmpty()) ScoreTable("Runs", rows, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
