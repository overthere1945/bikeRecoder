package com.cowork.bikerecoder.ui.video

import android.content.Context
import android.os.BatteryManager
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.VideoEstimate
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.PendingJob
import com.cowork.bikerecoder.video.StartResult
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.VideoInputs
import com.cowork.bikerecoder.video.VideoInputsLoader
import com.cowork.bikerecoder.video.VideoJob
import com.cowork.bikerecoder.video.VideoJobControl
import com.cowork.bikerecoder.video.VideoJobState
import com.cowork.bikerecoder.video.VideoNotifications
import com.cowork.bikerecoder.video.VideoOptionsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The battery when [만들기] is pressed (spec §3.2). */
data class BatteryLevel(val percent: Int, val charging: Boolean)

/** The options sheet (spec §1.2, §1.3) of trip [tripId]. */
data class VideoSheet(
    val tripId: Long,
    val kind: MediaKind,
    val options: VideoOptions,
    /** [심박] is offered: some target segment has detailed heart rate. */
    val canColorByHeart: Boolean = false,
    /** "약 N분 걸립니다" (video); null while it is worked out. */
    val minutes: Int? = null,
    /** The picked music's file name, while [VideoOptions.musicUri] is set. */
    val musicName: String? = null,
    /** [만들기] was pressed and the start checks run (with music, it is encoded meanwhile). */
    val starting: Boolean = false,
) {
    /** The video needs its estimate first (the trip is read meanwhile); the image can start at once. */
    val canMake: Boolean get() = !starting && (kind == MediaKind.IMAGE || minutes != null)
}

/** The video's dialogs (spec §3.2–3.4). */
sealed interface VideoDialog {
    /** A refusal or failure, with [확인]. */
    data class Message(val text: String) : VideoDialog

    /** "배터리가 부족합니다(N%). 계속할까요?" [계속] [취소]. */
    data class LowBattery(val percent: Int) : VideoDialog

    /** "음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?" [음악 없이 만들기] [취소]. */
    data object MusicUnreadable : VideoDialog

    /** At app start: "영상을 만들지 못했습니다. 다시 만들까요?" [다시 만들기] [닫기]. */
    data class Unfinished(val job: PendingJob) : VideoDialog
}

/** The progress card: "영상 만드는 중 N% · 약 M분 남음"; [percent] null before the first frame (or for the image). */
data class VideoProgress(val kind: MediaKind, val percent: Int?, val text: String)

/** A finished job, shown once: the preview of the made file, or the failure's message. */
sealed interface VideoOutcome {
    data class Preview(val uri: String, val kind: MediaKind, val downscaled: Boolean) : VideoOutcome
    data class Message(val text: String) : VideoOutcome
}

/**
 * The making of videos and summary images on screen (spec §1, §3.2–3.5), shared by the trip detail and both summaries
 * (activity-scoped, so one sheet and one set of dialogs). [open] shows the options sheet with the last options
 * ([lastOptions], music never remembered), the [심박] choice and the estimate; [make] remembers them, asks once when the
 * battery is low, then starts [job] and shows its [StartResult]. While the job runs [progress] is its card; when it ends
 * [outcome] is the preview or the failure, until [outcomeShown]. [checkUnfinished] offers the job a forced kill left.
 */
