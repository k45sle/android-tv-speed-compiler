package dev.speedcompiler.tv.ui.help

import androidx.compose.ui.platform.LocalResources
import dev.speedcompiler.tv.theme.upper
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.speedcompiler.tv.theme.ArcadeButton
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.PixelText
import dev.speedcompiler.tv.theme.RoomBackdrop
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.AppGraph
import dev.speedcompiler.tv.theme.QrCard
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.ui.home.rememberGraph
import dev.speedcompiler.tv.web.DiagnosticsInput
import dev.speedcompiler.tv.R
import androidx.compose.ui.res.stringResource
import dev.speedcompiler.tv.web.DiagnosticsServer
import dev.speedcompiler.tv.web.PairingSession
import dev.speedcompiler.tv.web.buildCappedJson
import dev.speedcompiler.tv.web.buildDiagnosticsJson
import dev.speedcompiler.tv.web.localIp
import dev.speedcompiler.tv.web.qrImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHARE_MAX_CHARS = 100_000

private suspend fun collect(g: AppGraph, version: String) = DiagnosticsInput(
    appVersion = version,
    sdk = Build.VERSION.SDK_INT,
    settings = g.settings.values.first(),
    connection = g.connectionMonitor.state.first().name,
    watched = g.database.watchedApps().list(),
    queue = g.database.queue().active().first(), // not scheduler.queue: its gate probe can take 5 s while disconnected
    history = g.scheduler.history.first(),
    events = g.database.events().recent(200).first(),
)

/** The TV's own names: replaced verbatim wherever free text quotes them. */
private fun deviceNames(c: Context): List<String> = listOf("device_name", "bluetooth_name").mapNotNull {
    runCatching { Settings.Global.getString(c.contentResolver, it) }.getOrNull()
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val graph = rememberGraph()
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf<DiagnosticsServer?>(null) }
    DisposableEffect(Unit) { onDispose { server?.stop() } }
    val share = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { share.requestFocus() } }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }

    RoomBackdrop {
      Column(Modifier.padding(horizontal = 48.dp, vertical = 12.dp)) {
        Marquee(stringResource(R.string.diag_title), compact = true, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background).border(2.dp, LocalSpeedColors.current.hairline)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x4)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        PixelText(stringResource(R.string.diag_send_title), color = LocalSpeedColors.current.accent, size = 36.sp)
        Text(
            stringResource(R.string.diag_send_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        ArcadeButton(
            onClick = {
                note = res.getString(R.string.diag_preparing)
                scope.launch {
                    val json = buildCappedJson(collect(graph, version), deviceNames(context), SHARE_MAX_CHARS)
                    note = null
                    try {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json),
                                res.getString(R.string.diag_share_diagnostics),
                            ),
                        )
                    } catch (_: RuntimeException) { // no handler, or TransactionTooLargeException
                        note = res.getString(R.string.diag_share_failed)
                    }
                }
            },
            modifier = Modifier.focusRequester(share).semantics { contentDescription = res.getString(R.string.diag_share_diagnostics) },
        ) { Text(stringResource(R.string.diag_share).upper()) }
        ArcadeButton(
            onClick = {
                scope.launch {
                    val old = server
                    server = null // so the old server's onFinished does not clear the new url
                    old?.stop()
                    url = null
                    note = res.getString(R.string.diag_preparing)
                    val json = buildDiagnosticsJson(collect(graph, version), deviceNames(context))
                    val ref = arrayOfNulls<DiagnosticsServer>(1)
                    val made = withContext(Dispatchers.IO) {
                        val ip = localIp() ?: return@withContext null
                        val s = DiagnosticsServer(json, PairingSession(graph.clock), onFinished = { if (server === ref[0]) url = null })
                        ref[0] = s
                        s.start()
                        s to "http://$ip:${s.port}/${s.token}"
                    }
                    note = null
                    if (made == null) {
                        note = res.getString(R.string.diag_connect_network)
                    } else {
                        server = made.first
                        url = made.second
                    }
                }
            },
            modifier = Modifier.semantics { contentDescription = res.getString(R.string.diag_download_desc) },
        ) { Text(stringResource(R.string.diag_download_phone).upper()) }
        note?.let { Text(it) }
        }
        url?.let {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                PixelText(stringResource(R.string.setup_scan_me), color = LocalSpeedColors.current.accent, size = 36.sp)
                Text(stringResource(R.string.diag_scan_body))
                QrCard { Image(remember(it) { qrImageBitmap(it) }, stringResource(R.string.diag_qr_desc), Modifier.size(200.dp)) }
            }
        }
        }
        }
      }
    }
}
