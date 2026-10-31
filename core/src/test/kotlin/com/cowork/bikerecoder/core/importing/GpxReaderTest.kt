package com.cowork.bikerecoder.core.importing

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GpxReaderTest {

    private fun gpx(body: String, trkExtra: String = "") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <trk>$trkExtra<trkseg>$body</trkseg></trk>
        </gpx>
    """.trimIndent()

    private fun pt(lat: String, lon: String, time: String?, ele: String? = null) =
        "<trkpt lat=\"$lat\" lon=\"$lon\">" +
            (ele?.let { "<ele>$it</ele>" } ?: "") +
            (time?.let { "<time>$it</time>" } ?: "") +
            "</trkpt>"

    private fun read(text: String) = GpxReader.read(text.toByteArray())

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun readsPointsTypeAndName() {
        val track = read(
            gpx(
                pt("37.5", "127.0", "2026-10-06T00:00:00Z", "12.5") + pt("37.50001", "127.00005", "2026-10-06T00:00:02.500Z"),
                trkExtra = "<name>  아침 라이딩 </name><type> cycling </type>",
            ),
        )

        assertEquals("아침 라이딩", track.name)
        assertEquals("cycling", track.type)
        assertEquals(2, track.totalPoints)
        assertEquals(0, track.droppedPoints)
        assertEquals(listOf(GeoPoint(37.5, 127.0), GeoPoint(37.50001, 127.00005)), track.fixes.map { it.point })
        assertEquals(millis("2026-10-06T00:00:00Z") + 2_500, track.fixes[1].timeMillis)
        track.fixes.forEach {
            assertEquals(5f, it.accuracyM)
            assertNull(it.speedMps)
            assertNull(it.bearingDeg)
        }
        assertEquals(12.5, track.fixes[0].altitudeM)
    }

    @Test
    fun blankTypeAndNameAreNull() {
        val track = read(gpx(pt("37.5", "127.0", "2026-10-06T00:00:00Z"), trkExtra = "<name> </name><type/>"))

        assertNull(track.name)
        assertNull(track.type)
    }

    @Test
    fun noTrkMeansNullTypeAndName() {
        val track = read("""<gpx><trk><trkseg>${pt("37.5", "127.0", "2026-10-06T00:00:00Z")}</trkseg></trk></gpx>""")

        assertNull(track.name)
        assertNull(track.type)
    }

    @Test
    fun offsetTimesParse() {
        val track = read(gpx(pt("37.5", "127.0", "2023-05-14T09:12:00+09:00")))

        assertEquals(millis("2023-05-14T00:12:00Z"), track.fixes.single().timeMillis)
    }

    @Test
    fun sortsAndDropsDuplicateTimes() {
        val track = read(
            gpx(
                pt("37.3", "127.0", "2026-10-06T00:00:03Z") +
                    pt("37.1", "127.0", "2026-10-06T00:00:01Z") +
                    pt("37.2", "127.0", "2026-10-06T00:00:02Z") +
                    pt("37.9", "127.0", "2026-10-06T00:00:02Z"),
            ),
        )

        assertEquals(listOf(37.1, 37.2, 37.3), track.fixes.map { it.point.lat })
        assertEquals(4, track.totalPoints)
        assertEquals(1, track.droppedPoints)
    }

    @Test
    fun fewMissingTimesAreDropped() {
        val pts = (0 until 10).joinToString("") { i ->
            pt("37.5", "127.0${i}", if (i == 3 || i == 7) null else "2026-10-06T00:00:0${i}Z")
        }

        val track = read(gpx(pts))

        assertEquals(8, track.fixes.size)
        assertEquals(2, track.droppedPoints)
        assertEquals(10, track.totalPoints)
    }

    @Test
    fun unparsableTimeCountsAsMissing() {
        val pts = pt("37.5", "127.0", "2026-10-06T00:00:00Z") +
            pt("37.5", "127.1", "2026-10-06T00:00:01Z") +
            pt("37.5", "127.2", "yesterday")

        val track = read(gpx(pts))

        assertEquals(2, track.fixes.size)
        assertEquals(1, track.droppedPoints)
    }

    @Test
    fun mostlyMissingTimesIsNoTime() {
        val pts = (0 until 10).joinToString("") { i ->
            pt("37.5", "127.0${i}", if (i < 4) "2026-10-06T00:00:0${i}Z" else null)
        }

        val e = assertFailsWith<GpxReadException> { read(gpx(pts)) }

        assertEquals(ImportProblem.NO_TIME, e.problem)
    }

    @Test
    fun exactlyHalfMissingIsStillReadable() {
        val pts = (0 until 10).joinToString("") { i ->
            pt("37.5", "127.0${i}", if (i < 5) "2026-10-06T00:00:0${i}Z" else null)
        }

        assertEquals(5, read(gpx(pts)).fixes.size)
    }

    @Test
    fun noPointsIsNoTime() {
        val e = assertFailsWith<GpxReadException> { read(gpx("")) }

        assertEquals(ImportProblem.NO_TIME, e.problem)
    }

    @Test
    fun badCoordinatesDropped() {
        val pts = pt("37.5", "127.0", "2026-10-06T00:00:00Z") +
            pt("91", "127.0", "2026-10-06T00:00:01Z") +
            pt("37.5", "181", "2026-10-06T00:00:02Z") +
            pt("abc", "127.0", "2026-10-06T00:00:03Z") +
            pt("NaN", "127.0", "2026-10-06T00:00:04Z") +
            pt("-90", "-180", "2026-10-06T00:00:05Z")

        val track = read(gpx(pts))

        assertEquals(listOf(GeoPoint(37.5, 127.0), GeoPoint(-90.0, -180.0)), track.fixes.map { it.point })
        assertEquals(4, track.droppedPoints)
        assertEquals(6, track.totalPoints)
    }

    @Test
    fun missingElevationIsNull() {
        val track = read(gpx(pt("37.5", "127.0", "2026-10-06T00:00:00Z") + pt("37.5", "127.1", "2026-10-06T00:00:01Z", "x")))

        assertNull(track.fixes[0].altitudeM)
        assertNull(track.fixes[1].altitudeM)
    }

    @Test
    fun multipleTrkAndTrksegMerged() {
        val text = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>first</name><type>cycling</type>
                <trkseg>${pt("37.1", "127.0", "2026-10-06T00:00:01Z")}</trkseg>
                <trkseg>${pt("37.2", "127.0", "2026-10-06T00:00:02Z")}</trkseg>
              </trk>
              <trk><name>second</name><type>running</type>
                <trkseg>${pt("37.3", "127.0", "2026-10-06T00:00:03Z")}</trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val track = read(text)

        assertEquals(listOf(37.1, 37.2, 37.3), track.fixes.map { it.point.lat })
        assertEquals("first", track.name)
        assertEquals("cycling", track.type)
    }

    @Test
    fun routePointsAndWaypointsAreIgnored() {
        val text = """
            <gpx><wpt lat="1" lon="1"><time>2026-10-06T00:00:00Z</time></wpt>
              <rte><rtept lat="2" lon="2"><time>2026-10-06T00:00:00Z</time></rtept></rte>
              <trk><trkseg>${pt("37.1", "127.0", "2026-10-06T00:00:01Z")}</trkseg></trk></gpx>
        """.trimIndent()

        assertEquals(1, read(text).totalPoints)
    }

    @Test
    fun prefixedNamespaceReads() {
        val text = """
            <g:gpx xmlns:g="http://www.topografix.com/GPX/1/1">
              <g:trk><g:name>pref</g:name><g:trkseg>
                <g:trkpt lat="37.5" lon="127.0"><g:ele>3</g:ele><g:time>2026-10-06T00:00:00Z</g:time></g:trkpt>
              </g:trkseg></g:trk>
            </g:gpx>
        """.trimIndent()

        val track = read(text)

        assertEquals("pref", track.name)
        assertEquals(3.0, track.fixes.single().altitudeM)
    }

    @Test
    fun doctypeIsRejected(@TempDir dir: File) {
        val secret = File(dir, "secret.txt").apply { writeText("TOP-SECRET") }
        val text = """
            <?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
            <gpx><trk><name>&xxe;</name><trkseg>${pt("37.5", "127.0", "2026-10-06T00:00:00Z")}</trkseg></trk></gpx>
        """.trimIndent()

        val e = assertFailsWith<GpxReadException> { read(text) }

        assertEquals(ImportProblem.UNREADABLE, e.problem)
    }

    @Test
    fun nonGpxXmlIsNotGpx() {
        val e = assertFailsWith<GpxReadException> { read("""<kml><Document/></kml>""") }

        assertEquals(ImportProblem.NOT_GPX, e.problem)
    }

    @Test
    fun plainTextIsNotGpx() {
        val e = assertFailsWith<GpxReadException> { read("hello, not xml") }

        assertEquals(ImportProblem.NOT_GPX, e.problem)
    }

    @Test
    fun truncatedGpxIsUnreadable() {
        val text = gpx(pt("37.5", "127.0", "2026-10-06T00:00:00Z")).substringBefore("</trkseg>")

        val e = assertFailsWith<GpxReadException> { read(text) }

        assertEquals(ImportProblem.UNREADABLE, e.problem)
    }

    @Test
    fun leadingWhitespaceBeforeXmlDeclReads() {
        val body = gpx(pt("37.5", "127.0", "2026-10-06T00:00:00Z"))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        assertEquals(1, GpxReader.read("\n  \r\n\t$body".toByteArray()).fixes.size)
        assertEquals(1, GpxReader.read(bom + " \n$body".toByteArray()).fixes.size)
        assertEquals(1, GpxReader.read(bom + body.toByteArray()).fixes.size)
    }

    @Test
    fun absurdTimeIsDropped() {
        val track = read(
            gpx(
                pt("37.5", "127.0", "2026-10-06T00:00:00Z") +
                    pt("37.5", "127.1", "2026-10-06T00:00:01Z") +
                    pt("37.5", "127.2", "+999999999-12-31T00:00:00Z"),
            ),
        )

        assertEquals(2, track.fixes.size)
        assertEquals(1, track.droppedPoints)
    }
}
