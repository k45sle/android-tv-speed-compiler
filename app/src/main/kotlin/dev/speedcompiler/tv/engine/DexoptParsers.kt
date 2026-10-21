package dev.speedcompiler.tv.engine

private val PACKAGE_ID = Regex("[A-Za-z0-9_.]+")
private val HEADER = Regex("\\[([A-Za-z_][A-Za-z0-9_.]+)]")
private val FILTER = Regex("(?:compiler[-_ ]filter|filter|status)\\s*[=:]\\s*([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
private val RC = Regex("__rc=(\\d+)")

fun isValidPackageId(id: String) = PACKAGE_ID.matches(id)

/** The only compile command this app ever sends. Throws on an invalid id. */
fun compileCommand(pkg: String): String {
    require(isValidPackageId(pkg)) { "invalid package id" }
    return "cmd package compile -m speed -f $pkg"
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
 * (Android 11-13) output. Both list `[package.id]` headers followed by `[status=...]` lines.
 * Returns null when the TV does not report it; "mixed" when entries disagree.
 */
fun parseCompilerFilter(text: String, pkg: String): String? {
    var inPackage = false
    val lines = mutableListOf<String>()
    for (line in text.lines()) {
        val header = HEADER.find(line)
        if (header != null) {
            if (inPackage && lines.isNotEmpty()) break
            inPackage = header.groupValues[1] == pkg
            continue
        }
        if (inPackage) lines += line
    }
    val filters = lines.mapNotNull { FILTER.find(it)?.groupValues?.get(1)?.lowercase() }
    val expected = lines.count { "status=" in it.lowercase() || "filter=" in it.lowercase() }
    if (filters.isEmpty() || filters.size != expected) return null
    return filters.distinct().singleOrNull() ?: "mixed"
}
