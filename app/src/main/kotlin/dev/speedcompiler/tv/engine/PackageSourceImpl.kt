package dev.speedcompiler.tv.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import dev.speedcompiler.tv.watch.SyncState
import dev.speedcompiler.tv.watch.SyncStateDao
import dev.speedcompiler.tv.watch.WatchedApp

/** Fingerprint of one installed app: versionCode (long) plus lastUpdateTime, so a same-version reinstall differs. */
fun fingerprintOf(versionCode: Long, lastUpdateTime: Long) = "$versionCode:$lastUpdateTime"

/** Packages touched since [sequence]; [names] includes removed packages. */
data class PackageChanges(val sequence: Int, val names: List<String>)

/** The only place that touches PackageManager and Settings.Global, so tests can fake it. */
interface PackageSystem {
    fun bootCount(): Int
    fun installed(): List<InstalledPackage>

    /** Fingerprint of [packageName], or null when it is not installed. */
    fun fingerprint(packageName: String): String?

    /** True for a debug build: ART never compiles those past verify. False when not installed. */
    fun isDebuggable(packageName: String): Boolean

    /** Null when nothing changed since [sequence]. */
    fun changedSince(sequence: Int): PackageChanges?
}

class AndroidPackageSystem(private val context: Context) : PackageSystem {
    private val pm: PackageManager get() = context.packageManager

    override fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    override fun installed() = pm.getInstalledPackages(0).map {
        InstalledPackage(it.packageName, fingerprintOf(it.longVersionCode, it.lastUpdateTime))
    }

    override fun fingerprint(packageName: String) = try {
        pm.getPackageInfo(packageName, 0).let { fingerprintOf(it.longVersionCode, it.lastUpdateTime) }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    override fun isDebuggable(packageName: String) = try {
        pm.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override fun changedSince(sequence: Int) =
        pm.getChangedPackages(sequence)?.let { PackageChanges(it.sequenceNumber, it.packageNames) }
}

private const val KEY_BOOT_COUNT = "pkg_boot_count"
private const val KEY_SEQUENCE = "pkg_sequence"

/**
 * Reports watched apps that changed or were uninstalled since the last call. The stored position moves only after
 * [onDetected] returns, so a crash while queueing them is picked up again by the next call.
 * The sequence number resets each boot, so a different boot count (or no stored state) forces a full compare.
 */
class ChangeDetector(
    private val system: PackageSystem,
    private val state: SyncStateDao,
    private val watched: suspend () -> List<WatchedApp>,
) {
    suspend fun detect(onDetected: suspend (List<String>) -> Unit = {}): List<String> {
        val apps = watched()
        val boot = system.bootCount()
        val storedBoot = state.get(KEY_BOOT_COUNT)?.toIntOrNull()
        val storedSeq = state.get(KEY_SEQUENCE)?.toIntOrNull()
        val incrementalFrom = storedSeq.takeIf { boot >= 0 && storedBoot == boot } // unknown boot count: always full compare
        val changes = system.changedSince(incrementalFrom ?: 0)
        val candidates = if (incrementalFrom != null) {
            val touched = changes?.names?.toSet().orEmpty()
            apps.filter { it.packageName in touched }
        } else {
            apps
        }
        val result = candidates
            .filter { system.fingerprint(it.packageName) != it.fingerprint }
            .map { it.packageName }
        onDetected(result)
        state.put(SyncState(KEY_BOOT_COUNT, boot.toString()))
        state.put(SyncState(KEY_SEQUENCE, (changes?.sequence ?: incrementalFrom ?: 0).toString()))
        return result
    }
}
