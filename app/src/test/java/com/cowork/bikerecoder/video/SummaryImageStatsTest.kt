package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.RidePoint
import com.cowork.bikerecoder.core.video.RideTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The six numbers of the summary image (spec §4 ③): the last is the average heart rate, or "주행 N회" without one. */
class SummaryImageStatsTest {

    /** Three segments (two on day 1, one on day 2): N counts segments, not days. */
    private fun inputs(avgBpm: Int?) = VideoInputs(
        tripId = 1,
        timeline = RideTimeline(
            listOf(0 to 0, 0 to 0, 1 to 0, 1 to 0, 2 to 1, 2 to 1).mapIndexed { i, (segment, day) ->
                RidePoint(i * 1_000L, GeoPoint(37.5 + i * 1e-4, 127.0), null, i * 1_000L, segment, day)
            },
        ),
        photos = emptyList(),
        heartRate = emptyList(),
        dayStarts = emptyList(),
        title = "10월 17일 ~ 18일",
        rideTitle = "2일 여행",
        totals = TripTotals(12_345.0, 3_600_000L, 3.43, 9.0, 120.4, avgBpm, null),
        canColorByHeart = false,
        firstStartedAt = 0L,
    )

    @Test
    fun withoutHeartRateTheLastCellCountsRides() {
        assertEquals(
            listOf(
                "거리" to "12.34km", "이동 시간" to "1시간 0분", "평균 속도" to "12.3km/h",
                "최고 속도" to "32.4km/h", "상승 고도" to "120m", "주행" to "3회",
            ),
            SummaryImageRenderer.stats(inputs(avgBpm = null)),
        )
    }

    @Test
    fun withHeartRateTheLastCellIsTheAverage() {
        assertEquals("평균 심박" to "141bpm", SummaryImageRenderer.stats(inputs(avgBpm = 141)).last())
    }

    /** V1.9.0 spec §5.1: the first cells follow the trip's dominant activity; the rest is as it was. */
    @Test
    fun runImageHasPaceAndFastestKm() {
        val run = inputs(avgBpm = null).let {
            it.copy(activity = ActivityType.RUN, totals = it.totals.copy(distanceM = 5_000.0, movingTimeMs = 1_660_000L, fastestKmMs = 288_000L))
        }
        assertEquals(
            listOf(
                "거리" to "5.00km", "이동 시간" to "27분", "평균 페이스" to "5'32\"/km", "가장 빠른 1 km" to "4'48\"/km",
                "상승 고도" to "120m", "활동" to "3회",
            ),
            SummaryImageRenderer.stats(run),
        )
    }

    @Test
    fun hikeImageHasFiveCellsAndNoSecondAscent() {
        val hike = inputs(avgBpm = 120).let {
            it.copy(activity = ActivityType.HIKE, totals = it.totals.copy(hikeElapsedMs = 12_000_000L, maxAltitudeM = 835.6))
        }
        assertEquals(
            listOf("산행 시간" to "3시간 20분", "누적 상승" to "120m", "최고 고도" to "836m", "거리" to "12.34km", "평균 심박" to "120bpm"),
            SummaryImageRenderer.stats(hike),
        )
    }
}
