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
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.PENDING, endedAt, null, endedAt))
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.PENDING, endedAt, null, limit))
        // past the limit too, so an Empty read can turn it into NONE (e.g. V1.1.0 rides first seen after an upgrade)
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.PENDING, endedAt, null, limit + 1))
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
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, null, endedAt + 60_000))
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, null, limit)) // inclusive
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, null, limit + 1))
        assertEquals(HrStatus.FAILED, HrRetryPolicy.after(HrRead.Failed, endedAt, endedAt + 60_000))
        // a read failure stays FAILED even past the limit; shouldTry stops retrying it
        assertEquals(HrStatus.FAILED, HrRetryPolicy.after(HrRead.Failed, endedAt, limit + 1))
    }

    /** R14: a backlog ride whose first real read fails is retried for 24 h from that read, then given up. */
    @Test
    fun failedIsRetriedForADayFromTheFirstReadToo() {
        val firstTry = endedAt + 3 * HrRetryPolicy.GIVE_UP_MS // e.g. a V1.1.0 ride first read after the upgrade
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, firstTry, firstTry + 60_000))
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, firstTry, firstTry + HrRetryPolicy.GIVE_UP_MS))
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, firstTry, firstTry + HrRetryPolicy.GIVE_UP_MS + 1))
        // a first read before the ride's end (cannot happen, but the later of the two counts)
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, endedAt - 5_000, limit))
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, endedAt - 5_000, limit + 1))
        // a first read within the day moves the limit with it
        assertTrue(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, endedAt + 60_000, limit + 60_000))
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.FAILED, endedAt, endedAt + 60_000, limit + 60_001))
    }

    @Test
    fun retryLimitIsTheLaterOfEndAndFirstReadPlusADay() {
        assertEquals(limit, HrRetryPolicy.failedRetryUntil(endedAt, null))
        assertEquals(limit, HrRetryPolicy.failedRetryUntil(endedAt, endedAt - 1))
        assertEquals(limit + 7, HrRetryPolicy.failedRetryUntil(endedAt, endedAt + 7))
    }

    @Test
    fun readyIsNotRetried() {
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.READY, endedAt, null, endedAt))
    }

    @Test
    fun noneIsNotRetried() {
        assertFalse(HrRetryPolicy.shouldTry(HrStatus.NONE, endedAt, null, endedAt))
    }

    @Test
    fun samplesMakeReady() {
        val read = HrRead.Samples(listOf(HeartRateSample(1, 120)), calories = 85.5)
        assertEquals(HrStatus.READY, HrRetryPolicy.after(read, endedAt, endedAt + 60_000))
        assertEquals(HrStatus.READY, HrRetryPolicy.after(read, endedAt, limit + 1)) // late data still counts
    }
}
