package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.core.video.FPS
import com.cowork.bikerecoder.core.video.FramePlan
import com.cowork.bikerecoder.core.video.MediaNaming
import com.cowork.bikerecoder.core.video.VideoEstimate
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.offline.VideoMaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** What the one video/image job is doing (the progress card, the notification and the preview follow it). */
sealed interface VideoJobState {
    data object Idle : VideoJobState

    /** Started: the map around the track is being downloaded, the gallery item made. */
    data class Preparing(val kind: MediaKind) : VideoJobState

    /** "영상 만드는 중 N% · 약 M분 남음". */
    data class Rendering(val percent: Int, val minutesLeft: Int) : VideoJobState

    /** Every frame is drawn; the encoder writes the rest of the MP4. */
    data object Encoding : VideoJobState

    /** Saved in the gallery and in `trip_media`; [downscaled] = made at 720p ("이 휴대폰은 720p로 만들었습니다"). */
    data class Done(val media: TripMedia, val downscaled: Boolean) : VideoJobState

    /** Stopped by a failure or by a recording that started; [message] is shown as is. */
    data class Failed(val message: String) : VideoJobState

    /** [취소]: nothing was kept. */
    data object Cancelled : VideoJobState
}

/** Why [VideoJob.startVideo] / [VideoJob.startImage] did not start; [message] is what to show (spec §3.2, §3.4). */
enum class StartResult {
    STARTED,
    BUSY,
    RECORDING,
    NO_INTERNET,
    NO_SPACE,
    MUSIC_UNREADABLE,

    /** No finished segment of at least 10 m (the buttons are hidden then). */
    NOTHING_TO_MAKE,
    ;

    /** [neededBytes] = [VideoJob.neededBytes] or [VideoJob.IMAGE_BYTES], for [NO_SPACE]. Null for [STARTED] / [NOTHING_TO_MAKE]. */
    fun message(neededBytes: Long): String? = when (this) {
        STARTED, NOTHING_TO_MAKE -> null
        BUSY -> "이미 영상을 만들고 있습니다"
        RECORDING -> "기록 중에는 만들 수 없습니다"
        NO_INTERNET -> VideoJob.MSG_NO_INTERNET
        NO_SPACE -> VideoJob.noSpaceMessage(neededBytes)
        MUSIC_UNREADABLE -> "음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?"
    }
}

/** What the screens use of [VideoJob] (replaceable in the UI's tests). */
interface VideoJobControl {
    val state: StateFlow<VideoJobState>

    /** The trip being made while a job runs (its screens show the progress card), else null. */
    val runningTripId: StateFlow<Long?>
    val isRunning: Boolean
    suspend fun startVideo(tripId: Long, options: VideoOptions): StartResult
    suspend fun startImage(tripId: Long, aspect: Aspect): StartResult
    fun cancel()
    fun acknowledge()
    suspend fun unfinished(): PendingJob?
    suspend fun dismissUnfinished()
}

/**
 * The one video or summary-image job (spec §3.1–3.4), outside any screen. One at a time; never while recording.
 *
 * Start checks (busy, recording, something to make, storage = [VideoEstimate.bytes] against what is free, the music
 * opened and encoded up front, a map: online or the trip's own) refuse with a [StartResult] before anything is
 * written. Then, in [scope]: pending job ([VideoJobStore.setPending]) → map around the track
 * ([VideoMaps.downloadForTrack]) → gallery item (`IS_PENDING`) → frames ([VideoBackend.openVideo], cameras at the
 * encoder's final size) → finish → publish → [TripMediaStore.add] → this phone's seconds per frame → pending cleared.
 * Cancel, failure or a recording that starts ([recording] true) delete the gallery item and the pending job. The
 * video map is deleted in every case. [startService] puts the foreground service ([VideoService]) up for the run.
 */
