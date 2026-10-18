package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.GeoMath

/** 그래프 점 하나: 가로축 [distanceKm](구간 첫 점부터의 누적 거리), 세로축 [value]. */
data class ChartPoint(val distanceKm: Double, val value: Double)

/** 구간 상세의 고도·속도 그래프 데이터(가로축 거리). */
object RideCharts {

    /** 이보다 긴 시간 간격(일시정지·GPS 끊김)의 두 점 사이 거리는 가로축에 더하지 않는다. */
    private const val GAP_MS = 10_000L

    /**
     * 가로축 = 첫 점부터의 누적 거리(km). 시간 간격 > 10초인 쌍(일시정지·GPS 끊김)은 거리를 더하지 않는다.
     * 점이 maxPoints보다 많으면 첫 점과 마지막 점은 그대로 두고, 그 사이 점들을 순서대로 maxPoints − 2개 묶음으로 나눠
     * 묶음마다 x·값을 평균한다(결과 정확히 maxPoints개).
     * altitudeM null인 점은 값 목록에서 빠지되 x 누적에는 쓰인다.
     */
    fun altitude(points: List<TrackPoint>, maxPoints: Int = 300): List<ChartPoint> {
        val xs = cumulativeKm(points)
        val values = points.indices.mapNotNull { i -> points[i].altitudeM?.let { ChartPoint(xs[i], it) } }
        return downsample(values, maxPoints)
    }

    /** km/h. 값은 speedMps, 없으면 바로 앞 점과의 구간 속도; 둘 다 없는 첫 점(또는 시간 차 0)은 빠진다. */
    fun speedKmh(points: List<TrackPoint>, maxPoints: Int = 300): List<ChartPoint> {
        val xs = cumulativeKm(points)
        val values = points.indices.mapNotNull { i ->
            val mps = points[i].speedMps?.toDouble() ?: pairSpeedMps(points, i)
            mps?.let { ChartPoint(xs[i], it * 3.6) }
        }
        return downsample(values, maxPoints)
    }

    private fun pairSpeedMps(points: List<TrackPoint>, i: Int): Double? {
        if (i == 0) return null
        val dtMs = points[i].timeMillis - points[i - 1].timeMillis
        if (dtMs <= 0) return null
        return GeoMath.distanceM(points[i - 1].point, points[i].point) / (dtMs / 1000.0)
    }

    private fun cumulativeKm(points: List<TrackPoint>): DoubleArray {
        val xs = DoubleArray(points.size)
        var m = 0.0
        for (i in 1 until points.size) {
            if (points[i].timeMillis - points[i - 1].timeMillis <= GAP_MS) {
                m += GeoMath.distanceM(points[i - 1].point, points[i].point)
            }
            xs[i] = m / 1000.0
        }
        return xs
    }

    private fun downsample(values: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
        require(maxPoints >= 2) { "maxPoints must be at least 2" }
        if (values.size <= maxPoints) return values
        val middle = values.subList(1, values.size - 1)
        val buckets = maxPoints - 2
        val result = ArrayList<ChartPoint>(maxPoints)
        result += values.first()
        for (b in 0 until buckets) {
            // Ordered buckets whose sizes differ by at most one; none is empty since middle.size > buckets.
            val from = (b.toLong() * middle.size / buckets).toInt()
            val to = ((b + 1).toLong() * middle.size / buckets).toInt()
            val bucket = middle.subList(from, to)
            result += ChartPoint(bucket.sumOf { it.distanceKm } / bucket.size, bucket.sumOf { it.value } / bucket.size)
        }
        result += values.last()
        return result
    }
}
