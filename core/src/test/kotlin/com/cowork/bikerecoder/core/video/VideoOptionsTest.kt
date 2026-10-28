package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoOptionsTest {

    @Test
    fun defaults() {
        val o = VideoOptions()
        assertEquals(Aspect.PORTRAIT, o.aspect)
        assertEquals(VideoLength.NORMAL, o.length)
        assertEquals(true, o.photos)
        assertEquals(true, o.hud)
        assertEquals(RouteColoring.DEFAULT, o.coloring)
        assertNull(o.musicUri)
        assertEquals(24, FPS)
    }

    @Test
    fun aspectSizes() {
        assertEquals(1080 to 1920, Aspect.PORTRAIT.width to Aspect.PORTRAIT.height)
        assertEquals(1920 to 1080, Aspect.LANDSCAPE.width to Aspect.LANDSCAPE.height)
    }

    @Test
    fun lengthsAndPhotoCaps() {
        assertEquals(listOf(30 to 4, 60 to 8, 120 to 15), VideoLength.entries.map { it.seconds to it.photoCap })
    }
}
