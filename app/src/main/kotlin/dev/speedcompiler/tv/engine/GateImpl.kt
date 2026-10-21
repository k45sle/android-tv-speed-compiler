package dev.speedcompiler.tv.engine

import java.time.Instant
import java.time.ZoneId

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
            manual = false,
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
