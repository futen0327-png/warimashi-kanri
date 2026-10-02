package jp.warimashi.voiceop.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopySearchTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun entry(plate: String, customer: String, time: String?, deleted: Boolean = false) =
        RecordedEntry(plate, "コンガラ", "4t", "20", listOf("大きさ"), customer, time?.let { Instant.parse(it) }, deleted)

    @Test
    fun todayUsesLocalDate() {
        val now = Instant.parse("2026-09-30T01:00:00Z") // 9/30 10:00 JST
        val entries = listOf(
            entry("1", "中川組", "2026-09-29T14:59:59Z"), // 9/29 23:59 JST → 前日
            entry("2", "中川組", "2026-09-29T15:00:00Z"), // 9/30 00:00 JST → 当日
            entry("3", "中川組", "2026-09-30T00:30:00Z"),
            entry("4", "中川組", "2026-09-30T00:40:00Z", deleted = true),
            entry("5", "中川組", null),
        )
        assertEquals(listOf("2", "3"), CopySearch.today(entries, now, tokyo).map { it.plate })
    }

    @Test
    fun yesterdayIsNotCopied() {
        val now = Instant.parse("2026-09-30T01:00:00Z")
        val today = CopySearch.today(listOf(entry("1234", "中川組", "2026-09-29T05:00:00Z")), now, tokyo)
        assertNull(CopySearch.find(today, "中川組", null))
        assertNull(CopySearch.find(today, null, null))
    }

    @Test
    fun findLatestMatching() {
        val today = listOf(
            entry("1234", "中川組", "2026-09-30T00:10:00Z"),
            entry("1234", "中川組", "2026-09-30T00:50:00Z"),
            entry("5678", "中川組", "2026-09-30T00:30:00Z"),
            entry("1234", "西川組", "2026-09-30T00:40:00Z"),
        )
        assertEquals(Instant.parse("2026-09-30T00:50:00Z"), CopySearch.find(today, null, null)?.timestamp)
        assertEquals("5678", CopySearch.find(today, "中川組", "5678")?.plate)
        assertEquals(Instant.parse("2026-09-30T00:50:00Z"), CopySearch.find(today, null, "1234")?.timestamp)
        assertEquals(Instant.parse("2026-09-30T00:40:00Z"), CopySearch.find(today, "西川組", null)?.timestamp)
        assertNull(CopySearch.find(today, "丸本建材", null))
        assertNull(CopySearch.find(today, "西川組", "5678"))
    }

    @Test
    fun toBufferSplitsFields() {
        val b = RecordedEntry("", "瓦", "軽", "none", emptyList(), "", Instant.EPOCH).toBuffer(listOf("中川組"))
        assertNull(b.plate)
        assertNull(b.customer)
        assertTrue(b.surchargeAuto)
        assertTrue(b.isSendable)
        val unreg = entry("1", "新顧客", "2026-09-30T00:00:00Z").toBuffer(listOf("中川組"))
        assertEquals(false, unreg.customerRegistered)
    }
}
