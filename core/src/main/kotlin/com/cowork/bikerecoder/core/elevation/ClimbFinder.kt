package com.cowork.bikerecoder.core.elevation

import kotlin.math.max

/** 오르막 하나. 거리는 단면의 거리 축(m), [gainM]은 끝 고도 - 시작 고도. */
data class Climb(
    val startM: Double,
    val endM: Double,
    val gainM: Double,
    val avgGradePct: Double,
    val maxGradePct: Double,
) {
    val lengthM: Double get() = endM - startM
}

/**
 * 단면에서 오르막을 찾는다(스펙 §1.4).
 *
 * 각 표본은 [ElevationProfile.bands]처럼 이웃 표본과의 중간점까지를 맡는다(첫 표본은 0부터, 마지막은 끝까지).
 * 경사 [START_GRADE_PCT] 이상인 표본이 이어지는 칸을 묶고, 사이에 낀 3% 미만 칸의 길이가 [MAX_DIP_M] 미만이면
 * 같은 묶음으로 잇는다. 묶음 중 길이 [MIN_LENGTH_M] 이상이고 평균 경사(오른 높이 ÷ 길이) [MIN_AVG_GRADE_PCT]
 * 이상인 것만 오르막이다. 길이는 칸 경계의 실제 거리로 재므로 마지막 칸이 짧아도 맞다.
 */
object ClimbFinder {
    const val START_GRADE_PCT = 3.0
    const val MAX_DIP_M = 50.0
    const val MIN_LENGTH_M = 200.0
    const val MIN_AVG_GRADE_PCT = 4.0

    private const val EPS = 1e-9

    fun find(profile: ElevationProfile): List<Climb> =
        findIn(profile.distancesM, profile.elevationsM, profile.gradesPct)

    /** [find]의 본체. 세 배열은 같은 길이, 거리는 오름차순. */
    internal fun findIn(distancesM: DoubleArray, elevationsM: DoubleArray, gradesPct: DoubleArray): List<Climb> {
        val n = distancesM.size
        if (n < 2) return emptyList()

        fun cellStart(i: Int) = if (i == 0) distancesM[0] else (distancesM[i - 1] + distancesM[i]) / 2
        fun cellEnd(i: Int) = if (i == n - 1) distancesM[i] else (distancesM[i] + distancesM[i + 1]) / 2

        // 3% 이상 표본이 이어지는 칸 묶음 [첫 표본, 끝 표본].
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (gradesPct[i] >= START_GRADE_PCT) {
                val first = i
                while (i + 1 < n && gradesPct[i + 1] >= START_GRADE_PCT) i++
                runs.add(intArrayOf(first, i))
            }
            i++
        }

        // 사이 3% 미만 칸이 50 m 미만이면 잇는다.
        val groups = ArrayList<IntArray>()
        for (run in runs) {
            val last = groups.lastOrNull()
            if (last != null && cellStart(run[0]) - cellEnd(last[1]) < MAX_DIP_M - EPS) {
                last[1] = run[1]
            } else {
                groups.add(run.copyOf())
            }
        }

        val climbs = ArrayList<Climb>()
        for ((first, last) in groups.map { it[0] to it[1] }) {
            val start = cellStart(first)
            val end = cellEnd(last)
            val length = end - start
            if (length < MIN_LENGTH_M - EPS) continue
            val gain = interpolate(distancesM, elevationsM, end) - interpolate(distancesM, elevationsM, start)
            val avg = gain / length * 100.0
            if (avg < MIN_AVG_GRADE_PCT - EPS) continue
            var maxGrade = gradesPct[first]
            for (k in first..last) maxGrade = max(maxGrade, gradesPct[k])
            climbs.add(Climb(start, end, gain, avg, maxGrade))
        }
        return climbs
    }

    /** 선형 보간. 범위 밖은 끝 값. */
    private fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val f = (x - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + f * (ys[hi] - ys[lo])
    }
}
