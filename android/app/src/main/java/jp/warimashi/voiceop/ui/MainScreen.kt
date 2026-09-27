package jp.warimashi.voiceop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.warimashi.voiceop.Phase
import jp.warimashi.voiceop.UiState
import jp.warimashi.voiceop.core.EntryBuffer
import jp.warimashi.voiceop.core.SurchargeRules

enum class NfcStatus { READY, DISABLED, UNSUPPORTED }

private val Bg = Color(0xFF101418)
private val Panel = Color(0xFF1B2229)
private val Border = Color(0xFF2E3842)
private val Text1 = Color(0xFFE8EDF2)
private val Text2 = Color(0xFF93A1AE)
private val Accent = Color(0xFFF5A623)
private val Ok = Color(0xFF4CAF7D)
private val Err = Color(0xFFE5534B)
private val Listening = Color(0xFF3D8BFD)

@Composable
fun MainScreen(
    state: UiState,
    nfcStatus: NfcStatus,
    micGranted: Boolean,
    pocketMode: Boolean,
    onMic: () -> Unit,
    onCommand: (String) -> Unit,
    onPocketMode: (Boolean) -> Unit,
    onOpenNfcSettings: () -> Unit,
    onRefreshCustomers: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
        Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusBar(state, nfcStatus, micGranted, onOpenNfcSettings, onRefreshCustomers)
                BufferCard(state.buffer)
                SpeechCard(state)

                Button(
                    onClick = onMic,
                    enabled = state.phase != Phase.SENDING,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.phase == Phase.LISTENING) Listening else Accent,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        if (state.phase == Phase.LISTENING) "聞き取り中… タップで終了" else "マイク（NFCタップと同じ）",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCommand("取消") }, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("取消", fontSize = 16.sp)
                    }
                    OutlinedButton(onClick = { onCommand("確認") }, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("読み上げ", fontSize = 16.sp)
                    }
                    Button(
                        onClick = { onCommand("送信") },
                        enabled = state.phase != Phase.SENDING,
                        modifier = Modifier.weight(1.3f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ok, contentColor = Color.Black),
                    ) {
                        Text(if (state.phase == Phase.SENDING) "送信中…" else "送信", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(onClick = { onPocketMode(true) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("ポケットモード（画面を暗くしてタッチ無効）", fontSize = 15.sp)
                }
                if (state.lastSent.isNotEmpty()) {
                    Text(
                        "送信済み ${state.sentCount}件 ／ 直前: ${state.lastSent}",
                        color = Text2, fontSize = 13.sp,
                    )
                }
                HelpCard()
            }

            if (pocketMode) PocketOverlay(state, onExit = { onPocketMode(false) })
        }
    }
}

@Composable
private fun StatusBar(
    state: UiState,
    nfcStatus: NfcStatus,
    micGranted: Boolean,
    onOpenNfcSettings: () -> Unit,
    onRefreshCustomers: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val (phaseText, phaseColor) = when (state.phase) {
            Phase.IDLE -> "待機中" to Text2
            Phase.STARTING, Phase.LISTENING -> "聞き取り中" to Listening
            Phase.SENDING -> "送信中" to Accent
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(phaseText, phaseColor)
            when (nfcStatus) {
                NfcStatus.READY -> Chip("NFC 待受中", Ok)
                NfcStatus.DISABLED -> Chip("NFC オフ（設定を開く）", Err, onOpenNfcSettings)
                NfcStatus.UNSUPPORTED -> Chip("NFC 非対応", Err)
            }
            Chip(
                state.customerCount?.let { "顧客 ${it}件" } ?: "顧客 未取得",
                if (state.customerCount != null) Text2 else Err,
                onRefreshCustomers,
            )
        }
        if (!micGranted) Text("マイクの使用を許可してください", color = Err, fontSize = 14.sp)
    }
}

@Composable
private fun Chip(text: String, color: Color, onClick: (() -> Unit)? = null) {
    val m = Modifier
        .border(1.dp, color, RoundedCornerShape(50))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 10.dp, vertical = 4.dp)
    Box(m) { Text(text, color = color, fontSize = 12.sp) }
}

@Composable
private fun BufferCard(b: EntryBuffer) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ナンバー", color = Text2, fontSize = 13.sp, modifier = Modifier.width(72.dp))
            Text(b.plate ?: "----", color = Text1, fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        FieldRow("品目", b.item, required = true)
        FieldRow("サイズ", b.size, required = true)
        FieldRow("割増", b.surcharge?.let { SurchargeRules.label(it) }, required = true)
        FieldRow("理由", b.reasons.joinToString("・").ifEmpty { null })
        FieldRow("客先", b.customer?.let { if (b.customerRegistered) it else "$it（未登録）" })
        FieldRow("拒否", b.rejects.joinToString("・").ifEmpty { null })
        Text(
            if (b.isSendable) "送信できます" else "未入力: " + b.missingRequired.joinToString("・") { it.label }.ifEmpty { "割増の組み合わせ" },
            color = if (b.isSendable) Ok else Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FieldRow(label: String, value: String?, required: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Text2, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(
            value ?: if (required) "未入力" else "—",
            color = when {
                value != null -> Text1
                required -> Accent
                else -> Text2
            },
            fontSize = 22.sp,
            fontWeight = if (value != null) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SpeechCard(state: UiState) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("聞き取り: " + state.lastHeard.ifEmpty { "—" }, color = Text2, fontSize = 14.sp)
        Text(
            "読み上げ: " + state.lastSpeech.ifEmpty { "—" },
            color = if (state.lastWasError) Err else Text1, fontSize = 15.sp,
        )
    }
}

@Composable
private fun HelpCard() {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val lines = listOf(
            "タップ → ポン → 話す（聞き取り中はブルッと小さく振動。2.5秒黙ると ピピッ で終了）",
            "まとめて:「1234、コンガラ、2トン、2割」 ／ 不明は「パス」",
            "項目ごと:「ナンバー 1234」「理由 大きさ 鉄筋」「客先 中川組」「拒否 赤レンガ」",
            "「送信」で送る ／「取消」で全部消す ／「確認」で読み上げ",
        )
        lines.forEach { Text(it, color = Text2, fontSize = 13.sp) }
    }
}

/** ポケット内の誤タッチ防止。長押しで解除。 */
@Composable
private fun PocketOverlay(state: UiState, onExit: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onExit() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (state.phase) {
                    Phase.LISTENING, Phase.STARTING -> "聞き取り中"
                    Phase.SENDING -> "送信中"
                    Phase.IDLE -> "ポケットモード"
                },
                color = Color(0xFF555555), fontSize = 20.sp,
            )
            Text("長押しで解除", color = Color(0xFF333333), fontSize = 14.sp)
        }
    }
}