class VideoViewModel(
    private val job: VideoJobControl,
    private val loadInputs: suspend (tripId: Long) -> VideoInputs?,
    private val lastOptions: suspend () -> VideoOptions,
    private val saveOptions: suspend (VideoOptions) -> Unit,
    private val secondsPerFrame: suspend () -> Double?,
    private val recording: () -> Boolean,
    private val battery: () -> BatteryLevel?,
    private val musicName: suspend (uri: String) -> String?,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _sheet = MutableStateFlow<VideoSheet?>(null)
    val sheet: StateFlow<VideoSheet?> = _sheet.asStateFlow()

    private val _dialog = MutableStateFlow<VideoDialog?>(null)
    val dialog: StateFlow<VideoDialog?> = _dialog.asStateFlow()

    /** The trip the running job makes (from the job itself, so it survives the activity), else null. */
    val runningTripId: StateFlow<Long?> = job.runningTripId

    val progress: StateFlow<VideoProgress?> = job.state.map(::progressOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, progressOf(job.state.value))

    val outcome: StateFlow<VideoOutcome?> = job.state.map(::outcomeOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, outcomeOf(job.state.value))

    /** The sheet's trip, read once per sheet (the [심박] choice and the estimate). */
    private var inputs: VideoInputs? = null
    private var sheetJob: Job? = null
    private var estimateJob: Job? = null

    /** What [계속] / [음악 없이 만들기] start. */
    private var waiting: Request? = null
    private var starting = false

    init {
        // [취소] needs no message: back to idle at once.
        viewModelScope.launch { job.state.collect { if (it == VideoJobState.Cancelled) job.acknowledge() } }
    }

    /**
     * [영상 만들기] / [이미지 만들기] of trip [tripId]: the sheet, unless a recording runs ("기록 중에는 만들 수 없습니다") or
     * a job does ("이미 영상을 만들고 있습니다").
     */
    fun open(tripId: Long, kind: MediaKind) {
        when {
            recording() -> return show(StartResult.RECORDING.message(0))
            job.isRunning -> return show(StartResult.BUSY.message(0))
        }
        sheetJob?.cancel()
        estimateJob?.cancel()
        inputs = null
        sheetJob = viewModelScope.launch {
            val last = guarded("Could not read the last video options") { lastOptions() } ?: VideoOptions()
            if (kind == MediaKind.IMAGE) {
                _sheet.value = VideoSheet(tripId, kind, VideoOptions(aspect = last.aspect))
                return@launch
            }
            val start = last.copy(musicUri = null)
            _sheet.value = VideoSheet(tripId, kind, start)
            val loaded = try {
                loadInputs(tripId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not read trip $tripId for the video", e)
                _sheet.value = null
                return@launch show(VideoJob.MSG_VIDEO_FAILED)
            }
            if (loaded == null) {
                // Nothing to make (the buttons are hidden then).
                _sheet.value = null
                return@launch
            }
            inputs = loaded
            _sheet.update { s ->
                s?.copy(
                    canColorByHeart = loaded.canColorByHeart,
                    options = if (loaded.canColorByHeart) s.options else s.options.copy(coloring = RouteColoring.DEFAULT),
                )
            }
            estimate()
        }
    }

    /** A choice in the sheet; the estimate follows the length and [사진 넣기]. */
    fun setOptions(options: VideoOptions) {
        val s = _sheet.value ?: return
        val allowed = if (s.canColorByHeart) options else options.copy(coloring = RouteColoring.DEFAULT)
        _sheet.value = s.copy(options = allowed)
        if (s.kind == MediaKind.VIDEO && (allowed.length != s.options.length || allowed.photos != s.options.photos)) estimate()
    }

    /** [음악 고르기] picked [uri] (its name is read for the sheet). */
    fun pickMusic(uri: String) {
        _sheet.update { it?.copy(options = it.options.copy(musicUri = uri), musicName = null) }
        viewModelScope.launch {
            val name = guarded("Could not read the music's name") { musicName(uri) }
            _sheet.update { if (it?.options?.musicUri == uri) it.copy(musicName = name ?: MUSIC_PICKED) else it }
        }
    }

    /** [없음]: no music. */
    fun clearMusic() {
        _sheet.update { it?.copy(options = it.options.copy(musicUri = null), musicName = null) }
    }

    fun closeSheet() {
        sheetJob?.cancel()
        estimateJob?.cancel()
        _sheet.value = null
    }

    /** [만들기]: the options are remembered (not the music), then the battery check and the start. */
    fun make() {
        val s = _sheet.value ?: return
        if (!s.canMake) return
        val request = when (s.kind) {
            MediaKind.VIDEO -> {
                viewModelScope.launch {
                    guarded("Could not save the video options") { saveOptions(s.options.copy(musicUri = null)) }
                }
                Request.Video(s.tripId, s.options)
            }
            MediaKind.IMAGE -> Request.Image(s.tripId, s.options.aspect)
        }
        begin(request)
    }

    /** [계속] on the low-battery question. */
    fun confirmBattery() {
        val request = waiting ?: return dismissDialog()
        waiting = null
        _dialog.value = null
        start(request)
    }

    /** [음악 없이 만들기]: the same video without music. */
    fun makeWithoutMusic() {
        val request = (waiting as? Request.Video) ?: return dismissDialog()
        waiting = null
        _dialog.value = null
        _sheet.update { it?.copy(options = it.options.copy(musicUri = null), musicName = null) }
        start(request.copy(options = request.options.copy(musicUri = null)))
    }

    /** [확인] / [취소] / dismissed (the unfinished job's [닫기] is [dismissUnfinished]). */
    fun dismissDialog() {
        waiting = null
        _dialog.value = null
    }

    /** [취소] on the progress card. */
    fun cancel() = job.cancel()

    /**
     * The [outcome] was taken on screen (the preview opened): the job goes back to idle. A failure's message is shown
     * here, as the other dialogs — except a job stopped by a recording (or any failure while one runs): spec §3.2 says
     * that by the notification only, and a dialog would cover the record screen of the ride that just started.
     */
    /**
     * The preview to open on screen for [outcome], or null: a failure, or a job that ended while a recording runs (its
     * last seconds of encoding cannot be stopped; final review M2). The preview is then not opened over the record
     * screen; the "영상을 만들었습니다" notification opens it later.
     */
    fun previewToOpen(outcome: VideoOutcome): VideoOutcome.Preview? = (outcome as? VideoOutcome.Preview)?.takeIf { !recording() }

    fun outcomeShown() {
        val shown = outcome.value
        val byRecording = shown is VideoOutcome.Message && (shown.text == VideoJob.MSG_RECORDING_STARTED || recording())
        if (shown is VideoOutcome.Message && !byRecording && _dialog.value == null) _dialog.value = VideoDialog.Message(shown.text)
        job.acknowledge()
    }

    /**
     * At app start (spec §3.3): a job the app was killed in the middle of is offered again — not while one runs, nor over
     * another dialog.
     */
    fun checkUnfinished() {
        viewModelScope.launch {
            if (job.isRunning) return@launch
            val pending = guarded("Could not read the unfinished video job") { job.unfinished() } ?: return@launch
            if (_dialog.value == null) _dialog.value = VideoDialog.Unfinished(pending)
        }
    }

    /** [다시 만들기]: the same options, without music. */
    fun retryUnfinished() {
        val pending = (_dialog.value as? VideoDialog.Unfinished)?.job ?: return
        _dialog.value = null
        val options = VideoOptionsJson.decode(pending.optionsJson)
        val request = when (pending.kind) {
            MediaKind.VIDEO -> Request.Video(pending.tripId, options.copy(musicUri = null), retry = true)
            MediaKind.IMAGE -> Request.Image(pending.tripId, options.aspect, retry = true)
        }
        begin(request)
    }

    /** [닫기] on the unfinished job: it is forgotten. */
    fun dismissUnfinished() {
        if (_dialog.value !is VideoDialog.Unfinished) return
        _dialog.value = null
        viewModelScope.launch { guarded("Could not forget the unfinished video job") { job.dismissUnfinished() } }
    }

    private fun begin(request: Request) {
        val level = try {
            battery()
        } catch (e: Exception) {
            log.warn("Could not read the battery", e)
            null
        }
        if (level != null && level.percent < LOW_BATTERY_PERCENT && !level.charging) {
            waiting = request
            _dialog.value = VideoDialog.LowBattery(level.percent)
        } else {
            start(request)
        }
    }

    private fun start(request: Request) {
        if (starting) return
        starting = true
        _sheet.update { it?.copy(starting = true) }
        viewModelScope.launch {
            val result = try {
                when (request) {
                    is Request.Video -> job.startVideo(request.tripId, request.options)
                    is Request.Image -> job.startImage(request.tripId, request.aspect)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // M10: the trip could not be read, or other unexpected IO; the claim is released, so a retry works.
                log.warn("Could not start the ${request.kind} of trip ${request.tripId}", e)
                null
            } finally {
                starting = false
                _sheet.update { it?.copy(starting = false) }
            }
            when (result) {
                null -> show(if (request.kind == MediaKind.VIDEO) VideoJob.MSG_VIDEO_FAILED else VideoJob.MSG_IMAGE_FAILED)
                StartResult.STARTED -> closeSheet()
                StartResult.MUSIC_UNREADABLE -> {
                    waiting = request
                    _dialog.value = VideoDialog.MusicUnreadable
                }
                StartResult.NOTHING_TO_MAKE -> {
                    closeSheet()
                    // An unfinished job of a trip with nothing left to make is not offered again.
                    if (request.retry) guarded("Could not forget the unfinished video job") { job.dismissUnfinished() }
                }
                else -> show(result.message(request.neededBytes))
            }
        }
    }

    private fun estimate() {
        val s = _sheet.value ?: return
        val loaded = inputs ?: return
        _sheet.value = s.copy(minutes = null)
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch {
            val perFrame = guarded("Could not read this phone's seconds per frame") { secondsPerFrame() }
                ?: VideoJob.DEFAULT_SECONDS_PER_FRAME
            val options = s.options
            val minutes = withContext(compute) {
                VideoEstimate.minutes(VideoTimeline.plan(loaded.timeline, loaded.photos, options, loaded.dayStarts), perFrame)
            }
            _sheet.update { if (it?.tripId == s.tripId && it.kind == MediaKind.VIDEO) it.copy(minutes = minutes) else it }
        }
    }

    private fun show(text: String?) {
        if (text != null) _dialog.value = VideoDialog.Message(text)
    }

    private suspend fun <T> guarded(message: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(message, e)
        null
    }

    private sealed interface Request {
        val tripId: Long
        val kind: MediaKind

        /** From [다시 만들기]. */
        val retry: Boolean
        val neededBytes: Long

        data class Video(override val tripId: Long, val options: VideoOptions, override val retry: Boolean = false) : Request {
            override val kind get() = MediaKind.VIDEO
            override val neededBytes get() = VideoJob.neededBytes(options)
        }

        data class Image(override val tripId: Long, val aspect: Aspect, override val retry: Boolean = false) : Request {
            override val kind get() = MediaKind.IMAGE
            override val neededBytes get() = VideoJob.IMAGE_BYTES
        }
    }

    companion object {
        const val LOW_BATTERY_PERCENT = 20

        /** Shown for a picked file whose name cannot be read. */
        const val MUSIC_PICKED = "고른 음악"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val context = container.appContext
                val loader = VideoInputsLoader(container.rideStore, container.postStore, container.tripStore)
                VideoViewModel(
                    job = container.videoJob,
                    loadInputs = { withContext(Dispatchers.IO) { loader.load(it) } },
                    lastOptions = { container.videoJobStore.lastOptions.first() },
                    saveOptions = container.videoJobStore::saveOptions,
                    secondsPerFrame = container.videoJobStore::secondsPerFrame,
                    recording = { container.rideState.let { it is RideUiState.Starting || it is RideUiState.Active } },
                    battery = { batteryLevel(context) },
                    musicName = { uri -> withContext(Dispatchers.IO) { displayName(context, uri) } },
                    log = androidWarnLog("VideoViewModel"),
                )
            }
        }

        private fun batteryLevel(context: Context): BatteryLevel? {
            val manager = context.getSystemService(BatteryManager::class.java) ?: return null
            val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent !in 0..100) return null
            return BatteryLevel(percent, manager.isCharging)
        }

        /** The picked file's name (the document provider's DISPLAY_NAME). */
        private fun displayName(context: Context, uri: String): String? =
            context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }
}

