package com.runanywhere.kotlin_starter_example.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingUiUpdateThrottlerTest {
    @Test
    fun coalescesUpdatesUntilTheQueuedSnapshotIsDispatched() {
        val throttler = StreamingUiUpdateThrottler(minimumIntervalMs = 50)

        assertEquals(0L, throttler.scheduleDelayMs(nowMs = 100))
        assertNull(throttler.scheduleDelayMs(nowMs = 101))

        throttler.markDispatched(nowMs = 100)

        assertEquals(49L, throttler.scheduleDelayMs(nowMs = 101))
    }

    @Test
    fun allowsTheFirstUpdateImmediately() {
        val throttler = StreamingUiUpdateThrottler(minimumIntervalMs = 50)

        assertEquals(0L, throttler.scheduleDelayMs(nowMs = 0))
    }
}
