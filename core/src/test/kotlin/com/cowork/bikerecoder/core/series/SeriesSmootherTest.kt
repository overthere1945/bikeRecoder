package com.cowork.bikerecoder.core.series

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesSmootherTest {

    /** 1 Hz, 한 구간. */
    private fun smooth1Hz(values: List<Double?>, windowMs: Long = SeriesSmoother.SPEED_WINDOW_MS): List<Double?> =
        SeriesSmoother.smooth(
            LongArray(values.size) { it * 1_000L },
            IntArray(values.size),
            values,
            windowMs,
        )

    @Test
    fun singleNeedleRemoved() {
        val out = smooth1Hz(listOf(10.0, 10.0, 10.0, 0.0, 10.0, 10.0, 10.0))
        assertTrue(out[3]!! > 9, "needle survived: ${out[3]}")
    }

    @Test
    fun fiveSecondStopKept() {
        val values = List(10) { 10.0 } + List(5) { 0.0 } + List(10) { 10.0 }
        val out = smooth1Hz(values)
        assertTrue(out[12]!! < 2.0, "stop was averaged away: ${out[12]}")
    }

    @Test
    fun gapNotCrossed() {
        // 앞쪽 0 두 점(0, 1 s), 10.001 s 끊김, 뒤쪽 20 열 점.
        // 끊지 않으면 1번 점의 중앙값이 [0, 0, 20, 20]의 10이 된다.
        val times = LongArray(12) { i -> if (i < 2) i * 1_000L else 1_000L + 10_001L + (i - 2) * 1_000L }
        val values = List(2) { 0.0 } + List(10) { 20.0 }
        val out = SeriesSmoother.smooth(times, IntArray(12), values, SeriesSmoother.HEART_WINDOW_MS)
        assertEquals(0.0, out[1])
        assertEquals(20.0, out[2])
        out.subList(0, 2).forEach { assertEquals(0.0, it) }
        out.subList(2, 12).forEach { assertEquals(20.0, it) }
    }

    @Test
    fun segmentBoundaryNotCrossed() {
        // 시각은 1초 간격으로 이어지지만 구간이 바뀐다.
        val times = LongArray(13) { it * 1_000L }
        val segmentOf = IntArray(13) { if (it < 3) 0 else 1 }
        val values = List(3) { 0.0 } + List(10) { 20.0 }
        val out = SeriesSmoother.smooth(times, segmentOf, values, SeriesSmoother.HEART_WINDOW_MS)
        out.subList(0, 3).forEach { assertEquals(0.0, it) }
        out.subList(3, 13).forEach { assertEquals(20.0, it) }
    }

    @Test
    fun nullsKeptAndSkipped() {
        val out = smooth1Hz(listOf(10.0, null, 10.0, 10.0, null, null, 10.0, 10.0), SeriesSmoother.HEART_WINDOW_MS)
        assertNull(out[1])
        assertNull(out[4])
        assertNull(out[5])
        for (i in listOf(0, 2, 3, 6, 7)) assertEquals(10.0, out[i]!!, 1e-12, "index $i")

        val allNull = smooth1Hz(listOf(null, null, null))
        assertEquals(listOf<Double?>(null, null, null), allNull)
        assertEquals(emptyList(), smooth1Hz(emptyList()))
    }

    @Test
    fun windowWidthsDiffer() {
        val values = List(20) { 0.0 } + List(20) { 20.0 }
        val speed = smooth1Hz(values, SeriesSmoother.SPEED_WINDOW_MS)
        val heart = smooth1Hz(values, SeriesSmoother.HEART_WINDOW_MS)
        assertNotEquals(speed, heart)
        assertEquals(0.0, speed[17]!!, 1e-12)
        assertTrue(heart[17]!! > 0.0)
    }

    @Test
    fun smoothsHundredThousandPointsQuickly() {
        val n = 100_000
        val times = LongArray(n) { it * 1_000L + (it % 7) * 50L }
        val segmentOf = IntArray(n) { it / 20_000 }
        val values = List(n) { i -> if (i % 97 == 0) null else 15.0 + (i % 13) - (i % 5) * 0.7 }
        SeriesSmoother.smooth(times.copyOf(1_000), segmentOf.copyOf(1_000), values.subList(0, 1_000), 5_000L) // JIT 데우기

        val start = System.nanoTime()
        val out = SeriesSmoother.smooth(times, segmentOf, values, SeriesSmoother.HEART_WINDOW_MS)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(n, out.size)
        assertTrue(ms < 1_000, "took $ms ms")
    }
}
