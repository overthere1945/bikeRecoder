package com.cowork.bikerecoder.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** File names of the exports (spec §4.2): the time in Asia/Seoul, whatever the JVM's zone. */
class ExportFileNameTest {

    @Test
    fun tripNameUsesSeoulStart() {
        // 2026-10-30 22:05 UTC = 2026-10-31 07:05 Seoul: the date and the hour both follow Seoul.
        val start = LocalDateTime.of(2026, 10, 30, 22, 5, 59).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertEquals("bikeRecoder_20261031_0705.gpx", exportFileName(TripExporter.TRIP_PREFIX, start, ExportFormat.GPX))
        assertEquals("bikeRecoder_20261031_0705.kml", exportFileName(TripExporter.TRIP_PREFIX, start, ExportFormat.KML))
    }

    @Test
    fun routeNamePrefix() {
        val now = LocalDateTime.of(2026, 11, 1, 3, 30).toInstant(ZoneOffset.UTC).toEpochMilli() // 12:30 Seoul

        assertEquals("bikeRecoder_route_20261101_1230.gpx", exportFileName(TripExporter.ROUTE_PREFIX, now, ExportFormat.GPX))
        assertEquals("bikeRecoder_route_20261101_1230.kml", exportFileName(TripExporter.ROUTE_PREFIX, now, ExportFormat.KML))
    }

    @Test
    fun formatsCarryTheirMime() {
        assertEquals("application/gpx+xml", ExportFormat.GPX.mime)
        assertEquals("application/vnd.google-earth.kml+xml", ExportFormat.KML.mime)
        assertEquals("gpx", ExportFormat.GPX.extension)
        assertEquals("kml", ExportFormat.KML.extension)
    }
}
