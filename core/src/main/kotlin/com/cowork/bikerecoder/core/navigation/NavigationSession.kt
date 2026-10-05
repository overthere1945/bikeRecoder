package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.voice.KoreanPhrases
import com.cowork.bikerecoder.core.voice.Priority
import com.cowork.bikerecoder.core.voice.Utterance
import com.cowork.bikerecoder.core.voice.VoiceScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** 안내 화면에 그릴 현재 상태. */
data class NavState(
    val route: Route,
    val progress: Progress,
    val speedMps: Double,
    val etaMillis: Long,
    val gpsWeak: Boolean,
    val rerouting: Boolean,
)

sealed interface NavEvent {
    /** 경유지 또는 목적지 도달. `stop.isDestination`이면 목적지이며 세션이 끝난다. */
    data class StopReached(val stop: Stop) : NavEvent
    data class Rerouted(val route: Route) : NavEvent
    data object RerouteFailed : NavEvent
}

/** [NavigationSession.updates]의 한 번의 방출: 상태, 이번에 말할 문구, 그 사이 일어난 이벤트. */
data class NavUpdate(val state: NavState, val utterances: List<Utterance>, val events: List<NavEvent>)

/**
 * 진행 추적·이탈 판정·도착 판정·ETA·주행 거리·음성 안내를 묶어 턴바이턴 안내를 수행한다.
 *
 * - [onFix]마다 상태를 갱신하고 [updates]로 정확히 한 번 방출한다. 정확도가 30m보다 나쁜 위치는
 *   진행/도착/이탈 판정에서 제외한다(진행 상태는 직전 값 유지).
 * - 이탈이 확정되면 "다시 탐색" 안내 후 [scope]에서 [router]를 호출한다. 재탐색은 동시에 하나만 진행한다.
 *   그 사이 들어온 위치는 기존 경로 기준으로 처리되고 `rerouting = true`로 방출된다.
 *   결과(성공 → [NavEvent.Rerouted], 실패 → [NavEvent.RerouteFailed] + 안내)는 다음 방출에 실린다.
 *   실패하면 [rerouteRetryMs] 뒤 첫 위치가 여전히 이탈(40m 이상)일 때 이탈 판정을 기다리지 않고 다시 시도하며,
 *   그때까지는 새 재탐색을 시작하지 않는다.
 * - 목적지에 도착하면 세션이 끝난다: 진행 중인 재탐색을 취소하고, 이후 호출은 아무것도 방출하지 않는다.
 * - [onTick]은 마지막 위치(또는 첫 tick) 이후 [gpsTimeoutMs]가 지나면 GPS 약함을 한 번 알린다.
 *   새 위치가 오면 안내 없이 해제한다.
 *
 * 스레드: 모든 공개 메서드는 [scope]의 디스패처와 같은 단일 스레드(예: Main)에서 호출해야 한다.
 * 재탐색 결과도 그 [scope]의 코루틴 안에서 적용되므로 잠금을 쓰지 않는다.
 * 위치·감지기 시각은 `fix.timeMillis`, GPS 감시는 `onTick(nowMillis)`를 쓴다.
 */
