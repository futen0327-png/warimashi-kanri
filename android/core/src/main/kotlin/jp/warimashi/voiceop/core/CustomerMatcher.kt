package jp.warimashi.voiceop.core

/**
 * 顧客名の候補照合（設計書9章）。AI API は使わず、端末内の編集距離だけで判定する。
 */
class CustomerMatcher(registered: List<String>, private val threshold: Double = 0.5) {

    data class Result(
        /** 送信に使う顧客名（登録名に補正済み、または認識結果そのまま）。 */
        val name: String,
        /** 登録済みリストの名前に一致・補正できたか。 */
        val registered: Boolean,
        /** 0.0〜1.0 の近さ。 */
        val score: Double,
    )

    private val entries: List<Pair<String, String>> =
        registered.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { it to key(it) }

    /**
     * 音声認識の候補（上位N件）それぞれについて登録名との近さを測り、最も近いものに補正する。
     * どれも閾値に届かなければ、第1候補をそのまま返す。
     */
    fun match(heard: List<String>): Result? {
        val cleaned = heard.map { stripHonorific(it.trim()) }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        var best: Result? = null
        for (h in cleaned) {
            val hk = key(h)
            if (hk.isEmpty()) continue
            for ((name, nk) in entries) {
                val score = similarity(hk, nk)
                if (best == null || score > best.score) best = Result(name, true, score)
            }
        }
        if (best != null && best.score >= threshold) return best
        return Result(cleaned.first(), false, best?.score ?: 0.0)
    }

    companion object {
        private val HONORIFICS = listOf("さん", "様", "さま", "殿", "御中")
        private val COMPANY_WORDS = listOf("株式会社", "有限会社", "合同会社", "(株)", "(有)", "㈱", "㈲", "かぶしきがいしゃ", "ゆうげんがいしゃ")

        private fun stripHonorific(s: String): String {
            var r = s
            for (h in HONORIFICS) if (r.endsWith(h) && r.length > h.length) r = r.dropLast(h.length)
            return r.trim()
        }

        /** 比較用のキー（正規化＋法人格・敬称の除去）。 */
        internal fun key(s: String): String {
            var r = NormalizedText.normalize(stripHonorific(s))
            for (w in COMPANY_WORDS) r = r.replace(NormalizedText.normalize(w), "")
            return r
        }

        internal fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            val maxLen = maxOf(a.length, b.length)
            if (maxLen == 0) return 0.0
            return 1.0 - levenshtein(a, b).toDouble() / maxLen
        }

        internal fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                val tmp = prev; prev = cur; cur = tmp
            }
            return prev[b.length]
        }
    }
}
