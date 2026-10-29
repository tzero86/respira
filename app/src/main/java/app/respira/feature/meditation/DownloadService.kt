package app.respira.feature.meditation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.respira.MainActivity
import app.respira.R
import app.respira.i18n.LocalePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var token: Int = 0
    private var title: String = ""
    private var foregroundStarted = false
    private var localizedLanguage: String? = null
    private var localized: Context? = null
    private var channelLanguage: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancel(intent)
            ACTION_START -> start(intent)
            else -> {
                startForegroundNow(title)
                stopIdle()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        Downloads.abandon(token)
        foregroundStarted = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        job?.cancel()
        token = intent.getIntExtra(EXTRA_TOKEN, 0)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        startForegroundNow(title)
        val source = sourceOf(intent)
        if (source == null) {
            stopIdle()
        } else {
            launchDownload(source)
        }
    }

    private fun sourceOf(intent: Intent): AudioSource? {
        intent.getStringExtra(EXTRA_VIDEO_ID)?.let {
            return AudioSource.YouTube(it, intent.getStringExtra(EXTRA_TRACK))
        }
        intent.getStringExtra(EXTRA_URL)?.let { return AudioSource.Direct(it) }
        return null
    }

    private fun launchDownload(source: AudioSource) {
        val owner = token
        val downloadTitle = title
        job = serviceScope.launch {
            val result = try {
                downloadAudio(applicationContext, source, downloadTitle) { downloaded, total ->
                    Downloads.progress(owner, downloadTitle, downloaded, total)
                    if (owner == token) notify(buildNotification(downloadTitle, downloaded, total))
                }
            } catch (error: CancellationException) {
                Downloads.abandon(owner)
                if (owner == token) stopSelf()
                return@launch
            }
            result.fold(
                onSuccess = {
                    Downloads.complete(owner, downloadTitle)
                    LibraryEvents.bump()
                },
                onFailure = { error -> Downloads.fail(owner, downloadTitle, classify(error)) },
            )
            if (owner == token) stopSelf()
        }
    }

    private fun cancel(intent: Intent) {
        val requestToken = intent.getIntExtra(EXTRA_TOKEN, token)
        val running = job?.isActive == true
        if (running && requestToken != token) return
        intent.getStringExtra(EXTRA_TITLE)?.let { title = it }
        if (!foregroundStarted) startForegroundNow(title)
        if (running) job?.cancel() else stopIdle(requestToken)
    }

    private fun stopIdle(requestToken: Int = token) {
        Downloads.abandon(requestToken)
        stopSelf()
    }

    private fun classify(error: Throwable): DownloadFailure {
        val message = error.message.orEmpty()
        return when {
            error is YouTubeUnavailableException -> DownloadFailure.UNAVAILABLE
            message.startsWith("HTTP 403") || message.startsWith("HTTP 410") -> DownloadFailure.EXPIRED
            isTooLarge(error) -> DownloadFailure.TOO_LARGE
            else -> DownloadFailure.GENERIC
        }
    }

    private fun isTooLarge(error: Throwable): Boolean =
        error.javaClass.simpleName.startsWith("DownloadTooLarge") || error.message.orEmpty().startsWith("Download exceeds")

    private fun startForegroundNow(title: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title, 0L, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foregroundStarted = true
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.med_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized.getString(R.string.med_download_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelLanguage = language
    }

    private fun buildNotification(
        title: String,
        downloaded: Long,
        total: Long?,
    ): Notification {
        val localized = localizedContext()
        ensureChannel(localized)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wind_arrow_down)
            .setContentTitle(localized.getString(R.string.app_name))
            .setContentText(title.ifBlank { localized.getString(R.string.med_download_notification_text) })
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, localized.getString(R.string.med_download_cancel), cancelIntent())
        if (total != null && total > 0L) {
            val max = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val value = (downloaded.coerceAtMost(max.toLong())).toInt()
            builder.setProgress(max, value, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun notify(notification: Notification) {
        if (!foregroundStarted) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getForegroundService(
        this,
        1,
        Intent(this, DownloadService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_TITLE, title),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "app.respira.meditation.download.START"
        const val ACTION_CANCEL = "app.respira.meditation.download.CANCEL"

        const val EXTRA_TOKEN = "token"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_TRACK = "audioTrack"

        private const val CHANNEL_ID = "respira_downloads"
        private const val NOTIFICATION_ID = 43
    }
}
