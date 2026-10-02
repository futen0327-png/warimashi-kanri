package jp.warimashi.voiceop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionsTest {

    private val customers = listOf("中川組", "西川組", "山地組", "川村建築", "丸本建材", "誠建", "アズマヤ")

    private fun parsed(raw: String): Utterance.Updates =
        UtteranceParser.parse(raw) as? Utterance.Updates ?: throw AssertionError("解釈できませんでした: $raw")

    private fun updates(raw: String): Map<Field, FieldValue> = parsed(raw).updates.associate { it.field to it.value }

    private fun single(v: String) = FieldValue.Single(v)

    private fun say(raw: String, registered: List<String> = customers) =
        VoiceInterpreter.handle(EntryBuffer(), listOf(raw), registered)

    // ------------------------------------------------------------------
    // 品目: 本柄・小柄 → コンガラ
    // ------------------------------------------------------------------

    @Test
    fun item_positional() {
        for (heard in listOf("本柄", "小柄")) {
            val u = updates("1234 $heard 2トン 2割")
            assertEquals(heard, single("1234"), u[Field.NUMBER])
            assertEquals(heard, single("コンガラ"), u[Field.ITEM])
            assertEquals(heard, single("2t"), u[Field.SIZE])
            assertEquals(heard, single("20"), u[Field.SURCHARGE])
            assertEquals(heard, single("コンガラ"), updates("${heard}2トン2割")[Field.ITEM])
        }
    }

    @Test
    fun item_labeled() {
        for (heard in listOf("本柄", "小柄")) {
            assertEquals(heard, single("コンガラ"), updates("品目 $heard")[Field.ITEM])
            assertEquals(heard, single("コンガラ"), updates("品目、$heard")[Field.ITEM])
        }
    }

    // ------------------------------------------------------------------
    // 割増: 寮 → 良、無料 → 不良
    // ------------------------------------------------------------------

    @Test
    fun surcharge_positional() {
        assertEquals(single("good"), updates("コンガラ 10トン 寮")[Field.SURCHARGE])
        assertEquals(single("bad"), updates("コンガラ 10トン 無料")[Field.SURCHARGE])
        assertEquals(single("bad"), updates("1234こんがら10トン無料")[Field.SURCHARGE])
    }

    @Test
    fun surcharge_labeled() {
        assertEquals(single("good"), updates("割増 寮")[Field.SURCHARGE])
        assertEquals(single("bad"), updates("割増 無料")[Field.SURCHARGE])
        assertEquals(single("bad"), updates("割増、無料")[Field.SURCHARGE])
    }

    @Test
    fun surcharge_noPartialMatch() {
        // 「不良」の中の「良」に寄らない／「良」が「不良」に寄らない
        assertEquals(single("bad"), updates("コンガラ 10トン 不良")[Field.SURCHARGE])
        assertEquals(single("good"), updates("コンガラ 10トン 良")[Field.SURCHARGE])
        assertEquals(single("bad"), updates("割増 不良")[Field.SURCHARGE])
        assertEquals(single("good"), updates("割増 良")[Field.SURCHARGE])
    }

    // ------------------------------------------------------------------
    // 理由: 入金・料金 → 有筋（「理由」なしでも）
    // ------------------------------------------------------------------

    @Test
    fun reason_positionalTail() {
        for (heard in listOf("入金", "料金")) {
            val u = updates("コンガラ 4トン 2割 $heard")
            assertEquals(heard, single("20"), u[Field.SURCHARGE])
            assertEquals(heard, FieldValue.Multi(listOf("有筋")), u[Field.REASON])
            assertEquals(heard, FieldValue.Multi(listOf("有筋")), updates("コンガラ 4トン 2割 理由 $heard")[Field.REASON])
        }
    }

    @Test
    fun reason_labeled() {
        for (heard in listOf("入金", "料金")) {
            assertEquals(heard, FieldValue.Multi(listOf("有筋")), updates("理由 $heard")[Field.REASON])
            assertEquals(heard, FieldValue.Multi(listOf("大きさ", "有筋")), updates("理由 大きさ $heard")[Field.REASON])
        }
    }

    @Test
    fun reason_withoutPrefix() {
        for (heard in listOf("入金", "料金")) {
            val u = updates(heard)
            assertEquals(heard, setOf(Field.REASON), u.keys)
            assertEquals(heard, FieldValue.Multi(listOf("有筋")), u[Field.REASON])
        }
        assertEquals(FieldValue.Multi(listOf("鉄筋", "有筋")), updates("鉄筋 料金")[Field.REASON])
    }

    // ------------------------------------------------------------------
    // 項目をまたいで波及しない（補正はその項目の値にだけ効く）
    // ------------------------------------------------------------------

    @Test
    fun corrections_stayInTheirField() {
        // 「無料」は割増、「料金」は理由。並べてもそれぞれの項目にだけ効く
        val u = updates("コンガラ 10トン 無料 料金")
        assertEquals(single("bad"), u[Field.SURCHARGE])
        assertEquals(FieldValue.Multi(listOf("有筋")), u[Field.REASON])

        // 「料金」は割増にならない（理由として扱う）
        val r = updates("コンガラ 10トン 料金")
        assertNull(r[Field.SURCHARGE])
        assertEquals(FieldValue.Multi(listOf("有筋")), r[Field.REASON])

        // 他の項目名の後ろでは補正しない（その項目の語ではないので解釈できない）
        for (raw in listOf("品目 無料", "品目 料金", "割増 料金", "割増 本柄", "理由 無料", "理由 寮", "理由 本柄", "サイズ 無料")) {
            assertNull(raw, UtteranceParser.parse(raw))
        }
        // 単独の「無料」「寮」は理由にしない（位置ベースの割増）
        assertEquals(mapOf(Field.SURCHARGE to single("bad")), updates("無料"))
        assertEquals(mapOf(Field.SURCHARGE to single("good")), updates("寮"))
    }

    @Test
    fun dictionary_doesNotCollideWithOtherVocabulary() {
        val n = NormalizedText::normalize
        val ownForms = mapOf(
            Field.ITEM to Vocabulary.items, Field.SIZE to Vocabulary.sizes, Field.SURCHARGE to Vocabulary.surcharges,
            Field.REASON to Vocabulary.reasons, Field.REJECT to Vocabulary.rejects,
        ).mapValues { (_, terms) -> terms.flatMap { it.forms }.toSet() }
        val heardForms = Corrections.rows.flatMap { row -> row.heard.map { row.field to n(it) } }

        // 辞書の語どうしが重ならない
        assertEquals(heardForms.map { it.second }.distinct().size, heardForms.size)
        for ((field, form) in heardForms) {
            // 他の項目の語彙と重ならない（自分の項目の正規の語とも重ならない）
            for ((other, forms) in ownForms) assertFalse("$field「$form」が$other の語と重なる", form in forms)
            // 項目名・コマンド・パス・コピー・つなぎ言葉として読まれない
            assertNull(form, Vocabulary.fieldLabels.matchAt(form, 0))
            assertNull(form, Vocabulary.commands.matchAt(form, 0))
            assertNull(form, Vocabulary.pass.matchAt(form, 0))
            assertNull(form, Vocabulary.copy.matchAt(form, 0))
            assertNull(form, Vocabulary.fillers.matchAt(form, 0))
        }
        // 辞書の語は自分の項目でだけ照合される
        val sets = mapOf(
            Field.ITEM to Vocabulary.itemSet, Field.SIZE to Vocabulary.sizeSet, Field.SURCHARGE to Vocabulary.surchargeSet,
            Field.REASON to Vocabulary.reasonSet, Field.REJECT to Vocabulary.rejectSet,
        )
        for ((field, form) in heardForms) {
            for ((other, set) in sets) {
                val hit = set.matchAt(form, 0)
                if (other == field) assertTrue("$field「$form」", hit != null && hit.second == form.length)
                else assertNull("$field「$form」が$other で照合された", hit)
            }
        }
    }

    // ------------------------------------------------------------------
    // 客先: 東屋 → アズマヤ
    // ------------------------------------------------------------------

    @Test
    fun customer_dictionary() {
        for (heard in listOf("東屋", "アズマヤ", "あずまや", "ｱｽﾞﾏﾔ", "東屋さん", " 東 屋 ")) {
            assertEquals(heard, "アズマヤ", Corrections.correct(Field.CUSTOMER, heard))
        }
        for (other in listOf("中川組", "東屋建設", "東山", "東", "アズマ", "あずま屋商店")) {
            assertNull(other, Corrections.correct(Field.CUSTOMER, other))
        }
    }

    @Test
    fun customer_labeled() {
        for (raw in listOf("客先 東屋", "客先東屋", "客先、東屋", "客先は東屋", "客先 アズマヤ", "客先 あずまや", "客先　東屋")) {
            assertEquals(raw, single("アズマヤ"), updates(raw)[Field.CUSTOMER])
            val b = say(raw).buffer
            assertEquals(raw, "アズマヤ", b.customer)
            assertTrue(raw, b.customerRegistered)
        }
        // 他の項目と続けても
        val u = updates("ナンバー 1234 客先東屋 品目 コンガラ")
        assertEquals(single("1234"), u[Field.NUMBER])
        assertEquals(single("アズマヤ"), u[Field.CUSTOMER])
        assertEquals(single("コンガラ"), u[Field.ITEM])
    }

    @Test
    fun customer_positional() {
        for (raw in listOf(
            "1234 コンガラ 2トン 2割 客先東屋",
            "1234 コンガラ 2トン 2割 客先 東屋",
            "1234こんがら2トン2割客先東屋",
            "コンガラ 2トン 2割 大きさ 客先 東屋",
            "1234 コンガラ 2トン 2割 客先 アズマヤ",
        )) {
            val u = updates(raw)
            assertEquals(raw, single("コンガラ"), u[Field.ITEM])
            assertEquals(raw, single("2t"), u[Field.SIZE])
            assertEquals(raw, single("20"), u[Field.SURCHARGE])
            assertEquals(raw, single("アズマヤ"), u[Field.CUSTOMER])
            val b = say(raw).buffer
            assertEquals(raw, "アズマヤ", b.customer)
            assertTrue(raw, b.isSendable)
        }
        assertEquals(FieldValue.Multi(listOf("大きさ")), updates("コンガラ 2トン 2割 大きさ 客先 東屋")[Field.REASON])
    }

    @Test
    fun customer_correctedEvenIfNotRegistered() {
        val b = say("客先 東屋", registered = listOf("中川組", "西川組")).buffer
        assertEquals("アズマヤ", b.customer)
        assertFalse(b.customerRegistered)
    }

    @Test
    fun customer_othersAreNotPulledToAzumaya() {
        for ((raw, expected) in listOf(
            "客先 中川組" to "中川組",
            "客先 西川組" to "西川組",
            "客先 中川組さん" to "中川組",
            "1234 コンガラ 2トン 2割 客先 山地組" to "山地組",
            "客先 東屋建設" to "東屋建設",
            "客先 東山" to "東山",
        )) {
            assertEquals(raw, expected, say(raw).buffer.customer)
            assertTrue(raw, say(raw).corrections.isEmpty())
        }
    }

    @Test
    fun copy_customerIsCorrected() {
        val u = UtteranceParser.parse("コピー 東屋") as Utterance.Copy
        assertEquals("アズマヤ", u.customer)
        assertEquals(listOf(Correction(Field.CUSTOMER, "東屋", "アズマヤ")), u.corrections)
    }

    // ------------------------------------------------------------------
    // ログ（元の認識文字列と補正後の値）
    // ------------------------------------------------------------------

    @Test
    fun corrections_areRecorded() {
        assertEquals(
            listOf(
                Correction(Field.ITEM, "本柄", "コンガラ"),
                Correction(Field.SURCHARGE, "無料", "不良"),
                Correction(Field.REASON, "料金", "有筋"),
                Correction(Field.CUSTOMER, "東屋", "アズマヤ"),
            ),
            parsed("1234 本柄 10トン 無料 料金 客先 東屋").corrections,
        )
        assertEquals(listOf(Correction(Field.SURCHARGE, "寮", "良")), parsed("割増 寮").corrections)
        assertEquals(listOf(Correction(Field.REASON, "入金", "有筋")), parsed("入金").corrections)
        // 補正していなければ記録しない
        assertTrue(parsed("1234 コンガラ 10トン 不良 有筋 客先 アズマヤ").corrections.isEmpty())
    }

    @Test
    fun corrections_areReportedWithTheRecognizedText() {
        val o = VoiceInterpreter.handle(EntryBuffer(), listOf("ざつおん", "コンガラ 10トン 無料 客先東屋"), customers)
        assertEquals("bad", o.buffer.surcharge)
        assertEquals("アズマヤ", o.buffer.customer)
        assertEquals(
            listOf(
                "補正: 割増「無料」→「不良」 認識「コンガラ 10トン 無料 客先東屋」",
                "補正: 客先「東屋」→「アズマヤ」 認識「コンガラ 10トン 無料 客先東屋」",
            ),
            o.corrections,
        )
        assertTrue(say("1234 コンガラ 2トン 2割").corrections.isEmpty())
    }
}
