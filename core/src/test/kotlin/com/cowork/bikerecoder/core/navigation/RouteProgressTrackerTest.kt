package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.Instruction
import com.cowork.bikerecoder.core.model.TurnType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProgressTrackerTest {

    @Test
    fun `remaining distance within 1 percent on straight route`() {
        val route = straightRoute(1_000.0)
        val tracker = RouteProgressTracker(route)

        val progress = tracker.update(pointAt(500.0, 0.0))

        assertEquals(500.0, progress.remainingM, 5.0)
    }

    @Test
    fun `out and back route does not jump to return leg`() {
        val route = outAndBackRoute(1_000.0)
        val tracker = RouteProgressTracker(route)

        tracker.update(pointAt(0.0, 0.0)) // 출발점에서 첫 업데이트 (전체 검사로 기준 확립)
        val progress = tracker.update(pointAt(100.0, 0.0))

        assertEquals(100.0, progress.distanceAlongM, 10.0)
    }

    @Test
    fun `next instruction is first ahead`() {
        val instructions = listOf(
            Instruction(pointIndex = 0, type = TurnType.STRAIGHT, roundaboutExit = 0, distanceFromStartM = 300.0),
            Instruction(pointIndex = 0, type = TurnType.LEFT, roundaboutExit = 0, distanceFromStartM = 700.0),
        )
        val route = straightRoute(1_000.0, instructions)
        val tracker = RouteProgressTracker(route)

        val progress = tracker.update(pointAt(400.0, 0.0))

        val next = progress.nextInstruction
        assertTrue(next != null)
        assertEquals(700.0, next.distanceFromStartM)
        val distanceToNext = progress.distanceToNextInstructionM
        assertTrue(distanceToNext != null)
        assertEquals(300.0, distanceToNext, 1.0)
    }

    @Test
    fun `lateral offset reported when beside route`() {
        val route = straightRoute(1_000.0)
        val tracker = RouteProgressTracker(route)

        val progress = tracker.update(pointAt(500.0, 60.0))

        assertEquals(60.0, progress.lateralOffsetM, 2.0)
    }
}
