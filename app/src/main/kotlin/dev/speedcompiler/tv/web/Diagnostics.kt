package dev.speedcompiler.tv.web

import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.WatchedApp
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
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
    Regex("[\\w.-]+/[\\w.-]+/[\\w.-]+:\\S*-keys"), // build fingerprint (carries the device codename)
    Regex("(?i)\\bssid\\s*[:=]?\\s*(\"[^\"]*\"|'[^']*'|\\S+)"), // network name after the word SSID
    Regex("\\b[0-9A-Fa-f]{2}([:-])([0-9A-Fa-f]{2}\\1){4}[0-9A-Fa-f]{2}\\b"), // MAC with colons or dashes
    Regex("\\S+@\\S+"), // adb public key tail (user@host)
    Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?\\b"), // IPv4 with optional port
    Regex("(?<![\\w:])([0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|(?<![\\w:])([0-9A-Fa-f]{1,4}(:[0-9A-Fa-f]{1,4}){0,6})?::([0-9A-Fa-f]{1,4}(:[0-9A-Fa-f]{1,4}){0,6})?"), // IPv6: eight groups or a "::"
    Regex("\\b[\\w-]+\\.local\\b"), // mDNS host
    Regex("\\bemulator-\\d+"),
    Regex("(?i)\\bport\\s*:?\\s*\\d{1,5}\\b"),
    Regex(":\\d{2,5}\\b"),
    Regex("[A-Za-z0-9+/_=-]{24,}"), // tokens and base64 blobs
    Regex("\\b\\d{3}[ -]?\\d{3}\\b"), // pairing code, also "123 456" and "123-456"
    Regex("\\b(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\\d)[A-Z0-9]{8,}\\b"), // serial-like
)

/**
 * Free text may quote anything the TV said, so strip secret-shaped substrings before export.
 * [known] are exact strings (for example the device name) replaced verbatim first.
 */
fun scrub(text: String?, known: List<String> = emptyList()): String? = text?.let {
    // Clock times are set aside first: the port pattern would otherwise eat ":30" from "12:30:45".
    val times = mutableListOf<String>()
    val masked = TIME.replace(known.filter(String::isNotBlank).fold(it.replace("\u0001", "")) { s, k -> s.replace(k, "[removed]") }) { m ->
        times += m.value
        "\u0001${times.size - 1}\u0001"
    }
    val clean = SECRET_PATTERNS.fold(masked) { s, r -> s.replace(r, "[removed]") }
    Regex("\u0001(\\d+)\u0001").replace(clean) { times[it.groupValues[1].toInt()] }
}

private val TIME = Regex("(?<![\\w.:])([01]?\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?(?![\\w:])")

private fun item(i: QueueItem, k: List<String>) = JSONObject()
    .put("package", i.packageName).put("source", i.source.name).put("state", i.state.name)
    .put("enqueuedAt", i.enqueuedAt).put("startedAt", i.startedAt ?: JSONObject.NULL).put("finishedAt", i.finishedAt ?: JSONObject.NULL)
    .put("failureReason", scrub(i.failureReason, k) ?: JSONObject.NULL)

fun buildDiagnosticsJson(d: DiagnosticsInput, known: List<String> = emptyList(), note: String? = null): String {
    val s = d.settings
    return JSONObject()
        .apply { if (note != null) put("note", note) }
        .put("appVersion", d.appVersion).put("androidSdk", d.sdk).put("connection", d.connection)
        .put(
            "settings",
            JSONObject().put("mode", s.mode.name).put("windowStartMinute", s.windowStartMinute)
                .put("windowEndMinute", s.windowEndMinute).put("gateMode", s.gateMode.name)
                .put("paused", s.paused).put("keepTrusting", s.keepTrusting).put("keepWifiDebugging", s.keepWifiDebugging).put("theme", s.theme.name),
        )
        .put(
            "watchedApps",
            JSONArray(d.watched.map {
                JSONObject().put("package", it.packageName).put("autoOptimize", it.autoOptimize)
                    .put("lastResult", scrub(it.lastResult, known) ?: JSONObject.NULL).put("lastOptimizedAt", it.lastOptimizedAt ?: JSONObject.NULL)
            }),
        )
        .put("queue", JSONArray(d.queue.map { item(it, known) }))
        .put("history", JSONArray(d.history.map { item(it, known) }))
        .put(
            "recentEvents",
            JSONArray(d.events.map {
                JSONObject().put("time", it.timeMillis).put("package", it.packageName ?: JSONObject.NULL)
                    .put("kind", scrub(it.kind, known)).put("message", scrub(it.message, known))
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
) : NanoHTTPD(0) {
    private val served = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val timer = Timer("diagnostics-expiry", true)

    val token get() = session.token
    val port get() = listeningPort

    override fun start() {
        start(SOCKET_READ_TIMEOUT, false)
        timer.schedule(PairingSession.TTL_MILLIS) { stop() }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        timer.cancel()
        super.stop()
        onFinished()
    }

    override fun serve(s: IHTTPSession): Response {
        if (s.method != Method.GET || !session.tokenMatches(s.uri.removePrefix("/")) || session.expired()) {
            return reply(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        if (!served.compareAndSet(false, true)) return reply(Response.Status.GONE, "text/plain", "Already downloaded. Export again from the TV to get a new link.")
        val bytes = json.toByteArray()
        // Stop only once NanoHTTPD has written the body and closes the stream.
        val body = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun close() { super.close(); Thread { stop() }.start() }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", body, bytes.size.toLong()).apply {
            addHeader("Cache-Control", "no-store")
            closeConnection(true)
            addHeader("Content-Disposition", "attachment; filename=\"speed-compile-diagnostics.json\"")
        }
    }

    private fun reply(status: Response.IStatus, mime: String, body: String) =
        newFixedLengthResponse(status, mime, body).apply {
            addHeader("Cache-Control", "no-store")
            closeConnection(true)
        }
}

/** Same LAN-address pick as the pairing service; null when the TV has no site-local IPv4 address. Blocking. */
fun localIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .flatMap { it.inetAddresses.toList() }
    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

/** JSON capped at [max] chars: drops older events (the list is newest first) until it fits. */
fun buildCappedJson(d: DiagnosticsInput, known: List<String>, max: Int): String {
    var n = d.events.size
    var json = buildDiagnosticsJson(d, known)
    while (json.length > max && n > 0) {
        n /= 2
        json = buildDiagnosticsJson(d.copy(events = d.events.take(n)), known, "trimmed, use the QR download for the full file")
    }
    return json
}
