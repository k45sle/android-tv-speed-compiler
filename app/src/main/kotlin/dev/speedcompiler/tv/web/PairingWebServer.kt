package dev.speedcompiler.tv.web

import dev.speedcompiler.tv.adb.Pairing
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * One-page pairing server on a random port. Only GET and POST on /<token> exist; everything else is 404.
 * [host] and [pairingPort] supply the TV's own wireless-debugging endpoint (from NSD discovery).
 */
class PairingWebServer(
    private val session: PairingSession,
    private val pairing: Pairing,
    private val host: () -> String,
    private val pairingPort: () -> Int?,
    private val onFinished: () -> Unit = {},
) : NanoHTTPD(0), WebServer {
    private val timer = Timer("pairing-expiry", true)

    val port get() = listeningPort
    val token get() = session.token

    override fun start() {
        start(SOCKET_READ_TIMEOUT, false)
        timer.schedule(PairingSession.TTL_MILLIS) { finish() }
    }

    override fun stop() {
        timer.cancel()
        super.stop()
    }

    private fun finish() {
        stop()
        onFinished()
    }

    override fun serve(s: IHTTPSession): Response {
        val get = s.method == Method.GET
        val post = s.method == Method.POST
        if (!(get || post) || !session.tokenMatches(s.uri.removePrefix("/")) || session.expired()) {
            return reply(Response.Status.NOT_FOUND, "Not found")
        }
        if (get) return reply(Response.Status.OK, PAGE)
        if (!session.tryAttempt()) return reply(Response.Status.FORBIDDEN, msg("Too many attempts. Restart pairing on the TV."))
        s.parseBody(HashMap())
        val code = PairingSession.cleanCode(s.parms["code"])
            ?: return reply(Response.Status.BAD_REQUEST, msg("Enter the 6-digit code."))
        val port = s.parms["port"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: pairingPort()
            ?: return reply(Response.Status.BAD_REQUEST, msg("Pairing port not found. Enter the port shown on the TV."))
        val ok = runBlocking { runCatching { pairing.pair(host(), port, code) }.getOrDefault(false) }
        if (!ok) return reply(Response.Status.OK, msg("Pairing failed. Check the code and try again."))
        Timer(true).schedule(500) { finish() }
        return reply(Response.Status.OK, msg("Paired. You can close this page."))
    }

    private fun reply(status: Response.IStatus, body: String) =
        newFixedLengthResponse(status, MIME_HTML, body).apply {
            addHeader("Cache-Control", "no-store")
            closeConnection(true) // an unread POST body would corrupt the next keep-alive request
        }

    private fun msg(text: String) = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><p>$text</p>"

    companion object {
        private const val MIME_HTML = "text/html; charset=utf-8"
        val PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair TV</title><style>body{font-family:sans-serif;margin:2em}input{font-size:2em;width:100%;box-sizing:border-box;margin:.3em 0}</style></head>
<body><h1>Pair with the TV</h1>
<form method="post" id="f">
<label>Pairing code<input name="code" id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" autofocus></label>
<label>Port (only if pairing fails)<input name="port" inputmode="numeric" maxlength="5"></label>
</form>
<script>
var c=document.getElementById('code');
c.addEventListener('input',function(){c.value=c.value.replace(/\D/g,'').slice(0,6);if(c.value.length==6)document.getElementById('f').submit();});
</script></body></html>
""".trimIndent()
    }
}
