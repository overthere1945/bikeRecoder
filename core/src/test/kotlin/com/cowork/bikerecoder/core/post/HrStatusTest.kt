package com.cowork.bikerecoder.core.post

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HrStatusTest {

    private val endedAt = 1_000_000L
    private val limit = endedAt + HrRetryPolicy.GIVE_UP_MS

    @Test
    fun giveUpIsTwentyFourHours() {
        assertEquals(24 * 60 * 60_000L, HrRetryPolicy.GIVE_UP_MS)
    }

    @Test
    fun pendingIsAlwaysTried() {
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.PENDING, endedAt, endedAt))
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.PENDING, endedAt, limit))
        // past the limit too, so an Empty read can turn it into NONE (e.g. V1.1.0 rides first seen after an upgrade)
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.PENDING, endedAt, limit + 1))
        assertEquals(HrStatus.NONE, HrRetryPolicy.after(HrRead.Empty, endedAt, limit + 1))
    }

    @Test
    fun emptyBeforeLimitStaysPending() {
        assertEquals(HrStatus.PENDING, HrRetryPolicy.after(HrRead.Empty, endedAt, endedAt + 60_000))
        assertEquals(HrStatus.PENDING, HrRetryPolicy.after(HrRead.Empty, endedAt, limit)) // inclusive
    }

    @Test
    fun emptyAfterLimitIsNone() {
        assertEquals(HrStatus.NONE, HrRetryPolicy.after(HrRead.Empty, endedAt, limit + 1))
    }

    @Test
    fun failedIsRetried() {
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, endedAt + 60_000))
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, limit)) // inclusive
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, limit + 1))
        assertEquals(HrStatus.FAILED, HrRetryPolicy.after(HrRead.Failed, endedAt, endedAt + 60_000))
        // a read failure stays FAILED even past the limit; shouldTry stops retrying it
        assertEquals(HrStatus.FAILED, HrRetryPolicy.after(HrRead.Failed, endedAt, limit + 1))
    }

    @Test
    fun readyIsNotRetried() {
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.READY, endedAt, endedAt))
    }

    @Test
    fun noneIsNotRetried() {
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.NONE, endedAt, endedAt))
    }

    @Test
    fun samplesMakeReady() {
        val read = HrRead.Samples(listOf(HeartRateSample(1, 120)), calories = 85.5)
        assertEquals(HrStatus.READY, HrRetryPolicy.after(read, endedAt, endedAt + 60_000))
        assertEquals(HrStatus.READY, HrRetryPolicy.after(read, endedAt, limit + 1)) // late data still counts
    }
}
