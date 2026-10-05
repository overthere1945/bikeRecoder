package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Stop

/**
 * 경유지/목적지 도달을 판정한다.
 *
 * 다음 Stop과의 거리가 `arriveRadiusM` 이내면 도달이다.
 * 또는 한 번이라도 `passRadiusM` 이내로 접근했다가, 바로 전 측위보다 거리가 멀어지기 시작하면
 * 그 시점에 도달로 처리한다("지나침").
 * 목적지는 추가로 `remainingM <= destinationMinProgress`일 때만 도달로 인정한다(순환 경로 보호).
 */
class ArrivalDetector(
    private val stops: List<Stop>,
    private val stopDistancesAlongM: List<Double>,
    firstUnvisited: Int = 0,
    private val arriveRadiusM: Double = 30.0,
    private val passRadiusM: Double = 50.0,
    private val destinationMinProgress: Double = 200.0,
) {

    init {
        require(stopDistancesAlongM.size == stops.size) {
            "stopDistancesAlongM must have the same size as stops"
        }
    }

    var nextIndex: Int = firstUnvisited
        private set

    private var hasBeenWithinPassRadius: Boolean = false
    private var previousDistanceM: Double? = null

    fun onFix(p: GeoPoint, remainingM: Double): Stop? {
        if (nextIndex >= stops.size) return null

        val stop = stops[nextIndex]
        val distanceM = GeoMath.distanceM(p, stop.point)
        val previous = previousDistanceM

        var reached = distanceM <= arriveRadiusM
        if (!reached && hasBeenWithinPassRadius && previous != null && distanceM > previous) {
            reached = true
        }

        if (distanceM <= passRadiusM) {
            hasBeenWithinPassRadius = true
        }
        previousDistanceM = distanceM

        if (reached && stop.isDestination && remainingM > destinationMinProgress) {
            reached = false
        }

        if (!reached) return null

        nextIndex += 1
        hasBeenWithinPassRadius = false
        previousDistanceM = null
        return stop
    }
}
