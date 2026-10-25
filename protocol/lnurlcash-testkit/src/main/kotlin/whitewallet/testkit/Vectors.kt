package whitewallet.testkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Loads the vendored test vectors. See protocol/SPEC.md for the pinned commits.
 *
 * - `spec/`: the LUD-25/26 vectors published in the PR itself (authoritative).
 * - `conformance/`: lnurlcash-conformance vectors (MIT); some files describe superseded drafts.
 */
public object Vectors {
    public fun spec(name: String): JsonObject = load("vectors/spec/$name")
    public fun conformance(name: String): JsonObject = load("vectors/conformance/$name.json")

    private fun load(path: String): JsonObject {
        val stream = Vectors::class.java.classLoader.getResourceAsStream(path)
            ?: error("missing test vector resource $path")
        return Json.parseToJsonElement(stream.reader().readText()).jsonObject
    }
}

public fun JsonElement.obj(key: String): JsonObject = jsonObject.getValue(key).jsonObject
public fun JsonElement.arr(key: String): JsonArray = jsonObject.getValue(key).jsonArray
public fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content
public fun JsonElement.strOrNull(key: String): String? =
    (jsonObject[key] as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }?.content
public fun JsonElement.long(key: String): Long = jsonObject.getValue(key).jsonPrimitive.long
public fun JsonElement.int(key: String): Int = long(key).toInt()
public fun JsonElement.objects(key: String): List<JsonObject> = arr(key).map { it.jsonObject }
