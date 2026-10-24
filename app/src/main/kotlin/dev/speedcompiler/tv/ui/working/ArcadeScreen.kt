package dev.speedcompiler.tv.ui.working

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.R
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.RoomBackdrop
import dev.speedcompiler.tv.theme.scrollbar
import dev.speedcompiler.tv.ui.home.rememberGraph
import kotlinx.coroutines.flow.map

/** The arcade opened from Home: the Working screen's cabinets with no job, so the games never pause. */
@Composable
fun ArcadeScreen(back: () -> Unit) {
    val graph = rememberGraph()
    val bests by remember(graph) { graph.settings.values.map { it.bests } }.collectAsState(emptyMap())
    var playing by remember { mutableStateOf<GameKind?>(null) }
    var last by remember { mutableStateOf(GameKind.entries.first()) } // Back from a game lands on its cabinet
    val first = remember { FocusRequester() }
    LaunchedEffect(playing) { if (playing == null) runCatching { first.requestFocus() } }
    playing?.let { kind ->
        GamePlayer(kind, paused = false, best = bests[kind.name] ?: 0, onBest = { saveBest(graph, kind.name, it) }, onExit = { playing = null })
        return
    }
    val scroll = rememberScrollState()
    RoomBackdrop {
        Column(
            Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(start = 48.dp, top = 12.dp, end = 34.dp), // same insets as the Working screen
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Marquee(stringResource(R.string.nav_arcade), compact = true, onBack = back)
            ArcadePicker({ last = it; playing = it }, first, last)
        }
    }
}
