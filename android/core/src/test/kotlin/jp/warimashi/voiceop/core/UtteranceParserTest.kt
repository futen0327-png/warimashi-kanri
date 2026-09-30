package jp.warimashi.voiceop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtteranceParserTest {

    private fun updates(raw: String): Map<Field, FieldValue> {
        val u = UtteranceParser.parse(raw) as? Utterance.Updates
            ?: throw AssertionError("解釈できませんでした: $raw")
        return u.updates.associate { it.field to it.value }
    }

    private fun single(v: String) = FieldValue.Single(v)

    @Test
    fun positional_fullSet_withSpaces() {
        val u = updates("1234 コンガラ 2トン 2割")
        assertEquals(single("1234"), u[Field.NUMBER])
        assertEquals(single("コンガラ"), u[Field.ITEM])
        assertEquals(single("2t"), u[Field.SIZE])
        assertEquals(single("20"), u[Field.SURCHARGE])
    }

    @Test
    fun positional_noSeparators() {
        val u = updates("1234こんがら2トン2割")
        assertEquals(single("1234"), u[Field.NUMBER])
        assertEquals(single("コンガラ"), u[Field.ITEM])
        assertEquals(single("2t"), u[Field.SIZE])
        assertEquals(single("20"), u[Field.SURCHARGE])
    }

    @Test
    fun positional_pass() {
        val u = updates("パス、こんがら、2トン、2割")
        assertEquals(FieldValue.Clear, u[Field.NUMBER])
        assertEquals(single("コンガラ"), u[Field.ITEM])
    }

    @Test
    fun positional_plateStuckToSize() {
        val u = updates("12342トン4割")
        assertEquals(single("1234"), u[Field.NUMBER])
        assertEquals(null, u[Field.ITEM])
        assertEquals(single("2t"), u[Field.SIZE])
        assertEquals(single("40"), u[Field.SURCHARGE])
    }

    @Test
    fun positional_omittedPlateWithoutPass() {
        val u = updates("アスガラ 10トン 切削")
        assertEquals(null, u[Field.NUMBER])
        assertEquals(single("アスガラ"), u[Field.ITEM])
        assertEquals(single("10t"), u[Field.SIZE])
        assertEquals(single("cutting"), u[Field.SURCHARGE])
    }

    @Test
    fun positional_kanjiNumeralsAndKatakana() {
        val u = updates("五六七八 ザンド 四トン 割増なし")
        assertEquals(single("5678"), u[Field.NUMBER])
        assertEquals(single("残土"), u[Field.ITEM])
        assertEquals(single("4t"), u[Field.SIZE])
        assertEquals(single("none"), u[Field.SURCHARGE])
    }

    @Test
    fun positional_fullWidthDigitsAndKei() {
        val u = updates("０１２３ 瓦 軽")
        assertEquals(single("0123"), u[Field.NUMBER])
        assertEquals(single("瓦"), u[Field.ITEM])
        assertEquals(single("軽"), u[Field.SIZE])
    }

    @Test
    fun positional_badVsGood() {
        assertEquals(single("bad"), updates("コンガラ 10トン 不良")[Field.SURCHARGE])
        assertEquals(single("good"), updates("コンガラ 10トン 良")[Field.SURCHARGE])
    }

    @Test
    fun positional_withFillers() {
        val u = updates("えーと、コンガラの2トンで2割")
        assertEquals(single("コンガラ"), u[Field.ITEM])
        assertEquals(single("2t"), u[Field.SIZE])
        assertEquals(single("20"), u[Field.SURCHARGE])
    }

    @Test
    fun positional_hachiTon() {
        assertEquals(single("8t"), updates("はちとん")[Field.SIZE])
        assertEquals(single("8t"), updates("8トン")[Field.SIZE])
    }

    @Test
    fun unparsable_returnsNull() {
        assertNull(UtteranceParser.parse("今日はいい天気"))
        assertNull(UtteranceParser.parse(""))
        assertNull(UtteranceParser.parse("12345"))
    }

    @Test
    fun labeled_number() {
        val u = updates("ナンバー、1234")
        assertEquals(mapOf(Field.NUMBER to single("1234")), u)
    }

    @Test
    fun labeled_reasonsMultiple() {
        val u = updates("理由、大きさ、鉄筋")
        assertEquals(FieldValue.Multi(listOf("大きさ", "鉄筋")), u[Field.REASON])
    }

    @Test
    fun labeled_reasonsNoSeparator() {
        val u = updates("理由大きさと鉄筋とダブルメッシュ")
        assertEquals(FieldValue.Multi(listOf("大きさ", "鉄筋", "Wメッシュ")), u[Field.REASON])
    }

    // ---- 割増理由: 「理由」なしでも、入力の途中でもいつでも言える ----

    @Test
    fun reasonsOnly_withoutPrefix() {
        val u = updates("大きさ、鉄筋")
        assertEquals(mapOf(Field.REASON to FieldValue.Multi(listOf("大きさ", "鉄筋"))), u)
    }

    @Test
    fun reasonsOnly_aliasesAndNoSeparators() {
        assertEquals(FieldValue.Multi(listOf("Wメッシュ")), updates("ダブルメッシュ")[Field.REASON])
        assertEquals(FieldValue.Multi(listOf("大きさ", "鉄筋")), updates("おおきさとてっきん")[Field.REASON])
        assertEquals(FieldValue.Multi(listOf("大谷石", "その他")), updates("えーと大谷石とその他")[Field.REASON])
    }

    @Test
    fun reasonsOnly_prefixIsOptional() {
        assertEquals(updates("理由 大きさ 鉄筋"), updates("大きさ 鉄筋"))
        assertEquals(updates("りゆう 二次製品"), updates("二次製品"))
    }

    @Test
    fun reasonsOnly_passAloneIsStillPlate() {
        // 「パス」だけは従来どおりナンバーの空欄（理由を消すのは「理由 パス」）
        assertEquals(mapOf(Field.NUMBER to FieldValue.Clear), updates("パス"))
    }

    @Test
    fun reasonsOnly_unknownWordIsNull() {
        assertNull(UtteranceParser.parse("大きさ 天気"))
        assertNull(UtteranceParser.parse("大きさ コンガラ"))
    }

    @Test
    fun positional_thenReasonsWithoutPrefix() {
        val u = updates("1234 コンガラ 4トン 2割 大きさ 鉄筋")
        assertEquals(single("1234"), u[Field.NUMBER])
        assertEquals(single("コンガラ"), u[Field.ITEM])
        assertEquals(single("4t"), u[Field.SIZE])
        assertEquals(single("20"), u[Field.SURCHARGE])
        assertEquals(FieldValue.Multi(listOf("大きさ", "鉄筋")), u[Field.REASON])
    }

    @Test
    fun positional_thenReasonsWithPrefix() {
        val u = updates("コンガラ 4トン 2割 理由 大きさ")
        assertEquals(single("20"), u[Field.SURCHARGE])
        assertEquals(FieldValue.Multi(listOf("大きさ")), u[Field.REASON])
        assertEquals(updates("コンガラ4トン2割理由大きさ"), u)
        assertEquals(updates("コンガラ 4トン 2割 大きさ"), u)
    }

    @Test
    fun positional_thenReasonPassNeedsPrefix() {
        assertEquals(FieldValue.Clear, updates("コンガラ 4トン 2割 理由 パス")[Field.REASON])
        assertNull(UtteranceParser.parse("コンガラ 4トン 2割 パス"))
    }

    @Test
    fun positional_withoutReasons_hasNoReasonUpdate() {
        assertNull(updates("1234 コンガラ 2トン 2割")[Field.REASON])
    }

    @Test
    fun labeled_reject() {
        assertEquals(FieldValue.Multi(listOf("赤レンガ")), updates("拒否、赤レンガ")[Field.REJECT])
        assertEquals(FieldValue.Multi(listOf("石", "防水シート")), updates("拒否 石 防水シート")[Field.REJECT])
    }

    @Test
    fun labeled_customerKeepsOriginalNotation() {
        assertEquals(single("アズマヤ"), updates("客先、アズマヤ")[Field.CUSTOMER])
        assertEquals(single("中川組"), updates("客先は中川組")[Field.CUSTOMER])
    }

    @Test
    fun labeled_multiplePairs() {
        val u = updates("ナンバー1234 客先 中川組 理由 鉄筋")
        assertEquals(single("1234"), u[Field.NUMBER])
        assertEquals(single("中川組"), u[Field.CUSTOMER])
        assertEquals(FieldValue.Multi(listOf("鉄筋")), u[Field.REASON])
    }

    @Test
    fun labeled_surchargeNashi() {
        assertEquals(single("none"), updates("割増、なし")[Field.SURCHARGE])
        assertEquals(single("none"), updates("割増、割増なし")[Field.SURCHARGE])
        assertEquals(single("20"), updates("割増 2割")[Field.SURCHARGE])
    }

    @Test
    fun labeled_pass() {
        assertEquals(FieldValue.Clear, updates("ナンバー パス")[Field.NUMBER])
        assertEquals(FieldValue.Clear, updates("理由、パス")[Field.REASON])
        assertEquals(FieldValue.Clear, updates("客先 パス")[Field.CUSTOMER])
    }

    @Test
    fun labeled_withoutValue_isNull() {
        assertNull(UtteranceParser.parse("拒否"))
        assertNull(UtteranceParser.parse("品目 えー"))
    }

    @Test
    fun commands() {
        assertEquals(Utterance.Command(Vocabulary.Command.SEND), UtteranceParser.parse("送信"))
        assertEquals(Utterance.Command(Vocabulary.Command.SEND), UtteranceParser.parse("送信します"))
        assertEquals(Utterance.Command(Vocabulary.Command.CANCEL), UtteranceParser.parse("取り消し"))
        assertEquals(Utterance.Command(Vocabulary.Command.CANCEL), UtteranceParser.parse("取消"))
        assertEquals(Utterance.Command(Vocabulary.Command.READBACK), UtteranceParser.parse("確認"))
    }

    @Test
    fun kanjiNumberPositional() {
        assertEquals("1234", NormalizedText.normalize("千二百三十四"))
        assertEquals("10とん", NormalizedText.normalize("十トン"))
        assertEquals("2次製品", NormalizedText.normalize("二次製品"))
    }

    @Test
    fun explain_showsParsedPrefixAndRemainder() {
        // 2026-09-28 の実機ログで棄却された候補
        assertEquals(
            "norm=240明日から4t2期 方式=位置 解釈できた=[ナンバー:240] 残り=「明日から4t2期」",
            UtteranceParser.explain("240 明日から4 T 2期"),
        )
        assertEquals(
            "norm=240あすがら4t2期 方式=位置 解釈できた=[ナンバー:240 品目:アスガラ サイズ:4t] 残り=「2期」",
            UtteranceParser.explain("240 アスガラ 4 T 2期"),
        )
    }
}
