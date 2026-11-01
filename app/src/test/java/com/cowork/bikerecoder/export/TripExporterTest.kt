package com.cowork.bikerecoder.export

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.export.ExportLabels
import com.cowork.bikerecoder.core.export.ExportSegment
import com.cowork.bikerecoder.core.export.GpxWriter
import com.cowork.bikerecoder.core.export.KmlWriter
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/** A trip or a planned route into GPX/KML text, handed to the writer (spec §4.2–§4.4); fake stores and writer. */
class TripExporterTest {

    private val rides = FakeRideStore(FakeTripStore())
    private val post = FakePostStore { rides.segments(TRIP) }
    private val sink = RecordingSink()
    private val warnings = mutableListOf<Pair<String, Throwable?>>()
    private val log = WarnLog { message, error -> warnings += message to error }

    private fun exporter(io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined) =
        TripExporter(rides, post, sink, io, clock = { EXPORTED_AT }, creator = CREATOR, log = log)

    /** [count] points 10 m apart going north from [startLat], one every 2 s from [startedAt], elevation 50 + i. */
    private fun track(startedAt: Long, startLat: Double, count: Int): List<TrackPoint> = List(count) { i ->
        TrackPoint(startedAt + i * 2_000L, GeoPoint(startLat + i * 10.0 / 111_320.0, 127.0), 50.0 + i, 5f, 5f)
    }

    private suspend fun ride(
        startedAt: Long,
        day: LocalDate,
        points: List<TrackPoint>,
        distanceM: Double = 1_000.0,
        activity: ActivityType = ActivityType.BIKE,
    ): Long {
        val totals = SegmentTotals.ZERO.copy(distanceM = distanceM, movingTimeMs = 60_000)
        val id = rides.openSegment(TRIP, startedAt, day, activity)
        rides.appendPoints(id, points, totals)
        rides.closeSegment(id, points.last().timeMillis, totals)
        return id
    }

