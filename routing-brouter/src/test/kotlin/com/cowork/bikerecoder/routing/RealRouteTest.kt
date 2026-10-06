package com.cowork.bikerecoder.routing

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Routes on the real E125_N35 segment (Seoul area); needs network on first run to fetch it. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealRouteTest {

    private val yeouido = GeoPoint(37.5284, 126.9327)
    private val banpo = GeoPoint(37.5100, 126.9960)

    private lateinit var segDir: File
    private lateinit var profileDir: File
    private lateinit var router: BRouterRouter
    private val raw = mutableMapOf<RouteProfile, Pair<RouteResult, String?>>()

    @BeforeAll
    fun setUp() {
        segDir = SegmentFixture.ensure("E125_N35.rd5")
        profileDir = ProfileInstaller.install(Files.createTempDirectory("brouter-profiles").toFile())
        router = BRouterRouter(segDir, profileDir)
        for (p in RouteProfile.entries) {
            raw[p] = runBlocking { router.routeRaw(RouteRequest(yeouido, null, listOf(banpo), p)) }
        }
    }

    private fun success(p: RouteProfile) =
        assertIs<RouteResult.Success>(raw.getValue(p).first, "$p: ${raw.getValue(p).first}").route

    private fun wayRows(json: String): List<Pair<Double, String>> {
        val messages = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray[0]
            .jsonObject["properties"]!!.jsonObject["messages"]!!.jsonArray
        val header = messages[0].jsonArray.map { it.jsonPrimitive.content }
        val d = header.indexOf("Distance")
        val t = header.indexOf("WayTags")
        return messages.drop(1).map { it.jsonArray[d].jsonPrimitive.content.toDouble() to it.jsonArray[t].jsonPrimitive.content }
    }

    @Test
    fun `yeouido to banpo cycleway-first is at least 70 percent cycle friendly`() {
        val ratio = success(RouteProfile.CYCLEWAY_FIRST).summary.cyclewayRatio
        println("cycleway-first cyclewayRatio = $ratio")
        assertTrue(ratio >= 0.70, "cycleway ratio $ratio < 0.70")
    }

    @Test
    fun `no motorroad motorway or trunk on any profile`() {
        val forbidden = Regex("""(?:^|\s)(?:motorroad=yes|highway=(?:motorway|motorway_link|trunk|trunk_link))(?=\s|$)""")
        for (p in RouteProfile.entries) {
            val json = assertNotNull(raw.getValue(p).second, "$p produced no GeoJSON")
            val rows = wayRows(json)
            assertTrue(rows.isNotEmpty(), "$p has no message rows")
            val bad = rows.filter { forbidden.containsMatchIn(it.second) }.sumOf { it.first }
            assertEquals(0.0, bad, 0.0, "$p: $bad m on forbidden roads")
        }
    }

    @Test
    fun `profile ordering`() {
        val routes = RouteProfile.entries.associateWith { success(it) }
        val dist = routes.mapValues { it.value.summary.totalDistanceM }
        val ratio = routes.mapValues { it.value.summary.cyclewayRatio }
        println("distances (m): $dist")
        println("cycleway ratios: $ratio")

        val tol = 1.05
        val s = dist.getValue(RouteProfile.SHORTEST)
        val b = dist.getValue(RouteProfile.BALANCED)
        val c = dist.getValue(RouteProfile.CYCLEWAY_FIRST)
        assertTrue(s <= b * tol, "distance SHORTEST $s > BALANCED $b")
        assertTrue(b <= c * tol, "distance BALANCED $b > CYCLEWAY_FIRST $c")

        val rs = ratio.getValue(RouteProfile.SHORTEST)
        val rb = ratio.getValue(RouteProfile.BALANCED)
        val rc = ratio.getValue(RouteProfile.CYCLEWAY_FIRST)
        assertTrue(rc * tol >= rb, "ratio CYCLEWAY_FIRST $rc < BALANCED $rb")
        assertTrue(rb * tol >= rs, "ratio BALANCED $rb < SHORTEST $rs")
    }

    @Test
    fun `instructions converted`() {
        for (p in RouteProfile.entries) {
            val route = success(p)
            assertTrue(route.instructions.isNotEmpty(), "$p has no instructions")
            assertTrue(route.instructions.all { it.pointIndex in route.points.indices }, "$p pointIndex out of range")
            assertEquals(route.instructions.sortedBy { it.distanceFromStartM }, route.instructions, "$p ordering")
            assertEquals(listOf(route.points.lastIndex), route.stopPointIndices, "$p destination index")
        }
    }

    @Test
    fun `seoul to yeoju within 60s`() {
        val request = RouteRequest(GeoPoint(37.5663, 126.9779), null, listOf(GeoPoint(37.2982, 127.6372)), RouteProfile.BALANCED)
        lateinit var result: RouteResult
        val ms = measureTimeMillis { result = runBlocking { router.route(request) } }
        println("seoul->yeoju took $ms ms")
        assertTrue(ms < 60_000, "took $ms ms")
        val success = assertIs<RouteResult.Success>(result, result.toString())
        println("seoul->yeoju distance ${success.route.summary.totalDistanceM} m, ratio ${success.route.summary.cyclewayRatio}")
    }

    @Test
    fun `missing segment returns NO_SEGMENT_DATA`() {
        val empty = Files.createTempDirectory("empty-segments").toFile()
        val result = runBlocking {
            BRouterRouter(empty, profileDir).route(RouteRequest(yeouido, null, listOf(banpo), RouteProfile.BALANCED))
        }
        val failure = assertIs<RouteResult.Failure>(result, result.toString())
        assertEquals(RouteFailure.NO_SEGMENT_DATA, failure.reason, failure.detail)
    }
}
