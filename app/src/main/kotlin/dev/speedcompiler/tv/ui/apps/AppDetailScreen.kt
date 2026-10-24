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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import dev.speedcompiler.tv.theme.Button
import dev.speedcompiler.tv.theme.upper
import dev.speedcompiler.tv.ui.home.JobResult
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.speedcompiler.tv.R
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
import dev.speedcompiler.tv.ui.home.storedText
import kotlinx.coroutines.launch

@Composable
fun AppDetailScreen(packageName: String, go: (String) -> Unit, onBack: () -> Unit, deps: AppsDeps = rememberAppsDeps()) {
    val loaded by produceState<AppInfo?>(null, deps, packageName) { value = deps.loadApp(packageName) }
    val queue by remember(deps) { deps.scheduler.queue }.collectAsState(emptyList())
    val watched by remember(deps) { deps.watched }.collectAsState(emptyList())
    val history by remember(deps) { deps.scheduler.history }.collectAsState(emptyList())
    val resets by remember(deps) { deps.resets }.collectAsState(emptyList())
    val now = rememberNow(deps)
    val scope = rememberCoroutineScope()
    val optimizeNow = rememberOptimizeNow(deps, go)
    var resetNote by remember(packageName) { mutableStateOf<String?>(null) }
    val mine = watched.firstOrNull { it.packageName == packageName }
    val res = LocalResources.current
    val resetting = stringResource(R.string.detail_resetting)
    var confirmReset by remember(packageName) { mutableStateOf(false) }
    val reset: () -> Unit = {
        if (resetNote != resetting) scope.launch {
            resetNote = resetting
            // NonCancellable so Back does not stop it halfway; unwatch only once the reset worked, so a failure leaves the app watched.
            resetNote = try {
                withContext(NonCancellable) { deps.reset(packageName).also { if (it.ok) deps.scheduler.unwatch(packageName) } }.detail.let { storedText(res, it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                res.getString(R.string.detail_didnt_reset, e.message ?: res.getString(R.string.detail_unexpected_error))
            }
        }
    }
    AppDetailContent(
        loaded ?: AppInfo(packageName, packageName),
        mine, optimizedAtMap(watched, history, resets)[packageName], queue.firstOrNull { it.item.packageName == packageName }, now, deps.zone,
        onOptimize = { optimizeNow(packageName) },
        onToggleWatch = { scope.launch { if (mine != null) deps.scheduler.unwatch(packageName) else deps.scheduler.watch(packageName) } },
        onAuto = { scope.launch { deps.setAutoOptimize(packageName, it) } },
        onReset = { confirmReset = true },
        resetNote = resetNote,
        onBack = onBack,
        runs = history.filter { it.packageName == packageName },
    )
    if (confirmReset) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancel.requestFocus() }
        SimpleDialog({ confirmReset = false }) {
            Text(stringResource(R.string.detail_reset_body))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x1)) {
                Button({ confirmReset = false; reset() }) { Text(stringResource(R.string.common_ok).upper()) }
                Button({ confirmReset = false }, Modifier.focusRequester(cancel)) { Text(stringResource(R.string.common_cancel).upper()) }
            }
        }
    }
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
    onReset: () -> Unit = {},
    resetNote: String? = null,
) {
    val mem = rememberFocusMemory("optimize")
    val result = resultFor(entry, watched?.lastResult, optimizedAt)
    val res = LocalResources.current
    val state = when {
        result == JobResult.Running -> stringResource(R.string.detail_optimizing_now)
        result == JobResult.Queued -> etaText(res, entry!!.eta, Instant.ofEpochMilli(now), zone).ifEmpty { stringResource(R.string.home_queued) }
        result == JobResult.Failed -> stringResource(R.string.home_didnt_finish)
        result == JobResult.Verified -> stringResource(R.string.detail_optimized_checked, agoText(res, optimizedAt ?: now, now))
        result != null -> stringResource(R.string.apps_pill_eta, stringResource(result.label), agoText(res, optimizedAt ?: now, now))
        else -> stringResource(R.string.home_not_optimized)
    }
    val rows = runs.filter { it.state == QueueState.DONE || it.state == QueueState.FAILED }
        .sortedByDescending { it.finishedAt ?: it.enqueuedAt }.take(3)
        .map {
            val ok = it.state == QueueState.DONE
            ScoreRow(
                agoText(res, it.finishedAt ?: it.enqueuedAt, now),
                res.getString(if (it.source == QueueSource.AUTO) R.string.detail_run_automatic else R.string.detail_run_manual),
                res.getString(if (ok) R.string.home_optimized else R.string.home_failed),
                if (ok) Tone.Ok else Tone.Error,
            )
        }
    val tone = if (result == JobResult.Queued) Tone.Warn else result?.tone ?: Tone.Neutral // matches the Apps list pill
    val word = result?.let { stringResource(it.label) } ?: stringResource(R.string.detail_new)
    val cardDesc = stringResource(R.string.activity_row_desc, app.label, word)
    RoomBackdrop {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            Marquee(app.label, compact = true, onBack = onBack)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.x4)) {
                // Display only: the Optimize button beside it is the one control, so the card takes no focus.
                CabinetCard(app.label, Modifier.width(280.dp).focusProperties { canFocus = false }.clearAndSetSemantics { contentDescription = cardDesc }, word, tone, screenHeight = 140.dp) {
                    // The Apps list cartridge blown up whole: its frame is fixed dp, so a bigger banner alone would widen its shape.
                    Cartridge(Modifier.scale(1.6f)) { AppBanner(app, Modifier.size(width = 72.dp, height = 40.dp)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                    Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state, style = MaterialTheme.typography.titleMedium)
                    if (app.updatedAt > 0) {
                        Text(
                            stringResource(R.string.detail_version_updated, app.versionName.ifEmpty { stringResource(R.string.detail_version_unknown) }, updatedText(app.updatedAt, zone)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        ArcadeButton(stringResource(R.string.setup_optimize_now), onOptimize, Modifier.remembersFocus(mem, "optimize"))
                        ArcadeButton(stringResource(if (watched != null) R.string.apps_stop_watching else R.string.apps_watch), onToggleWatch, Modifier.remembersFocus(mem, "watch"))
                        ArcadeButton(stringResource(R.string.detail_reset), onReset, Modifier.remembersFocus(mem, "reset"))
                    }
                    if (resetNote != null) Text(resetNote, style = MaterialTheme.typography.bodyMedium)
                    if (watched != null) {
                        OperatorRow(
                            stringResource(R.string.detail_auto_optimize), onClick = { onAuto(!watched.autoOptimize) },
                            modifier = Modifier.semantics {
                                role = Role.Switch
                                toggleableState = ToggleableState(watched.autoOptimize)
                            },
                            sublabel = stringResource(R.string.detail_auto_sublabel),
                        ) { DipSwitch(watched.autoOptimize) }
                    }
                    if (rows.isNotEmpty()) ScoreTable(stringResource(R.string.detail_runs), rows, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
