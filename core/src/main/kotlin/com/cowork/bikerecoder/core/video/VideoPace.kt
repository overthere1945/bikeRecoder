package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 이동 장면의 빠르기와 강약(스펙 §2): 경로 거리를 이동 장면 시각에 어떻게 펼칠지 정한다.
 * 곧은 길은 빠르게, 굽은 길·사진 앞·볼거리는 천천히 지나간다.
 */
object VideoPace {

    /** 거리가 [MIN_DISTANCE_KM] 미만일 때의 이동 장면 길이(스펙 §1.1). */
    const val MIN_RIDE_SECONDS = 4.0
    const val MIN_DISTANCE_KM = 0.05

    /** 칸: 전체 거리 / [BIN_M]칸, [MIN_BINS]~[MAX_BINS]칸(스펙 §2.2). */
    const val BIN_M = 5.0
    const val MIN_BINS = 60
    const val MAX_BINS = 300

    /** 솎은 축: 앞에 남긴 지점에서 직선으로 이만큼 떨어진 지점만 남긴다(판정 P1). */
    const val THIN_M = 10.0

    /** 칸 경계 사이가 이보다 가까우면 앞 칸 방향을 쓴다. */
    const val BEARING_MIN_M = 15.0

    const val CURVE_WINDOW_M = 50.0
    const val STRAIGHT_WINDOW_M = 150.0
    const val STRAIGHT_MAX_DEG = 20.0
    const val PHOTO_BEFORE_M = 150.0
    const val HIGHLIGHT_M = 100.0

    const val SCORE_STRAIGHT = 0.6
    const val SCORE_PHOTO = 2.0
    const val SCORE_HIGHLIGHT = 1.6
    const val SCORE_CURVE_EXTRA = 0.8

    /** 빠르기 곡선(스펙 §2.3). */
    const val SMOOTH_SIGMA_S = 0.35
    const val MAX_SPEED_RATIO = 4.0
    const val EASE_SECONDS = 1.5

    /** 이동 장면이 이보다 짧으면 가속·감속을 각각 R / 3초로 줄인다. */
    const val EASE_FULL_FROM_SECONDS = 4.0

    /** 전체 거리가 이보다 짧으면 고르게 편다. */
    const val MIN_PACE_M = 50.0

    /** 부드럽게 하기·가속·감속을 계산하는 시간 격자(초). */
    private const val GRID_S = 0.01

    /** 가우스 커널을 자르는 폭(σ의 배수). */
    private const val KERNEL_SIGMAS = 3.0

    /** 이동 장면 기본 길이 R0 = 6 + 5 × √(거리 km)초, [MIN_DISTANCE_KM] 미만이면 [MIN_RIDE_SECONDS](스펙 §1.1). */
    fun rideSeconds(distanceKm: Double): Double =
        if (distanceKm < MIN_DISTANCE_KM) MIN_RIDE_SECONDS else 6.0 + 5.0 * sqrt(distanceKm)

    /**
     * [seconds]초 이동 장면의 빠르기 곡선(스펙 §2.2~2.3). [photoAlongM]은 영상에 들어가는 사진의 경로 거리,
     * [highlightAlongM]은 볼거리([VideoHighlights])의 경로 거리.
     * 칸·점수·방향·부드럽게 하기는 솎은 축([THIN_M])에서 계산한다: 제자리 GPS 흔들림도 경로 거리를 늘리므로,
     * 경로 거리 위에서 펴면 신호 대기에 영상 시간을 쓴다(판정 P1).
     */
    fun profile(timeline: RideTimeline, seconds: Double, photoAlongM: List<Double>, highlightAlongM: List<Double>): PaceProfile {
        require(seconds > 0.0) { "이동 장면 길이는 0초보다 길어야 한다: $seconds" }
        val totalM = timeline.routeDistance.total
        if (totalM < MIN_PACE_M) return PaceProfile.uniform(seconds, totalM)
        val axis = ThinnedAxis.of(timeline)
        if (axis.length < MIN_PACE_M) return PaceProfile.uniform(seconds, totalM)

        val bins = (axis.length / BIN_M).toInt().coerceIn(MIN_BINS, MAX_BINS)
        val binM = axis.length / bins
        val scores = scores(axis, bins, binM, photoAlongM.map(axis::fromRoute), highlightAlongM.map(axis::fromRoute))
        val edgeSeconds = timeCurve(scores, seconds)
        val edgeAxis = DoubleArray(bins + 1) { it * binM }.also { it[bins] = axis.length }
        return PaceProfile(seconds, totalM, edgeSeconds, edgeAxis, axis.axis, axis.route)
    }

