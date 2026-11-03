package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.recording.AscentCounter

/**
 * 영상 경로([RideTimeline])에서 찾는 볼거리 위치(스펙 §2.2): 가장 빠른 곳, 30 m 넘게 오른 꼭대기, 가장 높은 곳.
 * 영상 입력에 `segment_extras`가 없어서 지점에서 바로 찾는다. 결과는 경로 거리(m, [RouteDistance])이고 [VideoPace.profile]의 볼거리 점수에 쓴다.
 */
internal object VideoHighlights {

    /** 가장 빠른 곳을 재는 이동 시간 폭. 이동 시간이 이보다 짧으면 찾지 않는다. */
    const val FASTEST_WINDOW_MS = 10_000L

    /** 앞 꼭대기 뒤 가장 낮은 곳에서 이만큼 넘게 오른 꼭대기만 볼거리다. */
    const val CLIMB_M = 30.0

    /** 가장 빠른 곳: 이보다 적게 더 간 구간은 같은 것으로 보고 앞의 것을 쓴다(계산 오차). */
    private const val TIE_M = 1e-6

    /**
     * 볼거리 거리들(작은 것부터). 이동 시간 10초 동안 가장 멀리 간 구간의 가운데, 꼭대기마다(누적 상승과 같은 3 m 문턱으로 거른 고도),
     * 가장 높은 지점. 고도 없는 지점은 건너뛰고, 고도가 하나도 없으면 가장 빠른 곳만.
     */
    fun alongM(timeline: RideTimeline): List<Double> {
        if (timeline.points.isEmpty()) return emptyList()
        val out = ArrayList<Double>()
        fastestMiddle(timeline)?.let { out += it }
        out += climbTops(timeline)
        highest(timeline)?.let { out += it }
        return out.sorted()
    }

    /** 이동 시간 [FASTEST_WINDOW_MS] 동안 가장 멀리 간 구간(지점에서 시작)의 가운데 거리. 같으면([TIE_M]) 앞의 것. */
    private fun fastestMiddle(timeline: RideTimeline): Double? {
        val route = timeline.routeDistance
        var best = -1.0
        var bestStart = -1L
        var lastStart = -1L
        for (p in timeline.points) {
            val start = p.moveMs
            if (start + FASTEST_WINDOW_MS > timeline.totalMoveMs) break
            if (start == lastStart) continue
            lastStart = start
            val d = route.atMove(start + FASTEST_WINDOW_MS) - route.atMove(start)
            if (d > best + TIE_M) {
                best = d
                bestStart = start
            }
        }
        return if (bestStart < 0) null else route.atMove(bestStart + FASTEST_WINDOW_MS / 2)
    }

    /**
     * 꼭대기: 3 m 문턱([HikeProgress.CLIMB_THRESHOLD_M])의 [AscentCounter]로 거른 고도가 오르다 처음 내려가기 직전(또는 끝)의 지점.
     * 앞 꼭대기 뒤(처음이면 출발 뒤) 가장 낮은 곳에서 [CLIMB_M] 이상 올랐을 때만. 여러 구간도 한 줄로 이어 센다.
     */
    private fun climbTops(timeline: RideTimeline): List<Double> {
        val cumulative = timeline.routeDistance.cumulative
        val counter = AscentCounter()
        val tops = ArrayList<Double>()
        var level = Double.NaN // 거른 고도
        var low = Double.NaN
        var peakIndex = -1 // 오르는 중이면 지금까지 가장 높은(거른 고도) 지점
        timeline.points.forEachIndexed { i, p ->
            val altitude = p.altitudeM ?: return@forEachIndexed
            if (level.isNaN()) {
                counter.onAltitude(altitude, HikeProgress.CLIMB_THRESHOLD_M)
                level = altitude
                low = altitude
                return@forEachIndexed
            }
            val before = counter.ascentM - counter.descentM
            counter.onAltitude(altitude, HikeProgress.CLIMB_THRESHOLD_M)
            val change = counter.ascentM - counter.descentM - before
            if (change == 0.0) return@forEachIndexed
            if (change > 0) {
                peakIndex = i
            } else if (peakIndex >= 0) {
                if (level - low >= CLIMB_M) tops += cumulative[peakIndex]
                peakIndex = -1
                low = level + change
            } else {
                low = minOf(low, level + change)
            }
            level += change
        }
        if (peakIndex >= 0 && level - low >= CLIMB_M) tops += cumulative[peakIndex]
        return tops
    }

    /** 가장 높은 지점(같으면 처음 닿은 곳)의 거리. 고도가 없으면 null. */
    private fun highest(timeline: RideTimeline): Double? {
        var best = -1
        timeline.points.forEachIndexed { i, p ->
            val altitude = p.altitudeM ?: return@forEachIndexed
            if (best < 0 || altitude > timeline.points[best].altitudeM!!) best = i
        }
        return if (best < 0) null else timeline.routeDistance.cumulative[best]
    }
}
