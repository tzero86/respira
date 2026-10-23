package app.respira.feature.breathe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.floor

internal enum class BreathStatus { Idle, Running, Paused, Complete }

internal sealed interface BreathTick {
    data object None : BreathTick
    data object Complete : BreathTick
    data class PhaseChanged(val phase: BreathPhase) : BreathTick
}

internal class BreathEngine(pattern: BreathPattern, minutes: Int?) {

    var pattern by mutableStateOf(pattern)
        private set

    var sessionMinutes by mutableStateOf(minutes)
        private set

    var status by mutableStateOf(BreathStatus.Idle)
        private set

    var runId by mutableIntStateOf(0)
        private set

    var elapsedSeconds by mutableDoubleStateOf(0.0)
        private set

    var phase by mutableStateOf(BreathPhase.Inhale)
        private set

    var phaseProgress by mutableFloatStateOf(0f)
        private set

    var phaseElapsedSeconds by mutableFloatStateOf(0f)
        private set

    var phaseRemainingSeconds by mutableFloatStateOf(pattern.inhale)
        private set

    private var sliceIndex = 0
    private var cycleIndex = 0
    private var lastSerial = 0

    val sessionSeconds: Double?
        get() = sessionMinutes?.let { it * 60.0 }

    fun selectPattern(next: BreathPattern) {
        if (next.id == pattern.id) return
        pattern = next
        restartIdle()
    }

    fun selectSession(minutes: Int?) {
        if (minutes == sessionMinutes) return
        sessionMinutes = minutes
        restartIdle()
    }

    fun begin() {
        runId++
        resetClock()
        status = BreathStatus.Running
    }

    fun pause() {
        if (status != BreathStatus.Running) return
        runId++
        status = BreathStatus.Paused
    }

    fun resume() {
        if (status != BreathStatus.Paused) return
        runId++
        status = BreathStatus.Running
    }

    fun stop() {
        restartIdle()
    }

    private fun restartIdle() {
        runId++
        status = BreathStatus.Idle
        resetClock()
    }

    private fun resetClock() {
        elapsedSeconds = 0.0
        lastSerial = 0
        applyFrame(0.0)
    }

    fun update(timeSeconds: Double): BreathTick {
        if (status != BreathStatus.Running) return BreathTick.None

        val limit = sessionSeconds
        if (limit != null && timeSeconds >= limit) {
            elapsedSeconds = limit
            applyFrame(limit)
            status = BreathStatus.Complete
            return BreathTick.Complete
        }

        elapsedSeconds = timeSeconds.coerceAtLeast(0.0)
        applyFrame(elapsedSeconds)

        val serial = cycleIndex * pattern.slices.size + sliceIndex
        if (serial == lastSerial) return BreathTick.None
        lastSerial = serial
        return BreathTick.PhaseChanged(phase)
    }

    private fun applyFrame(time: Double) {
        val slices = pattern.slices
        if (slices.isEmpty()) return

        val cycle = pattern.cycleSeconds.toDouble().coerceAtLeast(0.001)
        var completedCycles = floor(time / cycle).toInt()
        var within = time - completedCycles * cycle
        if (within >= cycle) {
            within -= cycle
            completedCycles++
        }

        var start = 0.0
        var index = slices.lastIndex
        for (i in slices.indices) {
            val end = start + slices[i].duration
            if (within < end) {
                index = i
                break
            }
            start = end
        }

        val duration = slices[index].duration
        val local = (within - start).coerceIn(0.0, duration.toDouble())

        cycleIndex = completedCycles
        sliceIndex = index
        phase = slices[index].phase
        phaseProgress = if (duration > 0f) (local / duration).toFloat().coerceIn(0f, 1f) else 0f
        phaseElapsedSeconds = local.toFloat()
        phaseRemainingSeconds = (duration - local).toFloat().coerceAtLeast(0f)
    }
}
