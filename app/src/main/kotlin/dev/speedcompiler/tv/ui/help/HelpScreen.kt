package dev.speedcompiler.tv.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.ui.Routes

/** One failure reason: what happened, what to do, and an optional route that fixes it. */
data class HelpEntry(val title: String, val body: String, val route: String? = null, val action: String? = null)

val helpEntries = listOf(
    HelpEntry("The TV forgot this app", "Approve it again on the TV.", Routes.QuickRepair, "Repair"),
    HelpEntry("Wireless debugging is off", "Turn it on in Developer options on the TV, then reconnect.", Routes.Reconnect, "Reconnect"),
    HelpEntry("The TV is waiting for you", "Press Allow on the prompt on the TV, then reconnect.", Routes.Reconnect, "Reconnect"),
    HelpEntry("The TV did not answer", "Check the TV is on and on the same Wi-Fi. Then try again.", Routes.Reconnect, "Reconnect"),
    HelpEntry("Optimizing did not finish", "The TV stopped or refused the job. Try again when the TV is idle."),
    HelpEntry("Optimized, but not checked", "This TV does not report the result. The app should still start faster."),
    HelpEntry("The pairing code did not work", "Codes expire fast. Get a new code on the TV and type all 6 digits."),
)

@Composable
fun HelpScreen(go: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    HelpContent(go)
}

@Composable
fun HelpContent(go: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(Space.x4),
        verticalArrangement = Arrangement.spacedBy(Space.x1),
    ) {
        item { Text("Help", style = MaterialTheme.typography.headlineMedium) }
        items(helpEntries) { e ->
            ListRow(e.title, Modifier.semantics { contentDescription = "${e.title}. ${e.body}" }, e.body) {
                if (e.route != null && e.action != null) {
                    Button(onClick = { go(e.route) }, modifier = Modifier.semantics { contentDescription = e.action }) { Text(e.action) }
                }
            }
        }
    }
}
