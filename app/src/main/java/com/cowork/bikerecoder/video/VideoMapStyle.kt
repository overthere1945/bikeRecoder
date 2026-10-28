package com.cowork.bikerecoder.video

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
    private val PLACE_CLASSES = listOf("city", "town", "village", "suburb", "quarter", "neighbourhood")
    private val ROAD_CLASSES = listOf("motorway", "trunk", "primary", "secondary")

    /** Place names 1.6× the base sizes, fixed: 18 at zoom 12 to 26 at zoom 17. */
    private val PLACE_SIZE = Json.parseToJsonElement("""["interpolate",["linear"],["zoom"],12,18,17,26]""")
    private const val ROAD_SIZE = 20
    private val KOREAN_NAME = Json.parseToJsonElement("""["coalesce",["get","name:ko"],["get","name"]]""")

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
                    add(labelLayer("v-road", labels, "transportation_name", ROAD_CLASSES, JsonPrimitive(ROAD_SIZE), line = true))
                    add(labelLayer("v-place", labels, "place", PLACE_CLASSES, PLACE_SIZE, line = false))
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
        classes: List<String>,
        size: JsonElement,
        line: Boolean,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "symbol")
        put("source", labels.sourceId)
        put("source-layer", sourceLayer)
        put("filter", buildJsonArray {
            add(JsonPrimitive("match"))
            add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("class")) })
            add(JsonArray(classes.map(::JsonPrimitive)))
            add(JsonPrimitive(true))
            add(JsonPrimitive(false))
        })
        put("layout", buildJsonObject {
            put("text-field", KOREAN_NAME)
            putJsonArray("text-font") { add(JsonPrimitive(labels.font)) }
            put("text-size", size)
            if (line) put("symbol-placement", "line")
        })
        put("paint", buildJsonObject {
            put("text-color", "#FFFFFF")
            put("text-halo-color", "rgba(0,0,0,0.75)")
            put("text-halo-width", 1.6)
        })
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * [base]'s labels over the VWorld satellite photo (spec §1.1). [styleUrl] stays the base's, so the vector maps are
 * still downloaded ahead for the labels (§1.3); the satellite tiles themselves are fetched while the video is made.
 */
class SatelliteTileSource(private val base: TileSource, private val key: String) : TileSource {
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
