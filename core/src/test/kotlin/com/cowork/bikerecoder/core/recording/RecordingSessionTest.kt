package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.LocationFix
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingSessionTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    /** 1Hz 픽스처는 now = fix.timeMillis로 넣는다. */
    private fun RecordingSession.feed(f: LocationFix) = onFix(f, f.timeMillis)

    private fun sec(s: Int) = s * 1_000L

    /** Barometric.altitudeM의 역함수. */
    private fun pressureFor(altM: Double) = 1013.25 * (1 - altM / 44330).pow(5.255)

    private fun started(autoPause: Boolean, startMillis: Long = 0L, initialDay: DayTotals = DayTotals.ZERO) =
        RecordingSession(startMillis, autoPause, initialDay).also { it.start() }

    /** 0→100m(18km/h, t=0..20), 100m에서 속도 0으로 t=21..40. t=26에 자동 일시정지. */
    private fun autoPausedAt100m(): Pair<RecordingSession, List<RecordingUpdate>> {
        val s = started(autoPause = true)
        val updates = (0..20).map { s.feed(fix(sec(it), 5.0 * it, 18.0)) } +
            (21..40).map { s.feed(fix(sec(it), 100.0, 0.0)) }
        return s to updates
    }

    @Test
    fun `start emits Started`() {
        val s = RecordingSession(0L, autoPause = true)
        val u = s.start()
        assertEquals(listOf<RecordingEvent>(RecordingEvent.Started), u.events)
        assertEquals(RecordingState.Recording, u.state)
        assertEquals(RecordingState.Recording, s.state)
        assertEquals(emptyList(), s.start().events) // 한 번만
    }

    @Test
    fun `manual mode never auto pauses`() {
        val s = started(autoPause = false)
        val updates = (0..30).map { s.feed(fix(sec(it), 0.0, 0.0)) }
        assertEquals(RecordingState.Recording, s.state)
        assertTrue(updates.flatMap { it.events }.none { it is RecordingEvent.Paused })
    }

    @Test
    fun `buttons pause and resume in manual mode`() {
        val s = started(autoPause = false)
        s.feed(fix(0, 0.0, 18.0))
        val p = s.pause(sec(1))
        assertEquals(listOf<RecordingEvent>(RecordingEvent.Paused(PauseKind.MANUAL)), p.events)
        assertEquals(RecordingState.Paused(PauseKind.MANUAL), p.state)
        assertEquals(emptyList(), s.pause(sec(2)).events) // 이미 일시정지면 무시
        val r = s.resume(sec(3))
        assertEquals(listOf<RecordingEvent>(RecordingEvent.Resumed), r.events)
        assertEquals(RecordingState.Recording, r.state)
    }

    @Test
    fun `manual pause is not auto resumed`() {
        val s = started(autoPause = true)
        s.feed(fix(0, 0.0, 18.0))
        s.pause(sec(1))
        val updates = (1..10).map { s.feed(fix(sec(it), 5.0 * it, 18.0)) }
        assertEquals(RecordingState.Paused(PauseKind.MANUAL), s.state)
        assertTrue(updates.all { it.events.isEmpty() })
    }

    @Test
    fun `turning auto pause off while auto paused resumes`() {
        val (s, _) = autoPausedAt100m()
        assertEquals(RecordingState.Paused(PauseKind.AUTO), s.state)
        val u = s.setAutoPause(false, sec(41))
        assertEquals(listOf<RecordingEvent>(RecordingEvent.Resumed), u.events)
        assertEquals(RecordingState.Recording, u.state)
        assertFalse(s.autoPause)
    }

    @Test
    fun `manual resume does not pause at once on a slow fix`() {
        val s = started(autoPause = true)
        val slow = 2.0 / 3.6
        (0..4).forEach { s.feed(fix(sec(it), slow * it, 2.0)) }      // 느림 4초: 아직 일시정지 아님
        assertEquals(RecordingState.Recording, s.state)
        s.pause(sec(4))
        s.resume(sec(4))
        val updates = (5..8).map { s.feed(fix(sec(it), slow * it, 2.0)) }
        assertEquals(RecordingState.Recording, s.state)
        assertTrue(updates.all { it.events.isEmpty() })
    }

    @Test
    fun `reanchored fix adds no distance`() {
        val s = started(autoPause = false)
        assertNotNull(s.feed(fix(0, 0.0, 18.0)).point)
        val jumps = (1..5).map { s.feed(fix(sec(it), 500.0 + 5 * it, 18.0)) }
        assertEquals(listOf(null, null, null, null), jumps.take(4).map { it.point })
        assertNotNull(jumps[4].point)                                // 5번째: 재기준으로 채택
        (6..25).forEach { s.feed(fix(sec(it), 500.0 + 5 * it, 18.0)) } // 530..625m
        assertEquals(100.0, s.stats.distanceM, 0.5)
    }

    @Test
    fun `paused distance and time are not recorded`() {
        val (s, updates) = autoPausedAt100m()
        val pausedAt = updates.indexOfFirst { RecordingEvent.Paused(PauseKind.AUTO) in it.events }
        assertEquals(26, pausedAt) // 속도 0 시작(t=21)부터 5초째

        val r105 = s.feed(fix(sec(41), 105.0, 18.0))
        val r110 = s.feed(fix(sec(42), 110.0, 18.0))
        val r115 = s.feed(fix(sec(43), 115.0, 18.0))
        assertTrue(r105.events.isEmpty() && r110.events.isEmpty())
        assertNull(r110.point)
        assertEquals(listOf<RecordingEvent>(RecordingEvent.Resumed), r115.events)
        assertEquals(sec(43), r115.point?.timeMillis)
        assertEquals(north(115.0), r115.point?.point)

        (44..63).forEach { s.feed(fix(sec(it), 115.0 + 5 * (it - 43), 18.0)) } // 215m까지
        assertEquals(200.0, s.stats.distanceM, 1.0)
        assertTrue(s.stats.movingTimeMs < s.stats.elapsedMs)
        assertEquals(sec(63), s.stats.elapsedMs)
    }

    @Test
    fun `auto pause resumes 15 m from the stop point`() {
        val (s, _) = autoPausedAt100m()
        // 5km/h 미만이라 속도 조건으로는 재개되지 않는다. 정지 지점(100m)에서 14.9m → 계속 일시정지, 15.1m → 재개.
        assertTrue(s.feed(fix(sec(41), 114.9, 4.0)).events.isEmpty())
        assertEquals(listOf<RecordingEvent>(RecordingEvent.Resumed), s.feed(fix(sec(42), 115.1, 4.0)).events)
        assertEquals(RecordingState.Recording, s.state)
    }

    @Test
    fun `elapsed includes pauses`() {
        val s = started(autoPause = false)
        s.pause(sec(10))
        assertEquals(90_000L, s.onTick(90_000).stats.elapsedMs)
        assertEquals(90_000L, s.onTick(80_000).stats.elapsedMs) // 가장 큰 now 기준
    }

    @Test
    fun `speed is 0 while paused`() {
        val s = started(autoPause = false)
        (0..5).forEach { s.feed(fix(sec(it), 5.0 * it, 18.0)) }
        assertEquals(5.0, s.stats.currentSpeedMps, 0.01)
        assertEquals(0.0, s.pause(sec(5)).stats.currentSpeedMps)
        assertEquals(0.0, s.feed(fix(sec(6), 35.0, 18.0)).stats.currentSpeedMps)
        assertEquals(0.0, s.onTick(sec(7)).stats.currentSpeedMps)
    }

    @Test
    fun `track points only while recording`() {
        val (s, updates) = autoPausedAt100m()
        assertTrue(updates.take(27).all { it.point != null }) // t=26 일시정지시킨 위치까지
        assertTrue(updates.drop(27).all { it.point == null })
        assertNull(s.feed(fix(sec(41), 105.0, 18.0)).point)
        s.feed(fix(sec(42), 110.0, 18.0))
        val resumed = s.feed(fix(sec(43), 115.0, 18.0))
        assertEquals(RecordingEvent.Resumed, resumed.events.single())
        assertEquals(sec(43), resumed.point?.timeMillis)
    }

    @Test
    fun `km continues the day's distance`() {
        val s = started(autoPause = false, initialDay = DayTotals(2_500.0, 500_000))
        val events = (0..120).flatMap { s.feed(fix(sec(it), 5.0 * it, 18.0)).events } // 600m
        val km = events.filterIsInstance<RecordingEvent.KmReached>().single()
        assertEquals(3, km.km)
        assertTrue(km.dayDistanceM in 3_000.0..3_005.0, "${km.dayDistanceM}")
        assertTrue(km.dayMovingTimeMs in 600_000L..601_000L, "${km.dayMovingTimeMs}")
        assertEquals(3_100.0, s.day.distanceM, 0.5)
        assertEquals(620_000L, s.day.movingTimeMs)
    }

    @Test
    fun `km restarts at 1 the next day`() {
        val start = ZonedDateTime.of(2026, 10, 12, 23, 58, 0, 0, seoul).toInstant().toEpochMilli()
        val t2359 = start + 60_000
        val s = started(autoPause = false, startMillis = start, initialDay = DayTotals(2_900.0, 0))
        // 23:59:00부터 18km/h. 40초 뒤 200m(3,100m) → 3km, 60초 뒤(0시) 300m, 0시 이후 1,050m(i=270)까지.
        val events = (0..270).flatMap { i -> s.onFix(fix(t2359 + sec(i), 5.0 * i, 18.0), t2359 + sec(i)).events }
        val kms = events.filterIsInstance<RecordingEvent.KmReached>()
        assertEquals(listOf(3, 1), kms.map { it.km })
        assertTrue(kms[1].dayDistanceM in 1_000.0..1_010.0, "${kms[1].dayDistanceM}")
        assertEquals(1_055.0, s.day.distanceM, 0.5) // 23:59:59→0:00:00 구간은 0시 날짜에 들어간다
    }

    @Test
    fun `gps weak after 10 s on tick time`() {
        val s = started(autoPause = false)
        s.feed(fix(0, 0.0, 18.0))
        val t0 = 1_000_000L // tick 시계는 fix 시각과 무관하다
        assertFalse(s.onTick(t0).gpsWeak)
        assertFalse(s.onTick(t0 + 10_000 - 1).gpsWeak)
        assertTrue(s.onTick(t0 + 10_001).gpsWeak)
        assertTrue(s.onTick(t0 + 20_000).gpsWeak)
        assertFalse(s.onFix(fix(sec(1), 5.0, 18.0), t0 + 20_500).gpsWeak)
        assertFalse(s.onTick(t0 + 21_000).gpsWeak)
    }

    @Test
    fun `only accepted fixes keep the gps watchdog quiet`() {
        // Fixes keep coming but every one is too inaccurate (> 30 m): the rider has no usable GPS.
        val s = started(autoPause = false)
        s.feed(fix(0, 0.0, 18.0))
        val t0 = 1_000_000L
        assertFalse(s.onTick(t0).gpsWeak)
        for (i in 1..10) {
            s.onFix(fix(sec(i), 5.0 * i, 18.0, accuracyM = 50f), t0 + i * 1_000L - 500)
            s.onTick(t0 + i * 1_000L)
        }
        assertTrue(s.onTick(t0 + 10_001).gpsWeak, "rejected fixes are not a signal")
        assertTrue(s.onFix(fix(sec(11), 55.0, 18.0, accuracyM = 50f), t0 + 10_500).gpsWeak, "still weak")
        assertFalse(s.onFix(fix(sec(12), 60.0, 18.0), t0 + 11_000).gpsWeak, "an accepted fix clears it")
    }

    @Test
    fun `ascent only while recording`() {
        val s = started(autoPause = false)
        fun step(t: Int, altM: Double) {
            s.onPressure(pressureFor(altM), sec(t))
            s.feed(fix(sec(t), 5.0 * t, 18.0))
        }
        s.pause(0)
        for (t in 0..39) step(t, min(0.7 * t, 20.3))
        assertEquals(0.0, s.stats.ascentM)
        s.resume(sec(40))
        for (t in 40..68) step(t, 20.3 + 0.7 * (t - 39))            // → 40.6m
        for (t in 69..78) step(t, 40.6)                              // 10초 평지
        assertEquals(17.36, s.stats.ascentM, 0.01)
    }

    @Test
    fun `climb during a pause is not counted on resume`() {
        val s = started(autoPause = false)
        fun step(t: Int, altM: Double) {
            s.onPressure(pressureFor(altM), sec(t))
            s.feed(fix(sec(t), 5.0 * t, 18.0))
        }
        for (t in 0..9) step(t, 0.0)                                 // 기록 중 기준점 0m
        s.pause(sec(9))
        for (t in 10..39) step(t, 30.0)                              // 일시정지 중 30m로 올라감
        s.resume(sec(40))
        for (t in 40..49) step(t, 30.0)
        assertEquals(0.0, s.stats.ascentM)
    }

    @Test
    fun `stop freezes`() {
        val s = started(autoPause = true)
        (0..10).forEach { s.feed(fix(sec(it), 5.0 * it, 18.0)) }
        val stopped = s.stop(sec(11))
        assertEquals(RecordingState.Stopped, stopped.state)
        assertEquals(RecordingState.Stopped, s.state)
        val after = s.feed(fix(sec(12), 60.0, 18.0))
        assertEquals(emptyList(), after.events)
        assertNull(after.point)
        assertEquals(stopped.stats, after.stats)
        assertEquals(stopped.stats, s.onTick(sec(30)).stats)
        assertEquals(emptyList(), s.resume(sec(31)).events)
        assertEquals(emptyList(), s.stop(sec(32)).events)
        assertEquals(RecordingState.Stopped, s.state)
    }

    @Test
    fun `day average speed`() {
        assertEquals(5.0, DayTotals(1_000.0, 200_000).avgSpeedMps)
        assertEquals(0.0, DayTotals(1_000.0, 0).avgSpeedMps)
    }
}
