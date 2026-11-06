package org.mdzip.core

import java.math.BigDecimal

/**
 * A JSON value, as held by [MdzManifest] and edited through [MdzEditor.manifest] and
 * [MdzBuilder.manifest]. Objects keep their member order and every member, including ones this
 * library doesn't know, so a rewritten manifest loses nothing (Section 6.3).
 *
 * Equality is JSON equality: objects compare members regardless of order, and numbers compare
 * by value (`3.5` equals `3.50`).
 */
public sealed class JsonValue {
    /** A copy that shares nothing mutable with this value. */
    public abstract fun deepCopy(): JsonValue

    /** Compact JSON text. */
    public fun toJson(): String = StringBuilder().also { JsonWriter.write(this, it, null, 0) }.toString()

    /** JSON text indented by two spaces, with LF line endings. */
    public fun toPrettyJson(): String = StringBuilder().also { JsonWriter.write(this, it, "  ", 0) }.toString()

    override fun toString(): String = toJson()

    public companion object {
        /**
         * Parses strict RFC 8259 JSON. Duplicate member names keep the last value.
         * @throws JsonParseException when the text is not valid JSON.
         */
        @JvmStatic
        public fun parse(text: String): JsonValue = JsonParser(text).parseDocument().value
    }
}

/** A JSON object. Members keep their order; [put] on an existing name replaces it in place. */
public class JsonObject() : JsonValue() {
    private val map = LinkedHashMap<String, JsonValue>()

    /** Member names, in order. */
    public val keys: Set<String> get() = map.keys

    /** Number of members. */
    public val size: Int get() = map.size

    /** True when there are no members. */
    public fun isEmpty(): Boolean = map.isEmpty()

    /** The member called [key], or `null` when there is none. A JSON `null` member is [JsonNull]. */
    public operator fun get(key: String): JsonValue? = map[key]

    /** True when there is a member called [key] (whatever its value). */
    public fun containsKey(key: String): Boolean = map.containsKey(key)

    /** Sets a member, keeping its position when it already exists. Returns this object. */
    public fun put(key: String, value: JsonValue): JsonObject = apply { map[key] = value }

    /** Sets a string member, or removes it when [value] is `null`. Returns this object. */
    public fun put(key: String, value: String?): JsonObject =
        apply { if (value == null) map.remove(key) else map[key] = JsonString(value) }

    /** Removes a member. Returns the removed value or `null`. */
    public fun remove(key: String): JsonValue? = map.remove(key)

    /** The member as a string, or `null` when it is absent or not a string. */
    public fun getString(key: String): String? = (map[key] as? JsonString)?.value

    /** The member as an object, or `null` when it is absent or not an object. */
    public fun getObject(key: String): JsonObject? = map[key] as? JsonObject

    /** The member as an object, creating an empty one first when it is absent or not an object. */
    public fun getOrCreateObject(key: String): JsonObject {
        val existing = map[key]
        if (existing is JsonObject) return existing
        return JsonObject().also { map[key] = it }
    }

    /** The members as a read-only map, in order. */
    public fun asMap(): Map<String, JsonValue> = java.util.Collections.unmodifiableMap(map)

    override fun deepCopy(): JsonObject {
        val copy = JsonObject()
        for ((k, v) in map) copy.map[k] = v.deepCopy()
        return copy
    }

    override fun equals(other: Any?): Boolean = other is JsonObject && other.map == map
    override fun hashCode(): Int = map.hashCode()
}

/** A JSON array. */
public class JsonArray(items: List<JsonValue> = emptyList()) : JsonValue() {
    /** The items; this list can be changed. */
    public val items: MutableList<JsonValue> = ArrayList(items)

    override fun deepCopy(): JsonArray = JsonArray(items.map { it.deepCopy() })
    override fun equals(other: Any?): Boolean = other is JsonArray && other.items == items
    override fun hashCode(): Int = items.hashCode()
}

