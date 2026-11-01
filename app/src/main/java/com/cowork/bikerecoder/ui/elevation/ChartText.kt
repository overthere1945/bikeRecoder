package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.activity.ActivityFormat
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.core.series.SpeedBand
import com.cowork.bikerecoder.ui.activity.ActivityText
import kotlin.math.roundToInt

/**
 * How the speed chart is named, valued and coloured (V1.9.0 spec §3.5, plan P7): of one [activity], or of a trip that
 * mixes activities ([activity] null). The chart always holds speed; a run's or walk's reads it as a pace.
 */
data class SpeedChartStyle(val activity: ActivityType?) {

    /** Several activities in one trip: 속도 in km/h, each segment coloured by its own activity, no legend. */
    val mixed: Boolean get() = activity == null

    /** The chart is named 페이스 and its numbers are paces: a run or a walk alone. */
    val pace: Boolean get() = activity == ActivityType.RUN || activity == ActivityType.WALK

    companion object {
        val BIKE = SpeedChartStyle(ActivityType.BIKE)

        /**
         * The style of the segments' [activities] (the non-empty ones of the chart's axis): the one they all share
         * (pace only if all are runs or all walks), mixed when they differ; a bike's for none.
         */
        fun of(activities: List<ActivityType>): SpeedChartStyle {
            val distinct = activities.distinct()
            return when (distinct.size) {
                0 -> BIKE
                1 -> SpeedChartStyle(distinct.single())
                else -> SpeedChartStyle(null)
            }
        }
    }
}

/**
 * The speed and heart-rate chart copy (V1.8.0 spec §3) and the charts' titles. Distances by [SummaryFormatter.distance],
 * km/h and bpm rounded to whole numbers and never below 0 (a smoothed stop can come out a hair under it).
 */
object ChartText {
    const val TITLE_ELEVATION = "고도"
    const val TITLE_SPEED = "속도"
    const val TITLE_PACE = "페이스"
    const val TITLE_HEART = "심박"
    const val NO_SPEED = "속도 정보가 없습니다"

    /** The speed bands' legend, slowest first. */
    val SPEED_LEGEND: List<String> = SpeedBand.entries.map { it.legend }

    /** The heart-rate bands' legend, [HrBand][com.cowork.bikerecoder.core.post.HrBand] BLUE to RED. */
    val HEART_LEGEND: List<String> = listOf("100 미만", "100~120", "120~140", "140~160", "160 이상")

    /** The speed chart's title and tab: 페이스 for a run or walk alone, else 속도. */
    fun title(style: SpeedChartStyle): String = if (style.pace) TITLE_PACE else TITLE_SPEED

    /**
     * The speed legend's texts for [style], slowest first; none for a mixed trip, whose colour scales differ (it shows
     * [ActivityText.MIXED_COLOR_NOTE] instead).
     */
    fun speedLegend(style: SpeedChartStyle): List<String> =
        style.activity?.let { activity -> SpeedBand.entries.map { it.legend(activity) } }.orEmpty()

    /** What stands where the legend would: the mixed trip's note, else null. */
    fun legendNote(style: SpeedChartStyle): String? = ActivityText.MIXED_COLOR_NOTE.takeIf { style.mixed }

    /**
     * "1.1km · 18km/h", "1.1km · 145bpm": where the big chart is scrubbed; "--" where there is no value. With [pace] the
     * speed (km/h) reads as a pace: "1.1km · 5'32"/km".
     */
    fun scrubLine(kind: MetricKind, distanceM: Double, value: Double?, pace: Boolean = false): String {
        val shown = if (pace && kind == MetricKind.SPEED) paceText(value) else "${value?.let(::whole) ?: "--"}${unit(kind)}"
        return "${SummaryFormatter.distance(distanceM)} · $shown"
    }

    /** "속도 그래프, 평균 18km/h, 최고 42km/h": what the speed chart reads out. */
    fun speedDescription(avgKmh: Double, maxKmh: Double): String =
        "속도 그래프, 평균 ${whole(avgKmh)}km/h, 최고 ${whole(maxKmh)}km/h"

    /** The speed chart's read-out from the stored [totals] (a trip's or a segment's), never from the series (§2.1). */
    fun speedDescription(totals: SegmentTotals): String =
        speedDescription(totals.avgSpeedMps * MPS_TO_KMH, totals.maxSpeedMps * MPS_TO_KMH)

    /** "페이스 그래프, 평균 5'32", 최고 4'48"": what a run's or walk's chart reads out, from speeds in m/s. */
    fun paceDescription(avgMps: Double, maxMps: Double): String =
        "페이스 그래프, 평균 ${ActivityFormat.pace(avgMps)}, 최고 ${ActivityFormat.pace(maxMps)}"

    /** The speed chart's read-out of [style] from the stored [totals]: a pace for a run or walk, else [speedDescription]. */
    fun speedDescription(totals: SegmentTotals, style: SpeedChartStyle): String =
        if (style.pace) paceDescription(totals.avgSpeedMps, totals.maxSpeedMps) else speedDescription(totals)

    /** "심박 그래프, 평균 132bpm, 최고 171bpm": what the heart-rate chart reads out. */
    fun heartDescription(avgBpm: Int, maxBpm: Int): String = "심박 그래프, 평균 ${avgBpm}bpm, 최고 ${maxBpm}bpm"

    /**
     * The heart-rate chart's read-out (§2.1): the stored [avgBpm] and [maxBpm] (a trip's moving-time-weighted average and
     * highest, or a segment's); without either, the mean and highest of [samples]; "" without those either.
     */
    fun heartDescription(avgBpm: Int?, maxBpm: Int?, samples: List<HeartRateSample>): String {
        if (avgBpm != null && maxBpm != null) return heartDescription(avgBpm, maxBpm)
        val sampled = HeartRateSummary.of(samples, movingTimeMs = 0L) ?: return ""
        return heartDescription(sampled.avgBpm, sampled.maxBpm)
    }

    /**
     * "34km/h", "162bpm": the chart's top and bottom labels. With [pace] a speed reads as a pace ("4'48"/km"); the faster
     * end stays on top, as the chart's values are speeds.
     */
    fun valueLabel(kind: MetricKind, v: Double, pace: Boolean = false): String =
        if (pace && kind == MetricKind.SPEED) paceText(v) else "${whole(v)}${unit(kind)}"

    /** [kmh] as "5'32"/km"; "--'--"/km" without a usable speed. */
    private fun paceText(kmh: Double?): String = "${ActivityFormat.pace(kmh?.let { it / MPS_TO_KMH })}/km"

    private fun unit(kind: MetricKind) = when (kind) {
        MetricKind.SPEED -> "km/h"
        MetricKind.HEART_RATE -> "bpm"
    }

    private const val MPS_TO_KMH = 3.6

    private fun whole(v: Double): Int = v.roundToInt().coerceAtLeast(0)
}
