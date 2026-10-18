package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRead
import com.cowork.bikerecoder.core.post.HrRetryPolicy
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoCandidate
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

class PostProcessorTest {
    private val rides = FakeRideStore(FakeTripStore())
    private val store = FakePostStore { rides.segments(TRIP) }
    private val photos = FakePhotoSource()
    private val heartRate = FakeHeartRateSource()
    private val warnings = mutableListOf<String>()
    private var now = 0L

    private fun TestScope.processor(
        postStore: PostStore = store,
        photoSource: PhotoSource = photos,
        hrSource: HeartRateSource = heartRate,
        scope: CoroutineScope = backgroundScope,
    ) = PostProcessor(
        rides, postStore, photoSource, hrSource, clock = { now }, scope = scope,
        warnLog = { message, _ -> warnings += message },
    )

    /** A ride with a point every 10 s on a straight line from [startedAt] to [endedAt] (open while [endedAt] is null). */
    private suspend fun ride(startedAt: Long = START, endedAt: Long? = END): RideSegment {
        val id = rides.openSegment(TRIP, startedAt, DATE)
        val last = endedAt ?: (startedAt + TEN_MIN)
        val points = (0..((last - startedAt) / 10_000L)).map { i ->
            TrackPoint(startedAt + i * 10_000L, GeoPoint(37.5 + i * 0.0001, 127.0), null, 5f, 5f)
        }
        rides.appendPoints(id, points, TOTALS)
        if (endedAt != null) rides.closeSegment(id, endedAt, TOTALS)
        return rides.segment(id)!!
    }

    private fun extras(segment: RideSegment) = store.extrasById.value[segment.id]

    private suspend fun photosOf(segment: RideSegment) = store.observePhotos(listOf(segment.id)).first()

    /** One sample a minute over the ten-minute ride: enough for "detailed". */
    private fun samples(start: Long = START, bpm: (Int) -> Int = { 100 + it }) =
        (0 until 10).map { HeartRateSample(start + it * 60_000L, bpm(it)) }

    @Test
    fun skipsOpenSegment() = runTest {
        val open = ride(endedAt = null)
        photos.photos = listOf(PhotoCandidate(1, START + 60_000L, exif = null))
        heartRate.reads = { _, _ -> HrRead.Samples(samples(), 50.0) }
        // A store that (wrongly) lists the open segment too: the processor still leaves it alone.
        val listsOpen = object : PostStore by store {
            override suspend fun segmentsNeedingWork(now: Long) = rides.segments(TRIP)
        }
        now = START + 60 * 60_000L

        processor(postStore = listsOpen).runOnce()

        assertEquals(emptyList<Pair<Long, Long>>(), photos.queries)
        assertEquals(emptyList<Pair<Long, Long>>(), heartRate.calls)
        assertNull(extras(open))
        assertTrue(store.simplified.isEmpty())
        assertTrue(store.photos.value.isEmpty())
    }

    @Test
    fun placesPhotosAndKeepsScanningForTwentyFourHours() = runTest {
        val segment = ride()
        val onTrack = PhotoCandidate(1, START + 60_000L, exif = null) // the point at 60 s (index 6)
        val withExif = PhotoCandidate(2, END + 4 * 60_000L, exif = GeoPoint(35.0, 129.0))
        val tooEarly = PhotoCandidate(3, START - 6 * 60_000L, exif = null)
        photos.photos = listOf(onTrack, withExif, tooEarly)
        val p = processor()
        now = END + 60_000L

        p.runOnce()

        assertEquals(listOf(START - 5 * 60_000L to END + 5 * 60_000L), photos.queries)
        val placed = photosOf(segment)
        assertEquals(listOf(1L, 2L), placed.map { it.mediaId })
        assertEquals(GeoPoint(37.5 + 6 * 0.0001, 127.0), placed[0].point)
        assertEquals(PhotoPositionSource.TRACK, placed[0].source)
        assertEquals(GeoPoint(35.0, 129.0), placed[1].point)
        assertEquals(PhotoPositionSource.EXIF, placed[1].source)
        assertEquals(now, extras(segment)!!.photosScannedAt)
        assertFalse(extras(segment)!!.photoScanDone)

        // A photo saved late still turns up while the 24 hours last (the limit itself included).
        photos.photos = photos.photos + PhotoCandidate(4, END + 60_000L, exif = null)
        now = END + DAY

        p.runOnce()

        assertEquals(listOf(1L, 4L, 2L), photosOf(segment).map { it.mediaId })
        assertEquals(now, extras(segment)!!.photosScannedAt)
        assertFalse(extras(segment)!!.photoScanDone)
    }

