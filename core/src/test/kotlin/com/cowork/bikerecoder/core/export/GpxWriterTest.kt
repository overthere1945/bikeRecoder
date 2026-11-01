package com.cowork.bikerecoder.core.export

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpxWriterTest {

    private val t0 = Instant.parse("2026-10-06T01:02:03Z").toEpochMilli()
    private val exportedAt = Instant.parse("2026-10-31T09:00:00Z").toEpochMilli()

    private fun tp(offsetMs: Long, lat: Double = 37.1234567, lon: Double = 127.7654321, alt: Double? = 12.34) =
        TrackPoint(timeMillis = t0 + offsetMs, point = GeoPoint(lat, lon), altitudeM = alt, speedMps = 5f, accuracyM = 5f)

    private fun seg(
        points: List<TrackPoint>,
        hr: List<HeartRateSample> = emptyList(),
        date: LocalDate = LocalDate.of(2026, 10, 6),
        activity: ActivityType = ActivityType.BIKE,
    ) = ExportSegment(startedAt = points.first().timeMillis, localDate = date, points = points, heartRate = hr, activity = activity)

    private fun assertWellFormed(xml: String) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    @Test
    fun headerAndNamespaces() {
        val xml = GpxWriter.trip("한강 주행", listOf(seg(listOf(tp(0)))), "bikeRecoder 1.7.0", exportedAt)
        assertWellFormed(xml)
        assertTrue(xml.contains("<gpx version=\"1.1\" creator=\"bikeRecoder 1.7.0\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">"))
        assertTrue(xml.contains("<metadata><name>한강 주행</name><time>2026-10-31T09:00:00Z</time></metadata>"))
        assertTrue(xml.contains("<trk><name>한강 주행</name><type>cycling</type>"))
    }

    @Test
    fun multiDayTripHasOneTrksegPerSegment() {
        val day1 = seg(listOf(tp(0), tp(1_000)))
        val day2 = seg(listOf(tp(86_400_000), tp(86_401_000)), date = LocalDate.of(2026, 10, 7))
        val xml = GpxWriter.trip("이틀", listOf(day1, day2), "c", exportedAt)
        assertWellFormed(xml)
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
        assertEquals(1, Regex("<trk>").findAll(xml).count())
        assertEquals(4, Regex("<trkpt ").findAll(xml).count())
    }

    /** The output of V1.8.0 for a bike-only trip of two segments, written out by hand: it must not change. */
    @Test
    fun bikeOnlyTripUnchanged() {
        val day1 = seg(listOf(tp(0), tp(1_000, alt = null)), hr = listOf(HeartRateSample(t0 + 1_000, 150)))
        val day2 = seg(listOf(tp(86_400_000, lat = 37.2)), date = LocalDate.of(2026, 10, 7))
        val xml = GpxWriter.trip("이틀 & 라이딩", listOf(day1, day2), "bikeRecoder 1.8.0", exportedAt)
        val expected = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="bikeRecoder 1.8.0" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
<metadata><name>이틀 &amp; 라이딩</name><time>2026-10-31T09:00:00Z</time></metadata>
<trk><name>이틀 &amp; 라이딩</name><type>cycling</type>
<trkseg>
<trkpt lat="37.1234567" lon="127.7654321"><ele>12.3</ele><time>2026-10-06T01:02:03Z</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>150</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
<trkpt lat="37.1234567" lon="127.7654321"><time>2026-10-06T01:02:04Z</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>150</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
</trkseg>
<trkseg>
<trkpt lat="37.2000000" lon="127.7654321"><ele>12.3</ele><time>2026-10-07T01:02:03Z</time></trkpt>
</trkseg>
</trk>
</gpx>
"""
        assertEquals(expected, xml)
    }

    @Test
    fun typeChangesStartNewTrk() {
        val bike1 = seg(listOf(tp(0), tp(1_000)))
        val bike2 = seg(listOf(tp(2_000), tp(3_000)))
        val walk = seg(listOf(tp(4_000), tp(5_000)), activity = ActivityType.WALK)
        val xml = GpxWriter.trip("섞인 여행", listOf(bike1, bike2, walk), "c", exportedAt)
        assertWellFormed(xml)
        assertEquals(2, Regex("<trk>").findAll(xml).count())
        assertEquals(3, Regex("<trkseg>").findAll(xml).count())
        assertEquals(
            listOf("cycling", "walking"),
            Regex("<type>([a-z]+)</type>").findAll(xml).map { it.groupValues[1] }.toList(),
        )
        // 같은 제목이 두 <trk>에 모두 들어간다.
        assertEquals(2, Regex("<trk><name>섞인 여행</name>").findAll(xml).count())
        // 종류가 바뀌는 곳에서 <trk>가 닫히고 열린다.
        assertTrue(xml.contains("</trkseg>\n</trk>\n<trk><name>섞인 여행</name><type>walking</type>\n<trkseg>"), xml)
    }

    @Test
    fun returningToAnActivityStartsAnotherTrk() {
        val xml = GpxWriter.trip(
            "t",
            listOf(
                seg(listOf(tp(0))),
                seg(listOf(tp(1_000)), activity = ActivityType.RUN),
                seg(listOf(tp(2_000))),
            ),
            "c",
            exportedAt,
        )
        assertEquals(listOf("cycling", "running", "cycling"), Regex("<type>([a-z]+)</type>").findAll(xml).map { it.groupValues[1] }.toList())
    }

    @Test
    fun hikeAndRunWriteTheirGpxType() {
        val run = GpxWriter.trip("t", listOf(seg(listOf(tp(0)), activity = ActivityType.RUN)), "c", exportedAt)
        val hike = GpxWriter.trip("t", listOf(seg(listOf(tp(0)), activity = ActivityType.HIKE)), "c", exportedAt)
        assertTrue(run.contains("<type>running</type>"))
        assertTrue(hike.contains("<type>hiking</type>"))
    }

    @Test
    fun pointHasEleAndUtcTime() {
        val xml = GpxWriter.trip("t", listOf(seg(listOf(tp(0)))), "c", exportedAt)
        assertTrue(xml.contains("<trkpt lat=\"37.1234567\" lon=\"127.7654321\"><ele>12.3</ele><time>2026-10-06T01:02:03Z</time></trkpt>"), xml)
    }

    @Test
    fun missingAltitudeOmitsEle() {
        val xml = GpxWriter.trip("t", listOf(seg(listOf(tp(0, alt = null), tp(1_000, alt = Double.NaN)))), "c", exportedAt)
        assertFalse(xml.contains("<ele>"))
        assertEquals(2, Regex("<trkpt ").findAll(xml).count())
    }

    @Test
    fun nonFiniteCoordinatesAreSkipped() {
        val xml = GpxWriter.trip("t", listOf(seg(listOf(tp(0), tp(1_000, lat = Double.NaN), tp(2_000, lon = Double.POSITIVE_INFINITY)))), "c", exportedAt)
        assertWellFormed(xml)
        assertEquals(1, Regex("<trkpt ").findAll(xml).count())
    }

    @Test
    fun numbersIgnoreDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val xml = GpxWriter.trip("t", listOf(seg(listOf(tp(0)))), "c", exportedAt)
            assertTrue(xml.contains("lat=\"37.1234567\" lon=\"127.7654321\""))
            assertTrue(xml.contains("<ele>12.3</ele>"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun heartRateWithinFiveSeconds() {
        val points = listOf(tp(0), tp(100_000), tp(200_000), tp(300_000))
        val hr = listOf(
            HeartRateSample(t0 + 4_900, 120),    // 점 0: 4.9 s 앞 -> 들어감
            HeartRateSample(t0 + 100_000 + 5_100, 130), // 점 1: 5.1 s -> 안 들어감
            HeartRateSample(t0 + 200_000 - 5_000, 140), // 점 2: 정확히 5.0 s -> 들어감
        )
        val xml = GpxWriter.trip("t", listOf(seg(points, hr)), "c", exportedAt)
        val bpms = Regex("<trkpt .*?</trkpt>").findAll(xml).map { m ->
            Regex("<gpxtpx:hr>(\\d+)</gpxtpx:hr>").find(m.value)?.groupValues?.get(1)?.toInt()
        }.toList()
        assertEquals(listOf(120, null, 140, null), bpms)
        assertTrue(xml.contains("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>"))
    }

    @Test
    fun heartRateTiePicksEarlierAndNearestWins() {
        val samples = listOf(HeartRateSample(1_000, 100), HeartRateSample(3_000, 110), HeartRateSample(3_500, 120))
        assertEquals(100, GpxWriter.nearestBpm(samples, 2_000)) // 같은 거리 -> 앞
        assertEquals(120, GpxWriter.nearestBpm(samples, 3_400))
        assertEquals(null, GpxWriter.nearestBpm(samples, 9_000))
        assertEquals(null, GpxWriter.nearestBpm(emptyList(), 0))
    }

    @Test
    fun heartRateHandlesUnsortedSamples() {
        val points = listOf(tp(0))
        val hr = listOf(HeartRateSample(t0 + 4_000, 150), HeartRateSample(t0 + 100, 90))
        val xml = GpxWriter.trip("t", listOf(seg(points, hr)), "c", exportedAt)
        assertTrue(xml.contains("<gpxtpx:hr>90</gpxtpx:hr>"))
    }

    @Test
    fun escapesTitle() {
        val xml = GpxWriter.trip("A&B <x> \"q\" 'a'", listOf(seg(listOf(tp(0)))), "c&\"d", exportedAt)
        assertWellFormed(xml)
        assertTrue(xml.contains("<name>A&amp;B &lt;x&gt; &quot;q&quot; &apos;a&apos;</name>"))
        assertTrue(xml.contains("creator=\"c&amp;&quot;d\""))
        assertFalse(xml.contains("<x>"))
    }

    private fun route(elevations: List<Double?>): Route {
        val points = listOf(GeoPoint(37.5, 127.0), GeoPoint(37.51, 127.01), GeoPoint(37.52, 127.02))
        return Route(
            points = points,
            cumulativeM = listOf(0.0, 100.0, 200.0),
            instructions = emptyList(),
            stopPointIndices = listOf(1, 2),
            summary = RouteSummary(200.0, 0, 0.0, RouteProfile.BALANCED),
            elevationsM = elevations,
        )
    }

    private val stops = listOf(
        Stop(1, "경유 & 지", GeoPoint(37.51, 127.01), false),
        Stop(2, "남산", GeoPoint(37.52, 127.02), true),
    )

    @Test
    fun routeHasWaypointsAndNoTime() {
        val xml = GpxWriter.route(ExportLabels.routeName("남산"), route(listOf(10.0, 20.04, null)), stops, "c", exportedAt)
        assertWellFormed(xml)
        assertEquals(2, Regex("<wpt ").findAll(xml).count())
        assertTrue(xml.contains("<wpt lat=\"37.5100000\" lon=\"127.0100000\"><name>경유 &amp; 지</name></wpt>"))
        assertTrue(xml.contains("<trk><name>bikeRecoder 경로 · 남산</name><type>cycling</type>"))
        assertEquals(3, Regex("<trkpt ").findAll(xml).count())
        assertTrue(xml.contains("<trkpt lat=\"37.5000000\" lon=\"127.0000000\"><ele>10.0</ele></trkpt>"))
        assertTrue(xml.contains("<ele>20.0</ele>"))
        assertEquals(2, Regex("<ele>").findAll(xml).count()) // null 점은 뺀다
        // <time>은 metadata 안에만 있다
        assertEquals(1, Regex("<time>").findAll(xml).count())
        assertEquals(1, Regex("<trkseg>").findAll(xml).count())
    }

    @Test
    fun routeWithMismatchedElevationsOmitsEle() {
        assertFalse(GpxWriter.route("r", route(emptyList()), stops, "c", exportedAt).contains("<ele>"))
        assertFalse(GpxWriter.route("r", route(listOf(1.0, 2.0)), stops, "c", exportedAt).contains("<ele>"))
    }

    @Test
    fun negativeZeroIsNotWritten() {
        val xml = GpxWriter.trip("t", listOf(seg(listOf(tp(0, alt = -0.04)))), "c", exportedAt)
        assertTrue(xml.contains("<ele>0.0</ele>"))
    }
}
