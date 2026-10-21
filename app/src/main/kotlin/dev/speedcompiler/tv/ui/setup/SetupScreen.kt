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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.MonoStyle
import dev.speedcompiler.tv.theme.QrCard
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.StatusPill
import dev.speedcompiler.tv.theme.StepHeader
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.web.qrImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class SetupMode { Full, Repair }

/** Public help page the "Show me how" QR points at. */
const val HelpUrl = "https://github.com/k45sle/android-tv-speed-compiler#setup-help"

private const val PollMs = 500L
private const val Total = 5
private const val PairGraceMs = 2000L

/**
 * The 7-screen first-run flow. [SetupMode.Repair] is the quick re-pair entry: it starts at screen 4.
 * Steps already done are skipped going forward; Back goes to the nearest earlier step still to do.
 */
@Composable
fun SetupScreen(onBack: () -> Unit, mode: SetupMode = SetupMode.Full, deps: SetupDeps = rememberSetupDeps()) {
    val env = deps.env
    val first = if (mode == SetupMode.Repair) 4 else 1
    var step by remember { mutableIntStateOf(first) }
    var help by remember { mutableStateOf(false) }
    val failures = remember { mutableStateMapOf<Int, Int>() }
    val conn by deps.connection.collectAsState(ConnectionState.Unpaired)
    var apps by remember { mutableStateOf(emptyList<SetupApp>()) }
    val picked = remember { mutableStateListOf<String>() }

    fun done(s: Int) = when (s) {
        2 -> env.developerOptionsOn()
        3 -> env.wirelessDebuggingOn()
        4 -> conn != ConnectionState.Unpaired
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
        while (p > 1 && done(p)) p--
        step = p
    }
    fun fail() { failures[step] = (failures[step] ?: 0) + 1 }
    val helpOffer = if ((failures[step] ?: 0) >= 2) ({ help = true }) else null

    LaunchedEffect(step == 6) { if (step == 6) apps = env.recentApps() }
    BackHandler(enabled = help || step > first) { if (help) help = false else back() }

    if (help) return HelpPage(step) { help = false }
    when (step) {
        1 -> Page(null, "Make your apps open faster.") {
            Body("Android can fully compile apps ahead of time. This app does it for the apps you pick and redoes it after each update. Results vary, and each app uses a little more storage.")
            PrimaryButton("Start", ::advance)
        }
        2 -> Page(2, "Turn on Developer options.") {
            Body("Open Settings > System > About > Build, and press it 7 times.")
            PrimaryButton("Open Settings") { fail(); env.openSettings() }
            helpOffer?.let { Button(onClick = it) { Text("Show me how") } }
            AutoAdvance({ env.developerOptionsOn() }, ::advance)
        }
        3 -> Page(3, "Turn on Wireless debugging.") {
            Body("In Developer options, switch on Wireless debugging.")
            PrimaryButton("Open Developer options") { fail(); env.openDeveloperOptions() }
            helpOffer?.let { Button(onClick = it) { Text("Show me how") } }
            AutoAdvance({ env.wirelessDebuggingOn() }, ::advance)
        }
        4 -> PairStep(deps, helpOffer, onPaired = ::advance, onFail = ::fail)
        5 -> {
            var sawConnecting by remember { mutableStateOf(false) }
            LaunchedEffect(conn) {
                if (conn == ConnectionState.Connecting) sawConnecting = true
                else if (conn == ConnectionState.Disconnected && sawConnecting) { sawConnecting = false; fail() }
            }
            Page(5, "Allow this app.") {
                Body("Your TV will ask to allow debugging. Choose Always allow from this computer.")
                StatusPill(if (conn == ConnectionState.Connecting) "Connecting" else "Waiting for you", Tone.Info)
                helpOffer?.let { PrimaryButton("Show me how", it) }
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
            val finish = { now: Boolean -> deps.onFinish(chosen.map { it.packageName }, now); onBack() }
            Page(null, "You're set.") {
                Body("$subject will be optimized when nothing is playing. It takes a minute or two each.")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                    PrimaryButton("Optimize now") { finish(true) }
                    Button(onClick = { finish(false) }) { Text("Done") }
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
    LaunchedEffect(Unit) {
        while (!env.wirelessDebuggingOn()) delay(PollMs)
        waiting = true
        if (deps.connection.first() != ConnectionState.Connected) env.reconnect()
        deps.connection.first { it == ConnectionState.Connected }
        onBack()
    }
    Page(null, "Reconnect") {
        Body("Turn Wireless debugging back on and this app reconnects by itself.")
        if (waiting) StatusPill("Connecting", Tone.Info)
        else PrimaryButton("Open Developer options") { env.openDeveloperOptions() }
    }
}

@Composable
private fun PairStep(deps: SetupDeps, helpOffer: (() -> Unit)?, onPaired: () -> Unit, onFail: () -> Unit) {
    val url by deps.pairingUrl.collectAsState(null)
    var seen by remember { mutableStateOf(false) }
    val paired by rememberUpdatedState(onPaired)
    val failed by rememberUpdatedState(onFail)
    DisposableEffect(Unit) {
        deps.env.startPairing()
        onDispose { deps.env.stopPairing() }
    }
    LaunchedEffect(url) {
        if (url != null) { seen = true; return@LaunchedEffect }
        if (!seen) return@LaunchedEffect
        // The session ended: it worked if the connection layer left Unpaired shortly after.
        val ok = withTimeoutOrNull(PairGraceMs) { deps.connection.first { it != ConnectionState.Unpaired } } != null
        if (ok) paired() else { seen = false; failed(); deps.env.startPairing() }
    }
    Page(4, "Grab your phone.") {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x4), verticalAlignment = Alignment.CenterVertically) {
            QrCard {
                val u = url
                if (u != null) {
                    val bitmap = remember(u) { qrImageBitmap(u) }
                    Image(bitmap, "QR code for the pairing page", Modifier.size(240.dp))
                } else Box(Modifier.size(240.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                Text(url ?: "Starting...", style = MonoStyle, color = LocalSpeedColors.current.accent)
                Body("Scan this, then on the TV open Wireless debugging > Pair device with pairing code, and type the 6 digits on your phone.")
                helpOffer?.let { PrimaryButton("Show me how", it) }
            }
        }
    }
}

@Composable
private fun AppPicker(apps: List<SetupApp>, picked: MutableList<String>, onContinue: () -> Unit) {
    val firstRow = remember { FocusRequester() }
    Page(6, "Pick apps to speed up.") {
        Button(onClick = onContinue, enabled = picked.isNotEmpty()) { Text("Continue") }
        if (apps.isEmpty()) Body("No apps found.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            items(apps.size, key = { apps[it].packageName }) { i ->
                val app = apps[i]
                val on = app.packageName in picked
                if (i == 0) LaunchedEffect(Unit) { firstRow.requestFocus() }
                ListRow(
                    app.label,
                    if (i == 0) Modifier.focusRequester(firstRow) else Modifier,
                    subtitle = app.packageName,
                    onClick = { if (on) picked.remove(app.packageName) else picked.add(app.packageName) },
                ) { if (on) StatusPill("Selected", Tone.Ok) }
            }
        }
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
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x4), verticalAlignment = Alignment.CenterVertically) {
            QrCard { Image(remember { qrImageBitmap(HelpUrl) }, "QR code for the help page", Modifier.size(200.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                Body(text)
                Text(HelpUrl, style = MonoStyle, color = LocalSpeedColors.current.accent)
                PrimaryButton("Back", onClose)
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

/** [step] 2..6 shows "Step N of 5" (step - 1) and a thin progress bar; null is a plain title. */
@Composable
private fun Page(step: Int?, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(Space.x4 + Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x3)) {
        if (step == null) Text(title, style = MaterialTheme.typography.displaySmall)
        else {
            StepHeader(step - 1, Total, title)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(LocalSpeedColors.current.hairline)) {
                Box(Modifier.fillMaxWidth((step - 1) / Total.toFloat()).fillMaxHeight().background(LocalSpeedColors.current.accent))
            }
        }
        content()
    }
}

@Composable
private fun Body(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Button(onClick = onClick, modifier = Modifier.focusRequester(focus)) { Text(label) }
}
