package dev.speedcompiler.tv.ui.help

import dev.speedcompiler.tv.theme.scrollbar
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.speedcompiler.tv.theme.upper
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
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.R
import androidx.compose.ui.res.stringResource
import dev.speedcompiler.tv.ui.home.fixRoute
import androidx.annotation.StringRes

/** One failure reason: what happened, what to do, and an optional route that fixes it. */
data class HelpEntry(@StringRes val title: Int, @StringRes val body: Int, val fix: Fix? = null)

val helpEntries = listOf(
    HelpEntry(R.string.help_forgot_title, R.string.help_forgot_body, Fix.QuickRepair),
    HelpEntry(R.string.help_wireless_title, R.string.help_wireless_body, Fix.TurnOnWirelessDebugging),
    HelpEntry(R.string.help_waiting_title, R.string.help_waiting_body, Fix.AllowPrompt),
    HelpEntry(R.string.help_no_answer_title, R.string.help_no_answer_body, Fix.Retry),
    HelpEntry(R.string.help_not_finished_title, R.string.help_not_finished_body),
    HelpEntry(R.string.help_not_checked_title, R.string.help_not_checked_body),
    HelpEntry(R.string.help_changes_title, R.string.help_changes_body),
    HelpEntry(R.string.help_undo_title, R.string.help_undo_body),
    HelpEntry(R.string.help_pairing_title, R.string.help_pairing_body),
)

@Composable
fun HelpScreen(go: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    HelpContent(go, onBack)
}

@Composable
fun HelpContent(go: (String) -> Unit, onBack: () -> Unit = {}) {
    val mem = rememberFocusMemory(helpEntries.first { it.fix != null }.title.toString())
    RoomBackdrop {
      Column(Modifier.padding(horizontal = 48.dp, vertical = 12.dp)) {
        Marquee(stringResource(R.string.help_title), compact = true, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        val list = rememberLazyListState()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background).border(2.dp, LocalSpeedColors.current.hairline).scrollbar(list),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.x1),
        ) {
        item { PixelText(stringResource(R.string.help_problems_title), color = LocalSpeedColors.current.accent, size = 36.sp) }
        items(helpEntries) { e ->
            val title = stringResource(e.title)
            val body = stringResource(e.body)
            val desc = stringResource(R.string.help_entry_desc, title, body)
            val fixLabel = stringResource(R.string.common_fix_connection)
            ListRow(title, Modifier.semantics { contentDescription = desc }, body) {
                if (e.fix != null) {
                    Button(onClick = { go(fixRoute(e.fix)) }, modifier = Modifier.remembersFocus(mem, e.title.toString()).semantics { contentDescription = fixLabel }) { Text(fixLabel.upper()) }
                }
            }
        }
        }
      }
    }
}
