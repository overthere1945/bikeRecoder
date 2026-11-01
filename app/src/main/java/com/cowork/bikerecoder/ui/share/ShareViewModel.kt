package com.cowork.bikerecoder.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.activity.ActivityMix
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripLabelStore
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.video.RideTitle
import com.cowork.bikerecoder.data.hashtagsFor
import com.cowork.bikerecoder.share.MAX_SHARE_TEXT
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.NoSpaceException
import com.cowork.bikerecoder.share.ShareCache
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.ShareFacts
import com.cowork.bikerecoder.share.ShareFile
import com.cowork.bikerecoder.share.ShareGallery
import com.cowork.bikerecoder.share.ShareGalleryNames
import com.cowork.bikerecoder.share.ShareItem
import com.cowork.bikerecoder.share.ShareMode
import com.cowork.bikerecoder.share.ShareSelection
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.autoShareText
import com.cowork.bikerecoder.share.cards.PhotoUnreadableException
import com.cowork.bikerecoder.share.cards.ShareCardMaker
import com.cowork.bikerecoder.share.defaultShareHashtags
import com.cowork.bikerecoder.share.moodOn
import com.cowork.bikerecoder.share.shareItems
import com.cowork.bikerecoder.share.shareStamp
import com.cowork.bikerecoder.share.toggleMood
import com.cowork.bikerecoder.share.toggleTag
import com.cowork.bikerecoder.ui.video.canMakeMedia
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.TripMediaStore
import com.cowork.bikerecoder.video.mimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * The "활동 공유하기" sheet (share cards spec §1, §3.1, §5, §6; the text as in V1.5.0 spec §3).
 *
 * [open] sweeps the share [cache], reads the trip, its finished rides of at least [MIN_RIDE_DISTANCE_M], its label, the
 * default hashtags, the place tags ([placeTags], added to the auto text), its photos ([photoIds]) and its newest video
 * made after the last ride ([candidates]); then [cards] draws this mode's satellite card and, with photos, the photo
 * card. [press] saves the text (null when it equals the auto text) and copies it, waits for the picked cards and sends
 * them with their photo slides or the video, saves the picked cards ([저장]) or draws and saves the sticker ([스티커]).
 * [cancelMaking] and [close] drop a waiting send; [close] also drops what is still drawn for the sheet.
 */
