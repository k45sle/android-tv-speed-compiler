package dev.speedcompiler.tv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.theme.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.MonoStyle
import dev.speedcompiler.tv.theme.QrCard
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.StatusPill
import dev.speedcompiler.tv.theme.Cabinet
import dev.speedcompiler.tv.theme.Lamp
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.PixelText
import dev.speedcompiler.tv.theme.RoomBackdrop
import dev.speedcompiler.tv.theme.StageMeter
import dev.speedcompiler.tv.theme.ArcadeButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.web.qrImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

enum class SetupMode { Full, Repair }

/** Public help page the "Show me how" QR points at. */
const val HelpUrl = "https://github.com/k45sle/android-tv-speed-compiler#setup-help"

private const val PollMs = 500L
private const val Total = 5
private const val PairGraceMs = 2000L
private const val NoProgressMs = 45_000L
private const val ReconnectMs = 30_000L
private const val DebounceMs = 1000L

private val LocalPageBack = compositionLocalOf<() -> Unit> { {} }

/**
 * The 7-screen first-run flow. [SetupMode.Repair] is the quick re-pair entry: it starts at screen 4.
 * Steps already done are skipped going forward; Back goes to the nearest earlier step still to do.
 */
@Composable
fun SetupScreen(onBack: () -> Unit, mode: SetupMode = SetupMode.Full, deps: SetupDeps = rememberSetupDeps()) {
    val env = deps.env
    val scope = rememberCoroutineScope()
    val first = if (mode == SetupMode.Repair) 4 else 1
    var step by rememberSaveable { mutableIntStateOf(first) }
    var help by remember { mutableStateOf(false) }
    val failures = remember { mutableStateMapOf<Int, Int>() }
    val conn by deps.connection.collectAsState(ConnectionState.Unpaired)
    var apps by remember { mutableStateOf(emptyList<SetupApp>()) }
    val picked = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }

    // Connecting/Disconnected also happen on a fresh install (the TLS handshake fails when unpaired), so they count only once this app holds a pairing.
    fun done(s: Int) = when (s) {
        2 -> env.developerOptionsOn()
        3 -> env.wirelessDebuggingOn()
        4 -> conn == ConnectionState.Connected || (conn != ConnectionState.Unpaired && deps.holdsPairing())
        5 -> conn == ConnectionState.Connected
        else -> false
    }
    fun advance() {
        var n = step + 1
        while (n <= Total && done(n)) n++
        if (n > Total && mode == SetupMode.Repair) onBack() else step = n
    }
    fun back() {
        var p = step - 1
        while (p > first && done(p)) p--
        step = p
    }
    fun fail() { failures[step] = (failures[step] ?: 0) + 1 }
    val helpOffer = if ((failures[step] ?: 0) >= 2) ({ help = true }) else null

    LaunchedEffect(step) { if (step == 6 || (step == 7 && apps.isEmpty())) apps = env.recentApps() } // step 7 after a restore needs the labels too
    BackHandler(enabled = help || step > first) { if (help) help = false else back() }

    val pageBack = { if (help) help = false else if (step > first) back() else onBack() }
    CompositionLocalProvider(LocalPageBack provides pageBack) {
    if (help) HelpPage(step) { help = false } else when (step) {
        1 -> Page(null, "Compile your apps ahead of time.") {
            Body("Android can fully compile apps ahead of time. This app does it for the apps you pick and redoes it after each update. Results vary, and each app uses a little more storage.")
            PrimaryButton("Start", onClick = ::advance)
        }
        2 -> Page(2, "Turn on Developer options.") {
            Body("Open Settings > System > About > Build, and press it 7 times.")
            PrimaryButton("Open Settings", onClick = debounced(env::openSettings))
            helpOffer?.let { ShowMeHow(it) }
            Waiting()
            FailWhenStuck(::fail)
            AutoAdvance({ env.developerOptionsOn() }, ::advance)
        }
        3 -> Page(3, "Turn on Wireless debugging.") {
            Body("In Developer options, switch on Wireless debugging.")
            PrimaryButton("Open Developer options", onClick = debounced(env::openDeveloperOptions))
            helpOffer?.let { ShowMeHow(it) }
            Waiting()
            FailWhenStuck(::fail)
            AutoAdvance({ env.wirelessDebuggingOn() }, ::advance)
        }
        4 -> PairStep(deps, helpOffer, onPaired = ::advance, onFail = ::fail)
        5 -> {
            var sawConnecting by remember { mutableStateOf(false) }
            var denied by remember { mutableStateOf(false) }
            var stalled by remember { mutableStateOf(false) }
            var attempt by remember { mutableIntStateOf(0) }
            val reason by deps.reason.collectAsState(null)
            LaunchedEffect(conn) {
                if (conn == ConnectionState.Connecting) { sawConnecting = true; stalled = false }
                else if ((conn == ConnectionState.Disconnected || conn == ConnectionState.Unpaired) && sawConnecting) { sawConnecting = false; denied = true; fail() }
            }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(attempt) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { delay(NoProgressMs); stalled = true; fail() } }
            val problem = if (denied) reason ?: "The TV did not allow this app." else if (stalled) "No answer from the TV yet." else null
            Page(5, "Allow this app.") {
                Body("Your TV will ask to allow debugging. Choose Always allow from this computer.")
                if (problem != null) {
                    Body(problem)
                    PrimaryButton("Retry") { env.reconnect(); denied = false; stalled = false; sawConnecting = false; attempt++ }
                } else StatusPill(if (conn == ConnectionState.Connecting) "Connecting" else "Waiting for you", Tone.Info)
                helpOffer?.let { ShowMeHow(it, grab = problem == null) }
                AutoAdvance({ conn == ConnectionState.Connected }, ::advance)
            }
        }
        6 -> AppPicker(apps, picked, onContinue = { step = 7 })
        else -> {
            val chosen = apps.filter { it.packageName in picked }
            val name = chosen.firstOrNull()?.label ?: "Your apps"
            val others = chosen.size - 1
            val subject = when {
                others <= 0 -> name
                else -> "$name and $others other${if (others == 1) "" else "s"}"
            }
            val finish = { now: Boolean -> scope.launch { deps.onFinish(picked.toList(), now); onBack() }; Unit }
            Page(null, "Setup is done.") {
                Body("$subject will be optimized when nothing is playing. It takes a minute or two each.")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                    PrimaryButton("Optimize now") { finish(true) }
                    Button(onClick = { finish(false) }) { Text("Done".uppercase()) }
                }
            }
        }
    }
    }
}

