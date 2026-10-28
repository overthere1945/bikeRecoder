package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.cos

/** The route preview's fitting of points into its box (spec §6.11). */
class RoutePreviewTest {

    private fun assertAt(x: Float, y: Float, actual: androidx.compose.ui.geometry.Offset, message: String) {
        assertEquals(x, actual.x, 0.01f, "$message x")
        assertEquals(y, actual.y, 0.01f, "$message y")
    }

    @Test
    fun fitToBoxKeepsAspectAndPadding() {
        // Twice as wide as tall (at the equator a degree of longitude is as long as one of latitude).
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 2.0), GeoPoint(0.5, 1.0))

        val fitted = fitToBox(points, w = 72f, h = 72f, pad = 6f)

        // The width fills the 60 px inside the padding; the height is half of it, centred; north is up.
        val k = cos(Math.toRadians(0.5)).toFloat() // longitude shrinks with the cosine of the mean latitude
        val height = 60f / (2f * k)
        assertAt(6f, 36f + height / 2, fitted[0], "south-west")
        assertAt(66f, 36f - height / 2, fitted[1], "north-east")
        assertAt(36f, 36f, fitted[2], "middle")
    }

    @Test
    fun fitToBoxShrinksLongitudeAwayFromTheEquator() {
        // At 60° N a degree of longitude is about half as long: 2° × 1° is about square.
        val points = listOf(GeoPoint(60.0, 10.0), GeoPoint(61.0, 12.0))

        val fitted = fitToBox(points, w = 100f, h = 50f, pad = 5f)

        val k = cos(Math.toRadians(60.5)).toFloat()
        val width = 40f * 2f * k // the height (40 px inside the padding) limits
        assertAt(50f - width / 2, 45f, fitted[0], "south-west")
        assertAt(50f + width / 2, 5f, fitted[1], "north-east")
    }

    @Test
    fun aSinglePointOrNoneIsCentredOrEmpty() {
        assertEquals(emptyList<androidx.compose.ui.geometry.Offset>(), fitToBox(emptyList(), 72f, 72f, 6f))
        val same = GeoPoint(37.5, 127.0)
        fitToBox(listOf(same, same), 72f, 72f, 6f).forEach { assertAt(36f, 36f, it, "same point") }
    }

    @Test
    fun aStraightLineIsCentredAcross() {
        // Due east: no height; the line runs across the middle.
        val fitted = fitToBox(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 72f, 72f, 6f)
        assertAt(6f, 36f, fitted[0], "west")
        assertAt(66f, 36f, fitted[1], "east")
    }
}
