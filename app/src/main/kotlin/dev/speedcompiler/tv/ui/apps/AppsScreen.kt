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
import dev.speedcompiler.tv.theme.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.CardRadius
import dev.speedcompiler.tv.theme.Cartridge
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.RowRadius
import dev.speedcompiler.tv.theme.Space
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.speedcompiler.tv.theme.Lamp
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.PixelText
import dev.speedcompiler.tv.theme.RoomBackdrop
import dev.speedcompiler.tv.theme.SelectRow
import dev.speedcompiler.tv.theme.Selector
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.theme.toneColor
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
        Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(CardRadius)).padding(Space.x3),
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
                Button({ pending = null; run(pkg) }, Modifier.focusRequester(focus)) { Text("Continue".uppercase()) }
                Button({ pending = null }) { Text("Cancel".uppercase()) }
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
    val box = modifier.clip(RoundedCornerShape(RowRadius)).background(MaterialTheme.colorScheme.surfaceVariant)
    val b = bitmap
    if (b != null) Image(b, null, box, contentScale = ContentScale.Fit)
    else Box(box, Alignment.Center) { Text(app.label.take(1), style = MaterialTheme.typography.titleLarge) }
}

@Composable
fun AppsScreen(go: (String) -> Unit, onBack: () -> Unit, deps: AppsDeps = rememberAppsDeps()) {
    val loaded by produceState<List<AppInfo>?>(null, deps) { value = deps.loadApps() }
    val queue by remember(deps) { deps.scheduler.queue }.collectAsState(emptyList())
    val watched by remember(deps) { deps.watched }.collectAsState(emptyList())
    val history by remember(deps) { deps.scheduler.history }.collectAsState(emptyList())
    val now = rememberNow(deps)
    val scope = rememberCoroutineScope()
    val optimizeNow = rememberOptimizeNow(deps, go)
    AppsContent(
        loaded.orEmpty(), queue.associateBy { it.item.packageName }, watched.associateBy { it.packageName }, optimizedAtMap(watched, history), now, deps.zone,
        onOpen = { go(Routes.appDetail(it)) },
        onOptimize = optimizeNow,
        onQueue = { pkg -> scope.launch { deps.scheduler.enqueue(pkg, QueueSource.MANUAL) } },
        onToggleWatch = { pkg, isWatched -> scope.launch { if (isWatched) deps.scheduler.unwatch(pkg) else deps.scheduler.watch(pkg) } },
        onWatchSelected = { pkgs -> scope.launch { pkgs.filter { p -> watched.none { it.packageName == p } }.forEach { deps.scheduler.watch(it) } } },
        onQueueSelected = { pkgs -> scope.launch { pkgs.forEach { deps.scheduler.enqueue(it, QueueSource.MANUAL) } } },
        onBack = onBack,
        loading = loaded == null,
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
    onWatchSelected: (List<String>) -> Unit = {},
    onQueueSelected: (List<String>) -> Unit = {},
    onBack: () -> Unit = {},
    loading: Boolean = false,
    startSelecting: Boolean = false,
    startSelected: Set<String> = emptySet(),
) {
    var selecting by remember { mutableStateOf(startSelecting) }
    var selected by remember { mutableStateOf(startSelected) }
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
    val shown = remember(apps, query, sort) {
        apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
            .let { l -> if (sort == Sort.Recent) l.sortedByDescending { it.updatedAt } else l.sortedBy { it.label.lowercase() } }
    }
    fun finish(act: (List<String>) -> Unit) { act(selected.toList()); selected = emptySet(); selecting = false }
    val c = LocalSpeedColors.current

    RoomBackdrop {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        Marquee("Your apps", compact = true, onBack = onBack, trailing = { PixelText("${apps.size} apps · ${watched.size} watched", color = c.accent) })
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalAlignment = Alignment.CenterVertically) {
            PixelText("search:", color = MaterialTheme.colorScheme.onSurfaceVariant)
            // TV pattern: the box takes focus like a button; OK starts editing (and the keyboard), Up/Down leave it.
            Box(Modifier.weight(1f).focusRequester(box).semantics { contentDescription = "Search apps" }.focusFx(1.025f, RowRadius, onClick = { editing = true }).border(2.dp, c.hairline, RoundedCornerShape(RowRadius)).padding(Space.x1)) {
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
            val toggleSort = { sort = if (sort == Sort.Recent) Sort.Name else Sort.Recent }
            var sortFocused by remember { mutableStateOf(false) }
            var sortPressed by remember { mutableStateOf(false) }
            Box(
                Modifier.remembersFocus(mem, "sort").onFocusChanged { sortFocused = it.hasFocus; if (!it.isFocused) sortPressed = false }
                    .onKeyEvent {
                        val select = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
                        if (select) when (it.type) {
                            KeyEventType.KeyDown -> sortPressed = true
                            KeyEventType.KeyUp -> if (sortPressed) { sortPressed = false; toggleSort() } // ignore a release whose press landed elsewhere
                        }
                        select
                    }
                    // Not clickable(): that takes focus only outside touch mode, which would refuse the focus restore (as ArcadeButton does).
                    .focusFx(1.05f, RowRadius, remember { MutableInteractionSource() }, false)
                    .pointerInput(Unit) { detectTapGestures { toggleSort() } }
                    .semantics(mergeDescendants = true) { role = Role.Button; onClick { toggleSort(); true } }
                    .background(if (sortFocused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .border(2.dp, c.hairline).padding(horizontal = Space.x2, vertical = Space.x1),
            ) { Selector("Sort: ${sort.label}", sortFocused) }
            Button({ selecting = !selecting; selected = emptySet() }) { Text((if (selecting) "Done" else "Select").uppercase()) }
        }
        if (selecting) Row(horizontalArrangement = Arrangement.spacedBy(Space.x1), verticalAlignment = Alignment.CenterVertically) {
            val none = selected.isEmpty()
            Button({ finish(onWatchSelected) }, enabled = !none) { Text("Watch selected".uppercase()) }
            Button({ finish(onQueueSelected) }, enabled = !none) { Text("Optimize selected".uppercase()) }
            Button({ selected = selected + apps.map { it.packageName }.filter { it in watched } }) { Text("Select all watched".uppercase()) }
            PixelText("${selected.size} selected", color = c.accent)
        } else PixelText("Hold OK on an app for more options", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (shown.isEmpty()) Text(
            when { loading -> "Loading apps…"; apps.isEmpty() -> "No apps found."; else -> "No apps match your search." },
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            items(shown, key = { it.packageName }) { app ->
                val pill = pillFor(queue[app.packageName], watched[app.packageName]?.lastResult, optimizedAt[app.packageName], now, zone)
                SelectRow(
                    app.label,
                    Modifier.remembersFocus(mem, app.packageName).semantics {
                        if (selecting) { role = Role.Checkbox; toggleableState = ToggleableState(app.packageName in selected) }
                        onLongClick("Open menu") { menu = app; true }
                    }
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
                    subtitle = app.versionName.takeIf { it.isNotEmpty() }?.let { "Version $it" },
                    onClick = { if (selecting) selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName else onOpen(app.packageName) },
                    leading = { Cartridge { AppBanner(app, Modifier.size(width = 72.dp, height = 40.dp)) } },
                    trailing = {
                        if (selecting) PixelText(if (app.packageName in selected) "[x]" else "[ ]", color = c.accent)
                        if (pill != null) Row(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalAlignment = Alignment.CenterVertically) {
                            Lamp(toneColor(pill.second), waiting = pill.second == Tone.Warn)
                            PixelText(pill.first, color = toneColor(pill.second))
                        }
                    },
                )
            }
        }
    }
    }

    LaunchedEffect(menu) { if (menu == null) swallowUp = false } // the dialog eats the key-up, so clear the flag on close
    menu?.let { app ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val isWatched = app.packageName in watched
        fun act(f: () -> Unit): () -> Unit = { menu = null; f() }
        SimpleDialog({ menu = null }) {
            Text(app.label, style = MaterialTheme.typography.titleLarge)
            Button(act { onOptimize(app.packageName) }, Modifier.focusRequester(focus)) { Text("Optimize now".uppercase()) }
            Button(act { onQueue(app.packageName) }) { Text("Add to queue".uppercase()) }
            Button(act { onToggleWatch(app.packageName, isWatched) }) { Text((if (isWatched) "Stop watching" else "Watch").uppercase()) }
            Button(act { onOpen(app.packageName) }) { Text("Details".uppercase()) }
        }
    }
}