class NavigationSession(
    initialRoute: Route,
    stops: List<Stop>,
    private val profile: RouteProfile,
    private val router: Router,
    private val phrases: KoreanPhrases,
    private val scope: CoroutineScope,
    private val gpsTimeoutMs: Long = 10_000,
    private val rerouteRetryMs: Long = 30_000,
) {

    private companion object {
        const val MAX_ACCURACY_M = 30f
        const val OFF_ROUTE_THRESHOLD_M = 40.0
    }

    private val _updates = MutableSharedFlow<NavUpdate>(extraBufferCapacity = 64)
    val updates: SharedFlow<NavUpdate> = _updates.asSharedFlow()

    private val odometer = Odometer()
    val sessionDistanceM: Double get() = odometer.distanceM

    private val eta = EtaEstimator()
    private val offRoute = OffRouteDetector()
    private val scheduler = VoiceScheduler(initialRoute, phrases)

    private var route: Route = initialRoute
    private var tracker = RouteProgressTracker(initialRoute)

    /** 아직 도달하지 않은 경유지들과 목적지(순서대로). */
    private val remainingStops: MutableList<Stop> = stops.toMutableList()
    private var arrival = ArrivalDetector(stops, initialRoute.stopPointIndices.map { initialRoute.cumulativeM[it] })

    private var progress: Progress = startProgress(initialRoute)
    private var speedMps = 0.0
    private var gpsWeak = false
    private var finished = false

    private var lastActivityMillis: Long? = null
    private var latestFixMillis = 0L
    private var lastAcceptedFix: LocationFix? = null

    /** 진행 중인 재탐색. null이 아니면 rerouting 상태다. */
    private var rerouteJob: Job? = null
    private var retryAtMillis: Long? = null

    /** 다음 방출에 실을 이벤트. */
    private val pendingEvents = mutableListOf<NavEvent>()

    fun onFix(fix: LocationFix) {
        if (finished) return
        lastActivityMillis = fix.timeMillis
        latestFixMillis = fix.timeMillis
        gpsWeak = false

        odometer.onFix(fix)
        eta.onFix(fix)
        speedMps = speedFor(fix)

        if (fix.accuracyM <= MAX_ACCURACY_M) {
            lastAcceptedFix = fix
            progress = tracker.update(fix.point)
            checkArrival(fix)
            if (!finished) checkOffRoute(fix)
        }

        val etaMillis = eta.etaMillis(fix.timeMillis, progress.remainingM)
        val utterances = scheduler.onUpdate(progress, speedMps, odometer.distanceM, etaMillis)
        emit(utterances, etaMillis)
    }

    fun onTick(nowMillis: Long) {
        if (finished) return
        val last = lastActivityMillis
        if (last == null) {
            lastActivityMillis = nowMillis
            return
        }
        if (gpsWeak || nowMillis - last < gpsTimeoutMs) return

        gpsWeak = true
        emit(listOf(Utterance(phrases.gpsWeak, Priority.EVENT)), eta.etaMillis(nowMillis, progress.remainingM))
    }

    /** fix 자체 속도가 있으면 그것, 없으면 직전 채택 위치와의 거리/시간(없으면 0). */
    private fun speedFor(fix: LocationFix): Double {
        fix.speedMps?.let { return it.toDouble() }
        val previous = lastAcceptedFix ?: return 0.0
        val dtMs = fix.timeMillis - previous.timeMillis
        if (dtMs <= 0) return 0.0
        return GeoMath.distanceM(previous.point, fix.point) / (dtMs / 1_000.0)
    }

    private fun checkArrival(fix: LocationFix) {
        val reached = arrival.onFix(fix.point, progress.remainingM) ?: return
        remainingStops.removeAt(0)
        pendingEvents += NavEvent.StopReached(reached)
        if (!reached.isDestination) {
            scheduler.enqueueEvent(phrases.arrivedWaypoint)
            return
        }
        scheduler.enqueueEvent(phrases.arrivedDestination)
        finished = true
        retryAtMillis = null
        rerouteJob?.cancel()
        rerouteJob = null
    }

    private fun checkOffRoute(fix: LocationFix) {
        val retryAt = retryAtMillis
        if (retryAt != null) {
            // 실패 후 재시도 대기 중에는 이탈 판정기를 쓰지 않는다.
            if (fix.timeMillis < retryAt) return
            retryAtMillis = null
            if (progress.lateralOffsetM >= OFF_ROUTE_THRESHOLD_M) {
                if (rerouteJob == null) startReroute(fix)
                return
            }
            // 이미 경로로 돌아왔다: 재시도를 취소하고 평소 판정으로 돌아간다.
        }

        if (offRoute.onFix(fix, progress.lateralOffsetM) && rerouteJob == null) {
            scheduler.enqueueEvent(phrases.offRoute)
            startReroute(fix)
        }
    }

    private fun startReroute(fix: LocationFix) {
        retryAtMillis = null
        val request = RouteRequest(
            start = fix.point,
            startBearingDeg = fix.bearingDeg,
            stops = remainingStops.map { it.point },
            profile = profile,
        )
        // LAZY: 즉시 실행 디스패처에서도 rerouteJob이 먼저 대입된 뒤 본문이 돈다.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                router.route(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RouteResult.Failure(RouteFailure.OTHER, e.message ?: e.toString())
            }
            applyRerouteResult(result)
        }
        rerouteJob = job
        job.start()
    }

    private fun applyRerouteResult(result: RouteResult) {
        rerouteJob = null
        if (finished) return
        val t = latestFixMillis

        when (result) {
            is RouteResult.Success -> {
                val newRoute = result.route
                route = newRoute
                tracker = RouteProgressTracker(newRoute)
                // 요청 이후 도달한 경유지가 있으면 그만큼 앞쪽 stop 인덱스를 버린다.
                val stopIndices = newRoute.stopPointIndices.takeLast(remainingStops.size)
                arrival = ArrivalDetector(remainingStops.toList(), stopIndices.map { newRoute.cumulativeM[it] })
                scheduler.replaceRoute(newRoute)
                progress = lastAcceptedFix?.let { tracker.update(it.point) } ?: startProgress(newRoute)
                pendingEvents += NavEvent.Rerouted(newRoute)
            }

            is RouteResult.Failure -> {
                scheduler.enqueueEvent(phrases.rerouteFailed)
                pendingEvents += NavEvent.RerouteFailed
                retryAtMillis = t + rerouteRetryMs
            }
        }
        offRoute.onRerouteFinished(t)
    }

    private fun emit(utterances: List<Utterance>, etaMillis: Long) {
        val state = NavState(route, progress, speedMps, etaMillis, gpsWeak, rerouting = rerouteJob != null)
        val update = NavUpdate(state, utterances, pendingEvents.toList())
        pendingEvents.clear()
        _updates.tryEmit(update)
    }

    /** 첫 위치 전의 진행 상태: 거리 0, 남은 거리 = 전체, 다음 안내 = 첫 안내. */
    private fun startProgress(route: Route): Progress {
        val first = route.instructions.firstOrNull()
        return Progress(
            distanceAlongM = 0.0,
            lateralOffsetM = 0.0,
            remainingM = route.cumulativeM.last(),
            nextInstruction = first,
            distanceToNextInstructionM = first?.distanceFromStartM,
        )
    }
}
