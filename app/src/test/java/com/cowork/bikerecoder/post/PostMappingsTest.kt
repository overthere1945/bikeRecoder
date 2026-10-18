package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.data.RidePhotoEntity
import com.cowork.bikerecoder.data.toEntity
import com.cowork.bikerecoder.data.toModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PostMappingsTest {
    @Test
    fun `placed photo becomes a visible row of the segment`() {
        val placed = PlacedPhoto(mediaId = 77, takenAt = 5_000L, point = GeoPoint(37.5, 127.1), source = PhotoPositionSource.TRACK)

        val entity = placed.toEntity(segmentId = 4)

        assertEquals(RidePhotoEntity(0, 4, 77, 5_000L, 37.5, 127.1, "TRACK", hidden = false), entity)
    }

    @Test
    fun `photo row maps to model with source and hidden`() {
        val entity = RidePhotoEntity(9, 4, 77, 5_000L, 37.5, 127.1, "EXIF", hidden = true)

        assertEquals(
            RidePhoto(9, 4, 77, 5_000L, GeoPoint(37.5, 127.1), PhotoPositionSource.EXIF, hidden = true),
            entity.toModel(),
        )
    }

    @Test
    fun `extras round trip keeps every field and stores status by name`() {
        val extras = SegmentExtras(
            segmentId = 4,
            photosScannedAt = 1_000L,
            photoScanDone = true,
            hrStatus = HrStatus.FAILED,
            hrFirstTriedAt = 2_000L,
            hrLastTriedAt = 3_000L,
            hrDetailed = true,
            avgBpm = 121,
            maxBpm = 166,
            calories = 432.5,
            simplifiedDone = true,
        )

        val entity = extras.toEntity()

        assertEquals("FAILED", entity.hrStatus)
        assertEquals(extras, entity.toModel())
    }

    @Test
    fun `default extras round trip keeps nulls and PENDING`() {
        val extras = SegmentExtras(segmentId = 4)

        val entity = extras.toEntity()

        assertEquals("PENDING", entity.hrStatus)
        assertNull(entity.photosScannedAt)
        assertNull(entity.calories)
        assertEquals(extras, entity.toModel())
    }

    @Test
    fun `every hr status survives as its name`() {
        for (status in HrStatus.entries) {
            val entity = SegmentExtras(segmentId = 1, hrStatus = status).toEntity()
            assertEquals(status.name, entity.hrStatus)
            assertEquals(status, entity.toModel().hrStatus)
        }
    }

    @Test
    fun `heart rate sample round trip`() {
        val sample = HeartRateSample(timeMillis = 12_345L, bpm = 133)

        val entity = sample.toEntity(segmentId = 4)

        assertEquals(4L, entity.segmentId)
        assertEquals(0L, entity.id)
        assertEquals(sample, entity.toModel())
    }

    @Test
    fun `simplified route stores encoded points and bounding box`() {
        val entry = CumulativeEntry(
            segmentId = 99, // the segmentId argument wins
            localDate = LocalDate.of(2026, 10, 18),
            distanceM = 15_000.0,
            movingTimeMs = 3_600_000L,
            points = listOf(GeoPoint(37.5, 127.0), GeoPoint(37.6, 126.9), GeoPoint(37.55, 127.2)),
        )

        val entity = entry.toEntity(segmentId = 4)

        assertEquals(4L, entity.segmentId)
        assertEquals("2026-10-18", entity.localDate)
        assertEquals(37.5, entity.minLat)
        assertEquals(126.9, entity.minLon)
        assertEquals(37.6, entity.maxLat)
        assertEquals(127.2, entity.maxLon)
        assertEquals(entry.copy(segmentId = 4), entity.toModel())
    }

    @Test
    fun `simplified route without points has no bounding box`() {
        val entry = CumulativeEntry(4, LocalDate.of(2026, 10, 18), 0.0, 0L, emptyList())

        val entity = entry.toEntity(segmentId = 4)

        assertEquals("", entity.points)
        assertNull(entity.minLat)
        assertNull(entity.maxLon)
        assertEquals(entry, entity.toModel())
    }
}
