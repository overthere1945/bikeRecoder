package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.navigation.KmAnnouncementSource
import com.cowork.bikerecoder.core.recording.DayTotals
import com.cowork.bikerecoder.core.recording.RecordingEvent
import com.cowork.bikerecoder.core.recording.RecordingSession
import com.cowork.bikerecoder.core.recording.RecordingUpdate
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.toTotals
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.core.voice.KoreanPhrases
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.location.PressureSource
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.guidingTripId
import com.cowork.bikerecoder.tts.VoiceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How a recording ends: only this segment, today of a multi-day trip ("오늘은 여기까지"), or the whole trip. */
enum class RecordingEnd { SEGMENT_ONLY, STOP_TODAY, COMPLETE_TRIP }

/**
 * Runs one [RecordingSession] (one ride segment) at a time, outside any UI lifecycle — the recording counterpart of
 * [NavigationController].
 *
 * [startRecording] closes every leftover open segment at its last save (a recording killed without a stop), opens a
 * new segment of the trip and starts the session seeded with the trip's distance today ([zone]'s date), so the
 * kilometre announcements continue across segments of a day. While it records, this controller feeds the session from
 * [location] (resubscribed [LOCATION_RETRY_MS] after the stream fails or ends — the session keeps running and its GPS
 * watchdog reports the gap), from [pressure] and with ticks every [tickIntervalMs] by [clock]; every [saveIntervalMs]
 * it appends the buffered points with the totals so far (also while paused). The trip is touched when the recording
 * starts and with a successful save at most every [TOUCH_INTERVAL_MS] (best effort). A failed save keeps the points for the
 * next one and raises [RideUiState.Active.saveWarning] once. Session events are spoken unless the voice setting is
 * off; a kilometre is announced here only while no guidance runs (guidance announces the combined sentence then).
 *
 * With guidance ([navigation], followed on [scope] for the controller's lifetime): when a guidance becomes active
 * and `autoStartRecordingWithNavigation` is on, a controller that is not recording (idle, or still showing a summary
 * or a start error) starts recording its trip — not while the guidance is still starting, so a start whose routing
 * fails leaves no recording; when any guidance of the recorded trip finishes (arrival, completed, stopped today, also
 * one whose trip write failed, `error != null` — the guidance has ended either way) the recording stops as
 * [RecordingEnd.SEGMENT_ONLY] (the guidance already changed the trip); a guidance that is only closed leaves the
 * recording running. While recording, this controller is [NavigationController.kmSource] — one combined kilometre
 * sentence counted on the recording's day distance — set when the session starts, set again when the day distance
 * falls (a new day, to re-base the count) and cleared when the recording ends. Accepted narrow loss: a km sentence the
 * guidance deferred (a turn was imminent) is not spoken if the guidance is closed before it comes due — the recording
 * already counted that km and does not repeat it.
 *
 * [splitSegment] closes the running segment and opens the next one of the same trip without passing through a summary
 * or Idle; [announce] and the split's start sentence also go to [notice] (shown once on screen, voice or not). Ruling
 * U1: a guidance started into this recording that arrives ends alone ([NavigationController.onArrivedKeepingRecording]);
 * its arrival sentence goes to [notice] and the recording goes on.
 *
 * Threading: every public method must be called on [scope]'s single thread (Main in the app); the session and all its
 * inputs run there too, and the per-fix handler never suspends (store writes run in their own coroutines). Store and
 * trip writes at the end run [NonCancellable] in [scope], so they finish even if the caller goes away.
 */
