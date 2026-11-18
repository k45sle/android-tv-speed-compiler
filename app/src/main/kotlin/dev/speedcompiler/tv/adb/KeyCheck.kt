package dev.speedcompiler.tv.adb

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
    /** Refused, and the TV lists the key as allowed but not in the file adbd trusts: it didn't save it. */
    NotSaved,
    /** Was [Saved], then the TV refused every quiet reconnect of a run (usually at a restart): it dropped the key. */
    Forgotten,
    /** Was [Forgotten], then the person allowed the app again and the TV saved it; the next restart tells whether it keeps it. */
    SavedAgain,
    /** The TV dropped a key it had saved again after an earlier drop: it forgets the app at every full restart. */
    ForgetsAtRestart,
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

/**
 * Explains a refused quiet connection by where the TV lists the key. A key in user_keys reached the file adbd trusts, so
 * the save did land, just after the probes and too late for this connection; only a key in the framework's keystore
 * alone (the Android 12 EACCES case, where the file write failed) means it didn't save.
 */
fun classifyRefused(places: KeyPlaces?): KeyCheck = when {
    places == null -> KeyCheck.Unknown
    places.inUserKeys -> KeyCheck.Saved
    places.inKeystore -> KeyCheck.NotSaved
    else -> KeyCheck.AllowedOnce
}

/**
 * The result to keep when the check right after someone allowed the app found [found]; [stored] is the result kept for
 * this key. The check can't see a restart, so it never clears what a restart showed: only a quiet handshake (stored as
 * [KeyCheck.Saved] directly) does.
 */
fun afterPromptedCheck(stored: KeyCheck, found: KeyCheck): KeyCheck = when {
    stored == KeyCheck.ForgetsAtRestart -> stored
    (stored == KeyCheck.Forgotten || stored == KeyCheck.SavedAgain) && found == KeyCheck.Saved -> KeyCheck.SavedAgain
    (stored == KeyCheck.Forgotten || stored == KeyCheck.SavedAgain) && found == KeyCheck.Unknown -> stored
    else -> found
}

/**
 * The result to keep after the TV refused every quiet reconnect of a run for this key; [stored] is the result kept and
 * [sameBoot] says it was stored in this boot. A key saved again and refused after a restart means the TV forgets it at
 * restarts; refused within the same boot it only shows the TV stopped accepting it (for example authorizations were
 * revoked).
 */
fun afterQuietRefusal(stored: KeyCheck, sameBoot: Boolean): KeyCheck = when (stored) {
    KeyCheck.Saved -> KeyCheck.Forgotten
    KeyCheck.SavedAgain -> if (sameBoot) KeyCheck.Forgotten else KeyCheck.ForgetsAtRestart
    KeyCheck.ForgetsAtRestart -> KeyCheck.ForgetsAtRestart
    else -> stored
}

/**
 * The result to keep after a quiet handshake (the TV let the key in by itself). That proves the TV kept the key across a
 * restart only when [stored] was stored in an earlier boot; within the same boot ([sameBoot]) a result about restarts
 * stands, for example after the app's process was restarted.
 * [KeyCheck.Forgotten] within the same boot is the exception: Forgotten in this boot means the TV refused the key and
 * nothing since showed a restart, so when the TV now lets the key in by itself, it still has the key: it was saved
 * again after the drop (or the refusal was a slow boot), so the result becomes [KeyCheck.SavedAgain]. That shows no
 * warning on Home, and the next restart still tells whether the TV keeps the key.
 */
fun afterQuietHandshake(stored: KeyCheck, sameBoot: Boolean): KeyCheck = when {
    sameBoot && stored == KeyCheck.Forgotten -> KeyCheck.SavedAgain
    sameBoot && (stored == KeyCheck.SavedAgain || stored == KeyCheck.ForgetsAtRestart) -> stored
    else -> KeyCheck.Saved
}

/**
 * Right after someone allowed the app: will the TV let the same key back in without asking, as it must after a
 * restart? [trusted] makes a second, quiet connection (true = let in, false = refused, null = couldn't tell). The TV
 * saves the key just after it lets the first connection in, so each try waits its entry in [waits] first.
 * One refusal in any try is enough to explain where the TV lists the key; only when every try got no answer at all
 * (all null) is the result [KeyCheck.Unknown].
 */
class KeyChecker(
    private val trusted: suspend () -> Boolean?,
    private val shell: AdbShell,
    private val keyBase64: () -> String,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val waits: List<Long> = listOf(1_500L, 3_000L),
) {
    suspend fun check(): KeyCheck {
        var refused = false
        for (w in waits) {
            pause(w)
            val r = trusted()
            if (r == true) return KeyCheck.Saved
            if (r == false) refused = true
        }
        // Nothing was refused (every try failed to answer, so all were null): there is nothing to explain.
        if (!refused) return KeyCheck.Unknown
        val dump = tryOrNull { shell.run("dumpsys adb").output }
        return classifyRefused(dump?.let { parseDumpsysAdb(it, keyBase64()) })
    }
}
