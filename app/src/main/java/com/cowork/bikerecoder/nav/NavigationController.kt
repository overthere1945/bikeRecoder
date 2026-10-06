package com.cowork.bikerecoder.nav

import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.navigation.NavEvent
import com.cowork.bikerecoder.core.navigation.NavState
import com.cowork.bikerecoder.core.navigation.NavUpdate
import com.cowork.bikerecoder.core.navigation.NavigationSession
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.core.voice.KoreanPhrases
import com.cowork.bikerecoder.data.DayDistances
import com.cowork.bikerecoder.data.MemoryDayDistances
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.tts.VoiceOutput
import com.cowork.bikerecoder.ui.common.routeFailureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/** What the navigation screen and the foreground-service notification show. */
sealed interface NavUiState {
    data object Idle : NavUiState

    /** Waiting for the first fix or computing the route to the unvisited stops. */
    data class Starting(val tripId: Long) : NavUiState

    /** Guidance is running. [stops] are the stops not reached yet; [fix] is the latest location. */
    data class Active(
        val tripId: Long,
        val type: TripType,
        val state: NavState,
        val stops: List<Stop>,
        val fix: LocationFix?,
        val muted: Boolean,
    ) : NavUiState

    /**
     * The start failed (no location, no route, ...); [message] is shown, with [다시 시도] if [canRetry].
     * No session runs.
     */
    data class Failed(val tripId: Long, val message: String, val canRetry: Boolean = true) : NavUiState

    /**
     * Guidance ended for [reason]. [error] is set when the trip's new state could not be saved (the screen
     * shows it instead of reporting success).
     */
    data class Finished(
        val tripId: Long,
        val type: TripType,
        val reason: FinishReason,
        val error: String? = null,
    ) : NavUiState
}

enum class FinishReason { ARRIVED, STOPPED_TODAY, COMPLETED }

/**
 * Runs one [NavigationSession] at a time, outside any UI lifecycle: the app-wide singleton lives in
 * [AppContainer.navigation] and the location foreground service keeps the process alive while it runs.
 *
 * Starting a trip takes the first fix within [START_ACCURACY_M] of a fresh [locationSource] (or, after
 * [firstFixTimeoutMs], the most accurate one seen) as the start point, routes from there to
 * the trip's unvisited stops with the trip's profile, then feeds every later fix to the session, drives
 * [NavigationSession.onTick] every [tickIntervalMs] by [clock] (independent of fix times) and touches the
 * trip every [touchIntervalMs]. Today's distance of the trip ([dayDistances], by [zone]'s calendar date at the
 * start) seeds the session, so a same-day resume continues the kilometre announcements; it is saved at
 * every new kilometre and when the session ends. Session updates are spoken (unless [voiceEnabled] is off), reached stops are
 * marked visited, and reaching the destination completes the trip. Every way out of a session (arrival,
 * [stopToday], [completeTrip], [close], a new start) stops the location source and the ticks.
 *
 * Threading: every public method must be called on [scope]'s single thread (Main in the app); the session
 * runs on the same [scope]. Trip writes run in [scope], so they finish even if the caller goes away.
 */
