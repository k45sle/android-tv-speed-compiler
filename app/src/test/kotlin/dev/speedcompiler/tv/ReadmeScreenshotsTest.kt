package dev.speedcompiler.tv

import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import java.util.concurrent.atomic.AtomicInteger
import dev.speedcompiler.tv.ui.working.WorkingHost
import dev.speedcompiler.tv.ui.Routes
import com.github.takahirom.roborazzi.recordRoboVideo
import com.github.takahirom.roborazzi.RoboVideoOptions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.theme.Presets
import dev.speedcompiler.tv.theme.SeedChoice
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.apps.AppDetailContent
import dev.speedcompiler.tv.ui.apps.AppInfo
import dev.speedcompiler.tv.ui.apps.AppsDeps
import dev.speedcompiler.tv.ui.apps.AppsScreen
import dev.speedcompiler.tv.ui.home.AppLook
import dev.speedcompiler.tv.ui.home.HomeContent
import dev.speedcompiler.tv.ui.home.HomeState
import dev.speedcompiler.tv.ui.settings.SettingsDeps
import dev.speedcompiler.tv.ui.settings.SettingsScreen
import dev.speedcompiler.tv.ui.working.JobView
import dev.speedcompiler.tv.ui.working.WorkingContent
import dev.speedcompiler.tv.ui.working.WorkingState
import dev.speedcompiler.tv.ui.working.games.AlienRunDef
import dev.speedcompiler.tv.ui.working.games.CosmicWormDef
import dev.speedcompiler.tv.ui.working.games.StarArrowDef
import dev.speedcompiler.tv.ui.working.GameKind
import dev.speedcompiler.tv.ui.home.ActivityContent
import dev.speedcompiler.tv.ui.setup.FakeSetupEnv
import dev.speedcompiler.tv.ui.setup.SetupApp
import dev.speedcompiler.tv.ui.setup.SetupDeps
import dev.speedcompiler.tv.ui.setup.SetupMode
import dev.speedcompiler.tv.ui.setup.SetupScreen
import android.graphics.BitmapFactory
import androidx.compose.ui.test.onNodeWithText
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.FakeScheduler
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.WatchedApp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * README screenshots from made-up demo data at 1920x1080. They go to build/outputs/roborazzi/readme, and to the
 * directory in env README_SHOTS_DIR (docs/media) when it is set. Skipped without -Pmedia, and recording is opt-in, so a normal run writes nothing:
 * README_SHOTS_DIR=$PWD/docs/media ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests ReadmeScreenshotsTest -Pmedia -Proborazzi.record
 */