    @Test
    fun photoScanStopsAfterTwentyFourHours() = runTest {
        val segment = ride()
        photos.photos = listOf(PhotoCandidate(1, START + 60_000L, exif = null))
        val p = processor()
        now = END + DAY + 1

        p.runOnce()

        assertEquals(listOf(1L), photosOf(segment).map { it.mediaId })
        assertTrue(extras(segment)!!.photoScanDone)

        photos.photos = photos.photos + PhotoCandidate(2, START + 120_000L, exif = null)
        p.runOnce()

        assertEquals(1, photos.queries.size)
        assertEquals(listOf(1L), photosOf(segment).map { it.mediaId })
    }

    @Test
    fun noPhotoAccessLeavesScanUndone() = runTest {
        val segment = ride()
        photos.access = PhotoAccess.NONE
        photos.photos = listOf(PhotoCandidate(1, START + 60_000L, exif = null))
        val p = processor()
        now = END + DAY + 1

        p.runOnce()

        assertEquals(emptyList<Pair<Long, Long>>(), photos.queries)
        assertFalse(extras(segment)!!.photoScanDone)
        assertNull(extras(segment)!!.photosScannedAt)
        assertEquals(listOf(segment.id), store.segmentsNeedingWork(now).map { it.id })

        // Allowing photos later still finds the ride's photos, even past 24 hours.
        photos.access = PhotoAccess.FULL
        p.runOnce()

        assertEquals(listOf(1L), photosOf(segment).map { it.mediaId })
        assertTrue(extras(segment)!!.photoScanDone)
    }

    @Test
    fun heartRatePendingThenReady() = runTest {
        val segment = ride()
        val p = processor()
        val firstTry = END + 60_000L
        now = firstTry

        p.runOnce()

        assertEquals(listOf(START to END), heartRate.calls)
        var e = extras(segment)!!
        assertEquals(HrStatus.PENDING, e.hrStatus)
        assertEquals(firstTry, e.hrFirstTriedAt)
        assertEquals(firstTry, e.hrLastTriedAt)

        heartRate.reads = { _, _ -> HrRead.Samples(samples().reversed(), 85.5) }
        now = END + 2 * 60 * 60_000L

        p.runOnce()

        e = extras(segment)!!
        assertEquals(HrStatus.READY, e.hrStatus)
        assertEquals(firstTry, e.hrFirstTriedAt)
        assertEquals(now, e.hrLastTriedAt)
        assertEquals(105, e.avgBpm) // (100 + … + 109) / 10 = 104.5, rounded
        assertEquals(109, e.maxBpm)
        assertEquals(85.5, e.calories)
        assertTrue(e.hrDetailed)
        assertEquals(samples(), store.samples(segment.id))

        // READY is not read again.
        p.runOnce()
        assertEquals(2, heartRate.calls.size)
    }

    @Test
    fun heartRateSummaryWithFewSamplesIsNotDetailed() = runTest {
        val segment = ride()
        heartRate.reads = { _, _ -> HrRead.Samples(listOf(HeartRateSample(START, 90), HeartRateSample(END, 130)), null) }
        now = END + 60_000L

        processor().runOnce()

        val e = extras(segment)!!
        assertEquals(HrStatus.READY, e.hrStatus)
        assertEquals(110, e.avgBpm)
        assertEquals(130, e.maxBpm)
        assertNull(e.calories)
        assertFalse(e.hrDetailed)
    }

    @Test
    fun heartRateEmptyAfterLimitIsNone() = runTest {
        val segment = ride()
        val p = processor()
        now = END + 60_000L

        p.runOnce()
        assertEquals(HrStatus.PENDING, extras(segment)!!.hrStatus)

        // Still PENDING past the limit, so it is read once more and becomes NONE.
        now = END + DAY + 1
        p.runOnce()

        assertEquals(2, heartRate.calls.size)
        assertEquals(HrStatus.NONE, extras(segment)!!.hrStatus)
        assertTrue(store.samples(segment.id).isEmpty())

        p.runOnce()
        assertEquals(2, heartRate.calls.size)
    }

