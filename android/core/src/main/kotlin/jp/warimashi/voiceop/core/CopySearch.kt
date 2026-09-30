package jp.warimashi.voiceop.core

import java.time.Instant
import java.time.ZoneId

/** Firebase `warashi_entries` に登録済みの1件（コピー元）。値は送信時と同じ形式。 */
data class RecordedEntry(
    val plate: String,
    val item: String,
    val vehicle: String,
    val surcharge: String,
    /** カンマ区切りを分割したもの。 */
    val reasons: List<String>,
    val customer: String,
    val timestamp: Instant?,
    /** 事務所・オペ画面で削除済み（deletedFromOffice / deletedFromOperator）。 */
    val deleted: Boolean = false,
) {
    /**
     * 入力欄に写した状態。品目・サイズ・割増・理由・客先・ナンバーを写し、拒否（memo）は写さない。
     * @param customers 登録済み顧客名（未登録の表示に使う。空なら未取得として登録済み扱い）
     */
    fun toBuffer(customers: List<String>): EntryBuffer {
        val name = customer.trim().ifEmpty { null }
        val fixed = SurchargeRules.fixedFor(item)
        return EntryBuffer(
            plate = plate.trim().ifEmpty { null },
            item = item.ifEmpty { null },
            size = vehicle.ifEmpty { null },
            surcharge = surcharge.ifEmpty { null },
            surchargeAuto = fixed != null && fixed == surcharge,
            reasons = reasons,
            customer = name,
            customerRegistered = name == null || customers.isEmpty() || customers.any { it.trim() == name },
        )
    }
}

/** 「コピー」の検索（当日分から、条件に合う最新の1件）。 */
object CopySearch {

    /** 当日（zone での日付）の、削除されていない登録。 */
    fun today(entries: List<RecordedEntry>, now: Instant, zone: ZoneId): List<RecordedEntry> {
        val date = now.atZone(zone).toLocalDate()
        return entries.filter { e ->
            !e.deleted && e.timestamp != null && e.timestamp.atZone(zone).toLocalDate() == date
        }
    }

    /**
     * 条件に合う最新の1件。条件がどちらも null なら直前の登録。
     * 客先名は当日の客先名と編集距離で照合し（認識の揺れ対策）、どれにも近くなければ該当なし。
     * ナンバーは完全一致。
     */
    fun find(today: List<RecordedEntry>, customer: String?, plate: String?): RecordedEntry? {
        var hits = today
        if (customer != null) {
            // 名前は当日の全客先から先に決める（ナンバーで絞ってから照合すると、別の客先に寄せてしまう）
            val names = today.map { it.customer.trim() }.filter { it.isNotEmpty() }.distinct()
            val m = CustomerMatcher(names).match(listOf(customer)) ?: return null
            if (!m.registered) return null
            hits = hits.filter { it.customer.trim() == m.name }
        }
        if (plate != null) hits = hits.filter { it.plate.trim() == plate }
        return hits.maxByOrNull { it.timestamp ?: Instant.MIN }
    }
}
