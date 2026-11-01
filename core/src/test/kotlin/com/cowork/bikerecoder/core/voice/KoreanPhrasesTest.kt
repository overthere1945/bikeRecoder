package com.cowork.bikerecoder.core.voice

import com.cowork.bikerecoder.core.model.TurnType.LEFT
import com.cowork.bikerecoder.core.model.TurnType.RIGHT
import com.cowork.bikerecoder.core.model.TurnType.ROUNDABOUT
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.SunsetStage
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class KoreanPhrasesTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val p = KoreanPhrases(zone)

    /** 2026-10-06 Asia/Seoul 기준, 시:분을 epochMillis로 변환한다. */
    private fun at(h: Int, m: Int): Long =
        ZonedDateTime.of(2026, 10, 6, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun climbPhrase() {
        assertEquals("300미터 앞 오르막, 800미터, 평균 6퍼센트입니다", p.climb(300.0, 800.0, 5.6))
        assertEquals("280미터 앞 오르막, 1.2킬로미터, 평균 4퍼센트입니다", p.climb(283.0, 1_210.0, 4.4))
    }

    @Test
    fun distances() {
        assertEquals("800미터", p.distance(796.0))
        assertEquals("200미터", p.distance(204.0))
        assertEquals("1.0킬로미터", p.distance(1_000.0))
        assertEquals("42.1킬로미터", p.distance(42_080.0))
        assertEquals("1.2킬로미터", p.distance(1_150.0))
    }

    @Test
    fun `clock noon and midnight`() {
        assertEquals("오후 12시 0분", p.clockTime(at(12, 0)))
        assertEquals("오전 12시 5분", p.clockTime(at(0, 5)))
        assertEquals("오후 3시 40분", p.clockTime(at(15, 40)))
        assertEquals("오전 9시 7분", p.clockTime(at(9, 7)))
    }

    @Test
    fun turns() {
        assertEquals("200미터 앞에서 좌회전입니다", p.turnAhead(200.0, LEFT, 0, null))
        assertEquals("좌회전 후 바로 우회전입니다", p.turnNow(LEFT, 0, RIGHT))
        assertEquals("300미터 앞에서 회전교차로에서 2번째 출구입니다", p.turnAhead(300.0, ROUNDABOUT, 2, null))
    }

    @Test
    fun `km report`() = assertEquals(
        "5킬로미터 이동. 남은 거리 42.1킬로미터, 도착 예정 오후 3시 40분입니다.",
        p.kmReport(5, 42_080.0, at(15, 40)),
    )

    @Test
    fun `fixed phrases`() {
        assertEquals("목적지에 도착했습니다", p.arrivedDestination)
        assertEquals("경유지에 도착했습니다", p.arrivedWaypoint)
        assertEquals("경로를 벗어났습니다. 다시 탐색합니다", p.offRoute)
        assertEquals("경로를 다시 찾지 못했습니다", p.rerouteFailed)
        assertEquals("GPS 신호가 약합니다", p.gpsWeak)
    }

    @Test
    fun `recording phrases`() {
        assertEquals("기록을 시작합니다", p.recordingStarted)
        assertEquals("일시정지되었습니다", p.recordingPaused)
        assertEquals("기록을 다시 시작합니다", p.recordingResumed)
    }

    @Test
    fun rideUxPhrases() {
        assertEquals("안내를 마칩니다. 기록은 계속됩니다", p.guidanceEndedRecordingGoesOn)
        assertEquals("새 구간으로 기록을 시작합니다", p.newSegmentStarted)
    }

    @Test
    fun `km report while recording only`() =
        assertEquals("3킬로미터 이동. 평균 시속 18.0킬로미터, 이동 시간 10분입니다.", p.rideKmReport(3, 5.0, 600_000))

    @Test
    fun `km report combined with guidance`() = assertEquals(
        "1킬로미터 이동. 평균 시속 18.0킬로미터, 이동 시간 3분. 남은 거리 1.2킬로미터, 도착 예정 오후 3시 5분입니다.",
        p.rideKmReportWithNav(1, 5.0, 200_000, 1_200.0, Instant.parse("2026-10-12T06:05:00Z").toEpochMilli()),
    )

    @Test
    fun `km report reads hours when moving time is an hour or more`() =
        assertEquals(
            "20킬로미터 이동. 평균 시속 18.0킬로미터, 이동 시간 1시간 6분입니다.",
            p.rideKmReport(20, 5.0, 4_000_000),
        )

    @Test
    fun bikeKmSaysSiSok() =
        assertEquals("5킬로미터 이동. 평균 시속 18.0킬로미터, 이동 시간 16분입니다.", p.rideKmReport(5, 5.0, 1_000_000))

    @Test
    fun runKmReport() =
        assertEquals(
            "3킬로미터. 이번 1킬로미터 5분 32초, 평균 페이스 5분 40초입니다.",
            p.paceKmReport(3, 332, 340),
        )

    @Test
    fun paceKmReportDropsMissingParts() {
        assertEquals("1킬로미터. 이번 1킬로미터 6분 5초입니다.", p.paceKmReport(1, 365, null))
        assertEquals("2킬로미터. 평균 페이스 6분입니다.", p.paceKmReport(2, null, 360))
        assertEquals("2킬로미터입니다.", p.paceKmReport(2, null, null))
    }

    @Test
    fun paceDropsZeroSeconds() {
        assertEquals("5분", p.pace(300))
        assertEquals("5분 1초", p.pace(301))
        assertEquals("45초", p.pace(45))
    }

    @Test
    fun spokenDurationFloorsAndDropsZeroMinutes() {
        assertEquals("2시간", p.spokenDuration(7_200_000))
        assertEquals("1시간 30분", p.spokenDuration(5_400_000))
        assertEquals("1시간 30분", p.spokenDuration(5_459_999))
        assertEquals("45분", p.spokenDuration(2_700_000))
        assertEquals("0분", p.spokenDuration(59_999))
    }

    @Test
    fun hikeReportWithSunset() = assertEquals(
        "산행 1시간 30분. 현재 고도 820미터, 누적 상승 412미터, 거리 4.3킬로미터입니다. 일몰까지 2시간 남았습니다.",
        p.hikeReport(5_400_000, 819.6, 412.4, 4_250.0, 7_200_000),
    )

    @Test
    fun hikeReportWithoutAltitude() = assertEquals(
        "산행 1시간. 누적 상승 412미터, 거리 4.3킬로미터입니다.",
        p.hikeReport(3_600_000, null, 412.4, 4_250.0, null),
    )

    @Test
    fun hikeReportSunsetTailOnlyUnderThreeHours() {
        val base = "산행 30분. 현재 고도 100미터, 누적 상승 50미터, 거리 1.0킬로미터입니다."
        assertEquals(base, p.hikeReport(1_800_000, 100.0, 50.0, 1_000.0, 10_800_000))
        assertEquals(base, p.hikeReport(1_800_000, 100.0, 50.0, 1_000.0, 0))
        assertEquals(base, p.hikeReport(1_800_000, 100.0, 50.0, 1_000.0, -5_000))
        assertEquals("$base 일몰까지 2시간 59분 남았습니다.", p.hikeReport(1_800_000, 100.0, 50.0, 1_000.0, 10_799_999))
    }

    @Test
    fun startPhrases() {
        assertEquals("기록을 시작합니다", p.recordingStarted(ActivityType.BIKE, null))
        assertEquals("기록을 시작합니다", p.recordingStarted)
        assertEquals("걷기 기록을 시작합니다", p.recordingStarted(ActivityType.WALK, null))
        assertEquals("달리기 기록을 시작합니다", p.recordingStarted(ActivityType.RUN, 3_600_000))
        assertEquals("등산 기록을 시작합니다", p.recordingStarted(ActivityType.HIKE, null))
        assertEquals("등산 기록을 시작합니다", p.recordingStarted(ActivityType.HIKE, 7_200_000))
        assertEquals(
            "등산 기록을 시작합니다 일몰까지 1시간 30분 남았습니다.",
            p.recordingStarted(ActivityType.HIKE, 5_400_000),
        )
    }

    @Test
    fun activityNames() {
        assertEquals(listOf("자전거", "걷기", "달리기", "등산"), ActivityType.entries.map { p.activityName(it) })
    }

    @Test
    fun sunsetCues() {
        assertEquals("일몰까지 2시간 남았습니다. 하산 시간을 확인하세요.", p.sunsetCue(SunsetStage.H2))
        assertEquals("일몰까지 1시간 남았습니다. 하산을 시작하세요.", p.sunsetCue(SunsetStage.H1))
        assertEquals("일몰까지 30분 남았습니다. 서둘러 하산하세요.", p.sunsetCue(SunsetStage.M30))
        assertEquals("해가 졌습니다. 곧 어두워지니 랜턴을 준비하세요.", p.sunsetCue(SunsetStage.NOW))
    }
}