    @Test
    fun noPermissionStaysPendingAndIsRetried() = runTest {
        val segment = ride()
        heartRate.permitted = false
        val p = processor()
        now = END + 60_000L

        p.runOnce()

        // R8: not read without permission, and not FAILED either — only the attempt is noted
        assertEquals(emptyList<Pair<Long, Long>>(), heartRate.calls)
        var e = extras(segment)!!
        assertEquals(HrStatus.PENDING, e.hrStatus)
        assertEquals(now, e.hrLastTriedAt)
        assertNull(e.hrFirstTriedAt)

        heartRate.permitted = true
        heartRate.availability = HealthAvailability.UPDATE_REQUIRED
        now = END + 2 * 60_000L
        p.runOnce()

        // nor while Health Connect is unavailable
        assertEquals(emptyList<Pair<Long, Long>>(), heartRate.calls)
        assertEquals(HrStatus.PENDING, extras(segment)!!.hrStatus)
        assertEquals(now, extras(segment)!!.hrLastTriedAt)

        heartRate.availability = HealthAvailability.AVAILABLE
        heartRate.reads = { _, _ -> HrRead.Samples(samples(), null) }
        now = END + 3 * 60_000L
        p.runOnce()

        assertEquals(listOf(START to END), heartRate.calls)
        e = extras(segment)!!
        assertEquals(HrStatus.READY, e.hrStatus)
        assertEquals(now, e.hrFirstTriedAt)
        assertEquals(now, e.hrLastTriedAt)
    }

    @Test
    fun oldPendingSegmentWithoutPermissionStaysPendingThenResolvesWhenGranted() = runTest {
        // V1.1.0 rides seen for the first time after the upgrade, before the Health Connect card was ever shown
        val withHeartRate = ride(START, END)
        val without = ride(START + 60 * 60_000L, END + 60 * 60_000L)
        heartRate.permitted = false
        heartRate.reads = { from, _ -> if (from == START) HrRead.Samples(samples(), 40.0) else HrRead.Empty }
        val p = processor()
        now = END + 3 * DAY

        p.runOnce()

        assertEquals(emptyList<Pair<Long, Long>>(), heartRate.calls)
        for (segment in listOf(withHeartRate, without)) {
            assertEquals(HrStatus.PENDING, extras(segment)!!.hrStatus)
            assertEquals(now, extras(segment)!!.hrLastTriedAt)
        }

        heartRate.permitted = true
        now += 60_000L
        p.runOnce()

        assertEquals(2, heartRate.calls.size)
        assertEquals(HrStatus.READY, extras(withHeartRate)!!.hrStatus)
        assertEquals(40.0, extras(withHeartRate)!!.calories)
        assertEquals(HrStatus.NONE, extras(without)!!.hrStatus)
    }

    @Test
    fun failedWithinLimitIsRetried() = runTest {
        val segment = ride()
        heartRate.reads = { _, _ -> HrRead.Failed } // an actual read error from a permitted source
        val p = processor()
        now = END + 60_000L
        p.runOnce()
        assertEquals(HrStatus.FAILED, extras(segment)!!.hrStatus)

        heartRate.reads = { _, _ -> HrRead.Samples(samples(), null) }
        now = END + 2 * 60_000L
        p.runOnce() // within the limit FAILED is read again
        assertEquals(HrStatus.READY, extras(segment)!!.hrStatus)
        assertEquals(2, heartRate.calls.size)
    }

    @Test
    fun failedPastLimitIsNotRetried() = runTest {
        val segment = ride()
        heartRate.reads = { _, _ -> HrRead.Failed }
        val p = processor()
        now = END + 60_000L
        p.runOnce()
        assertEquals(HrStatus.FAILED, extras(segment)!!.hrStatus)

        heartRate.reads = { _, _ -> HrRead.Samples(samples(), null) }
        now = END + DAY + 1
        p.runOnce() // still listed (photo scan not done yet), but FAILED is past its retries

        assertEquals(1, heartRate.calls.size)
        assertEquals(HrStatus.FAILED, extras(segment)!!.hrStatus)
    }

