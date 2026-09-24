// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ToolArgumentsTest {
    @Test
    fun strictParseAcceptsUsableArguments() {
        val parsed = requireNotNull(ToolArguments.parse("""{"expression":"1+1"}"""))
        assertEquals(JsonPrimitive("1+1"), parsed.json["expression"])
        assertEquals("""{"expression":"1+1"}""", parsed.text)
        assertFalse(parsed.repaired)
        assertEquals(parsed.json, ToolArguments.strict(parsed.text))
    }

    /**
     * A raw C0 control character inside a string literal is illegal JSON.  The
     * repair escapes it and nothing else, so the repaired text re-parses to the
     * very same intended value.
     */
    @Test
    fun rawControlCharactersInsideStringsAreEscapedAndReparseToTheSameValue() {
        val raw = "{\"code\":\"line one\nline two\ttabbed\u0007bell\"}"
        val repaired = requireNotNull(ToolArguments.escapeRawControlCharacters(raw))
        assertEquals("""{"code":"line one\nline two\ttabbed\u0007bell"}""", repaired)

        val intended = "line one\nline two\ttabbed\u0007bell"
        val fromRepaired = requireNotNull(ToolArguments.strict(repaired))
        assertEquals(intended, fromRepaired["code"]!!.jsonPrimitive.content)

        val parsed = requireNotNull(ToolArguments.parse(raw))
        assertEquals(intended, parsed.json["code"]!!.jsonPrimitive.content)
        assertEquals(fromRepaired, parsed.json)
        if (parsed.repaired) assertEquals(repaired, parsed.text)
    }

    @Test
    fun controlCharactersOutsideStringLiteralsAreNeverTouched() {
        // A tab or a newline *between* tokens is legal JSON whitespace: rewriting
        // it would change structure rather than a string value, which the repair
        // must never do.
        val raw = "{\n\t\"a\":\"x\ny\",\n\t\"b\":[1,\t2]\n}"
        val repaired = requireNotNull(ToolArguments.escapeRawControlCharacters(raw))
        assertEquals("{\n\t\"a\":\"x\\ny\",\n\t\"b\":[1,\t2]\n}", repaired)
        // Every structural character survives in the same order: nothing was
        // dropped, added, closed or re-opened.
        val structure = { text: String -> text.filter { it in "{}[],:" } }
        assertEquals(structure(raw), structure(repaired))
        // Exactly one character changed, and it only grew (the escape is longer).
        assertEquals(raw.length + 1, repaired.length)
    }

    @Test
    fun escapedSequencesAreLeftAloneByTheStringScanner() {
        // `\"` must not end the literal, so the newline that follows it is still
        // recognised as an in-string control character.
        val raw = "{\"a\":\"say \\\"hi\\\"\nthere\"}"
        assertEquals("{\"a\":\"say \\\"hi\\\"\\nthere\"}", ToolArguments.escapeRawControlCharacters(raw))
        // Already-escaped control characters are not rewritten a second time.
        assertNull(ToolArguments.escapeRawControlCharacters("""{"a":"line\\nbreak","b":"q \" and \\"}"""))
    }

    @Test
    fun truncatedArgumentsAreNeverCompleted() {
        assertNull(ToolArguments.parse("""{"a":"""))
        assertNull(ToolArguments.strict("""{"a":"""))
        assertNull(ToolArguments.escapeRawControlCharacters("""{"a":"""))
        // Even a control character does not license inventing the missing tail.
        assertNull(ToolArguments.parse("{\"a\":\"x\ny"))
        assertNull(ToolArguments.parse("""{"code":"print(1)"""))
    }

    @Test
    fun unchangedInputReportsNoRepair() {
        assertNull(ToolArguments.escapeRawControlCharacters("""{"a":"plain","b":[1,2]}"""))
        assertNull(ToolArguments.escapeRawControlCharacters(""))
    }

    @Test
    fun whitespaceAndByteOrderMarkAreTolerated() {
        val parsed = requireNotNull(ToolArguments.parse("\uFEFF  {\"a\":1}  \n"))
        assertEquals(JsonPrimitive(1), parsed.json["a"])
        assertFalse(parsed.repaired)
        assertEquals("{\"a\":1}", parsed.text)
    }

    @Test
    fun nonObjectOrEmptyArgumentsAreNotUsable() {
        assertNull(ToolArguments.parse(""))
        assertNull(ToolArguments.parse("   "))
        assertNull(ToolArguments.parse("5"))
        assertNull(ToolArguments.parse("[1,2]"))
        assertNull(ToolArguments.parse("\"text\""))
        assertNull(ToolArguments.parse("null"))
        assertNull(ToolArguments.parse("{bad"))
    }
}
