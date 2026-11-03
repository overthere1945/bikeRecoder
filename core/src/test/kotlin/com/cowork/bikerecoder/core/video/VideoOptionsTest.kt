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
        assertEquals(BuiltInMusic.SUNNY_PEDAL, o.builtInMusic)
        assertEquals(30, FPS)
        assertEquals(8, framesOf(0.25))
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

    @Test
    fun builtInTracks() {
        assertEquals(
            listOf(Triple("sunny_pedal", "햇살 페달", "music_sunny_pedal"), Triple("wind_road", "바람길", "music_wind_road"), Triple("over_the_hill", "언덕 너머", "music_over_the_hill")),
            BuiltInMusic.entries.map { Triple(it.key, it.title, it.rawName) },
        )
    }

    @Test
    fun musicSourcePrefersPickedFile() {
        assertEquals("android.resource://pkg/raw/music_sunny_pedal", VideoOptions().musicSource("pkg"))
        assertEquals("android.resource://pkg/raw/music_wind_road", VideoOptions(builtInMusic = BuiltInMusic.WIND_ROAD).musicSource("pkg"))
        assertEquals("content://music/1", VideoOptions(musicUri = "content://music/1").musicSource("pkg"))
        assertEquals("content://music/1", VideoOptions(musicUri = "content://music/1", builtInMusic = BuiltInMusic.OVER_THE_HILL).musicSource("pkg"))
        assertNull(VideoOptions(builtInMusic = null).musicSource("pkg"))
    }
}
