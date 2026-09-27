package jp.warimashi.voiceop.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 画面を見ずに状態がわかるよう、音とバイブで合図する。
 *
 * | 状態 | 音 | バイブ |
 * |---|---|---|
 * | マイクON | ポン（1回） | 短く1回 |
 * | 聞き取り中 | （設定で小さなコッ） | 一定間隔でごく短く |
 * | マイクOFF（聞き取り終了） | ピピッ（2回） | 短く2回 |
 * | 送信完了 | 確認音 | 長め1回 |
 * | エラー | プー | 3回 |
 */
class Cues(context: Context) {

    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()

    /** 聞き取り中の合図用（マイクへの影響を抑えるため小さい音量）。 */
    private val softTone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 25) }.getOrNull()

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
        vibrate(longArrayOf(0, 120))
    }

    /** 聞き取り中（一定間隔で呼ぶ）。 */
    fun listening(withSound: Boolean) {
        if (withSound) softTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 30)
        vibrate(longArrayOf(0, 35))
    }

    /** マイクOFF（聞き取り終了）。開始音と区別できるよう2回鳴らす。 */
    fun listenEnd() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        vibrate(longArrayOf(0, 80, 90, 80))
    }

    fun error() {
        tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        vibrate(longArrayOf(0, 120, 100, 120, 100, 120))
    }

    fun sent() {
        tone?.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 400)
        vibrate(longArrayOf(0, 300))
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun release() {
        tone?.release()
        softTone?.release()
    }
}
