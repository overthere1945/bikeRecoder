package com.cowork.bikerecoder.core.activity

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.recording.TrackPoint

/**
 * 달리기 최고 기록의 거리.
 * @property keyM 저장 키(m, INTEGER 칸). 하프는 21,098이다.
 * @property targetM 계산에 쓰는 실제 거리(m).
 */
enum class BestEffortDistance(val keyM: Int, val targetM: Double, val label: String) {
    ONE_K(1000, 1000.0, "1 km"),
    FIVE_K(5000, 5000.0, "5 km"),
    TEN_K(10000, 10000.0, "10 km"),
    HALF(21098, 21097.5, "하프"),
    ;

    companion object {
        fun ofKey(keyM: Int): BestEffortDistance? = entries.firstOrNull { it.keyM == keyM }
    }
}

/**
 * 거리 하나의 가장 빠른 기록.
 * @property timeMs 그 거리를 달린 시간.
 * @property startIndex 가장 빠른 구간이 시작하는 점의 위치(넘겨받은 점 목록 기준).
 */
data class BestEffort(val distance: BestEffortDistance, val timeMs: Long, val startIndex: Int)

object BestEfforts {

    /**
     * 점 목록에서 거리마다 가장 빠른 연속 구간을 찾는다. 그 거리를 채우지 못하면 결과에서 뺀다.
     *
     * 이웃 점 사이가 [gapMs]보다 길거나 시각이 늘지 않으면 끊고, 구간은 끊긴 곳을 넘지 않는다.
     * 끝 지점은 두 점 사이를 시간에 비례해 보간한다.
     */
    fun find(points: List<TrackPoint>, gapMs: Long = 10_000): List<BestEffort> {
        val n = points.size
        if (n < 2) return emptyList()

        // 이웃 점까지의 누적 거리. 끊기는 곳은 조각 시작에서 다시 0으로 둔다.
        val cum = DoubleArray(n)
        val t = LongArray(n) { points[it].timeMillis }
        val best = LongArray(BestEffortDistance.entries.size) { Long.MAX_VALUE }
        val bestStart = IntArray(best.size)

        var start = 0
        while (start < n) {
            var end = start
            cum[start] = 0.0
            while (end + 1 < n) {
                val dt = t[end + 1] - t[end]
                if (dt <= 0 || dt > gapMs) break
                cum[end + 1] = cum[end] + GeoMath.distanceM(points[end].point, points[end + 1].point)
                end++
            }
            if (end > start) scanChunk(start, end, cum, t, best, bestStart)
            start = end + 1
        }

        return BestEffortDistance.entries.mapIndexedNotNull { k, d ->
            if (best[k] == Long.MAX_VALUE) null else BestEffort(d, best[k], bestStart[k])
        }
    }

    /** 조각 `[s, e]`에서 거리마다 두 포인터로 가장 빠른 구간을 찾는다. */
    private fun scanChunk(s: Int, e: Int, cum: DoubleArray, t: LongArray, best: LongArray, bestStart: IntArray) {
        BestEffortDistance.entries.forEachIndexed { k, d ->
            val target = d.targetM
            if (cum[e] - cum[s] < target) return@forEachIndexed
            var j = s
            for (i in s..e) {
                while (j <= e && cum[j] - cum[i] < target) j++
                if (j > e) break
                val seg = cum[j] - cum[j - 1]
                val frac = (target - (cum[j - 1] - cum[i])) / seg
                val endTime = t[j - 1] + (t[j] - t[j - 1]) * frac
                val time = Math.round(endTime - t[i])
                if (time < best[k]) {
                    best[k] = time
                    bestStart[k] = i
                }
            }
        }
    }
}
