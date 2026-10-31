package com.cowork.bikerecoder.core.importing

import com.cowork.bikerecoder.core.gpx.GpxParser
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.recording.RecordingSession
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.toTotals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideReplayTest {

    private val followText: String =
        checkNotNull(javaClass.getResourceAsStream("/gpx/follow.gpx")) { "follow.gpx 없음" }
            .readBytes().toString(Charsets.UTF_8)

    private val t0 = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()

    /** 동쪽으로 [meters]만큼 간 점. 위도 37.5에서 1 m는 경도 약 1.1322e-5도. */
    private fun fix(second: Int, meters: Double, baseMillis: Long = t0) = LocationFix(
        point = GeoPoint(37.5, 127.0 + meters * 1.1322e-5),
        accuracyM = 5f,
        speedMps = null,
        bearingDeg = null,
        timeMillis = baseMillis + second * 1_000L,
    )

    @Test
    fun matchesLiveRecording() {
        val fixes = GpxParser.parse(followText)

        val live = RecordingSession(startMillis = fixes.first().timeMillis, autoPause = true)
        live.start()
        val livePoints = fixes.mapNotNull { live.onFix(it, it.timeMillis).point }
        val liveTotals = live.stop(fixes.last().timeMillis).stats.toTotals()

        val replayed = assertNotNull(RideReplay.replay(fixes))
        assertEquals(liveTotals, replayed.totals)
        assertEquals(livePoints, replayed.points)
        assertEquals(fixes.first().timeMillis, replayed.startedAt)
        assertEquals(fixes.last().timeMillis, replayed.endedAt)
        assertTrue(replayed.totals.distanceM > 1_000.0)
    }

    @Test
    fun pauseIsNotMovingTime() {
        val fixes = ArrayList<LocationFix>()
        for (s in 0..60) fixes += fix(s, 5.0 * s) // 1분 달리기
        for (s in 61..360) fixes += fix(s, 300.0) // 5분 같은 자리
        for (s in 361..420) fixes += fix(s, 300.0 + 5.0 * (s - 360)) // 1분 달리기

        val ride = assertNotNull(RideReplay.replay(fixes))

        assertEquals(420_000L, ride.totals.elapsedMs)
        assertTrue(ride.totals.movingTimeMs in 105_000L..135_000L, "movingTimeMs=${ride.totals.movingTimeMs}")
    }

    @Test
    fun shuffledPointsGiveSameResult() {
        val pointLines = Regex("<trkpt .*?</trkpt>").findAll(followText).map { it.value }.toList()
        assertTrue(pointLines.size > 100)
        val rng = Random(7)
        val messy = (pointLines + pointLines.filterIndexed { i, _ -> i % 5 == 0 }).shuffled(rng)
        val shuffledText = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>${messy.joinToString("\n")}</trkseg></trk>
            </gpx>
        """.trimIndent()

        val original = assertNotNull(RideReplay.replay(GpxReader.read(followText.toByteArray()).fixes))
        val shuffled = assertNotNull(RideReplay.replay(GpxReader.read(shuffledText.toByteArray()).fixes))

        assertEquals(original, shuffled)
    }

    @Test
    fun localDateIsSeoulDate() {
        // 2023-05-13T16:30:00Z = 서울 2023-05-14 01:30
        val base = Instant.parse("2023-05-13T16:30:00Z").toEpochMilli()
        val fixes = (0..30).map { fix(it, 5.0 * it, base) }

        val ride = assertNotNull(RideReplay.replay(fixes))

        assertEquals(LocalDate.of(2023, 5, 14), ride.localDate)
    }

    @Test
    fun tooShortIsNull() {
        assertNull(RideReplay.replay(listOf(fix(0, 0.0), fix(3, 9.0))))
        assertNull(RideReplay.replay(listOf(fix(0, 0.0))))
        assertNull(RideReplay.replay(emptyList()))
    }
}
