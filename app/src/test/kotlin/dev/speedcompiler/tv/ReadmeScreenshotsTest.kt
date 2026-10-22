package dev.speedcompiler.tv

import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import org.junit.Assume.assumeNotNull
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
import dev.speedcompiler.tv.ui.working.games.CosmicWormDef
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * README screenshots from made-up demo data at 1920x1080. They go to build/outputs/roborazzi/readme, and to the
 * directory in env README_SHOTS_DIR (docs/media) when it is set. Recording is opt-in, so a normal test run writes nothing:
 * README_SHOTS_DIR=$PWD/docs/media ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests ReadmeScreenshotsTest -Proborazzi.record
 */
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
        Demo("com.example.newstoday", "News Today", Color.rgb(60, 130, 200)),
        Demo("com.example.podcastplayer", "Podcast Player", Color.rgb(220, 140, 50)),
        Demo("com.example.weather", "Weather", Color.rgb(50, 160, 180)),
        Demo("com.example.musichub", "Music Hub", Color.rgb(60, 170, 110)),
        Demo("com.example.photoframe", "Photo Frame", Color.rgb(170, 90, 150)),
        Demo("com.example.cookbook", "Cookbook", Color.rgb(150, 110, 70)),
    )

    private fun banner(d: Demo): Bitmap {
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(d.color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 80f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText(d.label.take(1), 160f, 110f, p)
        return bmp
    }

    private fun look(pkg: String) = demo.firstOrNull { it.pkg == pkg }
        ?.let { AppLook(it.label, banner = banner(it).asImageBitmap()) } ?: AppLook(pkg)

    private fun shoot(name: String, dark: Boolean, act: () -> Unit = {}, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { SpeedTheme(if (dark) ThemeChoice.Dark else ThemeChoice.Light) { content() } }
        rule.mainClock.advanceTimeBy(500)
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
    private val newsEntry = QueueEntry(q(1, "com.example.newstoday", QueueState.WAITING_GATE), 0, Eta.StartsAt(Instant.parse("2026-01-01T02:00:00Z")))

    /** [newsDone]: News Today was just optimized (the demo flow's last screen). */
    private fun homeState(newsDone: Boolean = false): HomeState {
        val watched = listOf(
            optimized("com.example.streambox", 20), optimized("com.example.retroarcade", 180),
            if (newsDone) optimized("com.example.newstoday", 0) else WatchedApp("com.example.newstoday", "1"),
            optimized("com.example.musichub", 26 * 60),
        )
        val podcast = QueueEntry(q(2, "com.example.podcastplayer", QueueState.QUEUED), 1, Eta.InQueue(1, 120_000))
        val queue = if (newsDone) listOf(QueueEntry(podcast.item, 0, Eta.InQueue(0, 120_000))) else listOf(newsEntry, podcast)
        val events = listOfNotNull(
            if (newsDone) Event(4, nowMs, "com.example.newstoday", "optimize_verified", "Optimized (checked)") else null,
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
            WatchedApp("com.example.newstoday", "1"), WatchedApp("com.example.podcastplayer", "1"),
            optimized("com.example.musichub", 26 * 60),
        ))
        val sched = FakeScheduler()
        runBlocking { sched.enqueue("com.example.newstoday", QueueSource.AUTO); sched.enqueue("com.example.podcastplayer", QueueSource.AUTO) }
        return AppsDeps({ infos }, sched, watched, { false }, { _, _ -> }, { nowMs }, utc)
    }

    @Test fun appsDark() = shoot("apps-dark", true) { AppsScreen({}, {}, appsDeps()) }
    @Test fun appsLight() = shoot("apps-light", false) { AppsScreen({}, {}, appsDeps()) }

    @Test fun appDetailDark() = shoot("appdetail-dark", true) {
        val d = demo[0]
        AppDetailContent(
            AppInfo(d.pkg, d.label, "4.2", nowMs - 9L * 24 * 60 * min, banner = { banner(d) }),
            optimized(d.pkg, 20), nowMs - 20 * min, null, nowMs, utc, {}, {}, {},
        )
    }

    @Test fun workingDark() {
        CosmicWormDef.seed = 7 // the food positions are random otherwise, so the shot changed on every run
        try {
            shoot(
                "working-dark", true,
                act = { rule.onNodeWithContentDescription("Play Cosmic Worm. Eat stars, use the gates").performSemanticsAction(SemanticsActions.OnClick) },
            ) {
                WorkingContent(WorkingState("Retro Arcade", banner(demo[1]).asImageBitmap(), JobView.Running, 72_000, 120_000), {})
            }
        } finally { CosmicWormDef.seed = null }
    }

    @Test fun settingsDark() = shoot("settings-dark", true) {
        val v = MutableStateFlow(SettingsValues(setupDone = true, theme = ThemeChoice.Dark))
        SettingsScreen({}, {}, SettingsDeps(v, { f -> v.value = f(v.value) }, flowOf(KeeperStatus.Connected), "1.0.0"))
    }

    private enum class Step { Home, Apps, Detail, Working, HomeDone }

    /**
     * README demo clip: Home, Apps, News Today, Optimize now, the Working screen with a few seconds of Cosmic Worm, the job
     * finishing, Done, and Home with News Today optimized. Runs only with DEMO_FLOW_GIF=<absolute path>; tools/demo.sh builds the video.
     */
    @OptIn(ExperimentalTestApi::class)
    @Config(qualifiers = "w960dp-h540dp-mdpi") // 960x540 like the old demo; 1080p frames run out of heap
    @Test fun demoFlow() {
        val path = System.getenv("DEMO_FLOW_GIF")
        assumeNotNull(path)
        val news = demo[2]
        val clock = { nowMs + rule.mainClock.currentTime }
        var run = q(9, news.pkg, QueueState.RUNNING).copy(source = QueueSource.MANUAL, runNow = true)
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
                    Step.Apps -> AppsScreen({ if (it == Routes.appDetail(news.pkg)) step = Step.Detail }, {}, appsDeps())
                    Step.Detail -> AppDetailContent(
                        AppInfo(news.pkg, news.label, "3.2", nowMs - 27L * 24 * 60 * min, banner = { banner(news) }),
                        WatchedApp(news.pkg, "1"), null, QueueEntry(newsEntry.item.copy(state = QueueState.QUEUED), 0, Eta.InQueue(0, 60_000)), nowMs, utc,
                        { run = run.copy(startedAt = clock()); queue.value = listOf(QueueEntry(run, 0, Eta.None)); step = Step.Working }, {}, {},
                    )
                    Step.Working -> WorkingHost(news.pkg, look(news.pkg), queue, history, clock) { step = Step.HomeDone }
                    Step.HomeDone -> HomeContent(homeState(newsDone = true), {}, now, utc)
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
        } finally { CosmicWormDef.seed = null }
    }
}
