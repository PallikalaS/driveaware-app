package edu.csueb.driveaware.data

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Tiered alerts. Level 3 fires locally first (no network wait); Gemini's
 * message, if it arrives, is spoken after.
 *   L1: soft chime            L2: chime + spoken message + short vibration
 *   L3: loud alarm + long vibration + red flash (UI) + spoken message
 */
class AlertManager(context: Context) : TextToSpeech.OnInitListener {
    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
    private val tts = TextToSpeech(context, this)
    private var ttsReady = false
    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= 0
    }

    fun fire(level: Int, message: String?) {
        when (level) {
            1 -> tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            2 -> {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            else -> {
                tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
            }
        }
        if (level >= 2 && message != null) say(message)
    }

    fun say(message: String) {
        if (ttsReady) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "driveaware")
    }

    fun close() { tts.shutdown(); tone.release() }
}
