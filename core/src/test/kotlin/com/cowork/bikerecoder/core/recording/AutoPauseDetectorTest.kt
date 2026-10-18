package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPauseDetectorTest {

    private fun mps(kmh: Double) = kmh / 3.6

    private fun AutoPauseDetector.pauseAt(sec: Int, kmh: Double, p: GeoPoint = north(0.0)) =
        shouldPause(sec * 1_000L, mps(kmh), p)

    private fun AutoPauseDetector.resumeAt(sec: Int, kmh: Double, p: GeoPoint = north(0.0)) =
        shouldResume(sec * 1_000L, mps(kmh), p)

    /** 2km/h로 t=0..5초 → 정지 지점 north(0)에서 일시정지된 감지기. */
    private fun pausedDetector(): AutoPauseDetector {
        val d = AutoPauseDetector()
        for (s in 0..5) d.pauseAt(s, 2.0)
        return d
    }

    @Test
    fun `under 3 km per h for 5 s pauses`() {
        val d = AutoPauseDetector()
        val results = (0..5).map { d.pauseAt(it, 2.0) }
        assertEquals(listOf(false, false, false, false, false, true), results)
    }

    @Test
    fun `4 s slow is not a pause`() {
        val d = AutoPauseDetector()
        val results = (0..4).map { d.pauseAt(it, 2.0) } + d.pauseAt(5, 10.0)
        assertTrue(results.none { it })
    }

    @Test
    fun `exactly 3 km per h is not slow`() {
        val d = AutoPauseDetector()
        assertTrue((0..10).none { d.pauseAt(it, 3.0) })
    }

    @Test
    fun `5 km per h for 2 s resumes`() {
        val d = pausedDetector()
        assertFalse(d.resumeAt(6, 5.0))
        assertFalse(d.resumeAt(7, 5.0))
        assertTrue(d.resumeAt(8, 5.0))
    }

    @Test
    fun `rolling at 4 km per h does not resume`() {
        val d = pausedDetector()
        // 4km/h = 1.11 m/s → 10초 동안 11.1m 이동(15m 미만)
        val results = (0..10).map { d.resumeAt(6 + it, 4.0, north(it * mps(4.0))) }
        assertTrue(results.none { it })
    }

    @Test
    fun `moving 15 m from the stop resumes`() {
        val d = pausedDetector()
        assertFalse(d.resumeAt(6, 0.0, north(14.9)))
        assertTrue(d.resumeAt(7, 0.0, north(15.1)))
    }

    @Test
    fun `reset forgets slow and fast start times`() {
        val d = AutoPauseDetector()
        for (s in 0..4) assertFalse(d.pauseAt(s, 2.0))
        d.reset()
        val results = (5..10).map { d.pauseAt(it, 2.0) }
        assertEquals(listOf(false, false, false, false, false, true), results)

        val f = pausedDetector()
        assertFalse(f.resumeAt(6, 5.0))
        f.reset()
        assertFalse(f.resumeAt(8, 5.0)) // 빠름 시작이 t=8초로 다시 잡힌다
        assertTrue(f.resumeAt(10, 5.0))
    }

    @Test
    fun `a fast fix restarts the slow streak`() {
        val d = AutoPauseDetector()
        val results = (0..3).map { d.pauseAt(it, 2.0) } +
            d.pauseAt(4, 10.0) +
            (5..8).map { d.pauseAt(it, 2.0) }
        assertTrue(results.none { it }) // 끊기지 않았다면 t=5초에 true
    }

    @Test
    fun `a slow fix restarts the fast streak`() {
        val d = pausedDetector()
        assertFalse(d.resumeAt(6, 5.0))
        assertFalse(d.resumeAt(7, 2.0))
        assertFalse(d.resumeAt(8, 5.0))
        assertFalse(d.resumeAt(9, 5.0))
        assertTrue(d.resumeAt(10, 5.0)) // 빠름 시작이 t=8초로 다시 잡힌다
    }

    @Test
    fun `reset forgets the stop point`() {
        val d = pausedDetector()
        d.reset()
        assertFalse(d.resumeAt(6, 0.0, north(20.0)))
    }
}
