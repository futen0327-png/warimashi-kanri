package jp.warimashi.voiceop.core

/**
 * 送信前の一時バッファ（設計書6章）。1台分の入力をためておき、「送信」で Firebase に送る。
 */
data class EntryBuffer(
    val plate: String? = null,
    val item: String? = null,
    /** 車両サイズ。Firebase では既存PWAに合わせて `vehicle` として送る。 */
    val size: String? = null,
    /** 割増キー（none/good/normal/cutting/20/40/bad）。 */
    val surcharge: String? = null,
    /** 石・瓦で自動的に「割増なし」にした場合 true。 */
    val surchargeAuto: Boolean = false,
    val reasons: List<String> = emptyList(),
    val customer: String? = null,
    val customerRegistered: Boolean = true,
    val rejects: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = this == EntryBuffer()

    /** 必須3項目（品目・サイズ・割増）のうち未入力のもの。 */
    val missingRequired: List<Field>
        get() = listOfNotNull(
            Field.ITEM.takeIf { item == null },
            Field.SIZE.takeIf { size == null },
            Field.SURCHARGE.takeIf { surcharge == null },
        )

    val isSendable: Boolean
        get() = missingRequired.isEmpty() && SurchargeRules.isAllowed(item, size, surcharge)

    /**
     * Firebase `warashi_entries` に push する内容。既存PWAの submitEntry と同じ形式。
     * 受入拒否品目（旧・備考）は memo に入れる。
     */
    fun toEntry(timestampIso: String): Map<String, Any> {
        check(isSendable) { "必須項目が揃っていません" }
        return linkedMapOf(
            "plate" to (plate ?: ""),
            "item" to item!!,
            "vehicle" to size!!,
            "surcharge" to surcharge!!,
            "reasons" to reasons.joinToString(","),
            "memo" to rejects.joinToString("、"),
            "customer" to (customer ?: ""),
            "timestamp" to timestampIso,
            "confirmed" to false,
        )
    }

    companion object {
        const val MAX_REASONS = 4
    }
}
