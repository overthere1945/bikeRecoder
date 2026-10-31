package com.cowork.bikerecoder.core.elevation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.TrackPoint

/**
 * 구간들을 이어 붙인 거리 축. 고도·속도·심박 그래프와 큰 지도가 모두 이 축을 쓴다.
 *
 * 바깥 목록은 시작 시각 순 구간, 안쪽은 시간 순 점이다. 빈 구간은 건너뛴다.
 * 구간 안에서는 이웃 점 사이 거리를 모두 더하고, 앞 구간 끝과 다음 구간 첫 점 사이 거리는 더하지 않는다.
 * 점 배열([points], [segmentOf], [timesMs], [distancesM])은 비지 않은 구간들의 점을 순서대로 이은 것이라
 * `segments.flatten()`과 한 점씩 맞는다.
 */
class TrackAxis private constructor(
    /** 비지 않은 구간들의 점을 순서대로 이은 것. */
    val points: List<GeoPoint>,
    /** 점마다 구간 번호. 비지 않은 구간만 0부터 센다([paths]의 번호와 같다). */
    val segmentOf: IntArray,
    /** 점마다 시각(epoch ms). */
    val timesMs: LongArray,
    /** 점마다 축 위 거리(m). 0부터 시작하고 줄지 않는다. */
    val distancesM: DoubleArray,
    /** 둘째 구간부터 각 구간이 시작하는 거리(m). 오름차순. */
    val boundariesM: List<Double>,
    /** 구간별 점. 지도 선을 구간마다 끊어 그릴 때 쓴다. */
    val paths: List<List<GeoPoint>>,
) {
    /** 전체 거리(m). */
    val totalM: Double get() = distancesM.last()

    companion object {
        /** 빈 구간은 건너뛴다. 점이 2개 미만이면 null. */
        fun of(segments: List<List<TrackPoint>>): TrackAxis? {
            val nonEmpty = segments.filter { it.isNotEmpty() }
            val n = nonEmpty.sumOf { it.size }
            if (n < 2) return null

            val points = ArrayList<GeoPoint>(n)
            val segmentOf = IntArray(n)
            val times = LongArray(n)
            val dist = DoubleArray(n)
            val boundaries = ArrayList<Double>()
            var total = 0.0
            var k = 0
            for ((s, segment) in nonEmpty.withIndex()) {
                if (s > 0) boundaries.add(total)
                for ((i, tp) in segment.withIndex()) {
                    if (i > 0) total += GeoMath.distanceM(segment[i - 1].point, tp.point)
                    points.add(tp.point)
                    segmentOf[k] = s
                    times[k] = tp.timeMillis
                    dist[k] = total
                    k++
                }
            }
            return TrackAxis(points, segmentOf, times, dist, boundaries, nonEmpty.map { seg -> seg.map { it.point } })
        }
    }
}
