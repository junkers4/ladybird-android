package io.github.junkers4.ladybird.core.json

/**
 * A small, strict JSON (RFC 8259) reader and writer for the messages exchanged with the engine
 * (requirement ARCH-007). Values map to Kotlin types:
 *
 * object -> Map<String, Any?> (insertion ordered), array -> List<Any?>, string -> String,
 * number -> Long when integral and in range, otherwise Double, true/false -> Boolean, null -> null.
 *
 * The parser rejects trailing commas, comments, leading zeros, lone surrogates in escapes,
 * unescaped control characters and nesting deeper than [MAX_DEPTH].
 */
object Json {
    const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw parser.error("Unexpected trailing characters")
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("Expected a JSON object")

    fun write(value: Any?): String = StringBuilder().also { writeValue(it, value) }.toString()

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "JSON cannot represent $value" }
                out.append(if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString())
            }
            is Float -> writeValue(out, value.toDouble())
            is String -> writeString(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, element) in value) {
                    require(key is String) { "JSON object keys must be strings" }
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    writeValue(out, element)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (element in value) {
                    if (!first) out.append(',')
                    first = false
                    writeValue(out, element)
                }
                out.append(']')
            }
            is Array<*> -> writeValue(out, value.asList())
            else -> throw IllegalArgumentException("Cannot write ${value::class.simpleName} as JSON")
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (character < ' ' || character == ' ' || character == ' ') {
                    out.append("\\u").append(String.format("%04x", character.code))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var position = 0

        fun atEnd() = position >= text.length

        fun error(message: String) = JsonException("$message at offset $position")

        fun skipWhitespace() {
            while (position < text.length) {
                when (text[position]) {
                    ' ', '\t', '\n', '\r' -> position++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw error("Nesting too deep")
            if (atEnd()) throw error("Unexpected end of input")
            return when (val character = text[position]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (character == '-' || character in '0'..'9') readNumber() else throw error("Unexpected character '$character'")
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, position)) throw error("Invalid literal")
            position += literal.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            position++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw error("Expected a string key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> throw error("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            position++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> throw error("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw error("Unterminated string")
                val character = text[position++]
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> readEscape(builder)
                    character < ' ' -> throw error("Unescaped control character")
                    else -> builder.append(character)
                }
            }
        }

        private fun readEscape(builder: StringBuilder) {
            if (atEnd()) throw error("Unterminated escape")
            when (val escaped = text[position++]) {
                '"' -> builder.append('"')
                '\\' -> builder.append('\\')
                '/' -> builder.append('/')
                'b' -> builder.append('\b')
                'f' -> builder.append('\u000C')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                'u' -> {
                    val first = readHex4()
                    if (Character.isHighSurrogate(first)) {
                        if (!text.startsWith("\\u", position)) throw error("Lone high surrogate")
                        position += 2
                        val second = readHex4()
                        if (!Character.isLowSurrogate(second)) throw error("Invalid surrogate pair")
                        builder.append(first).append(second)
                    } else if (Character.isLowSurrogate(first)) {
                        throw error("Lone low surrogate")
                    } else {
                        builder.append(first)
                    }
                }
                else -> throw error("Invalid escape '\\$escaped'")
            }
        }

        private fun readHex4(): Char {
            if (position + 4 > text.length) throw error("Truncated unicode escape")
            var value = 0
            repeat(4) {
                val digit = Character.digit(text[position++], 16)
                if (digit < 0) throw error("Invalid unicode escape")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun readNumber(): Number {
            val start = position
            if (peek() == '-') position++
            when {
                peek() == '0' -> position++
                peek() in '1'..'9' -> while (peek() in '0'..'9') position++
                else -> throw error("Invalid number")
            }
            var integral = true
            if (peek() == '.') {
                integral = false
                position++
                if (peek() !in '0'..'9') throw error("Invalid fraction")
                while (peek() in '0'..'9') position++
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                position++
                if (peek() == '+' || peek() == '-') position++
                if (peek() !in '0'..'9') throw error("Invalid exponent")
                while (peek() in '0'..'9') position++
            }
            val literal = text.substring(start, position)
            if (integral) literal.toLongOrNull()?.let { return it }
            return literal.toDouble()
        }

        private fun peek(): Char = if (position < text.length) text[position] else '\u0000'

        private fun expect(character: Char) {
            if (peek() != character) throw error("Expected '$character'")
            position++
        }
    }
}

class JsonException(message: String) : IllegalArgumentException(message)

/** Typed accessors for the loosely typed values [Json.parse] returns. */
fun Map<String, Any?>.string(key: String): String? = this[key] as? String
fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.let { if (it is Long) it else null }
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>
