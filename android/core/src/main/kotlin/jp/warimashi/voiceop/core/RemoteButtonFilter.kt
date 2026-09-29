package jp.warimashi.voiceop.core

/**
 * Bluetooth リモコン（BTselfie E03 など）のキーから「マイクON/OFFの合図」を取り出す判定。
 *
 * このリモコンはボタン1回ごとに VOLUME_UP と VOLUME_DOWN を交互に送ってくる（どちらも同じ「1回押した」）。
 * 1回の押下で DOWN と UP が数ms差で届くので、DOWN（repeatCount=0）だけを合図にし、
 * さらに前回の合図から [debounceMs] 未満のものは捨てる。
 *
 * 端末本体の音量ボタン（gpio-keys）はデバイス名で区別して、従来どおり音量操作に回す。
 * Android に依存しないよう、キーコードなどは Int で受け取る（値は android.view.KeyEvent と同じ）。
 */
class RemoteButtonFilter(
    /** 発生元デバイス名にこれを含むキーだけを対象にする（大文字小文字は区別しない）。 */
    private val deviceNameContains: String,
    private val debounceMs: Long,
) {
    enum class Decision {
        /** 対象外のキー。いつもどおりシステム・画面に渡す。 */
        PASS,
        /** リモコンからのキーだが合図ではない（UP・長押しの繰り返し・連続）。消費して何もしない。 */
        CONSUME,
        /** マイクON/OFFの合図。消費して切り替える。 */
        TOGGLE,
    }

    /** 最後に TOGGLE を返した時刻。 */
    private var lastToggleAt: Long? = null

    /**
     * @param enabled 設定でリモコン操作がONか。OFFなら常に [Decision.PASS]
     * @param now 単調増加する時刻（SystemClock.elapsedRealtime()）
     */
    fun decide(
        enabled: Boolean,
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        deviceName: String?,
        now: Long,
    ): Decision {
        if (!enabled) return Decision.PASS
        if (deviceName == null || !deviceName.contains(deviceNameContains, ignoreCase = true)) return Decision.PASS
        if (keyCode != KEYCODE_VOLUME_UP && keyCode != KEYCODE_VOLUME_DOWN) return Decision.CONSUME
        if (action != ACTION_DOWN || repeatCount != 0) return Decision.CONSUME
        val last = lastToggleAt
        if (last != null && now - last < debounceMs) return Decision.CONSUME
        lastToggleAt = now
        return Decision.TOGGLE
    }

    companion object {
        // android.view.KeyEvent と同じ値
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }
}
