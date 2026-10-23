package app.respira.feature.meditation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bumped whenever the meditation library changes outside its screen (link
 * download, shared audio) so the list reloads without a lifecycle round-trip.
 */
object LibraryEvents {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun bump() {
        _version.value += 1
    }
}
