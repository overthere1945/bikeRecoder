package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The video map (spec §1.1): the VWorld satellite photo under white place and big-road names from the base vector
 * style. The names are MapLibre symbols, so they stay upright while the camera turns (VWorld's own Hybrid label tiles
 * would turn with it). Without a usable base style the photo alone is drawn.
 */
object VideoMapStyle {
    const val ATTRIBUTION = "© 브이월드 · OpenStreetMap"

    private const val TILE_URL = "https://api.vworld.kr/req/wmts/1.0.0/%s/Satellite/{z}/{y}/{x}.jpeg"
    private val ROAD_CLASSES = listOf("motorway", "trunk", "primary", "secondary")

    /**
     * The place classes of spec §1.1, the smaller ones only close up so the sky view is not a map app's screen full of
     * names: city / town / village always, suburb from zoom 13, quarter / neighbourhood from zoom 15.
     */
    private val PLACE_FILTER = Json.parseToJsonElement(
        """["any",""" +
            """["match",["get","class"],["city","town","village"],true,false],""" +
            """["all",["==",["get","class"],"suburb"],[">=",["zoom"],13]],""" +
            """["all",["match",["get","class"],["quarter","neighbourhood"],true,false],[">=",["zoom"],15]]]""",
    )

    /** Road names only close up (the ride's 16.6–17.4), not on the sky view. */
    private const val ROAD_MIN_ZOOM = 15

    /** Place names 1.6× the base sizes, fixed: 18 at zoom 12 to 26 at zoom 17. */
    private val PLACE_SIZE = Json.parseToJsonElement("""["interpolate",["linear"],["zoom"],12,18,17,26]""")
    /** Ruling L1: 24 rather than the plan's 20, as the names read small on the 60° camera. */
    private const val ROAD_SIZE = 24
    private const val ROAD_SPACING = 600
    private val KOREAN_NAME = Json.parseToJsonElement("""["coalesce",["get","name:ko"],["get","name"]]""")

    /** [text] (a log line or error message that may hold a tile URL) with every [key] replaced by "***" (ruling K1). */
    fun redact(text: String, key: String): String = if (key.isBlank()) text else text.replace(key, "***")

    /**
     * [log] with [key] replaced by "***" in the message and in the error's text and its causes' (ruling K1) — for a log
     * that sees errors from the map (the video job's). An error without the key is passed on as it is.
     */
    fun redacting(log: WarnLog, key: String): WarnLog = WarnLog { message, error ->
        log.warn(redact(message, key), error?.let { redactedError(it, key) })
    }

    /**
     * [error] itself when there is nothing to hide (a blank [key], or no [key] anywhere in its text or its causes'); else a
     * copy with [key] replaced by "***" in its text and its causes' (ruling K1), the stack traces kept. The one redaction
     * for every log that may see a map error (the job's, [MapSnapshots]', the video scope's).
     */
    internal fun redactedError(error: Throwable, key: String): Throwable =
        if (key.isBlank() || key !in error.stackTraceToString()) error else copyRedacted(error, key)

    private fun copyRedacted(error: Throwable, key: String): Throwable =
        Redacted(redact(error.toString(), key), error.cause?.let { copyRedacted(it, key) }).apply { stackTrace = error.stackTrace }

    private class Redacted(text: String, cause: Throwable?) : Exception(text, cause)

    /** The style JSON for `Style.Builder().fromJson`; [vworldKey] is written into the tile URL only. */
    fun build(baseStyleJson: String?, vworldKey: String): String {
        val labels = baseStyleJson?.let(::labelsFrom)
        val style = buildJsonObject {
            put("version", 8)
            put("sources", buildJsonObject {
                put("vworld", buildJsonObject {
                    put("type", "raster")
                    putJsonArray("tiles") { add(JsonPrimitive(TILE_URL.format(vworldKey))) }
                    put("tileSize", 256)
                    put("minzoom", 6)
                    put("maxzoom", 19)
                })
                if (labels != null) put(labels.sourceId, labels.source)
            })
            if (labels != null) put("glyphs", labels.glyphs)
            put("layers", buildJsonArray {
                add(buildJsonObject {
                    put("id", "background")
                    put("type", "background")
                    put("paint", buildJsonObject { put("background-color", "#1A1F1A") })
                })
                add(buildJsonObject {
                    put("id", "vworld")
                    put("type", "raster")
                    put("source", "vworld")
                })
                if (labels != null) {
                    add(labelLayer("v-road", labels, "transportation_name", classMatch(ROAD_CLASSES), JsonPrimitive(ROAD_SIZE), line = true))
                    add(labelLayer("v-place", labels, "place", PLACE_FILTER, PLACE_SIZE, line = false))
                }
            })
        }
        return Json.encodeToString(JsonElement.serializer(), style)
    }