    /** 칸마다 점수(스펙 §2.2). [photos]·[highlights]는 솎은 축 거리. */
    private fun scores(axis: ThinnedAxis, bins: Int, binM: Double, photos: List<Double>, highlights: List<Double>): DoubleArray {
        val bearings = binBearings(axis, bins, binM)
        val straightWindow = min(STRAIGHT_WINDOW_M, axis.length / 20)
        return DoubleArray(bins) { j ->
            val centre = (j + 0.5) * binM
            val theta = spread(bearings, j, (CURVE_WINDOW_M / binM).toInt())
            val curve = 1.0 + SCORE_CURVE_EXTRA * min(theta / 90.0, 1.0)
            val nearPhoto = photos.any { centre in (it - PHOTO_BEFORE_M)..it }
            val nearHighlight = highlights.any { abs(centre - it) <= HIGHLIGHT_M }
            when {
                nearPhoto || nearHighlight -> max(curve, if (nearPhoto) SCORE_PHOTO else SCORE_HIGHLIGHT)
                spread(bearings, j, (straightWindow / binM).toInt()) <= STRAIGHT_MAX_DEG -> SCORE_STRAIGHT
                else -> curve
            }
        }
    }

    /**
     * 칸마다 방향(0~360°). 칸 경계 점을 차례로 보며, 앞에서 방향을 잰 경계에서 [BEARING_MIN_M] 이상 떨어진 경계가 나오면
     * 그 사이 칸들에 두 경계 사이 방위각을 준다. 그보다 가까운 경계(짧은 칸, GPS 흔들림)는 앞 칸 방향을 그대로 쓴다.
     */
    private fun binBearings(axis: ThinnedAxis, bins: Int, binM: Double): DoubleArray {
        val edges = Array(bins + 1) { axis.pointAt(it * binM) }
        val out = DoubleArray(bins)
        var from = 0
        var last = Double.NaN
        for (j in 1..bins) {
            if (GeoMath.distanceM(edges[from], edges[j]) < BEARING_MIN_M) continue
            last = CameraPath.bearing(edges[from], edges[j])
            out.fill(last, from, j)
            from = j
        }
        out.fill(if (last.isNaN()) 0.0 else last, from, bins)
        return out
    }

    /** 칸 [j]를 가운데로 앞뒤 [half]칸 안 방향들이 [j]의 방향에서 벌어진 폭(°, 가장 왼쪽에서 가장 오른쪽까지). */
    private fun spread(bearings: DoubleArray, j: Int, half: Int): Double {
        var lo = 0.0
        var hi = 0.0
        for (k in max(0, j - half)..min(bearings.lastIndex, j + half)) {
            val d = CameraPath.signedDelta(bearings[j], bearings[k])
            lo = min(lo, d)
            hi = max(hi, d)
        }
        return hi - lo
    }

    /**
     * 칸 경계마다 누적 시각(스펙 §2.3, 칸은 같은 길이). 칸 시간 ∝ 점수 → [GRID_S] 격자의 빠르기를 σ [SMOOTH_SIGMA_S]초 가우스로
     * 부드럽게([KERNEL_SIGMAS]σ에서 자름, 끝에서는 있는 쪽만으로 나눔) → 가장 느린 빠르기를 가장 빠른 것의 1/[MAX_SPEED_RATIO]로 올림 →
     * 처음·끝 [EASE_SECONDS]초([EASE_FULL_FROM_SECONDS]초 미만이면 R / 3) smoothstep → 전체를 [seconds]초·전체 칸에 맞춤.
     */
    private fun timeCurve(scores: DoubleArray, seconds: Double): DoubleArray {
        val bins = scores.size
        val raw = DoubleArray(bins + 1)
        for (j in 0 until bins) raw[j + 1] = raw[j] + scores[j]
        for (j in 0..bins) raw[j] *= seconds / raw[bins]

        val cells = max(1, ceil(seconds / GRID_S).toInt())
        val dt = seconds / cells
        // 격자 칸마다 빠르기(칸/초): 점수 곡선 위에서 그 시간에 지나는 칸 수.
        val speed = DoubleArray(cells)
        var bin = 0
        var previous = 0.0
        for (i in 0 until cells) {
            val t = if (i == cells - 1) seconds else (i + 1) * dt
            while (bin < bins - 1 && raw[bin + 1] < t) bin++
            val x = bin + (t - raw[bin]) / (raw[bin + 1] - raw[bin])
            speed[i] = (x - previous) / dt
            previous = x
        }

        val sigma = SMOOTH_SIGMA_S / dt
        val reach = ceil(KERNEL_SIGMAS * sigma).toInt()
        val kernel = DoubleArray(reach + 1) { exp(-it * it / (2 * sigma * sigma)) }
        val smooth = DoubleArray(cells) { i ->
            var sum = 0.0
            var weight = 0.0
            for (k in max(0, i - reach)..min(cells - 1, i + reach)) {
                val w = kernel[abs(k - i)]
                sum += speed[k] * w
                weight += w
            }
            sum / weight
        }

        val slowest = smooth.max() / MAX_SPEED_RATIO
        val ease = if (seconds < EASE_FULL_FROM_SECONDS) seconds / 3 else EASE_SECONDS
        for (i in 0 until cells) {
            val t = (i + 0.5) * dt
            smooth[i] = max(smooth[i], slowest) * smoothstep(t / ease) * smoothstep((seconds - t) / ease)
        }

        val along = DoubleArray(cells + 1)
        for (i in 0 until cells) along[i + 1] = along[i] + smooth[i] * dt
        val scale = bins / along[cells]
        for (i in 0..cells) along[i] *= scale

        // 칸 경계 j에 닿는 시각.
        val out = DoubleArray(bins + 1)
        var cell = 0
        for (j in 1 until bins) {
            while (along[cell + 1] < j) cell++
            out[j] = (cell + (j - along[cell]) / (along[cell + 1] - along[cell])) * dt
        }
        out[bins] = seconds
        return out
    }

