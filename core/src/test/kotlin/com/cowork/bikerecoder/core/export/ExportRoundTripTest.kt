package com.cowork.bikerecoder.core.export

import com.cowork.bikerecoder.core.importing.GpxReader
import com.cowork.bikerecoder.core.importing.RideReplay
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExportRoundTripTest {

    @Test
    fun exportedTripReadsBackWithSameNumbers() {
        val t0 = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
        // 1 Hz 20분. 평균 약 5 m/s로 동쪽, 위도는 완만하게 흔들리고 고도는 오르내린다.
        val fixes = (0..1_200).map { s ->
            LocationFix(
                point = GeoPoint(37.5 + 0.0005 * sin(s / 120.0), 127.0 + s * 5.0 * 1.1322e-5),
                accuracyM = 5f,
                speedMps = null,
                bearingDeg = null,
                timeMillis = t0 + s * 1_000L,
                altitudeM = 40.0 + 15.0 * sin(s / 200.0),
            )
        }
        val original = assertNotNull(RideReplay.replay(fixes))

        val segment = ExportSegment(original.startedAt, original.localDate, original.points, emptyList())
        val xml = GpxWriter.trip("왕복", listOf(segment), "bikeRecoder test", t0)

        val track = GpxReader.read(xml.toByteArray(Charsets.UTF_8))
        assertEquals("왕복", track.name)
        assertEquals("cycling", track.type)
        assertEquals(0, track.droppedPoints)
        val again = assertNotNull(RideReplay.replay(track.fixes))

        assertEquals(original.startedAt, again.startedAt)
        assertEquals(original.endedAt, again.endedAt)
        assertTrue(within(original.totals.distanceM, again.totals.distanceM), "거리 ${original.totals.distanceM} vs ${again.totals.distanceM}")
        assertTrue(
            within(original.totals.movingTimeMs.toDouble(), again.totals.movingTimeMs.toDouble()),
            "이동 시간 ${original.totals.movingTimeMs} vs ${again.totals.movingTimeMs}",
        )
        assertTrue(original.totals.distanceM > 5_000.0)
    }

    private fun within(expected: Double, actual: Double): Boolean = abs(actual - expected) <= expected * 0.01
}