    /** What the labels take from the base style: its vector source, glyph URL and font. */
    private class Labels(val sourceId: String, val source: JsonElement, val glyphs: JsonElement, val font: String)

    /** Null when the base is not a style with glyphs, a vector source and some font (then: photo only). */
    private fun labelsFrom(baseStyleJson: String): Labels? {
        val base = runCatching { Json.parseToJsonElement(baseStyleJson) }.getOrNull() as? JsonObject ?: return null
        val glyphs = (base["glyphs"] as? JsonPrimitive)?.takeIf { it.isString } ?: return null
        val sources = base["sources"] as? JsonObject ?: return null
        val layers = (base["layers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val sourceId = layers
            .firstOrNull { it.string("source-layer") in setOf("place", "transportation_name") }
            ?.string("source")
            ?.takeIf { (sources[it] as? JsonObject)?.string("type") == "vector" }
            ?: sources.entries.firstOrNull { (it.value as? JsonObject)?.string("type") == "vector" }?.key
            ?: return null
        val fonts = layers.mapNotNull { layer ->
            ((layer["layout"] as? JsonObject)?.get("text-font") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.firstOrNull()
        }
        val first = fonts.firstOrNull() ?: return null
        val bold = first.substringBeforeLast(' ', missingDelimiterValue = first) + " Bold"
        return Labels(sourceId, sources.getValue(sourceId), glyphs, if (bold in fonts) bold else first)
    }

    private fun labelLayer(
        id: String,
        labels: Labels,
        sourceLayer: String,
        filter: JsonElement,
        size: JsonElement,
        line: Boolean,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "symbol")
        put("source", labels.sourceId)
        put("source-layer", sourceLayer)
        if (line) put("minzoom", ROAD_MIN_ZOOM)
        put("filter", filter)
        put("layout", buildJsonObject {
            put("text-field", KOREAN_NAME)
            putJsonArray("text-font") { add(JsonPrimitive(labels.font)) }
            put("text-size", size)
            if (line) {
                put("symbol-placement", "line")
                // Placed along the road but written level on the screen: line-aligned Korean names are stacked
                // upright in map space, so they stand at odd angles once the camera turns (seen on the descent).
                put("text-rotation-alignment", "viewport")
                // Fewer repeats: on the 60° camera the far roads are squeezed and the default 250 crowds the horizon.
                put("symbol-spacing", ROAD_SPACING)
            }
            // Upright and facing the viewer on the tilted camera, not lying squashed on the ground (ruling L1).
            put("text-pitch-alignment", "viewport")
        })
        put("paint", buildJsonObject {
            put("text-color", "#FFFFFF")
            put("text-halo-color", "rgba(0,0,0,0.75)")
            put("text-halo-width", 1.6)
        })
    }

    /** `["match", ["get", "class"], classes, true, false]`. */
    private fun classMatch(classes: List<String>): JsonElement = buildJsonArray {
        add(JsonPrimitive("match"))
        add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("class")) })
        add(JsonArray(classes.map(::JsonPrimitive)))
        add(JsonPrimitive(true))
        add(JsonPrimitive(false))
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * [base]'s labels over the VWorld satellite photo (spec §1.1). [styleUrl] stays the base's, so the vector maps are
 * still downloaded ahead for the labels (§1.3); the satellite tiles themselves are fetched while the video is made.
 */
class SatelliteTileSource(private val base: TileSource, internal val key: String) : TileSource {
    override val styleUrl: String get() = base.styleUrl
    override val attribution: String = VideoMapStyle.ATTRIBUTION

    override suspend fun styleJson(): String {
        val baseJson = try {
            base.styleJson()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return VideoMapStyle.build(baseJson, key)
    }
}