/** Quick re-pair (tier 4): straight to the pairing screen. */
@Composable
fun QuickRepairScreen(onBack: () -> Unit, deps: SetupDeps = rememberSetupDeps()) =
    SetupScreen(onBack, SetupMode.Repair, deps)

/**
 * Reconnect (tier 3): one button to Developer options. Reconnects as soon as Wireless debugging is on,
 * then returns to the app, which is already connected. Skips the button if it is on or already connected.
 */
@Composable
fun ReconnectScreen(onBack: () -> Unit, deps: SetupDeps = rememberSetupDeps()) {
    val env = deps.env
    var waiting by remember { mutableStateOf(false) }
    var stuck by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var repair by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val reason by deps.reason.collectAsState(null)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(attempt) {
        while (!env.wirelessDebuggingOn()) delay(PollMs)
        waiting = true
        if (deps.connection.first() != ConnectionState.Connected) env.reconnect()
        deps.connection.first { it == ConnectionState.Connected }
        onBack() // also after "stuck": a late connect still finishes
    }
    // Only time spent in front counts; the user may be in Settings.
    LaunchedEffect(attempt) {
        stuck = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { delay(ReconnectMs); stuck = true }
    }
    if (repair) return QuickRepairScreen(onBack, deps)
    if (help) return CompositionLocalProvider(LocalPageBack provides { help = false }) { HelpPage(3) { help = false } }
    CompositionLocalProvider(LocalPageBack provides onBack) {
        Page(null, "Reconnect") {
            if (stuck) {
                Body(reason ?: if (env.wirelessDebuggingOn()) "The TV did not answer in time." else "Wireless debugging is off.")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                    PrimaryButton("Try again") { attempt++ }
                    Button(onClick = { repair = true }) { Text("Pair again".uppercase()) }
                    ShowMeHow({ help = true })
                }
            } else {
                Body("Turn Wireless debugging back on and this app reconnects by itself.")
                if (waiting) StatusPill("Connecting", Tone.Info)
                else PrimaryButton("Open Developer options", onClick = debounced(env::openDeveloperOptions))
            }
        }
    }
}

