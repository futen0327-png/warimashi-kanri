package jp.warimashi.voiceop.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionHintsTest {

    private val words = RecognitionHints.words

    @Test
    fun containsFixedCandidates() {
        listOf("コンガラ", "アスガラ", "残土", "石", "瓦", "軽", "2トン", "10トン", "割増なし", "2割", "4割",
            "不良", "良", "普通", "切削", "大きさ", "有筋", "Wメッシュ", "二次製品", "赤レンガ", "大きすぎる")
            .forEach { assertTrue(it, it in words) }
    }

    @Test
    fun containsOnlyValidSizeSurchargeCombos() {
        assertTrue("2トン2割" in words)
        assertTrue("10トン不良" in words)
        assertTrue("10トン切削" in words)
        assertTrue("4トン割増なし" in words)
        assertFalse("2トン切削" in words)
        assertFalse("10トン割増なし" in words)
    }

    @Test
    fun noDuplicates() {
        assertTrue(words.size == words.distinct().size)
    }
}
