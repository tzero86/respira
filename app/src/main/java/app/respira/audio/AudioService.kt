package app.respira.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.IconCompat
import app.respira.MainActivity
import app.respira.R
import app.respira.i18n.LocalePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps playback alive while the screen is off and
 * exposes transport controls through a media notification.
 */
class AudioService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: MediaSessionCompat? = null
    private var stateJob: Job? = null
    private var foregroundStarted = false
    private var notificationSignature: String? = null
    private var channelLanguage: String? = null
    private var localizedLanguage: String? = null
    private var localized: Context? = null

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            val hub = Audio.hub
            if (hub.state.value.track != null) hub.resumeTrack()
            if (hub.state.value.layers.isNotEmpty()) hub.setLayersPlaying(true)
        }

        override fun onPause() {
            Audio.hub.pauseTrack()
            Audio.hub.setLayersPlaying(false)
        }

        override fun onStop() = Audio.hub.stopAll()

        override fun onSeekTo(pos: Long) = Audio.hub.seekTo(pos)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        session = MediaSessionCompat(this, "RespiraAudio").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setSessionActivity(contentIntent())
            setCallback(sessionCallback, mainHandler)
            isActive = true
        }
        startForegroundNow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayback()
            ACTION_STOP -> Audio.hub.stopAll()
        }

        val state = Audio.hub.state.value
        if (foregroundStarted) {
            updateNotification(state)
        } else {
            startForegroundNow()
        }
        updateSession(state)

        if (stateJob == null) {
            stateJob = serviceScope.launch {
                Audio.hub.state.collect { latest ->
                    updateNotification(latest)
                    updateSession(latest)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        if (foregroundStarted) return
        val state = Audio.hub.state.value
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        foregroundStarted = true
        notificationSignature =
            state.notificationSignature(LocalePreferences.language(applicationContext))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        stateJob = null
        session?.release()
        session = null
        foregroundStarted = false
        notificationSignature = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun togglePlayback() {
        val hub = Audio.hub
        val state = hub.state.value
        if (state.isAudioActive()) {
            if (state.trackPlaying) hub.pauseTrack()
            if (state.layersPlaying) hub.setLayersPlaying(false)
        } else {
            if (state.track != null) hub.resumeTrack()
            if (state.layers.isNotEmpty()) hub.setLayersPlaying(true)
        }
    }

    private fun localizedContext(): Context {
        val language = LocalePreferences.language(applicationContext)
        val cached = localized
        if (cached != null && localizedLanguage == language) return cached
        return LocalePreferences.localizedContext(applicationContext).also {
            localized = it
            localizedLanguage = language
        }
    }

    private fun ensureChannel(localized: Context) {
        val language = LocalePreferences.language(applicationContext)
        if (channelLanguage == language) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.audio_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized.getString(R.string.audio_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
        channelLanguage = language
    }

    private fun AudioTrack?.localizedTitle(context: Context): String? =
        this?.let { track -> track.titleRes?.let(context::getString) ?: track.title }

    private fun AudioTrack?.localizedSubtitle(context: Context): String? =
        this?.let { track -> track.subtitleRes?.let(context::getString) ?: track.subtitle }

    private fun buildNotification(state: AudioState): Notification {
        val localized = localizedContext()
        ensureChannel(localized)
        val playing = state.isAudioActive()
        val track = state.track
        val toggleAction = if (playing) {
            NotificationCompat.Action(
                IconCompat.createWithResource(this, R.drawable.ic_pause),
                localized.getString(R.string.audio_action_pause),
                serviceAction(ACTION_TOGGLE, 1),
            )
        } else {
            NotificationCompat.Action(
                IconCompat.createWithResource(this, R.drawable.ic_play),
                localized.getString(R.string.audio_action_play),
                serviceAction(ACTION_TOGGLE, 1),
            )
        }
        val stopAction = NotificationCompat.Action(
            IconCompat.createWithResource(this, R.drawable.ic_square),
            localized.getString(R.string.audio_action_stop),
            serviceAction(ACTION_STOP, 2),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wind)
            .setContentTitle(
                track.localizedTitle(localized)
                    ?: localized.getString(R.string.audio_notification_title_fallback),
            )
            .setContentText(
                track.localizedSubtitle(localized)
                    ?: if (state.layers.isEmpty()) {
                        localized.getString(R.string.audio_notification_idle_text)
                    } else {
                        localized.getString(R.string.audio_notification_ambience_text)
                    },
            )
            .setContentIntent(contentIntent())
            .setDeleteIntent(serviceAction(ACTION_STOP, 2))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(state: AudioState) {
        if (!foregroundStarted) return
        val signature = state.notificationSignature(LocalePreferences.language(applicationContext))
        if (signature == notificationSignature) return
        notificationSignature = signature
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun updateSession(state: AudioState) {
        val mediaSession = session ?: return
        val localized = localizedContext()
        val playbackState = when {
            state.isAudioActive() -> PlaybackStateCompat.STATE_PLAYING
            state.track != null || state.layers.isNotEmpty() -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(playbackState, state.positionMs, 1f)
                .build(),
        )
        val track = state.track
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    track.localizedTitle(localized)
                        ?: localized.getString(R.string.audio_notification_title_fallback),
                )
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    track.localizedSubtitle(localized)
                        ?: localized.getString(R.string.audio_notification_ambience_text),
                )
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                .build(),
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val ACTION_SYNC = "app.respira.audio.action.SYNC"
        const val ACTION_TOGGLE = "app.respira.audio.action.TOGGLE"
        const val ACTION_STOP = "app.respira.audio.action.STOP"

        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 42

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}

internal fun AudioState.isAudioActive(): Boolean =
    trackPlaying || (layersPlaying && layers.isNotEmpty())

private fun AudioState.notificationSignature(language: String): String =
    "$language|${track?.id}|${track?.title}|${trackPlaying}|${layersPlaying}|${layers.size}"
