package app.respira.feature.breathe

import androidx.annotation.StringRes
import app.respira.R

internal enum class BreathPhase {
    Inhale,
    HoldIn,
    Exhale,
    HoldOut,
}

@StringRes
internal fun BreathPhase.labelRes(): Int = when (this) {
    BreathPhase.Inhale -> R.string.breathe_phase_inhale
    BreathPhase.HoldIn -> R.string.breathe_phase_hold_in
    BreathPhase.Exhale -> R.string.breathe_phase_exhale
    BreathPhase.HoldOut -> R.string.breathe_phase_hold_out
}

internal data class PhaseSlice(val phase: BreathPhase, val duration: Float)

internal data class BreathPattern(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val hintRes: Int,
    val inhale: Float,
    val holdIn: Float,
    val exhale: Float,
    val holdOut: Float,
) {
    val slices: List<PhaseSlice> = buildList {
        if (inhale > 0f) add(PhaseSlice(BreathPhase.Inhale, inhale))
        if (holdIn > 0f) add(PhaseSlice(BreathPhase.HoldIn, holdIn))
        if (exhale > 0f) add(PhaseSlice(BreathPhase.Exhale, exhale))
        if (holdOut > 0f) add(PhaseSlice(BreathPhase.HoldOut, holdOut))
    }

    val cycleSeconds: Float = slices.fold(0f) { total, slice -> total + slice.duration }
}

internal val BreathPatterns: List<BreathPattern> = listOf(
    BreathPattern(
        id = "calm",
        nameRes = R.string.breathe_pattern_calm,
        hintRes = R.string.breathe_pattern_calm_hint,
        inhale = 4f,
        holdIn = 0f,
        exhale = 6f,
        holdOut = 0f,
    ),
    BreathPattern(
        id = "box",
        nameRes = R.string.breathe_pattern_box,
        hintRes = R.string.breathe_pattern_box_hint,
        inhale = 4f,
        holdIn = 4f,
        exhale = 4f,
        holdOut = 4f,
    ),
    BreathPattern(
        id = "478",
        nameRes = R.string.breathe_pattern_478,
        hintRes = R.string.breathe_pattern_478_hint,
        inhale = 4f,
        holdIn = 7f,
        exhale = 8f,
        holdOut = 0f,
    ),
    BreathPattern(
        id = "coherent",
        nameRes = R.string.breathe_pattern_coherent,
        hintRes = R.string.breathe_pattern_coherent_hint,
        inhale = 5.5f,
        holdIn = 0f,
        exhale = 5.5f,
        holdOut = 0f,
    ),
)

internal data class SessionLength(val minutes: Int?)

internal val SessionLengths: List<SessionLength> = listOf(
    SessionLength(1),
    SessionLength(3),
    SessionLength(5),
    SessionLength(10),
    SessionLength(null),
)