class VideoJob(
    private val loadInputs: suspend (tripId: Long) -> VideoInputs?,
    private val store: VideoJobStore,
    private val tripMedia: TripMediaStore,
    private val maps: VideoMaps,
    private val backend: VideoBackend,
    private val recording: Flow<Boolean>,
    private val online: () -> Boolean,
    private val startService: () -> Unit,
    private val scope: CoroutineScope,
    private val log: WarnLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val zone: ZoneId = SEOUL,
) : VideoJobControl {
    private val _state = MutableStateFlow<VideoJobState>(VideoJobState.Idle)
    override val state: StateFlow<VideoJobState> = _state.asStateFlow()

    private val _runningTripId = MutableStateFlow<Long?>(null)
    override val runningTripId: StateFlow<Long?> = _runningTripId.asStateFlow()

    /** Held from the start checks until the job has ended (and by [unfinished]). */
    private val claimed = AtomicBoolean(false)
    @Volatile private var running: Job? = null
    @Volatile private var stoppedByRecording = false

    /** A job is being checked, made or cleaned up. */
    override val isRunning: Boolean get() = claimed.get()

    override suspend fun startVideo(tripId: Long, options: VideoOptions): StartResult {
        if (!claimed.compareAndSet(false, true)) return StartResult.BUSY
        var launched = false
        try {
            if (recording.first()) return StartResult.RECORDING
            val inputs = loadInputs(tripId) ?: return StartResult.NOTHING_TO_MAKE
            val needed = neededBytes(options)
            if (backend.freeBytes() < needed) return StartResult.NO_SPACE
            val plans = VideoTimeline.plan(inputs.timeline, inputs.photos, options, inputs.dayStarts)
            if (plans.isEmpty()) return StartResult.NOTHING_TO_MAKE
            // The map first: offline it answers at once, the music takes seconds.
            if (!mapReachable(inputs)) return StartResult.NO_INTERNET
            val music = options.musicUri?.let { uri ->
                backend.loadMusic(uri, plans.size * 1_000_000L / FPS) ?: return StartResult.MUSIC_UNREADABLE
            }
            launch(MediaKind.VIDEO, inputs, VideoOptionsJson.encode(options), needed) { uri ->
                makeVideo(uri, inputs, options, plans, music)
            }
            launched = true
            startForeground()
            return StartResult.STARTED
        } finally {
            if (!launched) claimed.set(false)
        }
    }

    override suspend fun startImage(tripId: Long, aspect: Aspect): StartResult {
        if (!claimed.compareAndSet(false, true)) return StartResult.BUSY
        var launched = false
        try {
            if (recording.first()) return StartResult.RECORDING
            val inputs = loadInputs(tripId) ?: return StartResult.NOTHING_TO_MAKE
            if (backend.freeBytes() < IMAGE_BYTES) return StartResult.NO_SPACE
            if (!mapReachable(inputs)) return StartResult.NO_INTERNET
            launch(MediaKind.IMAGE, inputs, VideoOptionsJson.encode(VideoOptions(aspect = aspect)), IMAGE_BYTES) { uri ->
                backend.writeImage(uri, inputs, aspect)
                Made(downscaled = false, secondsPerFrame = null)
            }
            launched = true
            startForeground()
            return StartResult.STARTED
        } finally {
            if (!launched) claimed.set(false)
        }
    }

    /** [취소]: the running job stops and deletes what it made. */
    override fun cancel() {
        running?.cancel()
    }

    /** After the UI has shown a finished job's result: back to [VideoJobState.Idle] (a running job is not touched). */
    override fun acknowledge() {
        val s = _state.value
        if (s is VideoJobState.Done || s is VideoJobState.Failed || s is VideoJobState.Cancelled) {
            _state.compareAndSet(s, VideoJobState.Idle)
        }
    }

    /**
     * The job the app was killed in the middle of (spec §3.3, "영상을 만들지 못했습니다. 다시 만들까요?"), or null (also while
     * a job runs). Its half-written gallery item and video map are deleted here (ruling V6); the job stays offered until
     * it is made again ([startVideo] / [startImage] with its options, without music) or [dismissUnfinished].
     */
    override suspend fun unfinished(): PendingJob? {
        if (!claimed.compareAndSet(false, true)) return null
        try {
            return clearLeftovers()
        } finally {
            claimed.set(false)
        }
    }

    /** [닫기]: forget the unfinished job (its leftovers deleted). Nothing happens while a job runs. */
    override suspend fun dismissUnfinished() {
        if (!claimed.compareAndSet(false, true)) return
        try {
            clearLeftovers()
            store.setPending(null)
        } finally {
            claimed.set(false)
        }
    }

    private suspend fun clearLeftovers(): PendingJob? {
        val pending = store.pending() ?: return null
        val uri = pending.outputUri
        val left = pending.copy(outputUri = null)
        if (uri != null) {
            attempt("Could not delete the unfinished gallery item") { backend.delete(uri) }
            store.setPending(left)
        }
        attempt("Could not delete the unfinished video map") { maps.deleteVideoRegions(pending.tripId) }
        return left
    }

    /** Online (the map is downloaded when the job runs), or offline with the trip's own map. */
    private suspend fun mapReachable(inputs: VideoInputs): Boolean =
        online() || maps.downloadForTrack(inputs.tripId, inputs.points())

    private class Made(val downscaled: Boolean, val secondsPerFrame: Double?)

    /** Raised for an expected stop whose [message] is shown as is. */
    private class Stop(override val message: String) : Exception(message)

    /** ATOMIC: even a job cancelled at once runs [run], whose `finally` ends the state and frees the claim. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun launch(kind: MediaKind, inputs: VideoInputs, optionsJson: String, neededBytes: Long, make: suspend (uri: String) -> Made) {
        stoppedByRecording = false
        _runningTripId.value = inputs.tripId
        _state.value = VideoJobState.Preparing(kind)
        val job = scope.launch(start = CoroutineStart.ATOMIC) { run(kind, inputs, optionsJson, neededBytes, make) }
        running = job
        // A recording that starts stops the job (GPS recording first, spec §3.2).
        val watcher = scope.launch {
            recording.first { it }
            stoppedByRecording = true
            job.cancel()
        }
        job.invokeOnCompletion { watcher.cancel() }
    }

    /** The job runs (and keeps its claim) even when the service cannot start. */
    private fun startForeground() {
        try {
            startService()
        } catch (e: Exception) {
            log.warn("Could not start the video service", e)
        }
    }

    private suspend fun run(kind: MediaKind, inputs: VideoInputs, optionsJson: String, neededBytes: Long, make: suspend (String) -> Made) {
        val tripId = inputs.tripId
        var uri: String? = null
        var published = false
        var result: VideoJobState = VideoJobState.Cancelled
        try {
            store.setPending(PendingJob(tripId, kind, optionsJson, clock()))
            if (!maps.downloadForTrack(tripId, inputs.points())) throw Stop(MSG_NO_INTERNET)
            val base = MediaNaming.baseName(inputs.firstStartedAt, zone)
            val name = MediaNaming.unique(base, kind.extension, backend.existingNames(kind))
            // Not cancellable, and kept inside the block: a cancel that lands as the item is made must not lose its uri.
            withContext(NonCancellable) { uri = backend.createPending(kind, name) }
            val item = checkNotNull(uri)
            store.setPending(PendingJob(tripId, kind, optionsJson, clock(), outputUri = item))
            val made = make(item)
            // Finished: kept even if [취소] comes now.
            withContext(NonCancellable) {
                // Forget the item before it is public: a kill from here on can never make [unfinished] delete a finished file.
                store.setPending(PendingJob(tripId, kind, optionsJson, clock()))
                backend.publish(item)
                published = true
                store.setPending(null)
                val media = TripMedia(0, tripId, kind, item, clock(), optionsJson)
                val id = tripMedia.add(media)
                made.secondsPerFrame?.let { store.recordSpeed(it) }
                result = VideoJobState.Done(media.copy(id = id), made.downscaled)
            }
        } catch (e: CancellationException) {
            if (!published) result = if (stoppedByRecording) VideoJobState.Failed(MSG_RECORDING_STARTED) else VideoJobState.Cancelled
        } catch (e: Throwable) {
            // Errors too (an OutOfMemoryError on a frame bitmap): a failure, not a silent "cancelled".
            log.warn("Making the ${kind.name.lowercase()} of trip $tripId failed", e)
            result = VideoJobState.Failed(failureMessage(e, kind, neededBytes))
        } finally {
            withContext(NonCancellable) {
                val item = uri
                if (!published && item != null) attempt("Could not delete the unfinished gallery item") { backend.delete(item) }
                attempt("Could not clear the pending video job") { store.setPending(null) }
                attempt("Could not delete the video map") { maps.deleteVideoRegions(tripId) }
            }
            _state.value = result
            _runningTripId.value = null
            if (running === currentCoroutineContext()[Job]) running = null
            claimed.set(false)
        }
    }

    /** The encoder and renderer are closed when this returns or throws — only here, never while a frame is drawn (carry). */
    private suspend fun makeVideo(uri: String, inputs: VideoInputs, options: VideoOptions, plans: List<FramePlan>, music: MusicTrack?): Made {
        // Opened without cancellation and kept outside the block (a result handed back across dispatchers is dropped if
        // the job was cancelled meanwhile), so the encoder is closed below whatever happens.
        var opened: VideoSink? = null
        try {
            withContext(NonCancellable) { opened = backend.openVideo(uri, inputs, options) }
            val sink = checkNotNull(opened)
            // The encoder's final size (720p fallback included) decides the overview and the closing camera.
            val w = sink.width
            val h = sink.height
            val cameras = CameraPath.cameras(
                plans, inputs.timeline, CameraPath.overview(inputs.points(), w, h), FrameRenderer.outroCamera(inputs, w, h),
            )
            sink.addAudio(music) // before the first frame (ruling V7)

            var mapLeft = plans.count { !it.reuseMap }
            var reusedLeft = plans.size - mapLeft
            val stored = store.secondsPerFrame() ?: DEFAULT_SECONDS_PER_FRAME
            var mapNanos = 0L
            var mapFrames = 0
            _state.value = VideoJobState.Rendering(0, VideoEstimate.minutes(mapLeft, reusedLeft, stored))
            for ((i, plan) in plans.withIndex()) {
                currentCoroutineContext().ensureActive()
                val start = nanoTime()
                sink.frame(plan, cameras[i])
                if (plan.reuseMap) {
                    reusedLeft--
                } else {
                    mapNanos += nanoTime() - start
                    mapFrames++
                    mapLeft--
                }
                if (i < plans.lastIndex) {
                    val perFrame = mapNanos / 1e9 / mapFrames
                    _state.value = VideoJobState.Rendering((i + 1) * 100 / plans.size, VideoEstimate.minutes(mapLeft, reusedLeft, perFrame))
                }
            }
            _state.value = VideoJobState.Encoding
            sink.finish()
            return Made(sink.downscaled, if (mapFrames > 0) mapNanos / 1e9 / mapFrames else null)
        } finally {
            opened?.close()
        }
    }

    /** After a failure: a disk that is too full now says so (spec §3.4, "중간 실패 포함"). */
    private fun failureMessage(e: Throwable, kind: MediaKind, neededBytes: Long): String {
        if (e is Stop) return e.message
        val free = try {
            backend.freeBytes()
        } catch (x: Exception) {
            log.warn("Could not read the free space", x)
            Long.MAX_VALUE
        }
        return when {
            free < neededBytes -> noSpaceMessage(neededBytes)
            kind == MediaKind.VIDEO -> MSG_VIDEO_FAILED
            else -> MSG_IMAGE_FAILED
        }
    }

    private suspend fun attempt(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(what, e)
        }
    }

    companion object {
        const val MSG_NO_INTERNET = "지도를 받으려면 인터넷이 필요합니다"
        const val MSG_RECORDING_STARTED = "기록이 시작되어 영상 만들기를 멈췄습니다"
        const val MSG_VIDEO_FAILED = "영상을 만들지 못했습니다"
        const val MSG_IMAGE_FAILED = "이미지를 만들지 못했습니다"

        /** A summary PNG is a few MB; the space it asks for before starting. */
        const val IMAGE_BYTES = 20_000_000L

        /** Before this phone has made a video (spec §3.6). */
        const val DEFAULT_SECONDS_PER_FRAME = 0.25

        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /** The space a video of [options] asks for: 8 Mbps × its length × 1.2 (spec §3.4). */
        fun neededBytes(options: VideoOptions): Long = VideoEstimate.bytes(options.length.seconds.toDouble())

        /** "저장 공간이 부족합니다(약 N MB 필요)", N in MB (10⁶ bytes) rounded up. */
        fun noSpaceMessage(neededBytes: Long): String = "저장 공간이 부족합니다(약 ${ceil(neededBytes / 1e6).toLong()} MB 필요)"

        private val MediaKind.extension: String
            get() = when (this) {
                MediaKind.VIDEO -> "mp4"
                MediaKind.IMAGE -> "png"
            }

        private fun VideoInputs.points() = timeline.points.map { it.point }
    }
}
