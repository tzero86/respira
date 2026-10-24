package app.respira.feature.sleep

import android.content.Context
import app.respira.audio.Audio
import app.respira.feature.meditation.resolvePreferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bedtime routine: a short breathing session that hands over to the
 * preferred track once the breathing is done.
 */
internal object SleepRoutine {
    /** Pattern the routine breathes with. */
    const val PatternId = "478"

    /** Length of the routine session, in minutes. */
    const val Minutes = 3

    private val _running = MutableStateFlow(false)
    private val _requests = MutableStateFlow(0)
    private val _trackStarted = MutableStateFlow(false)

    /** True while the routine's breathing session is running. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Pending routine requests; the Breathe tab watches it to start the routine. */
    val requests: StateFlow<Int> = _requests.asStateFlow()

    /** True once the routine handed over to the preferred track. */
    val trackStarted: StateFlow<Boolean> = _trackStarted.asStateFlow()

    private var pendingRequests = 0

    @Volatile
    private var sessionPatternId: String? = null

    @Volatile
    private var sessionMinutes: Int? = null

    /** Pattern of the routine in progress, or null when no routine runs. */
    val patternId: String? get() = sessionPatternId

    /** Length of the routine in progress in minutes, or null when no routine runs. */
    val minutes: Int? get() = sessionMinutes

    /** Asks the Breathe tab to start the bedtime routine. */
    @Synchronized
    fun request() {
        pendingRequests++
        _requests.value = pendingRequests
    }

    /** Consumes a pending request; true once per request. */
    @Synchronized
    fun consumeRequest(): Boolean {
        if (pendingRequests == 0) return false
        pendingRequests--
        _requests.value = pendingRequests
        return true
    }

    /** Starts the routine: [patternId] for [minutes], then the preferred track. */
    @Synchronized
    fun start(patternId: String, minutes: Int) {
        sessionPatternId = patternId
        sessionMinutes = minutes
        _trackStarted.value = false
        _running.value = true
    }

    /** Ends the routine without starting a track. */
    @Synchronized
    fun cancel() {
        sessionPatternId = null
        sessionMinutes = null
        _trackStarted.value = false
        _running.value = false
    }

    /** Called by the Breathe screen when the breathing session completes. */
    @Synchronized
    fun onSessionComplete(context: Context) {
        val pick = resolvePreferred(context)
        cancel()
        if (pick == null) return
        Audio.hub.playTrack(pick.meditation.track)
        _trackStarted.value = true
    }
}
