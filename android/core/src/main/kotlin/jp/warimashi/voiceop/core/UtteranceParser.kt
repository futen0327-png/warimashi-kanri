package jp.warimashi.voiceop.core

/** 項目に入れる値。 */
sealed interface FieldValue {
    /** 「パス」で空欄にする。 */
    data object Clear : FieldValue

    /** 単一値（ナンバー・品目・サイズ・割増キー・顧客名の認識文字列）。 */
    data class Single(val value: String) : FieldValue

    /** 複数選択（理由・拒否）。 */
    data class Multi(val values: List<String>) : FieldValue
}

data class FieldUpdate(val field: Field, val value: FieldValue)

/** 1回の発話の解釈結果。 */
sealed interface Utterance {
    data class Command(val command: Vocabulary.Command) : Utterance

    /**
     * 「コピー」: 当日の登録から条件に合う最新の1件を入力欄に写す。
     * 条件はどちらも任意（両方 null なら直前の登録）。customer は発話の表記のまま。
     */
    data class Copy(val customer: String?, val plate: String?) : Utterance

    /** 位置ベース（5-1）または項目名+値（5-2）で解釈した項目の更新。 */
    data class Updates(val updates: List<FieldUpdate>, val labeled: Boolean) : Utterance
}

/**
 * 発話の解釈（設計書5章）。
 *
 * - 全体が「送信」「取消」ならコマンド
 * - 「コピー」を含むなら、前後を客先名・ナンバー（順不同・どちらも任意）としてコピー
 * - 全体が割増理由の語だけ（「大きさ 鉄筋」）なら、「理由」を付けなくても理由
 * - 先頭が項目名（ナンバー/品目/サイズ/割増/理由/客先/拒否）なら「項目名+値」方式
 * - それ以外は「ナンバー → 品目 → サイズ → 割増」の位置ベース方式。
 *   その後ろに割増理由を続けてもよい（「コンガラ 4トン 2割 大きさ」。「理由」は付けても付けなくてもよい）
 *
 * 音声認識は区切り（空白・読点）を入れないことが多いので、区切りに頼らず
 * 正規化テキストを先頭から語彙と突き合わせて切り分ける。解釈しきれない文字が
 * 残った場合は null を返す（誤った値で上書きしないため）。
 */
object UtteranceParser {

    private val positionalSlots = listOf(Field.NUMBER, Field.ITEM, Field.SIZE, Field.SURCHARGE)

    fun parse(raw: String): Utterance? {
        val nt = NormalizedText.of(raw)
        val t = nt.text
        if (t.isEmpty()) return null

        parseCommand(t)?.let { return it }
        parseCopy(nt)?.let { return it }
        parseReasonsOnly(t)?.let { return it }

        val labelStart = fillerSkips(t, 0).firstOrNull { Vocabulary.fieldLabels.matchAt(t, it) != null }
        return if (labelStart != null) parseLabeled(nt, labelStart) else parsePositional(t)
    }

    // ------------------------------------------------------------------
    // コマンド
    // ------------------------------------------------------------------

    private fun parseCommand(t: String): Utterance? {
        for (start in fillerSkips(t, 0)) {
            val (cmd, len) = Vocabulary.commands.matchAt(t, start) ?: continue
            if (reachesEnd(t, start + len)) return Utterance.Command(cmd)
        }
        return null
    }

    // ------------------------------------------------------------------
    // コピー（「コピー 中川組 1234」「1234 中川組 コピー」「中川組のコピー」）
    // ------------------------------------------------------------------

    /** 名前の前に付きがちな言葉（読み飛ばす）。名前の一部を削らないよう、つなぎ言葉より絞ってある。 */
    private val copyLeading = TermSet(listOf("えーと", "えっと", "えー", "あの").map { it to Unit })

    /** 名前の後ろに付きがちな助詞など（「中川組のコピー」「中川組をコピーして」）。 */
    private val copyTrailing = TermSet(listOf("の", "を", "は", "で", "です", "して", "します").map { it to Unit })

    /** 項目名のあとの助詞（「客先は中川組」）。 */
    private val afterLabel = TermSet(listOf("は", "の").map { it to Unit })

    private fun parseCopy(nt: NormalizedText): Utterance? {
        val t = nt.text
        val at = t.indices.firstOrNull { Vocabulary.copy.matchAt(t, it) != null } ?: return null
        val len = Vocabulary.copy.matchAt(t, at)!!.second
        // 「コピー」の前後それぞれから客先名・ナンバーを取り出す（順不同）
        val before = copyArgs(nt, 0, at) ?: return null
        val after = copyArgs(nt, at + len, t.length) ?: return null
        if ((before.customer != null && after.customer != null) || (before.plate != null && after.plate != null)) return null
        return Utterance.Copy(before.customer ?: after.customer, before.plate ?: after.plate)
    }

