package jp.warimashi.voiceop

/**
 * 接続先の設定。既存PWA（index.html）の FIREBASE_CONFIG / GAS_WEBHOOK_URL と同じ値。
 */
object AppConfig {
    /** Firebase Realtime Database（REST API で書き込む）。 */
    const val FIREBASE_DB_URL = "https://yotei-kanri-default-rtdb.asia-southeast1.firebasedatabase.app"

    /** 事務所タブが監視している送信先。 */
    const val ENTRIES_PATH = "warashi_entries"

    /** 顧客名の照合に使う登録済み顧客リスト（読み取りのみ）。 */
    const val CUSTOMERS_PATH = "warashi_customers"

    /** 既存PWAと同じくスプレッドシートにも記録する。不要なら空文字にする。 */
    const val GAS_WEBHOOK_URL =
        "https://script.google.com/macros/s/AKfycbwtvRVkvtuuBv-jGqExfsjSlS4w0TFLBJS5JsHPk6UXPXsfujsPg9CDSN7-cI13vSsU/exec"

    /** NFCタグを同じタップとみなす間隔（ミリ秒）。 */
    const val NFC_DEBOUNCE_MS = 1200L

    // ---- 聞き取りの時間（ミリ秒）。実機で調整する ----

    /** マイクON後、話し始めるまで待つ時間。過ぎても何も話されなければ「聞き取れませんでした」。 */
    const val SPEECH_START_TIMEOUT_MS = 8000L

    /** 話している途中の間（息継ぎ）がこれより長く続いたら、話し終わりとみなしてマイクOFF。 */
    const val SPEECH_END_SILENCE_MS = 2500L

    /** 1回のタップで聞き取る最大の長さ。 */
    const val SPEECH_MAX_SESSION_MS = 30000L

    /** 聞き取り中の合図の間隔。 */
    const val LISTENING_PULSE_INTERVAL_MS = 1500L

    /** 聞き取り中の合図に小さな「コッ」音も鳴らすか（既定はバイブのみ。音はマイクが拾って認識や無音判定に影響するおそれがある）。 */
    const val LISTENING_PULSE_SOUND = false
}
