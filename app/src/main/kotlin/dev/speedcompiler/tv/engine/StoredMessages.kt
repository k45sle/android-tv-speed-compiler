package dev.speedcompiler.tv.engine

// Every English message the engine writes into Room. storedText() maps them to string resources for display,
// so rows already stored must keep matching: change a value here only together with a migration.
const val DID_NOT_RESET = "Didn't reset: "
const val DID_NOT_FINISH = "Didn't finish: "
const val NOT_PAIRED_TAIL = "the TV no longer trusts this app. Choose Fix connection in Settings."
const val NOT_ALLOWED_TAIL = "the TV has not allowed this app. Accept the debugging prompt on the TV."
const val LOST_TAIL = "lost the connection to the TV."
const val INVALID_APP_TAIL = "that is not a valid app name."
const val REFUSED_RESET_TAIL = "the TV refused."
const val REFUSED_FINISH_TAIL = "the TV refused to optimize this app."
const val TOOK_TOO_LONG_RESET_TAIL = "the TV took too long."
const val RESET_DONE = "Reset done. The TV chooses how to run this app again."
const val OPTIMIZED_UNVERIFIED = "Optimized (this TV doesn't report the result)"
const val OPTIMIZED_CHECKED = "Optimized (checked)"
const val VERIFY_UNKNOWN = "The TV did not report this app's compiler filter."
const val NO_PROFILE_YET = "Waiting for the app's first launch."
const val NOT_INSTALLED = "Not installed"
const val DEBUG_BUILD = "Didn't finish: this is a debug build, and Android never compiles debug builds ahead of time."

fun tookMinutes(minutes: Long) = "${DID_NOT_FINISH}the TV took more than $minutes minutes."
fun unexpectedError(name: String?) = "${DID_NOT_FINISH}unexpected error ($name)."
fun notSpeed(filter: String) = "${DID_NOT_FINISH}${filter.removePrefix("not fully compiled: ")}, not speed."
fun withSaid(message: String, said: String?) = message + said?.let { " It said: $it" }.orEmpty()