    @Test
    fun tripGpxHasAllSegmentsAndHeartRate() = runBlocking {
        val day1 = LocalDate.of(2026, 10, 24)
        val day2 = LocalDate.of(2026, 10, 25)
        val start1 = day1.atTime(7, 5).atZone(SEOUL).toInstant().toEpochMilli()
        val start2 = day2.atTime(9, 0).atZone(SEOUL).toInstant().toEpochMilli()
        val second = track(start2, 37.6, 4)
        val first = track(start1, 37.5, 3)
        // Saved out of order: the file still goes by start time.
        val id2 = ride(start2, day2, second)
        val id1 = ride(start1, day1, first)
        // An empty segment (no distance) is not listed on the screen, nor exported.
        ride(start2 + 3_600_000L, day2, track(start2 + 3_600_000L, 37.7, 1), distanceM = 0.0)
        val hr1 = listOf(HeartRateSample(start1 + 1_000L, 121), HeartRateSample(start1 + 4_000L, 133))
        post.samplesById[id1] = hr1
        val segmentsBefore = rides.segments(TRIP)
        val pointsBefore = segmentsBefore.map { rides.points(it.id) }

        val result = exporter().exportTrip(TRIP, ExportFormat.GPX, TITLE)

        assertEquals(ExportResult.Saved(SAVED_URI, "bikeRecoder_20261024_0705.gpx", "application/gpx+xml"), result)
        val (name, mime, content) = sink.saved.single()
        assertEquals("bikeRecoder_20261024_0705.gpx", name)
        assertEquals("application/gpx+xml", mime)
        val expected = GpxWriter.trip(
            TITLE,
            listOf(ExportSegment(start1, day1, first, hr1), ExportSegment(start2, day2, second, emptyList())),
            CREATOR,
            EXPORTED_AT,
        )
        assertEquals(expected, content)
        assertEquals(2, Regex("<trkseg>").findAll(content).count())
        assertTrue("<gpxtpx:hr>121</gpxtpx:hr>" in content)
        assertTrue("<gpxtpx:hr>133</gpxtpx:hr>" in content)
        assertTrue("creator=\"$CREATOR\"" in content)
        // Exporting changes nothing in the app's records.
        assertEquals(segmentsBefore, rides.segments(TRIP))
        assertEquals(pointsBefore, segmentsBefore.map { rides.points(it.id) })
        assertEquals(hr1, post.samples(id1))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun segmentActivitiesGoToTheFiles() = runBlocking {
        val day = LocalDate.of(2026, 10, 24)
        val start = day.atTime(7, 0).atZone(SEOUL).toInstant().toEpochMilli()
        val bike = track(start, 37.5, 3)
        val run = track(start + 3_600_000L, 37.6, 3)
        ride(start, day, bike)
        ride(start + 3_600_000L, day, run, activity = ActivityType.RUN)

        exporter().exportTrip(TRIP, ExportFormat.GPX, TITLE)
        exporter().exportTrip(TRIP, ExportFormat.KML, TITLE)

        val (_, _, gpx) = sink.saved[0]
        val (_, _, kml) = sink.saved[1]
        assertEquals(listOf("cycling", "running"), Regex("<type>([a-z]+)</type>").findAll(gpx).map { it.groupValues[1] }.toList())
        assertEquals(2, Regex("<trk>").findAll(gpx).count())
        assertTrue("<name>2026-10-24 주행</name>" in kml)
        assertTrue("<name>2026-10-24 달리기</name>" in kml)
    }

    @Test
    fun tripKmlHasEverySegment() = runBlocking {
        val day = LocalDate.of(2026, 10, 24)
        val start = day.atTime(18, 0).atZone(SEOUL).toInstant().toEpochMilli()
        val points = track(start, 37.5, 3)
        ride(start, day, points)

        val result = exporter().exportTrip(TRIP, ExportFormat.KML, TITLE)

        assertEquals(ExportResult.Saved(SAVED_URI, "bikeRecoder_20261024_1800.kml", ExportFormat.KML.mime), result)
        val (_, mime, content) = sink.saved.single()
        assertEquals("application/vnd.google-earth.kml+xml", mime)
        assertEquals(KmlWriter.trip(TITLE, listOf(ExportSegment(start, day, points, emptyList()))), content)
    }

    @Test
    fun openSegmentIsRecording() = runBlocking {
        val day = LocalDate.of(2026, 10, 24)
        ride(1_000_000L, day, track(1_000_000L, 37.5, 3))
        val open = rides.openSegment(TRIP, 9_000_000L, day)
        rides.appendPoints(open, track(9_000_000L, 37.6, 2), SegmentTotals.ZERO.copy(distanceM = 10.0))

        assertEquals(ExportResult.Recording, exporter().exportTrip(TRIP, ExportFormat.GPX, TITLE))
        assertTrue(sink.saved.isEmpty())
        assertEquals(0, sink.calls)
    }

    @Test
    fun writerFailureIsFailed() = runBlocking {
        ride(1_000_000L, LocalDate.of(2026, 10, 24), track(1_000_000L, 37.5, 3))
        val boom = IOException("no space left")
        sink.fail = boom

        assertEquals(ExportResult.Failed, exporter().exportTrip(TRIP, ExportFormat.GPX, TITLE))
        assertEquals(boom, warnings.single().second)
    }

    @Test
    fun tripWithoutRidesIsFailed() = runBlocking {
        assertEquals(ExportResult.Failed, exporter().exportTrip(TRIP, ExportFormat.GPX, TITLE))
        assertEquals(0, sink.calls)
        assertEquals(1, warnings.size)
    }

    /** A big trip (30,000 points): built and written on the io dispatcher, the whole file down to the closing tag. */
    @Test
    fun exportRunsOffMainAndWritesWholeFile() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }
        val io = executor.asCoroutineDispatcher()
        try {
            val day = LocalDate.of(2026, 10, 24)
            val start = day.atTime(6, 0).atZone(SEOUL).toInstant().toEpochMilli()
            runBlocking { ride(start, day, track(start, 37.0, 30_000), distanceM = 300_000.0) }
            val caller = Thread.currentThread().name

            val began = System.nanoTime()
            val result = runBlocking { exporter(io).exportTrip(TRIP, ExportFormat.GPX, TITLE) }
            val tookMs = (System.nanoTime() - began) / 1_000_000
            println("TripExporterTest: 30,000-point GPX export took $tookMs ms")

            assertTrue(result is ExportResult.Saved)
            // With coroutine debugging on, the thread name carries " @coroutine#n".
            assertEquals(IO_THREAD, sink.thread?.substringBefore(" @"))
            assertNotEquals(caller.substringBefore(" @"), IO_THREAD)
            val content = sink.saved.single().third
            assertTrue(content.endsWith("</trk>\n</gpx>\n"))
            assertEquals(30_000, Regex("<trkpt ").findAll(content).count())
            assertTrue(tookMs < 5_000, "30,000 points took $tookMs ms")
        } finally {
            io.close()
        }
    }

    private val route = Route(
        points = listOf(GeoPoint(35.10, 129.00), GeoPoint(35.157, 129.06), GeoPoint(35.163, 129.16)),
        cumulativeM = listOf(0.0, 8_000.0, 17_000.0),
        instructions = emptyList(),
        stopPointIndices = listOf(1, 2),
        summary = RouteSummary(17_000.0, 120, 0.5, RouteProfile.CYCLEWAY_FIRST),
        elevationsM = listOf(10.0, 42.5, 3.0),
    )
    private val stops = listOf(
        Stop(1, "서면", GeoPoint(35.157, 129.06), isDestination = false),
        Stop(2, "해운대", GeoPoint(35.163, 129.16), isDestination = true),
    )

    @Test
    fun routeKml() = runBlocking {
        val result = exporter().exportRoute(route, stops, "해운대", ExportFormat.KML)

        // 2026-11-01 00:00 UTC = 09:00 Seoul.
        assertEquals(ExportResult.Saved(SAVED_URI, "bikeRecoder_route_20261101_0900.kml", ExportFormat.KML.mime), result)
        val (name, mime, content) = sink.saved.single()
        assertEquals("bikeRecoder_route_20261101_0900.kml", name)
        assertEquals(ExportFormat.KML.mime, mime)
        assertEquals(KmlWriter.route(ExportLabels.routeName("해운대"), route, stops), content)
        assertTrue("<name>bikeRecoder 경로 · 해운대</name>" in content)
        assertTrue("<name>서면</name>" in content)
    }

    @Test
    fun routeGpxNamedAfterDestination() = runBlocking {
        val result = exporter().exportRoute(route, stops, "해운대", ExportFormat.GPX)

        assertEquals(ExportResult.Saved(SAVED_URI, "bikeRecoder_route_20261101_0900.gpx", ExportFormat.GPX.mime), result)
        val content = sink.saved.single().third
        assertEquals(GpxWriter.route("bikeRecoder 경로 · 해운대", route, stops, CREATOR, EXPORTED_AT), content)
    }

    @Test
    fun creatorCarriesTheVersion() {
        assertEquals("bikeRecoder 1.7.0", TripExporter.creator("1.7.0"))
    }

    /** Records what it is given and the thread it ran on; throws [fail] instead when set. */
    private class RecordingSink : ExportSink {
        val saved = mutableListOf<Triple<String, String, String>>()
        var calls = 0
        var fail: Exception? = null

        @Volatile var thread: String? = null

        override fun save(displayName: String, mime: String, content: String): ExportResult.Saved {
            calls++
            thread = Thread.currentThread().name
            fail?.let { throw it }
            saved += Triple(displayName, mime, content)
            return ExportResult.Saved(SAVED_URI, displayName, mime)
        }
    }

    private companion object {
        const val TRIP = 1L
        const val TITLE = "저녁 라이딩 · 서울 강서구"
        const val CREATOR = "bikeRecoder 1.7.0-test"
        const val SAVED_URI = "content://media/external/downloads/42"
        const val IO_THREAD = "export-io-test"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val EXPORTED_AT = java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
    }
}