    @Test
    fun heartRateFailureDoesNotStopPhotos() = runTest {
        val older = ride(START, END)
        val newer = ride(START + DAY, END + DAY)
        photos.photos = listOf(
            PhotoCandidate(1, START + 60_000L, exif = null),
            PhotoCandidate(2, START + DAY + 60_000L, exif = null),
        )
        val throwing = object : HeartRateSource by heartRate {
            override suspend fun read(from: Long, to: Long): HrRead = throw IllegalStateException("binder died")
        }
        now = END + DAY + 60_000L

        processor(hrSource = throwing).runOnce()

        for ((segment, mediaId) in listOf(older to 1L, newer to 2L)) {
            assertEquals(listOf(mediaId), photosOf(segment).map { it.mediaId })
            val e = extras(segment)!!
            assertEquals(HrStatus.FAILED, e.hrStatus)
            assertEquals(now, e.hrLastTriedAt)
            assertTrue(e.simplifiedDone)
            assertNotNull(store.simplified[segment.id])
        }
        assertEquals(2, warnings.size)
    }

    @Test
    fun photoFailureDoesNotStopHeartRateOrRoute() = runTest {
        val segment = ride()
        photos.failure = IOException("MediaStore query failed")
        heartRate.reads = { _, _ -> HrRead.Samples(samples(), null) }
        val p = processor()
        now = END + DAY + 1

        p.runOnce()

        val e = extras(segment)!!
        assertFalse(e.photoScanDone) // looked for again next time, silently
        assertNull(e.photosScannedAt)
        assertEquals(HrStatus.READY, e.hrStatus)
        assertTrue(e.simplifiedDone)
        assertEquals(1, warnings.size)

        photos.failure = null
        photos.photos = listOf(PhotoCandidate(1, START + 60_000L, exif = null))
        p.runOnce()

        assertEquals(listOf(1L), photosOf(segment).map { it.mediaId })
        assertTrue(extras(segment)!!.photoScanDone)
    }

    @Test
    fun refetchIgnoresLimitAndOverwrites() = runTest {
        val segment = ride()
        heartRate.reads = { _, _ -> HrRead.Samples(samples(), 50.0) }
        val p = processor()
        val firstTry = END + 60_000L
        now = firstTry
        p.runOnce()
        assertEquals(HrStatus.READY, extras(segment)!!.hrStatus)

        val newer = samples { 150 }
        heartRate.reads = { _, _ -> HrRead.Samples(newer, 70.0) }
        now = END + 3 * DAY

        p.refetchHeartRate(segment.id)

        assertEquals(2, heartRate.calls.size)
        val e = extras(segment)!!
        assertEquals(HrStatus.READY, e.hrStatus)
        assertEquals(150, e.avgBpm)
        assertEquals(150, e.maxBpm)
        assertEquals(70.0, e.calories)
        assertEquals(firstTry, e.hrFirstTriedAt)
        assertEquals(now, e.hrLastTriedAt)
        assertEquals(newer, store.samples(segment.id))
    }

    /** Review minor 3: a read cancelled while it writes still stores samples and their summary together. */
    @Test
    fun aCancelledRefetchStillStoresSamplesAndSummaryTogether() = runTest {
        val segment = ride()
        now = END + 60_000L
        heartRate.reads = { _, _ -> HrRead.Samples(samples(), 50.0) }
        val writing = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val slowStore = object : PostStore by store {
            override suspend fun replaceSamples(segmentId: Long, samples: List<HeartRateSample>) {
                store.replaceSamples(segmentId, samples)
                writing.complete(Unit)
                gate.await()
            }
        }
        val p = processor(postStore = slowStore)

        val job = launch { p.refetchHeartRate(segment.id) }
        writing.await()
        job.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(samples(), store.samplesById[segment.id])
        assertEquals(HrStatus.READY, extras(segment)?.hrStatus, "the summary of the stored samples was saved too")
        assertEquals(105, extras(segment)?.avgBpm)
    }