@Composable
private fun PairStep(deps: SetupDeps, helpOffer: (() -> Unit)?, onPaired: () -> Unit, onFail: () -> Unit) {
    val url by deps.pairingUrl.collectAsState(null)
    var seen by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(ConnectionState.Unpaired) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val paired by rememberUpdatedState(onPaired)
    val failed by rememberUpdatedState(onFail)
    // The step can open while the app is stopped (user in Settings); a background app may not start the service.
    LaunchedEffect(Unit) { lifecycle.awaitStarted(); deps.env.startPairing() }
    DisposableEffect(Unit) { onDispose { deps.env.stopPairing() } }
    LaunchedEffect(url) {
        if (url != null) { seen = true; start = deps.connection.first(); return@LaunchedEffect }
        if (!seen) return@LaunchedEffect
        // The session ended: it worked if the connection layer moved on shortly after, even while the user is away in
        // Settings. From an Unpaired start any other state counts; from Disconnected/Connecting (re-pair) only a new
        // Connecting or Connected does. The grace only runs while the app is in front.
        val ok = coroutineScope {
            val moved = async {
                deps.connection.first {
                    it == ConnectionState.Connected ||
                        (it != start && it != ConnectionState.Unpaired &&
                            (start == ConnectionState.Unpaired || it == ConnectionState.Connecting))
                }
                true
            }
            val quiet = async { lifecycle.awaitStarted(); delay(PairGraceMs); false }
            select { moved.onAwait { it }; quiet.onAwait { it } }.also { coroutineContext.cancelChildren() }
        }
        if (ok) paired()
        else {
            seen = false; failed()
            // A background app may not start a foreground service (Android 12+); wait for the next resume.
            lifecycle.awaitStarted(); deps.env.startPairing()
        }
    }
    Page(4, "Use your phone.", side = {
        PixelText("Scan me", color = LocalSpeedColors.current.accent, size = 36.sp)
        Column(Modifier.background(Color(Cabinet.BLUE)).padding(Space.x2)) {
            QrCard {
                val u = url
                if (u != null) {
                    val bitmap = remember(u) { qrImageBitmap(u) }
                    Image(bitmap, "QR code for the pairing page", Modifier.size(180.dp))
                } else Box(Modifier.size(180.dp))
            }
        }
        Row(
            Modifier.width(260.dp).height(36.dp).background(Color(Cabinet.GREY)),
            Arrangement.spacedBy(Space.x2, Alignment.CenterHorizontally), Alignment.CenterVertically,
        ) {
            for (c in listOf(Cabinet.ORANGE, Cabinet.LOGO, Cabinet.BLUE)) Box(Modifier.size(14.dp).background(Color(c)))
        }
    }) {
        Body("On your phone, scan this code and keep the page open. It asks for 6 digits.")
        Body("On the TV, open Wireless debugging and choose Pair device with pairing code.")
        PrimaryButton("Open Developer options", onClick = debounced(deps.env::openDeveloperOptions))
        Text(url ?: "Starting...", style = MonoStyle, color = LocalSpeedColors.current.accent)
        Waiting()
        helpOffer?.let { ShowMeHow(it) }
    }
}

private suspend fun Lifecycle.awaitStarted() { currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) } }

@Composable
private fun AppPicker(apps: List<SetupApp>, picked: MutableList<String>, onContinue: () -> Unit) {
    val firstRow = remember { FocusRequester() }
    Page(6, "Pick apps to optimize.") {
        if (apps.isEmpty()) Body("No apps found.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            items(apps.size, key = { apps[it].packageName }) { i ->
                val app = apps[i]
                val on = app.packageName in picked
                if (i == 0) LaunchedEffect(Unit) { firstRow.requestFocus() }
                ListRow(
                    app.label,
                    (if (i == 0) Modifier.focusRequester(firstRow) else Modifier)
                        .semantics { role = Role.Checkbox; toggleableState = ToggleableState(on) },
                    subtitle = app.packageName,
                    onClick = { if (on) picked.remove(app.packageName) else picked.add(app.packageName) },
                ) { if (on) StatusPill("Selected", Tone.Ok) else StatusPill("Not selected", Tone.Neutral) }
            }
        }
        Button(onClick = onContinue, enabled = picked.isNotEmpty()) { Text("Continue (${picked.size} selected)".uppercase()) }
    }
}

