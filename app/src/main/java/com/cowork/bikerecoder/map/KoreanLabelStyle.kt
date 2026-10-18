package com.cowork.bikerecoder.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Rewrites a MapLibre style so place labels prefer the Korean name (`name:ko`) and fall back to `name`.
 * Only layers whose `layout["text-field"]` references a latin/nonlatin/default name are touched.
 */
object KoreanLabelStyle {

    private val NAME_FIELDS = setOf("name", "name:latin", "name:nonlatin", "name_en")
    private val NAME_TOKEN = Regex("""\{(name|name:latin|name:nonlatin|name_en)\}""")

    private val KOREAN_TEXT_FIELD: JsonElement = JsonArray(
        listOf(
            JsonPrimitive("coalesce"),
            JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("name:ko"))),
            JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("name"))),
        ),
    )

    private val json = Json

    fun apply(styleJson: String): String {
        val root = json.parseToJsonElement(styleJson) as? JsonObject ?: return styleJson
        val layers = root["layers"] as? JsonArray ?: return styleJson
        val newLayers = JsonArray(layers.map(::transformLayer))
        return json.encodeToString(JsonElement.serializer(), JsonObject(root + ("layers" to newLayers)))
    }

    private fun transformLayer(layer: JsonElement): JsonElement {
        val obj = layer as? JsonObject ?: return layer
        val layout = obj["layout"] as? JsonObject ?: return layer
        val textField = layout["text-field"] ?: return layer
        if (!referencesName(textField)) return layer
        return JsonObject(obj + ("layout" to JsonObject(layout + ("text-field" to KOREAN_TEXT_FIELD))))
    }

    private fun referencesName(element: JsonElement): Boolean = when (element) {
        is JsonArray -> isNameGet(element) || element.any(::referencesName)
        is JsonObject -> element.values.any(::referencesName)
        is JsonPrimitive -> element.isString &&
            element.contentOrNull?.let { NAME_TOKEN.containsMatchIn(it) } == true
    }

    /** `["get", "<name field>"]`, optionally with an object argument as third element. */
    private fun isNameGet(array: JsonArray): Boolean {
        if (array.size < 2) return false
        val op = (array[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val key = (array[1] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return op == "get" && key != null && key in NAME_FIELDS
    }
}