    @Test
    fun refetchTurnsNoneIntoReady() = runTest {
        val segment = ride()
        val p = processor()
        now = END + DAY + 1
        p.runOnce()
        assertEquals(HrStatus.NONE, extras(segment)!!.hrStatus)

        heartRate.reads = { _, _ -> HrRead.Samples(samples(), null) }
        now = END + 5 * DAY
        p.refetchHeartRate(segment.id)

        assertEquals(HrStatus.READY, extras(segment)!!.hrStatus)
        assertEquals(samples(), store.samples(segment.id))
    }

    @Test
    fun refetchFailureKeepsReadyData() = runTest {
        val segment = ride()
        heartRate.reads = { _, _ -> HrRead.Samples(samples(), 50.0) }
        val p = processor()
        now = END + 60_000L
        p.runOnce()
        val ready = extras(segment)!!

        // R7: a failed read never throws away heart rate already read.
        heartRate.reads = { _, _ -> HrRead.Failed }
        now = END + 2 * DAY
        p.refetchHeartRate(segment.id)

        assertEquals(ready.copy(hrLastTriedAt = now), extras(segment))
        assertEquals(samples(), store.samples(segment.id))

        // Nor does a missing permission (R8: not read at all).
        heartRate.permitted = false
        now = END + 3 * DAY
        p.refetchHeartRate(segment.id)

        assertEquals(2, heartRate.calls.size)
        assertEquals(ready.copy(hrLastTriedAt = now), extras(segment))
        assertEquals(samples(), store.samples(segment.id))
    }

    @Test
    fun refetchEmptyKeepsReadyData() = runTest {
        val segment = ride()
        heartRate.reads = { _, _ -> HrRead.Samples(samples(), 50.0) }
        val p = processor()
        now = END + 60_000L
        p.runOnce()
        val ready = extras(segment)!!

        // R7: Empty, within the limit (would be PENDING) and past it (would be NONE), keeps READY and its data.
        heartRate.reads = { _, _ -> HrRead.Empty }
        for (at in listOf(END + 2 * 60 * 60_000L, END + 2 * DAY)) {
            now = at
            p.refetchHeartRate(segment.id)

            assertEquals(ready.copy(hrLastTriedAt = now), extras(segment))
            assertEquals(samples(), store.samples(segment.id))
        }
    }

    @Test
    fun refetchLeavesOpenOrMissingSegmentAlone() = runTest {
        val open = ride(endedAt = null)
        val p = processor()
        now = END

        p.refetchHeartRate(open.id)
        p.refetchHeartRate(999L)

        assertEquals(emptyList<Pair<Long, Long>>(), heartRate.calls)
        assertNull(extras(open))
    }

    @Test
    fun simplifiesOnce() = runTest {
        val segment = ride()
        val p = processor()
        now = END + 60_000L

        p.runOnce()

        val entry = store.simplified[segment.id]!!
        // a straight line keeps only its ends
        assertEquals(listOf(GeoPoint(37.5, 127.0), GeoPoint(37.5 + 60 * 0.0001, 127.0)), entry.points)
        assertEquals(DATE, entry.localDate)
        assertEquals(TOTALS.distanceM, entry.distanceM)
        assertEquals(TOTALS.movingTimeMs, entry.movingTimeMs)
        assertTrue(extras(segment)!!.simplifiedDone)

        // Still listed for photos and heart rate, but the route is not simplified again.
        store.simplified.clear()
        p.runOnce()
        assertTrue(store.simplified.isEmpty())
    }

    @Test
    fun segmentDeletedMidRunIsSkipped() = runTest {
        val older = ride(START, END)
        val newer = ride(START + DAY, END + DAY)
        photos.photos = listOf(
            PhotoCandidate(1, START + 60_000L, exif = null),
            PhotoCandidate(2, START + DAY + 60_000L, exif = null),
        )
        // The newer ride (processed first) is deleted while its photos are being looked for.
        val deleting = object : PhotoSource by photos {
            override suspend fun photos(from: Long, to: Long): List<PhotoCandidate> {
                if (from < newer.startedAt && to > newer.startedAt) rides.deleteSegment(newer.id)
                return photos.photos(from, to)
            }
        }
        now = END + DAY + 60_000L

        processor(photoSource = deleting).runOnce()

        assertNull(extras(newer))
        assertNull(store.simplified[newer.id])
        assertEquals(listOf(older.startedAt to older.endedAt), heartRate.calls)
        assertEquals(listOf(1L), store.photos.value.map { it.mediaId })
        assertNotNull(extras(older))
        assertNotNull(store.simplified[older.id])
        assertEquals(emptyList<String>(), warnings) // a deleted ride is not an error
    }

