package com.cowork.bikerecoder.core.gpx

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpxParserTest {

    @Test
    fun `gpx parser reads track points`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="37.5" lon="127.0"><ele>12.0</ele><time>2026-10-06T00:00:00Z</time></trkpt>
                <trkpt lat="37.50001" lon="127.00005"><time>2026-10-06T00:00:01Z</time></trkpt>
                <trkpt lat="37.50002" lon="127.0001"><time>2026-10-06T00:00:02.500Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val fixes = GpxParser.parse(gpx)

        assertEquals(3, fixes.size)
        assertEquals(
            listOf(GeoPoint(37.5, 127.0), GeoPoint(37.50001, 127.00005), GeoPoint(37.50002, 127.0001)),
            fixes.map { it.point },
        )
        val t0 = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
        assertEquals(listOf(t0, t0 + 1_000, t0 + 2_500), fixes.map { it.timeMillis })
        fixes.forEach {
            assertEquals(5f, it.accuracyM)
            assertNull(it.speedMps)
            assertNull(it.bearingDeg)
        }
    }

    @Test
    fun `reads fixture resource`() {
        val text = checkNotNull(javaClass.getResource("/gpx/deviate.gpx")).readText()

        val fixes = GpxParser.parse(text)

        assertEquals(50, fixes.size)
        assertEquals(Instant.parse("2026-10-06T00:00:49Z").toEpochMilli(), fixes.last().timeMillis)
    }
}
