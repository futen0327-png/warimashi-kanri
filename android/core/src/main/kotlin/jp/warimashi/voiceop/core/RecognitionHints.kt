package jp.warimashi.voiceop.core

/**
 * 音声認識エンジンに事前に渡す「認識されやすくしたい語」（RecognizerIntent.EXTRA_BIASING_STRINGS）。
 *
 * 固定候補の単語に加え、誤認識しやすい「サイズ＋割増」の続けて言う組み合わせ
 * （例: 2トン2割、10トン不良）も、選べる組み合わせだけフレーズとして渡す。
 */
object RecognitionHints {

    private fun sizeWord(size: String): String = if (size == "軽") "軽" else size.removeSuffix("t") + "トン"

    val words: List<String> by lazy {
        val base = buildList {
            addAll(Vocabulary.items.map { it.value })
            addAll(Vocabulary.sizes.map { sizeWord(it.value) })
            addAll(Vocabulary.surcharges.map { SurchargeRules.label(it.value) })
            addAll(Vocabulary.reasons.map { it.value })
            add("ダブルメッシュ")
            addAll(Vocabulary.rejects.map { it.value })
            addAll(listOf("ナンバー", "品目", "サイズ", "割増", "理由", "客先", "拒否", "パス", "送信", "取消", "確認"))
        }
        // 石・瓦は割増なし固定なので、組み合わせはコンガラ・アスガラ・残土で選べるものだけ
        val combos = Vocabulary.sizes.flatMap { size ->
            listOf("コンガラ", "アスガラ", "残土")
                .flatMap { item -> SurchargeRules.options(item, size.value) }
                .distinct()
                .map { key -> sizeWord(size.value) + SurchargeRules.label(key) }
        }
        (base + combos).distinct()
    }
}
