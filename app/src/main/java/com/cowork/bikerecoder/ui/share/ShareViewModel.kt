package com.cowork.bikerecoder.ui.share

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
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripLabelStore
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.RideTitle
import com.cowork.bikerecoder.share.DEFAULT_SHARE_HASHTAGS
import com.cowork.bikerecoder.share.MAX_SHARE_TEXT
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.ShareFacts
import com.cowork.bikerecoder.share.SharePick
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.autoShareText
import com.cowork.bikerecoder.share.fileLines
import com.cowork.bikerecoder.share.moodOn
import com.cowork.bikerecoder.share.pickFile
import com.cowork.bikerecoder.share.shareStamp
import com.cowork.bikerecoder.share.toggleMood
import com.cowork.bikerecoder.ui.video.canMakeMedia
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.StartResult
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.TripMediaStore
import com.cowork.bikerecoder.video.VideoJob
import com.cowork.bikerecoder.video.VideoJobControl
import com.cowork.bikerecoder.video.VideoJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * The "활동 공유하기" sheet (V1.5.0 spec §1.2, §2, §3).
 *
 * [open] reads the trip, its finished rides of at least [MIN_RIDE_DISTANCE_M], its label, the default hashtags and the
 * gallery files ([candidates]); the sheet starts with the saved text or the auto text. [press] saves the text (null when
 * it equals the auto text) and then copies, sends the picked file, or makes a portrait summary image with [job] and does
 * the pressed action once it is [VideoJobState.Done]. A refused start or a failure leaves [ShareSheetState.blocked] with
 * [retry]. [close] drops the waiting action only: the job runs on, and its later outcome is the trip screen's as usual.
 */
