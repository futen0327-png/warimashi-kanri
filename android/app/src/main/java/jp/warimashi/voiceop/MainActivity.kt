package jp.warimashi.voiceop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.warimashi.voiceop.ui.MainScreen
import jp.warimashi.voiceop.ui.NfcStatus

/**
 * 画面は1つだけ。NFCタグのタップでマイクON/OFFを切り替える。
 *
 * NFC は画面が点いていてアプリが前面にあるときしか読めないため、
 * このアクティビティ表示中は画面を消さない（ポケットモードでは最低輝度＋タッチ無効）。
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val vm: MainViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private var lastTapAt = 0L

    private var nfcStatus by mutableStateOf(NfcStatus.UNSUPPORTED)
    private var micGranted by mutableStateOf(false)
    private var pocketMode by mutableStateOf(false)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) micPermission.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            MainScreen(
                state = state,
                nfcStatus = nfcStatus,
                micGranted = micGranted,
                pocketMode = pocketMode,
                onMic = { if (micGranted) vm.onTrigger() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onCommand = vm::onCommand,
                onPocketMode = ::applyPocketMode,
                onOpenNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                onRefreshCustomers = vm::refreshCustomers,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        nfcStatus = when {
            adapter == null -> NfcStatus.UNSUPPORTED
            !adapter.isEnabled -> NfcStatus.DISABLED
            else -> NfcStatus.READY
        }
        // Foreground Dispatch の代わりにリーダーモードを使う:
        // タグの種類を問わず受け取れ、「未対応のタグです」等のシステム表示や音が出ない
        adapter?.takeIf { it.isEnabled }?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) },
        )
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        vm.onPause()
        super.onPause()
    }

    /** NFCタグ検出（バインダースレッドで呼ばれる）。 */
    override fun onTagDiscovered(tag: Tag?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapAt < AppConfig.NFC_DEBOUNCE_MS) return
        lastTapAt = now
        runOnUiThread {
            if (micGranted) vm.onTrigger()
        }
    }

    private fun applyPocketMode(on: Boolean) {
        pocketMode = on
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}
