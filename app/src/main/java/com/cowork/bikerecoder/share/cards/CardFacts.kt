package com.cowork.bikerecoder.share.cards

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.ui.feed.meterText
import com.cowork.bikerecoder.ui.history.cardDateText
import com.cowork.bikerecoder.video.VideoInputs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The words drawn on a share card (spec §2.1): the dominant [activity] (the badge), the record's [title] and its [region]
 * (null: left out), the [dateLine] ([cardDateLine]), the three number [cells] (label, value) of
 * [ActivityText.cardCells] over the trip totals, the [ascent] value ("410 m"; null for a hike, whose cells have it, or
 * under 1 m) and the average [heart] rate ("♥ 128"; null: unknown) of the satellite card (spec §2.2).
 */
data class CardFacts(
    val activity: ActivityType,
    val title: String,
    val region: String?,
    val dateLine: String,
    val cells: List<Pair<String, String>>,
    val ascent: String?,
    val heart: String?,
) {
    /** The feed card's small line: "상승 410 m · ♥ 128", either part alone, or null without both. */
    val smallLine: String?
        get() = listOfNotNull(ascent?.let { "$LABEL_ASCENT $it" }, heart).joinToString(" · ").ifEmpty { null }

    companion object {
        /** The ascent's label: in the feed card's small line and as the story card's third small cell. */
        const val LABEL_ASCENT = "상승"
    }
}

/**
 * The [CardFacts] of a trip (spec §2.1). The dates are the Seoul dates of the timeline's first and last point, the days
 * its distinct riding days. The ascent is left out for a hike (its cells have it already) and under 1 m (none, as in the
 * auto text); the heart rate when it is known.
 */
fun cardFacts(inputs: VideoInputs, region: String?): CardFacts {
    val t = inputs.totals
    val activity = inputs.activity
    val points = inputs.timeline.points
    val dateLine = if (points.isEmpty()) "" else cardDateLine(
        dateOf(points.first().timeMillis),
        dateOf(points.last().timeMillis),
        points.map { it.dayIndex }.distinct().size,
    )
    val cells = ActivityText.cardCells(
        activity, t.distanceM, t.movingTimeMs, t.hikeElapsedMs, t.ascentM, t.maxAltitudeM,
        paceDistanceM = t.paceDistanceM ?: t.distanceM,
        paceMovingTimeMs = t.paceMovingTimeMs ?: t.movingTimeMs,
    )
    val ascent = if (activity != ActivityType.HIKE && t.ascentM >= 1.0) meterText(t.ascentM) else null
    return CardFacts(activity, inputs.rideTitle, region, dateLine, cells, ascent, t.avgBpm?.let { "♥ $it" })
}

/**
 * The card's date line (spec §2.1), in [cardDateText]'s form: one day "2026년 11월 3일 (화)"; several "3일 · 11월 1일–3일",
 * or "3일 · 10월 31일–11월 2일" across months, [days] being the riding days.
 */
fun cardDateLine(first: LocalDate, last: LocalDate, days: Int): String = when {
    first == last -> "${first.year}년 ${cardDateText(first)}"
    first.year == last.year && first.month == last.month ->
        "${days}일 · ${first.monthValue}월 ${first.dayOfMonth}일–${last.dayOfMonth}일"
    else -> "${days}일 · ${first.monthValue}월 ${first.dayOfMonth}일–${last.monthValue}월 ${last.dayOfMonth}일"
}

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(SEOUL).toLocalDate()
