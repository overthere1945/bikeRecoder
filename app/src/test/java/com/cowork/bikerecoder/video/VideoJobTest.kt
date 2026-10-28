package com.cowork.bikerecoder.video

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.core.video.FramePlan
import com.cowork.bikerecoder.core.video.RideTimelineBuilder
import com.cowork.bikerecoder.core.video.VideoEstimate
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.offline.VideoMaps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

class VideoJobTest {

    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @AfterEach
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy {
        VideoJobStore(PreferenceDataStoreFactory.create(scope = scope(), produceFile = { File(dir, "video_job.preferences_pb") }))
    }
    private val backend = FakeBackend()
    private val maps = FakeMaps()
    private val media = FakeTripMedia()
    private val recording = MutableStateFlow(false)
    private var online = true
    private var servicesStarted = 0
    private val warnings = mutableListOf<String>()

    private val inputs = testInputs()
    private val short = VideoOptions(length = VideoLength.SHORT, photos = false, hud = true)
    private val plans = VideoTimeline.plan(inputs.timeline, inputs.photos, short, inputs.dayStarts)

    private val job by lazy {
        VideoJob(
            loadInputs = { if (it == TRIP) inputs else null },
            store = store,
            tripMedia = media,
            maps = maps,
            backend = backend,
            recording = recording,
            online = { online },
            regionOf = { regionOf(it) },
            startService = { servicesStarted++ },
            scope = scope(),
            log = { message, _ -> warnings += message },
            clock = { NOW },
            nanoTime = { backend.nanos },
        )
    }

    /** The region of the ride's first point (spec §3.8); none by default. */
    private var regionOf: suspend (GeoPoint) -> String? = { null }

    private fun awaitEnd(): VideoJobState = runBlocking {
        withTimeout(20_000) { job.state.first { it is VideoJobState.Done || it is VideoJobState.Failed || it is VideoJobState.Cancelled } }
    }

    private fun awaitIdle() = runBlocking { withTimeout(20_000) { while (job.isRunning) kotlinx.coroutines.delay(5) } }

