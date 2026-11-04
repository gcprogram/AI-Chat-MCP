package app.aichatmcp.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** JSON wie bei den Python-Dateien: Schlüssel unbekannter Felder werden ignoriert, Ausgabe lesbar eingerückt. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "    "
}

/** Gewöhnliche Kotlin-Werte (String, Zahl, Boolean, Liste, Map) <-> JSON. */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

fun JsonElement.toNative(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonArray -> map { it.toNative() }
    is JsonObject -> mapValues { it.value.toNative() }
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
