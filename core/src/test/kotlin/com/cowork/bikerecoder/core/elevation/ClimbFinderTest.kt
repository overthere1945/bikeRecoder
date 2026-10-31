package com.cowork.bikerecoder.core.elevation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClimbFinderTest {

    /** 표본 (거리, 경사 %). 고도는 경사를 따라 누적한다(이웃 경사의 평균). */
    private fun find(samples: List<Pair<Double, Double>>): List<Climb> {
        val d = samples.map { it.first }.toDoubleArray()
        val g = samples.map { it.second }.toDoubleArray()
        val e = DoubleArray(d.size)
        for (i in 1 until d.size) e[i] = e[i - 1] + (g[i - 1] + g[i]) / 2 * (d[i] - d[i - 1]) / 100.0
        return ClimbFinder.findIn(d, e, g)
    }

    /** [from]부터 [to]까지(둘 다 포함) [step] 간격 표본, 경사 [grade]. */
    private fun run(from: Double, to: Double, grade: Double, step: Double = 10.0): List<Pair<Double, Double>> {
        val n = Math.round((to - from) / step).toInt()
        return (0..n).map { (from + it * step) to grade }
    }

    @Test
    fun steadyClimbFound() {
        val p = elevationProfile(2_000.0) { d ->
            when {
                d < 500.0 -> 0.0
                d < 1_500.0 -> (d - 500.0) * 0.05
                else -> 50.0
            }
        }
        val climbs = ClimbFinder.find(p)
        assertEquals(1, climbs.size, "$climbs")
        val c = climbs.single()
        assertTrue(c.startM in 480.0..560.0, "start=${c.startM}")
        assertTrue(c.endM in 1_440.0..1_520.0, "end=${c.endM}")
        assertEquals(c.endM - c.startM, c.lengthM, 1e-9)
        assertEquals(p.elevationAt(c.endM) - p.elevationAt(c.startM), c.gainM, 1e-6)
        assertEquals(5.0, c.avgGradePct, 0.5)
        assertEquals(5.0, c.maxGradePct, 0.2)
    }

    @Test
    fun dipShorterThanFiftyJoins() {
        // 마지막 오르막 표본 300, 끼는 표본 349(칸 324.5~373.5 = 49 m), 다음 오르막 표본 398부터.
        val climbs = find(run(0.0, 300.0, 5.0) + listOf(349.0 to 0.0) + run(398.0, 698.0, 5.0))
        assertEquals(1, climbs.size, "$climbs")
        assertEquals(0.0, climbs.single().startM, 1e-9)
        assertEquals(698.0, climbs.single().endM, 1e-9)
    }

    @Test
    fun dipOfFiftySplits() {
        // 끼는 표본 350(칸 325~375 = 50 m).
        val climbs = find(run(0.0, 300.0, 5.0) + listOf(350.0 to 0.0) + run(400.0, 700.0, 5.0))
        assertEquals(2, climbs.size, "$climbs")
        assertEquals(0.0, climbs[0].startM, 1e-9)
        assertEquals(325.0, climbs[0].endM, 1e-9)
        assertEquals(375.0, climbs[1].startM, 1e-9)
        assertEquals(700.0, climbs[1].endM, 1e-9)
    }

    @Test
    fun length199IsNotClimb() {
        // 마지막 칸은 9 m: 0..190 간격 10, 끝 199.
        assertEquals(emptyList(), find(run(0.0, 190.0, 5.0) + listOf(199.0 to 5.0)))
    }

    @Test
    fun length200IsClimb() {
        val c = find(run(0.0, 200.0, 5.0)).single()
        assertEquals(200.0, c.lengthM, 1e-9)
        assertEquals(10.0, c.gainM, 1e-9)
        assertEquals(5.0, c.avgGradePct, 1e-9)
    }

    @Test
    fun avg3point9IsNotClimb() {
        assertEquals(emptyList(), find(run(0.0, 300.0, 3.9)))
    }

    @Test
    fun avg4IsClimb() {
        val c = find(run(0.0, 300.0, 4.0)).single()
        assertEquals(4.0, c.avgGradePct, 1e-9)
    }

    @Test
    fun maxGradeReported() {
        val samples = run(0.0, 300.0, 5.0).map { (d, g) -> d to if (d == 150.0) 9.0 else g }
        assertEquals(9.0, find(samples).single().maxGradePct, 1e-9)
    }

    @Test
    fun descentsIgnored() {
        val p = elevationProfile(3_000.0) { d ->
            when {
                d < 1_000.0 -> 100.0 - d * 0.06
                d < 1_500.0 -> 40.0
                d < 2_300.0 -> 40.0 + (d - 1_500.0) * 0.05
                else -> 80.0
            }
        }
        val climbs = ClimbFinder.find(p)
        assertEquals(1, climbs.size, "$climbs")
        assertTrue(climbs.single().startM > 1_400.0, "${climbs.single()}")
    }
}
