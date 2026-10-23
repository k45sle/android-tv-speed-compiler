package dev.speedcompiler.tv.engine

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val GATE_REFRESH_MILLIS = 30_000L

/** Device reads the gate needs. [wakefulness] is null when the screen state is unknown. */
interface GateSignals {
    suspend fun musicActive(): Boolean
    suspend fun wakefulness(): Wakefulness?
}

data class GateConfig(val mode: GateMode, val window: GateWindow, val paused: Boolean)

fun interface GateConfigProvider {
    suspend fun config(): GateConfig
}

/** Automatic gate: manual "Optimize now" never reaches here, the scheduler runs it directly. */
class GateImpl(
    private val clock: Clock,
    private val signals: GateSignals,
    private val configs: GateConfigProvider,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Gate {
    override suspend fun status(ignorePause: Boolean): GateStatus {
        val config = configs.config()
        val now = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone)
        val decision = decide(
            now = now,
            musicActive = signals.musicActive(),
            wakefulness = signals.wakefulness(),
            mode = config.mode,
            window = config.window,
            paused = config.paused && !ignorePause,
        )
        return when (decision) {
            GateDecision.Run -> GateStatus.Open
            is GateDecision.Wait -> when (val reason = decision.reason) {
                WaitReason.Playback -> GateStatus.WaitingForPlayback
                WaitReason.NotIdle -> GateStatus.WaitingForIdle
                WaitReason.Paused -> GateStatus.Paused
                is WaitReason.OutsideWindow -> GateStatus.OpensAt(reason.opensAt)
            }
        }
    }
}

/** Last gate status for display. [refreshIfStale] reloads in [scope] at most every 30 s (unless [refreshIfStale] is forced) and never waits; [status] is null until a load succeeds, when the last one failed, or when [load] returns null (TV unreachable). */
class GateCache(private val clock: Clock, private val scope: CoroutineScope, private val load: suspend () -> GateStatus?) {
    private val _status = MutableStateFlow<GateStatus?>(null)
    val status: StateFlow<GateStatus?> = _status
    private var startedAt = -GATE_REFRESH_MILLIS
    private var job: Job? = null

    @Synchronized
    fun refreshIfStale(force: Boolean = false) {
        val now = clock.nowMillis()
        if (force) job?.cancel()
        else if (job?.isActive == true || now - startedAt < GATE_REFRESH_MILLIS) return
        startedAt = now
        job = scope.launch {
            _status.value = try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
    }
}