/** A JSON string. */
public class JsonString(public val value: String) : JsonValue() {
    override fun deepCopy(): JsonString = this
    override fun equals(other: Any?): Boolean = other is JsonString && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/**
 * A JSON number, kept as its original text so that rewriting it changes nothing.
 * @throws JsonParseException if [text] is not a JSON number.
 */
public class JsonNumber(public val text: String) : JsonValue() {
    init {
        if (!NUMBER.matches(text)) throw JsonParseException("Not a JSON number: $text", 0)
    }

    /** The value as a double (may lose precision). */
    public fun toDouble(): Double = text.toDouble()

    /** The exact value, or `null` if the exponent is too large to represent. */
    public fun toBigDecimal(): BigDecimal? = try { BigDecimal(text) } catch (e: NumberFormatException) { null }

    override fun deepCopy(): JsonNumber = this

    override fun equals(other: Any?): Boolean {
        if (other !is JsonNumber) return false
        val a = toBigDecimal()
        val b = other.toBigDecimal()
        return if (a != null && b != null) a.compareTo(b) == 0 else text == other.text
    }

    override fun hashCode(): Int {
        val d = toBigDecimal() ?: return text.hashCode()
        return if (d.signum() == 0) 0 else d.stripTrailingZeros().hashCode()
    }

    public companion object {
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        /** A number from a whole value. */
        @JvmStatic
        public fun of(value: Long): JsonNumber = JsonNumber(value.toString())

        /** A number from a finite double. */
        @JvmStatic
        public fun of(value: Double): JsonNumber {
            require(!value.isNaN() && !value.isInfinite()) { "JSON numbers must be finite" }
            return JsonNumber(BigDecimal.valueOf(value).toString().replace("E+", "E"))
        }
    }
}

/** `true` or `false`. */
public class JsonBoolean private constructor(public val value: Boolean) : JsonValue() {
    override fun deepCopy(): JsonBoolean = this

    public companion object {
        @JvmField public val TRUE: JsonBoolean = JsonBoolean(true)
        @JvmField public val FALSE: JsonBoolean = JsonBoolean(false)

        @JvmStatic
        public fun of(value: Boolean): JsonBoolean = if (value) TRUE else FALSE
    }
}

/** JSON `null`. */
public object JsonNull : JsonValue() {
    override fun deepCopy(): JsonNull = this
}

/** The text is not valid JSON. [offset] is the character position of the problem. */
public class JsonParseException(message: String, public val offset: Int) :
    IllegalArgumentException("$message (at character $offset)")

internal class JsonParseResult(val value: JsonValue, val duplicates: List<String>)

/** A strict RFC 8259 parser that records duplicate member names as JSON Pointers. */
internal class JsonParser(private val s: String) {
    private var i = 0
    private val duplicates = ArrayList<String>()

    fun parseDocument(): JsonParseResult {
        skipWs()
        val v = parseValue("", 0)
        skipWs()
        if (i != s.length) error("Unexpected text after the JSON value")
        return JsonParseResult(v, duplicates)
    }

    private fun error(message: String): Nothing = throw JsonParseException(message, i)

