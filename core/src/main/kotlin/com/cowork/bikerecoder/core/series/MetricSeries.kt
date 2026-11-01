package com.cowork.bikerecoder.core.series

import com.cowork.bikerecoder.core.activity.ActivityType
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
    /**
     * 축의 구간(빈 구간 제외, [TrackAxis.paths]와 같은 번호)마다의 활동 종류. 속도 색 구간을 이 종류로 정한다.
     * 심박 계열에서는 쓰지 않는다.
     */
    val segmentActivities: List<ActivityType> = emptyList(),
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

    /**
     * 거리 [d]의 색(`#RRGGBB`). 속도는 [d]가 놓인 구간의 종류로 정한 [SpeedBand], 심박은 [HeartRateColor.band].
     * 값이 없으면 null. 구간 이음매([boundariesM]) 위의 [d]는 그 이음매에서 시작하는 구간에 속한다
     * (이음매 위의 점이 어느 구간 것인지 아는 곳은 `segment` 인자를 받는 [colorHexAt]을 쓴다).
     */
    fun colorHexAt(d: Double): String? = colorHexAt(d, segmentAt(d))

    /** [colorHexAt]을 구간 번호([TrackAxis.paths]와 같은 번호)를 아는 점에 쓴다. 이음매 위 점도 자기 구간의 종류로 칠한다. */
    fun colorHexAt(d: Double, segment: Int): String? {
        val v = valueAt(d) ?: return null
        return when (kind) {
            MetricKind.SPEED -> SpeedBand.of(v, activityOf(segment)).hex
            MetricKind.HEART_RATE -> HeartRateColor.band(v.roundToInt()).colorHex
        }
    }

    /** 거리 [d]가 놓인 구간 번호: [d] 이하인 이음매의 수. */
    private fun segmentAt(d: Double): Int {
        var lo = 0
        var hi = boundariesM.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (boundariesM[mid] <= d) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 구간 [segment]의 종류. 종류를 모르면 자전거. */
    private fun activityOf(segment: Int): ActivityType =
        if (segmentActivities.isEmpty()) ActivityType.BIKE else segmentActivities[segment.coerceIn(0, segmentActivities.lastIndex)]

    companion object {
        /** 그래프에 남기는 최대 점 수. */
        const val MAX_POINTS = 300

        /** 이보다 빠른 속도 값(GPS 튐)은 다듬기 전에 null로 버린다. */
        const val MAX_SPEED_KMH = 100.0

        /**
         * 속도 계열(km/h). 점의 값은 `speedMps`, 없으면 같은 구간 바로 앞 점과의 구간 속도다.
         * 시각 차가 0 이하면 구간 속도도 null이다. 값 있는 점이 2개 미만이거나 그릴 선분이 없으면 null.
         * [segments]는 [axis]를 만든 것과 같은 목록이어야 한다.
         *
         * 값은 늘 km/h다. [segmentActivities]는 축의 구간(빈 구간 제외)마다의 종류이고([TrackAxis.paths]와 같은 길이),
         * 점의 색 구간([colorHexAt])이 자기 구간의 종류로 정해진다. 기본은 모두 자전거다.
         */
        fun speed(
            axis: TrackAxis,
            segments: List<List<TrackPoint>>,
            segmentActivities: List<ActivityType> = List(axis.paths.size) { ActivityType.BIKE },
        ): MetricSeries? {
            require(segmentActivities.size == axis.paths.size) { "segmentActivities must have one type per segment of the axis" }
            val raw = ArrayList<Double?>(axis.points.size)
            for (segment in segments) {
                for (i in segment.indices) {
                    val mps = segment[i].speedMps?.toDouble() ?: pairSpeedMps(segment, i)
                    raw.add(mps?.let { it * 3.6 }?.takeIf { it.isFinite() && it <= MAX_SPEED_KMH })
                }
            }
            return build(MetricKind.SPEED, axis, raw, SeriesSmoother.SPEED_WINDOW_MS, segmentActivities)
        }

        /**
         * 심박 계열(bpm). 점마다 시각이 가장 가까운 표본을 ±[HeartRateColor.MATCH_MS] 안에서 붙이고, 없으면 null 점이다.
         *
         * 표본 밀도는 구간마다 따진다. [detailed]는 [segments]와 같은 순서·길이이고, 구간의 심박이 자세한지
         * ([com.cowork.bikerecoder.core.post.HeartRateSummary.of]의 `detailed`: 이동 시간 1분에 표본 1개 이상 — 앱은
         * 구간마다 저장한 값을 넘긴다)다. 자세하지 않은 구간(예: 10분에 하나뿐인 평소 심박)은 표본이 있어도 모든 점이 null이다.
         * 표본 하나가 앞뒤 30초의 점들에 붙어 짧은 주행에서도 토막 선이 남기 때문이다. [심박] 지도 색과 같은 기준이다.
         *
         * 붙은 점이 2개 미만이거나 그릴 선분이 없으면(예: 자세한 구간이 없음) null.
         * [segments]는 [axis]를 만든 것과 같은 목록이어야 한다.
         */
        fun heartRate(
            axis: TrackAxis,
            segments: List<List<TrackPoint>>,
            samples: List<HeartRateSample>,
            detailed: List<Boolean>,
        ): MetricSeries? {
            require(detailed.size == segments.size) { "detailed must have one flag per segment" }
            val sorted = samples.sortedBy { it.timeMillis }
            val raw = ArrayList<Double?>(axis.points.size)
            for ((s, segment) in segments.withIndex()) {
                if (!detailed[s]) {
                    repeat(segment.size) { raw.add(null) }
                    continue
                }
                for (tp in segment) raw.add(HeartRateColor.matchedBpm(sorted, tp.timeMillis)?.toDouble())
            }
            return build(MetricKind.HEART_RATE, axis, raw, SeriesSmoother.HEART_WINDOW_MS)
        }

        private fun build(
            kind: MetricKind,
            axis: TrackAxis,
            raw: List<Double?>,
            windowMs: Long,
            segmentActivities: List<ActivityType> = emptyList(),
        ): MetricSeries? {
            require(raw.size == axis.points.size) { "segments do not match the axis" }
            if (raw.count { it != null } < 2) return null
            val smoothed = SeriesSmoother.smooth(axis.timesMs, axis.segmentOf, raw, windowMs)
            val (d, v) = downsample(axis.distancesM, smoothed, MAX_POINTS)
            if (!hasDrawableStep(v)) return null
            return MetricSeries(kind, d, v, axis.totalM, axis.boundariesM, segmentActivities)
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
