package com.cowork.bikerecoder.map

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BikeMapGeoJsonTest {

    private val empty = """{"type":"FeatureCollection","features":[]}"""

    @Test
    fun `track geojson`() {
        assertEquals(empty, trackGeoJson(emptyList()))
        assertEquals(empty, trackGeoJson(listOf(GeoPoint(37.5, 127.0))))

        assertEquals(
            """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[127.0,37.5],[127.01,37.51]]}}""",
            trackGeoJson(listOf(GeoPoint(37.5, 127.0), GeoPoint(37.51, 127.01))),
        )
    }

    @Test
    fun `coloured track geojson joins runs of one colour and shares the point where the colour changes`() {
        val a = GeoPoint(37.5, 127.0)
        val b = GeoPoint(37.51, 127.0)
        val c = GeoPoint(37.52, 127.0)
        val d = GeoPoint(37.53, 127.0)
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"color":"#1E88E5"},"geometry":{"type":"LineString","coordinates":[[127.0,37.5],[127.0,37.51],[127.0,37.52]]}},""" +
                """{"type":"Feature","properties":{"color":"#E53935"},"geometry":{"type":"LineString","coordinates":[[127.0,37.52],[127.0,37.53]]}}""" +
                "]}",
            trackGeoJson(listOf(a, b, c, d), listOf("#1E88E5", "#1E88E5", "#E53935", "#9E9E9E")),
        )
        // One colour: one feature.
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"color":"#43A047"},"geometry":{"type":"LineString","coordinates":[[127.0,37.5],[127.0,37.51]]}}""" +
                "]}",
            trackGeoJson(listOf(a, b), listOf("#43A047", "#43A047")),
        )
        // Colours that do not match the track: the track's own colour.
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"color":"#E53935"},"geometry":{"type":"LineString","coordinates":[[127.0,37.5],[127.0,37.51]]}}""" +
                "]}",
            trackGeoJson(listOf(a, b), listOf("#43A047")),
        )
        assertEquals(empty, trackGeoJson(listOf(a), listOf("#43A047")))
    }

    @Test
    fun `separate lines are separate features with no connector`() {
        val a = GeoPoint(37.5, 127.0)
        val b = GeoPoint(37.51, 127.0)
        val c = GeoPoint(38.0, 127.0)
        val d = GeoPoint(38.01, 127.0)
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"color":"#E53935"},"geometry":{"type":"LineString","coordinates":[[127.0,37.5],[127.0,37.51]]}},""" +
                """{"type":"Feature","properties":{"color":"#FDD835"},"geometry":{"type":"LineString","coordinates":[[127.0,38.0],[127.0,38.01]]}}""" +
                "]}",
            trackGeoJson(emptyList(), lines = listOf(MapLine(listOf(a, b)), MapLine(listOf(c, d), listOf("#FDD835", "#FDD835")))),
        )
        assertEquals(empty, trackGeoJson(emptyList(), lines = listOf(MapLine(listOf(a)))))
    }

    @Test
    fun `rides are one feature each, oldest first, with their colour and style`() {
        val a = GeoPoint(37.5, 127.0)
        val b = GeoPoint(37.51, 127.0)
        val c = GeoPoint(38.0, 127.0)
        val d = GeoPoint(38.01, 127.0)
        // Newest first, as the cumulative map lists them; a ride of one point draws nothing.
        val rides = listOf(
            RideLine(5, listOf(a, b), "#E53935"),
            RideLine(3, listOf(c, d), "#1E88E5"),
            RideLine(2, listOf(c), "#43A047"),
        )
        fun feature(id: Long, color: String, selected: Boolean, width: String, opacity: String, coords: String) =
            """{"type":"Feature","properties":{"segmentId":$id,"color":"$color","selected":$selected,""" +
                """"width":$width,"opacity":$opacity},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
        val ab = "[127.0,37.5],[127.0,37.51]"
        val cd = "[127.0,38.0],[127.0,38.01]"

        // Nothing selected: every ride width 4, opacity 0.8; the newest drawn last (on top).
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                feature(3, "#1E88E5", false, "4.0", "0.8", cd) + "," + feature(5, "#E53935", false, "4.0", "0.8", ab) +
                "]}",
            ridesGeoJson(rides, selected = null),
        )
        // One selected: it is width 6, opacity 1 and drawn last; the others fade to 0.25.
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                feature(5, "#E53935", false, "4.0", "0.25", ab) + "," + feature(3, "#1E88E5", true, "6.0", "1.0", cd) +
                "]}",
            ridesGeoJson(rides, selected = 3),
        )
        assertEquals(empty, ridesGeoJson(emptyList(), selected = null))
        assertEquals(empty, ridesGeoJson(listOf(RideLine(2, listOf(c), "#43A047")), selected = 2))
    }

    @Test
    fun `photos geojson`() {
        assertEquals(empty, photosGeoJson(emptyList()))

        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"photoId":7,"image":"photo-7"},"geometry":{"type":"Point","coordinates":[127.0,37.5]}},""" +
                """{"type":"Feature","properties":{"photoId":9,"image":"photo-9"},"geometry":{"type":"Point","coordinates":[127.01,37.51]}}""" +
                "]}",
            photosGeoJson(
                listOf(
                    MapPhoto(7, GeoPoint(37.5, 127.0), thumbnail = null),
                    MapPhoto(9, GeoPoint(37.51, 127.01), thumbnail = null),
                ),
            ),
        )
    }
}
