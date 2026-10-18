package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.Stop
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArrivalDetectorTest {

    @Test
    fun `waypoint reached within 30m`() {
        val stop = Stop(id = 1, name = "경유지", point = pointAt(1_000.0, 0.0), isDestination = false)
        val detector = ArrivalDetector(stops = listOf(stop), stopDistancesAlongM = listOf(1_000.0))

        val reached = detector.onFix(pointAt(1_000.0, 20.0), remainingM = 500.0)

        assertEquals(stop, reached)
        assertEquals(1, detector.nextIndex)
    }

    @Test
    fun `waypoint passed within 50m counts as reached`() {
        val stop = Stop(id = 1, name = "경유지", point = pointAt(1_000.0, 0.0), isDestination = false)
        val detector = ArrivalDetector(stops = listOf(stop), stopDistancesAlongM = listOf(1_000.0))

        // 최소 접근 거리는 약 40m (30m 이내로는 들어가지 않음)
        assertNull(detector.onFix(pointAt(900.0, 40.0), remainingM = 600.0))  // ~107.7m, 50m 밖
        assertNull(detector.onFix(pointAt(950.0, 40.0), remainingM = 580.0)) // ~64.0m, 50m 밖
        assertNull(detector.onFix(pointAt(980.0, 40.0), remainingM = 560.0)) // ~44.7m, 50m 이내 최초 진입
        assertNull(detector.onFix(pointAt(1_000.0, 40.0), remainingM = 540.0)) // 40.0m, 더 가까워짐
        val reached = detector.onFix(pointAt(1_020.0, 40.0), remainingM = 520.0) // ~44.7m, 멀어지기 시작
        assertEquals(stop, reached)
        assertEquals(1, detector.nextIndex)
    }

    @Test
    fun `stops reached in order`() {
        val first = Stop(id = 1, name = "첫번째", point = pointAt(1_000.0, 0.0), isDestination = false)
        val second = Stop(id = 2, name = "두번째", point = pointAt(2_000.0, 0.0), isDestination = false)
        val detector = ArrivalDetector(
            stops = listOf(first, second),
            stopDistancesAlongM = listOf(1_000.0, 2_000.0),
        )

        // 두 번째 경유지 바로 옆을 지나지만 첫 번째가 아직 미도달 상태
        val result = detector.onFix(pointAt(2_000.0, 5.0), remainingM = 100.0)

        assertNull(result)
        assertEquals(0, detector.nextIndex)
    }

    @Test
    fun `loop route does not arrive at start`() {
        val route = loopRoute()
        val start = route.points.first()
        val destination = Stop(id = 1, name = "목적지", point = start, isDestination = true)
        val detector = ArrivalDetector(
            stops = listOf(destination),
            stopDistancesAlongM = listOf(route.cumulativeM.last()),
        )

        val result = detector.onFix(start, remainingM = 9_800.0)

        assertNull(result)
        assertEquals(0, detector.nextIndex)
    }
}