    private fun smoothstep(t: Double) = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /**
     * 솎은 축: 경로 지점 중 앞에 남긴 지점에서 직선으로 [THIN_M] 이상 떨어진 지점과 처음·끝·구간의 첫·마지막 지점만 남기고,
     * 남긴 지점 사이 직선거리로 잰 축(구간이 바뀌는 곳은 0). [route]는 남긴 지점의 경로 거리([RouteDistance.cumulative]).
     */
    private class ThinnedAxis(val points: List<GeoPoint>, val axis: DoubleArray, val route: DoubleArray) {

        val length: Double get() = axis.last()

        /** 경로 거리 → 솎은 축 거리. */
        fun fromRoute(alongM: Double): Double = interpolateFirst(route, axis, alongM)

        /** 솎은 축 거리 [at]의 위치(남긴 지점 사이 직선 보간). */
        fun pointAt(at: Double): GeoPoint {
            val i = lastAtOrBefore(axis, at)
            if (i < 0) return points.first()
            if (i == points.lastIndex) return points.last()
            val f = (at - axis[i]) / (axis[i + 1] - axis[i])
            val a = points[i]
            val b = points[i + 1]
            return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        }

        companion object {
            fun of(timeline: RideTimeline): ThinnedAxis {
                val all = timeline.points
                val cumulative = timeline.routeDistance.cumulative
                val kept = arrayListOf(0)
                for (i in 1 until all.size) {
                    val segmentFirst = all[i].segmentIndex != all[i - 1].segmentIndex
                    val segmentLast = i == all.lastIndex || all[i + 1].segmentIndex != all[i].segmentIndex
                    if (segmentFirst || segmentLast || GeoMath.distanceM(all[kept.last()].point, all[i].point) >= THIN_M) kept += i
                }
                val axis = DoubleArray(kept.size)
                for (k in 1 until kept.size) {
                    val a = all[kept[k - 1]]
                    val b = all[kept[k]]
                    axis[k] = axis[k - 1] + if (a.segmentIndex == b.segmentIndex) GeoMath.distanceM(a.point, b.point) else 0.0
                }
                return ThinnedAxis(kept.map { all[it].point }, axis, DoubleArray(kept.size) { cumulative[kept[it]] })
            }
        }
    }
}

/**
 * 경로 거리 ↔ 이동 장면 시각(스펙 §2.4). [seconds] = 이동 장면 길이, [totalM] = 경로 전체 거리([RouteDistance.total]).
 * 곡선은 솎은 축(판정 P1) 위의 꺾은점([curveSeconds] 시각에 [curveAxis] 거리)이고, 솎은 축과 경로 거리는 남긴 지점
 * ([keptAxis] ↔ [keptRoute]) 사이 직선으로 오간다. 제자리 흔들림이 늘린 경로 거리는 두 남긴 지점 사이에 몰려 거의 시간을 쓰지 않는다.
 */
