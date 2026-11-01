package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.core.series.SpeedBand
import kotlin.math.roundToInt

/**
 * The speed and heart-rate chart copy (V1.8.0 spec §3) and the charts' titles. Distances by [SummaryFormatter.distance],
 * km/h and bpm rounded to whole numbers and never below 0 (a smoothed stop can come out a hair under it).
 */
object ChartText {
    const val TITLE_ELEVATION = "고도"
    const val TITLE_SPEED = "속도"
    const val TITLE_HEART = "심박"
    const val NO_SPEED = "속도 정보가 없습니다"

    /** The speed bands' legend, slowest first. */
    val SPEED_LEGEND: List<String> = SpeedBand.entries.map { it.legend }

    /** The heart-rate bands' legend, [HrBand][com.cowork.bikerecoder.core.post.HrBand] BLUE to RED. */
    val HEART_LEGEND: List<String> = listOf("100 미만", "100~120", "120~140", "140~160", "160 이상")

    /** "1.1km · 18km/h", "1.1km · 145bpm": where the big chart is scrubbed; "--" where there is no value. */
    fun scrubLine(kind: MetricKind, distanceM: Double, value: Double?): String =
        "${SummaryFormatter.distance(distanceM)} · ${value?.let(::whole) ?: "--"}${unit(kind)}"

    /** "속도 그래프, 평균 18km/h, 최고 42km/h": what the speed chart reads out. */
    fun speedDescription(avgKmh: Double, maxKmh: Double): String =
        "속도 그래프, 평균 ${whole(avgKmh)}km/h, 최고 ${whole(maxKmh)}km/h"

    /** "심박 그래프, 평균 132bpm, 최고 171bpm": what the heart-rate chart reads out. */
    fun heartDescription(avgBpm: Int, maxBpm: Int): String = "심박 그래프, 평균 ${avgBpm}bpm, 최고 ${maxBpm}bpm"

    /** "34km/h", "162bpm": the chart's top and bottom labels. */
    fun valueLabel(kind: MetricKind, v: Double): String = "${whole(v)}${unit(kind)}"

    private fun unit(kind: MetricKind) = when (kind) {
        MetricKind.SPEED -> "km/h"
        MetricKind.HEART_RATE -> "bpm"
    }

    private fun whole(v: Double): Int = v.roundToInt().coerceAtLeast(0)
}
