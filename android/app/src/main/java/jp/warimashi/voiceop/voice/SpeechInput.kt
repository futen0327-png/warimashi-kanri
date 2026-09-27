package jp.warimashi.voiceop.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import jp.warimashi.voiceop.AppConfig
import jp.warimashi.voiceop.core.Vocabulary

/**
 * 1回のタップ分の聞き取り（セッション）。
 *
 * SpeechRecognizer は端末側の判定で「話し始めを待つ時間」「話し終わりとみなす無音」が短く、
 * そのための Intent 指定もほとんどの端末で無視される。そこでアプリ側で時間を管理する:
 *
 * - 話し始める前に認識エンジンが諦めても、[AppConfig.SPEECH_START_TIMEOUT_MS] までは黙って聞き直す
 * - 途中の息継ぎで認識エンジンが区切っても、すぐ聞き直して続きをつなげる。
 *   区切りのあと [AppConfig.SPEECH_END_SILENCE_MS] 話されなければ、そこで1回分の発話として確定する
 * - 聞き取り中にもう一度タップされたら、その時点までの内容で確定する
 *
 * メインスレッドから呼ぶこと。
 */
class SpeechInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** マイクが開いた（セッションの最初の1回だけ）。 */
        fun onReady()

        /** 1回分の発話が確定した。区切られた部分はつなげてある。 */
        fun onFinished(candidates: List<String>)

        /** 待っても何も話されなかった。 */
        fun onNoSpeech()

        /** 続行できないエラー（マイク権限・通信など）。 */
        fun onError(error: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private var active = false
    private var stopping = false
    private var readyNotified = false
    private var speechStarted = false
    private var endPending = false
    private var startPending = false
    private var retries = 0

    /** 区切られた部分ごとの認識候補。 */
    private val segments = ArrayList<List<String>>()

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private val startTimeout = Runnable {
        startPending = false
        if (active && !speechStarted && segments.isEmpty()) finish()
    }
    private val endSilence = Runnable {
        endPending = false
        if (active) finish()
    }
    private val maxLength = Runnable { stop() }
    private val stopFallback = Runnable { if (active) finish() }
    private val retryListen = Runnable { if (active) listen() }

    fun start() {
        cancel()
        active = true
        stopping = false
        readyNotified = false
        speechStarted = false
        endPending = false
        retries = 0
        segments.clear()
        postStartTimeout(AppConfig.SPEECH_START_TIMEOUT_MS)
        main.postDelayed(maxLength, AppConfig.SPEECH_MAX_SESSION_MS)
        listen()
    }

    /** 聞き取り中のタップ: その時点までの内容で確定する。 */
    fun stop() {
        if (!active || stopping) return
        stopping = true
        main.removeCallbacks(endSilence)
        endPending = false
        val r = recognizer
        if (r == null) finish() else {
            r.stopListening()
            main.postDelayed(stopFallback, 2500)
        }
    }

    /** 結果を返さずに打ち切る（画面が消えたときなど）。 */
    fun cancel() {
        active = false
        clearTimers()
        recognizer?.destroy()
        recognizer = null
    }

    fun destroy() = cancel()

    private fun listen() {
        if (!active) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(Callback(r))
        recognizer = r
        r.startListening(buildIntent())
    }

    private fun finish() {
        if (!active) return
        active = false
        clearTimers()
        recognizer?.destroy()
        recognizer = null
        if (segments.isEmpty()) listener.onNoSpeech() else listener.onFinished(joinSegments())
    }

    private fun clearTimers() {
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(endSilence)
        main.removeCallbacks(maxLength)
        main.removeCallbacks(stopFallback)
        main.removeCallbacks(retryListen)
        endPending = false
        startPending = false
    }

    private fun postStartTimeout(ms: Long) {
        main.removeCallbacks(startTimeout)
        main.postDelayed(startTimeout, ms)
        startPending = true
    }

    /** 区切られた部分をつなげる。候補の順位ごとにつなぎ、足りない部分は第1候補で補う。 */
    private fun joinSegments(): List<String> {
        val n = segments.maxOf { it.size }
        return (0 until n).map { i ->
            segments.joinToString(" ") { seg -> seg.getOrElse(i) { seg.first() } }
        }.distinct()
    }

    private inner class Callback(private val r: SpeechRecognizer) : RecognitionListener {
        private val current get() = active && r === recognizer

        override fun onReadyForSpeech(params: Bundle?) {
            if (!current) return
            retries = 0
            if (!readyNotified) {
                readyNotified = true
                listener.onReady()
            }
        }

        override fun onBeginningOfSpeech() {
            if (!current) return
            // 話し始めた（続きを話している）ので、終了待ちを取り消す
            speechStarted = true
            main.removeCallbacks(startTimeout)
            startPending = false
            main.removeCallbacks(endSilence)
            endPending = false
        }

        override fun onResults(results: Bundle?) {
            if (!current) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .filter { it.isNotBlank() }
            if (list.isNotEmpty()) segments += list
            if (stopping) {
                finish()
                return
            }
            if (segments.isNotEmpty()) {
                // 区切りのあと続きが話されるか待つ
                main.removeCallbacks(startTimeout)
                startPending = false
                main.removeCallbacks(endSilence)
                main.postDelayed(endSilence, AppConfig.SPEECH_END_SILENCE_MS)
                endPending = true
            }
            listen()
        }

        override fun onError(error: Int) {
            if (!current) return
            if (stopping) {
                finish()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // 認識エンジンが早々に諦めただけ。こちらの待ち時間内なら聞き直す
                    when {
                        segments.isNotEmpty() -> if (endPending) listen() else finish()
                        startPending -> listen()
                        speechStarted -> {
                            // 物音などで話し始めと判定されたが言葉にならなかった。少し待ち直す
                            speechStarted = false
                            postStartTimeout(AppConfig.SPEECH_END_SILENCE_MS)
                            listen()
                        }
                        else -> finish()
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    if (retries++ < 3) main.postDelayed(retryListen, 300)
                    else fail(error)
                }
                else -> fail(error)
            }
        }

        override fun onEndOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun fail(error: Int) {
        cancel()
        listener.onError(error)
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // 対応している認識エンジンでは無音判定そのものも長くなる（無視されてもセッション側で補う）
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, AppConfig.SPEECH_END_SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, AppConfig.SPEECH_END_SILENCE_MS)
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
