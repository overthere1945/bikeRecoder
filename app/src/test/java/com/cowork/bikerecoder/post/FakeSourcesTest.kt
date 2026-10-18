package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRead
import com.cowork.bikerecoder.core.post.PhotoCandidate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeSourcesTest {
    private val a = PhotoCandidate(mediaId = 1, takenAt = 3_000L, exif = null)
    private val b = PhotoCandidate(mediaId = 2, takenAt = 1_000L, exif = GeoPoint(37.5, 127.0))
    private val c = PhotoCandidate(mediaId = 3, takenAt = 9_000L, exif = null)

    @Test
    fun `photos returns the inclusive window in time order`() = runTest {
        val source = FakePhotoSource(PhotoAccess.FULL, listOf(a, b, c))

        assertEquals(listOf(b, a), source.photos(1_000L, 3_000L))
        assertEquals(listOf(1_000L to 3_000L), source.queries)
    }

    @Test
    fun `no access finds nothing`() = runTest {
        val source = FakePhotoSource(PhotoAccess.NONE, listOf(a, b, c))

        assertEquals(PhotoAccess.NONE, source.access())
        assertEquals(emptyList<PhotoCandidate>(), source.photos(0L, 10_000L))
    }

    @Test
    fun `existing drops photos removed from the gallery`() = runTest {
        val source = FakePhotoSource(PhotoAccess.FULL, listOf(a, b, c))
        source.photos = listOf(a, c)

        assertEquals(setOf(1L, 3L), source.existing(listOf(1L, 2L, 3L, 4L)))
    }

    @Test
    fun `existing keeps every id when access cannot prove a photo is gone`() = runTest {
        val partial = FakePhotoSource(PhotoAccess.PARTIAL, listOf(a))
        val none = FakePhotoSource(PhotoAccess.NONE, emptyList())

        assertEquals(setOf(1L, 2L), partial.existing(listOf(1L, 2L)))
        assertEquals(setOf(1L, 2L), none.existing(listOf(1L, 2L)))
    }

    @Test
    fun `photo failure is thrown from photos`() = runTest {
        val source = FakePhotoSource(PhotoAccess.FULL, listOf(a))
        source.failure = IllegalStateException("gallery down")

        val thrown = runCatching { source.photos(0L, 10_000L) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `heart rate read passes the window and returns what reads gives`() = runTest {
        val samples = HrRead.Samples(listOf(HeartRateSample(1_000L, 120)), calories = 42.0)
        val source = FakeHeartRateSource(HealthAvailability.AVAILABLE, permitted = true, reads = { _, _ -> samples })

        assertTrue(source.hasPermissions())
        assertEquals(samples, source.read(1_000L, 2_000L))
        assertEquals(listOf(1_000L to 2_000L), source.calls)
    }

    @Test
    fun `heart rate read fails without permission or without Health Connect`() = runTest {
        val ok = HrRead.Samples(listOf(HeartRateSample(1_000L, 120)), calories = null)
        val denied = FakeHeartRateSource(HealthAvailability.AVAILABLE, permitted = false, reads = { _, _ -> ok })
        val missing = FakeHeartRateSource(HealthAvailability.NOT_INSTALLED, permitted = true, reads = { _, _ -> ok })
        val outdated = FakeHeartRateSource(HealthAvailability.UPDATE_REQUIRED, permitted = true, reads = { _, _ -> ok })

        assertFalse(denied.hasPermissions())
        assertFalse(missing.hasPermissions())
        assertEquals(HrRead.Failed, denied.read(0L, 1L))
        assertEquals(HrRead.Failed, missing.read(0L, 1L))
        assertEquals(HrRead.Failed, outdated.read(0L, 1L))
    }

    @Test
    fun `heart rate read keeps the source contract - empty is Empty and samples are sorted`() = runTest {
        val unsorted = listOf(HeartRateSample(3_000L, 130), HeartRateSample(1_000L, 110), HeartRateSample(2_000L, 120))
        val empty = FakeHeartRateSource(reads = { _, _ -> HrRead.Samples(emptyList(), calories = 10.0) })
        val shuffled = FakeHeartRateSource(reads = { _, _ -> HrRead.Samples(unsorted, calories = 10.0) })

        assertEquals(HrRead.Empty, empty.read(0L, 5_000L))
        assertEquals(
            HrRead.Samples(unsorted.sortedBy { it.timeMillis }, calories = 10.0),
            shuffled.read(0L, 5_000L),
        )
    }

    @Test
    fun `hrReadOf turns no samples into Empty and sorts the rest by time`() {
        val unsorted = listOf(HeartRateSample(5_000L, 150), HeartRateSample(1_000L, 100))

        assertEquals(HrRead.Empty, hrReadOf(emptyList(), calories = 12.0))
        assertEquals(
            HrRead.Samples(listOf(HeartRateSample(1_000L, 100), HeartRateSample(5_000L, 150)), calories = 12.0),
            hrReadOf(unsorted, calories = 12.0),
        )
    }
}
