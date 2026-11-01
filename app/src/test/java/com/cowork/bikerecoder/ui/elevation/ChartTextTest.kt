package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.series.MetricKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The speed and heart-rate chart copy of V1.8.0 spec §3. */
class ChartTextTest {

    @Test
    fun scrubLines() {
        assertEquals("1.1km · 18km/h", ChartText.scrubLine(MetricKind.SPEED, 1_100.0, 18.4))
        assertEquals("1.1km · 145bpm", ChartText.scrubLine(MetricKind.HEART_RATE, 1_100.0, 145.4))
        assertEquals("1.1km · --bpm", ChartText.scrubLine(MetricKind.HEART_RATE, 1_100.0, null))
        assertEquals("850m · --km/h", ChartText.scrubLine(MetricKind.SPEED, 850.0, null))
        // A smoothed stop can come out a hair under zero: never "-0".
        assertEquals("850m · 0km/h", ChartText.scrubLine(MetricKind.SPEED, 850.0, -1e-14))
    }

    @Test
    fun descriptions() {
        assertEquals("속도 그래프, 평균 18km/h, 최고 42km/h", ChartText.speedDescription(18.4, 41.6))
        assertEquals("속도 그래프, 평균 0km/h, 최고 0km/h", ChartText.speedDescription(-1e-14, 0.0))
        assertEquals("심박 그래프, 평균 132bpm, 최고 171bpm", ChartText.heartDescription(132, 171))
    }

    @Test
    fun valueLabels() {
        assertEquals("34km/h", ChartText.valueLabel(MetricKind.SPEED, 34.2))
        assertEquals("0km/h", ChartText.valueLabel(MetricKind.SPEED, -1e-14))
        assertEquals("162bpm", ChartText.valueLabel(MetricKind.HEART_RATE, 161.5))
    }

    @Test
    fun legends() {
        assertEquals(listOf("10 미만", "10~15", "15~20", "20~25", "25 이상"), ChartText.SPEED_LEGEND)
        assertEquals(listOf("100 미만", "100~120", "120~140", "140~160", "160 이상"), ChartText.HEART_LEGEND)
    }

    @Test
    fun titles() {
        assertEquals("고도", ChartText.TITLE_ELEVATION)
        assertEquals("속도", ChartText.TITLE_SPEED)
        assertEquals("심박", ChartText.TITLE_HEART)
        assertEquals("속도 정보가 없습니다", ChartText.NO_SPEED)
    }

    /** V1.9.0 §3.5: 훑는 줄은 페이스로 보인다. 값은 속도(km/h)다. */
    @Test
    fun paceScrubLine() {
        val kmh = 3.6 * 1000 / 332 // 5'32" per km
        assertEquals("1.1km · 5'32\"/km", ChartText.scrubLine(MetricKind.SPEED, 1_100.0, kmh, pace = true))
        assertEquals("850m · --'--\"/km", ChartText.scrubLine(MetricKind.SPEED, 850.0, null, pace = true))
        assertEquals("850m · --'--\"/km", ChartText.scrubLine(MetricKind.SPEED, 850.0, 0.4, pace = true), "under 1 km/h is a stop")
        // Not asked for a pace: as before.
        assertEquals("1.1km · 11km/h", ChartText.scrubLine(MetricKind.SPEED, 1_100.0, kmh))
    }

    @Test
    fun paceAnnouncement() {
        // 5 km in 27 min 40 s = 5'32"/km on average; the fastest moment 4'48"/km.
        val totals = SegmentTotals.ZERO.copy(distanceM = 5_000.0, movingTimeMs = 1_660_000L, maxSpeedMps = 1000.0 / 288)
        val run = SpeedChartStyle(ActivityType.RUN)
        assertEquals("페이스 그래프, 평균 5'32\", 최고 4'48\"", ChartText.speedDescription(totals, run))
        assertEquals("페이스 그래프, 평균 5'32\", 최고 4'48\"", ChartText.speedDescription(totals, SpeedChartStyle(ActivityType.WALK)))
        // A bike, a hike and a mixed trip keep the speed read-out.
        assertEquals("속도 그래프, 평균 11km/h, 최고 13km/h", ChartText.speedDescription(totals, SpeedChartStyle.BIKE))
        assertEquals(ChartText.speedDescription(totals), ChartText.speedDescription(totals, SpeedChartStyle(ActivityType.HIKE)))
        assertEquals(ChartText.speedDescription(totals), ChartText.speedDescription(totals, SpeedChartStyle(null)))
    }

    /** 위·아래 끝 글은 빠른 쪽이 위다: 값이 속도라 가장 빠른 값이 위에 오고, 페이스로 읽힌다. */
    @Test
    fun paceEndLabelsKeepTheFasterOnTop() {
        assertEquals("4'48\"/km", ChartText.valueLabel(MetricKind.SPEED, 3.6 * 1000 / 288, pace = true))
        assertEquals("6'10\"/km", ChartText.valueLabel(MetricKind.SPEED, 3.6 * 1000 / 370, pace = true))
        assertEquals("--'--\"/km", ChartText.valueLabel(MetricKind.SPEED, 0.0, pace = true))
        assertEquals("12km/h", ChartText.valueLabel(MetricKind.SPEED, 12.4))
    }

    @Test
    fun titlesAndLegendsFollowTheStyle() {
        assertEquals("페이스", ChartText.TITLE_PACE)
        assertEquals("페이스", ChartText.title(SpeedChartStyle(ActivityType.RUN)))
        assertEquals("페이스", ChartText.title(SpeedChartStyle(ActivityType.WALK)))
        assertEquals("속도", ChartText.title(SpeedChartStyle(ActivityType.BIKE)))
        assertEquals("속도", ChartText.title(SpeedChartStyle(ActivityType.HIKE)))
        assertEquals("속도", ChartText.title(SpeedChartStyle(null)))
        assertEquals(listOf("10 미만", "10~15", "15~20", "20~25", "25 이상"), ChartText.speedLegend(SpeedChartStyle.BIKE))
        assertEquals(listOf("1 미만", "1~2", "2~3", "3~4", "4 이상"), ChartText.speedLegend(SpeedChartStyle(ActivityType.HIKE)))
        assertEquals(emptyList<String>(), ChartText.speedLegend(SpeedChartStyle(null)))
        assertEquals(null, ChartText.legendNote(SpeedChartStyle.BIKE))
        assertEquals("종류마다 색 기준이 다릅니다", ChartText.legendNote(SpeedChartStyle(null)))
    }

    @Test
    fun styleOfSegmentActivities() {
        val run = ActivityType.RUN
        val walk = ActivityType.WALK
        assertEquals(SpeedChartStyle(run), SpeedChartStyle.of(listOf(run, run)))
        assertEquals(SpeedChartStyle(walk), SpeedChartStyle.of(listOf(walk)))
        assertEquals(SpeedChartStyle(null), SpeedChartStyle.of(listOf(run, walk)), "a run and a walk are not one activity")
        assertEquals(SpeedChartStyle(null), SpeedChartStyle.of(listOf(ActivityType.BIKE, run, run)))
        assertEquals(SpeedChartStyle.BIKE, SpeedChartStyle.of(emptyList()))
    }
}
