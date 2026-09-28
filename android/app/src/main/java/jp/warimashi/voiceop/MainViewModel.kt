package jp.warimashi.voiceop

import android.app.Application
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import jp.warimashi.voiceop.core.EntryBuffer
import jp.warimashi.voiceop.core.SurchargeRules
import jp.warimashi.voiceop.core.VoiceInterpreter
import jp.warimashi.voiceop.data.FirebaseRestClient
import jp.warimashi.voiceop.data.GasClient
import jp.warimashi.voiceop.voice.Cues
import jp.warimashi.voiceop.voice.Speaker
import jp.warimashi.voiceop.voice.SpeechInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { IDLE, STARTING, LISTENING, SENDING }

data class UiState(
    val buffer: EntryBuffer = EntryBuffer(),
    val phase: Phase = Phase.IDLE,
    /** 直近の認識結果（第1候補）。 */
    val lastHeard: String = "",
    /** 直近の読み上げ内容。 */
    val lastSpeech: String = "",
    val lastWasError: Boolean = false,
    /** 取得済みの登録顧客数（未取得なら null）。 */
    val customerCount: Int? = null,
    /** この端末から送信した件数（今回の起動中）。 */
    val sentCount: Int = 0,
    val lastSent: String = "",
    /** 音声認識の診断ログ（新しい順）。画面に出してスクリーンショットで共有できるようにする。 */
    val diag: List<String> = emptyList(),
    /** 認識サービスが使えず、Android 標準の音声入力画面で聞き取っているか。 */
    val usingSystemDialog: Boolean = false,
)

/**
 * タップ → 発話 → バッファ反映 → 読み上げ、「送信」で Firebase へ push、の流れを管理する。
 */
class MainViewModel(app: Application) : AndroidViewModel(app), SpeechInput.Listener {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val speaker = Speaker(app)
    private val cues = Cues(app)
    private val speech = SpeechInput(app, this) { trace(it) }

    /** Activity に「Android 標準の音声入力画面を開いて」と頼むイベント。 */
    private val _launchSystemDialog = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val launchSystemDialog: SharedFlow<Unit> = _launchSystemDialog

    /** 認識サービスが使えなかった端末では、以降は標準の音声入力画面を使う。 */
    private var useSystemDialog = false
    private var systemDialogOpen = false
    private val firebase = FirebaseRestClient(AppConfig.FIREBASE_DB_URL)
    private val gas = GasClient(AppConfig.GAS_WEBHOOK_URL)

    private var customers: List<String> = emptyList()

    init {
        refreshCustomers()
    }

    // ------------------------------------------------------------------
    // マイクON/OFF（NFCタップ・画面のマイクボタン共通）
    // ------------------------------------------------------------------

    fun onTrigger() {
        when (_state.value.phase) {
            Phase.SENDING, Phase.STARTING -> Unit
            Phase.LISTENING -> speech.stop()
            Phase.IDLE -> startListening()
        }
    }

    /** NFCタップ（MainActivity で連続タップの判定を通ったもの）。 */
    fun onNfcTap(seq: Int, micGranted: Boolean) {
        if (!micGranted) {
            logNfc(seq, "無視", "マイクの使用が許可されていない")
            return
        }
        // 聞き取り中・標準の音声入力画面の表示中・起動中・送信中のタップはすべて無視する
        // （聞き取りの終了は画面のマイクボタンか、無音での自動終了で行う）
        val busy = when {
            systemDialogOpen -> "標準の音声入力画面を表示中"
            _state.value.phase == Phase.LISTENING -> "聞き取り中"
            _state.value.phase == Phase.STARTING -> "聞き取り開始処理中"
            _state.value.phase == Phase.SENDING -> "送信中"
            else -> null
        }
        if (busy != null) {
            logNfc(seq, "無視", busy)
            return
        }
        logNfc(seq, "受理", "phase=${_state.value.phase}")
        startListening()
    }

