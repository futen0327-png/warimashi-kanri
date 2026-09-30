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

    // ---- 「理由をどうぞ」 ----

    @Test
    fun reasonPrompt_afterSurchargeWithoutReason() {
        listOf("コンガラ 4トン 2割", "コンガラ 4トン 4割", "コンガラ 10トン 不良").forEach {
            val o = say(EntryBuffer(), it)
            assertTrue(o.speech, o.speech.endsWith("送信できます。理由をどうぞ"))
        }
    }

    @Test
    fun reasonPrompt_notForOtherSurcharges() {
        listOf("コンガラ 4トン 割増なし", "コンガラ 10トン 良", "コンガラ 10トン 普通", "アスガラ 10トン 切削").forEach {
            val o = say(EntryBuffer(), it)
            assertFalse(o.speech, o.speech.contains(VoiceInterpreter.REASON_PROMPT))
        }
    }

    @Test
    fun positionalWithReasons_readsReasonAfterSurcharge() {
        val o = say(EntryBuffer(), "コンガラ 4トン 2割 大きさ")
        assertEquals(listOf("大きさ"), o.buffer.reasons)
        assertTrue(o.speech, o.speech.startsWith("コンガラ、4トン、2割、理由、大きさ"))
    }

    @Test
    fun reasonPrompt_notWhenReasonGiven() {
        assertFalse(say(EntryBuffer(), "コンガラ 4トン 2割 大きさ").speech.contains(VoiceInterpreter.REASON_PROMPT))
        val b = say(EntryBuffer(), "コンガラ 4トン 2割").buffer
        assertFalse(say(b, "大きさ").speech.contains(VoiceInterpreter.REASON_PROMPT))
    }

    @Test
    fun reasonPrompt_onReadback() {
        val b = say(EntryBuffer(), "1234 コンガラ 2トン 2割").buffer
        assertTrue(say(b, "確認").speech.endsWith("送信できます。理由をどうぞ"))
        val withReason = say(b, "鉄筋").buffer
        assertFalse(say(withReason, "確認").speech.contains(VoiceInterpreter.REASON_PROMPT))
    }

    @Test
    fun reasonPrompt_notWithErrors() {
        // 選べない組み合わせは言い直しが先
        val o = say(EntryBuffer(), "コンガラ 2トン 切削")
        assertTrue(o.error)
        assertFalse(o.speech.contains(VoiceInterpreter.REASON_PROMPT))
    }
}
