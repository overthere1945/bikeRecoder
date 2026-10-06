package com.cowork.bikerecoder.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KoreanLabelStyleTest {

    private val koreanField: JsonElement = Json.parseToJsonElement("""["coalesce",["get","name:ko"],["get","name"]]""")

    private fun textField(styleJson: String, layerIndex: Int = 0): JsonElement =
        Json.parseToJsonElement(styleJson)
            .jsonObject.getValue("layers").jsonArray[layerIndex]
            .jsonObject.getValue("layout").jsonObject.getValue("text-field")

    @Test
    fun `replaces name based text field`() {
        val input = """{"layers":[{"id":"place","layout":{"text-field":["get","name:latin"]}}]}"""
        assertEquals(koreanField, textField(KoreanLabelStyle.apply(input)))
    }

    @Test
    fun `keeps ref text field`() {
        val input = """{"layers":[{"id":"road","layout":{"text-field":"{ref}"}},{"id":"road2","layout":{"text-field":["get","ref"]}}]}"""
        val output = KoreanLabelStyle.apply(input)
        assertEquals(JsonPrimitive("{ref}"), textField(output, 0))
        assertEquals(Json.parseToJsonElement("""["get","ref"]"""), textField(output, 1))
    }

    @Test
    fun `leaves layers without layout`() {
        val input = """{"version":8,"layers":[{"id":"bg","type":"background"},{"id":"line","type":"line","layout":{"line-cap":"round"}}]}"""
        assertEquals(Json.parseToJsonElement(input), Json.parseToJsonElement(KoreanLabelStyle.apply(input)))
    }

    @Test
    fun `replaces nested expression referencing name fields`() {
        val input = """{"layers":[{"id":"place","layout":{"text-field":["concat",["get","name:latin"],"\n",["get","name:nonlatin"]],"text-size":12}}]}"""
        val output = KoreanLabelStyle.apply(input)
        assertEquals(koreanField, textField(output))
        val layout = Json.parseToJsonElement(output).jsonObject.getValue("layers").jsonArray[0]
            .jsonObject.getValue("layout").jsonObject
        assertEquals(JsonPrimitive(12), layout.getValue("text-size"))
    }

    @Test
    fun `replaces legacy token strings`() {
        val input = """{"layers":[{"id":"a","layout":{"text-field":"{name:latin}"}},{"id":"b","layout":{"text-field":"{name}"}}]}"""
        val output = KoreanLabelStyle.apply(input)
        assertEquals(koreanField, textField(output, 0))
        assertEquals(koreanField, textField(output, 1))
    }

    @Test
    fun `preserves other top level keys`() {
        val input = """{"version":8,"name":"liberty","sources":{"x":{"type":"vector"}},"layers":[]}"""
        val output = Json.parseToJsonElement(KoreanLabelStyle.apply(input)) as JsonObject
        assertEquals(JsonPrimitive("liberty"), output["name"])
        assertEquals(JsonArray(emptyList()), output["layers"])
    }
}
