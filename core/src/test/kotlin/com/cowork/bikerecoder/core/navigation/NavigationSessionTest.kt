package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.gpx.GpxParser
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Instruction
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.model.TurnType
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.voice.KoreanPhrases
import com.cowork.bikerecoder.core.voice.Priority
import com.cowork.bikerecoder.core.voice.Utterance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 미리 정한 응답을 돌려주고 호출을 기록하는 [Router] 테스트 더블. */
private class FakeRouter(private val respond: suspend (RouteRequest) -> RouteResult) : Router {
    val requests = mutableListOf<RouteRequest>()
    val calls: Int get() = requests.size

    override suspend fun route(request: RouteRequest): RouteResult {
        requests += request
        return respond(request)
    }
}

private val T0: Long = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()

/** pointAt 좌표 꼭짓점들로 경로를 만든다. 거리는 GeoMath 기준(R9), 회전은 꼭짓점 인덱스에 둔다. */
private fun routeOf(vertices: List<GeoPoint>, turns: Map<Int, TurnType> = emptyMap()): Route {
    val cumulative = GeoMath.cumulativeDistances(vertices)
    return Route(
        points = vertices,
        cumulativeM = cumulative,
        instructions = turns.toSortedMap().map { (idx, type) -> Instruction(idx, type, 0, cumulative[idx]) },
        stopPointIndices = listOf(vertices.size - 1),
        summary = RouteSummary(cumulative.last(), 0, 0.0, RouteProfile.BALANCED),
    )
}

private fun fixAt(eastM: Double, northM: Double, sec: Int, accuracyM: Float = 5f, speedMps: Float? = 5f): LocationFix =
    LocationFix(pointAt(eastM, northM), accuracyM, speedMps, bearingDeg = 90f, timeMillis = T0 + sec * 1_000L)