    /**
     * コピーの条件。[from, to) の先頭か末尾にある1〜4桁の数字をナンバー、残りを客先名とする。
     * 名前の途中の数字（「第一建設」）はナンバーにしない。解釈できなければ null。
     */
    private fun copyArgs(nt: NormalizedText, from: Int, to: Int): Utterance.Copy? {
        val t = nt.text
        var s = from
        var e = to
        var plate: String? = null

        fun isLabel(field: Field?) = field == Field.NUMBER || field == Field.CUSTOMER

        // 先頭から: つなぎ言葉・項目名・ナンバー
        while (s < e) {
            val lead = copyLeading.matchAt(t, s)
            if (lead != null && s + lead.second <= e) {
                s += lead.second
                continue
            }
            val label = Vocabulary.fieldLabels.matchAt(t, s)
            if (label != null && isLabel(label.first) && s + label.second <= e) {
                s += label.second
                val particle = afterLabel.matchAt(t, s)
                if (particle != null && s + particle.second < e) s += particle.second
                continue
            }
            var d = s
            while (d < e && t[d].isDigit()) d++
            if (d > s) {
                if (plate != null || d - s > 4) return null
                plate = t.substring(s, d)
                s = if (d < e && t[d] == '番') d + 1 else d
                continue
            }
            break
        }
        // 末尾から: 助詞・「番」・ナンバー・項目名
        while (e > s) {
            val trail = copyTrailing.matchEndingAt(t, e)
            if (trail != null && e - trail.second >= s) {
                e -= trail.second
                continue
            }
            if (t[e - 1] == '番' && e - 1 > s && t[e - 2].isDigit()) {
                e--
                continue
            }
            var d = e
            while (d > s && t[d - 1].isDigit()) d--
            if (d < e) {
                if (plate != null || e - d > 4) return null
                plate = t.substring(d, e)
                e = d
                continue
            }
            val label = Vocabulary.fieldLabels.matchEndingAt(t, e)
            if (label != null && isLabel(label.first) && e - label.second >= s) {
                e -= label.second
                continue
            }
            break
        }
        val customer = nt.rawSlice(s, e).trim().trim('、', '。', ',', '.', ' ', '　').ifEmpty { null }
        return Utterance.Copy(customer, plate)
    }

    // ------------------------------------------------------------------
    // 理由の語だけの発話（「理由」は不要。入力の途中でもいつでも言える）
    // ------------------------------------------------------------------

    private fun parseReasonsOnly(t: String): Utterance? {
        for (p in fillerSkips(t, 0)) {
            val (value, end) = terms(t, p, Vocabulary.reasonSet) ?: continue
            if (reachesEnd(t, end)) return Utterance.Updates(listOf(FieldUpdate(Field.REASON, value)), labeled = true)
        }
        return null
    }

    // ------------------------------------------------------------------
    // 位置ベース（ナンバー → 品目 → サイズ → 割増）
    // ------------------------------------------------------------------

    private fun parsePositional(t: String): Utterance? {
        val updates = positionalSearch(t, 0, 0, emptyList()) ?: return null
        return Utterance.Updates(updates, labeled = false)
    }

    /** バックトラックで先頭から各スロットを埋め、全文字を使い切れる解釈を探す。 */
    private fun positionalSearch(t: String, pos: Int, slot: Int, acc: List<FieldUpdate>): List<FieldUpdate>? {
        if (slot == positionalSlots.size) {
            if (acc.isEmpty()) return null
            if (reachesEnd(t, pos)) return acc
            return reasonTail(t, pos)?.let { acc + FieldUpdate(Field.REASON, it) }
        }
        val field = positionalSlots[slot]
        for (p in fillerSkips(t, pos)) {
            for ((value, end) in slotCandidates(field, t, p)) {
                positionalSearch(t, end, slot + 1, acc + FieldUpdate(field, value))?.let { return it }
            }
        }
        // このスロットは発話されなかった（値を変更しない）
        return positionalSearch(t, pos, slot + 1, acc)
    }

    /**
     * 位置ベースの後ろに続く割増理由（最後まで）。「理由」が付いていれば取り除いてから照合する。
     * 「パス」（理由を空欄）は「理由」が付いているときだけ受け付ける。
     */
    private fun reasonTail(t: String, pos: Int): FieldValue? {
        for (p in fillerSkips(t, pos)) {
            val label = Vocabulary.fieldLabels.matchAt(t, p)
            val hit = if (label != null && label.first == Field.REASON) multiValue(t, p + label.second, Vocabulary.reasonSet)
            else terms(t, p, Vocabulary.reasonSet)
            if (hit != null && reachesEnd(t, hit.second)) return hit.first
        }
        return null
    }

