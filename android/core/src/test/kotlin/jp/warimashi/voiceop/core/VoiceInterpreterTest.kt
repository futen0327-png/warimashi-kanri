package jp.warimashi.voiceop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInterpreterTest {

    private val customers = listOf("中川組", "西川組", "山地組", "川村建築", "丸本建材", "誠建", "アズマヤ")

    private fun say(b: EntryBuffer, vararg candidates: String) =
        VoiceInterpreter.handle(b, candidates.toList(), customers)

    @Test
    fun fullCycle_positionalThenSend() {
        val o = say(EntryBuffer(), "1234 コンガラ 2トン 2割")
        assertEquals("1234", o.buffer.plate)
        assertTrue(o.buffer.isSendable)
        assertTrue(o.speech.contains("送信できます"))

        val send = say(o.buffer, "送信")
        assertEquals(VoiceInterpreter.Action.SEND, send.action)
        val entry = send.buffer.toEntry("2026-09-27T00:00:00.000Z")
        assertEquals("1234", entry["plate"])
        assertEquals("コンガラ", entry["item"])
        assertEquals("2t", entry["vehicle"])
        assertEquals("20", entry["surcharge"])
        assertEquals("", entry["reasons"])
        assertEquals(false, entry["confirmed"])
    }

    @Test
    fun send_missingRequired_isRejected() {
        val b = say(EntryBuffer(), "コンガラ").buffer
        val o = say(b, "送信")
        assertEquals(VoiceInterpreter.Action.NONE, o.action)
        assertTrue(o.error)
        assertTrue(o.speech, o.speech.contains("サイズ、割増が足りません"))
    }

    @Test
    fun plateIsOptional() {
        val b = say(EntryBuffer(), "パス アスガラ 4トン 不良").buffer
        assertTrue(b.isSendable)
        assertEquals("", b.toEntry("t")["plate"])
    }

    @Test
    fun invalidCombination_keepsOtherFields() {
        val o = say(EntryBuffer(), "1234 コンガラ 10トン 割増なし")
        assertTrue(o.error)
        assertTrue(o.speech.contains("その組み合わせは選べません"))
        assertEquals("1234", o.buffer.plate)
        assertEquals("コンガラ", o.buffer.item)
        assertEquals("10t", o.buffer.size)
        assertNull(o.buffer.surcharge)

        val fixed = say(o.buffer, "割増 普通")
        assertEquals("normal", fixed.buffer.surcharge)
        assertTrue(fixed.buffer.isSendable)
    }

    @Test
    fun cuttingOnlyForAsugara10t() {
        assertTrue(say(EntryBuffer(), "コンガラ 10トン 切削").error)
        assertFalse(say(EntryBuffer(), "アスガラ 10トン 切削").error)
        assertTrue(say(EntryBuffer(), "アスガラ 10トン 良").error)
    }

    @Test
    fun stoneAndTileAreFixedNone() {
        val b = say(EntryBuffer(), "石 4トン").buffer
        assertEquals("none", b.surcharge)
        assertTrue(b.isSendable)

        val o = say(EntryBuffer(), "瓦 2トン 2割")
        assertTrue(o.error)
        assertEquals("none", o.buffer.surcharge)
    }

    @Test
    fun changingItemInvalidatesSurcharge() {
        val b = say(EntryBuffer(), "コンガラ 2トン 割増なし").buffer
        val o = say(b, "サイズ 10トン")
        assertTrue(o.error)
        assertNull(o.buffer.surcharge)
        assertTrue(o.speech.contains("割増を言い直してください"))
    }

    @Test
    fun leavingStoneClearsAutoSurcharge() {
        val b = say(EntryBuffer(), "石 2トン").buffer
        assertEquals("none", b.surcharge)
        val o = say(b, "品目 コンガラ")
        assertNull(o.buffer.surcharge)
    }

    @Test
    fun surchargeBeforeSize_isValidatedLater() {
        val b = say(EntryBuffer(), "割増 良").buffer
        assertEquals("good", b.surcharge)
        val ok = say(b, "コンガラ 10トン")
        assertEquals("good", ok.buffer.surcharge)
        assertTrue(ok.buffer.isSendable)

        val ng = say(b, "コンガラ 2トン")
        assertNull(ng.buffer.surcharge)
        assertTrue(ng.error)
    }

    @Test
    fun laterNumberFillsPassedPlate() {
        val b = say(EntryBuffer(), "パス コンガラ 2トン 2割").buffer
        val o = say(b, "ナンバー、1234")
        assertEquals("1234", o.buffer.plate)
        assertEquals("コンガラ", o.buffer.item)
    }

    @Test
    fun reasons_maxFour() {
        val o = say(EntryBuffer(), "理由 大きさ 有筋 鉄筋 木くず 大谷石")
        assertEquals(listOf("大きさ", "有筋", "鉄筋", "木くず"), o.buffer.reasons)
        assertTrue(o.speech.contains("4つまで"))
    }

    @Test
    fun rejectsGoToMemo() {
        var b = say(EntryBuffer(), "コンガラ 4トン 2割").buffer
        b = say(b, "拒否 赤レンガ ビニール").buffer
        assertEquals("赤レンガ、ビニール", b.toEntry("t")["memo"])
    }

    @Test
    fun customer_correctedToRegisteredName() {
        val o = say(EntryBuffer(), "客先 中川グミ")
        assertEquals("中川組", o.buffer.customer)
        assertTrue(o.buffer.customerRegistered)
        assertTrue(o.speech.contains("客先、中川組"))
    }

    @Test
    fun customer_alternativeCandidateHelps() {
        val o = say(EntryBuffer(), "客先 あずまや", "客先 東屋")
        assertEquals("アズマヤ", o.buffer.customer)
    }

    @Test
    fun customer_unregisteredKeptAsHeard() {
        val o = say(EntryBuffer(), "客先 佐藤工務店")
        assertEquals("佐藤工務店", o.buffer.customer)
        assertFalse(o.buffer.customerRegistered)
        assertTrue(o.speech.contains("登録にない名前です"))
    }

    @Test
    fun cancelClearsEverything() {
        val b = say(EntryBuffer(), "1234 コンガラ 2トン 2割").buffer
        val o = say(b, "取消")
        assertTrue(o.buffer.isEmpty)
    }

    @Test
    fun notUnderstood_keepsBuffer() {
        val b = say(EntryBuffer(), "コンガラ").buffer
        val o = say(b, "ほげほげ")
        assertTrue(o.error)
        assertEquals(b, o.buffer)
    }

    @Test
    fun firstParsableCandidateWins() {
        val o = say(EntryBuffer(), "今がら2トン", "コンガラ 2トン")
        assertEquals("コンガラ", o.buffer.item)
    }

    @Test
    fun readback() {
        val b = say(EntryBuffer(), "1234 コンガラ 2トン 2割").buffer
        val o = say(b, "確認")
        assertTrue(o.speech, o.speech.startsWith("ナンバー 1 2 3 4、コンガラ、2トン、2割"))
    }

    // ---- 割増理由: 状態に関係なく置き換え ----

    @Test
    fun reasonsOnly_onEmptyBuffer() {
        val o = say(EntryBuffer(), "大きさ")
        assertEquals(listOf("大きさ"), o.buffer.reasons)
        assertNull(o.buffer.plate)
        assertNull(o.buffer.item)
        assertNull(o.buffer.surcharge)
    }

    @Test
    fun reasonsOnly_replaceAndKeepOtherFields() {
        var b = say(EntryBuffer(), "1234 コンガラ 4トン 2割").buffer
        b = say(b, "鉄筋").buffer
        val o = say(b, "有筋 木くず")
        assertEquals(listOf("有筋", "木くず"), o.buffer.reasons)
        assertEquals("1234", o.buffer.plate)
        assertEquals("コンガラ", o.buffer.item)
        assertEquals("4t", o.buffer.size)
        assertEquals("20", o.buffer.surcharge)
    }

    @Test
    fun positionalWithReasons_readsReasonAfterSurcharge() {
        val o = say(EntryBuffer(), "コンガラ 4トン 2割 大きさ")
        assertEquals(listOf("大きさ"), o.buffer.reasons)
        assertTrue(o.speech, o.speech.startsWith("コンガラ、4トン、2割、理由、大きさ"))
    }

    @Test
    fun reasonsStayOptional_noPrompt() {
        // 理由は任意: 未入力でも送信でき、促しの読み上げもしない
        val o = say(EntryBuffer(), "コンガラ 4トン 2割")
        assertTrue(o.buffer.isSendable)
        assertTrue(o.speech, o.speech.endsWith("送信できます"))
        assertTrue(say(o.buffer, "確認").speech.endsWith("送信できます"))
        assertEquals(VoiceInterpreter.Action.SEND, say(o.buffer, "送信").action)
    }

    // ---- コピー ----

    private fun entry(
        plate: String, customer: String, time: String,
        item: String = "コンガラ", vehicle: String = "4t", surcharge: String = "20",
        reasons: List<String> = emptyList(), deleted: Boolean = false,
    ) = RecordedEntry(plate, item, vehicle, surcharge, reasons, customer, java.time.Instant.parse(time), deleted)

    private val todayEntries = listOf(
        entry("1234", "中川組", "2026-09-30T00:10:00Z", reasons = listOf("大きさ", "鉄筋")),
        entry("5678", "西川組", "2026-09-30T01:00:00Z", item = "アスガラ", vehicle = "10t", surcharge = "cutting"),
        entry("1234", "中川組", "2026-09-30T02:00:00Z", vehicle = "2t", surcharge = "40"),
        entry("9999", "アズマヤ", "2026-09-30T03:00:00Z", item = "石", vehicle = "2t", surcharge = "none"),
    )

    private fun copySay(b: EntryBuffer, vararg candidates: String) =
        VoiceInterpreter.handle(b, candidates.toList(), customers, todayEntries)

    @Test
    fun copy_withoutConditionCopiesLatest() {
        val o = copySay(EntryBuffer(), "コピー")
        assertEquals("9999", o.buffer.plate)
        assertEquals("石", o.buffer.item)
        assertEquals("アズマヤ", o.buffer.customer)
        assertFalse(o.error)
    }

    @Test
    fun copy_byCustomerFillsAllFieldsAndReadsOnce() {
        val o = copySay(EntryBuffer(), "コピー 中川組")
        val b = o.buffer
        assertEquals("1234", b.plate)
        assertEquals("コンガラ", b.item)
        assertEquals("2t", b.size)
        assertEquals("40", b.surcharge)
        assertEquals(emptyList<String>(), b.reasons)
        assertEquals("中川組", b.customer)
        assertTrue(b.customerRegistered)
        assertEquals(VoiceInterpreter.Action.NONE, o.action)
        assertTrue(o.speech, o.speech.startsWith("コピーしました。ナンバー 1 2 3 4、コンガラ、2トン、4割、客先、中川組。送信できます"))
    }

    @Test
    fun copy_byCustomerToleratesRecognitionVariants() {
        assertEquals("西川組", copySay(EntryBuffer(), "コピー 西川組さん").buffer.customer)
        assertEquals("アズマヤ", copySay(EntryBuffer(), "コピー あずまや").buffer.customer)
    }

    @Test
    fun copy_byPlateAndBoth() {
        assertEquals("西川組", copySay(EntryBuffer(), "コピー 5678").buffer.customer)
        val o = copySay(EntryBuffer(), "1234 中川組 コピー")
        assertEquals("40", o.buffer.surcharge)
        // 同じナンバーが2件あれば新しい方（02:00 の 2トン 4割）
        val latest = copySay(EntryBuffer(), "コピー 1234").buffer
        assertEquals("2t", latest.size)
        assertEquals(emptyList<String>(), latest.reasons)
    }

    @Test
    fun copy_replacesCurrentInput() {
        val start = say(EntryBuffer(), "拒否 赤レンガ").buffer
        val o = copySay(start, "コピー 西川組")
        assertEquals("cutting", o.buffer.surcharge)
        assertEquals(emptyList<String>(), o.buffer.rejects)
    }

    @Test
    fun copy_noMatch() {
        listOf("コピー 丸本建材", "コピー 4321", "コピー 西川組 1234").forEach {
            val o = copySay(EntryBuffer(), it)
            assertEquals(it, VoiceInterpreter.NO_MATCH, o.speech)
            assertTrue(o.error)
            assertTrue(o.buffer.isEmpty)
        }
        assertEquals(VoiceInterpreter.NO_MATCH, say(EntryBuffer(), "コピー").speech)
    }

    @Test
    fun copy_thenSendUsesNormalFlow() {
        val copied = copySay(EntryBuffer(), "コピー 中川組").buffer
        val send = say(copied, "送信")
        assertEquals(VoiceInterpreter.Action.SEND, send.action)
        val e = send.buffer.toEntry("t")
        assertEquals("1234", e["plate"])
        assertEquals("コンガラ", e["item"])
        assertEquals("2t", e["vehicle"])
        assertEquals("40", e["surcharge"])
        assertEquals("中川組", e["customer"])
    }

    @Test
    fun copy_thenCorrectWithLabeledUtterance() {
        val copied = copySay(EntryBuffer(), "コピー 中川組").buffer
        val fixed = say(copied, "サイズ 4トン")
        assertEquals("4t", fixed.buffer.size)
        assertEquals("40", fixed.buffer.surcharge)
        assertEquals("1234", fixed.buffer.plate)
        val plate = say(fixed.buffer, "ナンバー 4321")
        assertEquals("4321", plate.buffer.plate)
        assertEquals(VoiceInterpreter.Action.SEND, say(plate.buffer, "送信").action)
    }

    @Test
    fun copy_stoneKeepsAutoSurcharge() {
        val b = copySay(EntryBuffer(), "コピー アズマヤ").buffer
        assertEquals("none", b.surcharge)
        assertTrue(b.surchargeAuto)
    }

    @Test
    fun isCopy() {
        assertTrue(VoiceInterpreter.isCopy(listOf("コピー 中川組")))
        assertFalse(VoiceInterpreter.isCopy(listOf("送信")))
        assertFalse(VoiceInterpreter.isCopy(listOf("今日はいい天気", "送信")))
    }
}
