package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.elevation.ClimbFinder
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.RideTimeEstimator
import com.cowork.bikerecoder.core.elevation.UsualSpeed
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun TestScope.session(
        route: Route,
        stops: List<Stop>,
        router: Router,
        initialDistanceM: Double = 0.0,
        usualSpeedMps: Double = UsualSpeed.DEFAULT_MPS,
    ) =
        NavigationSession(
            initialRoute = route,
            stops = stops,
            profile = RouteProfile.BALANCED,
            router = router,
            phrases = phrases,
            scope = backgroundScope,
            initialDistanceM = initialDistanceM,
            usualSpeedMps = usualSpeedMps,
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
    fun `loop route first fix beside the start does not snap to the end`() = runTest {
        val start = pointAt(0.0, 0.0)
        val route = routeOf(
            listOf(start, pointAt(300.0, 0.0), pointAt(300.0, 300.0), pointAt(0.0, 300.0), start),
            mapOf(1 to TurnType.LEFT, 2 to TurnType.LEFT, 3 to TurnType.LEFT),
        )
        val dest = Stop(1, "출발점", start, isDestination = true)
        val router = FakeRouter { error("no reroute expected") }
        val session = session(route, listOf(dest), router)
        val updates = collect(session)

        feed(session, fixAt(-3.0, 5.0, 0)) // 출발점 옆(마지막 선분이 더 가깝다)
        for (s in 1..30) feed(session, fixAt(5.0 * s, 0.0, s))

        assertEquals(0, router.calls)
        assertEquals(0, updates.texts().count { it == phrases.offRoute })
        assertTrue(updates.flatMap { it.events }.isEmpty())
        assertTrue(updates.none { it.state.rerouting })
        assertEquals(listOf("200미터 앞에서 좌회전입니다"), updates.flatMap { it.utterances }.filter { it.priority == Priority.TURN }.map { it.text })
        assertEquals(150.0, updates.last().state.progress.distanceAlongM, 5.0)
    }

    @Test
    fun `resumed with today's distance continues the kilometre count`() = runTest {
        val session = session(straight, listOf(destination), FakeRouter { error("no reroute expected") }, initialDistanceM = 3_400.0)
        val updates = collect(session)
        assertEquals(3_400.0, session.sessionDistanceM)

        for (s in 0..130) feed(session, fixAt(5.0 * s, 0.0, s)) // 650m → 오늘 4.05km

        val kmReports = updates.flatMap { it.utterances }.filter { it.priority == Priority.PERIODIC }.map { it.text }
        assertEquals(listOf("4킬로미터"), kmReports.map { it.substringBefore(" ") })
        assertTrue(kmReports.single().startsWith("4킬로미터 이동."))
        assertEquals(3_400.0 + GeoMath.distanceM(pointAt(0.0, 0.0), pointAt(650.0, 0.0)), session.sessionDistanceM, 1.0)
    }

    /** 테스트용 [KmAnnouncementSource]: 거리는 테스트가 정하고 문장은 "SRC $km". */
    private class FakeKmSource : KmAnnouncementSource {
        var d = 0.0
        override fun distanceM(): Double = d
        override fun phrase(km: Int, remainingM: Double, etaMillis: Long): String = "SRC $km"
    }

    private fun List<NavUpdate>.periodic(): List<String> =
        flatMap { u -> u.utterances.filter { it.priority == Priority.PERIODIC }.map { it.text } }

    @Test
    fun `km source replaces distance and phrase`() = runTest {
        val route = straightRoute(5_000.0)
        val dest = Stop(1, "목적지", pointAt(5_000.0, 0.0), isDestination = true)
        val session = session(route, listOf(dest), FakeRouter { error("no reroute expected") })
        val updates = collect(session)
        val source = FakeKmSource()
        session.setKmSource(source)

        // 오도미터는 1.5km를 지나지만 소스 거리는 999m: 안내가 없다.
        source.d = 999.0
        for (s in 0..300) feed(session, fixAt(5.0 * s, 0.0, s))
        assertTrue(session.sessionDistanceM > 1_400.0)
        assertEquals(emptyList(), updates.periodic())

        source.d = 1_000.0
        feed(session, fixAt(1_505.0, 0.0, 301))
        feed(session, fixAt(1_510.0, 0.0, 302))
        assertEquals(listOf("SRC 1"), updates.periodic())
    }

    @Test
    fun `setting a source does not announce past km`() = runTest {
        val route = straightRoute(5_000.0)
        val dest = Stop(1, "목적지", pointAt(5_000.0, 0.0), isDestination = true)
        val session = session(route, listOf(dest), FakeRouter { error("no reroute expected") })
        val updates = collect(session)

        for (s in 0..500) feed(session, fixAt(5.0 * s, 0.0, s)) // 약 2.5km
        val before = updates.size

        val source = FakeKmSource().apply { d = 1_700.0 }
        session.setKmSource(source)
        feed(session, fixAt(2_505.0, 0.0, 501))
        feed(session, fixAt(2_510.0, 0.0, 502))
        assertEquals(emptyList(), updates.drop(before).periodic())

        source.d = 2_000.0
        feed(session, fixAt(2_515.0, 0.0, 503))
        feed(session, fixAt(2_520.0, 0.0, 504))
        assertEquals(listOf("SRC 2"), updates.drop(before).periodic())
    }

    @Test
    fun `clearing the source resumes odometer km without repeats`() = runTest {
        val route = straightRoute(5_000.0)
        val dest = Stop(1, "목적지", pointAt(5_000.0, 0.0), isDestination = true)
        val session = session(route, listOf(dest), FakeRouter { error("no reroute expected") })
        val updates = collect(session)
        val source = FakeKmSource().apply { d = 100.0 }
        session.setKmSource(source)

        for (s in 0..500) feed(session, fixAt(5.0 * s, 0.0, s)) // 약 2.5km, 소스 거리는 100m
        assertEquals(emptyList(), updates.periodic())
        val before = updates.size

        session.setKmSource(null)
        for (s in 501..620) feed(session, fixAt(5.0 * s, 0.0, s)) // 약 3.1km
        val after = updates.drop(before)
        val kmUpdates = after.filter { u -> u.utterances.any { it.priority == Priority.PERIODIC } }
        assertEquals(1, kmUpdates.size)
        val u = kmUpdates.single()
        assertEquals(
            listOf(phrases.kmReport(3, u.state.progress.remainingM, u.state.etaMillis)),
            u.utterances.filter { it.priority == Priority.PERIODIC }.map { it.text },
        )
        assertTrue(u.utterances.single { it.priority == Priority.PERIODIC }.text.startsWith("3킬로미터 이동."))
    }

    @Test
    fun `source km waits for an imminent turn`() = runTest {
        val route = straightRoute(5_000.0, listOf(Instruction(1, TurnType.LEFT, 0, 1_000.0)))
        val dest = Stop(1, "목적지", pointAt(5_000.0, 0.0), isDestination = true)
        val session = session(route, listOf(dest), FakeRouter { error("no reroute expected") })
        val updates = collect(session)
        val source = FakeKmSource()
        session.setKmSource(source)

        for (s in 0..140) feed(session, fixAt(5.0 * s, 0.0, s)) // 700m, 아직 임박하지 않음
        assertEquals(emptyList(), updates.periodic())

        // 회전까지 약 250m, 5m/s: far 안내 15초 이내라 임박. 이때 소스 거리가 1km를 통과한다.
        source.d = 1_000.0
        feed(session, fixAt(750.0, 0.0, 150))
        assertEquals(emptyList(), updates.periodic())

        // near 안내가 나가는 업데이트에도 km 안내는 없다.
        feed(session, fixAt(975.0, 0.0, 195))
        assertEquals(emptyList(), updates.periodic())
        assertEquals("좌회전입니다", updates.last().utterances.single().text)

        // near 안내 다음 업데이트에 비로소 소스 문장이 나간다.
        feed(session, fixAt(990.0, 0.0, 198))
        assertEquals(listOf("SRC 1"), updates.periodic())
        assertEquals(listOf("SRC 1"), updates.last().utterances.map { it.text })
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

    // --- V1.7.0 고도: 도착 예정·앞길·오르막 음성 ---

    /** 3 km 동쪽 경로: 1,100~1,700 m가 6% 오르막. [turns]는 꼭짓점 인덱스(10 m 간격). */
    private fun climbRoute(turns: Map<Int, TurnType> = emptyMap()) =
        eastRouteWithElevation(3_000.0, turns = turns) { d ->
            when {
                d < 1_100.0 -> 0.0
                d < 1_700.0 -> (d - 1_100.0) * 0.06
                else -> 36.0
            }
        }

    private fun destinationOf(route: Route) = Stop(1, "목적지", route.points.last(), isDestination = true)

    @Test
    fun etaUsesUsualSpeedFirstFiveMinutes() = runTest {
        val total = straight.cumulativeM.last()
        val session = session(straight, listOf(destination), FakeRouter { error("no reroute") }, usualSpeedMps = 5.0)
        val updates = collect(session)

        // 2 m/s로 10초마다 20 m씩 6분.
        (0..36).forEach { i -> feed(session, fixAt(i * 20.0, 0.0, i * 10, speedMps = 2f)) }

        // 200초: 이동 데이터 5분 미만 -> 평소 속도 5 m/s.
        val early = updates[20].state
        val earlyFix = T0 + 200_000L
        assertEquals(earlyFix + ((total - early.progress.distanceAlongM) / 5.0 * 1_000).toLong(), early.etaMillis)

        // 360초: 최근 이동 속도 2 m/s.
        val late = updates[36].state
        val lateFix = T0 + 360_000L
        val expected = lateFix + ((total - late.progress.distanceAlongM) / 2.0 * 1_000).toLong()
        assertTrue(kotlin.math.abs(late.etaMillis - expected) < 2_000, "eta=${late.etaMillis} expected=$expected")
    }

    @Test
    fun etaAddsClimbTime() = runTest {
        val route = eastRouteWithElevation(3_000.0) { it * 0.05 }
        val total = route.cumulativeM.last()
        val session = session(route, listOf(destinationOf(route)), FakeRouter { error("no reroute") }, usualSpeedMps = 5.0)
        val updates = collect(session)

        feed(session, fixAt(0.0, 0.0, 0))

        val state = updates.single().state
        val profile = assertNotNull(ElevationProfile.fromRoute(route))
        val seconds = RideTimeEstimator.seconds(profile, state.progress.distanceAlongM, total, 5.0)
        assertEquals(T0 + (seconds * 1_000).toLong(), state.etaMillis)
        // 5% 오르막: 평지(600초)보다 확실히 길다.
        assertTrue(state.etaMillis - T0 > 600_000 * 1.4, "eta=${state.etaMillis - T0}")
    }

    @Test
    fun noProfileKeepsPlainEta() = runTest {
        val total = straight.cumulativeM.last()
        val session = session(straight, listOf(destination), FakeRouter { error("no reroute") })
        val updates = collect(session)

        feed(session, fixAt(0.0, 0.0, 0))

        val state = updates.single().state
        val remaining = total - state.progress.distanceAlongM
        assertEquals(T0 + (remaining / (15.0 / 3.6) * 1_000).toLong(), state.etaMillis)
        assertNull(state.profile)
        assertNull(state.ahead)
    }

    @Test
    fun climbVoiceAfterTurnWaits() = runTest {
        // 약 980 m(꼭짓점 98)에 좌회전: 오르막(약 1,095 m) 300 m 앞(약 795 m)에서 "…미터 앞에서" 회전 안내와 겹친다.
        val route = climbRoute(turns = mapOf(98 to TurnType.LEFT))
        val climb = ClimbFinder.find(assertNotNull(ElevationProfile.fromRoute(route))).first()
        val session = session(route, listOf(destinationOf(route)), FakeRouter { error("no reroute") })
        val updates = collect(session)

        (0..5).forEach { i -> feed(session, fixAt(i * 100.0, 0.0, i * 20)) }
        feed(session, fixAt(climb.startM - 300.0, 0.0, 200))
        feed(session, fixAt(climb.startM - 280.0, 0.0, 204))

        val isClimb = { u: Utterance -> "앞 오르막" in u.text }
        val turnUpdate = updates[6]
        assertTrue(turnUpdate.utterances.any { it.priority == Priority.TURN }, "${turnUpdate.utterances}")
        assertTrue(turnUpdate.utterances.none(isClimb), "${turnUpdate.utterances}")

        val next = updates[7]
        val gap = climb.startM - next.state.progress.distanceAlongM
        assertEquals(
            listOf(Utterance(phrases.climb(gap, climb.lengthM, climb.avgGradePct), Priority.PERIODIC)),
            next.utterances.filter(isClimb),
        )
        assertEquals(1, updates.flatMap { it.utterances }.count(isClimb))
    }

    @Test
    fun climbVoiceSpokenOnceAtThreeHundred() = runTest {
        val route = climbRoute()
        val climb = ClimbFinder.find(assertNotNull(ElevationProfile.fromRoute(route))).first()
        val session = session(route, listOf(destinationOf(route)), FakeRouter { error("no reroute") })
        val updates = collect(session)

        (0..5).forEach { i -> feed(session, fixAt(i * 100.0, 0.0, i * 20)) }
        feed(session, fixAt(climb.startM - 300.0, 0.0, 200))
        feed(session, fixAt(climb.startM - 280.0, 0.0, 204))

        val climbTexts = updates.flatMap { it.utterances }.filter { "앞 오르막" in it.text }
        assertEquals(1, climbTexts.size)
        assertEquals(climbTexts, updates[6].utterances.filter { "앞 오르막" in it.text })
    }

    @Test
    fun deferredClimbDroppedOncePassed() = runTest {
        val route = climbRoute(turns = mapOf(98 to TurnType.LEFT))
        val climb = ClimbFinder.find(assertNotNull(ElevationProfile.fromRoute(route))).first()
        val session = session(route, listOf(destinationOf(route)), FakeRouter { error("no reroute") })
        val updates = collect(session)

        (0..5).forEach { i -> feed(session, fixAt(i * 100.0, 0.0, i * 20)) }
        feed(session, fixAt(climb.startM - 300.0, 0.0, 200)) // 회전 안내와 겹쳐 미룸
        assertTrue(updates[6].utterances.any { it.priority == Priority.TURN }, "${updates[6].utterances}")
        feed(session, fixAt(climb.startM + 10.0, 0.0, 260)) // 오르막 시작을 지남
        feed(session, fixAt(climb.startM + 30.0, 0.0, 264))

        assertTrue(updates[7].state.progress.distanceAlongM > climb.startM)
        assertEquals(0, updates.flatMap { it.utterances }.count { "앞 오르막" in it.text })
    }

    @Test
    fun profileFollowsReroutedRoute() = runTest {
        // 처음 경로: deviate GPX와 같은 동쪽 2 km, 300~900 m가 6% 오르막.
        val initial = eastRouteWithElevation(2_000.0) { d -> ((d - 300.0) * 0.06).coerceIn(0.0, 36.0) }
        var newRoute: Route? = null
        val router = FakeRouter { request ->
            // 다시 탐색한 경로: 이탈 지점 → 북쪽 100 m 선을 따라 동쪽 2 km → 목적지. 1,000~1,600 m가 5% 오르막.
            val vertices = listOf(request.start) + (20..200).map { pointAt(it * 10.0, 100.0) } + pointAt(2_000.0, 0.0)
            val cumulative = GeoMath.cumulativeDistances(vertices)
            val r = Route(
                points = vertices,
                cumulativeM = cumulative,
                instructions = emptyList(),
                stopPointIndices = listOf(vertices.size - 1),
                summary = RouteSummary(cumulative.last(), 0, 0.0, RouteProfile.BALANCED),
                elevationsM = cumulative.map { d -> ((d - 1_000.0) * 0.05).coerceIn(0.0, 30.0) },
            )
            newRoute = r
            RouteResult.Success(r)
        }
        val session = session(initial, listOf(destination), router)
        val updates = collect(session)

        gpx("deviate").forEach { feed(session, it) }

        val rerouted = assertNotNull(newRoute)
        val state = updates.last().state
        assertEquals(rerouted, state.route)
        assertEquals(rerouted.cumulativeM.last(), assertNotNull(state.profile).totalM, 1e-6)
        val newClimbs = ClimbFinder.find(assertNotNull(ElevationProfile.fromRoute(rerouted)))
        assertEquals(1, newClimbs.size)
        assertEquals(AheadOfRider.state(newClimbs, state.progress.distanceAlongM), state.ahead)
        assertIs<AheadState.ClimbAhead>(state.ahead)
    }

    @Test
    fun navStateCarriesProfileAndAhead() = runTest {
        val route = climbRoute()
        val climbs = ClimbFinder.find(assertNotNull(ElevationProfile.fromRoute(route)))
        val climb = climbs.first()
        val session = session(route, listOf(destinationOf(route)), FakeRouter { error("no reroute") })
        val updates = collect(session)

        feed(session, fixAt(0.0, 0.0, 0))
        val start = updates.last().state
        assertEquals(route.cumulativeM.last(), assertNotNull(start.profile).totalM, 1e-6)
        assertIs<AheadState.ClimbAhead>(start.ahead)
        assertEquals(AheadOfRider.state(climbs, start.progress.distanceAlongM), start.ahead)

        listOf(500.0, 1_000.0, climb.startM + 100.0).forEachIndexed { i, east ->
            feed(session, fixAt(east, 0.0, (i + 1) * 100))
        }
        val inside = updates.last().state
        assertEquals(
            AheadState.InClimb(climb.endM - inside.progress.distanceAlongM, climb.avgGradePct),
            inside.ahead,
        )
    }
}