@Composable
private fun HelpPage(step: Int, onClose: () -> Unit) {
    val text = when (step) {
        2 -> "Open Settings, go to System, then About. Find the line named Build and press OK on it 7 times. A message says you are now a developer."
        3 -> "In Developer options, scroll to Wireless debugging and switch it on. If the TV asks about Wi-Fi, choose Allow."
        4 -> "Keep the phone on the same Wi-Fi as the TV. If the QR will not scan, type the short address into the phone's browser. If the TV's pairing code times out, open Pair device with pairing code again."
        else -> "Choose Always allow from this computer on the TV's prompt, then OK. If no prompt appears, switch Wireless debugging off and on again."
    }
    Page(null, "Show me how") {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.x4), verticalAlignment = Alignment.CenterVertically) {
            QrCard { Image(remember { qrImageBitmap(HelpUrl) }, "QR code for the help page", Modifier.size(180.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                Body(text)
                Text(HelpUrl, style = MonoStyle, color = LocalSpeedColors.current.accent)
                PrimaryButton("Back", onClick = onClose)
            }
        }
    }
}

/** Polls [isMet]: waits for it to be false first, so a step that is already done does not skip itself on Back. */
@Composable
private fun AutoAdvance(isMet: () -> Boolean, onMet: () -> Unit) {
    val met by rememberUpdatedState(isMet)
    val go by rememberUpdatedState(onMet)
    LaunchedEffect(Unit) {
        while (met()) delay(PollMs)
        while (!met()) delay(PollMs)
        go()
    }
}

/** [step] 2..6 shows "Step N of 5" (step - 1) and a stage meter in the marquee; null is a plain title. [side] sits on the right. */
@Composable
private fun Page(
    step: Int?,
    title: String,
    side: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalSpeedColors.current
    RoomBackdrop {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.x4, vertical = Space.x1), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            Marquee("Setup", compact = true, onBack = LocalPageBack.current, trailing = step?.let {
                {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        PixelText("Step ${it - 1} of $Total", color = c.accent)
                        StageMeter(it - 1, Total)
                    }
                }
            })
            PixelText(title, size = 36.sp, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x4)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.x2), content = content)
                if (side != null) Column(Modifier.fillMaxHeight(), Arrangement.spacedBy(Space.x1, Alignment.CenterVertically), Alignment.CenterHorizontally, side)
            }
        }
    }
}

@Composable
private fun Body(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Counts one failure each time [NoProgressMs] pass on the step without it finishing. */
@Composable
private fun FailWhenStuck(onFail: () -> Unit) {
    val fail by rememberUpdatedState(onFail)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only time in front counts: the user may be in Settings doing the right thing.
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(NoProgressMs); fail() } } }
}

/** Ignores presses within [DebounceMs] of the last one, so an impatient double press opens Settings once. */
@Composable
private fun debounced(onClick: () -> Unit): () -> Unit {
    var last by remember { mutableStateOf<Long?>(null) }
    return {
        val now = System.nanoTime() / 1_000_000
        if (last.let { it == null || now - it >= DebounceMs }) { last = now; onClick() }
    }
}

@Composable
private fun Waiting() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x1)) {
        Lamp(LocalSpeedColors.current.accent, waiting = true)
        PixelText("Waiting for you...", color = LocalSpeedColors.current.accent)
    }
}

@Composable
// grab only where it is the page's sole action; otherwise the step's main button keeps initial focus.
private fun ShowMeHow(onClick: () -> Unit, grab: Boolean = false) =
    if (grab) PrimaryButton("Show me how", Color(Cabinet.BLUE), Color(Cabinet.CREAM), onClick)
    else ArcadeButton(onClick, fill = Color(Cabinet.BLUE), contentColor = Color(Cabinet.CREAM)) {
        Text("Show me how".uppercase(), Modifier.clearAndSetSemantics { text = AnnotatedString("Show me how") })
    }

@Composable
private fun PrimaryButton(label: String, fill: Color = Color.Unspecified, ink: Color = Color.Unspecified, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Compose can hand focus to the new button as it attaches; the button's focus style misses that. Clear and ask again.
    LaunchedEffect(Unit) { focusManager.clearFocus(); focus.requestFocus() }
    // Shown uppercase; the semantics keep the original string so text matchers outside this screen still find it.
    ArcadeButton(onClick, Modifier.focusRequester(focus), fill = fill, contentColor = ink) {
        Text(label.uppercase(), Modifier.clearAndSetSemantics { text = AnnotatedString(label) })
    }
}
