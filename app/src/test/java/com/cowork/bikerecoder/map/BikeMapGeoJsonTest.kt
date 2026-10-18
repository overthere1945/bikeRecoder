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
