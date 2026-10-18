package com.cowork.bikerecoder.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoMathTest {

    @Test
    fun `seoul city hall to gangnam station is about 8_9km`() {
        val d = GeoMath.distanceM(GeoPoint(37.5663, 126.9779), GeoPoint(37.4979, 127.0276))
        assertEquals(8_778.0, d, 20.0)
    }

    @Test
    fun `project clamps to segment ends`() {
        val a = GeoPoint(37.5, 127.0)
        val b = GeoPoint(37.5, 127.01)
        assertEquals(0.0, GeoMath.project(GeoPoint(37.5, 126.99), a, b).fraction, 1e-9)
        assertEquals(1.0, GeoMath.project(GeoPoint(37.5, 127.02), a, b).fraction, 1e-9)
    }

    @Test
    fun `project perpendicular distance`() {
        val p = GeoMath.project(GeoPoint(37.5004, 127.005), GeoPoint(37.5, 127.0), GeoPoint(37.5, 127.01))
        assertEquals(44.5, p.distanceM, 1.0)
        assertEquals(0.5, p.fraction, 0.01)
    }

    @Test
    fun `cumulative distances start at zero and are monotonic`() {
        val c = GeoMath.cumulativeDistances(
            listOf(GeoPoint(37.5, 127.0), GeoPoint(37.5, 127.01), GeoPoint(37.51, 127.01))
        )
        assertEquals(0.0, c[0])
        assertTrue(c[1] < c[2])
        assertEquals(3, c.size)
    }
}
