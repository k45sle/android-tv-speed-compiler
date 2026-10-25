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
import androidx.compose.ui.platform.LocalContext
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
import dev.speedcompiler.tv.engine.DEBUG_BUILD
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.FrameStat
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
    val ctx = LocalContext.current
    val launch = remember(packageName) { ctx.packageManager.let { it.getLeanbackLaunchIntentForPackage(packageName) ?: it.getLaunchIntentForPackage(packageName) } }
    var resetNote by remember(packageName) { mutableStateOf<String?>(null) }
    val stats by remember(deps) { deps.frameStats }.collectAsState(emptyList())
    val mine = watched.firstOrNull { it.packageName == packageName }
    val res = LocalResources.current
    val resetting = stringResource(R.string.detail_resetting)
    var confirmReset by remember(packageName) { mutableStateOf(false) }
    val reset: () -> Unit = {
        if (resetNote != resetting) {
            resetNote = resetting // set before launch so a second tap cannot start another reset
            scope.launch {
                // Cancel a queued compile first so it cannot redo speed after the reset; on success unwatch, on failure
                // put the compile back so the app is left as it was. NonCancellable so Back does not stop it halfway.
                val active = queue.firstOrNull { it.item.packageName == packageName }?.item?.id
                resetNote = try {
                    withContext(NonCancellable) {
                        active?.let { deps.scheduler.cancel(it) }
                        val r = runCatching { deps.reset(packageName) }
                        if (r.getOrNull()?.ok == true) deps.scheduler.unwatch(packageName) else active?.let { deps.scheduler.retry(it) }
                        r.getOrThrow()
                    }.detail.let { storedText(res, it) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    res.getString(R.string.detail_didnt_reset, e.message ?: res.getString(R.string.detail_unexpected_error))
                }
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
        onOpen = launch?.let { i -> { ctx.startActivity(i) } },
        onBack = onBack,
        runs = history.filter { it.packageName == packageName },
        stats = stats.filter { it.packageName == packageName && it.fingerprint == (deps.fingerprint(packageName) ?: mine?.fingerprint) },
        onSmoother = { on -> scope.launch { deps.setSmoother(packageName, on); optimizeNow(packageName) } },
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
    /** Opens the app on the TV itself; null hides the button. Only offered while the app waits for you to use it. */
    onOpen: (() -> Unit)? = null,
    /** This app's measured smoothness for its current version. */
    stats: List<FrameStat> = emptyList(),
    /** Smoother mode on or off (back to normal mode); either way the app is compiled again. */
    onSmoother: (Boolean) -> Unit = {},
) {
    val mem = rememberFocusMemory("optimize")
    val result = resultFor(entry, watched?.lastResult, optimizedAt)
    val res = LocalResources.current
    val state = when {
        result == JobResult.Running -> stringResource(R.string.detail_optimizing_now)
        result == JobResult.Queued -> etaText(res, entry!!.eta, Instant.ofEpochMilli(now), zone, app.label).ifEmpty { stringResource(R.string.home_queued) }
        result == JobResult.Failed -> stringResource(R.string.home_didnt_finish)
        result == JobResult.Verified -> stringResource(R.string.detail_optimized_checked, agoText(res, optimizedAt ?: now, now))
        result != null -> stringResource(R.string.apps_pill_eta, stringResource(result.label), agoText(res, optimizedAt ?: now, now))
        else -> stringResource(R.string.home_not_optimized)
    }
    val smootherOn = watched?.useSpeed == true
    val advice = adviceFor(
        debugBuild = runs.filter { it.state == QueueState.DONE || it.state == QueueState.FAILED }
            .maxByOrNull { it.finishedAt ?: it.enqueuedAt }?.failureReason == DEBUG_BUILD,
        waitingForProfile = entry?.eta is Eta.WaitingForLaunch,
        compiled = result == JobResult.Verified || result == JobResult.Unverified,
        stats = stats,
        smootherOn = smootherOn,
    )
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
                    when (advice) {
                        Advice.DebugBuild -> R.string.advice_debug_build
                        Advice.Smooth -> R.string.advice_smooth
                        Advice.TrySmoother -> R.string.advice_try_smoother
                        Advice.SlowByItself -> if (smootherOn) R.string.advice_slow_by_itself_smoother else R.string.advice_slow_by_itself
                        Advice.Measuring -> R.string.advice_measuring
                        else -> null // the state line above, or MostlySmooth below
                    }?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    if (advice is Advice.MostlySmooth) Text(stringResource(R.string.advice_mostly_smooth, advice.percent), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        ArcadeButton(stringResource(R.string.setup_optimize_now), onOptimize, Modifier.remembersFocus(mem, "optimize"))
                        if (onOpen != null && entry?.eta is Eta.WaitingForLaunch) ArcadeButton(stringResource(R.string.detail_open, app.label), onOpen, Modifier.remembersFocus(mem, "open"))
                        ArcadeButton(stringResource(if (watched != null) R.string.apps_stop_watching else R.string.apps_watch), onToggleWatch, Modifier.remembersFocus(mem, "watch"))
                        if (advice == Advice.TrySmoother) ArcadeButton(
                            stringResource(R.string.advice_smoother_button),
                            // The button goes away once Smoother mode is on, so focus moves to Optimize now first.
                            { mem.id.value = "optimize"; mem.pending = true; onSmoother(true) },
                            Modifier.remembersFocus(mem, "smoother"),
                        )
                        if (advice == Advice.SlowByItself && smootherOn) ArcadeButton(
                            stringResource(R.string.advice_normal_button),
                            { mem.id.value = "optimize"; mem.pending = true; onSmoother(false) },
                            Modifier.remembersFocus(mem, "normal"),
                        )
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
