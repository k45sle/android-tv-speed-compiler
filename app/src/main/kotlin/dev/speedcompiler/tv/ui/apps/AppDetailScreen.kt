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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.theme.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.ListRow
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
    val apps by produceState(emptyList<AppInfo>(), deps) { value = deps.loadApps() }
    val queue by remember(deps) { deps.scheduler.queue }.collectAsState(emptyList())
    val watched by remember(deps) { deps.watched }.collectAsState(emptyList())
    val history by remember(deps) { deps.scheduler.history }.collectAsState(emptyList())
    val now = rememberNow(deps)
    val scope = rememberCoroutineScope()
    val optimizeNow = rememberOptimizeNow(deps, go)
    val mine = watched.firstOrNull { it.packageName == packageName }
    AppDetailContent(
        apps.firstOrNull { it.packageName == packageName } ?: AppInfo(packageName, packageName),
        mine, optimizedAtMap(watched, history)[packageName], queue.firstOrNull { it.item.packageName == packageName }, now, deps.zone,
        onOptimize = { optimizeNow(packageName) },
        onToggleWatch = { scope.launch { if (mine != null) deps.scheduler.unwatch(packageName) else deps.scheduler.watch(packageName) } },
        onAuto = { scope.launch { deps.setAutoOptimize(packageName, it) } },
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
) {
    val mem = rememberFocusMemory("optimize")
    val checkedAt = optimizedAt
    val state = when {
        entry?.state == QueueState.RUNNING -> "Optimizing now"
        entry != null -> etaText(entry.eta, Instant.ofEpochMilli(now), zone).ifEmpty { "Queued" }
        checkedAt != null -> "Optimized for speed · checked ${agoText(checkedAt, now)}"
        else -> "Not optimized yet"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x3), verticalAlignment = Alignment.CenterVertically) {
            AppBanner(app, Modifier.size(width = 160.dp, height = 90.dp))
            Column {
                Text(app.label, style = MaterialTheme.typography.displaySmall)
                Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(state, style = MaterialTheme.typography.titleMedium)
        if (app.updatedAt > 0) {
            Text(
                "Version ${app.versionName.ifEmpty { "unknown" }} · Updated ${updatedText(app.updatedAt, zone)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            Button(onOptimize, Modifier.remembersFocus(mem, "optimize")) { Text("Optimize now") }
            Button(onToggleWatch, Modifier.remembersFocus(mem, "watch")) { Text(if (watched != null) "Stop watching" else "Watch") }
        }
        if (watched != null) {
            ListRow(
                "Auto-optimize this app", onClick = { onAuto(!watched.autoOptimize) },
                modifier = Modifier.semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(watched.autoOptimize)
                },
                subtitle = "Optimize again after each update",
            ) { Switch(watched.autoOptimize, null) }
        }
    }
}
