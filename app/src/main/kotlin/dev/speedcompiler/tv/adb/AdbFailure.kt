package dev.speedcompiler.tv.adb

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** Typed connection failures the UI can explain. Anything else stays a plain IOException. */
sealed class AdbFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** The TV no longer trusts this app's key: pair again. */
    class NotPaired(cause: Throwable? = null) : AdbFailure("not paired", cause)

    /**
     * Nothing is listening: Wireless debugging is off, or with [legacy] (the older ADB connection on port 5555)
     * ADB debugging is off or the TV doesn't open that port.
     */
    class DebuggingOff(cause: Throwable? = null, val legacy: Boolean = false) : AdbFailure("debugging is off", cause)

    /** The TV rejected this app's key, or is waiting for someone to allow it. */
    class NotAuthorized(cause: Throwable? = null) : AdbFailure("not authorized", cause)

    class Timeout(cause: Throwable? = null) : AdbFailure("timed out", cause)
}

/** Maps libadb-android and socket errors onto [AdbFailure]; unknown errors pass through unchanged. */
fun mapAdbError(e: Throwable): Throwable = when (e) {
    is AdbFailure -> e
    is AdbPairingRequiredException -> AdbFailure.NotPaired(e)
    is AdbAuthenticationFailedException -> AdbFailure.NotAuthorized(e)
    is SocketTimeoutException -> AdbFailure.Timeout(e)
    // libadb also throws ConnectException when an open adb session refuses a stream; that is not "debugging off"
    is ConnectException -> if (e.message?.startsWith("Stream open") == true) e else AdbFailure.DebuggingOff(e)
    is NoRouteToHostException -> AdbFailure.DebuggingOff(e)
    else -> e
}
