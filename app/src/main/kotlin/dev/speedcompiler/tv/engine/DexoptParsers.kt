package dev.speedcompiler.tv.engine

private val PACKAGE_ID = Regex("[A-Za-z0-9_.]+")
// Whole line only: "used by other apps: [pkg]" inside another app's section is not a header.
private val HEADER = Regex("\\s*\\[([A-Za-z_][A-Za-z0-9_.]+)]\\s*")
private val FILTER = Regex("(?:compiler[-_ ]filter|filter|status)\\s*[=:]\\s*([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
private val RC = Regex("__rc=(\\d+)")

fun isValidPackageId(id: String) = PACKAGE_ID.matches(id)

/** The only compile command this app ever sends. Throws on an invalid id. */
fun compileCommand(pkg: String): String {
    require(isValidPackageId(pkg)) { "invalid package id" }
    return "cmd package compile -m speed -f $pkg"
}

/** Undoes [compileCommand]: the TV goes back to its own compile choice for the app. Throws on an invalid id. */
fun resetCommand(pkg: String): String {
    require(isValidPackageId(pkg)) { "invalid package id" }
    return "cmd package compile --reset $pkg"
}

fun artDumpCommand(pkg: String): String {
    require(isValidPackageId(pkg)) { "invalid package id" }
    return "pm art dump $pkg"
}

const val DEXOPT_DUMP_COMMAND = "dumpsys package dexopt"

/** The shell appends `__rc=` itself and strips it; fixtures may still carry it. Success needs rc 0 and the word Success. */
fun compileSucceeded(text: String): Boolean {
    val rc = RC.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    return (rc == null || rc == 0) && Regex("\\bSuccess\\b").containsMatchIn(text) &&
        !Regex("\\bFailure\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

/**
 * Reads the compiler filter of exactly [pkg] from `pm art dump` (Android 14+) or `dumpsys package dexopt`
 * (Android 11-13) output. Both list `[package.id]` headers, then `path:` lines each followed by `[status=...]` lines.
 * Returns "speed" only when every base and split entry, for every instruction set, reads speed; otherwise
 * "not fully compiled: <file> (<isa>) is <filter>" naming the first entry that does not. Null when the TV does not
 * report it. Secondary-dex and `[location is ...]` lines are skipped: `compile -m speed` does not touch them.
 */
fun parseCompilerFilter(text: String, pkg: String): String? {
    var inPackage = false
    val lines = mutableListOf<String>()
    for (line in text.lines()) {
        val header = HEADER.matchEntire(line)
        if (header != null) {
            if (inPackage && lines.isNotEmpty()) break
            inPackage = header.groupValues[1] == pkg
            continue
        }
        if (inPackage) lines += line
    }
    var file = "?"
    var notSpeed: String? = null
    var found = false
    for (raw in lines) {
        val line = raw.trim()
        if (line.startsWith("path:")) { file = line.substringAfter(":").trim().substringAfterLast('/'); continue }
        if (line.startsWith("known secondary dex files")) break // Android 13 lists them last in the section
        if (line.startsWith("[location is") || "secondary-dex" in line) continue
        val isStatus = "status=" in line.lowercase() || "filter=" in line.lowercase()
        val filter = FILTER.find(line)?.groupValues?.get(1)?.lowercase()
        if (isStatus && filter == null) return null
        if (filter == null) continue
        found = true
        if (filter != "speed" && notSpeed == null) notSpeed = "not fully compiled: $file (${line.substringBefore(':')}) is $filter"
    }
    return if (!found) null else notSpeed ?: "speed"
}
