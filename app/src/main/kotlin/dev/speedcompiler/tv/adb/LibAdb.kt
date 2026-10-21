package dev.speedcompiler.tv.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/** Exit code reported when the `__rc=` marker never arrived (the stream ended early). */
const val MISSING_RC = -1

private val RC_LINE = Regex("__rc=(\\d+)\\r?\\n?$")
private val RC_END = Regex("__rc=\\d+\\r?\\n$")

/** adb shell v1 has no exit status, so [LibAdb.run] appends `; echo __rc=$?`; this strips it back off. */
fun parseShellOutput(raw: String): ShellResult {
    val m = RC_LINE.find(raw) ?: return ShellResult(MISSING_RC, raw)
    return ShellResult(m.groupValues[1].toInt(), raw.substring(0, m.range.first))
}

/**
 * The real [AdbShell] and [Pairing] over libadb-android, talking to this device's own adbd.
 * With [direct] null it finds the TLS port by mDNS (`_adb-tls-connect._tcp`, Wireless debugging);
 * otherwise it dials that host and port (for example after `adb tcpip 5555`).
 * An unknown key fails fast with [AdbFailure.NotAuthorized], unless [promptForApproval] is set: then adbd
 * shows its "Allow debugging?" prompt and [connect] waits up to [connectTimeoutMillis] for someone to accept.
 */
class LibAdb(
    context: Context,
    private val direct: Pair<String, Int>? = null,
    private val timeoutMillis: Long = 15 * 60 * 1000L,
    private val connectTimeoutMillis: Long = 10_000L,
    promptForApproval: Boolean = false,
) : AdbShell, Pairing {
    private val app = context.applicationContext
    private val keys by lazy { AdbKeys(app.noBackupFilesDir.resolve("adb")) }
    private val connecting = Mutex()

    private val manager = object : AbsAdbConnectionManager() {
        override fun getPrivateKey() = keys.privateKey
        override fun getCertificate(): Certificate = keys.certificate
        override fun getDeviceName() = "speed-compiler"
    }.apply {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        setThrowOnUnauthorised(!promptForApproval)
    }

    /** Connects if not already connected. Throws [AdbFailure] for the failures the UI can explain. */
    suspend fun connect() = connecting.withLock {
        if (manager.isConnected) return@withLock
        var ok = false
        try {
            ok = adbCall(::dial)
        } finally {
            if (!ok) runCatching { manager.disconnect() } // drop any half-open session
        }
        if (!ok) throw AdbFailure.Timeout()
    }

    private fun dial(): Boolean = try {
        if (direct == null) manager.connectTls(app, connectTimeoutMillis) else manager.connect(direct.first, direct.second)
    } catch (e: InterruptedException) {
        // libadb's way of saying no _adb-tls-connect service showed up in time
        if (direct == null && e.message?.startsWith("Timed out") == true) throw AdbFailure.DebuggingOff(e) else throw e
    } catch (e: IOException) {
        if (direct == null && e.message?.startsWith("Could not find") == true) throw AdbFailure.DebuggingOff(e) else throw e
    }

    override suspend fun run(command: String): ShellResult {
        connect()
        // withTimeoutOrNull only reacts to its own deadline, so a caller's withTimeout still propagates as usual.
        val raw = withTimeoutOrNull(timeoutMillis) {
            adbCall {
                try {
                    manager.openStream("shell:$command; echo __rc=\$?").use { it.readAll() }
                } catch (e: IOException) {
                    runCatching { manager.disconnect() } // dead socket: next run() reconnects
                    throw e
                }
            }
        }
        if (raw == null) {
            // the stream may be wedged; the next command reconnects. Off the caller thread: the close writes to the socket.
            withContext(NonCancellable + Dispatchers.IO) { runCatching { manager.disconnect() } }
            throw AdbFailure.Timeout()
        }
        return parseShellOutput(raw.toString(Charsets.UTF_8))
    }

    override suspend fun pair(host: String, port: Int, code: String): Boolean = try {
        adbCall { manager.pair(host, port, code) }
    } catch (e: AdbFailure) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false // wrong or expired code: libadb reports it as a failed message exchange
    }

    suspend fun disconnect() = adbCall { manager.disconnect() }

    /**
     * Reads until adbd closes the stream or our closing `__rc=` line has arrived with nothing queued after it.
     * libadb 3.1.1 never wakes a reader when the close lands while data is still queued (it only sets a
     * private pending-close flag), so waiting for the close alone can hang.
     * shortcut: a command whose own output pauses right after a `__rc=N` line can end the read early; a per-call
     * nonce in the marker would rule that out if it ever matters.
     */
    private fun AdbStream.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var tail = ""
        while (true) {
            val n = try {
                read(buf, 0, buf.size)
            } catch (e: IOException) {
                if (isClosed) break else throw e // a closed AdbStream throws instead of returning -1
            }
            if (n < 0) break
            out.write(buf, 0, n)
            tail = (tail + String(buf, 0, n, Charsets.UTF_8)).takeLast(32)
            if (RC_END.containsMatchIn(tail) && runCatching { available() }.getOrDefault(0) == 0) break
        }
        return out.toByteArray()
    }

    private suspend fun <T> adbCall(block: () -> T): T = try {
        runInterruptible(Dispatchers.IO) {
            try {
                block()
            } catch (e: InterruptedException) {
                // libadb's own deadlines throw a messaged InterruptedException; a bare one is our cancellation
                if (e.message?.startsWith("Timed out") == true) throw AdbFailure.Timeout(e) else throw e
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw mapAdbError(e)
    }
}
