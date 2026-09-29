package jp.warimashi.voiceop.core

import jp.warimashi.voiceop.core.RemoteButtonFilter.Companion.ACTION_DOWN
import jp.warimashi.voiceop.core.RemoteButtonFilter.Companion.ACTION_UP
import jp.warimashi.voiceop.core.RemoteButtonFilter.Companion.KEYCODE_VOLUME_DOWN
import jp.warimashi.voiceop.core.RemoteButtonFilter.Companion.KEYCODE_VOLUME_UP
import jp.warimashi.voiceop.core.RemoteButtonFilter.Decision.CONSUME
import jp.warimashi.voiceop.core.RemoteButtonFilter.Decision.PASS
import jp.warimashi.voiceop.core.RemoteButtonFilter.Decision.TOGGLE
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteButtonFilterTest {

    private val remote = "BTselfie E03"
    private val filter = RemoteButtonFilter(deviceNameContains = "BTselfie", debounceMs = 400)

    private fun key(
        keyCode: Int = KEYCODE_VOLUME_UP,
        action: Int = ACTION_DOWN,
        repeat: Int = 0,
        device: String? = remote,
        now: Long = 10_000,
        enabled: Boolean = true,
    ) = filter.decide(enabled, keyCode, action, repeat, device, now)

    @Test
    fun remoteVolumeDownPressTogglesAndItsUpIsConsumed() {
        assertEquals(TOGGLE, key(action = ACTION_DOWN, now = 10_000))
        assertEquals(CONSUME, key(action = ACTION_UP, now = 10_006))
    }

    @Test
    fun upAndDownKeyCodesBothMeanOnePress() {
        // ボタンを押すたびに VOLUME_UP と VOLUME_DOWN が交互に届く
        assertEquals(TOGGLE, key(keyCode = KEYCODE_VOLUME_UP, now = 10_000))
        assertEquals(CONSUME, key(keyCode = KEYCODE_VOLUME_UP, action = ACTION_UP, now = 10_005))
        assertEquals(TOGGLE, key(keyCode = KEYCODE_VOLUME_DOWN, now = 12_000))
        assertEquals(CONSUME, key(keyCode = KEYCODE_VOLUME_DOWN, action = ACTION_UP, now = 12_007))
        assertEquals(TOGGLE, key(keyCode = KEYCODE_VOLUME_UP, now = 14_000))
    }

    @Test
    fun builtInVolumeButtonsPassThrough() {
        assertEquals(PASS, key(device = "gpio-keys"))
        assertEquals(PASS, key(keyCode = KEYCODE_VOLUME_DOWN, action = ACTION_UP, device = "gpio-keys"))
        assertEquals(PASS, key(device = null))
    }

    @Test
    fun builtInButtonDoesNotResetDebounce() {
        assertEquals(TOGGLE, key(now = 10_000))
        assertEquals(PASS, key(device = "gpio-keys", now = 10_600))
        assertEquals(TOGGLE, key(now = 10_700))
    }

    @Test
    fun deviceNameMatchIsSubstringAndCaseInsensitive() {
        assertEquals(TOGGLE, key(device = "btselfie", now = 10_000))
        assertEquals(TOGGLE, key(device = "My BTSELFIE remote", now = 20_000))
    }

    @Test
    fun repeatsFromLongPressAreConsumedWithoutToggling() {
        assertEquals(TOGGLE, key(repeat = 0, now = 10_000))
        assertEquals(CONSUME, key(repeat = 1, now = 10_500))
        assertEquals(CONSUME, key(repeat = 2, now = 10_550))
    }

    @Test
    fun pressesWithin400msAreDebounced() {
        assertEquals(TOGGLE, key(now = 10_000))
        assertEquals(CONSUME, key(now = 10_399))
        // 捨てた押下は基準時刻を動かさない: 最初の合図から400ms経てば受け付ける
        assertEquals(TOGGLE, key(now = 10_400))
    }

    @Test
    fun firstPressRightAfterBootIsAccepted() {
        assertEquals(TOGGLE, key(now = 5))
    }

    @Test
    fun otherKeysFromRemoteAreConsumed() {
        assertEquals(CONSUME, key(keyCode = 66 /* ENTER */))
    }

    @Test
    fun disabledSettingPassesEverything() {
        assertEquals(PASS, key(enabled = false, now = 10_000))
        assertEquals(PASS, key(enabled = false, action = ACTION_UP, now = 10_005))
        // OFF 中の押下はデバウンスの基準にならない
        assertEquals(TOGGLE, key(enabled = true, now = 10_100))
    }
}
