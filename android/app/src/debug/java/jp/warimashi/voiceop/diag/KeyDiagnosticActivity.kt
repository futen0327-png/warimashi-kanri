package jp.warimashi.voiceop.diag

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * キー入力の診断画面（debug ビルド専用）。
 *
 * 画面ON・前面のとき、この画面に届いたキーをすべてログに出す。
 * 画面OFF時の確認は KeyDiagnosticService（MediaSession）で行い、同じログに記録される。
 *
 * リモコンの Enter などでボタンが押されてしまわないよう、画面は Compose ではなく
 * フォーカスを一切取らない素の View で組んでいる（キーはすべてアクティビティに届く）。
 */
@Suppress("UseSwitchCompatOrMaterialCode")
class KeyDiagnosticActivity : ComponentActivity() {

    private lateinit var deviceText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var consumeSwitch: Switch
    private lateinit var silentSwitch: Switch
    private lateinit var serviceButton: Button

    private val inputManager by lazy { getSystemService(InputManager::class.java) }

    private val logListener: () -> Unit = { renderLog() }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = onDeviceChanged("接続", deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) = onDeviceChanged("切断", deviceId)
        override fun onInputDeviceChanged(deviceId: Int) = onDeviceChanged("変更", deviceId)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        KeyLog.add("Activity", "通知の許可: ${if (granted) "OK" else "拒否（診断は動くが通知が表示されない）"}")
        startService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        refreshDevices()
        KeyLog.add("Activity", "診断画面を開いた (Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} / ${Build.MANUFACTURER} ${Build.MODEL})")
    }

    override fun onStart() {
        super.onStart()
        KeyLog.addListener(logListener)
        inputManager.registerInputDeviceListener(deviceListener, null)
        refreshDevices()
        renderLog()
        updateServiceButton()
    }

    override fun onStop() {
        KeyLog.removeListener(logListener)
        inputManager.unregisterInputDeviceListener(deviceListener)
        super.onStop()
    }

    // ---- キー受信 ----

    /** 戻るキーは消費しない（消費ONでも画面を閉じられるように）。 */
    private fun shouldConsume(e: KeyEvent) = consumeSwitch.isChecked && e.keyCode != KeyEvent.KEYCODE_BACK

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyLog.key("Activity.dispatchKeyEvent", event)
        if (shouldConsume(event)) {
            // View には回さず onKeyDown / onKeyUp だけ呼んで、システム（音量変更など）にも渡さない。
            event.dispatch(this, window.decorView.keyDispatcherState, this)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        KeyLog.key("Activity.onKeyDown", event)
        return if (shouldConsume(event)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        KeyLog.key("Activity.onKeyUp", event)
        return if (shouldConsume(event)) true else super.onKeyUp(keyCode, event)
    }

    // ---- 入力デバイス一覧 ----

    private fun onDeviceChanged(what: String, deviceId: Int) {
        val name = InputDevice.getDevice(deviceId)?.name ?: "(取得不可)"
        KeyLog.add("InputDevice", "$what id=$deviceId \"$name\"")
        refreshDevices()
    }

    private fun refreshDevices() {
        val probeKeys = intArrayOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        )
        val sb = StringBuilder()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            val external = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (d.isExternal) "外部" else "内蔵"
            } else "?"
            val keyboard = when (d.keyboardType) {
                InputDevice.KEYBOARD_TYPE_ALPHABETIC -> "英字KB"
                InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC -> "非英字KB"
                else -> "KBなし"
            }
            val has = d.hasKeys(*probeKeys)
            val hasText = probeKeys.indices
                .filter { has[it] }
                .joinToString(",") { KeyEvent.keyCodeToString(probeKeys[it]).removePrefix("KEYCODE_") }
            sb.append("id=$id \"${d.name}\"\n")
            sb.append("   src=${KeyLog.sourceToString(d.sources)} $external $keyboard")
            if (d.isVirtual) sb.append(" 仮想")
            sb.append(" vid=0x${Integer.toHexString(d.vendorId)} pid=0x${Integer.toHexString(d.productId)}\n")
            if (hasText.isNotEmpty()) sb.append("   持つキー: $hasText\n")
        }
        deviceText.text = sb.toString().trimEnd().ifEmpty { "(入力デバイスなし)" }
    }

    // ---- ログ ----

    private fun renderLog() {
        logText.text = KeyLog.text().ifEmpty { "(ログなし) リモコンのボタンを押してください" }
        logScroll.post { logScroll.scrollTo(0, logText.height) }
    }

    private fun copyLog() {
        val text = buildString {
            append("== 入力デバイス ==\n")
            append(deviceText.text)
            append("\n\n== ログ ==\n")
            append(KeyLog.text())
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("キー診断ログ", text))
        Toast.makeText(this, "ログをコピーしました", Toast.LENGTH_SHORT).show()
    }

    // ---- 画面OFF診断（サービス） ----

    private fun toggleService() {
        if (KeyDiagnosticService.running) {
            KeyDiagnosticService.stop(this)
            serviceButton.postDelayed({ updateServiceButton() }, 300)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startService()
    }

    private fun startService() {
        KeyDiagnosticService.start(this, silentSwitch.isChecked)
        serviceButton.postDelayed({ updateServiceButton() }, 300)
    }

    private fun updateServiceButton() {
        serviceButton.text = if (KeyDiagnosticService.running) "画面OFF診断を停止" else "画面OFF診断を開始"
        silentSwitch.isEnabled = !KeyDiagnosticService.running
    }

    // ---- 画面 ----

    private fun buildLayout(): View {
        val fg = Color.rgb(0xE4, 0xE6, 0xE8)
        val sub = Color.rgb(0x9A, 0xA0, 0xAA)
        val accent = Color.rgb(0xF5, 0xA6, 0x23)
        val pad = dp(12)

        fun label(text: String, color: Int = sub, size: Float = 12f) = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            setPadding(0, dp(8), 0, dp(2))
        }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            setOnClickListener { onClick() }
            noFocus()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEach { addView(it) }
        }

        fun switch(text: String) = Switch(this).apply {
            this.text = text
            setTextColor(fg)
            textSize = 13f
            noFocus()
            setPadding(0, dp(4), 0, dp(4))
        }

        deviceText = TextView(this).apply {
            setTextColor(fg)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        val deviceScroll = ScrollView(this).apply {
            noFocus()
            addView(deviceText)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
        }

        consumeSwitch = switch("キーをこの画面で消費する（音量などが変わらない。戻るキーは除く）").apply {
            setOnCheckedChangeListener { _, on -> KeyLog.add("Activity", "キー消費: ${if (on) "ON" else "OFF"}") }
        }
        silentSwitch = switch("画面OFF診断で無音を再生する（メディアキーの届け先の優先を取る）")
        serviceButton = button("画面OFF診断を開始") { toggleService() }

        logText = TextView(this).apply {
            setTextColor(fg)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply {
            noFocus()
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.rgb(0x1C, 0x1E, 0x21))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(0x10, 0x14, 0x18))
            fitsSystemWindows = true
            // 子にフォーカスを渡さない: リモコンのキーでボタンが押されないようにする
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            noFocus()
            addView(label("キー診断（debug）", accent, 16f))
            addView(row(label("接続中の入力デバイス").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }, button("更新") { refreshDevices(); KeyLog.add("Activity", "デバイス一覧を更新") }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }))
            addView(deviceScroll)
            addView(consumeSwitch)
            addView(silentSwitch)
            addView(row(serviceButton))
            addView(row(button("ログをクリア") { KeyLog.clear() }, button("ログをコピー") { copyLog() }))
            addView(logScroll)
        }
    }

    private fun View.noFocus() {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