    @Test
    fun finishedRunsCountsEveryEndedRun() = runTest {
        ride()
        now = END + 60_000L
        val p = processor()
        assertEquals(0, p.finishedRuns.value)

        p.runOnce()
        assertEquals(1, p.finishedRuns.value)

        // A run that fails still ends; a run that is still going has not.
        val gate = CompletableDeferred<Unit>()
        store.beforeList = {
            gate.await()
            throw IllegalStateException("database closed")
        }
        p.request()
        runCurrent()
        assertTrue(p.running.value)
        assertEquals(1, p.finishedRuns.value)
        gate.complete(Unit)
        runCurrent()
        assertFalse(p.running.value)
        assertEquals(2, p.finishedRuns.value)
    }

    @Test
    fun busyFromRequestUntilNoPassIsLeft() = runTest {
        ride()
        now = END + 60_000L
        val gate = CompletableDeferred<Unit>()
        store.beforeList = { gate.await() }
        val p = processor()
        assertFalse(p.busy.value)

        p.request()
        assertTrue(p.busy.value, "busy as soon as asked, before the pass starts")
        assertFalse(p.running.value)
        runCurrent()
        p.request() // waits for the running pass
        gate.complete(Unit)
        runCurrent()

        assertFalse(p.busy.value)
        assertEquals(2, p.finishedRuns.value)
    }

    @Test
    fun routesSavedCountsEachSimplifiedRoute() = runTest {
        ride(START, END)
        ride(START + DAY, END + DAY)
        now = END + DAY + 60_000L
        val p = processor()
        assertEquals(0, p.routesSaved.value)

        p.runOnce()
        assertEquals(2, p.routesSaved.value)

        p.runOnce() // nothing left to simplify
        assertEquals(2, p.routesSaved.value)
    }

    @Test
    fun requestsDoNotOverlap() = runTest {
        ride()
        now = END + 60_000L
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        var active = 0
        var maxActive = 0
        store.beforeList = {
            runs++
            active++
            maxActive = maxOf(maxActive, active)
            try {
                gate.await()
            } finally {
                active--
            }
        }
        val p = processor()

        p.request()
        runCurrent()
        assertEquals(1, runs)
        assertTrue(p.running.value)

        repeat(3) { p.request() }
        runCurrent()
        assertEquals(1, runs)

        gate.complete(Unit)
        runCurrent()

        assertEquals(2, runs) // the three requests made during the run became one more run
        assertEquals(1, maxActive) // never two at once
        assertFalse(p.running.value)

        p.request()
        runCurrent()
        assertEquals(3, runs)
    }

    @Test
    fun runFailureDoesNotEscape() = runTest {
        ride()
        store.beforeList = { throw IllegalStateException("database closed") }
        val p = processor()

        p.request() // an escaping exception would fail the test through backgroundScope
        runCurrent()
        p.runOnce()

        assertFalse(p.running.value)
        assertEquals(2, warnings.size)
    }

    @Test
    fun segmentFailureSkipsOnlyThatSegment() = runTest {
        val older = ride(START, END)
        val newer = ride(START + DAY, END + DAY)
        // Reading the newer ride's progress fails before any step starts (e.g. an unreadable row).
        val failing = object : PostStore by store {
            override suspend fun extras(segmentId: Long): SegmentExtras? =
                if (segmentId == newer.id) throw IllegalStateException("bad row") else store.extras(segmentId)
        }
        now = END + DAY + 60_000L

        processor(postStore = failing).runOnce()

        assertNull(extras(newer))
        assertNull(store.simplified[newer.id])
        assertNotNull(extras(older)) // newest first, so the older ride comes after the failing one
        assertNotNull(store.simplified[older.id])
        assertEquals(1, warnings.size)
    }

