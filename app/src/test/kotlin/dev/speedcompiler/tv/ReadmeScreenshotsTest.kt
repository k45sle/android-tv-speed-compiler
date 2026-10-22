package dev.speedcompiler.tv

import android.graphics.Bitmap
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

    private fun homeState(): HomeState {
        val watched = listOf(
            optimized("com.example.streambox", 20), optimized("com.example.retroarcade", 180),
            WatchedApp("com.example.newstoday", "1"), optimized("com.example.musichub", 26 * 60),
        )
        fun q(id: Long, pkg: String, st: QueueState) = QueueItem(id, pkg, QueueSource.AUTO, st, enqueuedAt = 1_000)
        val queue = listOf(
            QueueEntry(q(1, "com.example.newstoday", QueueState.WAITING_GATE), 0, Eta.StartsAt(Instant.parse("2026-01-01T02:00:00Z"))),
            QueueEntry(q(2, "com.example.podcastplayer", QueueState.QUEUED), 1, Eta.InQueue(1, 120_000)),
        )
        val events = listOf(
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
}
