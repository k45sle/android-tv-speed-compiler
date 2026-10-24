package dev.speedcompiler.tv.web

import android.content.res.Resources
import dev.speedcompiler.tv.R
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
    private val res: Resources,
    private val session: PairingSession,
    private val pairing: Pairing,
    private val host: () -> String,
    private val pairingPort: () -> Int?,
    private val onFinished: () -> Unit = {},
    /** Runs once the TV accepted the code; the connection layer does not connect on its own. */
    private val onPaired: () -> Unit = {},
) : NanoHTTPD(0) {
    private val timer = Timer("pairing-expiry", true)

    val port get() = listeningPort
    val token get() = session.token

    init {
        setAsyncRunner(object : DefaultAsyncRunner() {
            // Extra connections are closed instead of getting a thread.
            override fun exec(clientHandler: ClientHandler) {
                if (running.size >= MAX_HANDLERS) clientHandler.close() else super.exec(clientHandler)
            }
        })
    }

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
        if (get) return reply(Response.Status.OK, page(res))
        if ((s.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_BODY) {
            return reply(Response.Status.lookup(413) ?: Response.Status.BAD_REQUEST, msg(res.getString(R.string.web_pair_too_large)))
        }
        s.parseBody(HashMap())
        // Malformed codes are rejected before they can use up an attempt.
        val code = PairingSession.cleanCode(s.parms["code"])
            ?: return reply(Response.Status.BAD_REQUEST, msg(res.getString(R.string.web_pair_enter_code)))
        if (!session.tryAttempt()) return reply(Response.Status.FORBIDDEN, msg(res.getString(R.string.web_pair_too_many)))
        // The discovered port wins; the form port is only a fallback when discovery found nothing.
        val port = pairingPort() ?: s.parms["port"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return reply(Response.Status.BAD_REQUEST, msg(res.getString(R.string.web_pair_no_port)))
        val ok = runBlocking { runCatching { pairing.pair(host(), port, code) }.getOrDefault(false) }
        if (!ok) return reply(Response.Status.OK, msg(res.getString(R.string.web_pair_failed)))
        onPaired()
        Timer(true).schedule(500) { finish() }
        return reply(Response.Status.OK, msg(res.getString(R.string.web_pair_done)))
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
        private const val MAX_BODY = 4096L
        private const val MAX_HANDLERS = 8
        private const val MIME_HTML = "text/html; charset=utf-8"
        private const val STYLE = "" +
            ":root{color-scheme:light dark;--fg:#123524;--bg:#f2e6c4;--line:#3f6450;--accent:#123524;--on:#f2e6c4}" +
            "@media(prefers-color-scheme:dark){:root{--fg:#f2e6c4;--bg:#0b2017;--line:#9dbfa9;--accent:#e8a33a;--on:#0b2017}}" +
            "body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}" +
            "main{max-width:28rem;padding:1rem}h1{font-size:1.5rem;margin:0 0 1rem}" +
            "label{display:block;margin:0 0 1rem}input{display:block;width:100%;box-sizing:border-box;min-height:44px;margin-top:.25rem;" +
            "padding:.5rem;font:inherit;color:inherit;background:var(--bg);border:2px solid var(--line);border-radius:0}" +
            "button{min-height:44px;padding:0 1.5rem;font:inherit;font-weight:600;color:var(--on);background:var(--accent);border:0;border-radius:0}" +
            "input:focus-visible,button:focus-visible{outline:3px solid var(--accent);outline-offset:2px}"
        fun page(res: Resources) = """
<!doctype html><html lang="${res.getString(R.string.web_page_lang)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${res.getString(R.string.web_page_title)}</title><style>$STYLE</style></head>
<body><main><h1>${res.getString(R.string.web_page_heading)}</h1>
<form method="post" id="f">
<label>${res.getString(R.string.web_page_code_label)}<input name="code" id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" autofocus></label>
<label>${res.getString(R.string.web_page_port_label)}<input name="port" inputmode="numeric" maxlength="5"></label>
<button type="submit">${res.getString(R.string.web_page_pair)}</button>
</form>
<script>
var f=document.getElementById('f'),c=document.getElementById('code'),s=0;
f.addEventListener('submit',function(e){if(s)e.preventDefault();s=1;});
c.addEventListener('input',function(){c.value=c.value.replace(/\D/g,'').slice(0,6);if(c.value.length==6&&!s){s=1;f.submit();}});
</script></main></body></html>
""".trimIndent()
    }
}