    @Test
    fun photoAccessFailureStaysInPhotoStep() = runTest {
        val segment = ride()
        val failing = object : PhotoSource by photos {
            override fun access(): PhotoAccess = throw SecurityException("no package")
        }
        heartRate.reads = { _, _ -> HrRead.Samples(samples(), null) }
        now = END + DAY + 1

        processor(photoSource = failing).runOnce()

        val e = extras(segment)!!
        assertFalse(e.photoScanDone)
        assertEquals(HrStatus.READY, e.hrStatus)
        assertTrue(e.simplifiedDone)
        assertEquals(1, warnings.size)
    }

    @Test
    fun cancellationThrownBySourceIsAFailureNotTheEndOfThePass() = runTest {
        val older = ride(START, END)
        val newer = ride(START + DAY, END + DAY)
        // e.g. a TimeoutCancellationException from a withTimeout inside the source, while this pass is not cancelled
        val timingOut = object : HeartRateSource by heartRate {
            override suspend fun read(from: Long, to: Long): HrRead =
                throw kotlinx.coroutines.CancellationException("timed out")
        }
        now = END + DAY + 60_000L

        processor(hrSource = timingOut).runOnce()

        for (segment in listOf(older, newer)) {
            assertEquals(HrStatus.FAILED, extras(segment)!!.hrStatus)
            assertTrue(extras(segment)!!.simplifiedDone)
        }
        assertEquals(2, warnings.size)
    }

    @Test
    fun cancelledPassResetsRunningAndReleasesTheLock() = runTest {
        val segment = ride()
        now = END + 60_000L
        val never = CompletableDeferred<Unit>()
        store.beforeList = { never.await() }
        val p = processor()

        val pass = launch { p.runOnce() }
        runCurrent()
        assertTrue(p.running.value)

        pass.cancel()
        runCurrent()

        assertTrue(pass.isCancelled)
        assertFalse(p.running.value)
        assertEquals(emptyList<String>(), warnings) // a real cancellation is not a failure

        store.beforeList = {}
        p.runOnce()
        assertNotNull(extras(segment))
    }

    @Test
    fun pruneSurvivesGalleryFailure() = runTest {
        val segment = ride()
        photos.photos = listOf(PhotoCandidate(1, START + 60_000L, exif = null))
        now = END + 60_000L
        processor().runOnce()
        val failing = object : PhotoSource by photos {
            override suspend fun existing(mediaIds: Collection<Long>): Set<Long> =
                throw IllegalStateException("MediaStore query returned no cursor")
        }

        processor(photoSource = failing).pruneDeletedPhotos(listOf(segment.id))

        assertEquals(listOf(1L), photosOf(segment).map { it.mediaId }) // nothing deleted
        assertEquals(1, warnings.size)
    }

    @Test
    fun pruneDeletesMissingPhotos() = runTest {
        val a = ride(START, END)
        val b = ride(START + DAY, END + DAY)
        photos.photos = listOf(
            PhotoCandidate(1, START + 60_000L, exif = null),
            PhotoCandidate(2, START + 120_000L, exif = null),
            PhotoCandidate(3, START + DAY + 60_000L, exif = null),
        )
        val p = processor()
        now = END + DAY + 60_000L
        p.runOnce()
        assertEquals(listOf(1L, 2L, 3L), store.photos.value.map { it.mediaId }.sorted())

        // Without access to all photos nothing can be known to be gone: nothing is deleted.
        photos.photos = emptyList()
        photos.access = PhotoAccess.PARTIAL
        p.pruneDeletedPhotos(listOf(a.id, b.id))
        assertEquals(listOf(1L, 2L, 3L), store.photos.value.map { it.mediaId }.sorted())

        photos.access = PhotoAccess.FULL
        photos.photos = listOf(PhotoCandidate(1, START + 60_000L, exif = null))
        p.pruneDeletedPhotos(listOf(a.id))

        assertEquals(listOf(1L), photosOf(a).map { it.mediaId })
        assertEquals(listOf(3L), photosOf(b).map { it.mediaId }) // only the screen's segments are checked
    }

    private companion object {
        const val TRIP = 1L
        const val START = 1_760_000_000_000L
        const val TEN_MIN = 10 * 60_000L
        const val END = START + TEN_MIN
        const val DAY = HrRetryPolicy.GIVE_UP_MS
        val DATE: LocalDate = LocalDate.of(2026, 10, 18)
        val TOTALS = SegmentTotals.ZERO.copy(distanceM = 670.0, movingTimeMs = TEN_MIN)
    }
}
