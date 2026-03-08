package jp.espresso3389.methings.service.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversion utilities between [Map] (Core API surface) and [JSONObject] (HTTP layer).
 *
 * Key rule: [UByteArray] / [ByteArray] values in a Map are encoded to base64 strings
 * when converting to JSONObject (for HTTP responses), whereas QuickJS-kt converts them
 * to Uint8Array natively.
 */
object CoreApiUtils {

    // ---- Map → JSONObject (for HTTP responses) --------------------------------

    /** Convert a Core API result [Map] to a [JSONObject] suitable for HTTP responses.
     *  [ByteArray] and [UByteArray] values are base64-encoded with a `_b64` suffix. */
    fun toJsonResponse(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) {
            if (key.startsWith("_")) continue // strip internal keys like _http_status
            obj.put(key, convertToJson(value))
        }
        return obj
    }

    // ---- JSONObject → Map (for incoming HTTP payloads) -------------------------

    /** Convert an incoming [JSONObject] payload to a [Map]. */
    fun fromJsonPayload(obj: JSONObject): Map<String, Any?> {
        return jsonObjectToMap(obj)
    }

    /** Parse a JSON string to a [Map]. */
    fun jsonStringToMap(json: String): Map<String, Any?> {
        return try {
            jsonObjectToMap(JSONObject(json))
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---- Map helper extensions --------------------------------------------------

    /** Convenience: get a String value or [default]. */
    fun Map<String, Any?>.optString(key: String, default: String = ""): String {
        val v = get(key) ?: return default
        return v.toString()
    }

    /** Convenience: get an Int value or [default]. */
    fun Map<String, Any?>.optInt(key: String, default: Int = 0): Int {
        return when (val v = get(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    /** Convenience: get a Long value or [default]. */
    fun Map<String, Any?>.optLong(key: String, default: Long = 0L): Long {
        return when (val v = get(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: default
            else -> default
        }
    }

    /** Convenience: get a Boolean value or [default]. */
    fun Map<String, Any?>.optBoolean(key: String, default: Boolean = false): Boolean {
        return when (val v = get(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true)
            else -> default
        }
    }

    /** Check if the map contains a non-null value for [key]. */
    fun Map<String, Any?>.has(key: String): Boolean = containsKey(key) && get(key) != null

    /** Get a sub-map or null. */
    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any?>.optMap(key: String): Map<String, Any?>? {
        return get(key) as? Map<String, Any?>
    }

    // ---- Error / OK helpers ----------------------------------------------------

    fun ok(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        return mapOf("status" to "ok") + pairs
    }

    fun error(code: String, httpStatus: Int = 400, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        return mapOf("error" to code, "_http_status" to httpStatus) + extra
    }

    /** HTTP status code embedded in a Core API result (stripped before JSON serialization). */
    fun httpStatusOf(map: Map<String, Any?>): Int {
        return (map["_http_status"] as? Number)?.toInt() ?: if (map.containsKey("error")) 400 else 200
    }

    // ---- Internal helpers -------------------------------------------------------

    private fun convertToJson(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val obj = JSONObject()
                @Suppress("UNCHECKED_CAST")
                for ((k, v) in value as Map<String, Any?>) {
                    if (k.startsWith("_")) continue
                    obj.put(k, convertToJson(v))
                }
                obj
            }
            is List<*> -> {
                val arr = JSONArray()
                for (item in value) arr.put(convertToJson(item))
                arr
            }
            is Array<*> -> {
                val arr = JSONArray()
                for (item in value) arr.put(convertToJson(item))
                arr
            }
            is ByteArray -> Base64.encodeToString(value, Base64.NO_WRAP)
            is UByteArray -> Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
            is Boolean, is Number, is String -> value
            else -> value.toString()
        }
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = jsonValueToKotlin(obj.get(key))
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until arr.length()) {
            list.add(jsonValueToKotlin(arr.get(i)))
        }
        return list
    }

    private fun jsonValueToKotlin(value: Any?): Any? {
        return when (value) {
            JSONObject.NULL, null -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> jsonArrayToList(value)
            else -> value // String, Number, Boolean
        }
    }
}
