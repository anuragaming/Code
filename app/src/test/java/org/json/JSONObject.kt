package org.json

import kotlin.collections.LinkedHashMap

class JSONException(message: String) : Exception(message)

class JSONObject {
    private val map = LinkedHashMap<String, Any?>()

    constructor()

    constructor(json: String) {
        val parser = JsonParser(json)
        val parsed = parser.parseValue()
        if (parsed !is JSONObject) {
            throw JSONException("Value is not a JSONObject")
        }
        map.putAll(parsed.map)
    }

    fun put(name: String, value: Any?): JSONObject {
        map[name] = wrap(value)
        return this
    }

    fun put(name: String, value: Boolean): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Double): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Int): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Long): JSONObject {
        map[name] = value
        return this
    }

    fun has(name: String): Boolean = map.containsKey(name)

    fun isNull(name: String): Boolean = !map.containsKey(name) || map[name] == null || map[name] === NULL

    fun opt(name: String): Any? = map[name]

    fun get(name: String): Any {
        return map[name] ?: throw JSONException("No value for $name")
    }

    fun getString(name: String): String = get(name).toString()

    @JvmOverloads
    fun optString(name: String, fallback: String = ""): String {
        val v = map[name]
        return if (v == null || v === NULL) fallback else v.toString()
    }

    fun getInt(name: String): Int = (get(name) as Number).toInt()

    @JvmOverloads
    fun optInt(name: String, fallback: Int = 0): Int {
        val v = map[name]
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: fallback
            else -> fallback
        }
    }

    @JvmOverloads
    fun optLong(name: String, fallback: Long = 0L): Long {
        val v = map[name]
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: fallback
            else -> fallback
        }
    }

    @JvmOverloads
    fun optDouble(name: String, fallback: Double = Double.NaN): Double {
        val v = map[name]
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: fallback
            else -> fallback
        }
    }

    @JvmOverloads
    fun optBoolean(name: String, fallback: Boolean = false): Boolean {
        val v = map[name]
        return when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> fallback
        }
    }

    fun getJSONObject(name: String): JSONObject {
        val v = get(name)
        return v as? JSONObject ?: throw JSONException("Value for $name is not JSONObject")
    }

    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject

    fun getJSONArray(name: String): JSONArray {
        val v = get(name)
        return v as? JSONArray ?: throw JSONException("Value for $name is not JSONArray")
    }

    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray

    fun keys(): Iterator<String> = map.keys.iterator()

    override fun toString(): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(k)).append("\":")
            sb.append(valueToString(v))
        }
        sb.append('}')
        return sb.toString()
    }

    companion object {
        val NULL: Any = Any()

        internal fun wrap(value: Any?): Any? = when (value) {
            null -> NULL
            is JSONObject, is JSONArray, is Boolean, is Number, is String -> value
            else -> value.toString()
        }

        internal fun escape(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        internal fun valueToString(v: Any?): String = when (v) {
            null, NULL -> "null"
            is Boolean, is Number, is JSONObject, is JSONArray -> v.toString()
            else -> "\"${escape(v.toString())}\""
        }
    }
}

class JSONArray {
    private val list = ArrayList<Any?>()

    constructor()

    constructor(json: String) {
        val parser = JsonParser(json)
        val parsed = parser.parseValue()
        if (parsed !is JSONArray) {
            throw JSONException("Value is not a JSONArray")
        }
        list.addAll(parsed.list)
    }

    fun length(): Int = list.size

    fun put(value: Any?): JSONArray {
        list.add(JSONObject.wrap(value))
        return this
    }

    fun get(index: Int): Any {
        if (index < 0 || index >= list.size) throw JSONException("Index $index out of bounds")
        return list[index] ?: throw JSONException("Null at $index")
    }

    fun getString(index: Int): String = get(index).toString()

    @JvmOverloads
    fun optString(index: Int, fallback: String = ""): String {
        if (index < 0 || index >= list.size) return fallback
        val v = list[index]
        return if (v == null || v === JSONObject.NULL) fallback else v.toString()
    }

    fun getJSONObject(index: Int): JSONObject {
        val v = get(index)
        return v as? JSONObject ?: throw JSONException("Value at $index is not JSONObject")
    }

    fun optJSONObject(index: Int): JSONObject? {
        if (index < 0 || index >= list.size) return null
        return list[index] as? JSONObject
    }

    override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in list.indices) {
            if (i > 0) sb.append(',')
            sb.append(JSONObject.valueToString(list[i]))
        }
        sb.append(']')
        return sb.toString()
    }
}

internal class JsonParser(private val src: String) {
    private var pos = 0

    fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= src.length) throw JSONException("Unexpected end of JSON")
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> throw JSONException("Unexpected character '$c' at position $pos")
        }
    }

    private fun parseObject(): JSONObject {
        val obj = JSONObject()
        pos++ // '{'
        skipWhitespace()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return obj
        }
        while (pos < src.length) {
            skipWhitespace()
            if (src[pos] != '"') throw JSONException("Expected key string at $pos")
            val key = parseString()
            skipWhitespace()
            if (pos >= src.length || src[pos] != ':') throw JSONException("Expected ':' at $pos")
            pos++
            val value = parseValue()
            obj.put(key, value)
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == '}') {
                pos++
                return obj
            } else {
                throw JSONException("Expected ',' or '}' at $pos")
            }
        }
        throw JSONException("Unterminated object")
    }

    private fun parseArray(): JSONArray {
        val arr = JSONArray()
        pos++ // '['
        skipWhitespace()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return arr
        }
        while (pos < src.length) {
            val value = parseValue()
            arr.put(value)
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == ']') {
                pos++
                return arr
            } else {
                throw JSONException("Expected ',' or ']' at $pos")
            }
        }
        throw JSONException("Unterminated array")
    }

    private fun parseString(): String {
        pos++ // '"'
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (pos >= src.length) throw JSONException("Unterminated escape")
                when (val esc = src[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = src.substring(pos, pos + 4)
                        sb.append(hex.toInt(16).toChar())
                        pos += 4
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        throw JSONException("Unterminated string")
    }

    private fun parseBoolean(): Boolean {
        return if (src.startsWith("true", pos)) {
            pos += 4
            true
        } else if (src.startsWith("false", pos)) {
            pos += 5
            false
        } else {
            throw JSONException("Invalid boolean at $pos")
        }
    }

    private fun parseNull(): Any {
        if (src.startsWith("null", pos)) {
            pos += 4
            return JSONObject.NULL
        }
        throw JSONException("Invalid null at $pos")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        var isDouble = false
        if (pos < src.length && src[pos] == '.') {
            isDouble = true
            pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            isDouble = true
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val numStr = src.substring(start, pos)
        return if (isDouble) numStr.toDouble() else (numStr.toLongOrNull() ?: numStr.toDouble())
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }
}
