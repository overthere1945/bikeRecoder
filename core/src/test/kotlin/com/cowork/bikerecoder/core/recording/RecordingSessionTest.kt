package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.activity.ActivityProfile
import com.cowork.bikerecoder.core.activity.ActivityType
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
        assertTrue(km.distanceM in 3_000.0..3_005.0, "${km.distanceM}")
        assertTrue(km.movingTimeMs in 600_000L..601_000L, "${km.movingTimeMs}")
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
        assertTrue(kms[1].distanceM in 1_000.0..1_010.0, "${kms[1].distanceM}")
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

    private fun profiled(type: ActivityType, autoPause: Boolean = false, initialDay: DayTotals = DayTotals.ZERO, startMillis: Long = 0L) =
        RecordingSession(startMillis, autoPause, initialDay, profile = ActivityProfile.of(type)).also { it.start() }

    @Test
    fun `segment basis counts from segment start`() {
        val s = profiled(ActivityType.RUN, initialDay = DayTotals(5_000.0, 1_800_000))
        val events = (0..220).flatMap { s.feed(fix(sec(it), 5.0 * it, 18.0)).events } // 1,100 m
        val km = events.filterIsInstance<RecordingEvent.KmReached>().single()
        assertEquals(1, km.km)
        assertTrue(km.distanceM in 1_000.0..1_100.0, "${km.distanceM}")
        assertTrue(km.movingTimeMs in 200_000L..201_000L, "${km.movingTimeMs}")
    }

    @Test
    fun `segment basis does not restart at midnight`() {
        val start = ZonedDateTime.of(2026, 10, 12, 23, 59, 0, 0, seoul).toInstant().toEpochMilli()
        val s = profiled(ActivityType.WALK, startMillis = start)
        // 5 m/s: 23:59:00부터 600초 = 3,000 m. 0시는 60초 뒤(300 m).
        val kms = (0..600).flatMap { s.onFix(fix(start + sec(it), 5.0 * it, 18.0), start + sec(it)).events }
            .filterIsInstance<RecordingEvent.KmReached>()
        assertEquals(listOf(1, 2, 3), kms.map { it.km })
    }

    @Test
    fun `hike has no km events`() {
        val s = profiled(ActivityType.HIKE, autoPause = true)
        val events = (0..1_300).flatMap { s.feed(fix(sec(it), 3.0 * it, 10.8)).events } // 3.9 km
        assertTrue(s.stats.distanceM > 3_000.0)
        assertTrue(events.none { it is RecordingEvent.KmReached })
        assertNull(s.stats.lastLapMovingTimeMs)
    }

    @Test
    fun `lap time is since last km`() {
        val s = profiled(ActivityType.BIKE)
        // 첫 1 km는 5 m/s(200초), 다음 1 km는 2.5 m/s(400초)
        val events = ((0..200).map { fix(sec(it), 5.0 * it, 18.0) } +
            (1..400).map { fix(sec(200 + it), 1_000.0 + 2.5 * it, 9.0) })
            .flatMap { s.feed(it).events }
        val kms = events.filterIsInstance<RecordingEvent.KmReached>()
        assertEquals(listOf(1, 2), kms.map { it.km })
        assertEquals(kms[0].movingTimeMs, kms[0].lapMovingTimeMs)
        assertEquals(kms[1].movingTimeMs - kms[0].movingTimeMs, kms[1].lapMovingTimeMs)
        assertEquals(400_000L, kms[1].lapMovingTimeMs, "두 번째 1 km는 400초")
        assertEquals(400_000L, s.stats.lastLapMovingTimeMs)
    }

    @Test
    fun `recent speed needs two points`() {
        val s = profiled(ActivityType.BIKE, autoPause = false)
        assertNull(s.stats.recentSpeedMps)
        assertNull(s.feed(fix(sec(0), 0.0, 18.0)).stats.recentSpeedMps)
        assertEquals(5.0, assertNotNull(s.feed(fix(sec(1), 5.0, 18.0)).stats.recentSpeedMps), 1e-6)

        // 30초 창: 처음 10초는 5 m/s, 다음 40초는 2 m/s → 마지막 30초 창은 2 m/s
        var m = 5.0
        for (t in 2..50) {
            m += if (t <= 10) 5.0 else 2.0
            s.feed(fix(sec(t), m, 7.0))
        }
        assertEquals(2.0, assertNotNull(s.stats.recentSpeedMps), 1e-6)

        // 일시정지 뒤에는 다시 null
        s.pause(sec(51))
        assertNull(s.stats.recentSpeedMps)
        s.resume(sec(52))
        assertNull(s.stats.recentSpeedMps)
        assertNull(s.feed(fix(sec(53), m + 5.0, 18.0)).stats.recentSpeedMps)
        assertNotNull(s.feed(fix(sec(54), m + 10.0, 18.0)).stats.recentSpeedMps)
    }

    // ---- V1.8.0 엔진 고정값(골든): 자전거 기록은 종류 기능이 들어와도 결과가 같아야 한다 ----

    /** 정지 2번, 튐 1번, GPS 끊김 1번, 고도·기압이 있는 5.2 km 자전거 경로를 만들어 [s]에 넣고 (이벤트 설명, 점) 목록을 돌려준다. */
    private fun driveGoldenBikeTrack(s: RecordingSession): Pair<List<String>, List<TrackPoint>> {
        val lines = ArrayList<String>()
        val points = ArrayList<TrackPoint>()
        var m = 0.0
        for (t in 0..1_100) {
            val moving = when (t) {
                in 300..330 -> false // 첫 번째 정지
                in 640..690 -> false // 두 번째 정지(속도 값 없음: 구간 속도로 판정)
                else -> true
            }
            if (moving) m += 5.0
            if (t in 500..514) continue // 15초 GPS 끊김(평균 5 m/s라 이동으로 센다)
            val kmh = if (moving) 17.0 + 2.0 * Math.sin(t / 7.0) else 0.0
            val pos = if (t == 400) m + 500.0 else m // 튐(1,800 km/h)은 필터가 버린다
            val alt = 50.0 + 10.0 * Math.sin(t / 60.0)
            val f = fix(
                sec(t), pos, if (t in 640..690) null else kmh,
                accuracyM = if (t % 97 == 0) 40f else 5f, // 정확도 불량은 버린다
                altitudeM = alt, verticalAccuracyM = 3f,
            )
            if (t % 5 == 0) s.onPressure(1013.25 * (1 - alt / 44330).pow(5.255), sec(t))
            val u = s.feed(f)
            u.events.forEach { lines += "$t ${describe(it)}" }
            u.point?.let(points::add)
            if (t % 5 == 0) s.onTick(sec(t))
        }
        return lines to points
    }

    /** 이벤트 한 건을 비교용 문자열로. */
    private fun describe(e: RecordingEvent): String = when (e) {
        RecordingEvent.Started -> "Started"
        is RecordingEvent.Paused -> "Paused(${e.kind})"
        RecordingEvent.Resumed -> "Resumed"
        is RecordingEvent.KmReached -> "Km(${e.km}, ${e.distanceM}, ${e.movingTimeMs})"
    }

    private fun digest(points: List<TrackPoint>): String =
        points.joinToString("|") { "${it.timeMillis},${it.point.lat},${it.point.lon},${it.altitudeM},${it.speedMps}" }

    /**
     * 값은 변경 전(V1.8.0, 9b31f7b) 엔진으로 같은 경로를 돌려 얻은 것이다. 엔진이 바뀌어도 자전거 결과는 그대로여야 한다.
     * 기본 생성과 `profile = of(BIKE)` 생성 둘 다 같은 값을 내야 한다.
     */
    @Test
    fun `bike profile matches V180 engine`() {
        val expectedEvents = listOf(
            "201 Km(1, 1000.000000000064, 200000)",
            "305 Paused(AUTO)",
            "333 Resumed",
            "435 Km(2, 2000.000000000121, 406000)",
            "635 Km(3, 3000.0000000002124, 606000)",
            "645 Paused(AUTO)",
            "693 Resumed",
            "889 Km(4, 4000.000000001098, 812000)",
            "1089 Km(5, 5000.000000001134, 1012000)",
        )
        val sessions = listOf(
            started(autoPause = true),
            RecordingSession(0L, autoPause = true, profile = ActivityProfile.of(ActivityType.BIKE)).also { it.start() },
        )
        for (s in sessions) {
            val (lines, points) = driveGoldenBikeTrack(s)
            val stats = s.stop(sec(1_101)).stats
            assertEquals(expectedEvents, lines)
            assertEquals(1_000, points.size)
            assertEquals(-368011408, digest(points).hashCode())
            assertEquals(5055.000000000686, stats.distanceM)
            assertEquals(1_023_000L, stats.movingTimeMs)
            assertEquals(1_101_000L, stats.elapsedMs)
            assertEquals(0.0, stats.currentSpeedMps)
            assertEquals(4.941348973607709, stats.avgSpeedMps)
            assertEquals(5.275816440582275, stats.maxSpeedMps)
            assertEquals(45.277081505657776, stats.altitudeM)
            assertEquals(46.09060316267851, stats.ascentM)
            assertEquals(49.56133686629977, stats.descentM)
            assertEquals(40.21720169493877, stats.minAltitudeM)
            assertEquals(60.29121418483479, stats.maxAltitudeM)
            assertEquals(DayTotals(5055.000000000686, 1_023_000L), s.day)
        }
    }
}
