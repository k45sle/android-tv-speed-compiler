package dev.speedcompiler.tv.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Whether the TV kept "Always allow" for this app's key (ADB debugging mode only), as last checked.
 * Android 11 and 12 save the key by appending one line to /data/misc/adb/adb_keys, only log a failed write, and
 * don't write it again until the next boot; adbd trusts only that file, so such a TV asks again on every new
 * connection, for example after a full restart. Android 13 and later rewrite the whole file on each save.
 */
enum class KeyCheck {
    /** Not checked yet, or the check couldn't tell. */
    Unknown,
    /** A second connection with the same key got in without a prompt, so it will after a restart too. */
    Saved,
    /** Refused, and the TV has no record of Always allow: Allow was chosen without ticking it. */
    AllowedOnce,
    /** Refused although the TV recorded Always allow: it didn't save the key. */
    NotSaved,
    /** Was [Saved], then a quiet reconnect was refused: the TV dropped the key, for example at a restart. */
    Forgotten,
}

/** Where `dumpsys adb` lists a key: the file adbd trusts (user_keys) and the framework's own list (keystore). */
data class KeyPlaces(val inUserKeys: Boolean, val inKeystore: Boolean)

/**
 * Finds [keyBase64] in `dumpsys adb` output. The user_keys and keystore values are raw file contents and can span
 * lines (keystore is binary XML on Android 12), so each runs from its `name=` to the next field name or the end.
 * Null when the output has no debugging_manager section.
 */
fun parseDumpsysAdb(dump: String, keyBase64: String): KeyPlaces? {
    if (!dump.contains("debugging_manager")) return null
    fun section(name: String): String {
        val start = dump.indexOf("$name=")
        if (start < 0) return ""
        val from = start + name.length + 1
        val end = listOf("user_keys=", "system_keys=", "keystore=").map { dump.indexOf(it, from) }.filter { it >= 0 }.minOrNull() ?: dump.length
        return dump.substring(from, end)
    }
    return KeyPlaces(section("user_keys").contains(keyBase64), section("keystore").contains(keyBase64))
}

/** Explains a refused quiet connection by where the TV lists the key. */
fun classifyRefused(places: KeyPlaces?): KeyCheck = when {
    places == null -> KeyCheck.Unknown
    places.inUserKeys || places.inKeystore -> KeyCheck.NotSaved
    else -> KeyCheck.AllowedOnce
}

/**
 * Right after someone allowed the app: will the TV let the same key back in without asking, as it must after a
 * restart? [trusted] makes a second, quiet connection (true = let in, false = refused, null = couldn't tell). The TV
 * saves the key just after it lets the first connection in, so each try waits its entry in [waits] first.
 */
class KeyChecker(
    private val trusted: suspend () -> Boolean?,
    private val shell: AdbShell,
    private val keyBase64: () -> String,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val waits: List<Long> = listOf(1_500L, 3_000L),
) {
    suspend fun check(): KeyCheck {
        var last: Boolean? = null
        for (w in waits) {
            pause(w)
            last = trusted()
            if (last == true) return KeyCheck.Saved
        }
        if (last == null) return KeyCheck.Unknown
        val dump = try {
            shell.run("dumpsys adb").output
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return classifyRefused(dump?.let { parseDumpsysAdb(it, keyBase64()) })
    }
}