class RideController(
    private val rideStore: RideStore,
    private val tripStore: TripStore,
    private val tripManager: TripManager,
    private val navigation: NavigationController,
    private val voice: VoiceOutput,
    private val location: LocationSource,
    private val pressure: PressureSource?,
    private val settings: Flow<AppSettings>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val phrases: KoreanPhrases = KoreanPhrases(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val tickIntervalMs: Long = 1_000,
    private val saveIntervalMs: Long = 5_000,
    private val log: WarnLog = androidWarnLog(TAG),
) : RideControls {
    constructor(container: AppContainer, scope: CoroutineScope) : this(
        rideStore = container.rideStore,
        tripStore = container.tripStore,
        tripManager = container.tripManager,
        navigation = container.navigation,
        voice = container.voice,
        location = container.rideLocation,
        pressure = container.barometer,
        settings = container.settings.settings,
        scope = scope,
    )

    private val _ui = MutableStateFlow<RideUiState>(RideUiState.Idle)
    val ui: StateFlow<RideUiState> = _ui.asStateFlow()

    private val _notice = MutableStateFlow<ScreenNotice?>(null)

    /** The latest on-screen notice until the screen reports it shown ([noticeShown]); null: none. */
    val notice: StateFlow<ScreenNotice?> = _notice.asStateFlow()
    private var noticeIds = 0L

    /**
     * The location stream kept subscribed while [splitSegment] closes one segment and opens the next, so the shared
     * stream never loses its last subscriber; [fixes] are the ones the closed segment did not take, [takenUpTo] the time
     * of the last fix it took.
     */
    private class Bridge(val fixes: MutableList<LocationFix>, val job: Job) {
        var takenUpTo = Long.MIN_VALUE
    }

    /**
     * Everything belonging to one recording (one segment). [startPhrase] replaces "기록을 시작합니다" (and is shown as a
     * notice); [bridge] carries the fixes from the segment before (see [splitSegment]); [autoPause] is the auto-pause
     * chip carried from that segment (null: the setting's default).
     */
    private class Run(
        val tripId: Long,
        val startPhrase: String? = null,
        var bridge: Bridge? = null,
        val autoPause: Boolean? = null,
    ) {
        var startJob: Job? = null
        lateinit var trip: Trip
        var session: RecordingSession? = null
        var segmentId = 0L
        var startedAt = 0L
        lateinit var localDate: LocalDate
        var voiceOn = true

        /** Accepted points not saved yet. */
        val buffer = mutableListOf<TrackPoint>()
        val saveLock = Mutex()
        val track = TrackLine()
        var lastFix: LocationFix? = null
        var gpsWeak = false
        var saveWarning: String? = null

        /** When the trip was last touched (or tried), by the controller's clock. */
        var touchedAt = 0L

        /** This recording as the guidance's km source (set once the session runs), and the day distance last seen. */
        var kmSource: KmAnnouncementSource? = null
        var lastDayM = 0.0

        /** Set once [stopRecording] began: the session no longer takes commands. */
        var stopping = false

        /** [stopRecording] was called (also while a split was closing this segment: then no new segment opens). */
        var stopRequested = false

        /** Fixes at or before this time were already taken (by this segment from the bridge, or by the one before). */
        var skipUpTo = Long.MIN_VALUE

        /** The one stop of this recording; later [stopRecording] calls wait for it. */
        var finishJob: Job? = null
        val jobs = mutableListOf<Job>()

        fun cancelJobs() {
            jobs.toList().forEach { it.cancel() }
            jobs.clear()
        }
    }

    private var run: Run? = null

    init {
        // Screen only: the guidance already spoke the arrival.
        navigation.onArrivedKeepingRecording = { text -> post(text) }
        scope.launch { followNavigation() }
    }

    /**
     * Starts recording [tripId] without waiting: the state is [RideUiState.Starting] when this returns. Returns null and
     * does nothing while a recording is starting or running (of this trip or another).
     */
    fun startRecording(tripId: Long): Job? {
        val state = _ui.value
        if (state is RideUiState.Starting || state is RideUiState.Active) {
            val current = if (state is RideUiState.Starting) state.tripId else (state as RideUiState.Active).tripId
            if (current != tripId) log.warn("Not recording trip $tripId: trip $current is being recorded", null)
            return null
        }
        return launchRun(tripId)
    }

    /** Starts a new [Run] of [tripId] without waiting (the state is [RideUiState.Starting] when this returns). */
    private fun launchRun(
        tripId: Long,
        startPhrase: String? = null,
        bridge: Bridge? = null,
        autoPause: Boolean? = null,
    ): Job {
        run?.cancelJobs()
        val r = Run(tripId, startPhrase, bridge, autoPause)
        // Cancelled with the run if its start fails or it stops.
        bridge?.let {
            r.skipUpTo = it.takenUpTo
            r.jobs += it.job
        }
        run = r
        _ui.value = RideUiState.Starting(tripId)
        val job = scope.launch {
            try {
                prepare(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Starting the recording of trip $tripId failed", e)
                fail(r)
            }
        }
        r.startJob = job
        return job
    }

    override fun pause() {
        val (r, session) = recording() ?: return
        handle(r, session.pause(clock()))
    }

    /** Resumes a manual or an automatic pause. */
    override fun resume() {
        val (r, session) = recording() ?: return
        handle(r, session.resume(clock()))
    }

    /** The auto-pause chip of this recording (the setting is only its default). */
    override fun setAutoPause(enabled: Boolean) {
        val (r, session) = recording() ?: return
        handle(r, session.setAutoPause(enabled, clock()))
    }

    /**
     * Stops the session, saves the last points (a failed write is tried once more; failing again leaves the segment open
     * at its last good save and the summary shows the error), closes the segment, ends the trip as [end] says and shows
     * [RideUiState.Finished]. A stop while starting waits for the start; a stop while stopping waits for that stop
     * (whose [end] wins).
     */
    override suspend fun stopRecording(end: RecordingEnd) {
        val r = run ?: return
        r.startJob?.join()
        if (run !== r || r.session == null) return
        r.stopRequested = true
        val job = r.finishJob ?: scope.launch { finish(r, end) }.also { r.finishJob = it }
        job.join()
    }

    /**
     * While a recording starts or runs: speaks [text] if that recording's voice setting is on and, voice or not, posts
     * it to [notice]. Does nothing without a recording (also while one is stopping).
     */
    override fun announce(text: String) {
        val r = run ?: return
        if (r.stopping || r.finishJob != null) return
        if (r.voiceOn) voice.speak(text)
        post(text)
    }

    /** Clears [notice] only if it still is [id]. */
    override fun noticeShown(id: Long) {
        if (_notice.value?.id == id) _notice.value = null
    }

    /**
     * "새 구간으로 나누기": closes the running segment as [RecordingEnd.SEGMENT_ONLY] (no summary) and at once records a
     * new segment of the same trip. Its start sentence is [KoreanPhrases.newSegmentStarted] instead of "기록을
     * 시작합니다", also posted to [notice] (even with the voice off). Returns [MSG_SAVE_PROBLEM] if the closed segment
     * had a save problem. Does nothing (null) without a running recording or while it is stopping. A [stopRecording]
     * while the segment is being closed opens no new segment and shows the closed segment's summary.
     *
     * Meanwhile the state stays Active (old segment) → Starting → Active (new segment); the location stream stays
     * subscribed (a bridge collects the fixes in between for the new segment) and the day's km count goes on (the new
     * session starts from the day totals, which include the closed segment). The new segment keeps the closed one's
     * auto-pause chip.
     */
    suspend fun splitSegment(): String? {
        val r = run ?: return null
        r.startJob?.join()
        if (run !== r || r.session == null || r.stopping || r.finishJob != null) return null
        val bridge = openBridge(r)
        // The rider's chip of this recording, not the setting's default, goes on in the new segment.
        val autoPause = r.session!!.autoPause
        var next: Job? = null
        var error: String? = null
        val job = scope.launch {
            val finished = finish(r, RecordingEnd.SEGMENT_ONLY, publishSummary = false)
            if (finished == null || run !== r) {
                bridge.job.cancel()
            } else if (r.stopRequested) {
                // A stop came while closing: the segment-only end that began first wins; nothing new opens.
                bridge.job.cancel()
                showSummary(finished)
            } else {
                error = finished.error
                bridge.takenUpTo = r.lastFix?.timeMillis ?: Long.MIN_VALUE
                next = launchRun(r.tripId, phrases.newSegmentStarted, bridge, autoPause)
            }
        }
        r.finishJob = job
        job.join()
        next?.join() ?: return null
        return error
    }

    /**
     * Subscribes to [location] at once (before [r]'s own collector goes) and keeps the fixes [r] did not take. Failures
     * are only logged; the new segment's own collector subscribes again anyway.
     */
    private fun openBridge(r: Run): Bridge {
        val fixes = mutableListOf<LocationFix>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                location.fixes().collect { fix ->
                    if (fix.timeMillis > (r.lastFix?.timeMillis ?: Long.MIN_VALUE)) fixes += fix
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Location stream failed while splitting the segment", e)
            }
        }
        return Bridge(fixes, job)
    }

    /**
     * Another trip is about to start (and the recorded trip to end): stops the running recording as
     * [RecordingEnd.SEGMENT_ONLY] — its segment is closed while its trip is still active; a stop already in flight is
     * waited for instead — and returns the summary's error ([MSG_SAVE_PROBLEM]) for the caller to tell the user. The
     * summary is dismissed when `autoStartRecordingWithNavigation` is on (the new guidance's recording would replace
     * it); otherwise it stays for the main screen. Does nothing (null) unless a recording is starting, running or
     * stopping.
     */
    suspend fun endForAnotherTrip(): String? {
        if (run == null) return null
        // Joins a stop already in flight, so the segment is closed when this returns.
        stopRecording(RecordingEnd.SEGMENT_ONLY)
        val finished = _ui.value as? RideUiState.Finished ?: return null
        // Only a recording of the new trip replaces the summary; without one it waits for the main screen.
        if (settings.first().autoStartRecordingWithNavigation && _ui.value === finished) dismissSummary()
        return finished.error
    }

    /** Closes the summary or the start error. */
    fun dismissSummary() {
        val state = _ui.value
        if (state is RideUiState.Finished || state is RideUiState.Failed) _ui.value = RideUiState.Idle
    }

    /** The most recent segment left open by a recording that never stopped; null unless idle. */
    suspend fun interrupted(): RideSegment? {
        if (_ui.value != RideUiState.Idle) return null
        return try {
            rideStore.openSegments().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the open segments", e)
            null
        }
    }

    /**
     * [종료] on an interrupted recording (ruling P2): closes every open segment at its last save and ends the trip of
     * the most recent one if it is active and not being guided — single-day completes, multi-day ends today. Idle only.
     */
    suspend fun closeInterrupted() {
        if (_ui.value != RideUiState.Idle) return
        scope.launch {
            withContext(NonCancellable) {
                val closed = saving("close the interrupted segments") { closeOpenSegments() } ?: return@withContext
                val tripId = closed.firstOrNull()?.tripId ?: return@withContext
                val trip = saving("read trip $tripId") { tripStore.trip(tripId) } ?: return@withContext
                if (trip.status != TripStatus.ACTIVE || isGuiding(tripId)) return@withContext
                when (trip.type) {
                    TripType.SINGLE_DAY -> saving("complete trip $tripId") { tripManager.complete(tripId) }
                    TripType.MULTI_DAY -> saving("end today of trip $tripId") { tripManager.endToday(tripId) }
                }
            }
        }.join()
    }

    private suspend fun prepare(r: Run) {
        closeOpenSegments()
        val trip = tripStore.trip(r.tripId)
        if (trip == null || trip.status != TripStatus.ACTIVE) {
            log.warn("Trip ${r.tripId} is not active: ${trip?.status}", null)
            return fail(r)
        }
        val current = settings.first()
        val now = clock()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val today = rideStore.dayTotals(r.tripId, date)
        if (run !== r) return
        val session = RecordingSession(now, r.autoPause ?: current.autoPauseEnabled, today, zone)
        val segmentId = rideStore.openSegment(r.tripId, now, date)
        if (run !== r) return
        r.trip = trip
        r.voiceOn = current.voiceEnabled
        r.segmentId = segmentId
        r.startedAt = now
        r.localDate = date
        r.session = session
        handle(r, session.start())
        becomeKmSource(r, session)
        takeBridgedFixes(r, session)

        r.jobs += scope.launch { collectLocation(r, session) }
        pressure?.let { source ->
            r.jobs += scope.launch {
                try {
                    source.pressures().collect { session.onPressure(it.toDouble(), clock()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Pressure stream failed; recording without the barometer", e)
                }
            }
        }
        r.jobs += scope.launch {
            while (true) {
                delay(tickIntervalMs)
                handle(r, session.onTick(clock()))
            }
        }
        r.jobs += scope.launch {
            touchTrip(r)
            while (true) {
                delay(saveIntervalMs)
                if (save(r)) {
                    if (clock() - r.touchedAt >= TOUCH_INTERVAL_MS) touchTrip(r)
                } else if (r.saveWarning == null) {
                    r.saveWarning = MSG_SAVE_PROBLEM
                    publish(r)
                }
            }
        }
        r.jobs += scope.launch {
            try {
                settings.map { it.voiceEnabled }.distinctUntilChanged().collect { r.voiceOn = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Voice setting updates failed", e)
            }
        }
    }

    /** Recording and guidance together (see the class comment). Never ends while [scope] lives. */
    private suspend fun followNavigation() {
        var previous: NavUiState? = null
        navigation.ui.collect { nav ->
            val before = previous
            previous = nav
            try {
                when (nav) {
                    is NavUiState.Active ->
                        if (!(before is NavUiState.Active && before.tripId == nav.tripId)) guidanceActive(nav.tripId)
                    is NavUiState.Finished ->
                        if (!(before is NavUiState.Finished && before.tripId == nav.tripId)) guidanceFinished(nav.tripId)
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Following the guidance state $nav failed", e)
            }
        }
    }

    private suspend fun guidanceActive(tripId: Long) {
        if (!settings.first().autoStartRecordingWithNavigation) return
        // The setting may answer late: the guidance must still run for that trip.
        val nav = navigation.ui.value
        if (nav !is NavUiState.Active || nav.tripId != tripId) return
        // Idle, a summary or a start error: start (startRecording itself refuses while starting or recording).
        val state = _ui.value
        if (state !is RideUiState.Starting && state !is RideUiState.Active) startRecording(tripId)
    }

    private suspend fun guidanceFinished(tripId: Long) {
        if (run?.tripId == tripId) stopRecording(RecordingEnd.SEGMENT_ONLY)
    }

    /** From now on the guidance announces kilometres of [session]'s day with the combined sentence. */
    private fun becomeKmSource(r: Run, session: RecordingSession) {
        if (run !== r) return
        val source = object : KmAnnouncementSource {
            override fun distanceM(): Double = session.day.distanceM

            override fun phrase(km: Int, remainingM: Double, etaMillis: Long): String {
                val day = session.day
                return phrases.rideKmReportWithNav(km, day.avgSpeedMps, day.movingTimeMs, remainingM, etaMillis)
            }
        }
        r.kmSource = source
        r.lastDayM = session.day.distanceM
        navigation.kmSource = source
    }

    /** A new day restarts the day distance at 0: the guidance's km count is re-based on it (only then). */
    private fun rebaseKmOnNewDay(r: Run, session: RecordingSession) {
        val source = r.kmSource ?: return
        val dayM = session.day.distanceM
        if (dayM < r.lastDayM && navigation.kmSource === source) navigation.kmSource = source
        r.lastDayM = dayM
    }

    /** The guidance goes back to its own km announcements, unless someone else's source replaced [r]'s. */
    private fun releaseKmSource(r: Run) {
        val source = r.kmSource ?: return
        r.kmSource = null
        if (navigation.kmSource === source) navigation.kmSource = null
    }

    /** Feeds the fixes the bridge of [r] collected since the segment before ([Run.skipUpTo] and older are dropped). */
    private fun takeBridgedFixes(r: Run, session: RecordingSession) {
        val bridge = r.bridge ?: return
        val fixes = bridge.fixes.sortedBy { it.timeMillis }
        bridge.fixes.clear()
        for (fix in fixes) {
            if (fix.timeMillis <= r.skipUpTo) continue
            r.skipUpTo = fix.timeMillis
            r.lastFix = fix
            handle(r, session.onFix(fix, clock()))
        }
    }

    /**
     * Feeds every fix to [session]; a stream that fails or ends is subscribed again after [LOCATION_RETRY_MS]. After a
     * split, the first fix ends the bridge (its last fixes first), and fixes already taken are dropped.
     */
    private suspend fun collectLocation(r: Run, session: RecordingSession) {
        while (true) {
            try {
                location.fixes().collect { fix ->
                    r.bridge?.let { bridge ->
                        takeBridgedFixes(r, session)
                        bridge.job.cancel()
                        r.bridge = null
                    }
                    if (fix.timeMillis <= r.skipUpTo) return@collect
                    r.lastFix = fix
                    handle(r, session.onFix(fix, clock()))
                }
                log.warn("Location stream ended; subscribing again in $LOCATION_RETRY_MS ms", null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Location stream failed; subscribing again in $LOCATION_RETRY_MS ms", e)
            }
            delay(LOCATION_RETRY_MS)
        }
    }

    /** Applies one session update: speech, the point to save, the map track and the state. Never suspends. */
    private fun handle(r: Run, update: RecordingUpdate) {
        if (run !== r || r.stopping) return
        if (r.voiceOn) update.events.forEach { event -> speech(r, event)?.let(voice::speak) }
        // On screen whatever the voice setting.
        r.startPhrase?.let { phrase -> if (RecordingEvent.Started in update.events) post(phrase) }
        update.point?.let { point ->
            r.buffer += point
            val last = r.track.last
            if (last == null || GeoMath.distanceM(last, point.point) >= TRACK_SPACING_M) r.track.append(point.point)
        }
        r.gpsWeak = update.gpsWeak
        r.session?.let { rebaseKmOnNewDay(r, it) }
        publish(r)
    }

    private fun speech(r: Run, event: RecordingEvent): String? = when (event) {
        RecordingEvent.Started -> r.startPhrase ?: phrases.recordingStarted
        is RecordingEvent.Paused -> phrases.recordingPaused
        RecordingEvent.Resumed -> phrases.recordingResumed
        // While guiding, the navigation announces the combined sentence instead (Task 11).
        is RecordingEvent.KmReached -> if (navigation.ui.value is NavUiState.Active) {
            null
        } else {
            val day = DayTotals(event.dayDistanceM, event.dayMovingTimeMs)
            phrases.rideKmReport(event.km, day.avgSpeedMps, event.dayMovingTimeMs)
        }
    }

    private fun publish(r: Run) {
        if (run !== r) return
        val session = r.session ?: return
        _ui.value = RideUiState.Active(
            tripId = r.tripId,
            tripType = r.trip.type,
            segmentId = r.segmentId,
            state = session.state,
            stats = session.stats,
            autoPause = session.autoPause,
            track = r.track.snapshot(),
            fix = r.lastFix,
            gpsWeak = r.gpsWeak,
            saveWarning = r.saveWarning,
        )
    }

    /**
     * Appends the buffered points with the current totals; only the points it saved leave the buffer. One save at a
     * time, and a started write is not cancelled (it either lands or fails), so no point is saved twice.
     */
    private suspend fun save(r: Run): Boolean = r.saveLock.withLock {
        withContext(NonCancellable) {
            val session = r.session ?: return@withContext true
            val batch = r.buffer.toList()
            try {
                rideStore.appendPoints(r.segmentId, batch, session.stats.toTotals())
                r.buffer.subList(0, batch.size).clear()
                true
            } catch (e: Exception) {
                log.warn("Saving ${batch.size} points of segment ${r.segmentId} failed; keeping them for the next save", e)
                false
            }
        }
    }

    /**
     * Best effort: marks the trip as active now, so the stale-trip prompt never takes a trip being recorded for an
     * abandoned one. A failure is logged and retried with a save [TOUCH_INTERVAL_MS] later.
     */
    private suspend fun touchTrip(r: Run) {
        r.touchedAt = clock()
        saving("touch trip ${r.tripId}") { tripManager.touch(r.tripId) }
    }

    /**
     * Ends [r] as [end] and returns its summary. With [publishSummary] the summary is shown (and [r] is no longer
     * current); without it ([splitSegment]) [r] stays current — stopping — and the state unchanged, for the caller to
     * decide. Null (nothing done) unless [r] is current, running and not stopping.
     */
    private suspend fun finish(
        r: Run,
        end: RecordingEnd,
        publishSummary: Boolean = true,
    ): RideUiState.Finished? = withContext(NonCancellable) {
        val session = r.session
        if (run !== r || session == null || r.stopping) return@withContext null
        val now = clock()
        session.stop(now)
        r.stopping = true
        releaseKmSource(r)
        r.cancelJobs()
        // The last points: tried once more if the first write fails. If both fail, the segment stays open at its last
        // good save (the interrupted-recording recovery or the next start closes it there) rather than being closed
        // with totals its saved points do not have.
        val saved = save(r) || save(r)
        val totals = session.stats.toTotals()
        var ok = saved &&
            saving("close segment ${r.segmentId}") { rideStore.closeSegment(r.segmentId, now, totals) } != null
        val tripId = r.tripId
        ok = when (end) {
            RecordingEnd.SEGMENT_ONLY -> true
            RecordingEnd.STOP_TODAY -> r.trip.type != TripType.MULTI_DAY ||
                saving("end today of trip $tripId") { tripManager.endToday(tripId) } != null
            // complete() saves the trip first, then deletes the offline maps.
            RecordingEnd.COMPLETE_TRIP -> saving("complete trip $tripId") { tripManager.complete(tripId) } != null ||
                saving("read trip $tripId") { tripStore.trip(tripId) }?.status == TripStatus.COMPLETED
        } && ok
        val finished = RideUiState.Finished(
            segment = RideSegment(r.segmentId, tripId, r.startedAt, now, r.localDate, totals),
            track = r.track.snapshot(),
            error = if (ok) null else MSG_SAVE_PROBLEM,
        )
        if (publishSummary) showSummary(finished)
        finished
    }

    /** The current (stopping) run is over: shows its summary; its notice must not reach the next recording's screen. */
    private fun showSummary(finished: RideUiState.Finished) {
        run = null
        _notice.value = null
        _ui.value = finished
    }

    /** Posts [text] to [notice] (a new id each time), apart from any voice decision. */
    private fun post(text: String) {
        _notice.value = ScreenNotice(++noticeIds, text)
    }

    /** Closes every open segment at its last save (startedAt + saved elapsed time, saved totals); returns them. */
    private suspend fun closeOpenSegments(): List<RideSegment> {
        val open = rideStore.openSegments()
        for (segment in open) {
            rideStore.closeSegment(segment.id, segment.startedAt + segment.totals.elapsedMs, segment.totals)
        }
        return open
    }

    private fun isGuiding(tripId: Long): Boolean = navigation.ui.value.guidingTripId == tripId

    /** The running recording and its session, unless it is starting or stopping. */
    private fun recording(): Pair<Run, RecordingSession>? {
        val r = run ?: return null
        if (r.stopping) return null
        val session = r.session ?: return null
        return r to session
    }

    /** The start of [r] failed: nothing records; a notice posted while it started must not reach a later recording. */
    private fun fail(r: Run) {
        if (run !== r) return
        releaseKmSource(r)
        r.cancelJobs()
        run = null
        _notice.value = null
        _ui.value = RideUiState.Failed(r.tripId, MSG_START_FAILED)
    }

    /** Runs a store or trip write; null (logged) if it failed. */
    private suspend inline fun <T> saving(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not $what", e)
        null
    }

    companion object {
        const val MSG_START_FAILED = "기록을 시작하지 못했습니다"
        const val MSG_SAVE_PROBLEM = "기록 저장에 문제가 있습니다"

        /** A failed or ended location stream is subscribed again after this long. */
        const val LOCATION_RETRY_MS = 5_000L

        /** The trip is touched when the recording starts and with a successful save at most this often. */
        const val TOUCH_INTERVAL_MS = 60_000L

        /** The map track keeps an accepted location only this far from the previous kept one. */
        private const val TRACK_SPACING_M = 10.0
        private const val TAG = "RideController"
    }
}
