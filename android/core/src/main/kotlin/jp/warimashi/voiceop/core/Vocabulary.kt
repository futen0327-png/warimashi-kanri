package jp.warimashi.voiceop.core

/** 入力項目（設計書3章）。 */
enum class Field(val label: String, val speech: String) {
    NUMBER("ナンバー", "ナンバー"),
    ITEM("品目", "品目"),
    SIZE("サイズ", "サイズ"),
    SURCHARGE("割増", "割増"),
    REASON("理由", "理由"),
    CUSTOMER("客先", "客先"),
    REJECT("拒否", "拒否"),
}

/**
 * 固定候補の1語。value は Firebase に送る値そのもの、speech は読み上げ用の表記。
 * synonyms は音声認識の揺れ（ひらがな・同音異字など）を吸収するための別表記。
 * valueIsWord=false のときは value（割増キーなど）自体を発話の語として扱わない。
 */
class Term private constructor(
    val value: String,
    val speech: String,
    val forms: List<String>,
    /** 補正辞書（[Corrections]）の語として照合されたとき、その語と補正後の表示名。 */
    val correction: Pair<String, String>?,
) {
    constructor(value: String, speech: String, synonyms: List<String>, valueIsWord: Boolean = true) : this(
        value,
        speech,
        (synonyms + speech + if (valueIsWord) listOf(value) else emptyList()).map { NormalizedText.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct(),
        null,
    )

    /** 誤認識 heard を、この語（表示名 label）として照合させる補正形。照合に使うのは heard だけ。 */
    fun correctedFrom(heard: String, label: String): Term =
        Term(value, speech, listOf(NormalizedText.normalize(heard)).filter { it.isNotEmpty() }, heard to label)
}

/** 正規化済みテキストの pos 位置から始まる語を探す（最長一致）。 */
class TermSet<T>(entries: List<Pair<String, T>>) {
    private val forms: List<Pair<String, T>> =
        entries.map { NormalizedText.normalize(it.first) to it.second }
            .filter { it.first.isNotEmpty() }
            .sortedByDescending { it.first.length }

    fun matchAt(text: String, pos: Int): Pair<T, Int>? {
        for ((form, v) in forms) {
            if (text.startsWith(form, pos)) return v to form.length
        }
        return null
    }

    /** 正規化済みテキストの end 位置で終わる語を探す（最長一致）。 */
    fun matchEndingAt(text: String, end: Int): Pair<T, Int>? {
        for ((form, v) in forms) {
            if (form.length <= end && text.startsWith(form, end - form.length)) return v to form.length
        }
        return null
    }

    companion object {
        fun ofTerms(terms: List<Term>): TermSet<Term> =
            TermSet(terms.flatMap { t -> t.forms.map { it to t } })
    }
}

object Vocabulary {

    // ---- 項目名（発話ラベル） ----
    val fieldLabels: TermSet<Field> = TermSet(
        listOf(
            "ナンバー" to Field.NUMBER, "なんば" to Field.NUMBER, "番号" to Field.NUMBER,
            "車番" to Field.NUMBER, "しゃばん" to Field.NUMBER, "ナンバープレート" to Field.NUMBER,
            "品目" to Field.ITEM, "ひんもく" to Field.ITEM,
            "サイズ" to Field.SIZE, "車種" to Field.SIZE, "しゃしゅ" to Field.SIZE,
            "割増" to Field.SURCHARGE, "割り増し" to Field.SURCHARGE, "割増し" to Field.SURCHARGE,
            "わりまし" to Field.SURCHARGE,
            "理由" to Field.REASON, "りゆう" to Field.REASON,
            "客先" to Field.CUSTOMER, "きゃくさき" to Field.CUSTOMER, "顧客" to Field.CUSTOMER,
            "お客" to Field.CUSTOMER,
            "拒否" to Field.REJECT, "きょひ" to Field.REJECT, "受入拒否" to Field.REJECT,
            "受け入れ拒否" to Field.REJECT,
        )
    )

    // ---- パス（空欄指定） ----
    val pass: TermSet<Unit> = TermSet(listOf("パス", "ぱす", "バス", "pass", "パース").map { it to Unit })

    // ---- コマンド ----
    enum class Command { SEND, CANCEL, READBACK }

    val commands: TermSet<Command> = TermSet(
        listOf(
            "送信" to Command.SEND, "そうしん" to Command.SEND, "送信する" to Command.SEND,
            "送信します" to Command.SEND, "送信して" to Command.SEND,
            "取消" to Command.CANCEL, "取り消し" to Command.CANCEL, "取消し" to Command.CANCEL,
            "とりけし" to Command.CANCEL, "取り消す" to Command.CANCEL, "キャンセル" to Command.CANCEL,
            // 設計書7章には無いが、画面を見ずに現在の入力内容を聞き直せるよう追加
            "確認" to Command.READBACK, "かくにん" to Command.READBACK, "読み上げ" to Command.READBACK,
        )
    )

    // ---- コピー（当日の登録から1件を入力欄に写す）。客先名・ナンバーを前後に付けられる ----
    val copy: TermSet<Unit> = TermSet(listOf("コピー", "こぴー", "コーピー", "copy").map { it to Unit })

    // ---- ② 品目 ----
    val items: List<Term> = listOf(
        Term("コンガラ", "コンガラ", listOf("こんがら", "コンクリートガラ", "コンクリガラ", "コンカラ")),
        Term("アスガラ", "アスガラ", listOf("あすがら", "アスファルトガラ", "アスカラ")),
        Term("残土", "ざんど", listOf("ざんど", "ザンド")),
        Term("石", "いし", listOf("いし", "医師", "意志", "意思")),
        Term("瓦", "かわら", listOf("かわら", "河原", "川原")),
    )

    // ---- ③ 車両サイズ ----
    val sizes: List<Term> = run {
        fun tonForms(n: Int, readings: List<String>): List<String> =
            listOf("${n}トン", "${n}t", "${n}トン車", "${n}t車", "${n}トントラック") + readings
        listOf(
            Term("軽", "けい", listOf("けい", "軽トラ", "けいとら", "軽トラック", "軽四")),
            Term("2t", "2トン", tonForms(2, listOf("にとん", "2トン"))),
            Term("4t", "4トン", tonForms(4, listOf("よんとん", "よとん"))),
            Term("8t", "8トン", tonForms(8, listOf("はちとん", "はっとん"))),
            Term("10t", "10トン", tonForms(10, listOf("じゅっとん", "じっとん", "じゅうとん"))),
        )
    }

    // ---- ④ 割増区分（value は Firebase に送るキー。既存PWAの surcharge と同じ） ----
    val surcharges: List<Term> = run {
        fun wari(n: Int, readings: List<String>): List<String> =
            listOf("${n}割", "${n}割増", "${n}割増し", "${n}割まし", "${n}わり") + readings
        listOf(
            Term("none", "割増なし", listOf("割増なし", "割り増しなし", "割増し無し", "割増無し", "わりましなし", "なし", "無し", "ない"), valueIsWord = false),
            Term("good", "りょう", listOf("良", "りょう", "良い", "よい"), valueIsWord = false),
            Term("normal", "普通", listOf("ふつう"), valueIsWord = false),
            Term("cutting", "切削", listOf("せっさく"), valueIsWord = false),
            Term("20", "2割", wari(2, listOf("にわり")), valueIsWord = false),
            Term("40", "4割", wari(4, listOf("よんわり", "よわり")), valueIsWord = false),
            Term("bad", "不良", listOf("ふりょう"), valueIsWord = false),
        )
    }

    // ---- ⑤ 割増理由 ----
    val reasons: List<Term> = listOf(
        Term("大きさ", "大きさ", listOf("おおきさ", "大きい", "おおきい")),
        Term("有筋", "ゆうきん", listOf("ゆうきん", "有金", "遊筋")),
        Term("鉄筋", "てっきん", listOf("てっきん", "鉄琴")),
        Term("木くず", "きくず", listOf("きくず", "木屑", "木クズ")),
        Term("Wメッシュ", "ダブルメッシュ", listOf("ダブルメッシュ", "ダブリューメッシュ", "Wメッシュ", "メッシュ")),
        Term("大谷石", "おおやいし", listOf("おおやいし", "大屋石", "大矢石")),
        Term("二次製品", "にじせいひん", listOf("にじせいひん", "2次製品")),
        Term("その他", "そのた", listOf("そのた", "そのほか", "その外")),
    )

    // ---- ⑦ 受入拒否品目 ----
    val rejects: List<Term> = listOf(
        Term("赤レンガ", "あかレンガ", listOf("あかれんが", "赤れんが", "赤煉瓦", "赤練瓦", "レンガ", "煉瓦")),
        Term("ラス", "ラス", listOf("らす")),
        Term("根っこ", "ねっこ", listOf("ねっこ", "根")),
        Term("木片", "もくへん", listOf("もくへん", "きへん", "木へん")),
        Term("石", "いし", listOf("いし", "医師", "意志", "意思")),
        Term("防水シート", "ぼうすいシート", listOf("ぼうすいしーと", "防水しーと", "シート")),
        Term("ビニール", "ビニール", listOf("びにーる", "ビニル", "ビニール袋")),
        Term("少ない", "すくない", listOf("すくない", "少なすぎる")),
        Term("大きすぎる", "おおきすぎる", listOf("おおきすぎる", "大き過ぎる", "大きすぎ", "でかすぎる")),
    )

    // 品目・割増・理由には補正辞書の語も入れる（その項目として照合したときだけ効く）
    val itemSet = TermSet.ofTerms(items + Corrections.termsFor(Field.ITEM, items))
    val sizeSet = TermSet.ofTerms(sizes)
    val surchargeSet = TermSet.ofTerms(surcharges + Corrections.termsFor(Field.SURCHARGE, surcharges))
    val reasonSet = TermSet.ofTerms(reasons + Corrections.termsFor(Field.REASON, reasons))
    val rejectSet = TermSet.ofTerms(rejects)

    /** 語と語のあいだに入りがちなつなぎ言葉（読み飛ばす）。 */
    val fillers: TermSet<Unit> = TermSet(
        listOf("えーと", "えっと", "えー", "あの", "の", "で", "と", "や", "は", "が", "です").map { it to Unit }
    )

    fun itemTerm(value: String): Term? = items.firstOrNull { it.value == value }
    fun sizeTerm(value: String): Term? = sizes.firstOrNull { it.value == value }
    fun surchargeTerm(key: String): Term? = surcharges.firstOrNull { it.value == key }
    fun reasonTerm(value: String): Term? = reasons.firstOrNull { it.value == value }
    fun rejectTerm(value: String): Term? = rejects.firstOrNull { it.value == value }
}
