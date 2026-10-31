package com.cowork.bikerecoder.core.export

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KmlWriterTest {

    private fun tp(sec: Int, lat: Double, lon: Double, alt: Double? = 5.55) =
        TrackPoint(timeMillis = sec * 1_000L, point = GeoPoint(lat, lon), altitudeM = alt, speedMps = 5f, accuracyM = 5f)

    private fun assertWellFormed(xml: String) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private val day1 = ExportSegment(0, LocalDate.of(2026, 10, 6), listOf(tp(0, 37.1, 127.1), tp(1, 37.2, 127.2)), emptyList())
    private val day2 = ExportSegment(100_000, LocalDate.of(2026, 10, 7), listOf(tp(100, 37.3, 127.3), tp(101, 37.4, 127.4)), emptyList())

    @Test
    fun tripLinePerSegmentWithStartEnd() {
        val xml = KmlWriter.trip("이틀 & 여행", listOf(day1, day2))
        assertWellFormed(xml)
        assertTrue(xml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>이틀 &amp; 여행</name>"))
        assertTrue(xml.contains("<name>2026-10-06 주행</name>"))
        assertTrue(xml.contains("<name>2026-10-07 주행</name>"))
        assertEquals(2, Regex("<LineString>").findAll(xml).count())
        assertEquals(2, Regex("<altitudeMode>clampToGround</altitudeMode>").findAll(xml).count())
        assertTrue(xml.contains("<color>ffff0000</color><width>4</width>"))
        assertEquals(1, Regex("<name>출발</name>").findAll(xml).count())
        assertEquals(1, Regex("<name>도착</name>").findAll(xml).count())
        // 출발은 첫 구간 첫 점, 도착은 마지막 구간 마지막 점
        assertTrue(xml.contains("<name>출발</name><Point><coordinates>127.1000000,37.1000000</coordinates>"))
        assertTrue(xml.contains("<name>도착</name><Point><coordinates>127.4000000,37.4000000</coordinates>"))
    }

    @Test
    fun routeLineAndStopPoints() {
        val route = Route(
            points = listOf(GeoPoint(37.5, 127.0), GeoPoint(37.6, 127.1)),
            cumulativeM = listOf(0.0, 100.0),
            instructions = emptyList(),
            stopPointIndices = listOf(1),
            summary = RouteSummary(100.0, 0, 0.0, RouteProfile.BALANCED),
            elevationsM = listOf(10.0, 20.0),
        )
        val stops = listOf(Stop(1, "남산", GeoPoint(37.6, 127.1), true))
        val xml = KmlWriter.route(ExportLabels.routeName("남산"), route, stops)
        assertWellFormed(xml)
        assertTrue(xml.contains("<Document><name>bikeRecoder 경로 · 남산</name>"))
        assertEquals(1, Regex("<LineString>").findAll(xml).count())
        assertTrue(xml.contains("127.0000000,37.5000000,10.0 127.1000000,37.6000000,20.0"))
        assertTrue(xml.contains("<name>남산</name><Point><coordinates>127.1000000,37.6000000</coordinates></Point>"))
    }

    @Test
    fun coordinatesAreLonLatEle() {
        val xml = KmlWriter.trip("t", listOf(day1))
        assertTrue(xml.contains("<coordinates>127.1000000,37.1000000,5.6 127.2000000,37.2000000,5.6</coordinates>"), xml)
    }

    @Test
    fun missingAltitudeWritesLonLatOnly() {
        val seg = ExportSegment(0, LocalDate.of(2026, 10, 6), listOf(tp(0, 37.1, 127.1, null), tp(1, 37.2, 127.2, Double.NaN)), emptyList())
        val xml = KmlWriter.trip("t", listOf(seg))
        assertTrue(xml.contains("<coordinates>127.1000000,37.1000000 127.2000000,37.2000000</coordinates>"))
    }

    @Test
    fun emptyTripHasNoPlacemarks() {
        val xml = KmlWriter.trip("t", emptyList())
        assertWellFormed(xml)
        assertFalse(xml.contains("<Placemark>"))
    }
}
