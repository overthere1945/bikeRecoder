package com.cowork.bikerecoder.core.series

import com.cowork.bikerecoder.core.elevation.TrackAxis
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.TrackPoint
import kotlin.math.roundToInt

/** 그래프 계열의 종류. */
enum class MetricKind { SPEED, HEART_RATE }

/**
 * [TrackAxis] 위의 속도(km/h) 또는 심박(bpm) 그래프 계열.
 *
 * 점별 원래 값 → (속도) [MAX_SPEED_KMH] 넘는 값은 null → [SeriesSmoother.smooth] → [MAX_POINTS]점 이하로 줄이기 순서로 만든다.
 * [values]의 null은 값이 없는 곳이며, 그래프는 그 자리에서 끊긴다.
 * 이웃한 두 값이 모두 있는 곳이 하나도 없으면(그릴 선분이 없으면) 계열을 만들지 않는다.
 */
class MetricSeries private constructor(
    val kind: MetricKind,
    /** 축 위 거리(m). 줄인 점들의 것이며 줄지 않는다. */
    val distancesM: DoubleArray,
    /** 다듬고 줄인 값. [distancesM]과 같은 길이. */
    val values: List<Double?>,
    /** 축 전체 거리(m). */
    val totalM: Double,
    /** 구간 경계의 거리(m). [TrackAxis.boundariesM]와 같다. */
    val boundariesM: List<Double>,
) {
    /** 다듬은(줄인) 값의 최저. 그래프 아래 끝 글에 쓴다. */
    val minValue: Double = values.filterNotNull().min()

    /** 다듬은(줄인) 값의 최고. 그래프 위 끝 글에 쓴다. */
    val maxValue: Double = values.filterNotNull().max()

    /** 거리 [d]의 값. 이웃 두 점 사이 선형이고, 어느 한쪽이 null이면 null. 범위 밖은 끝 값. */
    fun valueAt(d: Double): Double? {
        if (d.isNaN()) return null
        val xs = distancesM
        if (d <= xs.first()) return values.first()
        if (d >= xs.last()) return values.last()
        val i = firstIndexAtLeast(xs, d)
        if (xs[i] == d) return values[i]
        val a = values[i - 1] ?: return null
        val b = values[i] ?: return null
        val f = (d - xs[i - 1]) / (xs[i] - xs[i - 1])
        return a + f * (b - a)
    }

    /** 거리 [d]의 색(`#RRGGBB`). 속도는 [SpeedBand], 심박은 [HeartRateColor.band]. 값이 없으면 null. */
    fun colorHexAt(d: Double): String? {
        val v = valueAt(d) ?: return null
        return when (kind) {
            MetricKind.SPEED -> SpeedBand.of(v).hex
            MetricKind.HEART_RATE -> HeartRateColor.band(v.roundToInt()).colorHex
        }
    }

    companion object {
        /** 그래프에 남기는 최대 점 수. */
        const val MAX_POINTS = 300

        /** 이보다 빠른 속도 값(GPS 튐)은 다듬기 전에 null로 버린다. */
        const val MAX_SPEED_KMH = 100.0

        /**
         * 속도 계열(km/h). 점의 값은 `speedMps`, 없으면 같은 구간 바로 앞 점과의 구간 속도다.
         * 시각 차가 0 이하면 구간 속도도 null이다. 값 있는 점이 2개 미만이거나 그릴 선분이 없으면 null.
         * [segments]는 [axis]를 만든 것과 같은 목록이어야 한다.
         */
        fun speed(axis: TrackAxis, segments: List<List<TrackPoint>>): MetricSeries? {
            val raw = ArrayList<Double?>(axis.points.size)
            for (segment in segments) {
                for (i in segment.indices) {
                    val mps = segment[i].speedMps?.toDouble() ?: pairSpeedMps(segment, i)
                    raw.add(mps?.let { it * 3.6 }?.takeIf { it.isFinite() && it <= MAX_SPEED_KMH })
                }
            }
            return build(MetricKind.SPEED, axis, raw, SeriesSmoother.SPEED_WINDOW_MS)
        }

        /**
         * 심박 계열(bpm). 점마다 시각이 가장 가까운 표본을 ±[HeartRateColor.MATCH_MS] 안에서 붙이고, 없으면 null 점이다.
         * 붙은 점이 2개 미만이거나 그릴 선분이 없으면(예: 표본이 드문드문해 줄인 뒤 이웃한 값이 없음) null.
         * [segments]는 [axis]를 만든 것과 같은 목록이어야 한다.
         */
        fun heartRate(
            axis: TrackAxis,
            segments: List<List<TrackPoint>>,
            samples: List<HeartRateSample>,
        ): MetricSeries? {
            val sorted = samples.sortedBy { it.timeMillis }
            val raw = ArrayList<Double?>(axis.points.size)
            for (segment in segments) {
                for (tp in segment) raw.add(HeartRateColor.matchedBpm(sorted, tp.timeMillis)?.toDouble())
            }
            return build(MetricKind.HEART_RATE, axis, raw, SeriesSmoother.HEART_WINDOW_MS)
        }

        private fun build(kind: MetricKind, axis: TrackAxis, raw: List<Double?>, windowMs: Long): MetricSeries? {
            require(raw.size == axis.points.size) { "segments do not match the axis" }
            if (raw.count { it != null } < 2) return null
            val smoothed = SeriesSmoother.smooth(axis.timesMs, axis.segmentOf, raw, windowMs)
            val (d, v) = downsample(axis.distancesM, smoothed, MAX_POINTS)
            if (!hasDrawableStep(v)) return null
            return MetricSeries(kind, d, v, axis.totalM, axis.boundariesM)
        }

        /** 이웃한 두 값이 모두 null이 아닌 곳이 있으면 true. */
        private fun hasDrawableStep(v: List<Double?>): Boolean =
            (1 until v.size).any { v[it - 1] != null && v[it] != null }

        private fun pairSpeedMps(segment: List<TrackPoint>, i: Int): Double? {
            if (i == 0) return null
            val dtMs = segment[i].timeMillis - segment[i - 1].timeMillis
            if (dtMs <= 0) return null
            return GeoMath.distanceM(segment[i - 1].point, segment[i].point) / (dtMs / 1000.0)
        }

        /**
         * 점이 [maxPoints]보다 많으면 첫 점과 마지막 점은 두고, 사이 점들을 순서대로 maxPoints − 2개 묶음으로 나눠
         * 묶음마다 거리를 평균한다. 값은 묶음의 절반을 넘는 점이 null이면 null이고,
         * 아니면 null이 아닌 값들만의 평균이다(null 자리를 0으로 세지 않는다). 거리는 null 점까지 묶음 전체의 평균이다.
         */
        internal fun downsample(d: DoubleArray, v: List<Double?>, maxPoints: Int): Pair<DoubleArray, List<Double?>> {
            val n = v.size
            if (n <= maxPoints) return d.copyOf() to v
            val buckets = maxPoints - 2
            val middle = n - 2
            val outD = DoubleArray(maxPoints)
            val outV = arrayOfNulls<Double>(maxPoints)
            outD[0] = d[0]
            outV[0] = v[0]
            for (b in 0 until buckets) {
                // 크기가 많아야 하나 차이 나는 묶음. middle > buckets이므로 빈 묶음은 없다.
                val from = 1 + (b.toLong() * middle / buckets).toInt()
                val to = 1 + ((b + 1).toLong() * middle / buckets).toInt()
                var sumD = 0.0
                var sumV = 0.0
                var nulls = 0
                for (k in from until to) {
                    sumD += d[k]
                    val x = v[k]
                    if (x == null) nulls++ else sumV += x
                }
                val size = to - from
                outD[b + 1] = sumD / size
                outV[b + 1] = if (nulls * 2 > size) null else sumV / (size - nulls)
            }
            outD[maxPoints - 1] = d[n - 1]
            outV[maxPoints - 1] = v[n - 1]
            return outD to outV.asList()
        }

        /** [xs]가 오름차순일 때 xs[i] >= x인 첫 i. */
        private fun firstIndexAtLeast(xs: DoubleArray, x: Double): Int {
            var lo = 0
            var hi = xs.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (xs[mid] >= x) hi = mid else lo = mid + 1
            }
            return lo
        }
    }
}