class PaceProfile internal constructor(
    val seconds: Double,
    val totalM: Double,
    private val curveSeconds: DoubleArray,
    private val curveAxis: DoubleArray,
    private val keptAxis: DoubleArray,
    private val keptRoute: DoubleArray,
) {

    /** 시각 [second]의 경로 거리. 0~[seconds]로 자르고, 0초는 0, [seconds]초는 [totalM], 줄지 않는다. */
    fun alongAt(second: Double): Double {
        if (second <= 0.0) return 0.0
        if (second >= seconds) return totalM
        return interpolateLast(keptAxis, keptRoute, interpolateLast(curveSeconds, curveAxis, second))
    }

    /** 경로 거리 [alongM]에 처음 닿는 시각([alongAt]의 역). */
    fun secondAt(alongM: Double): Double =
        interpolateFirst(curveAxis, curveSeconds, interpolateFirst(keptRoute, keptAxis, alongM.coerceIn(0.0, totalM)))

    /**
     * 시각 [second]의 빠르기(앞뒤 [SPEED_HALF_S]초 동안의 경로 미터/초) ÷ 평균 빠르기([totalM] / [seconds]).
     * 제자리 흔들림을 지나는 순간은 경로 거리가 몰려 있어 크게 튈 수 있다. 전체 거리가 0이면 1.
     */
    fun speedFactor(second: Double): Double {
        if (totalM <= 0.0) return 1.0
        val from = (second - SPEED_HALF_S).coerceIn(0.0, seconds)
        val to = (second + SPEED_HALF_S).coerceIn(0.0, seconds)
        if (to <= from) return 1.0
        return (alongAt(to) - alongAt(from)) / (to - from) / (totalM / seconds)
    }

    /**
     * 박자 맞추기(스펙 §5.3): [anchors] = (옛 시각, 새 시각), 둘 다 늘어나는 순서, (0, 0)은 따로 적지 않는다.
     * 마지막 옛 시각은 [seconds], 마지막 새 시각이 새 길이. 기준점 사이는 직선으로 옮기고, 새 `alongAt(새)` = 옛 `alongAt(되돌린 옛)`.
     */
    fun warped(anchors: List<Pair<Double, Double>>): PaceProfile {
        require(anchors.isNotEmpty()) { "기준점이 없다" }
        var previous = 0.0 to 0.0
        for (a in anchors) {
            require(a.first > previous.first && a.second > previous.second) { "기준점이 늘어나지 않는다: $previous → $a" }
            previous = a
        }
        require(abs(anchors.last().first - seconds) <= 1e-9) { "마지막 기준점의 옛 시각이 ${seconds}초가 아니다: ${anchors.last().first}" }
        val olds = DoubleArray(anchors.size + 1)
        val news = DoubleArray(anchors.size + 1)
        anchors.forEachIndexed { i, (old, new) ->
            olds[i + 1] = old
            news[i + 1] = new
        }
        olds[anchors.size] = seconds

        val breaks = (curveSeconds.asList() + olds.asList()).sorted().distinct()
        return PaceProfile(
            seconds = news.last(),
            totalM = totalM,
            curveSeconds = DoubleArray(breaks.size) { interpolateLast(olds, news, breaks[it]) },
            curveAxis = DoubleArray(breaks.size) { interpolateLast(curveSeconds, curveAxis, breaks[it]) },
            keptAxis = keptAxis,
            keptRoute = keptRoute,
        )
    }

    companion object {
        /** [speedFactor]가 재는 앞뒤 폭(초). */
        const val SPEED_HALF_S = 0.05

        /** 고르게 편 곡선(짧은 경로, 스펙 §2.3). */
        internal fun uniform(seconds: Double, totalM: Double): PaceProfile {
            val line = doubleArrayOf(0.0, totalM)
            return PaceProfile(seconds, totalM, doubleArrayOf(0.0, seconds), line, line, line)
        }
    }
}

/** 줄지 않는 [xs]에서 값이 [x] 이하인 마지막 인덱스(없으면 -1). */
private fun lastAtOrBefore(xs: DoubleArray, x: Double): Int {
    var lo = 0
    var hi = xs.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] <= x) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/** 꺾은선 ([xs], [ys])의 [x]에서의 값. [x]와 같은 [xs]가 여럿이면 마지막 것. 범위 밖은 양 끝 값. */
private fun interpolateLast(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
    val i = lastAtOrBefore(xs, x)
    if (i < 0) return ys.first()
    if (i == xs.lastIndex) return ys.last()
    return ys[i] + (ys[i + 1] - ys[i]) * (x - xs[i]) / (xs[i + 1] - xs[i])
}

/** 꺾은선 ([xs], [ys])의 [x]에서의 값. [x]와 같은 [xs]가 여럿이면 처음 것. 범위 밖은 양 끝 값. */
private fun interpolateFirst(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
    // 값이 x 이상인 첫 인덱스.
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] >= x) hi = mid else lo = mid + 1
    }
    if (lo == 0) return ys.first()
    if (lo == xs.size) return ys.last()
    return ys[lo - 1] + (ys[lo] - ys[lo - 1]) * (x - xs[lo - 1]) / (xs[lo] - xs[lo - 1])
}
