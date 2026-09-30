package io.github.junkers4.ladybird.core.json

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

@Requirement("ARCH-007")
class JsonTest {
    @Test
    fun parsesAllValueKinds() {
        val value = Json.parseObject("""{"a":1,"b":-2.5,"c":"x\ny","d":true,"e":null,"f":[1,{"g":false}],"h":1e3}""")
        assertEquals(1L, value["a"])
        assertEquals(-2.5, value["b"])
        assertEquals("x\ny", value["c"])
        assertEquals(true, value["d"])
        assertNull(value["e"])
        assertEquals(listOf(1L, mapOf("g" to false)), value["f"])
        assertEquals(1000.0, value["h"])
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "h"), value.keys.toList())
    }

    @Test
    fun decodesUnicodeEscapesAndSurrogatePairs() {
        assertEquals("é😀", Json.parse(""""\u00e9\ud83d\ude00""""))
    }

    @Test
    fun rejectsMalformedInput() {
        val invalid = listOf(
            "", "{", "[1,]", "{\"a\":1,}", "01", "1.", "-", "tru", "\"\\x\"", "\"a", "{\"a\" 1}", "[1 2]",
            "\"\\ud83d\"", "\"\\ude00\"", "\"line\nbreak\"", "// comment\n1", "{} extra", "NaN",
        )
        for (text in invalid)
            assertThrows("should reject: $text", JsonException::class.java) { Json.parse(text) }
    }

    @Test
    fun limitsNestingDepth() {
        val deep = "[".repeat(Json.MAX_DEPTH + 2) + "]".repeat(Json.MAX_DEPTH + 2)
        assertThrows(JsonException::class.java) { Json.parse(deep) }
        val ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        Json.parse(ok)
    }

    @Test
    fun writesAndRoundTrips() {
        val value = linkedMapOf(
            "s" to "quote\" backslash\\ tab\t ctrl\u0001 ls\u2028",
            "n" to 42,
            "d" to 1.5,
            "b" to false,
            "z" to null,
            "l" to listOf(1, "two", mapOf("three" to 3)),
        )
        val text = Json.write(value)
        assertEquals("""{"s":"quote\" backslash\\ tab\t ctrl\u0001 ls\u2028","n":42,"d":1.5,"b":false,"z":null,"l":[1,"two",{"three":3}]}""", text)
        val parsed = Json.parseObject(text)
        assertEquals(value["s"], parsed["s"])
        assertEquals(42L, parsed["n"])
    }

    @Test
    fun largeIntegersBecomeDoubles() {
        assertEquals(9.223372036854776E18 * 10, Json.parse("92233720368547758070"))
    }

    @Test
    fun typedAccessors() {
        val value = Json.parseObject("""{"s":"x","b":true,"n":3,"o":{"k":1},"l":[]}""")
        assertEquals("x", value.string("s"))
        assertEquals(true, value.boolean("b"))
        assertEquals(3L, value.long("n"))
        assertEquals(1L, value.obj("o")?.long("k"))
        assertEquals(emptyList<Any?>(), value.list("l"))
        assertNull(value.string("n"))
    }
}