    /** pos から始まる、その項目の値として解釈できる候補（値, 終了位置）。 */
    private fun slotCandidates(field: Field, t: String, pos: Int): List<Pair<FieldValue, Int>> {
        val out = ArrayList<Pair<FieldValue, Int>>()
        Vocabulary.pass.matchAt(t, pos)?.let { (_, len) -> out += FieldValue.Clear to pos + len }
        when (field) {
            Field.NUMBER -> out += numberCandidates(t, pos)
            Field.ITEM -> Vocabulary.itemSet.matchAt(t, pos)?.let { (term, len) ->
                out += FieldValue.Single(term.value) to pos + len
            }
            Field.SIZE -> Vocabulary.sizeSet.matchAt(t, pos)?.let { (term, len) ->
                out += FieldValue.Single(term.value) to pos + len
            }
            Field.SURCHARGE -> Vocabulary.surchargeSet.matchAt(t, pos)?.let { (term, len) ->
                out += FieldValue.Single(term.value) to pos + len
            }
            else -> Unit
        }
        return out
    }

    /**
     * ナンバー（1〜4桁の数字）。「12342トン」のように次の語とくっついて
     * 認識された場合に備え、長い桁数から順に候補を返す。
     */
    private fun numberCandidates(t: String, pos: Int): List<Pair<FieldValue, Int>> {
        var end = pos
        while (end < t.length && t[end].isDigit()) end++
        val run = end - pos
        if (run == 0) return emptyList()
        return (minOf(run, 4) downTo 1).map { len ->
            var e = pos + len
            if (len == run && t.startsWith("番", e)) e++
            FieldValue.Single(t.substring(pos, pos + len)) to e
        }
    }

    // ------------------------------------------------------------------
    // 項目名+値
    // ------------------------------------------------------------------

    private fun parseLabeled(nt: NormalizedText, start: Int): Utterance? {
        val t = nt.text
        val updates = ArrayList<FieldUpdate>()
        var pos = start
        while (true) {
            val labelPos = fillerSkips(t, pos).firstOrNull { Vocabulary.fieldLabels.matchAt(t, it) != null }
                ?: return if (updates.isNotEmpty() && reachesEnd(t, pos)) Utterance.Updates(updates, true) else null
            val (field, len) = Vocabulary.fieldLabels.matchAt(t, labelPos)!!
            val (value, end) = labeledValue(field, nt, labelPos + len) ?: return null
            updates += FieldUpdate(field, value)
            pos = end
            if (reachesEnd(t, pos)) return Utterance.Updates(updates, true)
        }
    }

    private fun labeledValue(field: Field, nt: NormalizedText, pos: Int): Pair<FieldValue, Int>? {
        val t = nt.text
        return when (field) {
            Field.NUMBER, Field.ITEM, Field.SIZE, Field.SURCHARGE -> {
                for (p in fillerSkips(t, pos)) {
                    // 値の後ろが「終わり」か「次の項目名」になる候補を採用
                    val hit = slotCandidates(field, t, p).firstOrNull { (_, end) ->
                        reachesEnd(t, end) || fillerSkips(t, end).any { Vocabulary.fieldLabels.matchAt(t, it) != null }
                    }
                    if (hit != null) return hit
                }
                null
            }
            Field.REASON -> multiValue(t, pos, Vocabulary.reasonSet)
            Field.REJECT -> multiValue(t, pos, Vocabulary.rejectSet)
            Field.CUSTOMER -> customerValue(nt, pos)
        }
    }

    /** 「大きさ、鉄筋」のように複数の語を続けて言う項目。 */
    private fun multiValue(t: String, pos: Int, set: TermSet<Term>): Pair<FieldValue, Int>? {
        for (p in fillerSkips(t, pos)) {
            Vocabulary.pass.matchAt(t, p)?.let { (_, len) -> return FieldValue.Clear to p + len }
        }
        return terms(t, pos, set)
    }

    /** 語彙の語の並び（「パス」は含めない）。1語もなければ null。 */
    private fun terms(t: String, pos: Int, set: TermSet<Term>): Pair<FieldValue, Int>? {
        val values = ArrayList<String>()
        var cur = pos
        while (true) {
            val next = fillerSkips(t, cur).firstNotNullOfOrNull { p ->
                set.matchAt(t, p)?.let { (term, len) -> term.value to p + len }
            } ?: break
            if (next.first !in values) values += next.first
            cur = next.second
        }
        if (values.isEmpty()) return null
        return FieldValue.Multi(values) to cur
    }

