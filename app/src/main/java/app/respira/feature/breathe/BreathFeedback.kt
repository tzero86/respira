package app.respira.feature.breathe

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.respira.R
import java.util.concurrent.ConcurrentHashMap

internal class BreathFeedback(context: Context) {

    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val canVibrate = vibrator?.hasVibrator() == true

    private val loadedSamples: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val bellSound: Int
    private val tickSound: Int

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSamples.add(sampleId)
        }
        bellSound = soundPool.load(appContext, R.raw.sfx_bell, 1)
        tickSound = soundPool.load(appContext, R.raw.sfx_tick, 1)
    }

    fun startSession(haptics: Boolean, sound: Boolean) {
        vibrate(35L, 60, haptics)
        play(bellSound, 0.5f, sound)
    }

    fun phaseChanged(haptics: Boolean, sound: Boolean) {
        vibrate(35L, 60, haptics)
        play(tickSound, 0.5f, sound)
    }

    fun sessionComplete(haptics: Boolean, sound: Boolean) {
        vibrate(120L, 170, haptics)
        play(bellSound, 0.55f, sound)
    }

    fun stopSounds() {
        soundPool.autoPause()
    }

    fun release() {
        soundPool.autoPause()
        soundPool.release()
    }

    private fun vibrate(durationMs: Long, amplitude: Int, enabled: Boolean) {
        if (!enabled || !canVibrate) return
        runCatching {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }
    }

    private fun play(soundId: Int, volume: Float, enabled: Boolean) {
        if (!enabled || soundId == 0 || soundId !in loadedSamples) return
        runCatching {
            soundPool.play(soundId, volume, volume, 1, 0, 1f)
        }
    }
}
