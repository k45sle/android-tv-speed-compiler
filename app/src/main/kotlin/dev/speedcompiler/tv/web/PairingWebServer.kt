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
            ?: return reply(Response.Status.BAD_REQUEST, msg("Pairing port not found. Enter the port shown on the TV and press Pair."))
        val ok = runBlocking { runCatching { pairing.pair(host(), port, code) }.getOrDefault(false) }
        if (!ok) return reply(Response.Status.OK, msg("Pairing failed. Check the code on the TV and try again."))
        Timer(true).schedule(500) { finish() }
        return reply(Response.Status.OK, msg("Paired. Close this page."))
    }

    private fun reply(status: Response.IStatus, body: String) =
        newFixedLengthResponse(status, MIME_HTML, body).apply {
            addHeader("Cache-Control", "no-store")
            closeConnection(true) // an unread POST body would corrupt the next keep-alive request
        }

    private fun msg(text: String) =
        "<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<title>Speed Compile</title><style>$STYLE</style></head><body><main><p>$text</p></main></body></html>"

    companion object {
        private const val MIME_HTML = "text/html; charset=utf-8"
        private const val STYLE = "" +
            ":root{color-scheme:light dark;--fg:#1a1c1a;--bg:#fff;--line:#6b736d;--accent:#123524;--on:#fff}" +
            "@media(prefers-color-scheme:dark){:root{--fg:#e8ece9;--bg:#121413;--line:#8d968f;--accent:#7fc59a;--on:#08150d}}" +
            "body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}" +
            "main{max-width:28rem;padding:1rem}h1{font-size:1.5rem;margin:0 0 1rem}" +
            "label{display:block;margin:0 0 1rem}input{display:block;width:100%;box-sizing:border-box;min-height:44px;margin-top:.25rem;" +
            "padding:.5rem;font:inherit;color:inherit;background:var(--bg);border:1px solid var(--line);border-radius:4px}" +
            "button{min-height:44px;padding:0 1.5rem;font:inherit;font-weight:600;color:var(--on);background:var(--accent);border:0;border-radius:4px}" +
            "input:focus-visible,button:focus-visible{outline:3px solid var(--accent);outline-offset:2px}"
        val PAGE = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair Speed Compile</title><style>$STYLE</style></head>
<body><main><h1>Pair with Speed Compile</h1>
<form method="post" id="f">
<label>Pairing code (6 digits, shown on the TV)<input name="code" id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" autofocus></label>
<label>Port (enter only if pairing fails)<input name="port" inputmode="numeric" maxlength="5"></label>
<button type="submit">Pair</button>
</form>
<script>
var f=document.getElementById('f'),c=document.getElementById('code'),s=0;
f.addEventListener('submit',function(e){if(s)e.preventDefault();s=1;});
c.addEventListener('input',function(){c.value=c.value.replace(/\D/g,'').slice(0,6);if(c.value.length==6&&!s){s=1;f.submit();}});
</script></main></body></html>
""".trimIndent()
    }
}
