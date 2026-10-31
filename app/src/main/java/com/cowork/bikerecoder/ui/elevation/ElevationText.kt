package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.elevation.GradeBand
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.navigation.AheadState
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * The elevation copy of the app screens (V1.7.0 spec §5): the chart, the "고도" map screen, the plan preview's usual
 * speed and the guidance strip's line. Distances by [SummaryFormatter.distance], heights by [RideFormat.altitude],
 * grades and km/h rounded to whole numbers.
 */
object ElevationText {
    const val NO_ELEVATION = "고도 정보가 없습니다"
    const val SHOW_ON_MAP = "지도에 표시"
    const val TITLE = "고도"
    const val CLOSE = "닫기"

    /** "1.2km · 35m · 경사 5%": where the big chart is scrubbed. */
    fun scrubLine(distanceM: Double, elevationM: Double, gradePct: Double): String =
        "${SummaryFormatter.distance(distanceM)} · ${RideFormat.altitude(elevationM)} · 경사 ${gradePct.roundToInt()}%"

    /** "고도 그래프, 최저 12m, 최고 240m, 오르막 3곳": what the chart reads out. */
    fun chartDescription(minM: Double, maxM: Double, climbs: Int): String =
        "고도 그래프, 최저 ${RideFormat.altitude(minM)}, 최고 ${RideFormat.altitude(maxM)}, 오르막 ${climbs}곳"

    /** "평소 속도 18 km/h 기준" for a usual speed of [mps] metres per second. */
    fun usualSpeedLine(mps: Double): String = "평소 속도 ${(mps * 3.6).roundToInt()} km/h 기준"

    /** The guidance strip's line (spec §3.2) for [state]. */
    fun aheadLine(state: AheadState): String = when (state) {
        is AheadState.InClimb ->
            "오르막 남은 거리 ${SummaryFormatter.distance(state.remainingM)} · 평균 ${state.avgGradePct.roundToInt()}%"
        is AheadState.ClimbAhead ->
            "${SummaryFormatter.distance(state.inM)} 앞 오르막 · ${SummaryFormatter.distance(state.lengthM)} · " +
                "평균 ${state.avgGradePct.roundToInt()}%"
        AheadState.Flat -> "앞 2 km 평탄"
    }

    /** The legend text of [band] (spec §1.3). */
    fun legend(band: GradeBand): String = band.legend

    /** A km axis label: "0km", "2km", "0.5km". */
    fun kmTick(km: Double): String = "${BigDecimal.valueOf(km).stripTrailingZeros().toPlainString()}km"
}

/**
 * The km step of the chart's distance axis (spec §2.1): of 1, 2, 5 and 10 km the first that gives 3 to 6 ticks from 0 to
 * [totalM]; for rides too short or too long for those, the first of the same 1-2-5 series that does, else the one
 * closest to it.
 */
internal fun kmTickStepKm(totalM: Double): Double {
    val km = totalM / 1_000.0
    fun ticks(step: Double) = kotlin.math.floor(km / step + 1e-9).toInt() + 1
    PREFERRED_STEPS_KM.firstOrNull { ticks(it) in 3..6 }?.let { return it }
    FALLBACK_STEPS_KM.firstOrNull { ticks(it) in 3..6 }?.let { return it }
    return FALLBACK_STEPS_KM.minBy { step ->
        val n = ticks(step)
        if (n < 3) 3 - n else maxOf(0, n - 6)
    }
}

private val PREFERRED_STEPS_KM = listOf(1.0, 2.0, 5.0, 10.0)
private val FALLBACK_STEPS_KM = listOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