    /** NFC受信のログ（Logcat と画面の診断ログの両方に出す）。 */
    fun logNfc(seq: Int, verdict: String, reason: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN).format(Date())
        val msg = "NFC #$seq $time $verdict: $reason"
        Log.i(NFC_TAG, msg)
        trace(msg)
    }

    private fun startListening() {
        speaker.stop()
        if (useSystemDialog || !speech.isAvailable) {
            // 標準の音声入力画面は自分で開始音を鳴らすので、こちらの音は重ねない
            cues.listenStartVibrationOnly()
            openSystemDialog()
            return
        }
        _state.update { it.copy(phase = Phase.STARTING) }
        cues.listenStart()
        viewModelScope.launch {
            // 開始音を認識に拾わせないよう少し待つ
            delay(250)
            if (_state.value.phase != Phase.STARTING) return@launch // 待っている間に画面が消えた
            speech.start()
        }
    }

    /** 画面が消える・アプリが裏に回るときは聞き取りをやめる。 */
    fun onPause() {
        if (systemDialogOpen) return // 標準の音声入力画面が前に出ただけ
        if (_state.value.phase == Phase.LISTENING || _state.value.phase == Phase.STARTING) {
            speech.cancel()
            stopPulse()
            _state.update { it.copy(phase = Phase.IDLE) }
        }
    }

    override fun onReady() {
        _state.update { it.copy(phase = Phase.LISTENING) }
        startPulse()
    }

    override fun onFinished(candidates: List<String>) {
        stopPulse()
        cues.listenEnd()
        _state.update { it.copy(phase = Phase.IDLE, lastHeard = candidates.first()) }
        interpret(candidates)
    }

    override fun onNoSpeech() {
        stopPulse()
        _state.update { it.copy(phase = Phase.IDLE) }
        say("声が聞き取れませんでした。もう一度タップしてください", error = true)
    }

    override fun onError(error: Int, service: String) {
        stopPulse()
        _state.update { it.copy(phase = Phase.IDLE) }
        Log.w(SpeechInput.TAG, "speech failed: ${SpeechInput.errorName(error)} via $service")
        trace("speech failed: ${SpeechInput.errorName(error)} via $service")
        if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            // どの認識サービスも使えなかった。Android 標準の音声入力画面で聞き直す
            trace("fallback: system voice input dialog")
            useSystemDialog = true
            openSystemDialog()
            return
        }
        val msg = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイクの使用が許可されていません"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "音声認識に失敗しました。電波を確認してください"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声認識が混み合っています。もう一度タップしてください"
            12, 13 -> "日本語の音声認識が使えません。端末の音声入力の設定を確認してください"
            else -> "音声認識エラーです。もう一度タップしてください"
        }
        // 画面には原因調査用にエラーの種類と認識サービスも出す（読み上げはしない）
        say(msg, error = true, detail = "［${SpeechInput.errorName(error)} / $service］")
    }

    // ------------------------------------------------------------------
    // Android 標準の音声入力画面（認識サービスが使えない端末向けの予備）
    // ------------------------------------------------------------------

    private fun openSystemDialog() {
        systemDialogOpen = true
        _state.update { it.copy(phase = Phase.LISTENING, usingSystemDialog = true) }
        _launchSystemDialog.tryEmit(Unit)
    }

    /** 標準の音声入力画面の結果。null はキャンセル・失敗。 */
    fun onSystemDialogResult(candidates: List<String>?) {
        systemDialogOpen = false
        trace("system dialog result: ${candidates ?: "none"}")
        if (candidates.isNullOrEmpty()) onNoSpeech() else onFinished(candidates)
    }

    fun onSystemDialogUnavailable() {
        systemDialogOpen = false
        trace("system dialog unavailable")
        _state.update { it.copy(phase = Phase.IDLE) }
        say("この端末では音声入力が使えません。Google アプリか音声入力の設定を確認してください", error = true)
    }

    private fun trace(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
        _state.update { it.copy(diag = (listOf("$time $msg") + it.diag).take(30)) }
    }

    // 聞き取り中は一定間隔で合図し、ポケットの中でもマイクが開いているとわかるようにする
    private var pulseJob: Job? = null

    private fun startPulse() {
        pulseJob?.cancel()
        pulseJob = viewModelScope.launch {
            while (isActive) {
                delay(AppConfig.LISTENING_PULSE_INTERVAL_MS)
                cues.listening(AppConfig.LISTENING_PULSE_SOUND)
            }
        }
    }

    private fun stopPulse() {
        pulseJob?.cancel()
        pulseJob = null
    }

    // ------------------------------------------------------------------
    // 画面ボタン（送信・取消・確認）も音声コマンドと同じ経路で処理する
    // ------------------------------------------------------------------

    fun onCommand(word: String) {
        if (_state.value.phase == Phase.SENDING) return
        interpret(listOf(word))
    }

    private fun interpret(candidates: List<String>) {
        trace("heard: $candidates")
        val outcome = VoiceInterpreter.handle(_state.value.buffer, candidates, customers)
        _state.update { it.copy(buffer = outcome.buffer) }
        when (outcome.action) {
            VoiceInterpreter.Action.SEND -> send(outcome.buffer)
            VoiceInterpreter.Action.NONE -> say(outcome.speech, outcome.error)
        }
    }

    // ------------------------------------------------------------------
    // 送信
    // ------------------------------------------------------------------

    private fun send(buffer: EntryBuffer) {
        val entry = buffer.toEntry(isoNow())
        _state.update { it.copy(phase = Phase.SENDING) }
        viewModelScope.launch {
            val result = runCatching { firebase.push(AppConfig.ENTRIES_PATH, entry) }
            if (result.isSuccess) {
                // 送信完了後はバッファを自動クリアして次の1台に備える（設計書6章）
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        buffer = EntryBuffer(),
                        sentCount = it.sentCount + 1,
                        lastSent = summary(buffer),
                    )
                }
                cues.sent()
                say("送信しました")
                launch { gas.record(entry) }
                refreshCustomers()
            } else {
                _state.update { it.copy(phase = Phase.IDLE) }
                val e = result.exceptionOrNull()
                val msg = if (e is FirebaseRestClient.HttpException && (e.code == 401 || e.code == 403)) {
                    "送信できませんでした。書き込みの権限がありません"
                } else {
                    "送信できませんでした。電波を確認して、もう一度、送信と言ってください"
                }
                say(msg, error = true)
            }
        }
    }

    fun refreshCustomers() {
        viewModelScope.launch {
            runCatching { firebase.fetchStringValues(AppConfig.CUSTOMERS_PATH) }
                .onSuccess { list ->
                    customers = list
                    _state.update { it.copy(customerCount = list.size) }
                }
        }
    }

    private fun say(text: String, error: Boolean = false, detail: String = "") {
        if (error) cues.error()
        _state.update { it.copy(lastSpeech = text + detail, lastWasError = error) }
        trace("say: $text$detail")
        speaker.speak(text)
    }

    private fun summary(b: EntryBuffer): String = listOfNotNull(
        b.plate ?: "----",
        b.item,
        b.size,
        b.surcharge?.let { SurchargeRules.label(it) },
        b.customer,
    ).joinToString(" ")

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    companion object {
        /** NFC受信ログのタグ（adb logcat -s VoiceOpNfc で絞り込める）。 */
        const val NFC_TAG = "VoiceOpNfc"
    }

    override fun onCleared() {
        stopPulse()
        speech.destroy()
        speaker.shutdown()
        cues.release()
    }
}
