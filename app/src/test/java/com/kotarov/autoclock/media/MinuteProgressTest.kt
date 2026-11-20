package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MinuteProgressTest {
    @Test fun startsAtActualSecondWhenPlaybackStartsMidMinute() {
        assertEquals(24_250L, MinuteProgress.positionMs(24_250L))
        assertEquals(33_000L, MinuteProgress.positionMs(7L * 60_000L + 33_000L))
        assertEquals(59_999L, MinuteProgress.positionMs(2L * 60_000L + 59_999L))
    }

    @Test fun resetsAtExactMinuteBoundary() {
        assertEquals(0L, MinuteProgress.positionMs(60_000L))
        assertEquals(0L, MinuteProgress.positionMs(3_600_000L))
        assertEquals(1L, MinuteProgress.positionMs(3_600_001L))
    }

    @Test fun schedulingUsesTheRemainingFractionOfCurrentMinute() {
        assertEquals(60_150L, MinuteProgress.millisUntilNextMinute(0L))
        assertEquals(35_900L, MinuteProgress.millisUntilNextMinute(24_250L))
        assertEquals(151L, MinuteProgress.millisUntilNextMinute(59_999L))
    }

    @Test fun negativeTimestampsDoNotProduceNegativeProgress() {
        assertEquals(59_999L, MinuteProgress.positionMs(-1L))
    }
}
