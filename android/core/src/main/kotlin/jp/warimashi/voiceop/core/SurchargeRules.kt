package jp.warimashi.voiceop.core

/** 割増区分の候補ロジック（設計書4章。既存PWAの getSurOpts と同一）。 */
object SurchargeRules {

    /** 品目×サイズで選べる割増キーの一覧。どちらかが未確定なら空。 */
    fun options(item: String?, size: String?): List<String> {
        if (item == null || size == null) return emptyList()
        if (item == "石" || item == "瓦") return listOf("none")
        if (size == "10t") {
            return if (item == "アスガラ") listOf("cutting", "normal", "20", "40", "bad")
            else listOf("good", "normal", "20", "40", "bad")
        }
        return listOf("none", "20", "40", "bad")
    }

    /** 品目だけで割増が決まる場合（石・瓦は「割増なし」固定）。 */
    fun fixedFor(item: String?): String? = if (item == "石" || item == "瓦") "none" else null

    /** 品目・サイズが揃っていて、割増がその候補に含まれるか。 */
    fun isAllowed(item: String?, size: String?, surcharge: String?): Boolean =
        surcharge != null && surcharge in options(item, size)

    /** この割増区分で理由が未入力なら、読み上げの最後に「理由をどうぞ」と促す。 */
    val PROMPTS_REASON: Set<String> = setOf("20", "40", "bad")

    fun promptsReason(b: EntryBuffer): Boolean = b.surcharge in PROMPTS_REASON && b.reasons.isEmpty()

    /** 既存PWAの scLbl と同じ表示名。 */
    fun label(key: String): String = when (key) {
        "none" -> "割増なし"
        "good" -> "良"
        "normal" -> "普通"
        "cutting" -> "切削"
        "20" -> "2割"
        "40" -> "4割"
        "bad" -> "不良"
        else -> key
    }
}
