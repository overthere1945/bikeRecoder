package com.cowork.bikerecoder.core.series

/**
 * 그래프용 다듬기. 먼저 [MEDIAN_POINTS]점 중앙값으로 한 점짜리 튐을 지우고, 그 결과를 시간 창으로 평균 낸다.
 *
 * - 이웃 점 사이 시각이 [GAP_MS]를 넘거나 거꾸로 가는 곳, 그리고 구간 경계에서 두 단계 모두 창을 끊는다.
 * - null 점은 결과에서도 null이고, 어느 창에도 넣지 않는다.
 * - 점 수 n에 대해 O(n)이다.
 */
object SeriesSmoother {

    /** 중앙값을 내는 점 수(자기 점과 앞뒤 2점). */
    const val MEDIAN_POINTS = 5

    /** 이보다 긴 시각 간격(일시정지·GPS 끊김)에서는 창을 끊는다. */
    const val GAP_MS = 10_000L

    /** 속도 평균 창의 전체 폭(ms). 점마다 앞뒤 절반씩 본다. */
    const val SPEED_WINDOW_MS = 5_000L

    /** 심박 평균 창의 전체 폭(ms). 점마다 앞뒤 절반씩 본다. */
    const val HEART_WINDOW_MS = 10_000L

    /**
     * [values]를 다듬은 목록. 길이와 null 자리는 [values]와 같다.
     * 평균은 같은 묶음(구간 안, 끊김 없음)에서 시각이 점 시각 ± [windowMs]/2 안인 점들의 것이다.
     * [timesMs]·[segmentOf]는 [values]와 같은 길이여야 한다.
     */
    fun smooth(timesMs: LongArray, segmentOf: IntArray, values: List<Double?>, windowMs: Long): List<Double?> {
        val n = values.size
        require(timesMs.size == n && segmentOf.size == n) { "timesMs, segmentOf and values must have the same size" }
        require(windowMs >= 0) { "windowMs must not be negative" }
        if (n == 0) return emptyList()

        // 점마다 자기가 속한 묶음의 [시작, 끝] 번호.
        val runStart = IntArray(n)
        val runEnd = IntArray(n)
        var start = 0
        for (i in 1..n) {
            val breaks = i == n || segmentOf[i] != segmentOf[i - 1] ||
                timesMs[i] - timesMs[i - 1] > GAP_MS || timesMs[i] < timesMs[i - 1]
            if (breaks) {
                for (k in start until i) {
                    runStart[k] = start
                    runEnd[k] = i - 1
                }
                start = i
            }
        }

        // 1단계: 같은 묶음 안 앞뒤 2점까지, null이 아닌 값들의 중앙값.
        val half = MEDIAN_POINTS / 2
        val has = BooleanArray(n) { values[it] != null }
        val median = DoubleArray(n)
        val buf = DoubleArray(MEDIAN_POINTS)
        for (i in 0 until n) {
            if (!has[i]) continue
            var k = 0
            for (j in maxOf(i - half, runStart[i])..minOf(i + half, runEnd[i])) {
                val v = values[j] ?: continue
                buf[k++] = v
            }
            buf.sort(0, k)
            median[i] = if (k % 2 == 1) buf[k / 2] else (buf[k / 2 - 1] + buf[k / 2]) / 2
        }

        // 2단계: 묶음마다 두 포인터와 누적 합으로 시간 창 평균.
        val reach = windowMs / 2
        val out = arrayOfNulls<Double>(n)
        var i = 0
        while (i < n) {
            val s = runStart[i]
            val e = runEnd[i]
            var lo = s
            var hi = s // 창에 넣은 다음 점 번호(포함하지 않음)
            var sum = 0.0
            var count = 0
            for (c in s..e) {
                while (hi <= e && timesMs[hi] - timesMs[c] <= reach) {
                    if (has[hi]) { sum += median[hi]; count++ }
                    hi++
                }
                while (timesMs[c] - timesMs[lo] > reach) {
                    if (has[lo]) { sum -= median[lo]; count-- }
                    lo++
                }
                if (has[c]) out[c] = sum / count
            }
            i = e + 1
        }
        return out.asList()
    }
}
