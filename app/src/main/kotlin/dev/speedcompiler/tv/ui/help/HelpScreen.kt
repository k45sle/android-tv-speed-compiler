package dev.speedcompiler.tv.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.PixelText
import androidx.compose.ui.unit.sp
import dev.speedcompiler.tv.theme.RoomBackdrop
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.speedcompiler.tv.theme.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.rememberFocusMemory
import dev.speedcompiler.tv.theme.remembersFocus
import dev.speedcompiler.tv.ui.Routes

/** One failure reason: what happened, what to do, and an optional route that fixes it. */
data class HelpEntry(val title: String, val body: String, val route: String? = null, val action: String? = null)

val helpEntries = listOf(
    HelpEntry("The TV forgot this app", "Approve it again on the TV.", Routes.QuickRepair, "Repair"),
    HelpEntry("Wireless debugging is off", "Turn it on in Developer options on the TV, then reconnect.", Routes.Reconnect, "Reconnect"),
    HelpEntry("The TV is waiting for you", "Press Allow on the prompt on the TV, then reconnect.", Routes.Reconnect, "Reconnect"),
    HelpEntry("The TV did not answer", "Wireless debugging may have switched off. Reconnect, and if that fails turn it on again in Developer options.", Routes.Reconnect, "Reconnect"),
    HelpEntry("Optimizing did not finish", "The TV stopped or refused the job. Try again when the TV is idle."),
    HelpEntry("Optimized, but not checked", "This TV does not report the result. The app may still start faster."),
    HelpEntry("The pairing code did not work", "Pairing codes expire. Get a new code on the TV and type all 6 digits."),
)

@Composable
fun HelpScreen(go: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    HelpContent(go, onBack)
}

@Composable
fun HelpContent(go: (String) -> Unit, onBack: () -> Unit = {}) {
    val mem = rememberFocusMemory(helpEntries.first { it.route != null }.title)
    RoomBackdrop {
      Column(Modifier.padding(horizontal = 48.dp, vertical = 12.dp)) {
        Marquee("Help", compact = true, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background).border(2.dp, LocalSpeedColors.current.hairline),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.x1),
        ) {
        item { PixelText("Problems and fixes", color = LocalSpeedColors.current.accent, size = 36.sp) }
        items(helpEntries) { e ->
            ListRow(e.title, Modifier.semantics { contentDescription = "${e.title}. ${e.body}" }, e.body) {
                if (e.route != null && e.action != null) {
                    Button(onClick = { go(e.route) }, modifier = Modifier.remembersFocus(mem, e.title).semantics { contentDescription = e.action }) { Text(e.action.uppercase()) }
                }
            }
        }
        }
      }
    }
}