@OptIn(ExperimentalRoborazziApi::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class ReadmeScreenshotsTest : ComposeTestBase() {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-01-01T00:30:00Z")
    private val nowMs = now.toEpochMilli()
    private val min = 60_000L

    private class Demo(val pkg: String, val label: String, val color: Int)
    private val demo = listOf(
        Demo("com.example.streambox", "StreamBox", Color.rgb(200, 70, 90)),
        Demo("com.example.retroarcade", "Retro Arcade", Color.rgb(120, 90, 200)),
        Demo(NUVIO, "Nuvio", Color.rgb(20, 18, 40)),
        Demo("com.example.podcastplayer", "Podcast Player", Color.rgb(220, 140, 50)),
        Demo("com.example.weather", "Weather", Color.rgb(50, 160, 180)),
        Demo("com.example.musichub", "Music Hub", Color.rgb(60, 170, 110)),
        Demo("com.example.photoframe", "Photo Frame", Color.rgb(170, 90, 150)),
        Demo("com.example.cookbook", "Cookbook", Color.rgb(150, 110, 70)),
    )

    /** Nuvio's own art (see test resources nuvio/NOTICE); the made-up apps get their initial on a flat colour. */
    private fun nuvio(file: String, w: Int, h: Int) =
        javaClass.classLoader!!.getResourceAsStream("nuvio/$file")!!.use { Bitmap.createScaledBitmap(BitmapFactory.decodeStream(it), w, h, true) }

    // Decoded up front: the Apps screens load banners on another thread, and a slow 1080p decode there lost the race with the paused clock.
    private val nuvioBanner = nuvio("tv_banner.png", 320, 180)
    private val nuvioIcon = nuvio("app_logo_mark.png", 96, 96)

    private fun art(d: Demo, w: Int, h: Int): Bitmap {
        if (d.pkg == NUVIO) return if (w == h) nuvioIcon else nuvioBanner
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(d.color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = h * 0.44f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText(d.label.take(1), w / 2f, h * 0.61f, p)
        return bmp
    }

    // Banners decode on Dispatchers.IO, which runs on real time, not the paused clock; shoot() waits until each started one finished.
    private val bannersStarted = AtomicInteger()
    private val bannersDone = AtomicInteger()
    private fun banner(d: Demo): Bitmap {
        bannersStarted.incrementAndGet()
        try { return art(d, 320, 180) } finally { bannersDone.incrementAndGet() }
    }

    private fun look(pkg: String) = demo.firstOrNull { it.pkg == pkg }
        ?.let { AppLook(it.label, art(it, 96, 96).asImageBitmap(), banner(it).asImageBitmap()) } ?: AppLook(pkg)

    private fun shoot(name: String, dark: Boolean, act: () -> Unit = {}, seed: SeedChoice = SeedChoice.Preset(0), content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { SpeedTheme(if (dark) ThemeChoice.Dark else ThemeChoice.Light, seed) { content() } }
        rule.mainClock.advanceTimeBy(500)
        runBlocking(Dispatchers.IO) {} // queued after the banner loads, so they have started by now
        while (bannersStarted.get() != bannersDone.get()) Thread.yield()
        act()
        // A game opens a few frames after its click; 1100 ms in, the 100 ms logo loop is on frame 3 (whole logo).
        rule.mainClock.advanceTimeBy(600)
        val out = File("build/outputs/roborazzi/readme/$name.png")
        rule.onRoot().captureRoboImage(out.path)
        // Copies only when recording, else out is missing or stale. README_SHOTS_DIR must be absolute (tests run in app/); run with cleanTest so Gradle does not skip it.
        if (System.getProperty("roborazzi.test.record") == "true") System.getenv("README_SHOTS_DIR")?.let { out.copyTo(File(it, "$name.png").absoluteFile, overwrite = true) }
    }

    private fun optimized(pkg: String, agoMin: Long) =
        WatchedApp(pkg, "1", lastResult = "Optimized (checked)", lastOptimizedAt = nowMs - agoMin * min)

    private fun q(id: Long, pkg: String, st: QueueState) = QueueItem(id, pkg, QueueSource.AUTO, st, enqueuedAt = 1_000)
    private val nuvioEntry = QueueEntry(q(1, NUVIO, QueueState.WAITING_GATE), 0, Eta.StartsAt(Instant.parse("2026-01-01T02:00:00Z")))

    /** [nuvioDone]: Nuvio was just optimized (the demo flow's last screen). */
    private fun homeState(nuvioDone: Boolean = false): HomeState {
        val watched = listOf(
            optimized("com.example.streambox", 20), optimized("com.example.retroarcade", 180),
            if (nuvioDone) optimized(NUVIO, 0) else WatchedApp(NUVIO, "1"),
            optimized("com.example.musichub", 26 * 60),
        )
        val podcast = QueueEntry(q(2, "com.example.podcastplayer", QueueState.QUEUED), 1, Eta.InQueue(1, 120_000))
        val queue = if (nuvioDone) listOf(QueueEntry(podcast.item, 0, Eta.InQueue(0, 120_000))) else listOf(nuvioEntry, podcast)
        val events = listOfNotNull(
            if (nuvioDone) Event(4, nowMs, NUVIO, "optimize_verified", "Optimized (checked)") else null,
            Event(1, nowMs - 20 * min, "com.example.streambox", "optimize_verified", "Optimized (checked)"),
            Event(2, nowMs - 180 * min, "com.example.retroarcade", "optimize_verified", "Optimized (checked)"),
            Event(3, nowMs - 26 * 60 * min, "com.example.musichub", "optimize_verified", "Optimized (checked)"),
        )
        return HomeState(ConnectionState.Connected, true, watched, queue, events, ::look)
    }

    @Test fun homeDark() = shoot("home-dark", true) { HomeContent(homeState(), {}, now, utc) }
    @Test fun homeLight() = shoot("home-light", false) { HomeContent(homeState(), {}, now, utc) }

    private fun appsDeps(): AppsDeps {
        val infos = demo.mapIndexed { i, d ->
            AppInfo(d.pkg, d.label, "${i + 1}.${i % 3}", nowMs - (i + 1) * 9L * 24 * 60 * min, banner = { banner(d) })
        }
        val watched = MutableStateFlow(listOf(
            optimized("com.example.streambox", 20), optimized("com.example.retroarcade", 180),
            WatchedApp(NUVIO, "1"), WatchedApp("com.example.podcastplayer", "1"),
            optimized("com.example.musichub", 26 * 60),
        ))
        val sched = FakeScheduler()
        runBlocking { sched.enqueue(NUVIO, QueueSource.AUTO); sched.enqueue("com.example.podcastplayer", QueueSource.AUTO) }
        return AppsDeps({ infos }, sched, watched, { false }, { _, _ -> }, { nowMs }, utc)
    }

    @Test fun appsDark() = shoot("apps-dark", true) { AppsScreen({}, {}, appsDeps()) }
    @Test fun appsLight() = shoot("apps-light", false) { AppsScreen({}, {}, appsDeps()) }

    @Test fun appDetailDark() = shoot("appdetail-dark", true) {
        val d = demo[2]
        AppDetailContent(
            AppInfo(d.pkg, d.label, "3.2", nowMs - 9L * 24 * 60 * min, banner = { banner(d) }),
            optimized(d.pkg, 20), nowMs - 20 * min, null, nowMs, utc, {}, {}, {},
        )
    }

    @Test fun activityDark() = shoot("activity-dark", true) {
        val history = listOf(
            q(5, "com.example.streambox", QueueState.DONE).copy(startedAt = nowMs - 22 * min, finishedAt = nowMs - 20 * min),
            q(4, "com.example.weather", QueueState.FAILED).copy(startedAt = nowMs - 95 * min, finishedAt = nowMs - 94 * min, failureReason = "Connection lost"),
            q(3, "com.example.retroarcade", QueueState.DONE).copy(startedAt = nowMs - 183 * min, finishedAt = nowMs - 180 * min),
        )
        val s = homeState()
        ActivityContent(s.queue, history, s.events, ::look, {}, {}, now, utc)
    }

    private fun nuvioWorking() = WorkingState("Nuvio", banner(demo[2]).asImageBitmap(), JobView.Running, 72_000, 120_000)

    @Test fun workingDark() = shoot("working-dark", true) { WorkingContent(nuvioWorking(), {}) }

    /** A game on the Working screen: open its cabinet, then [play] presses keys on it (seeded so the shot repeats). */
    @OptIn(ExperimentalTestApi::class)
    private fun gameShot(name: String, kind: GameKind, play: (press: () -> Unit) -> Unit = {}) {
        CosmicWormDef.seed = 7; AlienRunDef.seed = 7; StarArrowDef.seed = 7
        try {
            shoot(name, true, act = {
                rule.onNodeWithContentDescription("Play ${kind.title}. ${kind.hint}").performSemanticsAction(SemanticsActions.OnClick)
                rule.mainClock.advanceTimeBy(100)
                play {
                    rule.onNodeWithContentDescription("${kind.title} game. Arrow keys move, Select to act or restart, Back to stop playing.")
                        .performKeyInput { pressKey(Key.DirectionCenter) }
                }
            }) { WorkingContent(nuvioWorking(), {}) }
        } finally { CosmicWormDef.seed = null; AlienRunDef.seed = null; StarArrowDef.seed = null }
    }

    @Test fun cosmicWormDark() = gameShot("cosmicworm-dark", GameKind.CosmicWorm)
    @Test fun alienRunDark() = gameShot("alienrun-dark", GameKind.AlienRun) { press ->
        rule.mainClock.advanceTimeBy(1_600); press(); rule.mainClock.advanceTimeBy(300) // mid-jump over the first cactus
    }
    @Test fun starArrowDark() = gameShot("stararrow-dark", GameKind.StarArrow) { press ->
        // A flap every 650 ms (the key press takes 50) holds the ship level; shoot() waits one more period, so no wait after the last.
        press(); repeat(4) { rule.mainClock.advanceTimeBy(600); press() }
    }

    private val plum = Presets.indexOfFirst { it.name == "Plum" }

    // The one non-default preset in the README, so a theme regression outside the default palette shows up in review.
    @Test fun settingsPlum() = shoot("settings-plum", true, seed = SeedChoice.Preset(plum)) {
        val v = MutableStateFlow(SettingsValues(setupDone = true, theme = ThemeChoice.Dark, accentPreset = plum))
        SettingsScreen({}, {}, SettingsDeps(v, { f -> v.value = f(v.value) }, flowOf(KeeperStatus.Connected), "1.0.0"))
    }

    private val setupApps = listOf(SetupApp(NUVIO, "Nuvio"), SetupApp("com.example.streambox", "StreamBox"), SetupApp("com.example.retroarcade", "Retro Arcade"), SetupApp("com.example.musichub", "Music Hub"))

    /** Setup with Developer options, Wireless debugging and the connection already done, so Start goes to the app picker. */
    private fun setupDeps(ready: Boolean, url: String? = null) = SetupDeps(
        FakeSetupEnv(ready, ready, setupApps), MutableStateFlow(if (ready) ConnectionState.Connected else ConnectionState.Unpaired), MutableStateFlow(url),
    )

    private fun pickApps() {
        rule.onNodeWithText("Start").performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeBy(500)
        listOf("Nuvio", "Retro Arcade").forEach { rule.onNodeWithText(it).performSemanticsAction(SemanticsActions.OnClick); rule.mainClock.advanceTimeBy(100) }
    }

    @Test fun setupWelcomeDark() = shoot("setup-welcome-dark", true) { SetupScreen({}, deps = setupDeps(false)) }
    @Test fun setupPairDark() = shoot("setup-pair-dark", true) {
        // 192.0.2.x is the documentation range (RFC 5737), and the token is made up.
        SetupScreen({}, SetupMode.Repair, setupDeps(false, "http://192.0.2.10:41237/3f9c2a7be15d4086c4e1a9b2d7f05c63"))
    }
    @Test fun setupAppsDark() = shoot("setup-apps-dark", true, act = ::pickApps) { SetupScreen({}, deps = setupDeps(true)) }
    @Test fun setupDoneDark() = shoot("setup-done-dark", true, act = {
        pickApps(); rule.onNodeWithText("Continue (2 selected)", ignoreCase = true).performSemanticsAction(SemanticsActions.OnClick)
    }) { SetupScreen({}, deps = setupDeps(true)) }

    private enum class Step { Home, Apps, Detail, Working, HomeDone }

    /**
     * README demo clip: Home, Apps, Nuvio, Optimize now, the Working screen and a few seconds of each game (Back to the arcade
     * between them), the job finishing during Cosmic Worm, Done, and Home with Nuvio optimized. Runs only with DEMO_FLOW_GIF=<absolute path>; tools/demo.sh builds the video.
     */
    @OptIn(ExperimentalTestApi::class)
    @Config(qualifiers = "w960dp-h540dp-mdpi") // 960x540 like the old demo; 1080p frames run out of heap
    @Test fun demoFlow() {
        val path = System.getenv("DEMO_FLOW_GIF")
        assumeNotNull(path)
        val nuvio = demo[2]
        val clock = { nowMs + rule.mainClock.currentTime }
        var run = q(9, nuvio.pkg, QueueState.RUNNING).copy(source = QueueSource.MANUAL, runNow = true)
        val queue = MutableStateFlow(emptyList<QueueEntry>())
        val history = MutableStateFlow(emptyList<QueueItem>())
        var step by mutableStateOf(Step.Home)
        rule.mainClock.autoAdvance = false // the games and the Working banner never go idle
        rule.setContent {
            // Robolectric starts in touch mode, where the clickable rows cannot take focus; a TV remote is keyboard mode.
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            SpeedTheme(ThemeChoice.Dark) {
                when (step) {
                    Step.Home -> HomeContent(homeState(), { if (it == Routes.Apps) step = Step.Apps }, now, utc)
                    Step.Apps -> AppsScreen({ if (it == Routes.appDetail(nuvio.pkg)) step = Step.Detail }, {}, appsDeps())
                    Step.Detail -> AppDetailContent(
                        AppInfo(nuvio.pkg, nuvio.label, "3.2", nowMs - 27L * 24 * 60 * min, banner = { banner(nuvio) }),
                        WatchedApp(nuvio.pkg, "1"), null, QueueEntry(nuvioEntry.item.copy(state = QueueState.QUEUED), 0, Eta.InQueue(0, 60_000)), nowMs, utc,
                        { run = run.copy(startedAt = clock()); queue.value = listOf(QueueEntry(run, 0, Eta.None)); step = Step.Working }, {}, {},
                    )
                    Step.Working -> WorkingHost(nuvio.pkg, look(nuvio.pkg), queue, history, clock) { step = Step.HomeDone }
                    Step.HomeDone -> HomeContent(homeState(nuvioDone = true), {}, now, utc)
                }
            }
        }
        rule.mainClock.advanceTimeByFrame() // keyboard mode first, so the first focus shows its ring
        // Robolectric's window never gets focus, and the screens wait for it before taking their first focus.
        rule.runOnUiThread { rule.activity.window.decorView.dispatchWindowFocusChanged(true) }
        rule.mainClock.advanceTimeByFrame()
        // Key events go to whatever has focus, as with a remote.
        fun press(key: Key = Key.DirectionCenter) = rule.onRoot().performKeyInput { pressKey(key) }
        CosmicWormDef.seed = 270 // picked by simulation: the gate loop below eats two stars on it
        AlienRunDef.seed = 7; StarArrowDef.seed = 7
        try {
            rule.onRoot().recordRoboVideo(rule, File(path), RoboVideoOptions(fps = 15, settleTimeoutMillis = 100)) {
                // The first focus shows no ring under Robolectric, so step off the Apps button and back.
                delay(1_200)
                press(Key.DirectionRight)
                delay(600)
                press(Key.DirectionLeft)
                delay(900)
                press()
                delay(1_000)
                repeat(3) { press(Key.DirectionDown); delay(500) }
                delay(500)
                press()
                delay(2_500)
                press()
                delay(2_000)
                // After Back the picker focuses its first cabinet (Cosmic Worm), then Alien Run and Star Arrow to its right.
                press(Key.DirectionRight)
                delay(600)
                press()
                delay(1_600) // the first cactus reaches the alien at about 2.1 s
                press()
                delay(1_600)
                press(Key.Back)
                delay(900)
                repeat(2) { press(Key.DirectionRight); delay(400) }
                delay(300)
                press()
                delay(500)
                // About 2 s of flight, out before the first rock column: the video's frame steps make longer blind flapping drift into the rocks.
                repeat(3) { press(); delay(600) }
                press(Key.Back)
                delay(900)
                press()
                // The head starts at (4, 3) going right, a cell every 0.17 s: at x = 10 turn up through the gate and loop the board.
                delay(1_000)
                press(Key.DirectionUp)
                delay(7_000)
                queue.value = emptyList()
                history.value = listOf(run.copy(state = QueueState.DONE, finishedAt = clock()))
                delay(2_000)
                press(Key.Back)
                delay(1_500)
                press()
                delay(3_000)
            }
        } finally { CosmicWormDef.seed = null; AlienRunDef.seed = null; StarArrowDef.seed = null }
    }

    private companion object {
        const val NUVIO = "com.nuvio.tv"
        @JvmStatic @BeforeClass fun mediaOnly() = assumeTrue(System.getProperty("media") != null)
    }
}
