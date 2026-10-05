package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Instruction
import com.cowork.bikerecoder.core.model.Route

/** 한 번의 위치 매칭 결과. */
data class Progress(
    val distanceAlongM: Double,
    val lateralOffsetM: Double,
    val remainingM: Double,
    val nextInstruction: Instruction?,
    val distanceToNextInstructionM: Double?,
)

/**
 * 경로 위에서 현재 위치를 매칭해 진행 거리/잔여 거리/다음 안내를 계산한다.
 *
 * 직전 매칭 지점부터 `distanceAlong + lookAheadM` 이내의 선분만 검사한다(첫 update는 전체 검사).
 * `distanceAlongM`은 단조 비감소(뒤로 가지 않는다).
 */
class RouteProgressTracker(private val route: Route, private val lookAheadM: Double = 500.0) {

    init {
        require(route.points.size >= 2) { "Route must have at least 2 points" }
    }

    private var lastDistanceAlongM: Double = 0.0
    private var hasMatched: Boolean = false

    fun update(p: GeoPoint): Progress {
        val points = route.points
        val cumulative = route.cumulativeM

        val startIdx: Int
        val maxAlongM: Double
        if (!hasMatched) {
            startIdx = 0
            maxAlongM = Double.MAX_VALUE
        } else {
            startIdx = segmentIndexContaining(lastDistanceAlongM)
            maxAlongM = lastDistanceAlongM + lookAheadM
        }

        var bestDistanceM = Double.MAX_VALUE
        var bestAlongM = lastDistanceAlongM
        var matched = false

        for (i in startIdx until points.size - 1) {
            if (cumulative[i] > maxAlongM) break
            val a = points[i]
            val b = points[i + 1]
            val projection = GeoMath.project(p, a, b)
            if (projection.distanceM < bestDistanceM) {
                bestDistanceM = projection.distanceM
                val segmentLenM = cumulative[i + 1] - cumulative[i]
                bestAlongM = cumulative[i] + projection.fraction * segmentLenM
                matched = true
            }
        }

        val distanceAlongM = if (matched) maxOf(bestAlongM, lastDistanceAlongM) else lastDistanceAlongM
        val lateralOffsetM = if (matched) bestDistanceM else 0.0
        lastDistanceAlongM = distanceAlongM
        hasMatched = true

        val totalDistanceM = cumulative.last()
        val remainingM = (totalDistanceM - distanceAlongM).coerceAtLeast(0.0)
        val nextInstruction = route.instructions.firstOrNull { it.distanceFromStartM > distanceAlongM }
        val distanceToNextInstructionM = nextInstruction?.let { it.distanceFromStartM - distanceAlongM }

        return Progress(
            distanceAlongM = distanceAlongM,
            lateralOffsetM = lateralOffsetM,
            remainingM = remainingM,
            nextInstruction = nextInstruction,
            distanceToNextInstructionM = distanceToNextInstructionM,
        )
    }

    /** cumulativeM[i] <= distanceAlongM 인 마지막 인덱스(선분 시작점)를 찾는다. */
    private fun segmentIndexContaining(distanceAlongM: Double): Int {
        val cumulative = route.cumulativeM
        var idx = 0
        for (i in cumulative.indices) {
            if (cumulative[i] <= distanceAlongM) idx = i else break
        }
        return idx.coerceAtMost(route.points.size - 2)
    }
}