class ShareViewModel(
    private val tripStore: TripStore,
    private val rideStore: RideStore,
    private val tripLabels: TripLabelStore,
    private val tripMedia: TripMediaStore,
    /** The default-hashtags setting: null = never saved ([DEFAULT_SHARE_HASHTAGS]), "" = none. */
    private val defaultHashtags: suspend () -> String?,
    /** The made files whose gallery item still exists ([com.cowork.bikerecoder.share.ShareCandidates.of]). */
    private val candidates: suspend (List<TripMedia>) -> List<ShareCandidate>,
    private val job: VideoJobControl,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = SEOUL,
) : ViewModel() {

    private val _sheet = MutableStateFlow<ShareSheetState?>(null)

    /** The open sheet; null while it is closed (or still being read). */
    val sheet: StateFlow<ShareSheetState?> = _sheet.asStateFlow()

    private val _events = Channel<ShareEvent>(Channel.BUFFERED)

    /** One-shot events; each is received by one collector only. */
    val events: Flow<ShareEvent> = _events.receiveAsFlow()

    private var openJob: Job? = null

    /** The record the sheet was opened on: its stamp and the end of its last ride (older files are not sent). */
    private var record: Record? = null

    /** The image being made for a pressed button, until it is done, failed, cancelled or dropped by [close]. */
    private var making: Making? = null

    /** The button that last asked for an image: what [retry] presses again. */
    private var lastMade: ShareTarget? = null

    /** Opens the sheet of trip [tripId]; a trip without a finished ride of 10 m or more opens nothing. */
    fun open(tripId: Long) {
        close()
        openJob = viewModelScope.launch {
            val opened = try {
                load(tripId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not read trip $tripId for sharing", e)
                null
            } ?: return@launch
            record = opened.first
            _sheet.value = opened.second
        }
    }

    /** The sheet is closed: edits are dropped, and so is an action waiting for its image (the job is not cancelled). */
    fun close() {
        openJob?.cancel()
        openJob = null
        drop()
        record = null
        lastMade = null
        _sheet.value = null
    }

    /** The text box changed; it holds at most [MAX_SHARE_TEXT] code points. */
    fun edit(text: String) {
        _sheet.update { it?.withText(RideTitle.truncateCodePoints(text, MAX_SHARE_TEXT)) }
    }

    /** [처음 글로]: the auto text again (saved on the next press). */
    fun reset() {
        _sheet.update { it?.withText(it.autoText) }
    }

    /** A mood chip: its hashtag is added or removed; a change that would pass [MAX_SHARE_TEXT] is not made. */
    fun toggle(mood: Mood) {
        _sheet.update { s ->
            val next = s?.let { toggleMood(it.text, mood) } ?: return@update s
            if (next.codePointCount(0, next.length) > MAX_SHARE_TEXT) s else s.withText(next)
        }
    }

    /**
     * A button (spec §1.2, §2): the text is saved, then [ShareTarget.COPY] copies it, a picked file is sent (or, for
     * [ShareTarget.SAVE], is already in the gallery), and a missing one is made first. When no image can be made the
     * dimmed buttons do nothing and [ShareTarget.EMAIL] / [ShareTarget.MORE] send the text only.
     */
    fun press(target: ShareTarget) {
        val s = _sheet.value ?: return
        if (s.making) return
        if (target == ShareTarget.COPY) {
            save(s)
            return emit(ShareEvent.Copy(s.text))
        }
        val pick = s.pick(target)
        when {
            pick is SharePick.File -> {
                save(s)
                emit(if (target == ShareTarget.SAVE) ShareEvent.Notice(MSG_ALREADY_SAVED) else ShareEvent.Send(target, pick.file, s.text))
            }
            s.canMakeImage -> {
                save(s)
                make(s, target)
            }
            target == ShareTarget.EMAIL || target == ShareTarget.MORE -> {
                save(s)
                emit(ShareEvent.Send(target, null, s.text))
            }
            // Dimmed on screen.
            else -> Unit
        }
    }

    /** [취소] while the image is made: the job stops (its file is deleted) and the action is dropped. */
    fun cancelMaking() {
        val m = making ?: return
        making = null
        m.dropped = true
        // Before the start answered, the job is cancelled as soon as it has started.
        if (m.started) {
            m.watch?.cancel()
            job.cancel()
        } else {
            m.cancelWhenStarted = true
        }
        _sheet.update { it?.copy(making = false) }
    }

    /** [다시 시도]: the refusal is cleared and the button that asked for the image is pressed again. */
    fun retry() {
        val target = lastMade ?: return
        val s = _sheet.value ?: return
        if (!s.canRetry || s.making) return
        _sheet.value = s.copy(blocked = null, canRetry = false)
        press(target)
    }

    private fun make(s: ShareSheetState, target: ShareTarget) {
        val m = Making(s.tripId, target, s.text)
        making = m
        lastMade = target
        _sheet.update { it?.copy(making = true) }
        m.watch = viewModelScope.launch {
            val result = try {
                job.startImage(s.tripId, Aspect.PORTRAIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not start the share image of trip ${s.tripId}", e)
                null
            }
            if (m.cancelWhenStarted) {
                if (result == StartResult.STARTED) job.cancel()
                return@launch
            }
            if (m.dropped) return@launch
            if (result != StartResult.STARTED) {
                making = null
                _sheet.update { it?.copy(making = false, blocked = refusal(result), canRetry = true) }
                return@launch
            }
            m.started = true
            val end = job.state.first { it.endsImageOf(m.tripId) }
            if (m.dropped) return@launch
            making = null
            when (end) {
                is VideoJobState.Done -> finish(m, end.media)
                is VideoJobState.Failed -> {
                    job.acknowledge()
                    _sheet.update { it?.copy(making = false, blocked = end.message, canRetry = true) }
                }
                // Cancelled elsewhere (its acknowledgement is the video screen's), or taken by the trip screen first.
                else -> {
                    _sheet.update { it?.copy(making = false) }
                    refreshPicks(m.tripId)
                }
            }
        }
    }

    /** The image is made: the job goes back to idle, the picks follow the new file, and the pressed action is done. */
    private suspend fun finish(m: Making, made: TripMedia) {
        job.acknowledge()
        val picks = refreshPicks(m.tripId)
        _sheet.update { if (it?.tripId == m.tripId) it.copy(making = false) else it }
        if (m.target == ShareTarget.SAVE) return emit(ShareEvent.Notice(MSG_SAVED))
        val file = (picks?.of(m.target) as? SharePick.File)?.file
            ?: ShareCandidate(made.uri, made.kind, Aspect.PORTRAIT, made.createdAt, sizeBytes = null)
        emit(ShareEvent.Send(m.target, file, m.text))
    }

    /** The files read again (after a made image) and the sheet's picks updated; null when they could not be read. */
    private suspend fun refreshPicks(tripId: Long): Picks? {
        val lastEndedAt = record?.lastEndedAt ?: return null
        val files = readCandidates(tripId) ?: return null
        val picks = withContext(compute) { Picks.of(files, lastEndedAt) }
        _sheet.update { s ->
            if (s?.tripId != tripId) return@update s
            s.copy(
                general = picks.general,
                story = picks.story,
                email = picks.email,
                fileLines = fileLines(picks.general, picks.story, picks.email),
            )
        }
        return picks
    }

    private suspend fun load(tripId: Long): Pair<Record, ShareSheetState>? {
        val trip = withContext(io) { tripStore.trip(tripId) } ?: return null
        val segments = withContext(io) { rideStore.segments(tripId) }
        val label = withContext(io) { tripLabels.label(tripId) }
        val tags = guarded("Could not read the default hashtags") { defaultHashtags() } ?: DEFAULT_SHARE_HASHTAGS
        val files = readCandidates(tripId).orEmpty()
        return withContext(compute) {
            val rides = segments.filter { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }
                .sortedBy { it.startedAt }
            if (rides.isEmpty()) return@withContext null
            val distanceM = rides.sumOf { it.totals.distanceM }
            val record = Record(shareStamp(rides.size, distanceM), rides.maxOf { it.endedAt!! })
            val autoText = autoShareText(facts(label, rides, distanceM), tags)
            val finished = canMakeMedia(trip, segments)
            val picks = Picks.of(files, record.lastEndedAt)
            val needsImage = picks.general == SharePick.MakeImage || picks.story == SharePick.MakeImage ||
                picks.email == SharePick.MakeImage
            val text = label.shareText ?: autoText
            record to ShareSheetState(
                tripId = tripId,
                text = text,
                autoText = autoText,
                stale = label.shareStamp != null && label.shareStamp != record.stamp,
                moods = moodsIn(text),
                general = picks.general,
                story = picks.story,
                email = picks.email,
                fileLines = fileLines(picks.general, picks.story, picks.email),
                blocked = if (!finished && needsImage) MSG_UNFINISHED else null,
                canRetry = false,
                making = false,
                tripFinished = finished,
            )
        }
    }

    private fun facts(label: TripLabel, rides: List<RideSegment>, distanceM: Double) = ShareFacts(
        title = RideTitle.shown(label, rides.first().startedAt, rides.map { it.localDate }.distinct().size, zone),
        firstDate = rides.first().localDate,
        lastDate = rides.last().localDate,
        distanceM = distanceM,
        movingTimeMs = rides.sumOf { it.totals.movingTimeMs },
        ascentM = rides.sumOf { it.totals.ascentM },
        regionName = label.regionName,
    )

    /** The trip's usable gallery files; null (logged) when they cannot be read. */
    private suspend fun readCandidates(tripId: Long): List<ShareCandidate>? = guarded("Could not read the files of trip $tripId") {
        candidates(withContext(io) { tripMedia.observe(tripId).first() })
    }

    /** Spec §3.4: the auto text is saved as null (it follows the title and region); an edit with the record's stamp. */
    private fun save(s: ShareSheetState) {
        val stamp = record?.stamp ?: return
        val text = s.text.takeIf { it != s.autoText }
        viewModelScope.launch {
            guarded("Could not save the share text of trip ${s.tripId}") {
                withContext(io) { tripLabels.setShareText(s.tripId, text, text?.let { stamp }) }
            }
        }
    }

    /** Drops the action waiting for its image; a job already started runs on. */
    private fun drop() {
        val m = making ?: return
        making = null
        m.dropped = true
        if (m.started) m.watch?.cancel()
    }

    private fun emit(event: ShareEvent) {
        _events.trySend(event)
    }

    private suspend fun <T> guarded(message: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(message, e)
        null
    }

    private fun ShareSheetState.withText(next: String) = copy(text = next, moods = moodsIn(next))

    private data class Record(val stamp: String, val lastEndedAt: Long)

    /** An image made for [target]; [text] is the text saved at the press, sent once the image is done. */
    private class Making(val tripId: Long, val target: ShareTarget, val text: String) {
        var watch: Job? = null
        var started = false
        var dropped = false
        var cancelWhenStarted = false
    }

    private data class Picks(val general: SharePick, val story: SharePick, val email: SharePick) {
        fun of(target: ShareTarget): SharePick = when (target) {
            ShareTarget.STORY -> story
            ShareTarget.EMAIL -> email
            else -> general
        }

        companion object {
            fun of(files: List<ShareCandidate>, lastEndedAt: Long) = Picks(
                general = pickFile(ShareTarget.FACEBOOK, files, lastEndedAt),
                story = pickFile(ShareTarget.STORY, files, lastEndedAt),
                email = pickFile(ShareTarget.EMAIL, files, lastEndedAt),
            )
        }
    }

    companion object {
        const val MSG_UNFINISHED = "여행을 마치면 그림을 만들 수 있습니다"
        const val MSG_BUSY = "영상을 만드는 중이라 지금은 이미지를 만들 수 없습니다"
        const val MSG_NO_INTERNET = "인터넷이 없어 이미지를 만들 수 없습니다"
        const val MSG_ALREADY_SAVED = "갤러리에 저장되어 있습니다"
        const val MSG_SAVED = "갤러리에 저장했습니다"
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /** Why the image was not started (spec §2.5); a start that threw reads as a failed image. */
        internal fun refusal(result: StartResult?): String = when (result) {
            StartResult.BUSY -> MSG_BUSY
            StartResult.NO_INTERNET -> MSG_NO_INTERNET
            null -> VideoJob.MSG_IMAGE_FAILED
            else -> result.message(VideoJob.IMAGE_BYTES) ?: VideoJob.MSG_IMAGE_FAILED
        }

        private fun moodsIn(text: String): Set<Mood> = Mood.entries.filterTo(mutableSetOf()) { moodOn(text, it) }

        /** The image of trip [tripId] ended: made, failed, cancelled, or already taken back to idle elsewhere. */
        private fun VideoJobState.endsImageOf(tripId: Long): Boolean = when (this) {
            is VideoJobState.Done -> media.tripId == tripId && media.kind == MediaKind.IMAGE
            is VideoJobState.Failed, VideoJobState.Cancelled, VideoJobState.Idle -> true
            else -> false
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShareViewModel(
                    tripStore = container.tripStore,
                    rideStore = container.rideStore,
                    tripLabels = container.tripLabels,
                    tripMedia = container.tripMedia,
                    defaultHashtags = { container.settings.settings.first().shareHashtags },
                    candidates = container.shareCandidates::of,
                    job = container.videoJob,
                    log = androidWarnLog("ShareViewModel"),
                )
            }
        }
    }
}