    private fun skipWs() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
        }
    }

    private fun parseValue(pointer: String, depth: Int): JsonValue {
        if (depth > MAX_DEPTH) error("JSON nested too deeply")
        if (i >= s.length) error("Unexpected end of JSON")
        return when (s[i]) {
            '{' -> parseObject(pointer, depth)
            '[' -> parseArray(pointer, depth)
            '"' -> JsonString(parseString())
            't' -> literal("true", JsonBoolean.TRUE)
            'f' -> literal("false", JsonBoolean.FALSE)
            'n' -> literal("null", JsonNull)
            else -> parseNumber()
        }
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!s.startsWith(word, i)) error("Invalid literal")
        i += word.length
        return value
    }

    private fun parseObject(pointer: String, depth: Int): JsonObject {
        i++ // {
        val obj = JsonObject()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return obj }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') error("Expected a member name")
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') error("Expected ':'")
            i++
            skipWs()
            val childPointer = pointer + "/" + key.replace("~", "~0").replace("/", "~1")
            val value = parseValue(childPointer, depth + 1)
            if (obj.containsKey(key)) {
                duplicates.add(childPointer)
                obj.remove(key) // the last occurrence wins, in the position where it appears
            }
            obj.put(key, value)
            skipWs()
            if (i >= s.length) error("Unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return obj }
                else -> error("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(pointer: String, depth: Int): JsonArray {
        i++ // [
        val arr = JsonArray()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return arr }
        while (true) {
            skipWs()
            arr.items.add(parseValue(pointer + "/" + arr.items.size, depth + 1))
            skipWs()
            if (i >= s.length) error("Unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return arr }
                else -> error("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) error("Unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) error("Unterminated escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) error("Invalid \\u escape")
                            var code = 0
                            for (k in 0 until 4) {
                                val d = Character.digit(s[i + k], 16)
                                if (d < 0) error("Invalid \\u escape")
                                code = code * 16 + d
                            }
                            i += 4
                            sb.append(code.toChar())
                        }
                        else -> { i--; error("Invalid escape '\\$e'") }
                    }
                }
                c < ' ' -> { i--; error("Control character in string") }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonNumber {
        val start = i
        if (i < s.length && s[i] == '-') i++
        if (i >= s.length) error("Invalid number")
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < s.length && s[i] in '0'..'9') i++
        } else {
            error("Unexpected character '${s[i]}'")
        }
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || s[i] !in '0'..'9') error("Invalid number")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || s[i] !in '0'..'9') error("Invalid number")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        return JsonNumber(s.substring(start, i))
    }

    private companion object {
        const val MAX_DEPTH = 256
    }
}

internal object JsonWriter {
    fun write(v: JsonValue, out: StringBuilder, indent: String?, level: Int) {
        when (v) {
            is JsonNull -> out.append("null")
            is JsonBoolean -> out.append(if (v.value) "true" else "false")
            is JsonNumber -> out.append(v.text)
            is JsonString -> writeString(v.value, out)
            is JsonArray -> {
                if (v.items.isEmpty()) { out.append("[]"); return }
                out.append('[')
                v.items.forEachIndexed { idx, item ->
                    if (idx > 0) out.append(',')
                    newline(out, indent, level + 1)
                    write(item, out, indent, level + 1)
                }
                newline(out, indent, level)
                out.append(']')
            }
            is JsonObject -> {
                if (v.isEmpty()) { out.append("{}"); return }
                out.append('{')
                var first = true
                for ((k, item) in v.asMap()) {
                    if (!first) out.append(',')
                    first = false
                    newline(out, indent, level + 1)
                    writeString(k, out)
                    out.append(if (indent != null) ": " else ":")
                    write(item, out, indent, level + 1)
                }
                newline(out, indent, level)
                out.append('}')
            }
        }
    }

    private fun newline(out: StringBuilder, indent: String?, level: Int) {
        if (indent == null) return
        out.append('\n')
        repeat(level) { out.append(indent) }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c < ' ' || c == '\u007f' -> hex(c, out)
                Character.isHighSurrogate(c) && k + 1 < s.length && Character.isLowSurrogate(s[k + 1]) -> {
                    out.append(c).append(s[k + 1]); k++
                }
                Character.isSurrogate(c) -> hex(c, out) // a lone surrogate can't be encoded as UTF-8
                else -> out.append(c)
            }
            k++
        }
        out.append('"')
    }

    private fun hex(c: Char, out: StringBuilder) {
        val h = Integer.toHexString(c.code)
        out.append("\\u")
        repeat(4 - h.length) { out.append('0') }
        out.append(h)
    }
}
