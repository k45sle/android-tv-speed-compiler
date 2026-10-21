package dev.speedcompiler.tv.web

import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.WatchedApp
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import org.json.JSONArray
import org.json.JSONObject

/** Plain data for the export. Deliberately has no field for keys, pairing codes, tokens, hosts or serials. */
data class DiagnosticsInput(
    val appVersion: String,
    val sdk: Int,
    val settings: SettingsValues,
    val connection: String,
    val watched: List<WatchedApp>,
    val queue: List<QueueItem>,
    val history: List<QueueItem>,
    val events: List<Event>,
)

private val SECRET_PATTERNS = listOf(
    Regex("-----BEGIN[\\s\\S]*?(-----END[^-]*-----|$)"), // key material
    Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?\\b"), // IPv4 with optional port
    Regex("\\b[\\w-]+\\.local\\b"), // mDNS host
    Regex("\\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b"), // MAC
    Regex("[A-Za-z0-9+/_=-]{24,}"), // tokens and base64 blobs
    Regex("\\b\\d{6}\\b"), // pairing code
)

/** Free text may quote anything the TV said, so strip secret-shaped substrings before export. */
fun scrub(text: String?): String? = text?.let { SECRET_PATTERNS.fold(it) { s, r -> s.replace(r, "[removed]") } }

private fun item(i: QueueItem) = JSONObject()
    .put("package", i.packageName).put("source", i.source.name).put("state", i.state.name)
    .put("enqueuedAt", i.enqueuedAt).put("startedAt", i.startedAt ?: JSONObject.NULL).put("finishedAt", i.finishedAt ?: JSONObject.NULL)
    .put("failureReason", scrub(i.failureReason) ?: JSONObject.NULL)

fun buildDiagnosticsJson(d: DiagnosticsInput): String {
    val s = d.settings
    return JSONObject()
        .put("appVersion", d.appVersion).put("androidSdk", d.sdk).put("connection", d.connection)
        .put(
            "settings",
            JSONObject().put("mode", s.mode.name).put("windowStartMinute", s.windowStartMinute)
                .put("windowEndMinute", s.windowEndMinute).put("gateMode", s.gateMode.name)
                .put("paused", s.paused).put("keepTrusting", s.keepTrusting).put("theme", s.theme.name),
        )
        .put(
            "watchedApps",
            JSONArray(d.watched.map {
                JSONObject().put("package", it.packageName).put("autoOptimize", it.autoOptimize)
                    .put("lastResult", scrub(it.lastResult) ?: JSONObject.NULL).put("lastOptimizedAt", it.lastOptimizedAt ?: JSONObject.NULL)
            }),
        )
        .put("queue", JSONArray(d.queue.map(::item)))
        .put("history", JSONArray(d.history.map(::item)))
        .put(
            "recentEvents",
            JSONArray(d.events.map {
                JSONObject().put("time", it.timeMillis).put("package", it.packageName ?: JSONObject.NULL)
                    .put("kind", it.kind).put("message", scrub(it.message))
            }),
        )
        .toString(2)
}

/**
 * Serves [json] once at /<token> on a random port: the first GET gets 200, later ones 410. Expires after
 * [PairingSession.TTL_MILLIS] (404 from then on, and the server stops). Same token pattern as [PairingSession].
 */
class DiagnosticsServer(
    private val json: String,
    private val session: PairingSession,
    private val onFinished: () -> Unit = {},
) : NanoHTTPD(0), WebServer {
    private val served = AtomicBoolean(false)
    private val timer = Timer("diagnostics-expiry", true)

    val token get() = session.token
    val port get() = listeningPort

    override fun start() {
        start(SOCKET_READ_TIMEOUT, false)
        timer.schedule(PairingSession.TTL_MILLIS) { stop() }
    }

    override fun stop() {
        timer.cancel()
        super.stop()
        onFinished()
    }

    override fun serve(s: IHTTPSession): Response {
        if (s.method != Method.GET || !session.tokenMatches(s.uri.removePrefix("/")) || session.expired()) {
            return reply(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        if (!served.compareAndSet(false, true)) return reply(Response.Status.GONE, "text/plain", "Already downloaded")
        Timer(true).schedule(1000) { stop() }
        return reply(Response.Status.OK, "application/json", json).apply {
            addHeader("Content-Disposition", "attachment; filename=\"speed-compiler-diagnostics.json\"")
        }
    }

    private fun reply(status: Response.IStatus, mime: String, body: String) =
        newFixedLengthResponse(status, mime, body).apply {
            addHeader("Cache-Control", "no-store")
            closeConnection(true)
        }
}

/** Same LAN-address pick as the pairing service. */
fun localIp(): String = NetworkInterface.getNetworkInterfaces().toList()
    .flatMap { it.inetAddresses.toList() }
    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: "127.0.0.1"