    @Test
    fun happyPathSavesAndRecords() = runBlocking {
        backend.names += "bikeRecoder_20261025_0858.mp4"
        val options = short.copy(musicUri = "content://music/1")

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, options))
        val done = awaitEnd() as VideoJobState.Done

        val uri = backend.created.single()
        assertEquals("bikeRecoder_20261025_0858_2.mp4", backend.createdNames.single())
        assertEquals(listOf(uri), backend.published)
        assertEquals(emptyList<String>(), backend.deleted)
        val row = media.rows.single()
        assertEquals(TripMedia(row.id, TRIP, MediaKind.VIDEO, uri, NOW, VideoOptionsJson.encode(options)), row)
        assertEquals(row, done.media)
        assertFalse(done.downscaled)
        assertNull(store.pending())
        assertEquals(1, servicesStarted)
        val sink = backend.sinks.single()
        assertEquals(plans, sink.plans)
        // The music is made before the start and handed over before the first frame (ruling V7).
        assertEquals(plans.size * 1_000_000L / 24, backend.musicDurations.single())
        assertEquals("audio:content://music/1", sink.events.first())
        assertEquals(plans.size, sink.events.count { it == "frame" })
        assertEquals(listOf("finish", "close"), sink.events.takeLast(2))
    }

    /** The trip being made, for its progress card (only that trip's screens show it); none before or after. */
    @Test
    fun theRunningTripIsKnownWhileTheJobRuns() = runBlocking {
        assertNull(job.runningTripId.value)
        backend.gateAt = 3
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        backend.reached.await()
        assertEquals(TRIP, job.runningTripId.value)

        job.cancel()
        awaitEnd()
        awaitIdle()
        assertNull(job.runningTripId.value)

        // A refused start never names a trip.
        recording.value = true
        assertEquals(StartResult.RECORDING, job.startVideo(TRIP, short))
        assertNull(job.runningTripId.value)
    }

    @Test
    fun busyRefuses() = runBlocking {
        backend.gateAt = 3
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        backend.reached.await()

        assertEquals(StartResult.BUSY, job.startVideo(TRIP, short))
        assertEquals(StartResult.BUSY, job.startImage(TRIP, Aspect.PORTRAIT))
        assertEquals(1, backend.created.size)

        job.cancel()
        assertEquals(VideoJobState.Cancelled, awaitEnd())
        awaitIdle()
        backend.gateAt = -1
        assertEquals(StartResult.STARTED, job.startImage(TRIP, Aspect.PORTRAIT))
        assertTrue(awaitEnd() is VideoJobState.Done)
    }

    @Test
    fun recordingRefuses() = runBlocking {
        recording.value = true

        assertEquals(StartResult.RECORDING, job.startVideo(TRIP, short))
        assertEquals(StartResult.RECORDING, job.startImage(TRIP, Aspect.PORTRAIT))

        assertNothingStarted()
    }

    /**
     * Final review FIX-NOW 6: a recording that starts during the satellite probe (up to 10 s) or the music is refused as
     * RECORDING before anything is launched, and the claim is released.
     */
    @Test
    fun recordingThatStartsDuringTheProbeRefuses() = runBlocking {
        backend.onProbe = { recording.value = true }

        assertEquals(StartResult.RECORDING, job.startVideo(TRIP, short))
        assertNothingStarted()
        recording.value = false
        assertEquals(StartResult.RECORDING, job.startImage(TRIP, Aspect.PORTRAIT))
        assertNothingStarted()
        assertEquals(2, backend.probed.size)

        backend.onProbe = {}
        recording.value = false
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        assertTrue(awaitEnd() is VideoJobState.Done)
    }

    /** Final review FIX-NOW 7: [취소] while the title's region is looked up leaves nothing behind. */
    @Test
    fun cancelDuringTheRegionLookupLeavesNothing() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        regionOf = { entered.complete(Unit); awaitCancellation() }

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        entered.await()
        job.cancel()

        assertEquals(VideoJobState.Cancelled, awaitEnd())
        awaitIdle()
        assertNull(store.pending())
        assertEquals(emptyList<String>(), backend.created)
        assertEquals(emptyList<FakeSink>(), backend.sinks)
        assertEquals(listOf(TRIP), maps.deleted)
        assertNull(job.runningTripId.value)

        regionOf = { null }
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        assertTrue(awaitEnd() is VideoJobState.Done)
    }

    @Test
    fun startingARecordingStopsAndDeletes() = runBlocking {
        backend.gateAt = 5
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        backend.reached.await()

        recording.value = true

        assertEquals(VideoJobState.Failed("기록이 시작되어 영상 만들기를 멈췄습니다"), awaitEnd())
        awaitIdle()
        assertEquals(backend.created, backend.deleted)
        assertEquals(emptyList<String>(), backend.published)
        assertEquals(emptyList<TripMedia>(), media.rows)
        assertNull(store.pending())
        assertEquals(listOf(TRIP), maps.deleted)
        assertEquals("close", backend.sinks.single().events.last())
    }

    @Test
    fun cancelDeletesPartial() = runBlocking {
        backend.gateAt = 5
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        backend.reached.await()

        job.cancel()

        assertEquals(VideoJobState.Cancelled, awaitEnd())
        awaitIdle()
        assertEquals(1, backend.created.size)
        assertEquals(backend.created, backend.deleted)
        assertEquals(emptyList<TripMedia>(), media.rows)
        assertNull(store.pending())
        val events = backend.sinks.single().events
        assertEquals("close", events.last())
        assertFalse("finish" in events)
    }

    @Test
    fun failureDeletesPartialAndClearsPending() = runBlocking {
        backend.failAt = 5

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))

        assertEquals(VideoJobState.Failed("영상을 만들지 못했습니다"), awaitEnd())
        awaitIdle()
        assertEquals(backend.created, backend.deleted)
        assertEquals(emptyList<String>(), backend.published)
        assertEquals(emptyList<TripMedia>(), media.rows)
        assertNull(store.pending())
        assertEquals("close", backend.sinks.single().events.last())
        assertEquals(1, warnings.size)
    }

    @Test
    fun encoderFailureOnAFullDiskSaysNoSpace() = runBlocking {
        backend.failAt = 5
        backend.onFail = { backend.free = 1_000_000L }

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))

        assertEquals(VideoJobState.Failed("저장 공간이 부족합니다(약 36 MB 필요)"), awaitEnd())
        awaitIdle()
        assertEquals(backend.created, backend.deleted)
    }

    @Test
    fun noInternetRefusesBeforeWork() = runBlocking {
        online = false
        maps.available = false

        assertEquals(StartResult.NO_INTERNET, job.startVideo(TRIP, short))
        assertEquals(StartResult.NO_INTERNET, job.startImage(TRIP, Aspect.PORTRAIT))

        assertNothingStarted()
        assertEquals(2, maps.downloads.size)
        assertEquals("지도를 받으려면 인터넷이 필요합니다", StartResult.NO_INTERNET.message(0))
    }

    @Test
    fun offlineWithTheTripsOwnMapStarts() = runBlocking {
        online = false

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))

        assertTrue(awaitEnd() is VideoJobState.Done)
    }

    @Test
    fun noSpaceRefuses() = runBlocking {
        val needed = VideoJob.neededBytes(short)
        assertEquals(36_000_000L, needed)
        backend.free = needed - 1

        assertEquals(StartResult.NO_SPACE, job.startVideo(TRIP, short))
        assertEquals("저장 공간이 부족합니다(약 36 MB 필요)", StartResult.NO_SPACE.message(needed))

        backend.free = VideoJob.IMAGE_BYTES - 1
        assertEquals(StartResult.NO_SPACE, job.startImage(TRIP, Aspect.PORTRAIT))
        assertNothingStarted()

        backend.free = needed
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        assertTrue(awaitEnd() is VideoJobState.Done)
    }

    @Test
    fun musicUnreadableAskedBeforeStart() = runBlocking {
        backend.musicReadable = false

        assertEquals(StartResult.MUSIC_UNREADABLE, job.startVideo(TRIP, short.copy(musicUri = "content://music/broken")))

        assertNothingStarted()
        assertEquals("음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?", StartResult.MUSIC_UNREADABLE.message(0))
        // "음악 없이 만들기"
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        assertTrue(awaitEnd() is VideoJobState.Done)
        assertEquals("frame", backend.sinks.single().events.first { it != "audio:null" })
    }

    @Test
    fun nothingToMake() = runBlocking {
        assertEquals(StartResult.NOTHING_TO_MAKE, job.startVideo(TRIP + 1, short))
        assertEquals(StartResult.NOTHING_TO_MAKE, job.startImage(TRIP + 1, Aspect.PORTRAIT))
        assertNothingStarted()
    }

    @Test
    fun unfinishedJobIsOfferedAgain() = runBlocking {
        val killed = PendingJob(TRIP, MediaKind.VIDEO, VideoOptionsJson.encode(short), 1_000L, outputUri = "content://gallery/41")
        store.setPending(killed)

        val offered = job.unfinished()

        assertEquals(killed.copy(outputUri = null), offered)
        assertEquals(listOf("content://gallery/41"), backend.deleted)
        assertEquals(listOf(TRIP), maps.deleted)
        assertEquals(killed.copy(outputUri = null), store.pending())

        job.dismissUnfinished()
        assertNull(store.pending())
        assertNull(job.unfinished())
    }

    @Test
    fun noUnfinishedJobWhileOneRuns() = runBlocking {
        backend.gateAt = 2
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        backend.reached.await()

        assertNull(job.unfinished())
        job.dismissUnfinished()
        assertEquals(backend.created.single(), store.pending()?.outputUri)

        job.cancel()
        awaitEnd()
    }

    @Test
    fun videoRegionsDeletedAfterwards() = runBlocking {
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()
        awaitIdle()
        assertEquals(listOf(TRIP), maps.deleted)
        assertEquals(inputs.timeline.points.map { it.point }, maps.downloads.single())

        backend.failAt = 3
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()
        awaitIdle()
        assertEquals(listOf(TRIP, TRIP), maps.deleted)

        backend.failAt = -1
        maps.available = false
        assertEquals(StartResult.STARTED, job.startImage(TRIP, Aspect.PORTRAIT))
        assertEquals(VideoJobState.Failed("지도를 받으려면 인터넷이 필요합니다"), awaitEnd())
        awaitIdle()
        assertEquals(listOf(TRIP, TRIP, TRIP), maps.deleted)
        assertNull(store.pending())
    }

    @Test
    fun progressAndMinutesLeft() = runBlocking {
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()

        val seen = backend.sinks.single().states
        assertEquals(plans.size, seen.size)
        // Before the first frame: the default seconds per map frame (nothing measured on this phone yet, ruling FW2).
        assertEquals(VideoJobState.Rendering(0, VideoEstimate.minutes(plans, VideoJob.DEFAULT_SECONDS_PER_FRAME)), seen[0])
        for (k in 1 until plans.size) {
            val rest = plans.subList(k, plans.size)
            val reused = rest.count { it.reuseMap }
            // Measured: 2 s per map frame.
            val expected = VideoJobState.Rendering(k * 100 / plans.size, VideoEstimate.minutes(rest.size - reused, reused, 2.0))
            assertEquals(expected, seen[k], "frame $k")
        }
        assertTrue(seen.any { it is VideoJobState.Rendering && it.minutesLeft > 1 })
    }

    @Test
    fun theLastMeasuredSpeedEstimatesTheFirstFrame() = runBlocking {
        store.recordSpeed(1.5)

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()

        assertEquals(VideoJobState.Rendering(0, VideoEstimate.minutes(plans, 1.5)), backend.sinks.single().states[0])
    }

    @Test
    fun speedRecorded() = runBlocking {
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()

        assertEquals(2.0, store.secondsPerFrame())
    }

    @Test
    fun downscaledReported() = runBlocking {
        backend.downscale = true

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        val done = awaitEnd() as VideoJobState.Done

        assertTrue(done.downscaled)
        val at720 = camerasAt(720, 1280)
        val at1080 = camerasAt(1080, 1920)
        assertEquals(at720, backend.sinks.single().cameras)
        assertNotEquals(at1080.last(), at720.last())
    }

    @Test
    fun fullSizeCamerasUseTheOutroAboveTheCard() = runBlocking {
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()

        val overview = FrameRenderer.introCamera(inputs, 1080, 1920)
        val cameras = backend.sinks.single().cameras
        assertEquals(camerasAt(1080, 1920), cameras)
        assertNotEquals(overview, cameras.last())
    }

    /** Ruling C3: the sky view above the opening title, the outro above the closing card, the frame's own height. */
    private fun camerasAt(w: Int, h: Int) = CameraPath.cameras(
        plans, inputs.timeline, FrameRenderer.introCamera(inputs, w, h), FrameRenderer.outroCamera(inputs, w, h), heightPx = h,
    )

    @Test
    fun satelliteOkMakesSatellite() = runBlocking {
        backend.probe = SatelliteCheck.OK

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        val done = awaitEnd() as VideoJobState.Done

        assertEquals(listOf(true), backend.videoSatellite)
        assertFalse(done.satelliteFallback)
        // Spec §1.3: the tile under the start.
        assertEquals(listOf(inputs.timeline.points.first().point), backend.probed)
    }

    @Test
    fun satelliteFailureFallsBack() = runBlocking {
        backend.probe = SatelliteCheck.FAILED

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        val done = awaitEnd() as VideoJobState.Done

        assertEquals(listOf(false), backend.videoSatellite)
        assertTrue(done.satelliteFallback)
    }

    @Test
    fun noKeyUsesPlainWithoutNotice() = runBlocking {
        backend.probe = SatelliteCheck.NO_KEY

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        val done = awaitEnd() as VideoJobState.Done

        assertEquals(listOf(false), backend.videoSatellite)
        assertFalse(done.satelliteFallback)
    }

    @Test
    fun offlineStillNoInternet() = runBlocking {
        online = false
        maps.available = false

        assertEquals(StartResult.NO_INTERNET, job.startVideo(TRIP, short))
        assertEquals(StartResult.NO_INTERNET, job.startImage(TRIP, Aspect.PORTRAIT))

        assertEquals(emptyList<GeoPoint>(), backend.probed)
        assertNothingStarted()
    }

    /** The probe comes after the map check and before the music (spec §1.3). */
    @Test
    fun theProbeComesBeforeTheMusic() = runBlocking {
        backend.probe = SatelliteCheck.FAILED
        backend.musicReadable = false

        assertEquals(StartResult.MUSIC_UNREADABLE, job.startVideo(TRIP, short.copy(musicUri = "content://music/broken")))

        assertEquals(1, backend.probed.size)
        assertEquals(listOf("probe", "music"), backend.calls)
    }

    @Test
    fun imageUsesTheSameCheck() = runBlocking {
        backend.probe = SatelliteCheck.OK
        assertEquals(StartResult.STARTED, job.startImage(TRIP, Aspect.PORTRAIT))
        assertFalse((awaitEnd() as VideoJobState.Done).satelliteFallback)
        awaitIdle()

        backend.probe = SatelliteCheck.FAILED
        assertEquals(StartResult.STARTED, job.startImage(TRIP, Aspect.PORTRAIT))
        assertTrue((awaitEnd() as VideoJobState.Done).satelliteFallback)
        awaitIdle()

        backend.probe = SatelliteCheck.NO_KEY
        assertEquals(StartResult.STARTED, job.startImage(TRIP, Aspect.PORTRAIT))
        assertFalse((awaitEnd() as VideoJobState.Done).satelliteFallback)

        assertEquals(listOf(true, false, false), backend.imageSatellite)
        assertEquals(3, backend.probed.size)
    }

    @Test
    fun rideTitleGetsRegion() = runBlocking {
        val asked = mutableListOf<GeoPoint>()
        regionOf = { asked += it; "서울 강서구" }

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()
        awaitIdle()
        assertEquals(StartResult.STARTED, job.startImage(TRIP, Aspect.PORTRAIT))
        awaitEnd()

        assertEquals("아침 라이딩 · 서울 강서구", backend.videoInputs.single().rideTitle)
        assertEquals("아침 라이딩 · 서울 강서구", backend.imageInputs.single().rideTitle)
        assertEquals(List(2) { inputs.timeline.points.first().point }, asked)
    }

    /** Review Focus 3: a lookup that hangs is given up after 5 s; one that throws is ignored. Either way the job is made. */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun slowRegionLookupGivesTitleWithoutRegion() = runTest {
        regionOf = { delay(10_000); "서울 강서구" }
        val slow = virtualTimeJob(backgroundScope)

        assertEquals(StartResult.STARTED, slow.startVideo(TRIP, short))
        val done = slow.state.first { it is VideoJobState.Done || it is VideoJobState.Failed }

        assertTrue(done is VideoJobState.Done, done.toString())
        assertEquals("아침 라이딩", backend.videoInputs.single().rideTitle)
        assertEquals(5_000L, testScheduler.currentTime)

        regionOf = { throw IllegalStateException("lookup failed") }
        val failing = virtualTimeJob(backgroundScope)
        assertEquals(StartResult.STARTED, failing.startImage(TRIP, Aspect.PORTRAIT))
        val image = failing.state.first { it is VideoJobState.Done || it is VideoJobState.Failed }

        assertTrue(image is VideoJobState.Done, image.toString())
        assertEquals("아침 라이딩", backend.imageInputs.single().rideTitle)
    }

    /** A job on the test's virtual clock (its scope), so the 5 s limit passes at once. */
    private fun virtualTimeJob(scope: CoroutineScope) = VideoJob(
        loadInputs = { if (it == TRIP) inputs else null }, store = store, tripMedia = media, maps = maps, backend = backend,
        recording = recording, online = { true }, regionOf = { regionOf(it) }, startService = {}, scope = scope,
        log = { message, _ -> warnings += message }, clock = { NOW }, nanoTime = { backend.nanos },
    )

    @Test
    fun bytesPerFrameRecordedOnlyForSatellite() = runBlocking {
        assertEquals(720, plans.size)
        backend.probe = SatelliteCheck.OK
        backend.received.addAll(listOf(1_000_000L, 37_000_000L))

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()
        awaitIdle()

        assertEquals(50_000.0, store.bytesPerFrame())

        // Made on the plain map instead: its bytes say nothing about satellite tiles.
        backend.probe = SatelliteCheck.FAILED
        backend.received.addAll(listOf(1_000_000L, 2_000_000L))
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()
        awaitIdle()
        assertEquals(50_000.0, store.bytesPerFrame())

        // The counter unsupported on this phone.
        backend.probe = SatelliteCheck.OK
        backend.received.clear()
        backend.received.addAll(listOf(null, null))
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        awaitEnd()
        assertEquals(50_000.0, store.bytesPerFrame())
    }

    @Test
    fun imageIsSavedAndRecorded() = runBlocking {
        assertEquals(StartResult.STARTED, job.startImage(TRIP, Aspect.LANDSCAPE))
        val done = awaitEnd() as VideoJobState.Done

        assertEquals(VideoJobState.Preparing(MediaKind.IMAGE), backend.firstStateSeen)

        val uri = backend.created.single()
        assertEquals("bikeRecoder_20261025_0858.png", backend.createdNames.single())
        assertEquals(listOf(uri to Aspect.LANDSCAPE), backend.images)
        assertEquals(listOf(uri), backend.published)
        assertEquals(TripMedia(done.media.id, TRIP, MediaKind.IMAGE, uri, NOW, VideoOptionsJson.encode(VideoOptions(aspect = Aspect.LANDSCAPE))), done.media)
        assertEquals(listOf(done.media), media.rows)
        assertFalse(done.downscaled)
        assertNull(store.pending())
        assertEquals(listOf(TRIP), maps.deleted)
        assertEquals(1, servicesStarted)
    }

    @Test
    fun pendingRecordsTheGalleryItemWhileMaking() = runBlocking {
        backend.gateAt = 2
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        backend.reached.await()

        assertEquals(
            PendingJob(TRIP, MediaKind.VIDEO, VideoOptionsJson.encode(short), NOW, outputUri = backend.created.single()),
            store.pending(),
        )
        job.cancel()
        awaitEnd()
    }

    @Test
    fun cancelWhenTheGalleryItemIsJustMadeStillDeletesIt() = runBlocking {
        backend.cancelInCreate = true

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))

        assertEquals(VideoJobState.Cancelled, awaitEnd())
        awaitIdle()
        assertEquals(1, backend.created.size)
        assertEquals(backend.created, backend.deleted)
        assertEquals(emptyList<FakeSink>(), backend.sinks)
        assertNull(store.pending())
    }

    @Test
    fun cancelWhenTheEncoderIsJustOpenedStillClosesItAndDeletes() = runBlocking {
        backend.cancelInOpen = true

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))

        assertEquals(VideoJobState.Cancelled, awaitEnd())
        awaitIdle()
        val events = backend.sinks.single().events
        assertEquals("close", events.last())
        assertFalse("frame" in events || "finish" in events, events.toString())
        assertEquals(backend.created, backend.deleted)
        assertNull(store.pending())
    }

    @Test
    fun aPublishedVideoIsNeverLeftForTheUnfinishedCleanup() = runBlocking {
        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        assertTrue(awaitEnd() is VideoJobState.Done)

        // A kill at publish time leaves a job to offer again, but without the item: nothing deletes the finished file.
        assertEquals(PendingJob(TRIP, MediaKind.VIDEO, VideoOptionsJson.encode(short), NOW, outputUri = null), backend.pendingAtPublish)
        // Cleared before the trip_media row and the speed are written.
        assertEquals(listOf<PendingJob?>(null), media.pendingAtAdd)
    }

    @Test
    fun anErrorFailsTheJobInsteadOfCancellingIt() = runBlocking {
        backend.failAt = 5
        backend.failWith = { OutOfMemoryError("frame bitmap") }

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))

        assertEquals(VideoJobState.Failed("영상을 만들지 못했습니다"), awaitEnd())
        awaitIdle()
        assertEquals(backend.created, backend.deleted)
        assertEquals("close", backend.sinks.single().events.last())
    }

    /** Final review I2: the trip deleted mid-job (trip_media's foreign key fails) — the video stays in the gallery, Done. */
    @Test
    fun aPublishedVideoIsDoneEvenWhenItsRowCannotBeAdded() = runBlocking {
        media.failAdd = { IllegalStateException("FOREIGN KEY constraint failed") }

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))
        val done = awaitEnd() as VideoJobState.Done
        awaitIdle()

        val uri = backend.created.single()
        assertEquals(TripMedia(0, TRIP, MediaKind.VIDEO, uri, NOW, VideoOptionsJson.encode(short)), done.media)
        assertEquals(listOf(uri), backend.published)
        assertEquals(emptyList<String>(), backend.deleted)
        assertNull(store.pending())
        // The steps after the row still ran: this phone's speed is recorded.
        assertTrue(store.secondsPerFrame() != null)
        assertTrue(warnings.any { "row" in it }, warnings.toString())
    }

    /** Even an Error after publishing keeps the published file and reports it as made. */
    @Test
    fun anErrorAfterPublishingStillEndsDone() = runBlocking {
        media.failAdd = { OutOfMemoryError("row") }

        assertEquals(StartResult.STARTED, job.startVideo(TRIP, short))

        assertTrue(awaitEnd() is VideoJobState.Done)
        awaitIdle()
        assertEquals(emptyList<String>(), backend.deleted)
        assertEquals(1, backend.published.size)
    }

    /** Final review M1: an Error in the cleanup still ends the state and frees the job (and is not swallowed). */
    @Test
    fun anErrorInTheCleanupStillReleasesTheJob() = runBlocking {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> uncaught += e }
        val failing = VideoJob(
            loadInputs = { inputs }, store = store, tripMedia = media, maps = maps, backend = backend, recording = recording,
            online = { true }, regionOf = { null }, startService = {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).also { scopes += it },
            log = { message, _ -> warnings += message }, clock = { NOW }, nanoTime = { backend.nanos },
        )
        maps.failDelete = { StackOverflowError("cleanup") }

        assertEquals(StartResult.STARTED, failing.startVideo(TRIP, short))

        val end = withTimeout(20_000) { failing.state.first { it is VideoJobState.Done || it is VideoJobState.Failed } }
        assertTrue(end is VideoJobState.Done, end.toString())
        withTimeout(20_000) { while (failing.isRunning) kotlinx.coroutines.delay(5) }
        assertNull(failing.runningTripId.value)
        withTimeout(20_000) { while (uncaught.isEmpty()) kotlinx.coroutines.delay(5) }
        assertTrue(uncaught.single() is StackOverflowError)

        // Free again: the next job starts.
        maps.failDelete = null
        assertEquals(StartResult.STARTED, failing.startImage(TRIP, Aspect.PORTRAIT))
        assertTrue(withTimeout(20_000) { failing.state.first { it is VideoJobState.Done } } is VideoJobState.Done)
    }

    @Test
    fun theMapIsCheckedBeforeTheMusicIsMade() = runBlocking {
        online = false
        maps.available = false

        assertEquals(StartResult.NO_INTERNET, job.startVideo(TRIP, short.copy(musicUri = "content://music/1")))

        assertEquals(emptyList<Long>(), backend.musicDurations)
        assertNothingStarted()
    }

    @Test
    fun aServiceThatCannotStartLeavesTheRunningJobClaimed() = runBlocking {
        val throwing = VideoJob(
            loadInputs = { inputs }, store = store, tripMedia = media, maps = maps, backend = backend, recording = recording,
            online = { true }, regionOf = { null }, startService = { throw IllegalStateException("not allowed") }, scope = scope(),
            log = { message, _ -> warnings += message }, clock = { NOW }, nanoTime = { backend.nanos },
        )
        backend.gateAt = 2

        assertEquals(StartResult.STARTED, throwing.startVideo(TRIP, short))
        backend.reached.await()

        assertTrue(throwing.isRunning)
        assertEquals(StartResult.BUSY, throwing.startVideo(TRIP, short))
        throwing.cancel()
    }

    private suspend fun assertNothingStarted() {
        assertEquals(emptyList<String>(), backend.created)
        assertEquals(emptyList<FakeSink>(), backend.sinks)
        assertEquals(0, servicesStarted)
        assertNull(store.pending())
        assertEquals(VideoJobState.Idle, job.state.value)
        assertFalse(job.isRunning)
    }

    private inner class FakeBackend : VideoBackend {
        var nanos = 0L
        var free = 10_000_000_000L
        var musicReadable = true
        var downscale = false
        var failAt = -1
        var onFail: () -> Unit = {}
        var failWith: () -> Throwable = { IllegalStateException("video encoding failed") }
        var cancelInCreate = false
        var cancelInOpen = false
        var pendingAtPublish: PendingJob? = null
        var gateAt = -1
        var reached = CompletableDeferred<Unit>()
        val names = mutableSetOf<String>()
        val created: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val createdNames = mutableListOf<String>()
        val published = mutableListOf<String>()
        val deleted: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val musicDurations = mutableListOf<Long>()
        val sinks = mutableListOf<FakeSink>()
        val images = mutableListOf<Pair<String, Aspect>>()
        var firstStateSeen: VideoJobState? = null
        var probe = SatelliteCheck.OK

        /** Runs inside [probeSatellite] (e.g. a recording that starts meanwhile). */
        var onProbe: () -> Unit = {}
        val probed: MutableList<GeoPoint> = Collections.synchronizedList(mutableListOf())
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** What [receivedBytes] answers, in turn; null once empty (unsupported). */
        val received: MutableList<Long?> = Collections.synchronizedList(mutableListOf())
        val videoSatellite = mutableListOf<Boolean>()
        val imageSatellite = mutableListOf<Boolean>()
        val videoInputs = mutableListOf<VideoInputs>()
        val imageInputs = mutableListOf<VideoInputs>()

        override suspend fun probeSatellite(at: GeoPoint): SatelliteCheck {
            calls += "probe"
            probed += at
            onProbe()
            return probe
        }

        override fun receivedBytes(): Long? = if (received.isEmpty()) null else received.removeAt(0)

        override fun freeBytes(): Long = free

        override suspend fun loadMusic(uri: String, durationUs: Long): MusicTrack? {
            calls += "music"
            musicDurations += durationUs
            return if (musicReadable) FakeMusic(uri) else null
        }

        override suspend fun existingNames(kind: MediaKind): Set<String> = names.toSet()

        /** On another dispatcher like the real one; [cancelInCreate] cancels while the item is being made. */
        override suspend fun createPending(kind: MediaKind, displayName: String): String = withContext(Dispatchers.IO) {
            firstStateSeen = job.state.value
            createdNames += displayName
            if (cancelInCreate) job.cancel()
            "content://gallery/${created.size + 1}".also { created += it }
        }

        override suspend fun publish(uri: String) {
            pendingAtPublish = store.pending()
            published += uri
        }

        override suspend fun delete(uri: String) {
            deleted += uri
        }

        override suspend fun openVideo(uri: String, inputs: VideoInputs, options: VideoOptions, satellite: Boolean): VideoSink = withContext(Dispatchers.IO) {
            if (cancelInOpen) job.cancel()
            videoSatellite += satellite
            videoInputs += inputs
            FakeSink(if (downscale) 720 else 1080, if (downscale) 1280 else 1920, downscale).also { sinks += it }
        }

        override suspend fun writeImage(uri: String, inputs: VideoInputs, aspect: Aspect, satellite: Boolean) {
            images += uri to aspect
            imageSatellite += satellite
            imageInputs += inputs
        }
    }

    private class FakeMusic(val uri: String) : MusicTrack

    private inner class FakeSink(override val width: Int, override val height: Int, override val downscaled: Boolean) : VideoSink {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val plans = mutableListOf<FramePlan>()
        val cameras = mutableListOf<Camera>()
        val states = mutableListOf<VideoJobState>()

        override fun addAudio(music: MusicTrack?) {
            events += "audio:${(music as FakeMusic?)?.uri}"
        }

        override suspend fun frame(plan: FramePlan, camera: Camera) {
            val index = plans.size
            if (index == backend.gateAt) {
                backend.reached.complete(Unit)
                CompletableDeferred<Unit>().await() // until cancelled
            }
            if (index == backend.failAt) {
                backend.onFail()
                throw backend.failWith()
            }
            states += job.state.value
            plans += plan
            cameras += camera
            events += "frame"
            backend.nanos += if (plan.reuseMap) 30_000_000L else 2_000_000_000L
        }

        override fun finish() {
            events += "finish"
        }

        override fun close() {
            events += "close"
        }
    }

    private class FakeMaps : VideoMaps {
        var available = true
        val downloads: MutableList<List<GeoPoint>> = Collections.synchronizedList(mutableListOf())
        val deleted: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override suspend fun downloadForTrack(tripId: Long, points: List<GeoPoint>): Boolean {
            downloads += points
            return available
        }

        var failDelete: (() -> Throwable)? = null

        override suspend fun deleteVideoRegions(tripId: Long) {
            deleted += tripId
            failDelete?.let { throw it() }
        }
    }

    private inner class FakeTripMedia : TripMediaStore {
        val rows: MutableList<TripMedia> = Collections.synchronizedList(mutableListOf())
        val pendingAtAdd = mutableListOf<PendingJob?>()
        override fun observe(tripId: Long): Flow<List<TripMedia>> = flowOf(rows.filter { it.tripId == tripId })
        var failAdd: (() -> Throwable)? = null
        override suspend fun add(m: TripMedia): Long {
            pendingAtAdd += store.pending()
            failAdd?.let { throw it() }
            return (rows.size + 100L).also { rows += m.copy(id = it) }
        }
        override suspend fun remove(ids: Collection<Long>) {
            rows.removeAll { it.id in ids }
        }
    }

    private companion object {
        const val TRIP = 7L
        const val NOW = 1_800_000_000_000L
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 2 minutes at 5 m/s from 2026-10-25 09:00 Seoul, one point a second. The segment was started at 08:58:30, before
         * the first GPS fix: the file is named after the start (spec §3.5).
         */
        fun testInputs(): VideoInputs {
            val start = LocalDate.of(2026, 10, 25).atTime(9, 0).atZone(SEOUL).toInstant().toEpochMilli()
            val pressed = start - 90_000
            val track = List(121) { TrackPoint(start + it * 1_000L, GeoPoint(37.52, 126.93 + it * 0.0000565), 20.0, 5f, 5f) }
            val totals = SegmentTotals(600.0, 120_000, 120_000, 5.5, 3.0, 3.0, 20.0, 23.0)
            val segment = RideSegment(1, TRIP, pressed, track.last().timeMillis, LocalDate.of(2026, 10, 25), totals)
            val timeline = RideTimelineBuilder.build(listOf(segment to track), SEOUL)
            return VideoInputs(
                tripId = TRIP,
                timeline = timeline,
                photos = emptyList(),
                heartRate = emptyList(),
                dayStarts = emptyList(),
                title = "10월 25일 (일)",
                rideTitle = "아침 라이딩",
                totals = TripTotals(600.0, 120_000, 5.0, 5.5, 3.0, avgBpm = null, calories = null),
                canColorByHeart = false,
                firstStartedAt = pressed,
            )
        }
    }
}
