package com.acme.scantotally.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sound and haptics for a scan result.
 *
 * THIS IS THE PRIMARY INTERFACE, not a decoration. The operator is holding a
 * box with both hands and is not looking at the screen for every scan. What
 * they get is the beep, so the four patterns have to be unmistakably different
 * from one another through ear defenders and across a noisy warehouse.
 *
 * The screen is the secondary channel, for when something has gone wrong and
 * they stop to look.
 */
enum class Beep { ACCEPT, DUPLICATE, REJECT, FLAGGED }

class Feedback(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * ToneGenerator is used rather than SoundPool because these must cut
     * through: it plays on the alarm-adjacent stream at a fixed high volume,
     * independent of whatever the media volume happens to be set to.
     */
    private val tone: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, MAX_VOLUME)
    }.getOrNull()

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    fun play(beep: Beep) {
        scope.launch {
            when (beep) {
                // One short high beep: counted, move on. The sound the operator
                // hears hundreds of times a shift, so it is the shortest.
                Beep.ACCEPT -> {
                    toneOnce(ToneGenerator.TONE_PROP_BEEP, 90)
                    vibrate(longArrayOf(0, 35), -1)
                }

                // Two beeps: same box already scanned. Distinct from ACCEPT by
                // COUNT, which survives noise better than a pitch difference.
                Beep.DUPLICATE -> {
                    toneOnce(ToneGenerator.TONE_PROP_BEEP, 90)
                    delay(110)
                    toneOnce(ToneGenerator.TONE_PROP_BEEP, 90)
                    vibrate(longArrayOf(0, 40, 80, 40), -1)
                }

                // Long low buzz plus a strong double pulse: set the box aside.
                // Deliberately unpleasant and impossible to mistake for ACCEPT.
                Beep.REJECT -> {
                    toneOnce(ToneGenerator.TONE_SUP_ERROR, 500)
                    vibrate(longArrayOf(0, 180, 90, 180), -1)
                }

                // Short beep then a soft chirp: counted, but something will
                // need to look at it later. Close enough to ACCEPT that the
                // operator keeps moving, different enough to register.
                Beep.FLAGGED -> {
                    toneOnce(ToneGenerator.TONE_PROP_BEEP, 90)
                    delay(60)
                    toneOnce(ToneGenerator.TONE_PROP_ACK, 160)
                    vibrate(longArrayOf(0, 35, 60, 90), -1)
                }
            }
        }
    }

    /** Sounded when a whole session posts to Tally. */
    fun playSessionPosted() {
        scope.launch {
            toneOnce(ToneGenerator.TONE_PROP_ACK, 150)
            delay(160)
            toneOnce(ToneGenerator.TONE_PROP_ACK, 250)
            vibrate(longArrayOf(0, 60, 80, 120), -1)
        }
    }

    private fun toneOnce(type: Int, durationMs: Int) {
        runCatching { tone?.startTone(type, durationMs) }
    }

    private fun vibrate(pattern: LongArray, repeat: Int) {
        val v = vibrator ?: return
        runCatching {
            val effect = VibrationEffect.createWaveform(pattern, repeat)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.vibrate(effect, ALARM_ATTRS)
            } else {
                v.vibrate(effect)
            }
        }
    }

    fun release() {
        runCatching { tone?.release() }
    }

    private companion object {
        const val MAX_VOLUME = 100

        /**
         * Alarm usage so the feedback is not silenced by Do Not Disturb or a
         * muted notification stream. A scanner that has gone quiet is a scanner
         * the operator cannot trust.
         */
        val ALARM_ATTRS: android.os.VibrationAttributes
            get() = android.os.VibrationAttributes.Builder()
                .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                .build()

        @Suppress("unused")
        val AUDIO_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
