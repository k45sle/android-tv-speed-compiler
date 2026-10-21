package dev.speedcompiler.tv.ui.help

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.AppGraph
import dev.speedcompiler.tv.theme.QrCard
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.ui.home.rememberGraph
import dev.speedcompiler.tv.web.DiagnosticsInput
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
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf<DiagnosticsServer?>(null) }
    DisposableEffect(Unit) { onDispose { server?.stop() } }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }

    Column(Modifier.fillMaxSize().padding(Space.x4), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Settings, queue and recent events. It has no keys, codes or addresses.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                note = "Preparing…"
                scope.launch {
                    val json = buildCappedJson(collect(graph, version), deviceNames(context), SHARE_MAX_CHARS)
                    note = null
                    try {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json),
                                "Share diagnostics",
                            ),
                        )
                    } catch (_: RuntimeException) { // no handler, or TransactionTooLargeException
                        note = "Sharing did not work on this TV. Use the QR code."
                    }
                }
            },
            modifier = Modifier.semantics { contentDescription = "Share diagnostics" },
        ) { Text("Share") }
        Button(
            onClick = {
                scope.launch {
                    val old = server
                    server = null // so the old server's onFinished does not clear the new url
                    old?.stop()
                    url = null
                    note = "Preparing…"
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
                        note = "Connect the TV to Wi-Fi or Ethernet to use the QR download"
                    } else {
                        server = made.first
                        url = made.second
                    }
                }
            },
            modifier = Modifier.semantics { contentDescription = "Show QR code to download on a phone" },
        ) { Text("Download on phone") }
        note?.let { Text(it) }
        url?.let {
            Text("Scan with your phone on the same Wi-Fi. Works once, for 10 minutes.")
            QrCard { Image(remember(it) { qrImageBitmap(it) }, "QR code to download diagnostics", Modifier.size(240.dp)) }
        }
    }
}
