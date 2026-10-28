package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.RidePoint
import com.cowork.bikerecoder.core.video.RideTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The six numbers of the summary image (spec §4 ③): the last is the average heart rate, or "주행 N회" without one. */
class SummaryImageStatsTest {

    /** Three segments (two on day 1, one on day 2), but only two colours listed: N counts segments, not colours. */
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
        segmentColors = listOf("#E53935", "#1E88E5"),
        title = "10월 17일 ~ 18일",
        badge = "여러 날 · 2일",
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
}
