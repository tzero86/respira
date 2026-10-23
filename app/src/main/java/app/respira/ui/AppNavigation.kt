package app.respira.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot navigation requests from outside the composition (share intents).
 * The shell consumes the value after navigating.
 */
object AppNavigation {
    private val _request = MutableStateFlow<String?>(null)
    val request: StateFlow<String?> = _request

    fun open(route: String) {
        _request.value = route
    }

    fun consume() {
        _request.value = null
    }
}
