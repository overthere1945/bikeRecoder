package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PhotoPlacementTest {

    private fun tp(timeMillis: Long, lat: Double, lon: Double) =
        TrackPoint(timeMillis, GeoPoint(lat, lon), altitudeM = null, speedMps = null, accuracyM = 5f)

    private val a = tp(10_000, 37.0, 127.0)
    private val b = tp(20_000, 37.0002, 127.0004)

    @Test
    fun exifWins() {
        val exif = GeoPoint(35.1, 129.0)
        val placed = PhotoPlacement.place(PhotoCandidate(1, 15_000, exif), listOf(a, b))
        assertNotNull(placed)
        assertEquals(exif, placed.point)
        assertEquals(PhotoPositionSource.EXIF, placed.source)
        assertEquals(1L, placed.mediaId)
        assertEquals(15_000L, placed.takenAt)
    }

    @Test
    fun interpolatesBetweenNeighbours() {
        val placed = PhotoPlacement.place(PhotoCandidate(2, 15_000, null), listOf(a, b))
        assertNotNull(placed)
        assertEquals(PhotoPositionSource.TRACK, placed.source)
        assertEquals(37.0001, placed.point.lat, 1e-9)
        assertEquals(127.0002, placed.point.lon, 1e-9)
    }

    @Test
    fun photoInLongGapSnapsToNearerPoint() {
        val far = tp(130_000, 37.01, 127.02) // 120 s after a
        val track = listOf(a, far)
        val nearFront = PhotoPlacement.place(PhotoCandidate(3, 30_000, null), track)
        assertNotNull(nearFront)
        assertEquals(a.point, nearFront.point)
        assertEquals(PhotoPositionSource.TRACK, nearFront.source)
        val nearBack = PhotoPlacement.place(PhotoCandidate(4, 110_000, null), track)
        assertNotNull(nearBack)
        assertEquals(far.point, nearBack.point)
    }

    @Test
    fun beforeStartUsesFirstPoint() {
        val placed = PhotoPlacement.place(PhotoCandidate(5, 1_000, null), listOf(a, b))
        assertNotNull(placed)
        assertEquals(a.point, placed.point)
        assertEquals(PhotoPositionSource.TRACK, placed.source)
    }

    @Test
    fun afterEndUsesLastPoint() {
        val placed = PhotoPlacement.place(PhotoCandidate(6, 999_000, null), listOf(a, b))
        assertNotNull(placed)
        assertEquals(b.point, placed.point)
    }

    @Test
    fun exactTimestampHitUsesThatPoint() {
        val placed = PhotoPlacement.place(PhotoCandidate(7, 20_000, null), listOf(a, b))
        assertNotNull(placed)
        assertEquals(b.point, placed.point)
    }

    @Test
    fun emptyTrackGivesNull() {
        assertNull(PhotoPlacement.place(PhotoCandidate(8, 15_000, null), emptyList()))
    }

    @Test
    fun emptyTrackGivesNullEvenWithoutExif() {
        // EXIF 위치가 있으면 트랙이 비어 있어도 쓸 수 있다.
        val exif = GeoPoint(35.0, 129.0)
        val placed = PhotoPlacement.place(PhotoCandidate(9, 15_000, exif), emptyList())
        assertNotNull(placed)
        assertEquals(PhotoPositionSource.EXIF, placed.source)
    }

    @Test
    fun windowIsFiveMinutesEachSide() {
        assertEquals(1_000_000L - 300_000L, PhotoPlacement.windowStart(1_000_000L))
        assertEquals(2_000_000L + 300_000L, PhotoPlacement.windowEnd(2_000_000L))
    }
}