    /** 顧客名は自由記述。次の項目名が出てくるまで（または最後まで）を元の表記で取り出す。 */
    private fun customerValue(nt: NormalizedText, pos: Int): Pair<FieldValue, Int>? {
        val t = nt.text
        for (p in fillerSkips(t, pos)) {
            Vocabulary.pass.matchAt(t, p)?.let { (_, len) ->
                if (reachesEnd(t, p + len)) return FieldValue.Clear to p + len
            }
        }
        var start = pos
        // 「客先は中川組」のような助詞を読み飛ばす
        Vocabulary.fillers.matchAt(t, start)?.let { (_, len) -> if (start + len < t.length) start += len }
        var end = start
        while (end < t.length && (end == start || Vocabulary.fieldLabels.matchAt(t, end) == null)) end++
        val name = nt.rawSlice(start, end).trim().trim('、', '。', ',', '.', ' ', '　')
        if (name.isEmpty()) return null
        return FieldValue.Single(name) to end
    }

    // ------------------------------------------------------------------
    // 診断（解釈できなかった理由をログに出す。解釈の結果には影響しない）
    // ------------------------------------------------------------------

    /**
     * 解釈できなかった発話について、正規化後の文字列・先頭から解釈できた部分・残った文字を返す。
     * 例: `norm=240明日から4t2期 方式=位置 解釈できた=[ナンバー:240] 残り=「明日から4t2期」`
     */
    fun explain(raw: String): String {
        val nt = NormalizedText.of(raw)
        val t = nt.text
        if (t.isEmpty()) return "norm=（空） 正規化後に文字が残らない"
        if (parseCommand(t) != null) return "norm=$t 方式=コマンド"

        val labelStart = fillerSkips(t, 0).firstOrNull { Vocabulary.fieldLabels.matchAt(t, it) != null }
        val (method, best) = if (labelStart != null) "項目名" to explainLabeled(nt, labelStart)
        else "位置" to explainPositional(t)
        val (pos, acc) = best
        val done = acc.joinToString(" ") { "${it.field.label}:${describe(it.value)}" }
        return "norm=$t 方式=$method 解釈できた=[$done] 残り=「${t.substring(minOf(pos, t.length))}」"
    }

    /** 位置ベースで、先頭から最も遠くまで解釈できた（位置, 解釈した項目）。 */
    private fun explainPositional(t: String): Pair<Int, List<FieldUpdate>> {
        var best: Pair<Int, List<FieldUpdate>> = 0 to emptyList()
        fun search(pos: Int, slot: Int, acc: List<FieldUpdate>) {
            if (pos > best.first || (pos == best.first && acc.size > best.second.size)) best = pos to acc
            if (slot == positionalSlots.size) return
            val field = positionalSlots[slot]
            for (p in fillerSkips(t, pos)) {
                for ((value, end) in slotCandidates(field, t, p)) search(end, slot + 1, acc + FieldUpdate(field, value))
            }
            search(pos, slot + 1, acc)
        }
        search(0, 0, emptyList())
        return best
    }

    /** 項目名+値で、先頭から解釈できたところまで（位置, 解釈した項目）。 */
    private fun explainLabeled(nt: NormalizedText, start: Int): Pair<Int, List<FieldUpdate>> {
        val t = nt.text
        val acc = ArrayList<FieldUpdate>()
        var pos = start
        while (pos < t.length) {
            val labelPos = fillerSkips(t, pos).firstOrNull { Vocabulary.fieldLabels.matchAt(t, it) != null } ?: break
            val (field, len) = Vocabulary.fieldLabels.matchAt(t, labelPos)!!
            val (value, end) = labeledValue(field, nt, labelPos + len) ?: return labelPos to acc
            acc += FieldUpdate(field, value)
            pos = end
        }
        return pos to acc
    }

    private fun describe(v: FieldValue): String = when (v) {
        FieldValue.Clear -> "パス"
        is FieldValue.Single -> v.value
        is FieldValue.Multi -> v.values.joinToString("/")
    }

    // ------------------------------------------------------------------
    // つなぎ言葉
    // ------------------------------------------------------------------

    /** pos から、つなぎ言葉を0個以上読み飛ばして到達できる位置（近い順）。 */
    private fun fillerSkips(t: String, pos: Int): List<Int> {
        val out = mutableListOf(pos)
        var i = 0
        while (i < out.size && out.size < 8) {
            Vocabulary.fillers.matchAt(t, out[i])?.let { (_, len) ->
                val np = out[i] + len
                if (np !in out) out += np
            }
            i++
        }
        return out
    }

    private fun reachesEnd(t: String, pos: Int): Boolean = fillerSkips(t, pos).any { it >= t.length }
}
