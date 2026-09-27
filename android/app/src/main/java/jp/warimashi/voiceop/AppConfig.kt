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
}