class ShareViewModel(
    private val tripStore: TripStore,
    private val rideStore: RideStore,
    private val tripLabels: TripLabelStore,
    private val tripMedia: TripMediaStore,
    /**
     * The default-hashtags setting of an activity (the trip's dominant one): null = never saved ([defaultShareHashtags]),
     * "" = none.
     */
    private val defaultHashtags: suspend (ActivityType) -> String?,
    /** The made files whose gallery item still exists ([com.cowork.bikerecoder.share.ShareCandidates.of]). */
    private val candidates: suspend (List<TripMedia>) -> List<ShareCandidate>,
    /** The trip's shown photos that can still be read, in the order they were taken (spec §1.2). */
    private val photoIds: suspend (Long) -> List<Long>,
    /** The "지나간 길" tags of the trip (spec §4.5; [com.cowork.bikerecoder.share.PlaceTags.of]). */
    private val placeTags: suspend (Long) -> List<String>,
    private val cards: ShareCardMaker,
    private val gallery: ShareGallery,
    /** Swept on [open] (spec §3.2); null: nothing to sweep. */
    private val cache: ShareCache?,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = SEOUL,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _sheet = MutableStateFlow<ShareSheetState?>(null)

    /** The open sheet; null while it is closed (or still being read). */
    val sheet: StateFlow<ShareSheetState?> = _sheet.asStateFlow()

    private val _events = Channel<ShareEvent>(Channel.BUFFERED)

    /** One-shot events; each is received by one collector only. */
    val events: Flow<ShareEvent> = _events.receiveAsFlow()

    private var openJob: Job? = null

    /** The open sheet's drawing, waiting and saving; a result of another (closed) sheet is not taken. */
    private var session: Session? = null

    /** The record the sheet was opened on: its stamp and the text saved for the trip. */
    private var record: Record? = null

    /** Saves run one at a time, in the order of the presses. */
    private val saveLock = Mutex()

    /** Opens the sheet of trip [tripId]; a trip without a finished ride of 10 m or more opens nothing. */
    fun open(tripId: Long) {
        close()
        // The maker keeps the last trip it drew; the trip may have changed since (spec §3.1).
        cards.forget()
        val session = Session(tripId, SupervisorJob(viewModelScope.coroutineContext[Job]))
        this.session = session
        openJob = viewModelScope.launch {
            cache?.let { c -> guarded("Could not sweep the share cache") { withContext(io) { c.sweep() } } }
            val opened = guarded("Could not read trip $tripId for sharing") { load(tripId) } ?: return@launch
            record = opened.first
            _sheet.value = opened.second
            renderMissing(session)
        }
    }

    /** The sheet is closed: edits are dropped, and so are a waiting send and the cards still drawn for it. */
    fun close() {
        openJob?.cancel()
        openJob = null
        session?.job?.cancel()
        session = null
        record = null
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
    fun toggle(mood: Mood) = changeText { toggleMood(it, mood) }

    /** A "지나간 길" chip (spec §4.5): [tag] is added or removed as a mood's is. */
    fun togglePlace(tag: String) = changeText { toggleTag(it, tag) }

    /**
     * [게시물 4:5] / [스토리 9:16] (spec §1.2): the mode's first selection, and its cards drawn unless they are. A waiting
     * press is dropped: it was meant for the other mode's picks.
     */
    fun setMode(mode: ShareMode) {
        val session = session ?: return
        if (_sheet.value?.mode == mode) return
        dropPending(session)
        val s = _sheet.value ?: return
        _sheet.value = s.copy(mode = mode, selection = ShareSelection.initial(mode, s.items, s.tripFinished))
        renderMissing(session)
    }

    /**
     * An item of the strip is tapped (spec §1.2); a card cannot be picked on an unfinished trip. A waiting press is
     * dropped: it was meant for the picks it was pressed with.
     */
    fun tap(item: ShareItem) {
        val session = session ?: return
        val before = _sheet.value ?: return
        if (item !in before.items || (!before.tripFinished && before.keyOf(item) != null)) return
        dropPending(session)
        val s = _sheet.value ?: return
        val (selection, notice) = s.selection.toggle(item)
        _sheet.value = s.copy(selection = selection)
        notice?.let { emit(ShareEvent.Notice(it)) }
    }

    /** The photo card's background is photo [mediaId] (spec §1.2): only the photo card is drawn again. */
    fun setPhotoCard(mediaId: Long) {
        val session = session ?: return
        val s = _sheet.value ?: return
        if (s.photoCardMediaId == mediaId || s.items.none { it is ShareItem.Photo && it.mediaId == mediaId }) return
        _sheet.value = s.copy(photoCardMediaId = mediaId)
        renderMissing(session)
        proceed(session)
    }

    /**
     * [다시 시도] (spec §6): the error goes away and the cards that failed are drawn again; when the error came from a
     * press itself (a slide, the sticker or the gallery failed), that button is pressed again.
     */
    fun retryCards() {
        val session = session ?: return
        val s = _sheet.value ?: return
        val again = session.retry
        session.retry = null
        _sheet.value = s.copy(error = null)
        s.cards.filterValues { it is CardStatus.Failed }.keys.forEach { render(session, it) }
        again?.let(::press)
    }

    /**
     * A button (spec §1.3, §5): the text is saved, then [ShareTarget.COPY] copies it, [ShareTarget.STICKER] saves the
     * sticker, [ShareTarget.SAVE] the picked cards, and the others send what is picked once its cards are drawn. With
     * nothing picked, [ShareTarget.EMAIL] and [ShareTarget.MORE] send the text only and the dimmed buttons do nothing.
     * A press takes away the error of an earlier one.
     */
    fun press(target: ShareTarget) {
        val session = session ?: return
        val s = _sheet.value ?: return
        if (s.waiting || !s.enabled(target)) return
        // A new press: the error line of an earlier one (and its [다시 시도]) goes away.
        session.retry = null
        if (s.error != null) _sheet.update { it?.copy(error = null) }
        when (target) {
            ShareTarget.COPY -> {
                save(s)
                emit(ShareEvent.Copy(s.text))
            }
            ShareTarget.STICKER -> {
                save(s)
                sticker(session)
            }
            ShareTarget.SAVE -> {
                save(s)
                val keys = s.selection.chosen.mapNotNull(s::keyOf)
                when {
                    keys.isEmpty() -> emit(ShareEvent.Notice(MSG_ALREADY_IN_GALLERY))
                    keys.all { it in session.savedCards } -> emit(ShareEvent.Notice(MSG_ALREADY_SAVED))
                    else -> wait(session, target, s.text)
                }
            }
            else -> {
                save(s)
                if (s.selection.chosen.isEmpty()) emit(ShareEvent.Send(target, emptyList(), s.text)) else wait(session, target, s.text)
            }
        }
    }

    /** [취소] while waiting (spec §3.1): only the send is dropped; the cards go on being drawn. */
    fun cancelMaking() {
        dropPending(session ?: return)
    }

    /** The waiting press (and its send or save once begun) is dropped; the cards go on being drawn. */
    private fun dropPending(session: Session) {
        if (session.pending == null) return
        session.pending = null
        session.finishing?.cancel()
        session.finishing = null
        _sheet.update { it?.copy(waiting = false) }
    }

    /** [target] waits for the picked cards ([proceed]); [text] is what it sends. */
    private fun wait(session: Session, target: ShareTarget, text: String) {
        session.pending = Pending(target, text)
        _sheet.update { it?.copy(waiting = true, error = null) }
        proceed(session)
    }

    /**
     * The waiting press goes on once every picked card is drawn: a failed one ends it with [ShareSheetState.error], one not
     * drawn yet (a changed background) is drawn and waited for. Nothing picked any more ends it as a press with nothing
     * picked.
     */
    private fun proceed(session: Session) {
        val pending = session.pending ?: return
        if (session.finishing != null || this.session !== session) return
        val s = _sheet.value ?: return
        if (s.selection.chosen.isEmpty()) {
            session.pending = null
            _sheet.value = s.copy(waiting = false)
            if (pending.target == ShareTarget.EMAIL || pending.target == ShareTarget.MORE) {
                emit(ShareEvent.Send(pending.target, emptyList(), pending.text))
            }
            return
        }
        val keys = s.selection.chosen.mapNotNull(s::keyOf)
        val failed = keys.mapNotNull { s.cards[it] as? CardStatus.Failed }
        if (failed.isNotEmpty()) {
            session.pending = null
            _sheet.value = s.copy(waiting = false, error = if (failed.any { it.noSpace }) MSG_NO_SPACE else MSG_CARD_FAILED)
            return
        }
        keys.filter { it !in s.cards }.forEach { render(session, it) }
        if (keys.any { s.cards[it] !is CardStatus.Ready }) return
        // Lazy: on an immediate dispatcher a send that never suspends ends (and clears [Session.finishing]) inside launch.
        val finishing = viewModelScope.launch(session.job, start = CoroutineStart.LAZY) {
            if (pending.target == ShareTarget.SAVE) saveCards(session, pending, s) else send(session, pending, s)
        }
        session.finishing = finishing
        finishing.start()
    }

    /** Sends the picked items of [s] in order: cards as drawn, photos as slides drawn now, the video as it is. */
    private suspend fun send(session: Session, pending: Pending, s: ShareSheetState) {
        val files = mutableListOf<ShareFile>()
        var unreadable = 0
        for (item in s.selection.chosen) {
            when (item) {
                ShareItem.SatelliteCard, ShareItem.PhotoCard -> files += (s.cardOf(item) as CardStatus.Ready).file
                is ShareItem.Photo -> try {
                    files += cards.slide(session.tripId, item.mediaId, s.mode.size)
                } catch (e: PhotoUnreadableException) {
                    // Spec §6: the photo leaves the strip; the others are sent.
                    unreadable++
                    photoGone(session, item.mediaId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Could not draw the slide of photo ${item.mediaId}", e)
                    return fail(session, pending, e is NoSpaceException)
                }
                is ShareItem.Video -> files += item.candidate.let { ShareFile(it.uri, it.kind.mimeType, it.sizeBytes) }
            }
        }
        if (!end(session, pending)) return
        if (files.isNotEmpty() || pending.target == ShareTarget.EMAIL || pending.target == ShareTarget.MORE) {
            emit(ShareEvent.Send(pending.target, files, pending.text))
        } else if (unreadable > 0) {
            // Every picked photo became unreadable (final review M1): the press is not a silent no-op.
            emit(ShareEvent.Notice(MSG_PHOTOS_UNREADABLE))
        }
    }

    /** [저장] (spec §5.5): the picked cards not saved by this sheet yet go into the gallery as JPEGs. */
    private suspend fun saveCards(session: Session, pending: Pending, s: ShareSheetState) {
        val keys = s.selection.chosen.mapNotNull(s::keyOf)
        val unsaved = keys.filterNot { it in session.savedCards }
        try {
            for (key in unsaved) {
                gallery.saveJpeg((s.cards[key] as CardStatus.Ready).file, ShareGalleryNames.card(clock(), zone))
                session.savedCards += key
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not save the cards of trip ${session.tripId}", e)
            return fail(session, pending, e is NoSpaceException)
        }
        if (!end(session, pending)) return
        emit(
            ShareEvent.Notice(
                when {
                    keys.isEmpty() -> MSG_ALREADY_IN_GALLERY
                    unsaved.isEmpty() -> MSG_ALREADY_SAVED
                    else -> msgSavedCards(unsaved.size)
                },
            ),
        )
    }

    /**
     * [스티커] (spec §5.6): drawn and saved into the gallery once per sheet. Once begun it runs outside the sheet's
     * session, so a send that closes the sheet meanwhile (to paste the sticker into a story) does not cancel it and delete
     * the half-saved item (final review M2); its notice is only told while the sheet is open.
     */
    private fun sticker(session: Session) {
        if (session.stickerSaved) return emit(ShareEvent.Notice(MSG_ALREADY_SAVED))
        if (session.sticker?.isActive == true) return
        session.sticker = viewModelScope.launch {
            try {
                gallery.savePng(cards.sticker(session.tripId), ShareGalleryNames.sticker(clock(), zone))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not save the sticker of trip ${session.tripId}", e)
                if (this@ShareViewModel.session === session) failed(session, ShareTarget.STICKER, e is NoSpaceException)
                return@launch
            }
            session.stickerSaved = true
            if (this@ShareViewModel.session === session) emit(ShareEvent.Notice(MSG_STICKER_SAVED, long = true))
        }
    }

    /** The waiting press is over; false when it was dropped meanwhile (nothing more is done for it). */
    private fun end(session: Session, pending: Pending): Boolean {
        if (this.session !== session || session.pending !== pending) return false
        session.pending = null
        session.finishing = null
        _sheet.update { it?.copy(waiting = false) }
        return true
    }

    private fun fail(session: Session, pending: Pending, noSpace: Boolean) {
        if (end(session, pending)) failed(session, pending.target, noSpace)
    }

    /** A press of [target] failed by itself (not for a card drawn earlier): the error, and [retryCards] presses it again. */
    private fun failed(session: Session, target: ShareTarget, noSpace: Boolean) {
        session.retry = target
        _sheet.update { it?.copy(error = if (noSpace) MSG_NO_SPACE else MSG_CARD_FAILED) }
    }

    /** Draws the current mode's cards that are not drawn (or drawing) yet; none on an unfinished trip. */
    private fun renderMissing(session: Session) {
        if (this.session !== session) return
        val s = _sheet.value ?: return
        if (!s.tripFinished) return
        val items = listOf(ShareItem.SatelliteCard, ShareItem.PhotoCard).filter { it in s.items }
        items.mapNotNull(s::keyOf).filter { it !in s.cards }.forEach { render(session, it) }
    }

    /** Draws the card of [key]; its outcome is taken while [session] is the open sheet, and a waiting press goes on. */
    private fun render(session: Session, key: CardKey) {
        _sheet.update { it?.copy(cards = it.cards + (key to CardStatus.Rendering)) }
        viewModelScope.launch(session.job) {
            val status = try {
                CardStatus.Ready(cards.card(session.tripId, key.kind, key.mode.size, key.photoMediaId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoUnreadableException) {
                photoGone(session, e.mediaId)
                return@launch
            } catch (e: Exception) {
                log.warn("Could not draw the ${key.kind} card of trip ${session.tripId}", e)
                CardStatus.Failed(noSpace = e is NoSpaceException)
            }
            if (this@ShareViewModel.session !== session) return@launch
            _sheet.update { it?.copy(cards = it.cards + (key to status)) }
            proceed(session)
        }
    }

    /**
     * Photo [mediaId] cannot be read (spec §6): it leaves the strip and the selection (the photos are numbered again);
     * the photo card takes the next photo, or leaves too when none is left.
     */
    private fun photoGone(session: Session, mediaId: Long) {
        if (this.session !== session) return
        _sheet.update { s ->
            if (s == null) return@update null
            val before = s.items.filterIsInstance<ShareItem.Photo>().map { it.mediaId }
            if (mediaId !in before) return@update s
            val ids = before - mediaId
            val video = s.items.filterIsInstance<ShareItem.Video>().firstOrNull()?.candidate
            val items = shareItems(ids, video)
            val photos = items.filterIsInstance<ShareItem.Photo>().associateBy { it.mediaId }
            val chosen = s.selection.chosen.mapNotNull { item ->
                when (item) {
                    is ShareItem.Photo -> photos[item.mediaId]
                    ShareItem.PhotoCard -> item.takeIf { ids.isNotEmpty() }
                    else -> item
                }
            }
            val background = if (s.photoCardMediaId == mediaId) ids.getOrNull(before.indexOf(mediaId)) ?: ids.firstOrNull() else s.photoCardMediaId
            s.copy(
                items = items,
                selection = s.selection.copy(chosen = chosen),
                photoCardMediaId = background,
                cards = s.cards.filterKeys { it.photoMediaId != mediaId },
            )
        }
        renderMissing(session)
        proceed(session)
    }

    private fun changeText(change: (String) -> String) {
        _sheet.update { s ->
            val next = s?.let { change(it.text) } ?: return@update s
            if (next.codePointCount(0, next.length) > MAX_SHARE_TEXT) s else s.withText(next)
        }
    }

    private suspend fun load(tripId: Long): Pair<Record, ShareSheetState>? {
        val trip = withContext(io) { tripStore.trip(tripId) } ?: return null
        val segments = withContext(io) { rideStore.segments(tripId) }
        val rides = segments.filter { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }.sortedBy { it.startedAt }
        if (rides.isEmpty()) return null
        val label = withContext(io) { tripLabels.label(tripId) }
        val lastEndedAt = rides.maxOf { it.endedAt!! }
        // The three reads grow with the trip (final review I1): run together, each failing on its own into "none".
        val (video, photos, places) = coroutineScope {
            val video = async {
                guarded("Could not read the files of trip $tripId") {
                    candidates(withContext(io) { tripMedia.observe(tripId).first() })
                }.orEmpty().filter { it.kind == MediaKind.VIDEO && it.createdAt > lastEndedAt }.maxByOrNull { it.createdAt }
            }
            val photos = async { guarded("Could not read the photos of trip $tripId") { photoIds(tripId) }.orEmpty() }
            // Every track point of the trip is read and mapped: off the main thread.
            val places = async {
                guarded("Could not match the places of trip $tripId") { withContext(io) { placeTags(tripId) } }.orEmpty()
            }
            Triple(video.await(), photos.await(), places.await())
        }
        val dominant = ActivityMix.of(rides).dominant
        val tags = guarded("Could not read the default hashtags") { defaultHashtags(dominant) } ?: defaultShareHashtags(dominant)
        return withContext(compute) {
            val distanceM = rides.sumOf { it.totals.distanceM }
            val record = Record(
                stamp = shareStamp(rides.size, distanceM),
                savedText = label.shareText,
                savedStamp = label.shareText?.let { label.shareStamp },
            )
            val autoText = autoShareText(facts(label, rides, distanceM, dominant), tags, places)
            val finished = canMakeMedia(trip, segments)
            val items = shareItems(photos, video)
            val text = label.shareText ?: autoText
            record to ShareSheetState(
                tripId = tripId,
                text = text,
                autoText = autoText,
                stale = record.staleFor(text),
                moods = moodsIn(text),
                mode = ShareMode.FEED,
                items = items,
                selection = ShareSelection.initial(ShareMode.FEED, items, cardsAllowed = finished),
                cards = emptyMap(),
                photoCardMediaId = photos.firstOrNull(),
                placeTags = places,
                tripFinished = finished,
                waiting = false,
                error = null,
            )
        }
    }

    private fun facts(label: TripLabel, rides: List<RideSegment>, distanceM: Double, activity: ActivityType): ShareFacts {
        // A run's or walk's pace: the dominant activity's rides only (a one-activity trip: its totals).
        val paceOf = rides.filter { it.activity == activity }.takeIf { it.size < rides.size }
        return ShareFacts(
            title = RideTitle.shown(label, rides.first().startedAt, rides.map { it.localDate }.distinct().size, zone, activity),
            firstDate = rides.first().localDate,
            lastDate = rides.last().localDate,
            distanceM = distanceM,
            movingTimeMs = rides.sumOf { it.totals.movingTimeMs },
            ascentM = rides.sumOf { it.totals.ascentM },
            regionName = label.regionName,
            activity = activity,
            elapsedMs = rides.filter { it.activity == ActivityType.HIKE }.sumOf { it.totals.elapsedMs },
            maxAltitudeM = rides.mapNotNull { it.totals.maxAltitudeM }.maxOrNull(),
            paceDistanceM = paceOf?.sumOf { it.totals.distanceM },
            paceMovingTimeMs = paceOf?.sumOf { it.totals.movingTimeMs },
        )
    }

    /**
     * Spec §3.4: the auto text is saved as null (it follows the title and region); an edit with the record's stamp.
     * Saves run in the order of the presses; the sheet's stale line goes away (the text now matches the record).
     */
    private fun save(s: ShareSheetState) {
        val current = record ?: return
        val text = s.text.takeIf { it != s.autoText }
        val stamp = text?.let { current.stamp }
        val saved = current.copy(savedText = text, savedStamp = stamp)
        record = saved
        _sheet.update { if (it?.tripId == s.tripId) it.copy(stale = saved.staleFor(it.text)) else it }
        viewModelScope.launch {
            saveLock.withLock {
                guarded("Could not save the share text of trip ${s.tripId}") {
                    withContext(io) { tripLabels.setShareText(s.tripId, text, stamp) }
                }
            }
        }
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

    private fun ShareSheetState.withText(next: String) =
        copy(text = next, moods = moodsIn(next), stale = record?.staleFor(next) ?: false)

    /**
     * The record the sheet was opened on ([stamp]) and the text saved for the trip with the stamp it was written against
     * (both null while the auto text is used).
     */
    private data class Record(val stamp: String, val savedText: String?, val savedStamp: String?) {
        /** Spec §3.4: "기록이 바뀌어…" while [text] is the saved text and that was written against another record. */
        fun staleFor(text: String): Boolean = savedText != null && text == savedText && savedStamp != stamp
    }

    /**
     * One open sheet of trip [tripId]: its drawing, sending and saving run under [job] (cancelled by [close]; a sticker
     * save once begun outlives it, see [sticker]). [pending]
     * is the press waiting for its cards and [finishing] its send or save once they are drawn; [savedCards] and
     * [stickerSaved] what this sheet put into the gallery (spec §5.5, §5.6); [retry] the button [retryCards] presses
     * again after it failed by itself.
     */
    private class Session(val tripId: Long, val job: Job) {
        var retry: ShareTarget? = null
        var pending: Pending? = null
        var finishing: Job? = null
        val savedCards = mutableSetOf<CardKey>()
        var sticker: Job? = null
        var stickerSaved = false
    }

    /** A press of [target] waiting for its cards; [text] is the text at the press. */
    private class Pending(val target: ShareTarget, val text: String)

    companion object {
        const val MSG_UNFINISHED = "여행을 마치면 카드를 만들 수 있습니다"
        const val MSG_CARD_FAILED = "카드를 만들지 못했습니다"
        const val MSG_PHOTOS_UNREADABLE = "사진을 읽지 못했습니다"
        const val MSG_NO_SPACE = "저장 공간이 부족해 카드를 만들지 못했습니다"
        const val MSG_ALREADY_IN_GALLERY = "사진과 영상은 이미 갤러리에 있습니다"
        const val MSG_ALREADY_SAVED = "이미 저장했습니다"
        const val MSG_STICKER_SAVED = "스티커를 저장했습니다. 인스타 스토리에서 사진 스티커로 붙이세요"
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /** [저장] put [n] cards into the gallery (spec §5.5). */
        fun msgSavedCards(n: Int) = "카드 ${n}장을 갤러리에 저장했습니다"

        private fun moodsIn(text: String): Set<Mood> = Mood.entries.filterTo(mutableSetOf()) { moodOn(text, it) }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShareViewModel(
                    tripStore = container.tripStore,
                    rideStore = container.rideStore,
                    tripLabels = container.tripLabels,
                    tripMedia = container.tripMedia,
                    defaultHashtags = { type -> container.settings.settings.first().hashtagsFor(type) },
                    candidates = container.shareCandidates::of,
                    photoIds = container::sharePhotoIds,
                    placeTags = container.placeTags::of,
                    cards = container.shareCardMaker,
                    gallery = container.shareGallery,
                    cache = container.shareCache,
                    log = androidWarnLog("ShareViewModel"),
                )
            }
        }
    }
}
