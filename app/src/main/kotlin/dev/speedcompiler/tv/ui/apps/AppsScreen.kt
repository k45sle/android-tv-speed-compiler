package dev.speedcompiler.tv.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.RowRadius
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.StatusPill
import dev.speedcompiler.tv.theme.focusFx
import dev.speedcompiler.tv.theme.rememberFocusMemory
import dev.speedcompiler.tv.theme.remembersFocus
import androidx.compose.ui.focus.FocusDirection
import android.view.ViewTreeObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.WatchedApp
import java.time.ZoneId
import kotlinx.coroutines.launch

private enum class Sort(val label: String) { Recent("Recently updated"), Name("A–Z") }

/** Modal with a column of content; the caller focuses its first button. */
@Composable
fun SimpleDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) = Dialog(onDismiss) {
    Column(
        Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(Space.x3),
        verticalArrangement = Arrangement.spacedBy(Space.x1),
    ) { content() }
}

/**
 * Returns "Optimize now" for a package. With music or video active it asks first (Continue is focused);
 * otherwise it starts the job and opens the Working screen.
 */
@Composable
fun rememberOptimizeNow(deps: AppsDeps, go: (String) -> Unit): (String) -> Unit {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val run: (String) -> Unit = { pkg -> scope.launch { deps.scheduler.optimizeNow(pkg); go(Routes.working(pkg)) } }
    pending?.let { pkg ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        SimpleDialog({ pending = null }) {
            Text("Something is playing. Optimizing now may make it stutter.")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x1)) {
                Button({ pending = null; run(pkg) }, Modifier.focusRequester(focus)) { Text("Continue") }
                Button({ pending = null }) { Text("Cancel") }
            }
        }
    }
    return { pkg -> scope.launch { if (deps.musicActive()) pending = pkg else run(pkg) } }
}

@Composable
fun AppBanner(app: AppInfo, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, app.packageName, app.banner) {
        value = app.banner?.let { load -> withContext(Dispatchers.IO) { load()?.asImageBitmap() } }
    }
    val box = modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    val b = bitmap
    if (b != null) Image(b, null, box, contentScale = ContentScale.Fit)
    else Box(box, Alignment.Center) { Text(app.label.take(1), style = MaterialTheme.typography.titleLarge) }
}

@Composable
fun AppsScreen(go: (String) -> Unit, onBack: () -> Unit, deps: AppsDeps = rememberAppsDeps()) {
    val apps by produceState(emptyList<AppInfo>(), deps) { value = deps.loadApps() }
    val queue by remember(deps) { deps.scheduler.queue }.collectAsState(emptyList())
    val watched by remember(deps) { deps.watched }.collectAsState(emptyList())
    val history by remember(deps) { deps.scheduler.history }.collectAsState(emptyList())
    val now = rememberNow(deps)
    val scope = rememberCoroutineScope()
    val optimizeNow = rememberOptimizeNow(deps, go)
    AppsContent(
        apps, queue.associateBy { it.item.packageName }, watched.associateBy { it.packageName }, optimizedAtMap(watched, history), now, deps.zone,
        onOpen = { go(Routes.appDetail(it)) },
        onOptimize = optimizeNow,
        onQueue = { pkg -> scope.launch { deps.scheduler.enqueue(pkg, QueueSource.MANUAL) } },
        onToggleWatch = { pkg, isWatched -> scope.launch { if (isWatched) deps.scheduler.unwatch(pkg) else deps.scheduler.watch(pkg) } },
    )
}

