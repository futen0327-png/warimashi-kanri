package jp.warimashi.voiceop.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import jp.warimashi.voiceop.core.Vocabulary

/**
 * Android 標準の SpeechRecognizer による1回分の発話認識（無音検出で自動終了）。
 * メインスレッドから呼ぶこと。
 */
class SpeechInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onListeningStarted()
        fun onListeningEnded()
        fun onResults(candidates: List<String>)
        fun onError(error: Int)
    }

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        // 端末によっては使い回すと ERROR_RECOGNIZER_BUSY になるので毎回作り直す
        destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListeningStarted()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = listener.onListeningEnded()
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) = listener.onError(error)

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (list.isEmpty()) listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
                else listener.onResults(list)
            }
        })
        recognizer = r
        r.startListening(buildIntent())
    }

    /** 話し終わる前にタップされたら、その時点までの音声で認識を確定させる。 */
    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 対応する認識エンジンでは、固定候補の語を認識されやすくする
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasingWords))
        }
    }

    private val biasingWords: List<String> by lazy {
        (Vocabulary.items + Vocabulary.sizes + Vocabulary.surcharges + Vocabulary.reasons + Vocabulary.rejects)
            .map { it.speech } + listOf("ナンバー", "品目", "サイズ", "割増", "理由", "客先", "拒否", "パス", "送信", "取消")
    }
}
