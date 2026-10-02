package jp.warimashi.voiceop.core

import java.text.Normalizer

/**
 * 音声認識結果を照合しやすい形に正規化したテキスト。
 *
 * - NFKC正規化（全角英数→半角など）・英字は小文字化
 * - カタカナ→ひらがな
 * - 空白・句読点の除去
 * - 漢数字（一二三…、十百千）→算用数字
 *
 * 正規化後の各文字が元テキストのどこに対応するかを保持しているので、
 * 顧客名のような自由記述は元の表記（カタカナ・漢字）のまま取り出せる。
 */
class NormalizedText private constructor(
    val raw: String,
    val text: String,
    private val rawStarts: IntArray,
    private val rawEnds: IntArray,
) {
    val length: Int get() = text.length

    /** 正規化後の範囲 [start, end) に対応する元テキストを返す。 */
    fun rawSlice(start: Int, end: Int): String {
        if (start >= end) return ""
        return raw.substring(rawStarts[start], rawEnds[end - 1])
    }

    companion object {
        private const val PUNCT = "、。,.，．・!?！？「」『』()（）[]［］…‥~〜～:：;；'\"`"

        fun of(raw: String): NormalizedText {
            val chars = StringBuilder()
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()

            // 1文字（コードポイント）ずつNFKC＋小文字化＋カナ変換
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                val next = i + Character.charCount(cp)
                val piece = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC)
                    .lowercase()
                for (ch in piece) {
                    if (ch.isWhitespace() || PUNCT.indexOf(ch) >= 0) continue
                    // 半角カナの濁点・半濁点（ｽﾞ→ず）は直前の文字と合成する
                    if ((ch == '゙' || ch == '゚') && chars.isNotEmpty()) {
                        val composed = Normalizer.normalize("${chars.last()}$ch", Normalizer.Form.NFC)
                        if (composed.length == 1) {
                            chars.setCharAt(chars.length - 1, composed[0])
                            ends[ends.size - 1] = next
                            continue
                        }
                    }
                    chars.append(kataToHira(ch))
                    starts.add(i)
                    ends.add(next)
                }
                i = next
            }

            // 漢数字の連続を算用数字に置き換える
            val outChars = StringBuilder()
            val outStarts = ArrayList<Int>()
            val outEnds = ArrayList<Int>()
            var p = 0
            while (p < chars.length) {
                if (isKanjiNumeral(chars[p])) {
                    var q = p
                    while (q < chars.length && isKanjiNumeral(chars[q])) q++
                    val digits = kanjiRunToDigits(chars.substring(p, q))
                    if (digits != null) {
                        for (d in digits) {
                            outChars.append(d)
                            outStarts.add(starts[p])
                            outEnds.add(ends[q - 1])
                        }
                        p = q
                        continue
                    }
                }
                outChars.append(chars[p])
                outStarts.add(starts[p])
                outEnds.add(ends[p])
                p++
            }
            return NormalizedText(raw, outChars.toString(), outStarts.toIntArray(), outEnds.toIntArray())
        }

        /** 語彙の同義語など、位置情報が不要な場合の正規化。 */
        fun normalize(s: String): String = of(s).text

        private fun kataToHira(ch: Char): Char =
            if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch

        private const val KANJI_DIGITS = "〇零一二三四五六七八九"
        private const val KANJI_UNITS = "十百千"

        private fun isKanjiNumeral(ch: Char) = KANJI_DIGITS.indexOf(ch) >= 0 || KANJI_UNITS.indexOf(ch) >= 0

        private fun digitValue(ch: Char): Int = when (ch) {
            '〇', '零' -> 0
            else -> KANJI_DIGITS.indexOf(ch) - 1
        }

        /**
         * 「一二三四」→"1234"（位取りなし）、「千二百三十四」→"1234"（位取りあり）。
         * 解釈できなければ null（元の文字のまま残す）。
         */
        internal fun kanjiRunToDigits(run: String): String? {
            if (run.none { KANJI_UNITS.indexOf(it) >= 0 }) {
                return run.map { digitValue(it) }.joinToString("")
            }
            var total = 0
            var current = -1 // 直前の数字（未指定なら -1）
            var lastUnit = Int.MAX_VALUE
            for (ch in run) {
                val unit = when (ch) {
                    '十' -> 10
                    '百' -> 100
                    '千' -> 1000
                    else -> 0
                }
                if (unit == 0) {
                    if (current >= 0) return null // 「二三百」のような並びは解釈しない
                    current = digitValue(ch)
                } else {
                    if (unit >= lastUnit) return null
                    total += (if (current < 0) 1 else current) * unit
                    current = -1
                    lastUnit = unit
                }
            }
            if (current >= 0) total += current
            return total.toString()
        }
    }
}
