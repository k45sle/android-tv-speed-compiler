package dev.speedcompiler.tv.ui.help

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
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
import dev.speedcompiler.tv.web.buildDiagnosticsJson
import dev.speedcompiler.tv.web.localIp
import dev.speedcompiler.tv.web.qrImageBitmap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private suspend fun collect(g: AppGraph, version: String) = buildDiagnosticsJson(
    DiagnosticsInput(
        appVersion = version,
        sdk = Build.VERSION.SDK_INT,
        settings = g.settings.values.first(),
        connection = g.connectionMonitor.state.first().name,
        watched = g.database.watchedApps().list(),
        queue = g.scheduler.queue.first().map { it.item },
        history = g.scheduler.history.first(),
        events = g.database.events().recent(200).first(),
    ),
)

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
                scope.launch {
                    val json = collect(graph, version)
                    try {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json),
                                "Share diagnostics",
                            ),
                        )
                    } catch (_: ActivityNotFoundException) {
                        note = "No app on this TV can share. Use the QR code."
                    }
                }
            },
            modifier = Modifier.semantics { contentDescription = "Share diagnostics" },
        ) { Text("Share") }
        Button(
            onClick = {
                scope.launch {
                    server?.stop()
                    val s = DiagnosticsServer(collect(graph, version), PairingSession(graph.clock), onFinished = { url = null })
                    s.start()
                    server = s
                    url = "http://${localIp()}:${s.port}/${s.token}"
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
