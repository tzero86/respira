package app.respira.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Playback engine for the single meditation track and the mixable ambience
 * layers. Every mutation runs on the main thread; the UI observes [state].
 */
@OptIn(UnstableApi::class)
internal class DefaultAudioHub(private val context: Context) : AudioHub {

    private val _state = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var trackPlayer: ExoPlayer? = null
    private val voices = LinkedHashMap<String, LayerVoice>()
    private val retiringVoices = mutableListOf<LayerVoice>()

    private var focusHeld = false
    private var duckGain = 1f
    private var timerGain = 1f
    private var fadeGain = 1f
    private var autoSleepGain = 1f
    private var focusPausedTrack = false
    private var focusPausedLayers = false
    private var focusLossPaused = false
    private var timerJob: Job? = null
    private var fadeInJob: Job? = null
    private var autoSleepJob: Job? = null
    private var autoSleepEnabled = true
    private var autoSleepFadeStartMs = -1L
    private var lastRememberedAtMs = 0L
    private var serviceStartPending = false
    private var serviceStopScheduled = false
    private var serviceStopIssued = false
    private var stopRecheckAttempts = 0

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onMain { handleFocusChange(change) }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(PLATFORM_AUDIO_ATTRIBUTES)
        .setOnAudioFocusChangeListener(focusListener, mainHandler)
        .setWillPauseWhenDucked(false)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onMain { pauseAllForNoisy() }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val player = trackPlayer ?: return
            if (_state.value.track == null) return
            val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            _state.update {
                it.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = duration ?: it.durationMs,
                )
            }
            if (player.playWhenReady) {
                rememberPositionPeriodically(player.currentPosition)
                mainHandler.postDelayed(this, POSITION_INTERVAL_MS)
            }
        }
    }

    private val trackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateTrackFlags()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            updateTrackFlags()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                if (_state.value.track != null) stopTrackInternal()
            } else {
                updateTrackFlags()
                updateDuration()
                armAutoSleepIfNeeded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            stopTicker()
            trackPlayer?.let { rememberStoppedPosition(it) }
            updateState { it.copy(trackPlaying = false) }
            syncAudibility()
        }
    }

    private val stopServiceRunnable = object : Runnable {
        override fun run() {
            if (_state.value.isAudioActive()) {
                serviceStopScheduled = false
                stopRecheckAttempts = 0
                return
            }
            if (AudioService.isRunning) {
                serviceStopScheduled = false
                stopRecheckAttempts = 0
                serviceStartPending = false
                stopServiceNow()
                return
            }
            if (serviceStartPending) {
                if (stopRecheckAttempts < STOP_RECHECK_LIMIT) {
                    stopRecheckAttempts++
                    mainHandler.postDelayed(this, STOP_RECHECK_MS)
                } else {
                    serviceStopScheduled = false
                    stopRecheckAttempts = 0
                }
                return
            }
            serviceStopScheduled = false
            stopRecheckAttempts = 0
            stopServiceNow()
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun playTrack(track: AudioTrack) = onMain {
        val player = player()
        val current = _state.value.track
        if (current?.id == track.id &&
            player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE
        ) {
            player.playWhenReady = true
            updateState { it.copy(trackPlaying = true) }
            startTicker()
        } else {
            startFadeIn()
            lastRememberedAtMs = 0L
            cancelAutoSleep()
            player.setMediaItem(track.toMediaItem())
            player.prepare()
            player.playWhenReady = true
            updateState {
                it.copy(
                    track = track,
                    trackPlaying = true,
                    positionMs = 0L,
                    durationMs = track.durationMs.coerceAtLeast(0L),
                )
            }
            startTicker()
        }
        focusPausedTrack = false
        focusLossPaused = focusPausedLayers
        applyTrackVolume()
        syncAudibility()
    }

    override fun playTrackFrom(track: AudioTrack, positionMs: Long) = onMain {
        val player = player()
        val known = track.durationMs.takeIf { it > 0 }
            ?: _state.value.durationMs.takeIf { _state.value.track?.id == track.id && it > 0 }
            ?: 0L
        // Imported tracks report no duration up front, so trust the player to
        // clamp instead of throwing the position away.
        val target = if (known > 0L) positionMs.coerceIn(0L, known) else positionMs.coerceAtLeast(0L)
        startFadeIn()
        lastRememberedAtMs = 0L
        cancelAutoSleep()
        player.setMediaItem(track.toMediaItem())
        player.prepare()
        if (target > 0L) player.seekTo(target)
        player.playWhenReady = true
        updateState {
            it.copy(
                track = track,
                trackPlaying = true,
                positionMs = target,
                durationMs = known,
            )
        }
        startTicker()
        focusPausedTrack = false
        focusLossPaused = focusPausedLayers
        applyTrackVolume()
        syncAudibility()
    }

    override fun toggleTrack() = onMain {
        val current = _state.value
        if (current.track == null) return@onMain
        if (current.trackPlaying) pauseTrack() else resumeTrack()
    }

    override fun pauseTrack() = onMain {
        if (_state.value.track == null) return@onMain
        clearFocusResume()
        pauseTrackInternal()
        syncAudibility()
    }

    override fun resumeTrack() = onMain {
        if (_state.value.track == null) return@onMain
        focusPausedTrack = false
        focusLossPaused = focusPausedLayers
        val player = trackPlayer ?: return@onMain
        if (player.currentMediaItem == null) {
            _state.value.track?.let { player.setMediaItem(it.toMediaItem()) }
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        updateState { it.copy(trackPlaying = true) }
        startTicker()
        armAutoSleepIfNeeded()
        syncAudibility()
    }

    override fun seekTo(positionMs: Long) = onMain {
        val player = trackPlayer ?: return@onMain
        val current = _state.value
        if (current.track == null) return@onMain
        val upperBound = current.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, upperBound)
        player.seekTo(target)
        updateState { it.copy(positionMs = target) }
        armAutoSleepIfNeeded()
    }

    override fun stopTrack() = onMain { stopTrackInternal() }

    override fun setLayerVolume(layerId: String, volume: Float) = onMain {
        if (AmbienceCatalog.layer(layerId) == null) return@onMain
        val target = volume.coerceIn(0f, 1f)
        val current = _state.value
        if (target <= SILENT_THRESHOLD) {
            if (!current.layers.containsKey(layerId)) return@onMain
            removeVoice(layerId, fade = true)
            val layers = current.layers - layerId
            updateState {
                it.copy(layers = layers, layersPlaying = it.layersPlaying && layers.isNotEmpty())
            }
        } else {
            val wasEmpty = current.layers.isEmpty()
            val layers = current.layers + (layerId to target)
            updateState {
                it.copy(layers = layers, layersPlaying = if (wasEmpty) true else it.layersPlaying)
            }
            val audible = _state.value.layersPlaying
            val voice = ensureVoice(layerId, audible)
            if (voice != null) {
                if (audible) {
                    startVoice(voice)
                    fadeVoice(voice, layerGain(target))
                } else {
                    pauseVoice(voice)
                    setVoiceGain(voice, 0f)
                }
            }
        }
        syncAudibility()
    }

    override fun setLayers(levels: Map<String, Float>) = onMain {
        val cleaned = levels
            .mapValues { (_, volume) -> volume.coerceIn(0f, 1f) }
            .filter { (_, volume) -> volume > SILENT_THRESHOLD }
        if (cleaned.isEmpty()) {
            clearLayersInternal()
            return@onMain
        }
        val current = _state.value
        val wasEmpty = current.layers.isEmpty()
        updateState {
            it.copy(layers = cleaned, layersPlaying = if (wasEmpty) true else it.layersPlaying)
        }
        val audible = _state.value.layersPlaying
        (current.layers.keys - cleaned.keys).forEach { removeVoice(it, fade = true) }
        cleaned.forEach { (id, target) ->
            val voice = ensureVoice(id, audible) ?: return@forEach
            if (audible) {
                startVoice(voice)
                fadeVoice(voice, layerGain(target))
            } else {
                pauseVoice(voice)
                setVoiceGain(voice, 0f)
            }
        }
        syncAudibility()
    }

    override fun clearLayers() = onMain { clearLayersInternal() }

    override fun applyPreset(presetId: String) = onMain {
        val preset = AmbienceCatalog.preset(presetId) ?: return@onMain
        setLayers(preset.levels)
        if (!_state.value.layersPlaying) setLayersPlaying(true)
    }

    override fun setLayersPlaying(playing: Boolean) = onMain {
        val current = _state.value
        if (current.layers.isEmpty() || current.layersPlaying == playing) return@onMain
        focusPausedLayers = false
        focusLossPaused = focusPausedTrack
        updateState { it.copy(layersPlaying = playing) }
        val active = _state.value
        active.layers.forEach { (id, target) ->
            val voice = ensureVoice(id, playing) ?: return@forEach
            if (playing) {
                startVoice(voice)
                fadeVoice(voice, layerGain(target))
            } else {
                fadeVoice(voice, 0f) { pauseVoice(voice) }
            }
        }
        syncAudibility()
    }

    override fun toggleLayers() = onMain {
        val current = _state.value
        if (current.layers.isEmpty()) return@onMain
        setLayersPlaying(!current.layersPlaying)
    }

    override fun setMasterVolume(volume: Float) = onMain {
        val value = volume.coerceIn(0f, 1f)
        if (_state.value.masterVolume == value) return@onMain
        updateState { it.copy(masterVolume = value) }
        applyTrackVolume()
        updateVoiceGains(MASTER_FADE_MS)
    }

    override fun setSleepTimer(minutes: Int?) = onMain {
        timerJob?.cancel()
        timerJob = null
        if (timerGain != 1f) {
            timerGain = 1f
            applyAllGainsImmediate()
        }
        if (minutes == null) {
            updateState { it.copy(sleepTimerEndMs = null) }
            return@onMain
        }
        val end = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        updateState { it.copy(sleepTimerEndMs = end) }
        timerJob = scope.launch {
            delay((end - System.currentTimeMillis()).coerceAtLeast(0L))
            sleepTimerFadeOut()
        }
    }

    override fun setAutoSleepToEndOfTrack(enabled: Boolean) = onMain {
        autoSleepEnabled = enabled
        if (!enabled) {
            cancelAutoSleep()
            return@onMain
        }
        armAutoSleepIfNeeded()
    }

    override fun stopAll() = onMain { stopAllInternal() }

    private fun player(): ExoPlayer = trackPlayer ?: ExoPlayer.Builder(context)
        .setAudioAttributes(MEDIA_AUDIO_ATTRIBUTES, false)
        .setHandleAudioBecomingNoisy(false)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .also {
            it.addListener(trackListener)
            trackPlayer = it
        }

    private fun AudioTrack.toMediaItem(): MediaItem {
        val uri = when (val value = source) {
            is TrackSource.Bundled -> RawResourceDataSource.buildRawResourceUri(value.rawResId)
            is TrackSource.Local -> Uri.parse(value.uri)
        }
        return MediaItem.fromUri(uri)
    }

    private fun updateTrackFlags() {
        val player = trackPlayer ?: return
        if (_state.value.track == null) return
        val playing = player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        if (_state.value.trackPlaying != playing) {
            updateState { it.copy(trackPlaying = playing) }
            if (playing) startTicker()
            syncAudibility()
        }
    }

    private fun updateDuration() {
        val player = trackPlayer ?: return
        if (_state.value.track == null) return
        val duration = player.duration
        if (duration > 0 && duration != C.TIME_UNSET) {
            _state.update {
                if (it.track != null && it.durationMs != duration) it.copy(durationMs = duration) else it
            }
        }
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, POSITION_INTERVAL_MS)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(ticker)
    }

    private fun cancelAutoSleep() {
        autoSleepJob?.cancel()
        autoSleepJob = null
        autoSleepFadeStartMs = -1L
        if (autoSleepGain != 1f) {
            autoSleepGain = 1f
            applyAllGainsImmediate()
        }
        updateState { if (it.autoSleepEndMs != null) it.copy(autoSleepEndMs = null) else it }
    }

    /** Ramps the track volume up from silence over [FADE_IN_MS]. */
    private fun startFadeIn() {
        fadeInJob?.cancel()
        fadeGain = 0f
        applyTrackVolume()
        val steps = (FADE_IN_MS / FADE_STEP_MS).toInt().coerceAtLeast(1)
        fadeInJob = scope.launch {
            repeat(steps) { index ->
                fadeGain = (index + 1f) / steps
                applyTrackVolume()
                delay(FADE_STEP_MS)
            }
            fadeGain = 1f
            applyTrackVolume()
        }
    }

    /**
     * Arms the automatic fade that lets the track end softly. It runs in its
     * own job so the user sleep timer stays independent of it.
     */
    private fun armAutoSleepIfNeeded() {
        if (!autoSleepEnabled) return
        if (autoSleepGain < 1f) return
        val player = trackPlayer ?: return
        if (!player.playWhenReady) return
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        if (duration <= AUTO_SLEEP_MIN_TRACK_MS) return
        val remaining = duration - SLEEP_FADE_MS - player.currentPosition
        if (remaining <= 0L) return
        val fadeStart = System.currentTimeMillis() + remaining
        if (autoSleepJob != null && autoSleepFadeStartMs == fadeStart) return
        autoSleepJob?.cancel()
        autoSleepJob = null
        autoSleepFadeStartMs = fadeStart
        updateState { it.copy(autoSleepEndMs = fadeStart + SLEEP_FADE_MS) }
        autoSleepJob = scope.launch {
            delay(remaining)
            autoSleepFadeOut()
        }
    }

    private suspend fun autoSleepFadeOut() {
        val player = trackPlayer
        if (player == null || !player.playWhenReady) {
            cancelAutoSleep()
            return
        }
        updateState { it.copy(autoSleepEndMs = null) }
        val steps = (SLEEP_FADE_MS / SLEEP_FADE_STEP_MS).toInt().coerceAtLeast(1)
        repeat(steps) { index ->
            autoSleepGain = 1f - (index + 1f) / steps
            applyAllGainsImmediate()
            delay(SLEEP_FADE_STEP_MS)
        }
        autoSleepGain = 1f
        autoSleepJob = null
        _state.value.track?.let { track -> PlaybackMemory.remember(context, track.id, 0L) }
        stopAllInternal(rememberPosition = false)
    }

    private fun rememberPositionPeriodically(positionMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastRememberedAtMs < REMEMBER_INTERVAL_MS) return
        lastRememberedAtMs = now
        rememberPositionNow(positionMs)
    }

    private fun rememberPositionNow(positionMs: Long) {
        val track = _state.value.track ?: return
        PlaybackMemory.remember(context, track.id, positionMs)
    }

    /** Remembers where the player stopped; a finished track restarts from zero. */
    private fun rememberStoppedPosition(player: ExoPlayer) {
        if (player.playbackState == Player.STATE_ENDED) {
            _state.value.track?.let { PlaybackMemory.remember(context, it.id, 0L) }
        } else {
            rememberPositionNow(player.currentPosition.coerceAtLeast(0L))
        }
    }

    private fun pauseTrackInternal() {
        val player = trackPlayer
        if (player != null) {
            player.pause()
            if (_state.value.track != null) {
                val position = player.currentPosition.coerceAtLeast(0L)
                updateState {
                    it.copy(
                        trackPlaying = false,
                        positionMs = position,
                    )
                }
                rememberPositionNow(position)
            }
        }
        cancelAutoSleep()
    }

    private fun stopTrackInternal() {
        trackPlayer?.let {
            rememberStoppedPosition(it)
            it.stop()
            it.clearMediaItems()
        }
        stopTicker()
        cancelAutoSleep()
        updateState {
            it.copy(
                track = null,
                trackPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                autoSleepEndMs = null,
            )
        }
        syncAudibility()
    }

    private fun pauseLayersInternal() {
        if (!_state.value.layersPlaying) return
        updateState { it.copy(layersPlaying = false) }
        voices.values.forEach { voice ->
            voice.fadeJob?.cancel()
            setVoiceGain(voice, 0f)
            pauseVoice(voice)
        }
    }

    private fun clearLayersInternal() {
        updateState { it.copy(layers = emptyMap(), layersPlaying = false) }
        voices.keys.toList().forEach { removeVoice(it, fade = true) }
        syncAudibility()
    }

    private fun ensureVoice(layerId: String, audible: Boolean): LayerVoice? {
        voices[layerId]?.let { return it }
        val layer = AmbienceCatalog.layer(layerId) ?: return null
        val mediaPlayer = runCatching { MediaPlayer.create(context, layer.rawResId) }
            .getOrNull() ?: return null
        runCatching { mediaPlayer.isLooping = true }
        runCatching { mediaPlayer.setAudioAttributes(PLATFORM_AUDIO_ATTRIBUTES) }
        runCatching { mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK) }
        runCatching { mediaPlayer.setVolume(0f, 0f) }
        val voice = LayerVoice(mediaPlayer)
        voices[layerId] = voice
        if (audible) startVoice(voice)
        return voice
    }

    private fun removeVoice(layerId: String, fade: Boolean) {
        val voice = voices.remove(layerId) ?: return
        voice.fadeJob?.cancel()
        if (fade && voice.player.isPlaying) {
            retiringVoices.add(voice)
            voice.fadeJob = scope.launch {
                val start = voice.gain
                val steps = (LAYER_FADE_MS / FADE_STEP_MS).toInt().coerceAtLeast(1)
                repeat(steps) { index ->
                    setVoiceGain(voice, start * (1f - (index + 1f) / steps))
                    delay(LAYER_FADE_MS / steps)
                }
                releaseVoice(voice)
                retiringVoices.remove(voice)
            }
        } else {
            releaseVoice(voice)
        }
    }

    private fun releaseVoice(voice: LayerVoice) {
        runCatching { voice.player.stop() }
        runCatching { voice.player.release() }
    }

    private fun startVoice(voice: LayerVoice) {
        runCatching { if (!voice.player.isPlaying) voice.player.start() }
    }

    private fun pauseVoice(voice: LayerVoice) {
        runCatching { if (voice.player.isPlaying) voice.player.pause() }
    }

    private fun setVoiceGain(voice: LayerVoice, gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        voice.gain = clamped
        runCatching { voice.player.setVolume(clamped, clamped) }
    }

    private fun fadeVoice(
        voice: LayerVoice,
        target: Float,
        durationMs: Long = LAYER_FADE_MS,
        onEnd: (() -> Unit)? = null,
    ) {
        voice.fadeJob?.cancel()
        val clamped = target.coerceIn(0f, 1f)
        if (durationMs <= 0L) {
            setVoiceGain(voice, clamped)
            onEnd?.invoke()
            return
        }
        val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
        voice.fadeJob = scope.launch {
            val start = voice.gain
            repeat(steps) { index ->
                setVoiceGain(voice, start + (clamped - start) * ((index + 1f) / steps))
                delay(durationMs / steps)
            }
            setVoiceGain(voice, clamped)
            onEnd?.invoke()
        }
    }

    private fun layerGain(target: Float): Float = (
        target * _state.value.masterVolume * duckGain * timerGain * autoSleepGain
        ).coerceIn(0f, 1f)

    private fun updateVoiceGains(durationMs: Long = MASTER_FADE_MS) {
        val current = _state.value
        voices.forEach { (id, voice) ->
            val target = current.layers[id] ?: 0f
            val gain = if (current.layersPlaying) layerGain(target) else 0f
            fadeVoice(voice, gain, durationMs) {
                if (!_state.value.layersPlaying) pauseVoice(voice)
            }
        }
    }

    private fun applyTrackVolume() {
        val player = trackPlayer ?: return
        player.volume = (
            _state.value.masterVolume * duckGain * timerGain * fadeGain * autoSleepGain
            ).coerceIn(0f, 1f)
    }

    private fun applyAllGainsImmediate() {
        applyTrackVolume()
        val current = _state.value
        voices.forEach { (id, voice) ->
            val target = current.layers[id] ?: 0f
            val gain = if (current.layersPlaying) layerGain(target) else 0f
            voice.fadeJob?.cancel()
            setVoiceGain(voice, gain)
        }
    }

    private suspend fun sleepTimerFadeOut() {
        val steps = (SLEEP_FADE_MS / SLEEP_FADE_STEP_MS).toInt().coerceAtLeast(1)
        repeat(steps) { index ->
            timerGain = 1f - (index + 1f) / steps
            applyAllGainsImmediate()
            delay(SLEEP_FADE_STEP_MS)
        }
        timerGain = 1f
        stopAllInternal()
    }

    private fun stopAllInternal(rememberPosition: Boolean = true) {
        timerJob?.cancel()
        timerJob = null
        timerGain = 1f
        duckGain = 1f
        fadeInJob?.cancel()
        fadeInJob = null
        fadeGain = 1f
        autoSleepJob?.cancel()
        autoSleepJob = null
        autoSleepFadeStartMs = -1L
        autoSleepGain = 1f
        clearFocusResume()
        trackPlayer?.let {
            if (rememberPosition) rememberStoppedPosition(it)
            it.stop()
            it.clearMediaItems()
        }
        stopTicker()
        voices.values.forEach { voice ->
            voice.fadeJob?.cancel()
            releaseVoice(voice)
        }
        voices.clear()
        retiringVoices.forEach { voice ->
            voice.fadeJob?.cancel()
            releaseVoice(voice)
        }
        retiringVoices.clear()
        abandonFocus()
        updateState { AudioState(masterVolume = it.masterVolume) }
        syncService()
    }

    private fun requestFocusIfNeeded() {
        if (focusHeld) return
        val result = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusHeld = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun clearFocusResume() {
        focusPausedTrack = false
        focusPausedLayers = false
        focusLossPaused = false
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                focusHeld = true
                duckGain = 1f
                applyTrackVolume()
                val resumeTrack = focusPausedTrack
                val resumeLayers = focusPausedLayers
                clearFocusResume()
                if (resumeTrack) resumeTrackInternal()
                if (resumeLayers) resumeLayersInternal()
                updateVoiceGains(DUCK_FADE_MS)
                syncAudibility()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckGain = DUCK_GAIN
                applyTrackVolume()
                updateVoiceGains(DUCK_FADE_MS)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusPausedTrack = _state.value.trackPlaying
                focusPausedLayers = _state.value.layersPlaying && _state.value.layers.isNotEmpty()
                focusLossPaused = focusPausedTrack || focusPausedLayers
                duckGain = 1f
                focusHeld = false
                applyTrackVolume()
                if (_state.value.trackPlaying) pauseTrackInternal()
                pauseLayersInternal()
                syncAudibility()
            }
        }
    }

    private fun resumeTrackInternal() {
        val player = trackPlayer ?: return
        player.play()
        startTicker()
        if (!_state.value.trackPlaying) updateState { it.copy(trackPlaying = true) }
        armAutoSleepIfNeeded()
    }

    private fun resumeLayersInternal() {
        val current = _state.value
        if (current.layers.isEmpty()) return
        updateState { it.copy(layersPlaying = true) }
        current.layers.forEach { (id, target) ->
            val voice = voices[id] ?: return@forEach
            startVoice(voice)
            fadeVoice(voice, layerGain(target))
        }
    }

    private fun pauseAllForNoisy() {
        clearFocusResume()
        pauseTrackInternal()
        pauseLayersInternal()
        syncAudibility()
    }

    private fun syncAudibility() {
        val active = _state.value.isAudioActive()
        if (active) {
            requestFocusIfNeeded()
        } else if (!focusLossPaused) {
            duckGain = 1f
            abandonFocus()
            applyTrackVolume()
        }
        syncService()
    }

    private fun syncService() {
        if (_state.value.isAudioActive()) {
            cancelPendingServiceStop()
            if (serviceStopIssued) {
                serviceStopIssued = false
                serviceStartPending = true
                startService()
            } else if (AudioService.isRunning) {
                serviceStartPending = false
            } else if (!serviceStartPending) {
                serviceStartPending = true
                startService()
            }
        } else if (!serviceStopScheduled) {
            serviceStopScheduled = true
            stopRecheckAttempts = 0
            mainHandler.postDelayed(stopServiceRunnable, SERVICE_STOP_DELAY_MS)
        }
    }

    private fun cancelPendingServiceStop() {
        if (!serviceStopScheduled) return
        serviceStopScheduled = false
        stopRecheckAttempts = 0
        mainHandler.removeCallbacks(stopServiceRunnable)
    }

    private fun stopServiceNow() {
        serviceStopIssued = true
        context.stopService(Intent(context, AudioService::class.java))
    }

    private fun startService() {
        val started = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AudioService::class.java).setAction(AudioService.ACTION_SYNC),
            )
        }.isSuccess
        if (!started) serviceStartPending = false
    }

    private fun updateState(transform: (AudioState) -> AudioState) {
        _state.update(transform)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private class LayerVoice(
        val player: MediaPlayer,
        var gain: Float = 0f,
        var fadeJob: Job? = null,
    )

    private companion object {
        const val POSITION_INTERVAL_MS = 500L
        const val LAYER_FADE_MS = 600L
        const val MASTER_FADE_MS = 200L
        const val DUCK_FADE_MS = 200L
        const val FADE_STEP_MS = 30L
        const val FADE_IN_MS = 3_000L
        const val SLEEP_FADE_MS = 15_000L
        const val SLEEP_FADE_STEP_MS = 250L
        const val AUTO_SLEEP_MIN_TRACK_MS = 60_000L
        const val REMEMBER_INTERVAL_MS = 5_000L
        const val SILENT_THRESHOLD = 0.001f
        const val DUCK_GAIN = 0.25f
        const val SERVICE_STOP_DELAY_MS = 1_500L
        const val STOP_RECHECK_MS = 250L
        const val STOP_RECHECK_LIMIT = 40

        val PLATFORM_AUDIO_ATTRIBUTES: android.media.AudioAttributes =
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

        val MEDIA_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