private fun gpx(name: String): List<LocationFix> =
    GpxParser.parse(checkNotNull(NavigationSessionTest::class.java.getResource("/gpx/$name.gpx")).readText())

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSessionTest {

    private val phrases = KoreanPhrases()

    /** 동쪽 2,000m 직선 경로와 그 끝의 목적지. deviate/spike GPX와 같은 좌표계. */
    private val straight = straightRoute(2_000.0)
    private val destination = Stop(1, "목적지", pointAt(2_000.0, 0.0), isDestination = true)

    private fun TestScope.session(route: Route, stops: List<Stop>, router: Router) = NavigationSession(
        initialRoute = route,
        stops = stops,
        profile = RouteProfile.BALANCED,
        router = router,
        phrases = phrases,
        scope = backgroundScope,
    )

    /** 세션의 모든 업데이트를 즉시 수집한다. */
    private fun TestScope.collect(session: NavigationSession): List<NavUpdate> {
        val updates = mutableListOf<NavUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.updates.toList(updates) }
        return updates
    }

    /** fix 하나를 넣고, 그 사이 끝난 재탐색 코루틴을 실행한다(실제 1초 간격 공급을 흉내). */
    private fun TestScope.feed(session: NavigationSession, fix: LocationFix) {
        session.onFix(fix)
        runCurrent()
    }

    private fun List<NavUpdate>.texts(): List<String> = flatMap { u -> u.utterances.map { it.text } }

    @Test
    fun `follows route and arrives`() = runTest {
        val route = routeOf(
            listOf(pointAt(0.0, 0.0), pointAt(1_500.0, 0.0), pointAt(1_500.0, 1_000.0), pointAt(2_200.0, 1_000.0)),
            mapOf(1 to TurnType.LEFT, 2 to TurnType.RIGHT),
        )
        val dest = Stop(7, "목적지", pointAt(2_200.0, 1_000.0), isDestination = true)
        val router = FakeRouter { error("no reroute expected") }
        val session = session(route, listOf(dest), router)
        val updates = collect(session)

        val fixes = gpx("follow")
        fixes.forEach { feed(session, it) }
        session.onTick(fixes.last().timeMillis + 60_000)

        val utterances = updates.flatMap { it.utterances }
        assertEquals(
            listOf("200미터 앞에서 좌회전입니다", "좌회전입니다", "200미터 앞에서 우회전입니다", "우회전입니다"),
            utterances.filter { it.priority == Priority.TURN }.map { it.text },
        )
        val kmReports = utterances.filter { it.priority == Priority.PERIODIC }.map { it.text }
        assertEquals(3, kmReports.size)
        assertEquals(listOf("1킬로미터", "2킬로미터", "3킬로미터"), kmReports.map { it.substringBefore(" ") })
        assertEquals(phrases.arrivedDestination, utterances.last().text)
        assertEquals(1, utterances.count { it.text == phrases.arrivedDestination })

        assertEquals(listOf<NavEvent>(NavEvent.StopReached(dest)), updates.flatMap { it.events })
        // 목적지 도착 업데이트가 마지막이다: 이후 fix와 onTick은 아무것도 방출하지 않는다.
        assertEquals(listOf<NavEvent>(NavEvent.StopReached(dest)), updates.last().events)
        assertTrue(updates.size < fixes.size)
        assertEquals(0, router.calls)
        assertTrue(session.sessionDistanceM > 3_000.0)
    }

    @Test
    fun `waypoint reached announces and continues to destination`() = runTest {
        val vertices = listOf(pointAt(0.0, 0.0), pointAt(500.0, 0.0), pointAt(1_000.0, 0.0))
        val route = routeOf(vertices).copy(stopPointIndices = listOf(1, 2))
        val waypoint = Stop(1, "경유지", vertices[1], isDestination = false)
        val dest = Stop(2, "목적지", vertices[2], isDestination = true)
        val session = session(route, listOf(waypoint, dest), FakeRouter { error("no reroute expected") })
        val updates = collect(session)

        for (k in 0..200) feed(session, fixAt(5.0 * k, 0.0, k))

        val events = updates.flatMap { it.events }
        assertEquals(listOf<NavEvent>(NavEvent.StopReached(waypoint), NavEvent.StopReached(dest)), events)
        assertEquals(listOf(phrases.arrivedWaypoint, phrases.arrivedDestination), updates.texts().filter {
            it == phrases.arrivedWaypoint || it == phrases.arrivedDestination
        })
    }

    @Test
    fun `deviation triggers exactly one reroute`() = runTest {
        val router = FakeRouter { request ->
            RouteResult.Success(routeOf(listOf(request.start, pointAt(2_000.0, 100.0), pointAt(2_000.0, 0.0))))
        }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        val fixes = gpx("deviate")
        fixes.forEach { feed(session, it) }

        assertEquals(1, router.calls)
        val request = router.requests.single()
        assertEquals(listOf(destination.point), request.stops)
        assertEquals(RouteProfile.BALANCED, request.profile)
        // 이탈 시작(30초) 후 8초째 fix(동쪽 190m, 북쪽 100m)에서 재탐색한다.
        assertTrue(GeoMath.distanceM(request.start, pointAt(190.0, 100.0)) < 1.0)

        val rerouted = updates.flatMap { it.events }.filterIsInstance<NavEvent.Rerouted>()
        assertEquals(1, rerouted.size)
        assertEquals(1, updates.texts().count { it == phrases.offRoute })
        assertEquals(rerouted.single().route, updates.last().state.route)
        assertFalse(updates.last().state.rerouting)
        assertEquals(fixes.size, updates.size)
    }

    @Test
    fun `gps spike does not reroute`() = runTest {
        val router = FakeRouter { error("no reroute expected") }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        gpx("spike").forEach { feed(session, it) }

        assertEquals(0, router.calls)
        assertEquals(0, updates.texts().count { it == phrases.offRoute })
        assertTrue(updates.none { it.state.rerouting })
    }

    @Test
    fun `reroute is not started twice while in flight`() = runTest {
        val gate = CompletableDeferred<RouteResult>()
        val router = FakeRouter { gate.await() }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s))
        for (s in 10..18) feed(session, fixAt(5.0 * s, 100.0, s)) // 이탈 8초째(18초)에 재탐색 시작
        assertEquals(1, router.calls)
        assertTrue(updates.last().state.rerouting)

        for (s in 19..48) feed(session, fixAt(5.0 * s, 100.0, s)) // 응답 대기 중 30초 더 이탈
        assertEquals(1, router.calls)
        assertTrue(updates.takeLast(30).all { it.state.rerouting })
        assertEquals(1, updates.texts().count { it == phrases.offRoute })

        val newRoute = routeOf(listOf(pointAt(240.0, 100.0), pointAt(2_000.0, 100.0), pointAt(2_000.0, 0.0)))
        gate.complete(RouteResult.Success(newRoute))
        runCurrent()
        feed(session, fixAt(245.0, 100.0, 49))

        assertEquals(listOf<NavEvent>(NavEvent.Rerouted(newRoute)), updates.last().events)
        assertFalse(updates.last().state.rerouting)
        assertEquals(newRoute, updates.last().state.route)
        assertEquals(1, router.calls)
    }

    @Test
    fun `reroute failure announces and retries after 30s`() = runTest {
        val router = FakeRouter { RouteResult.Failure(RouteFailure.NO_ROUTE, "none") }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s))
        for (s in 10..18) feed(session, fixAt(5.0 * s, 100.0, s)) // 18초에 첫 재탐색 → 실패
        assertEquals(1, router.calls)

        feed(session, fixAt(95.0, 100.0, 19))
        assertEquals(listOf<NavEvent>(NavEvent.RerouteFailed), updates.last().events)
        assertEquals(listOf(Utterance(phrases.rerouteFailed, Priority.EVENT)), updates.last().utterances)
        assertFalse(updates.last().state.rerouting)

        // 실패 시각(18초) + 30초 전까지는 이탈이 계속돼도 다시 시도하지 않는다.
        for (s in 20..47) feed(session, fixAt(5.0 * s, 100.0, s))
        assertEquals(1, router.calls)

        feed(session, fixAt(240.0, 100.0, 48))
        assertEquals(2, router.calls)
        feed(session, fixAt(245.0, 100.0, 49))
        assertEquals(2, updates.texts().count { it == phrases.rerouteFailed })
        assertEquals(1, updates.texts().count { it == phrases.offRoute })
    }

    @Test
    fun `detector does not start a second reroute while a retry is in flight`() = runTest {
        val gate = CompletableDeferred<RouteResult>()
        var call = 0
        val router = FakeRouter { if (++call == 1) RouteResult.Failure(RouteFailure.NO_ROUTE, "none") else gate.await() }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s))
        for (s in 10..48) feed(session, fixAt(5.0 * s, 100.0, s)) // 18초 실패 → 48초 재시도(응답 대기)
        assertEquals(2, router.calls)

        // 재시도는 이탈 판정기를 거치지 않으므로, 판정기는 49초부터 다시 세어 57초에 true를 낸다.
        for (s in 49..70) feed(session, fixAt(5.0 * s, 100.0, s))
        assertEquals(2, router.calls)
        assertTrue(updates.takeLast(22).all { it.state.rerouting })
        assertEquals(1, updates.texts().count { it == phrases.offRoute })
    }

    @Test
    fun `back on route during retry wait resumes normal detection with announcement`() = runTest {
        val router = FakeRouter { RouteResult.Failure(RouteFailure.NO_ROUTE, "none") }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s))
        for (s in 10..18) feed(session, fixAt(5.0 * s, 100.0, s)) // 18초 실패, 재시도 예정 48초
        for (s in 19..25) feed(session, fixAt(5.0 * s, 0.0, s)) // 경로 복귀 → 재시도 취소
        for (s in 26..33) feed(session, fixAt(5.0 * s, 100.0, s)) // 48초 전에 다시 이탈, 8초째(34초) 전
        assertEquals(1, router.calls)

        feed(session, fixAt(5.0 * 34, 100.0, 34)) // 일반 이탈 판정(8초, 마지막 종료 후 15초 이상)
        assertEquals(2, router.calls)
        assertEquals(2, updates.texts().count { it == phrases.offRoute })
    }

    @Test
    fun `spike after returning to route does not trigger the cancelled retry`() = runTest {
        val router = FakeRouter { RouteResult.Failure(RouteFailure.NO_ROUTE, "none") }
        val session = session(straight, listOf(destination), router)
        collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s))
        for (s in 10..18) feed(session, fixAt(5.0 * s, 100.0, s)) // 18초 실패, 재시도 예정 48초
        for (s in 19..47) feed(session, fixAt(5.0 * s, 0.0, s)) // 경로 복귀
        feed(session, fixAt(5.0 * 48, 80.0, 48)) // 원래 재시도 시각에 80m 튐 한 점
        for (s in 49..70) feed(session, fixAt(5.0 * s, 0.0, s))

        assertEquals(1, router.calls)
    }

    @Test
    fun `unusable rerouted route is treated as failure`() = runTest {
        val vertices = listOf(pointAt(0.0, 0.0), pointAt(500.0, 0.0), pointAt(1_000.0, 0.0))
        val route = routeOf(vertices).copy(stopPointIndices = listOf(1, 2))
        val waypoint = Stop(1, "경유지", vertices[1], isDestination = false)
        val dest = Stop(2, "목적지", vertices[2], isDestination = true)
        // 남은 stop은 2개인데 stop 인덱스가 1개뿐인 경로.
        val router = FakeRouter { RouteResult.Success(routeOf(listOf(it.start, vertices[2]))) }
        val session = session(route, listOf(waypoint, dest), router)
        val updates = collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s))
        for (s in 10..19) feed(session, fixAt(5.0 * s, 100.0, s)) // 18초 재탐색 → 19초 업데이트에 결과

        assertEquals(1, router.calls)
        assertEquals(listOf<NavEvent>(NavEvent.RerouteFailed), updates.flatMap { it.events })
        assertEquals(1, updates.texts().count { it == phrases.rerouteFailed })
        assertEquals(route, updates.last().state.route)
        assertFalse(updates.last().state.rerouting)
    }

    @Test
    fun `waypoint reached while rerouting keeps only remaining stops on the new route`() = runTest {
        val vertices = listOf(pointAt(0.0, 0.0), pointAt(500.0, 0.0), pointAt(1_000.0, 0.0))
        val route = routeOf(vertices).copy(stopPointIndices = listOf(1, 2))
        val waypoint = Stop(1, "경유지", vertices[1], isDestination = false)
        val dest = Stop(2, "목적지", vertices[2], isDestination = true)
        val gate = CompletableDeferred<RouteResult>()
        val router = FakeRouter { gate.await() }
        val session = session(route, listOf(waypoint, dest), router)
        val updates = collect(session)

        for (s in 0..4) feed(session, fixAt(400.0 + 5.0 * s, 0.0, s))
        for (s in 5..13) feed(session, fixAt(400.0 + 5.0 * s, 100.0, s)) // 13초 재탐색(응답 대기)
        assertEquals(listOf(waypoint.point, dest.point), router.requests.single().stops)
        for (s in 14..20) feed(session, fixAt(470.0 + 5.0 * (s - 14), 0.0, s)) // 복귀, 경유지 도달

        // 요청 당시 stop 2개(경유지, 목적지)에 대한 경로가 돌아온다.
        val newRoute = routeOf(listOf(pointAt(465.0, 100.0), vertices[1], vertices[2])).copy(stopPointIndices = listOf(1, 2))
        gate.complete(RouteResult.Success(newRoute))
        runCurrent()
        for (s in 21..130) feed(session, fixAt(500.0 + 5.0 * (s - 20), 0.0, s))

        assertEquals(
            listOf(NavEvent.StopReached(waypoint), NavEvent.Rerouted(newRoute), NavEvent.StopReached(dest)),
            updates.flatMap { it.events },
        )
        assertEquals(1, updates.texts().count { it == phrases.arrivedWaypoint })
        assertEquals(phrases.arrivedDestination, updates.texts().last())
    }

    @Test
    fun `inaccurate fix does not change speed`() = runTest {
        val session = session(straight, listOf(destination), FakeRouter { error("no reroute expected") })
        val updates = collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s, speedMps = null))
        val before = updates.last().state.speedMps
        assertEquals(5.0, before, 0.05)

        feed(session, fixAt(45.0 + 300.0, 0.0, 10, accuracyM = 50f, speedMps = null)) // 300m 점프, 정확도 50m
        assertEquals(before, updates.last().state.speedMps)

        feed(session, fixAt(55.0, 0.0, 11, speedMps = null)) // 직전 채택 위치(9초, 45m) 기준 10m/2초
        assertEquals(5.0, updates.last().state.speedMps, 0.05)
    }

    @Test
    fun `inaccurate fixes are excluded from progress and off-route`() = runTest {
        val router = FakeRouter { error("no reroute expected") }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s))
        val before = updates.last().state.progress
        for (s in 10..40) feed(session, fixAt(5.0 * s, 100.0, s, accuracyM = 50f))

        assertEquals(0, router.calls)
        assertEquals(41, updates.size)
        assertTrue(updates.drop(10).all { it.state.progress == before })
    }

    @Test
    fun `destination reached while rerouting cancels the reroute and ends the session`() = runTest {
        val gate = CompletableDeferred<RouteResult>()
        val router = FakeRouter { gate.await() }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        for (s in 0..4) feed(session, fixAt(1_700.0 + 5.0 * s, 0.0, s))
        for (s in 5..13) feed(session, fixAt(1_700.0 + 5.0 * s, 100.0, s)) // 13초에 재탐색 시작(응답 대기)
        assertEquals(1, router.calls)
        for (s in 14..60) feed(session, fixAt(1_700.0 + 5.0 * s, 0.0, s)) // 경로 복귀 후 목적지 도착

        assertEquals(listOf<NavEvent>(NavEvent.StopReached(destination)), updates.last().events)
        assertFalse(updates.last().state.rerouting)
        val count = updates.size

        gate.complete(RouteResult.Success(straight))
        runCurrent()
        feed(session, fixAt(2_005.0, 0.0, 61))
        session.onTick(T0 + 120_000)

        assertEquals(count, updates.size)
        assertTrue(updates.flatMap { it.events }.none { it is NavEvent.Rerouted })
        assertEquals(1, router.calls)
    }

    @Test
    fun `router exception is treated as failure`() = runTest {
        val router = FakeRouter { throw IllegalStateException("engine crashed") }
        val session = session(straight, listOf(destination), router)
        val updates = collect(session)

        for (s in 0..9) feed(session, fixAt(5.0 * s, 0.0, s))
        for (s in 10..19) feed(session, fixAt(5.0 * s, 100.0, s))

        assertEquals(1, router.calls)
        assertTrue(updates.flatMap { it.events }.contains(NavEvent.RerouteFailed))
        assertEquals(1, updates.texts().count { it == phrases.rerouteFailed })
        assertFalse(updates.last().state.rerouting)
    }

    @Test
    fun `gps weak after 10s silence`() = runTest {
        val session = session(straight, listOf(destination), FakeRouter { error("no reroute expected") })
        val updates = collect(session)

        feed(session, fixAt(0.0, 0.0, 0))
        val t0 = 1_000_000L // tick 시계는 fix 시각과 무관하다.
        session.onTick(t0)
        session.onTick(t0 + 5_000)
        session.onTick(t0 + 10_000 - 1)
        assertEquals(1, updates.size)

        session.onTick(t0 + 10_001)
        assertEquals(2, updates.size)
        assertTrue(updates.last().state.gpsWeak)
        assertEquals(listOf(Utterance(phrases.gpsWeak, Priority.EVENT)), updates.last().utterances)
        assertEquals(updates.first().state.etaMillis, updates.last().state.etaMillis) // 마지막 fix 기준 ETA 유지

        session.onTick(t0 + 11_000)
        session.onTick(t0 + 30_000)
        assertEquals(2, updates.size)
        assertEquals(1, updates.texts().count { it == phrases.gpsWeak })

        feed(session, fixAt(5.0, 0.0, 31))
        assertEquals(3, updates.size)
        assertFalse(updates.last().state.gpsWeak)
        assertEquals(emptyList(), updates.last().utterances)
    }

    @Test
    fun `gps watchdog ignores fix clock skew`() = runTest {
        val session = session(straight, listOf(destination), FakeRouter { error("no reroute expected") })
        val updates = collect(session)
        val tickBase = 5_000_000_000_000L // fix 시각(2026-10-06)과 전혀 다른 시계

        for (s in 0..60) {
            feed(session, fixAt(5.0 * s, 0.0, s))
            session.onTick(tickBase + s * 1_000L)
        }

        assertEquals(61, updates.size)
        assertTrue(updates.none { it.state.gpsWeak })
        assertEquals(0, updates.texts().count { it == phrases.gpsWeak })
    }

    @Test
    fun `gps weak before first fix starts from first tick`() = runTest {
        val session = session(straight, listOf(destination), FakeRouter { error("no reroute expected") })
        val updates = collect(session)

        session.onTick(T0)
        session.onTick(T0 + 10_000 - 1)
        assertEquals(0, updates.size)

        session.onTick(T0 + 10_001)
        val update = updates.single()
        assertTrue(update.state.gpsWeak)
        assertEquals(0.0, update.state.progress.distanceAlongM)
        assertEquals(straight.cumulativeM.last(), update.state.progress.remainingM)
    }
}
