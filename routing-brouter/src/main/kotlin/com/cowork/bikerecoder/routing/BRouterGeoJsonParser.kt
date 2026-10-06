package com.cowork.bikerecoder.routing

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Instruction
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.model.TurnType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Converts the GeoJSON emitted by BRouter's `FormatJson` into the app's [Route] model. */
object BRouterGeoJsonParser {

    /**
     * @param stops the via points followed by the destination, in order; each is mapped to the
     * index of the closest route point at or after the previous stop's index.
     */
    fun parse(json: String, profile: RouteProfile, stops: List<GeoPoint>): Route {
        val feature = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.first().jsonObject
        val properties = feature["properties"]?.jsonObject ?: JsonObject(emptyMap())

        val points = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { c ->
            val coord = c.jsonArray
            GeoPoint(lat = coord[1].jsonPrimitive.content.toDouble(), lon = coord[0].jsonPrimitive.content.toDouble())
        }
        val cumulativeM = GeoMath.cumulativeDistances(points)

        return Route(
            points = points,
            cumulativeM = cumulativeM,
            instructions = parseInstructions(properties["voicehints"], cumulativeM),
            stopPointIndices = stopIndices(points, stops),
            summary = RouteSummary(
                totalDistanceM = cumulativeM.lastOrNull() ?: 0.0,
                ascentM = properties["filtered ascend"].asDouble()?.toInt() ?: 0,
                cyclewayRatio = cyclewayRatio(properties["messages"]),
                profile = profile,
            ),
        )
    }

    private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    private fun parseInstructions(voiceHints: JsonElement?, cumulativeM: List<Double>): List<Instruction> {
        val hints = (voiceHints as? JsonArray) ?: return emptyList()
        return hints.mapNotNull { h ->
            val hint = h.jsonArray
            val index = hint[0].jsonPrimitive.content.toInt()
            val type = turnType(hint[1].jsonPrimitive.content.toInt()) ?: return@mapNotNull null
            if (index !in cumulativeM.indices) return@mapNotNull null
            Instruction(
                pointIndex = index,
                type = type,
                roundaboutExit = if (type == TurnType.ROUNDABOUT) hint[2].jsonPrimitive.content.toInt() else 0,
                distanceFromStartM = cumulativeM[index],
            )
        }
    }

    // Command codes of BRouter's FormatJson.getJsonCommandIndex for turnInstructionMode 3 (osmand).
    // 12 (off route) and 16 (beeline) are not turns and are dropped.
    private fun turnType(cmd: Int): TurnType? = when (cmd) {
        1 -> TurnType.STRAIGHT
        2 -> TurnType.LEFT
        3 -> TurnType.SLIGHT_LEFT
        4 -> TurnType.SHARP_LEFT
        5 -> TurnType.RIGHT
        6 -> TurnType.SLIGHT_RIGHT
        7 -> TurnType.SHARP_RIGHT
        8 -> TurnType.KEEP_LEFT
        9 -> TurnType.KEEP_RIGHT
        10, 11, 15 -> TurnType.U_TURN
        13, 14 -> TurnType.ROUNDABOUT
        else -> null
    }

    private fun stopIndices(points: List<GeoPoint>, stops: List<GeoPoint>): List<Int> {
        var from = 0
        return stops.map { stop ->
            var best = from
            var bestDistance = Double.MAX_VALUE
            for (i in from until points.size) {
                val d = GeoMath.distanceM(points[i], stop)
                if (d < bestDistance) {
                    bestDistance = d
                    best = i
                }
            }
            from = best
            best
        }
    }

    private fun cyclewayRatio(messages: JsonElement?): Double {
        val rows = (messages as? JsonArray) ?: return 0.0
        val header = rows.firstOrNull()?.jsonArray?.map { it.jsonPrimitive.content } ?: return 0.0
        val distanceCol = header.indexOf("Distance")
        val tagsCol = header.indexOf("WayTags")
        if (distanceCol < 0 || tagsCol < 0) return 0.0

        var total = 0.0
        var friendly = 0.0
        for (row in rows.drop(1)) {
            val cells = row.jsonArray
            val distance = cells[distanceCol].jsonPrimitive.content.toDoubleOrNull() ?: continue
            total += distance
            if (CyclewayClassifier.isCycleFriendly(cells[tagsCol].jsonPrimitive.content)) friendly += distance
        }
        return if (total > 0.0) friendly / total else 0.0
    }
}
