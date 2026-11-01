package com.cowork.bikerecoder.ui.activity

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.stats.RideRecordKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The activity copy of the screens and the notification (V1.9.0 spec §2.4, §3.2). */
class ActivityTextTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val sunset = ZonedDateTime.of(2026, 11, 1, 17, 32, 0, 0, seoul).toInstant().toEpochMilli()
    private val minute = 60_000L

    @Test
    fun names() {
        assertEquals(listOf("자전거", "걷기", "달리기", "등산"), ActivityType.entries.map(ActivityText::name))
    }

    @Test
    fun recordingTitleKeepsTheBikeWording() {
        assertEquals("주행 기록 중", ActivityText.recordingTitle(ActivityType.BIKE))
        assertEquals("걷기 기록 중", ActivityText.recordingTitle(ActivityType.WALK))
        assertEquals("달리기 기록 중", ActivityText.recordingTitle(ActivityType.RUN))
        assertEquals("등산 기록 중", ActivityText.recordingTitle(ActivityType.HIKE))
    }

    @Test
    fun sunsetLineBeforeAfter() {
        assertEquals("일몰 17:32 · 1시간 40분 남음", ActivityText.sunsetLine(sunset, sunset - 100 * minute, seoul))
        assertEquals("일몰 17:32 · 1시간 40분 남음", ActivityText.sunsetLine(sunset, sunset - 100 * minute - 59_999, seoul))
        assertEquals("일몰 17:32 · 0분 남음", ActivityText.sunsetLine(sunset, sunset - 1, seoul))
        assertEquals("일몰 17:32 · 지남", ActivityText.sunsetLine(sunset, sunset, seoul))
        assertEquals("일몰 17:32 · 지남", ActivityText.sunsetLine(sunset, sunset + 30 * minute, seoul))
        val morning = ZonedDateTime.of(2026, 6, 21, 7, 5, 0, 0, seoul).toInstant().toEpochMilli()
        assertEquals("일몰 07:05 · 지남", ActivityText.sunsetLine(morning, morning + 1, seoul))
    }

    @Test
    fun warningFromOneHour() {
        assertFalse(ActivityText.sunsetWarning(sunset, sunset - 60 * minute - 1))
        assertTrue(ActivityText.sunsetWarning(sunset, sunset - 60 * minute))
        assertTrue(ActivityText.sunsetWarning(sunset, sunset - 1))
        assertTrue(ActivityText.sunsetWarning(sunset, sunset + 60 * minute), "after the sunset too")
    }

    @Test
    fun notificationSunsetPartFromOneHour() {
        assertNull(ActivityText.sunsetNotice(sunset, sunset - 61 * minute))
        assertEquals("일몰까지 1시간 0분", ActivityText.sunsetNotice(sunset, sunset - 60 * minute))
        assertEquals("일몰까지 25분", ActivityText.sunsetNotice(sunset, sunset - 25 * minute - 30_000))
        assertEquals("일몰 지남", ActivityText.sunsetNotice(sunset, sunset))
    }

    private val totals = SegmentTotals(
        distanceM = 5_000.0, movingTimeMs = 1_660_000L, elapsedMs = 1_800_000L, maxSpeedMps = 1000.0 / 288,
        ascentM = 42.0, descentM = 40.0, minAltitudeM = 20.0, maxAltitudeM = 80.0,
    )

    private fun labels(rows: List<List<Pair<String, String>>>) = rows.map { row -> row.map { it.first } }

    @Test
    fun copy() {
        assertEquals("활동 종류 바꾸기", ActivityText.CHANGE_ACTIVITY)
        assertEquals("일시정지 중에 저장되지 않은 위치는 되살릴 수 없습니다.", ActivityText.CHANGE_NOTE)
        assertEquals("종류마다 색 기준이 다릅니다", ActivityText.MIXED_COLOR_NOTE)
    }

    @Test
    fun segmentTotalRows() {
        val bike = ActivityText.segmentTotalRows(ActivityType.BIKE, totals)
        assertEquals(
            listOf(
                listOf("거리", "이동 시간"), listOf("경과 시간", "평균/최고"), listOf("누적 상승", "누적 하강"), listOf("최고 고도", "최저 고도"),
            ),
            labels(bike),
        )
        assertEquals("10.8 / 12.5km/h", bike[1][1].second)
        for (type in listOf(ActivityType.RUN, ActivityType.WALK)) {
            val rows = ActivityText.segmentTotalRows(type, totals)
            assertEquals(
                listOf(
                    listOf("거리", "이동 시간", "경과 시간"), listOf("평균 페이스", "평균 속도"),
                    listOf("누적 상승", "누적 하강"), listOf("최고 고도", "최저 고도"),
                ),
                labels(rows),
            )
            assertEquals(listOf("평균 페이스" to "5'32\"/km", "평균 속도" to "10.8km/h"), rows[1])
        }
        val hike = ActivityText.segmentTotalRows(ActivityType.HIKE, totals)
        assertEquals(listOf("산행 시간" to "0:30:00"), hike.first(), "the elapsed time, rests included")
        assertEquals(
            listOf(listOf("산행 시간"), listOf("거리", "이동 시간"), listOf("평균/최고"), listOf("누적 상승", "누적 하강"), listOf("최고 고도", "최저 고도")),
            labels(hike),
        )
    }

    @Test
    fun tripTotalRows() {
        val bike = ActivityText.tripTotalRows(ActivityType.BIKE, totals)
        assertEquals(
            listOf(listOf("거리", "이동 시간"), listOf("평균/최고"), listOf("누적 상승", "누적 하강", "최고 고도")),
            labels(bike),
        )
        assertEquals(
            listOf("거리" to "5.00km", "이동 시간" to "0:27:40"),
            bike[0],
        )
        val walk = ActivityText.tripTotalRows(ActivityType.WALK, totals)
        assertEquals(
            listOf(listOf("거리", "이동 시간"), listOf("평균 페이스", "평균 속도"), listOf("누적 상승", "누적 하강", "최고 고도")),
            labels(walk),
        )
        val hike = ActivityText.tripTotalRows(ActivityType.HIKE, totals)
        assertEquals(listOf("산행 시간" to "30분"), hike.first())
        assertEquals(
            listOf(listOf("산행 시간"), listOf("거리", "이동 시간"), listOf("평균/최고"), listOf("누적 상승", "누적 하강", "최고 고도")),
            labels(hike),
        )
    }

    @Test
    fun effortTimeFormats() {
        assertEquals("24'51\"", ActivityText.effortTime(1_491_000))
        assertEquals("24'51\"", ActivityText.effortTime(1_490_600)) // rounds to the second
        assertEquals("4'05\"", ActivityText.effortTime(245_000))
        assertEquals("59'59\"", ActivityText.effortTime(3_599_000))
        assertEquals("1:00'00\"", ActivityText.effortTime(3_600_000))
        assertEquals("1:52'10\"", ActivityText.effortTime(6_730_000))
    }

    @Test
    fun recordLabelsAndLines() {
        assertEquals("가장 긴 주행", ActivityText.recordLabel(RideRecordKind.LONGEST_DISTANCE))
        assertEquals(
            listOf("1 km 최고 기록", "5 km 최고 기록", "10 km 최고 기록", "하프 최고 기록"),
            listOf(RideRecordKind.RUN_BEST_1K, RideRecordKind.RUN_BEST_5K, RideRecordKind.RUN_BEST_10K, RideRecordKind.RUN_BEST_HALF)
                .map(ActivityText::recordLabel),
        )
        assertEquals("🏆 가장 긴 달리기 신기록", ActivityText.newRecordLine(RideRecordKind.RUN_LONGEST_DISTANCE, null))
        assertEquals("🏆 하프 최고 기록 1:52'10\"", ActivityText.newRecordLine(RideRecordKind.RUN_BEST_HALF, 6_730_000))
        assertEquals("🏆 가장 긴 산행", ActivityText.newRecordLine(RideRecordKind.HIKE_LONGEST_ELAPSED, null))
        assertEquals("🏆 가장 오래 탄 주행 신기록", ActivityText.newRecordLine(RideRecordKind.LONGEST_TIME, null))
    }

    @Test
    fun statsRidesWords() {
        assertEquals(listOf("활동", "라이딩", "걷기", "달리기", "등산"), (listOf(null) + ActivityType.entries).map(ActivityText::statsRidesWord))
    }
}
