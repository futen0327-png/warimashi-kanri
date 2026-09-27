package jp.warimashi.voiceop.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 画面を見ずに状態がわかるよう、ビープ音とバイブで合図する。 */
class Cues(context: Context) {

    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** マイクON（話してよい合図）。 */
    fun listenStart() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        vibrate(longArrayOf(0, 80))
    }

    /** マイクOFF（聞き取り終了）。 */
    fun listenEnd() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
    }

    fun error() {
        tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        vibrate(longArrayOf(0, 120, 100, 120, 100, 120))
    }

    fun sent() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        vibrate(longArrayOf(0, 300))
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun release() {
        tone?.release()
    }
}
