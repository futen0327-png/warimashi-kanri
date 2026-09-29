package jp.warimashi.voiceop.diag

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 診断ログ（debug ビルド専用）。アクティビティとサービスの両方から書き込み、画面に表示する。
 * プロセス内のメモリにだけ持つ。サービスが動いている間はプロセスが生きているので、
 * 画面OFF中に記録した分も後で画面を開けば見られる。logcat（タグ KeyDiag）にも同じ行を出す。
 */
object KeyLog {
    private const val TAG = "KeyDiag"
    private const val MAX_LINES = 3000

    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(where: String, message: String) {
        val line = "${timeFormat.format(Date())} [$where] $message"
        Log.i(TAG, line)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        notifyListeners()
    }

    fun key(where: String, e: KeyEvent) = add(where, describe(e))

    fun clear() {
        synchronized(lines) { lines.clear() }
        notifyListeners()
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    /** リスナーはメインスレッドで呼ばれる。 */
    fun addListener(l: () -> Unit) = main.post { listeners.add(l) }
    fun removeListener(l: () -> Unit) = main.post { listeners.remove(l) }

    private fun notifyListeners() {
        main.post { listeners.toList().forEach { it() } }
    }

    fun describe(e: KeyEvent): String {
        val action = when (e.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> "action=${e.action}"
        }
        val deviceName = InputDevice.getDevice(e.deviceId)?.name ?: "(不明)"
        return "$action ${KeyEvent.keyCodeToString(e.keyCode)}(${e.keyCode})" +
            " repeat=${e.repeatCount} scan=${e.scanCode}" +
            " src=${sourceToString(e.source)} dev=${e.deviceId} \"$deviceName\"" +
            if (e.isCanceled) " CANCELED" else ""
    }

    private val SOURCE_NAMES = listOf(
        InputDevice.SOURCE_KEYBOARD to "KEYBOARD",
        InputDevice.SOURCE_DPAD to "DPAD",
        InputDevice.SOURCE_GAMEPAD to "GAMEPAD",
        InputDevice.SOURCE_JOYSTICK to "JOYSTICK",
        InputDevice.SOURCE_TOUCHSCREEN to "TOUCHSCREEN",
        InputDevice.SOURCE_MOUSE to "MOUSE",
        InputDevice.SOURCE_STYLUS to "STYLUS",
        InputDevice.SOURCE_TOUCHPAD to "TOUCHPAD",
        InputDevice.SOURCE_TRACKBALL to "TRACKBALL",
        InputDevice.SOURCE_ROTARY_ENCODER to "ROTARY",
        InputDevice.SOURCE_HDMI to "HDMI",
        InputDevice.SOURCE_SENSOR to "SENSOR",
    )

    /** source のビットを名前にする。例: 0x101(KEYBOARD) */
    fun sourceToString(source: Int): String {
        val names = SOURCE_NAMES
            .filter { (bits, _) -> source and bits == bits }
            .map { it.second }
        return "0x${Integer.toHexString(source)}(${names.joinToString("|").ifEmpty { "-" }})"
    }
}
