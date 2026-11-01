package com.cowork.bikerecoder.share.cards

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.RidePoint
import com.cowork.bikerecoder.core.video.RideTimeline
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.video.TripTotals
import com.cowork.bikerecoder.video.VideoInputs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/** 카드에 쓰는 글(스펙 §2.1): 날짜 줄, 숫자 세 칸, 작은 줄. */
class CardFactsTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    /** 2026-11-01 09:00 Seoul plus [day] days. */
    private fun time(day: Int) = LocalDate.of(2026, 11, 1).plusDays(day.toLong()).atTime(9, 0).atZone(seoul).toInstant().toEpochMilli()

    /** Two points on each of [days] days, one segment a day. */
    private fun inputs(
        activity: ActivityType = ActivityType.BIKE,
        ascentM: Double = 410.2,
        avgBpm: Int? = 128,
        days: Int = 1,
    ) = VideoInputs(
        tripId = 1,
        timeline = RideTimeline(
            (0 until days).flatMap { day ->
                listOf(0, 1).map { k ->
                    RidePoint((2 * day + k) * 1_000L, GeoPoint(36.4 + k * 1e-3, 128.2), null, time(day) + k * 1_000L, day, day)
                }
            },
        ),
        photos = emptyList(),
        heartRate = emptyList(),
        dayStarts = emptyList(),
        title = "11월 1일 (일)",
        rideTitle = "아침 라이딩",
        totals = TripTotals(
            distanceM = 32_140.0, movingTimeMs = 6_300_000L, avgSpeedMps = 5.1, maxSpeedMps = 9.0, ascentM = ascentM,
            avgBpm = avgBpm, calories = null, hikeElapsedMs = 7_200_000L, maxAltitudeM = 835.6,
        ),
        canColorByHeart = false,
        firstStartedAt = time(0),
        activity = activity,
    )

    @Test
    fun oneDayDateLine() {
        assertEquals("2026년 11월 3일 (화)", cardDateLine(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3), 1))
    }

    @Test
    fun multiDaySameMonth() {
        assertEquals("3일 · 11월 1일–3일", cardDateLine(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), 3))
    }

    @Test
    fun multiDayAcrossMonths() {
        assertEquals("3일 · 10월 31일–11월 2일", cardDateLine(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 2), 3))
    }

    @Test
    fun bikeCellsAndSmallLine() {
        val facts = cardFacts(inputs(), "경북 상주시")
        assertEquals(ActivityType.BIKE, facts.activity)
        assertEquals("아침 라이딩", facts.title)
        assertEquals("경북 상주시", facts.region)
        assertEquals("2026년 11월 1일 (일)", facts.dateLine)
        assertEquals(ActivityText.cardCells(ActivityType.BIKE, 32_140.0, 6_300_000L, 7_200_000L, 410.2, 835.6), facts.cells)
        assertEquals("410 m", facts.ascent)
        assertEquals("♥ 128", facts.heart)
        assertEquals("상승 410 m · ♥ 128", facts.smallLine)
    }

    @Test
    fun smallLineWithOnePart() {
        assertEquals("상승 410 m", cardFacts(inputs(avgBpm = null), null).smallLine)
        assertEquals("♥ 128", cardFacts(inputs(ascentM = 0.0), null).smallLine)
    }

    /** The date line counts the timeline's days and spans its first and last point (Seoul dates). */
    @Test
    fun multiDayTripDateLineFromTheTimeline() {
        assertEquals("3일 · 11월 1일–3일", cardFacts(inputs(days = 3), null).dateLine)
    }

    @Test
    fun hikeSmallLineDropsAscent() {
        val facts = cardFacts(inputs(activity = ActivityType.HIKE), null)
        assertEquals(ActivityText.cardCells(ActivityType.HIKE, 32_140.0, 6_300_000L, 7_200_000L, 410.2, 835.6), facts.cells)
        assertNull(facts.ascent)
        assertEquals("♥ 128", facts.smallLine)
    }

    /** An ascent under 1 m counts as none, as in the auto text. */
    @Test
    fun noAscentNoHeartNoSmallLine() {
        val facts = cardFacts(inputs(ascentM = 0.6, avgBpm = null), null)
        assertNull(facts.ascent)
        assertNull(facts.heart)
        assertNull(facts.smallLine)
    }
}
