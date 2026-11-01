package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.post.HrBand
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.core.series.SpeedBand

/** A tab of the big map screen (V1.8.0 spec §2.4); [arg] is its name in the route's `tab`. */
enum class ChartTab(val arg: String) {
    ELEVATION("elevation"),
    SPEED("speed"),
    HEART_RATE("heartRate"),
    ;

    /** The tab's text, the same as the chart's title. */
    val title: String
        get() = when (this) {
            ELEVATION -> ChartText.TITLE_ELEVATION
            SPEED -> ChartText.TITLE_SPEED
            HEART_RATE -> ChartText.TITLE_HEART
        }

    /** The tab's text for the speed chart's [style]: the speed tab of a run or walk alone reads 페이스. */
    fun titleFor(style: SpeedChartStyle): String = if (this == SPEED) ChartText.title(style) else title

    /** The test tag of the tab's button. */
    val tag: String get() = "chart_tab_$arg"

    companion object {
        /** The test tag of the row of tabs. */
        const val ROW_TAG = "chart_tabs"

        /** The tab named [arg]; the elevation tab for none or an unknown name. */
        fun of(arg: String?): ChartTab = entries.firstOrNull { it.arg == arg } ?: ELEVATION
    }
}

/**
 * The speed or heart-rate legend (V1.8.0 spec §3): each band's text with its "#RRGGBB", slowest / lowest first. Heart
 * rate pairs [ChartText.HEART_LEGEND] with [HrBand] BLUE to RED (NONE, no sample, has no cell). The speed legend follows
 * the activity of [style] (V1.9.0 spec §3.5); a mixed trip has none.
 */
internal fun metricLegend(kind: MetricKind, style: SpeedChartStyle = SpeedChartStyle.BIKE): List<Pair<String, String>> = when (kind) {
    MetricKind.SPEED -> ChartText.speedLegend(style).zip(SpeedBand.entries.map { it.hex })
    MetricKind.HEART_RATE -> ChartText.HEART_LEGEND.zip(HrBand.entries.filter { it != HrBand.NONE }.map { it.colorHex })
}
