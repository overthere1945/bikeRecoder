package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException

/** 영상 지도 스타일: 브이월드 위성 바탕 + 지명·큰 길 이름만(스펙 §1.1). */
class VideoMapStyleTest {

    /** A cut-down liberty style (already Korean-label transformed): its vector source, glyphs and fonts. */
    private val base = """
        {"version":8,
         "sources":{
           "ne2_shaded":{"type":"raster","url":"https://tiles.openfreemap.org/natural_earth/ne2sr"},
           "openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"}},
         "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
         "layers":[
           {"id":"background","type":"background","paint":{"background-color":"#f8f4f0"}},
           {"id":"poi","type":"symbol","source":"openmaptiles","source-layer":"poi",
            "layout":{"text-font":["Noto Sans Italic"],"text-field":["coalesce",["get","name:ko"],["get","name"]]}},
           {"id":"label_town","type":"symbol","source":"openmaptiles","source-layer":"place",
            "layout":{"text-font":["Noto Sans Regular"],"text-size":14}},
           {"id":"label_capital","type":"symbol","source":"openmaptiles","source-layer":"place",
            "layout":{"text-font":["Noto Sans Bold"],"text-size":20}}
         ]}
    """.trimIndent()

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun layers(style: JsonObject) = style["layers"]!!.jsonArray.map { it.jsonObject }
    private fun layer(style: JsonObject, id: String) = layers(style).single { it["id"]!!.jsonPrimitive.content == id }
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun satelliteSourceCarriesTheKey() {
        val style = parse(VideoMapStyle.build(base, "TESTKEY"))

        val source = style["sources"]!!.jsonObject["vworld"]!!.jsonObject
        assertEquals("raster", source["type"]!!.jsonPrimitive.content)
        assertEquals(
            "https://api.vworld.kr/req/wmts/1.0.0/TESTKEY/Satellite/{z}/{y}/{x}.jpeg",
            source["tiles"]!!.jsonArray[0].jsonPrimitive.content,
        )
        assertEquals(256, source["tileSize"]!!.jsonPrimitive.int)
        assertEquals(6, source["minzoom"]!!.jsonPrimitive.int)
        assertEquals(19, source["maxzoom"]!!.jsonPrimitive.int)
        // Background, then the photo, then the road names under the place names.
        assertEquals(listOf("background", "vworld", "v-road", "v-place"), layers(style).map { it["id"]!!.jsonPrimitive.content })
        assertEquals(json("\"#1A1F1A\""), layer(style, "background")["paint"]!!.jsonObject["background-color"])
        assertEquals("vworld", layer(style, "vworld")["source"]!!.jsonPrimitive.content)
        assertEquals("raster", layer(style, "vworld")["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun onlyPlaceAndBigRoadLabels() {
        val style = parse(VideoMapStyle.build(base, "TESTKEY"))

        val symbols = layers(style).filter { it["type"]!!.jsonPrimitive.content == "symbol" }
        assertEquals(listOf("v-road", "v-place"), symbols.map { it["id"]!!.jsonPrimitive.content })

        val place = layer(style, "v-place")
        assertEquals("openmaptiles", place["source"]!!.jsonPrimitive.content)
        assertEquals("place", place["source-layer"]!!.jsonPrimitive.content)
        assertEquals(
            json("""["match",["get","class"],["city","town","village","suburb","quarter","neighbourhood"],true,false]"""),
            place["filter"],
        )
        val road = layer(style, "v-road")
        assertEquals("openmaptiles", road["source"]!!.jsonPrimitive.content)
        assertEquals("transportation_name", road["source-layer"]!!.jsonPrimitive.content)
        assertEquals(
            json("""["match",["get","class"],["motorway","trunk","primary","secondary"],true,false]"""),
            road["filter"],
        )
        // Korean name first (as the app's own map).
        val koreanName = json("""["coalesce",["get","name:ko"],["get","name"]]""")
        assertEquals(koreanName, place["layout"]!!.jsonObject["text-field"])
        assertEquals(koreanName, road["layout"]!!.jsonObject["text-field"])
    }

    @Test
    fun labelPaint() {
        val style = parse(VideoMapStyle.build(base, "TESTKEY"))

        for (id in listOf("v-place", "v-road")) {
            val paint = layer(style, id)["paint"]!!.jsonObject
            assertEquals(json("\"#FFFFFF\""), paint["text-color"], id)
            assertEquals(json("\"rgba(0,0,0,0.75)\""), paint["text-halo-color"], id)
            assertEquals(1.6, paint["text-halo-width"]!!.jsonPrimitive.content.toDouble(), id)
        }
    }

    @Test
    fun textSize() {
        val style = parse(VideoMapStyle.build(base, "TESTKEY"))

        assertEquals(
            json("""["interpolate",["linear"],["zoom"],12,18,17,26]"""),
            layer(style, "v-place")["layout"]!!.jsonObject["text-size"],
        )
        assertEquals(20, layer(style, "v-road")["layout"]!!.jsonObject["text-size"]!!.jsonPrimitive.int)
    }

    @Test
    fun fontsAndGlyphsFromBase() {
        val style = parse(VideoMapStyle.build(base, "TESTKEY"))

        assertEquals(json("""["Noto Sans Bold"]"""), layer(style, "v-place")["layout"]!!.jsonObject["text-font"])
        assertEquals(json("""["Noto Sans Bold"]"""), layer(style, "v-road")["layout"]!!.jsonObject["text-font"])
        assertEquals("https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf", style["glyphs"]!!.jsonPrimitive.content)
        // The base's vector source as it is; the other base sources are not carried.
        val sources = style["sources"]!!.jsonObject
        assertEquals(parse(base)["sources"]!!.jsonObject["openmaptiles"], sources["openmaptiles"])
        assertEquals(setOf("vworld", "openmaptiles"), sources.keys)

        // No Bold variant in the base: its first font.
        val noBold = base.replace("Noto Sans Bold", "Noto Sans Regular")
        val plain = parse(VideoMapStyle.build(noBold, "TESTKEY"))
        assertEquals(json("""["Noto Sans Italic"]"""), layer(plain, "v-place")["layout"]!!.jsonObject["text-font"])
    }

    @Test
    fun withoutBaseStillSatellite() {
        for (missing in listOf(null, "not json", """{"version":8,"sources":{},"layers":[]}""")) {
            val style = parse(VideoMapStyle.build(missing, "TESTKEY"))

            assertEquals(8, style["version"]!!.jsonPrimitive.int)
            assertEquals(listOf("background", "vworld"), layers(style).map { it["id"]!!.jsonPrimitive.content }, "$missing")
            assertEquals(setOf("vworld"), style["sources"]!!.jsonObject.keys)
            assertNull(style["glyphs"])
        }
    }

    @Test
    fun satelliteTileSourceWrapsTheBase() = runTest {
        val ok = SatelliteTileSource(FixedSource(base), "TESTKEY")
        assertEquals("© 브이월드 · OpenStreetMap", ok.attribution)
        assertEquals("https://example.test/style", ok.styleUrl)
        assertEquals(VideoMapStyle.build(base, "TESTKEY"), ok.styleJson())

        // The base style unreachable: still the satellite photo.
        val failing = SatelliteTileSource(FixedSource(null), "TESTKEY")
        assertEquals(VideoMapStyle.build(null, "TESTKEY"), failing.styleJson())
    }

    private class FixedSource(private val json: String?) : TileSource {
        override val styleUrl = "https://example.test/style"
        override val attribution = "base"
        override suspend fun styleJson(): String = json ?: throw IOException("offline")
    }
}
