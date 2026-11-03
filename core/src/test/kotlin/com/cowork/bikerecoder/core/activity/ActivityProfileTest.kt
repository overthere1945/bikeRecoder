package com.cowork.bikerecoder.core.activity

import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingEvent
import com.cowork.bikerecoder.core.recording.RecordingSession
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.fix
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActivityProfileTest {

    private fun sec(s: Int) = s * 1_000L

    private fun session(type: ActivityType, autoPause: Boolean = true) =
        RecordingSession(0L, autoPause, profile = ActivityProfile.of(type)).also { it.start() }

    private fun RecordingSession.feed(t: Int, m: Double, kmh: Double? = null) =
        onFix(fix(sec(t), m, kmh), sec(t))

    private fun kmh(mps: Double) = mps * 3.6

    private fun pauseAfterSec(p: ActivityProfile) = (p.pauseAfterMs / 1_000).toInt()

    @Test
    fun valuesFollowSpecTable() {
        val bike = ActivityProfile.of(ActivityType.BIKE)
        assertEquals(3.0 / 3.6, bike.pauseBelowMps)
        assertEquals(5_000L, bike.pauseAfterMs)
        assertEquals(5.0 / 3.6, bike.resumeAtMps)
        assertEquals(2_000L, bike.resumeAfterMs)
        assertEquals(15.0, bike.resumeDistanceM)
        assertEquals(80.0 / 3.6, bike.maxSpeedMps)
        assertEquals(3.0 / 3.6, bike.minGapSpeedMps)
        assertEquals(KmBasis.DAY, bike.kmBasis)
        assertEquals(14.8 to 16.8, bike.cameraZoomMin to bike.cameraZoomMax)

        val run = ActivityProfile.of(ActivityType.RUN)
        assertEquals(listOf(2.0, 4.0), listOf(kmh(run.pauseBelowMps), kmh(run.resumeAtMps)).map { Math.round(it * 1e9) / 1e9 })
        assertEquals(5_000L to 2_000L, run.pauseAfterMs to run.resumeAfterMs)
        assertEquals(10.0, run.resumeDistanceM)
        assertEquals(KmBasis.SEGMENT, run.kmBasis)
        assertEquals(15.6 to 17.6, run.cameraZoomMin to run.cameraZoomMax)

        val walk = ActivityProfile.of(ActivityType.WALK)
        assertEquals(10_000L to 3_000L, walk.pauseAfterMs to walk.resumeAfterMs)
        assertEquals(2.5 / 3.6, walk.resumeAtMps)
        assertEquals(KmBasis.SEGMENT, walk.kmBasis)
        assertEquals(15.6 to 17.6, walk.cameraZoomMin to walk.cameraZoomMax)

        val hike = ActivityProfile.of(ActivityType.HIKE)
        assertEquals(0.7 / 3.6, hike.pauseBelowMps)
        assertEquals(20_000L, hike.pauseAfterMs)
        assertEquals(Double.POSITIVE_INFINITY, hike.resumeAtMps)
        assertEquals(0L, hike.resumeAfterMs)
        assertEquals(10.0, hike.resumeDistanceM)
        assertEquals(40.0 / 3.6, hike.maxSpeedMps)
        assertEquals(KmBasis.NONE, hike.kmBasis)
        assertEquals(15.2 to 17.4, hike.cameraZoomMin to hike.cameraZoomMax)
    }

    @Test
    fun pausesJustBelowThreshold() {
        for (type in ActivityType.entries) {
            val p = ActivityProfile.of(type)
            val s = session(type)
            val slow = kmh(p.pauseBelowMps) - 0.1
            val n = pauseAfterSec(p)
            val events = (0..n).flatMap { s.feed(it, 0.0, slow).events }
            assertEquals(listOf<RecordingEvent>(RecordingEvent.Paused(PauseKind.AUTO)), events, "$type")
            assertEquals(RecordingState.Paused(PauseKind.AUTO), s.state, "$type")
        }
    }

    @Test
    fun notPausedOneSecondBeforeThePauseTime() {
        for (type in ActivityType.entries) {
            val p = ActivityProfile.of(type)
            val s = session(type)
            val slow = kmh(p.pauseBelowMps) - 0.1
            (0 until pauseAfterSec(p)).forEach { s.feed(it, 0.0, slow) }
            assertEquals(RecordingState.Recording, s.state, "$type")
        }
    }

    @Test
    fun staysJustAboveThreshold() {
        for (type in ActivityType.entries) {
            val p = ActivityProfile.of(type)
            val s = session(type)
            val above = kmh(p.pauseBelowMps) + 0.1
            val events = (0..pauseAfterSec(p) * 3).flatMap { s.feed(it, 0.0, above).events }
            assertTrue(events.none { it is RecordingEvent.Paused }, "$type")
            assertEquals(RecordingState.Recording, s.state, "$type")
        }
    }

    /** 멈춘 자리에서 거리 기준 이전 속도만으로 자동 일시정지에 들어간 세션과 마지막 시각(초). */
    private fun paused(type: ActivityType): Pair<RecordingSession, Int> {
        val p = ActivityProfile.of(type)
        val s = session(type)
        val n = pauseAfterSec(p)
        (0..n).forEach { s.feed(it, 0.0, 0.0) }
        assertEquals(RecordingState.Paused(PauseKind.AUTO), s.state, "$type")
        return s to n
    }

    @Test
    fun resumesBySpeed() {
        for (type in ActivityType.entries - ActivityType.HIKE) {
            val p = ActivityProfile.of(type)
            val fast = kmh(p.resumeAtMps) + 0.1
            val rs = (p.resumeAfterMs / 1_000).toInt()

            // 위치는 멈춘 자리 가까이(거리 기준 미만)이고 속도 값만 기준 이상이다.
            val (s, n) = paused(type)
            val before = (1..rs).flatMap { s.feed(n + it, 0.0, fast).events }
            assertTrue(before.isEmpty(), "$type 기준 시간 전에는 재개하지 않는다")
            val at = s.feed(n + rs + 1, 0.0, fast)
            assertEquals(listOf<RecordingEvent>(RecordingEvent.Resumed), at.events, "$type")
            assertNotNull(at.point, "$type")

            // 기준 속도 미만이면 오래 가도 재개하지 않는다.
            val (s2, n2) = paused(type)
            val slowish = kmh(p.resumeAtMps) - 0.1
            val events = (1..60).flatMap { s2.feed(n2 + it, 0.0, slowish).events }
            assertTrue(events.isEmpty(), "$type")
        }
    }

    @Test
    fun hikeNeverResumesBySpeed() {
        val (s, n) = paused(ActivityType.HIKE)
        // 10 km/h로 10초 달려도 위치가 그 자리(15 m 안)면 재개하지 않는다.
        val events = (1..10).flatMap { s.feed(n + it, 1.0 * (it % 3), 10.0).events }
        assertTrue(events.isEmpty())
        assertEquals(RecordingState.Paused(PauseKind.AUTO), s.state)
    }

    @Test
    fun resumesByDistance() {
        for (type in ActivityType.entries) {
            val p = ActivityProfile.of(type)
            val (s, n) = paused(type)
            val near = s.feed(n + 2, p.resumeDistanceM - 1.0, 0.0)
            assertTrue(near.events.isEmpty(), "$type 거리 기준 미만")
            val far = s.feed(n + 4, p.resumeDistanceM + 1.0, 0.0)
            assertEquals(listOf<RecordingEvent>(RecordingEvent.Resumed), far.events, "$type")
            assertEquals(RecordingState.Recording, s.state, "$type")
        }
    }

    @Test
    fun filterRejectsAboveMaxSpeed() {
        for (type in ActivityType.entries) {
            val limit = kmh(ActivityProfile.of(type).maxSpeedMps)
            for ((delta, accepted) in listOf(1.0 to false, -1.0 to true)) {
                val s = session(type, autoPause = false)
                assertNotNull(s.feed(0, 0.0).point)
                val jump = (limit + delta) / 3.6 * 2 // 2초 동안 간 거리
                val point = s.feed(2, jump).point
                assertEquals(accepted, point != null, "$type ${limit + delta} km/h")
            }
        }
    }

    @Test
    fun gapCountsAboveMinGapSpeed() {
        for (type in ActivityType.entries) {
            val min = kmh(ActivityProfile.of(type).minGapSpeedMps)
            val above = RecordingSession(0L, false, profile = ActivityProfile.of(type)).also {
                it.feed(0, 0.0)
                it.feed(12, (min + 0.1) / 3.6 * 12)
            }
            assertTrue(above.stats.distanceM > 0.0, "$type 기준보다 빠른 끊김은 센다")
            assertTrue(above.stats.movingTimeMs == 12_000L, "$type")

            val below = RecordingSession(0L, false, profile = ActivityProfile.of(type)).also {
                it.feed(0, 0.0)
                it.feed(12, (min - 0.1) / 3.6 * 12)
            }
            assertEquals(0.0, below.stats.distanceM, "$type 기준보다 느린 끊김은 세지 않는다")
            assertEquals(0L, below.stats.movingTimeMs, "$type")
        }
    }

    @Test
    fun hikeGapPointsFromSpec() {
        // 스펙 예: 등산 12초 틈 + 0.8 km/h → 거리에 들어간다, 0.6 km/h → 들어가지 않는다
        fun distanceFor(kmh: Double) = RecordingSession(0L, false, profile = ActivityProfile.of(ActivityType.HIKE)).let {
            it.feed(0, 0.0)
            it.feed(12, kmh / 3.6 * 12)
            it.stats.distanceM
        }
        assertTrue(distanceFor(0.8) > 0.0)
        assertEquals(0.0, distanceFor(0.6))
    }
}
