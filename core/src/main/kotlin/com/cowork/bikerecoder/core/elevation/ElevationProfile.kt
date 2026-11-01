package com.cowork.bikerecoder.core.elevation

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.recording.TrackPoint
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 거리 축 위의 고도 단면. 그래프·지도 색·오르막 찾기·시간 계산이 모두 같은 단면을 쓴다.
 *
 * 거리는 0부터 [stepM] 간격이고 마지막 값은 전체 거리다. [elevationsM]은 다듬은 값,
 * [gradesPct]는 각 위치를 가운데로 한 100 m 폭의 경사다.
 * 원래 경로 선([pointAt]용)은 단면과 같은 거리 축으로 따로 들고 있다.
 */
class ElevationProfile private constructor(
    val stepM: Double,
    val distancesM: DoubleArray,
    val elevationsM: DoubleArray,
    val gradesPct: DoubleArray,
    /** 구간(경유지) 경계의 거리. 오름차순. */
    val boundariesM: List<Double>,
    private val pathPoints: List<GeoPoint>,
    private val pathDistM: DoubleArray,
) {
    /** 전체 거리(m). */
    val totalM: Double get() = distancesM.last()

    val minElevationM: Double = elevationsM.min()

    val maxElevationM: Double = elevationsM.max()

    /** 같은 경사 단계가 이어지는 구간 목록. 0부터 [totalM]까지 빈틈이 없다. */
    val bands: List<BandRun> = buildBands()

    /** [distanceM] 위치의 다듬은 고도(m). 범위 밖은 끝 값. */
    fun elevationAt(distanceM: Double): Double = interpolate(distancesM, elevationsM, distanceM)

    /** [distanceM] 위치의 경사(%). 범위 밖은 끝 값. */
    fun gradeAt(distanceM: Double): Double = interpolate(distancesM, gradesPct, distanceM)

    /** [distanceM] 위치의 경사 단계. [bands]의 색칠과 같은 값이다. */
    fun bandAt(distanceM: Double): GradeBand {
        val d = distanceM.coerceIn(0.0, totalM)
        var lo = 0
        var hi = bands.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (bands[mid].startM <= d) lo = mid else hi = mid - 1
        }
        return bands[lo].band
    }

    /**
     * 원래 경로 선 위에서 [distanceM]에 해당하는 좌표. 0 미만이면 시작점, 전체 거리 초과면 끝점.
     * 구간 사이를 이은 거리 축을 쓰므로 이음매에서 좌표가 건너뛴다.
     */
    fun pointAt(distanceM: Double): GeoPoint {
        if (distanceM <= pathDistM.first()) return pathPoints.first()
        if (distanceM >= pathDistM.last()) return pathPoints.last()
        val i = firstIndexAtLeast(pathDistM, distanceM)
        val a = pathPoints[i - 1]
        val b = pathPoints[i]
        val f = (distanceM - pathDistM[i - 1]) / (pathDistM[i] - pathDistM[i - 1])
        return GeoPoint(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon))
    }

    /**
     * [fromM]~[toM] 구간만 떼어 거리를 0부터 다시 매긴 단면. 범위를 벗어나면 맞추고,
     * 길이가 [MIN_LENGTH_M] 미만이거나 NaN이면 null. 고도와 경사는 전체 단면의 값을 그대로 쓴다.
     */
    fun slice(fromM: Double, toM: Double): ElevationProfile? {
        val from = max(0.0, fromM)
        val to = min(totalM, toM)
        val length = to - from
        if (!(length >= MIN_LENGTH_M)) return null

        val grid = grid(length, stepM)
        val path = ArrayList<GeoPoint>()
        val pathDist = ArrayList<Double>()
        path.add(pointAt(from))
        pathDist.add(0.0)
        for (i in pathPoints.indices) {
            val d = pathDistM[i]
            if (d > from && d < to) {
                path.add(pathPoints[i])
                pathDist.add(d - from)
            }
        }
        path.add(pointAt(to))
        pathDist.add(length)

        return ElevationProfile(
            stepM = stepM,
            distancesM = grid,
            elevationsM = DoubleArray(grid.size) { elevationAt(from + grid[it]) },
            gradesPct = DoubleArray(grid.size) { gradeAt(from + grid[it]) },
            boundariesM = boundariesM.filter { it > from && it < to }.map { it - from },
            pathPoints = path,
            pathDistM = pathDist.toDoubleArray(),
        )
    }

    private fun buildBands(): List<BandRun> {
        val runs = ArrayList<BandRun>()
        val n = distancesM.size
        for (i in 0 until n) {
            // 표본 i가 맡는 칸: 이웃 표본과의 중간점까지.
            val start = if (i == 0) 0.0 else (distancesM[i - 1] + distancesM[i]) / 2
            val end = if (i == n - 1) distancesM[i] else (distancesM[i] + distancesM[i + 1]) / 2
            val band = GradeBand.of(gradesPct[i])
            val last = runs.lastOrNull()
            if (last != null && last.band == band) {
                runs[runs.size - 1] = last.copy(endM = end)
            } else {
                runs.add(BandRun(start, end, band))
            }
        }
        return runs
    }

    companion object {
        /** 전체 거리 [LONG_TRIP_M] 이하일 때 다시 고르기 간격(m). */
        const val SHORT_STEP_M = 10.0

        /** 전체 거리가 [LONG_TRIP_M]를 넘을 때 다시 고르기 간격(m). */
        const val LONG_STEP_M = 20.0
        const val LONG_TRIP_M = 50_000.0

        /** 이동 평균 폭(m). 앞뒤 25 m. */
        const val SMOOTH_WINDOW_M = 50.0

        /** 경사를 재는 폭(m). 앞뒤 50 m. */
        const val GRADE_WINDOW_M = 100.0

        /** 단면을 만드는 최소 전체 거리(m). */
        const val MIN_LENGTH_M = 50.0

        private const val EPS = 1e-9

        /**
         * 구간들을 이어 붙인 단면. 바깥 목록은 시작 시각 순 구간, 안쪽은 시간 순 점.
         * 거리 축은 [TrackAxis]를 쓴다: 구간이 바뀔 때 앞 구간 끝과 다음 구간 첫 점 사이 거리는 더하지 않는다.
         * 고도 있는 점이 2개 미만이거나 전체 거리가 [MIN_LENGTH_M] 미만이면 null.
         */
        fun fromSegments(segments: List<List<TrackPoint>>): ElevationProfile? {
            val axis = TrackAxis.of(segments) ?: return null
            val altitudes = segments.flatMap { segment -> segment.map { it.altitudeM } }
            return build(axis.points, axis.distancesM, altitudes, axis.boundariesM)
        }

        /**
         * 경로의 단면. `elevationsM`이 비었거나 점 수와 다르면 null.
         * 경계는 `stopPointIndices`의 누적 거리이며 마지막(목적지)은 뺀다.
         */
        fun fromRoute(route: Route): ElevationProfile? {
            if (route.elevationsM.isEmpty() || route.elevationsM.size != route.points.size) return null
            if (route.cumulativeM.size != route.points.size) return null
            val boundaries = route.stopPointIndices.dropLast(1)
                .mapNotNull { route.cumulativeM.getOrNull(it) }
            return build(route.points, route.cumulativeM.toDoubleArray(), route.elevationsM, boundaries)
        }

        private fun build(
            points: List<GeoPoint>,
            pathDist: DoubleArray,
            altitudes: List<Double?>,
            boundaries: List<Double>,
        ): ElevationProfile? {
            if (points.size < 2) return null
            val total = pathDist.last()
            if (total < MIN_LENGTH_M) return null

            val eleD = ArrayList<Double>()
            val eleV = ArrayList<Double>()
            for (i in altitudes.indices) {
                val a = altitudes[i]
                if (a != null && a.isFinite()) {
                    eleD.add(pathDist[i])
                    eleV.add(a)
                }
            }
            if (eleD.size < 2) return null
            val xs = eleD.toDoubleArray()
            val ys = eleV.toDoubleArray()

            val step = if (total <= LONG_TRIP_M) SHORT_STEP_M else LONG_STEP_M
            val grid = grid(total, step)
            val resampled = DoubleArray(grid.size) { interpolate(xs, ys, grid[it]) }
            val smooth = smooth(grid, resampled)
            val grades = grades(grid, smooth, total)
            return ElevationProfile(step, grid, smooth, grades, boundaries, points, pathDist)
        }

        /** 0부터 [step] 간격, 마지막은 [total]. */
        private fun grid(total: Double, step: Double): DoubleArray {
            val n = floor(total / step).toInt()
            val list = ArrayList<Double>(n + 2)
            for (i in 0..n) list.add(i * step)
            if (total - list.last() > EPS) list.add(total) else list[list.size - 1] = total
            return list.toDoubleArray()
        }

        /** 앞뒤 [SMOOTH_WINDOW_M]/2 안 표본의 평균. 양 끝은 있는 만큼만. */
        private fun smooth(d: DoubleArray, v: DoubleArray): DoubleArray {
            val half = SMOOTH_WINDOW_M / 2
            return DoubleArray(d.size) { i ->
                var sum = v[i]
                var count = 1
                var j = i - 1
                while (j >= 0 && d[i] - d[j] <= half + EPS) {
                    sum += v[j]; count++; j--
                }
                j = i + 1
                while (j < d.size && d[j] - d[i] <= half + EPS) {
                    sum += v[j]; count++; j++
                }
                sum / count
            }
        }

        /** 각 위치를 가운데로 한 [GRADE_WINDOW_M] 폭(양 끝은 있는 만큼)의 고도 차 ÷ 실제 폭 × 100. */
        private fun grades(d: DoubleArray, e: DoubleArray, total: Double): DoubleArray {
            val half = GRADE_WINDOW_M / 2
            return DoubleArray(d.size) { i ->
                val lo = max(0.0, d[i] - half)
                val hi = min(total, d[i] + half)
                val width = hi - lo
                if (width <= EPS) 0.0 else (interpolate(d, e, hi) - interpolate(d, e, lo)) / width * 100.0
            }
        }

        /** [xs]가 오름차순일 때 xs[i] >= x인 첫 i. 모두 작으면 xs.size. */
        private fun firstIndexAtLeast(xs: DoubleArray, x: Double): Int {
            var lo = 0
            var hi = xs.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (xs[mid] >= x) hi = mid else lo = mid + 1
            }
            return lo
        }

        /** 선형 보간. 범위 밖은 끝 값. */
        private fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
            if (x <= xs.first()) return ys.first()
            if (x >= xs.last()) return ys.last()
            val i = firstIndexAtLeast(xs, x)
            val f = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
            return ys[i - 1] + f * (ys[i] - ys[i - 1])
        }
    }
}
