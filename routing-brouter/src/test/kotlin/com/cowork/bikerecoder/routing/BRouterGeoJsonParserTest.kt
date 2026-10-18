package com.cowork.bikerecoder.routing

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.TurnType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BRouterGeoJsonParserTest {

    private val fixture: String =
        javaClass.getResourceAsStream("/fixtures/small.geojson")!!.use { it.readBytes().decodeToString() }

    private val last = GeoPoint(37.5030, 126.9015)

    @Test
    fun `parses points instructions ratio`() {
        val r = BRouterGeoJsonParser.parse(fixture, RouteProfile.BALANCED, listOf(last))
        assertEquals(4, r.points.size)
        assertEquals(GeoPoint(37.5013, 126.9), r.points[1])
        assertEquals(listOf(TurnType.LEFT, TurnType.ROUNDABOUT), r.instructions.map { it.type })
        assertEquals(2, r.instructions[1].roundaboutExit)
        assertEquals(0.75, r.summary.cyclewayRatio, 1e-9)
        assertEquals(listOf(3), r.stopPointIndices)
    }

    @Test
    fun `cumulative distances, instruction offsets and summary`() {
        val r = BRouterGeoJsonParser.parse(fixture, RouteProfile.CYCLEWAY_FIRST, listOf(last))
        assertEquals(GeoMath.cumulativeDistances(r.points), r.cumulativeM)
        assertEquals(0.0, r.cumulativeM[0])
        assertEquals(listOf(1, 2), r.instructions.map { it.pointIndex })
        assertEquals(r.cumulativeM[1], r.instructions[0].distanceFromStartM, 1e-9)
        assertEquals(r.cumulativeM[2], r.instructions[1].distanceFromStartM, 1e-9)
        assertEquals(0, r.instructions[0].roundaboutExit)
        assertEquals(7, r.summary.ascentM)
        assertEquals(r.cumulativeM.last(), r.summary.totalDistanceM, 1e-9)
        assertEquals(RouteProfile.CYCLEWAY_FIRST, r.summary.profile)
    }

    @Test
    fun `stop indices are searched forward from the previous stop`() {
        val via = GeoPoint(37.5013, 126.9015) // point 2
        val r = BRouterGeoJsonParser.parse(fixture, RouteProfile.BALANCED, listOf(via, last))
        assertEquals(listOf(2, 3), r.stopPointIndices)
        // A stop that is closest to an earlier point still resolves at or after the previous stop.
        val back = BRouterGeoJsonParser.parse(fixture, RouteProfile.BALANCED, listOf(via, GeoPoint(37.5, 126.9)))
        assertEquals(listOf(2, 2), back.stopPointIndices)
    }

    @Test
    fun `voice hint commands map to turn types and unsupported ones are dropped`() {
        fun route(cmd: Int) = BRouterGeoJsonParser.parse(
            fixture.replace("[1,2,0,100.0,-90]", "[1,$cmd,0,100.0,-90]"),
            RouteProfile.BALANCED,
            listOf(last),
        ).instructions.first().type

        val expected = mapOf(
            1 to TurnType.STRAIGHT, 2 to TurnType.LEFT, 3 to TurnType.SLIGHT_LEFT, 4 to TurnType.SHARP_LEFT,
            5 to TurnType.RIGHT, 6 to TurnType.SLIGHT_RIGHT, 7 to TurnType.SHARP_RIGHT,
            8 to TurnType.KEEP_LEFT, 9 to TurnType.KEEP_RIGHT,
            10 to TurnType.U_TURN, 11 to TurnType.U_TURN, 15 to TurnType.U_TURN,
            13 to TurnType.ROUNDABOUT, 14 to TurnType.ROUNDABOUT,
        )
        for ((cmd, type) in expected) assertEquals(type, route(cmd), "cmd $cmd")

        for (cmd in listOf(12, 16)) {
            val r = BRouterGeoJsonParser.parse(
                fixture.replace("[1,2,0,100.0,-90]", "[1,$cmd,0,100.0,-90]"),
                RouteProfile.BALANCED,
                listOf(last),
            )
            assertEquals(listOf(TurnType.ROUNDABOUT), r.instructions.map { it.type }, "cmd $cmd dropped")
        }
    }

    @Test
    fun `roundabout exit number is positive for both directions`() {
        fun exit(cmd: Int, exit: Int) = BRouterGeoJsonParser.parse(
            fixture.replace("[2,13,2,50.0,0]", "[2,$cmd,$exit,50.0,0]"),
            RouteProfile.BALANCED,
            listOf(last),
        ).instructions[1]

        assertEquals(2, exit(13, 2).roundaboutExit)
        val left = exit(14, -2)
        assertEquals(TurnType.ROUNDABOUT, left.type)
        assertEquals(2, left.roundaboutExit)
    }

    @Test
    fun `route without voicehints or ascent still parses`() {
        val json = """
            {"type":"FeatureCollection","features":[{"type":"Feature","properties":{
              "messages":[["Longitude","Latitude","Elevation","Distance","CostPerKm","ElevCost","TurnCost","NodeCost","InitialCost","WayTags","NodeTags","Time","Energy"]]},
              "geometry":{"type":"LineString","coordinates":[[126.9,37.5],[126.91,37.5]]}}]}
        """.trimIndent()
        val r = BRouterGeoJsonParser.parse(json, RouteProfile.SHORTEST, listOf(GeoPoint(37.5, 126.91)))
        assertEquals(2, r.points.size)
        assertTrue(r.instructions.isEmpty())
        assertEquals(0, r.summary.ascentM)
        assertEquals(0.0, r.summary.cyclewayRatio)
        assertEquals(listOf(1), r.stopPointIndices)
    }
}