/** The progress card of [state]; null once the job has ended (the same text as the notification). */
internal fun progressOf(state: VideoJobState): VideoProgress? {
    val text = VideoNotifications.progressText(state) ?: return null
    return when (state) {
        is VideoJobState.Preparing -> VideoProgress(state.kind, null, text)
        is VideoJobState.Rendering -> VideoProgress(MediaKind.VIDEO, state.percent, text)
        else -> VideoProgress(MediaKind.VIDEO, 100, text)
    }
}

/** The card on trip [tripId]'s screens: only while the job makes that very trip (I1). */
fun progressFor(progress: VideoProgress?, runningTripId: Long?, tripId: Long): VideoProgress? =
    progress?.takeIf { runningTripId != null && runningTripId == tripId }

internal fun outcomeOf(state: VideoJobState): VideoOutcome? = when (state) {
    is VideoJobState.Done -> VideoOutcome.Preview(state.media.uri, state.media.kind, state.downscaled)
    is VideoJobState.Failed -> VideoOutcome.Message(state.message)
    else -> null
}

/**
 * [영상 만들기] [이미지 만들기] are offered (spec §1.1): the trip is COMPLETED and has a target segment — finished and at
 * least [MIN_RIDE_DISTANCE_M]. A recording does not hide them; [VideoViewModel.open] then says "기록 중에는 만들 수 없습니다".
 */
fun canMakeMedia(trip: Trip?, segments: List<RideSegment>): Boolean =
    trip?.status == TripStatus.COMPLETED && segments.any { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }

/** The trip of segment [segmentId] when the summary offers the buttons ([canMakeMedia]), else null. */
suspend fun mediaTripOf(segmentId: Long, rideStore: RideStore, tripStore: TripStore): Long? {
    val tripId = rideStore.segment(segmentId)?.tripId ?: return null
    return tripId.takeIf { canMakeMedia(tripStore.trip(tripId), rideStore.segments(tripId)) }
}

/**
 * The made files gone from the gallery (spec §5.1, ruling V6): only those [exists] says are gone — one whose check fails
 * is kept (a failed query is not "deleted").
 */
suspend fun missingMedia(media: List<TripMedia>, exists: suspend (uri: String) -> Boolean, log: WarnLog): List<Long> =
    media.filter { m ->
        try {
            !exists(m.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not check made file ${m.id}", e)
            false
        }
    }.map { it.id }
