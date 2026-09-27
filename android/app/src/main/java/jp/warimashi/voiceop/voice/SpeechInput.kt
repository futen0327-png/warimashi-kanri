package jp.warimashi.voiceop.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import jp.warimashi.voiceop.AppConfig

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
class SpeechInput(
    private val context: Context,
    private val listener: Listener,
    /** 診断用のメッセージ（画面の診断ログにも出す）。 */
    private val trace: (String) -> Unit = {},
) {

    interface Listener {
        /** マイクが開いた（セッションの最初の1回だけ）。 */
        fun onReady()

        /** 1回分の発話が確定した。区切られた部分はつなげてある。 */
        fun onFinished(candidates: List<String>)

        /** 待っても何も話されなかった。 */
        fun onNoSpeech()

        /** 続行できないエラー（マイク権限・通信など）。service は最後に試した認識サービス。 */
        fun onError(error: Int, service: String)
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
        switchesThisSession = 0
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

    /**
     * 端末に入っている音声認識サービス。Google の GoogleTTSRecognitionService を最優先し、
     * 次に他の Google 製、その他、端末の既定、端末内認識の順。
     * 音声入力用ではない認識サービス（Claude アプリの音声アシスタント用など）は除外する。
     * まだ一度も聞き取れていないサービスがエラーになったときだけ、次のサービスで試し直す。
     */
    private class Service(val name: String, val create: (Context) -> SpeechRecognizer)

    private val services: List<Service> by lazy {
        val found = context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filterNot { cn -> EXCLUDED_PACKAGE_PREFIXES.any { cn.packageName.startsWith(it) } }
            .sortedBy { cn ->
                when {
                    cn.packageName == GOOGLE_TTS_PACKAGE -> 0
                    cn.packageName.startsWith("com.google.") -> 1
                    else -> 2
                }
            }
        val list = found.map { cn ->
            Service(cn.flattenToShortString()) { SpeechRecognizer.createSpeechRecognizer(it, cn) }
        } + Service("default") { SpeechRecognizer.createSpeechRecognizer(it) }
        val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) listOf(Service("on-device") { SpeechRecognizer.createOnDeviceSpeechRecognizer(it) }) else emptyList()
        (list + onDevice).also { all -> log("recognition services: ${all.joinToString { it.name }}") }
    }

    /** 今使っている認識サービスの番号（うまく動いたものを次回以降も使う）。 */
    private var serviceIndex = 0

    /** 実際に聞き取れた（結果を返した）サービスの番号。以後はこのサービスから切り替えない。 */
    private var provenIndex: Int? = null
    private var switchesThisSession = 0

    private val serviceName: String
        get() = services.getOrNull(serviceIndex)?.name ?: "?"

    private fun listen() {
        if (!active) return
        recognizer?.destroy()
        val service = services[serviceIndex]
        log("startListening via ${service.name}")
        val r = try {
            service.create(context)
        } catch (e: Exception) {
            log("create failed: ${e.javaClass.simpleName} ${e.message}")
            if (!switchService()) fail(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        r.setRecognitionListener(Callback(r))
        recognizer = r
        r.startListening(buildIntent())
    }

    /** 次の認識サービスがあれば切り替えて聞き直す。 */
    private fun switchService(): Boolean {
        if (switchesThisSession >= services.size - 1) return false
        switchesThisSession++
        retries = 0
        serviceIndex = (serviceIndex + 1) % services.size
        log("switching recognition service to $serviceName")
        listen()
        return true
    }

    private fun finish() {
        if (!active) return
        active = false
        clearTimers()
        recognizer?.destroy()
        recognizer = null
        log("session finished: ${segments.size} segment(s)")
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
            log("ready ($serviceName)")
            retries = 0
            if (!readyNotified) {
                readyNotified = true
                listener.onReady()
            }
        }

        override fun onBeginningOfSpeech() {
            if (!current) return
            log("beginning of speech")
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
            log("results: $list")
            if (list.isNotEmpty()) {
                segments += list
                provenIndex = serviceIndex
            }
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
            // 同じサービスで続きを聞く（すぐ作り直すと BUSY になる端末があるので少し待つ）
            recognizer?.destroy()
            recognizer = null
            main.postDelayed(retryListen, 200)
        }

        override fun onError(error: Int) {
            if (!current) return
            log("error ${errorName(error)} ($serviceName)")
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
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                    if (retries++ < 3) main.postDelayed(retryListen, 300) else handleFailure(error)
                else -> handleFailure(error)
            }
        }

        override fun onEndOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** 聞き直しても直らないエラー。 */
    private fun handleFailure(error: Int) {
        when {
            // すでに聞き取れた分があれば、それで1回分として確定する（途中のエラーで捨てない）
            segments.isNotEmpty() -> {
                log("keep ${segments.size} segment(s) despite ${errorName(error)}")
                finish()
            }
            // 通信の問題はサービスを替えても直らない
            error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fail(error)
            // このアプリ自身にマイク権限がない
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && !hasMicPermission() -> fail(error)
            // 実際に聞き取れたことのあるサービスからは切り替えない
            serviceIndex == provenIndex -> fail(error)
            else -> if (!switchService()) fail(error)
        }
    }

    private fun hasMicPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun fail(error: Int) {
        val name = serviceName
        cancel()
        listener.onError(error, name)
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
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        trace(msg)
    }

    companion object {
        const val TAG = "VoiceOp"

        /** Google の音声認識（「音声認識と合成」アプリ）。実機で正しく聞き取れたもの。 */
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

        /** 音声入力用ではない認識サービス（Claude アプリの音声アシスタント用など）。使わない。 */
        val EXCLUDED_PACKAGE_PREFIXES = listOf("com.anthropic.")

        fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT(1)"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK(2)"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO(3)"
            SpeechRecognizer.ERROR_SERVER -> "SERVER(4)"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT(5)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT(6)"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH(7)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY(8)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS(9)"
            10 -> "TOO_MANY_REQUESTS(10)"
            11 -> "SERVER_DISCONNECTED(11)"
            12 -> "LANGUAGE_NOT_SUPPORTED(12)"
            13 -> "LANGUAGE_UNAVAILABLE(13)"
            14 -> "CANNOT_CHECK_SUPPORT(14)"
            else -> "UNKNOWN($error)"
        }
    }
}
