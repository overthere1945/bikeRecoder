package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.ride.FakeRideStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 공유 창의 "지나간 길" 태그(공유 카드 설계 §4.4, §4.5): 여행의 끝난 구간 트랙을 앱의 장소 데이터에 맞춘다. */
class PlaceTagsTest {

    // Unit tests run with the module directory (app/) as the working directory.
    private val asset = File("src/main/assets/places/places.bin")

    private val rides = FakeRideStore(FakeTripStore())
    private val warnings = mutableListOf<String>()
    private val log = WarnLog { message, _ -> warnings += message }
    private var opens = 0

    private fun tags(open: () -> InputStream = { asset.inputStream() }) =
        PlaceTags({ opens++; open() }, rides, log)

    private val sangjubo = GeoPoint(36.43117, 128.25008)
    private val bukhansan = GeoPoint(37.6586, 126.9779)

    /** A straight north-south track of [lengthM] metres with [centre] in the middle. */
    private fun trackThrough(centre: GeoPoint, lengthM: Double): List<GeoPoint> {
        val halfDeg = lengthM / 2 / 110_540.0
        return (0..60).map { i -> GeoPoint(centre.lat - halfDeg + 2 * halfDeg * i / 60, centre.lon) }
    }

    private suspend fun segment(tripId: Long, startedAt: Long, track: List<GeoPoint>, distanceM: Double, finished: Boolean = true) {
        val id = rides.openSegment(tripId, startedAt, LocalDate.of(2026, 11, 1))
        val totals = SegmentTotals.ZERO.copy(distanceM = distanceM)
        rides.appendPoints(id, track.mapIndexed { i, p -> TrackPoint(startedAt + i * 1000L, p, null, null, 5f) }, totals)
        if (finished) rides.closeSegment(id, startedAt + track.size * 1000L, totals)
    }

    @Test
    fun finishedLongSegmentsAreMatched() = runTest {
        segment(1, 1_000_000, trackThrough(sangjubo, 3_000.0), distanceM = 3_000.0)

        val found = tags().of(1)

        assertTrue("#낙동강자전거길" in found, "tags: $found")
        assertTrue("#국토종주" in found, "tags: $found")
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun openAndShortSegmentsAreLeftOut() = runTest {
        segment(1, 1_000_000, trackThrough(bukhansan, 1_000.0), distanceM = 5.0)
        segment(1, 2_000_000, trackThrough(sangjubo, 3_000.0), distanceM = 3_000.0, finished = false)

        assertEquals(emptyList<String>(), tags().of(1))
    }

    @Test
    fun otherTripsAreLeftOut() = runTest {
        segment(2, 1_000_000, trackThrough(bukhansan, 1_000.0), distanceM = 1_000.0)

        assertEquals(emptyList<String>(), tags().of(1))
        assertTrue("#북한산" in tags().of(2))
    }

    @Test
    fun dataIsReadOnce() = runTest {
        segment(1, 1_000_000, trackThrough(bukhansan, 1_000.0), distanceM = 1_000.0)
        val places = tags()

        places.of(1)
        val again = places.of(1)

        assertEquals(1, opens)
        assertTrue("#북한산" in again, "tags: $again")
    }

    @Test
    fun unreadableDataGivesNoTagsAndWarns() = runTest {
        segment(1, 1_000_000, trackThrough(bukhansan, 1_000.0), distanceM = 1_000.0)
        val places = tags { ByteArrayInputStream("NOPE".toByteArray()) }

        assertEquals(emptyList<String>(), places.of(1))
        assertEquals(emptyList<String>(), places.of(1))
        assertEquals(1, warnings.size)
        assertFalse(opens > 1)
    }
}
