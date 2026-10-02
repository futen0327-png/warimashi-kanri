package jp.warimashi.voiceop.core

/** 補正辞書で値を直したことの記録（新しい揺れを拾えるようログに残す）。 */
data class Correction(
    val field: Field,
    /** 補正前の値（品目・割増・理由は辞書の語、客先は認識された表記そのまま）。 */
    val heard: String,
    /** 補正後の値（表示名）。 */
    val corrected: String,
) {
    /** ログ用の1行。utterance は元の認識文字列（発話全体）。 */
    fun describe(utterance: String): String = "補正: ${field.label}「$heard」→「$corrected」 認識「$utterance」"
}

/**
 * 音声認識の誤認識を正規の値に直す辞書（項目別・完全一致）。
 * **実機で新しい揺れが見つかったら [rows] に1行足すだけでよい。**
 *
 * - 項目が決まった後、その項目の値にだけ当てる（全文の置換はしない。「無料」「料金」が他の項目に波及しないように）
 * - 比較は正規化（空白除去・全角半角・ひらがな/カタカナの統一）した上での完全一致。「不良」の中の「良」のような部分一致はしない
 * - 品目・割増・理由は、各項目の語彙（[Vocabulary] の itemSet など）に「その項目の語」として組み込む。
 *   位置入力・項目名指定・「理由」なしの理由のどれでも、その項目として照合したときだけ効く
 * - 客先は自由記述なので、取り出した名前全体と比べる。あいまい一致（[CustomerMatcher]）より先に当てる
 */
object Corrections {

    /** 辞書の1行。canonical は正規の値の表示名（割増は「良」「不良」などの表記）。 */
    class Row(val field: Field, val canonical: String, val heard: List<String>)

    val rows: List<Row> = listOf(
        Row(Field.ITEM, "コンガラ", listOf("本柄", "小柄")),
        Row(Field.SURCHARGE, "良", listOf("寮")),
        Row(Field.SURCHARGE, "不良", listOf("無料")),
        Row(Field.REASON, "有筋", listOf("入金", "料金")),
        Row(Field.CUSTOMER, "アズマヤ", listOf("東屋")),
    )

    /**
     * 語彙で照合する項目（品目・割増・理由）用。辞書の各語を、正規の語 [Term] の補正形として返す。
     * 正規の値が語彙にない行は辞書の書き間違いなので、起動時に気づけるよう例外にする。
     */
    fun termsFor(field: Field, terms: List<Term>): List<Term> =
        rows.filter { it.field == field }.flatMap { row ->
            val key = NormalizedText.normalize(row.canonical)
            val canonical = terms.firstOrNull { key in it.forms }
                ?: error("補正辞書: ${field.label}「${row.canonical}」が語彙にありません")
            row.heard.map { canonical.correctedFrom(it, row.canonical) }
        }

    /**
     * 自由記述の項目（客先）用。値全体が辞書の語か正規の値そのものと一致すれば正規の値を返す。
     * 客先の比較は [CustomerMatcher.key]（正規化＋敬称・法人格の除去）で行う。
     */
    fun correct(field: Field, value: String): String? {
        val key = keyOf(field, value)
        if (key.isEmpty()) return null
        return rows.firstOrNull { row ->
            row.field == field && (row.heard + row.canonical).any { keyOf(field, it) == key }
        }?.canonical
    }

    private fun keyOf(field: Field, s: String): String =
        if (field == Field.CUSTOMER) CustomerMatcher.key(s) else NormalizedText.normalize(s)
}