@Composable
fun AppsContent(
    apps: List<AppInfo>,
    queue: Map<String, QueueEntry>,
    watched: Map<String, WatchedApp>,
    optimizedAt: Map<String, Long>,
    now: Long,
    zone: ZoneId,
    onOpen: (String) -> Unit,
    onOptimize: (String) -> Unit,
    onQueue: (String) -> Unit,
    onToggleWatch: (pkg: String, isWatched: Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.Recent) }
    var menu by remember { mutableStateOf<AppInfo?>(null) }
    var swallowUp by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val box = remember { FocusRequester() }
    // Back/Done hides the keyboard but leaves the field focused; hand focus back to the box so OK reopens it.
    val view = LocalView.current
    var imeVisible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val l = ViewTreeObserver.OnGlobalLayoutListener {
            imeVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(l)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(l) }
    }
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeSeen = true
        else if (imeSeen) { imeSeen = false; if (editing) { editing = false; runCatching { box.requestFocus() } } }
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val mem = rememberFocusMemory("sort")
    LaunchedEffect(editing) { if (editing) { runCatching { field.requestFocus() }; keyboard?.show() } }
    val shown = apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        .let { l -> if (sort == Sort.Recent) l.sortedByDescending { it.updatedAt } else l.sortedBy { it.label.lowercase() } }
    val c = LocalSpeedColors.current

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        Text("Apps", style = MaterialTheme.typography.displaySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalAlignment = Alignment.CenterVertically) {
            // TV pattern: the box takes focus like a button; OK starts editing (and the keyboard), Up/Down leave it.
            Box(Modifier.weight(1f).focusRequester(box).semantics { contentDescription = "Search apps" }.focusFx(1.025f, RowRadius, onClick = { editing = true }).border(1.dp, c.hairline, RoundedCornerShape(RowRadius)).padding(Space.x2)) {
                BasicTextField(
                    query, { query = it },
                    Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { if (!it.isFocused) editing = false }
                        .focusProperties { canFocus = editing }
                        .onPreviewKeyEvent {
                            val dir = when (it.key) { Key.DirectionDown -> FocusDirection.Down; Key.DirectionUp -> FocusDirection.Up; else -> null }
                            if (dir != null && it.type == KeyEventType.KeyDown) {
                                // Leave the field only where focus can go; otherwise fall back to the box so focus is never lost.
                                if (!focusManager.moveFocus(dir)) { editing = false; runCatching { box.requestFocus() } }
                                true
                            } else false
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(c.accent),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("Search apps", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    },
                )
            }
            Button({ sort = if (sort == Sort.Recent) Sort.Name else Sort.Recent }, Modifier.remembersFocus(mem, "sort")) { Text("Sort: ${sort.label}") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            items(shown, key = { it.packageName }) { app ->
                val pill = pillFor(queue[app.packageName], optimizedAt[app.packageName], now, zone)
                ListRow(
                    app.label,
                    Modifier.fillMaxWidth().remembersFocus(mem, app.packageName).semantics { onLongClick("Open menu") { menu = app; true } }
                        .onPreviewKeyEvent {
                            val enter = it.key == Key.Enter || it.key == Key.DirectionCenter || it.key == Key.NumPadEnter
                            // Holding OK: the first repeat is the long press.
                            val long = enter && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 1
                            if (long) { menu = app; swallowUp = true }
                            // The release after a long press must not click the row.
                            val up = enter && it.type == KeyEventType.KeyUp && swallowUp
                            if (up) swallowUp = false
                            long || up
                        },
                    onClick = { onOpen(app.packageName) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalAlignment = Alignment.CenterVertically) {
                        if (pill != null) StatusPill(pill.first, pill.second)
                        AppBanner(app, Modifier.size(width = 80.dp, height = 45.dp))
                    }
                }
            }
        }
    }

    menu?.let { app ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val isWatched = app.packageName in watched
        fun act(f: () -> Unit): () -> Unit = { menu = null; f() }
        SimpleDialog({ menu = null }) {
            Text(app.label, style = MaterialTheme.typography.titleLarge)
            Button(act { onOptimize(app.packageName) }, Modifier.focusRequester(focus)) { Text("Optimize now") }
            Button(act { onQueue(app.packageName) }) { Text("Add to queue") }
            Button(act { onToggleWatch(app.packageName, isWatched) }) { Text(if (isWatched) "Stop watching" else "Watch") }
            Button(act { onOpen(app.packageName) }) { Text("Details") }
        }
    }
}
