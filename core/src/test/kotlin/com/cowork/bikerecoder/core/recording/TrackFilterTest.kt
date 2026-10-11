package com.cowork.bikerecoder.core.recording

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackFilterTest {

    @Test
    fun `accuracy over 30 m is excluded`() {
        assertNull(TrackFilter().accept(fix(0, 0.0, accuracyM = 30.1f)))
        assertNotNull(TrackFilter().accept(fix(0, 0.0, accuracyM = 30.0f)))
    }

    @Test
    fun `jump over 80 km per h is excluded`() {
        val over = TrackFilter()
        assertNotNull(over.accept(fix(0, 0.0)))
        assertNull(over.accept(fix(1_000, 25.0))) // 90 km/h

        val under = TrackFilter()
        assertNotNull(under.accept(fix(0, 0.0)))
        assertNotNull(under.accept(fix(1_000, 22.0))) // 79.2 km/h
    }

    @Test
    fun `speed falls back to implied speed`() {
        val filter = TrackFilter()
        assertEquals(0.0, filter.accept(fix(0, 0.0))!!.speedMps, 1e-6)
        assertEquals(5.0, filter.accept(fix(1_000, 5.0))!!.speedMps, 1e-6)
    }

    @Test
    fun `reported speed wins over implied speed`() {
        val filter = TrackFilter()
        filter.accept(fix(0, 0.0))
        assertEquals(7.2 / 3.6, filter.accept(fix(1_000, 5.0, speedKmh = 7.2))!!.speedMps, 1e-6)
    }

    @Test
    fun `five consecutive jumps re-anchor without adding distance`() {
        val filter = TrackFilter()
        val first = filter.accept(fix(0, 0.0))
        assertNotNull(first)
        assertFalse(first.reanchored)
        for (k in 1..4) {
            assertNull(filter.accept(fix(k * 1_000L, 500.0 + 5 * k)), "jump $k")
        }
        val fifth = filter.accept(fix(5_000, 525.0))
        assertNotNull(fifth)
        assertTrue(fifth.reanchored)
        // 새 기준점에서 다시 정상 판정: 1초에 5m는 채택, 재기준 아님
        val next = filter.accept(fix(6_000, 530.0))
        assertNotNull(next)
        assertFalse(next.reanchored)
        assertEquals(5.0, next.speedMps, 1e-6)
    }

    @Test
    fun `accepted fix resets the jump counter`() {
        val filter = TrackFilter()
        filter.accept(fix(0, 0.0))
        for (k in 1..4) assertNull(filter.accept(fix(k * 1_000L, 500.0)))
        assertNotNull(filter.accept(fix(5_000, 5.0)))
        for (k in 6..9) assertNull(filter.accept(fix(k * 1_000L, 600.0)))
        // 카운터가 0부터 다시 세므로 다섯 번째 연속 버림에서야 재기준된다
        assertTrue(filter.accept(fix(10_000, 600.0))!!.reanchored)
    }

    @Test
    fun `bad accuracy does not count as a jump`() {
        val filter = TrackFilter()
        filter.accept(fix(0, 0.0))
        for (k in 1..10) assertNull(filter.accept(fix(k * 1_000L, 500.0, accuracyM = 50f)))
        assertNull(filter.accept(fix(11_000, 500.0)))
    }
}