class NavigationController(
    private val router: Router,
    private val tripManager: TripManager,
    private val tripStore: TripStore,
    private val offline: OfflineMapController,
    private val voice: VoiceOutput,
    private val locationSource: () -> LocationSource,
    private val voiceEnabled: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val phrases: KoreanPhrases = KoreanPhrases(),
    private val tickIntervalMs: Long = 1_000,
    private val touchIntervalMs: Long = 60_000,
    private val firstFixTimeoutMs: Long = FIRST_FIX_TIMEOUT_MS,
    private val log: WarnLog = androidWarnLog(TAG),
    private val dayDistances: DayDistances = MemoryDayDistances(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    constructor(container: AppContainer, scope: CoroutineScope) : this(
        router = container.router,
        tripManager = container.tripManager,
        tripStore = container.tripStore,
        offline = container.offline,
        voice = container.voice,
        locationSource = { container.locationSourceFactory() },
        voiceEnabled = container.settings.settings.map { it.voiceEnabled },
        scope = scope,
        dayDistances = container.dayDistances,
    )

    private val _ui = MutableStateFlow<NavUiState>(NavUiState.Idle)
    val ui: StateFlow<NavUiState> = _ui.asStateFlow()

    private var muted = false

    /** Everything belonging to one start of one trip. */
    private class Run(val tripId: Long) {
        var trip: Trip? = null
        var session: NavigationSession? = null
        var stops: List<Stop> = emptyList()
        var lastFix: LocationFix? = null
        var voiceOn = true

        /** The local date today's distance is counted for, and the last whole kilometre saved. */
        var day: LocalDate? = null
        var savedKm = 0
        val jobs = mutableListOf<Job>()

        fun cancelJobs() {
            jobs.toList().forEach { it.cancel() }
            jobs.clear()
        }
    }

    private var run: Run? = null

    /**
     * Starts guidance for [tripId] without waiting: the state is [NavUiState.Starting] when this returns.
     * Does nothing (returns the running job, or null) if that trip is already starting or running.
     */
    fun begin(tripId: Long): Job? {
        val current = run
        val state = _ui.value
        if (current != null && current.tripId == tripId &&
            (state is NavUiState.Starting || state is NavUiState.Active)
        ) {
            return null
        }
        endRun()
        val r = Run(tripId)
        run = r
        _ui.value = NavUiState.Starting(tripId)
        val job = scope.launch {
            try {
                prepare(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Starting guidance of trip $tripId failed", e)
                fail(r, MSG_START_FAILED)
            }
        }
        r.jobs += job
        return job
    }

    /** Routes from the current location to the unvisited stops and starts the session (or fails). */
    suspend fun start(tripId: Long) {
        begin(tripId)?.join()
    }

    /** MULTI_DAY only: guidance stops for today, the trip stays active. */
    fun stopToday() {
        val r = run ?: return
        if (r.trip?.type != TripType.MULTI_DAY) return
        scope.launch { finish(r, FinishReason.STOPPED_TODAY) }
    }

    /** Completes the trip (deleting its offline maps) and stops guidance. */
    suspend fun completeTrip() {
        val r = run ?: return
        if (r.trip == null) return
        scope.launch { finish(r, FinishReason.COMPLETED) }.join()
    }

    /** From a finished single-day trip: reopen it as an active multi-day trip, then go idle. */
    suspend fun convertToMultiDay() {
        val finished = _ui.value as? NavUiState.Finished ?: return
        if (finished.type != TripType.SINGLE_DAY || finished.reason == FinishReason.STOPPED_TODAY) return
        scope.launch {
            val saved = withContext(NonCancellable) {
                saving("convert trip ${finished.tripId} to multi-day") { tripManager.convertToMultiDay(finished.tripId) }
            }
            if (_ui.value == finished) {
                _ui.value = if (saved) NavUiState.Idle else finished.copy(error = MSG_SAVE_FAILED)
            }
        }.join()
    }

    fun setMuted(m: Boolean) {
        muted = m
        voice.muted = m
        val state = _ui.value
        if (state is NavUiState.Active) _ui.value = state.copy(muted = m)
    }

    /** Stops whatever runs without changing the trip (cancel a start, close an error or a finished trip). */
    fun close() {
        endRun()
        _ui.value = NavUiState.Idle
    }

    private fun endRun() {
        val r = run ?: return
        r.cancelJobs()
        run = null
        scope.launch { saveDayDistance(r) }
    }

    private suspend fun prepare(r: Run) {
        val trip = tripStore.trip(r.tripId) ?: return fail(r, MSG_NO_TRIP, canRetry = false)
        // A stale "안내가 중단되었습니다" notification or [이어서 안내] must not revive a finished trip.
        if (trip.status != TripStatus.ACTIVE) return fail(r, MSG_TRIP_ENDED, canRetry = false)
        r.trip = trip
        val stops = tripManager.remainingStops(r.tripId).map { Stop(it.id, it.name, it.point, it.isDestination) }
        if (stops.isEmpty()) return fail(r, MSG_NO_STOPS, canRetry = false)

        // The start point: the first fix within START_ACCURACY_M, else (at the timeout) the most accurate one seen.
        val firstFix = CompletableDeferred<LocationFix>()
        var bestFix: LocationFix? = null
        r.jobs += scope.launch {
            try {
                locationSource().fixes().collect { fix ->
                    r.lastFix = fix
                    val session = r.session
                    if (session != null) {
                        session.onFix(fix)
                    } else {
                        if (bestFix.let { it == null || fix.accuracyM < it.accuracyM }) bestFix = fix
                        if (fix.accuracyM <= START_ACCURACY_M) firstFix.complete(fix)
                    }
                }
                bestFix?.let { firstFix.complete(it) }
                if (!firstFix.isCompleted) log.warn("Location source ended without a fix", null)
                firstFix.completeExceptionally(IllegalStateException("location source ended without a fix"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Before the start this fails the start; later the session's GPS watchdog reports it.
                log.warn("Location source failed", e)
                firstFix.completeExceptionally(e)
            }
        }
        val start = try {
            withTimeoutOrNull(firstFixTimeoutMs) { firstFix.await() } ?: bestFix
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bestFix // The failure is already logged by the collector.
        }
        if (start == null) {
            if (!firstFix.isCompleted) log.warn("No location fix within $firstFixTimeoutMs ms", null)
            return fail(r, MSG_NO_LOCATION)
        }

        val request = RouteRequest(start.point, start.bearingDeg, stops.map { it.point }, trip.profile)
        val result = try {
            router.route(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Router threw", e)
            RouteResult.Failure(RouteFailure.OTHER, e.message.orEmpty())
        }
        if (run !== r) return
        val route = when (result) {
            is RouteResult.Failure -> {
                log.warn("Route to the stops of trip ${trip.id} failed: ${result.reason} ${result.detail}", null)
                return fail(r, routeFailureText(result.reason))
            }
            is RouteResult.Success -> result.route
        }
        // The setting must be known before the session can speak (DataStore answers asynchronously).
        r.voiceOn = try {
            voiceEnabled.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the voice setting; speaking", e)
            true
        }
        if (run !== r) return

        val day = LocalDate.ofInstant(Instant.ofEpochMilli(clock()), zone)
        val ridden = try {
            dayDistances.distanceOn(trip.id, day)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read today's distance of trip ${trip.id}", e)
            0.0
        }
        if (run !== r) return
        r.day = day
        r.savedKm = floor(ridden / 1_000.0).toInt()

        val session = NavigationSession(route, stops, trip.profile, router, phrases, scope, initialDistanceM = ridden)
        r.stops = stops
        r.session = session
        // Subscribed before anything can emit (the shared flow has no replay).
        r.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { session.updates.collect { handle(r, it) } }
        r.jobs += scope.launch {
            try {
                voiceEnabled.collect { r.voiceOn = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Voice setting updates failed", e)
            }
        }
        r.jobs += scope.launch {
            while (true) {
                delay(tickIntervalMs)
                session.onTick(clock())
            }
        }
        r.jobs += scope.launch {
            while (true) {
                delay(touchIntervalMs)
                ignoringErrors("touch trip ${trip.id}") { tripManager.touch(trip.id) }
            }
        }
        r.jobs += scope.launch { ignoringErrors("offline maps of trip ${trip.id}") { offline.downloadForTrip(trip.id, route, 0.0) } }
        // The latest fix (the start point, or newer if routing took a while) is the session's first.
        r.lastFix?.let(session::onFix)
    }

    private suspend fun handle(r: Run, update: NavUpdate) {
        if (run !== r) return
        if (r.voiceOn) update.utterances.forEach { voice.speak(it.text) }
        val km = floor((r.session?.sessionDistanceM ?: 0.0) / 1_000.0).toInt()
        if (km > r.savedKm) {
            r.savedKm = km
            scope.launch { saveDayDistance(r) }
        }
        var arrived = false
        for (event in update.events) {
            if (event !is NavEvent.StopReached) continue
            r.stops = r.stops.filterNot { it.id == event.stop.id }
            ignoringErrors("mark stop ${event.stop.id} visited") { tripManager.markVisited(event.stop.id) }
            if (event.stop.isDestination) arrived = true
        }
        if (arrived) {
            finish(r, FinishReason.ARRIVED)
            return
        }
        if (run !== r) return
        val trip = r.trip ?: return
        _ui.value = NavUiState.Active(r.tripId, trip.type, update.state, r.stops, r.lastFix, muted)
    }

    /** Ends [r]'s session and records the outcome. May be called from [r]'s own update collector. */
    private suspend fun finish(r: Run, reason: FinishReason) = withContext(NonCancellable) {
        if (run !== r) return@withContext
        val trip = r.trip ?: return@withContext
        run = null
        r.cancelJobs()
        saveDayDistance(r)
        val saved = when (reason) {
            FinishReason.ARRIVED, FinishReason.COMPLETED -> saving("complete trip ${trip.id}") {
                tripManager.complete(trip.id)
            } || isCompleted(trip.id) // complete() saves first, then deletes the offline maps
            FinishReason.STOPPED_TODAY -> saving("end today of trip ${trip.id}") { tripManager.endToday(trip.id) }
        }
        // A new start during the write wins.
        if (run == null) {
            _ui.value = NavUiState.Finished(trip.id, trip.type, reason, error = if (saved) null else MSG_SAVE_FAILED)
        }
    }

    /** Best effort: today's distance of [r]'s session so far (nothing before the session started). */
    private suspend fun saveDayDistance(r: Run) {
        val session = r.session ?: return
        val day = r.day ?: return
        ignoringErrors("save today's distance of trip ${r.tripId}") {
            dayDistances.save(r.tripId, day, session.sessionDistanceM)
        }
    }

    /** The start of [r] failed: stop its location source; [r] stays current so the trip can still be ended. */
    private fun fail(r: Run, message: String, canRetry: Boolean = true) {
        if (run !== r) return
        r.cancelJobs()
        _ui.value = NavUiState.Failed(r.tripId, message, canRetry)
    }

    /** Best effort while guiding: a failed write is logged and must not stop guidance. */
    private suspend inline fun ignoringErrors(what: String, block: () -> Unit) {
        saving(what, block)
    }

    /** Runs a trip write; false (logged) if it failed. */
    private suspend inline fun saving(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not $what", e)
        false
    }

    private suspend fun isCompleted(tripId: Long): Boolean = try {
        tripStore.trip(tripId)?.status == TripStatus.COMPLETED
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read trip $tripId", e)
        false
    }

    companion object {
        const val MSG_NO_LOCATION = "현재 위치를 확인할 수 없습니다. 위치 권한과 GPS를 확인하세요"
        const val MSG_NO_TRIP = "여행 정보를 찾을 수 없습니다"
        const val MSG_NO_STOPS = "남은 경유지가 없습니다"
        const val MSG_START_FAILED = "안내를 시작하지 못했습니다"
        const val MSG_TRIP_ENDED = "이미 끝난 여행입니다"
        const val MSG_SAVE_FAILED = "여행 상태를 저장하지 못했습니다"

        /**
         * The start waits this long for a fix within [START_ACCURACY_M]; then it takes the most accurate fix
         * seen, and without any fix it fails (and can be retried).
         */
        const val FIRST_FIX_TIMEOUT_MS = 30_000L

        /** A start point this accurate is taken at once (the session's accuracy limit, spec §6). */
        const val START_ACCURACY_M = 30f

        private const val TAG = "NavigationController"
    }
}
