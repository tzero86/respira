package app.respira.feature.meditation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DownloadFailure { UNAVAILABLE, EXPIRED, GENERIC, TOO_LARGE }

internal sealed interface DownloadState {
    data object Idle : DownloadState
    data class Active(val title: String, val downloaded: Long, val total: Long?) : DownloadState
    data class Done(val title: String) : DownloadState
    data class Failed(val title: String, val failure: DownloadFailure) : DownloadState
}

internal object Downloads {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    @Volatile
    private var generation: Int = 0
    private var source: AudioSource? = null
    private var title: String = ""

    fun start(context: Context, source: AudioSource, title: String) {
        if (_state.value is DownloadState.Active) return
        this.source = source
        this.title = title
        dispatch(context)
    }

    fun retry(context: Context) {
        if (_state.value !is DownloadState.Failed) return
        dispatch(context)
    }

    fun cancel(context: Context) {
        val current = _state.value as? DownloadState.Active ?: return
        val token = generation
        generation++
        _state.value = DownloadState.Idle
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .putExtra(DownloadService.EXTRA_TOKEN, token)
                    .putExtra(DownloadService.EXTRA_TITLE, current.title),
            )
        }
    }

    fun dismissFailure() {
        if (_state.value is DownloadState.Failed) _state.value = DownloadState.Idle
    }

    fun progress(token: Int, title: String, downloaded: Long, total: Long?) {
        if (token != generation) return
        val current = _state.value as? DownloadState.Active
        if (current == null) {
            _state.value = DownloadState.Active(title, downloaded, total)
            return
        }
        if (current.downloaded == downloaded && current.total == total) return
        _state.value = current.copy(downloaded = downloaded, total = total)
    }

    fun complete(token: Int, title: String) {
        if (token != generation) return
        _state.value = DownloadState.Done(title)
    }

    fun fail(token: Int, title: String, failure: DownloadFailure) {
        if (token != generation) return
        _state.value = DownloadState.Failed(title, failure)
    }

    fun abandon(token: Int) {
        if (token != generation) return
        if (_state.value is DownloadState.Active) _state.value = DownloadState.Idle
    }

    private fun dispatch(context: Context) {
        val source = source ?: return
        generation++
        _state.value = DownloadState.Active(title, 0L, null)
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_TOKEN, generation)
            .putExtra(DownloadService.EXTRA_TITLE, title)
        when (source) {
            is AudioSource.YouTube -> intent.putExtra(DownloadService.EXTRA_VIDEO_ID, source.videoId)
            is AudioSource.Direct -> intent.putExtra(DownloadService.EXTRA_URL, source.url)
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { _state.value = DownloadState.Failed(title, DownloadFailure.GENERIC) }
    }
}
