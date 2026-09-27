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

    /** 位置ベース（5-1）または項目名+値（5-2）で解釈した項目の更新。 */
    data class Updates(val updates: List<FieldUpdate>, val labeled: Boolean) : Utterance
}

/**
 * 発話の解釈（設計書5章）。
 *
 * - 全体が「送信」「取消」ならコマンド
 * - 先頭が項目名（ナンバー/品目/サイズ/割増/理由/客先/拒否）なら「項目名+値」方式
 * - それ以外は「ナンバー → 品目 → サイズ → 割増」の位置ベース方式
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
    // 位置ベース（ナンバー → 品目 → サイズ → 割増）
    // ------------------------------------------------------------------

    private fun parsePositional(t: String): Utterance? {
        val updates = positionalSearch(t, 0, 0, emptyList()) ?: return null
        return Utterance.Updates(updates, labeled = false)
    }

    /** バックトラックで先頭から各スロットを埋め、全文字を使い切れる解釈を探す。 */
    private fun positionalSearch(t: String, pos: Int, slot: Int, acc: List<FieldUpdate>): List<FieldUpdate>? {
        if (slot == positionalSlots.size) {
            return if (acc.isNotEmpty() && reachesEnd(t, pos)) acc else null
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
