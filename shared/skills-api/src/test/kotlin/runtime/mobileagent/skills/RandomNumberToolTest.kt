// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlin.random.Random
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RandomNumberToolTest {
    private fun broker(random: Random = Random(42)) = ToolBroker(
        emptySet(), ToolContext(search = { _, _, _ -> error("No I/O") }, readDocument = { _, _ -> error("No I/O") }),
        random = random,
    )
    private fun call(id: String, min: Int, max: Int) =
        ToolCall(id, "random_number", """{"min":$min,"max":$max}""")
    private fun value(result: ToolResult): Int {
        assertTrue(result is ToolResult.Value, result.toString())
        return Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject.getValue("value").jsonPrimitive.int
    }

    @Test fun schemaAndRuntimeAcceptTheSameBoundsWithoutAResourceGrant() {
        val spec = BuiltinTools.byName.getValue("random_number")
        assertEquals("", spec.capability)
        assertFalse(spec.sideEffect)
        val schema = Json.parseToJsonElement(spec.parametersJson).jsonObject
        assertEquals(setOf("min", "max"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        for (property in schema.getValue("properties").jsonObject.values) {
            assertEquals(Int.MIN_VALUE, property.jsonObject.getValue("minimum").jsonPrimitive.int)
            assertEquals(Int.MAX_VALUE, property.jsonObject.getValue("maximum").jsonPrimitive.int)
        }
        val tools = broker()
        for (bound in listOf(Int.MIN_VALUE, -20, 0, 6, Int.MAX_VALUE)) {
            val result = tools.invoke(call("same-$bound", bound, bound))
            assertEquals(bound, value(result))
            assertEquals("""{"min":$bound,"max":$bound,"value":$bound}""", (result as ToolResult.Value).json)
        }
    }

    @Test fun inclusiveSamplingHandlesDiceNegativeAndFullWidthRanges() {
        val tools = broker()
        for ((min, max) in listOf(1 to 6, 1 to 20, -6 to -1, -3 to 3,
            Int.MIN_VALUE to Int.MIN_VALUE + 1, Int.MAX_VALUE - 1 to Int.MAX_VALUE, Int.MIN_VALUE to Int.MAX_VALUE)) {
            val drawn = (0 until 512).map { index -> value(tools.invoke(call("$min-$max-$index", min, max))) }
            assertTrue(drawn.all { it in min..max })
            // Fixed seed keeps this endpoint regression deterministic, not a probabilistic fairness test.
            if (max.toLong() - min < 20) {
                assertTrue(min in drawn, "Lower endpoint missing: $min")
                assertTrue(max in drawn, "Upper endpoint missing: $max")
            }
        }
    }

    @Test fun invalidArgumentsNeverConsumeRandomness() {
        val noRandom = object : Random() { override fun nextBits(bitCount: Int): Int = error("Must not draw") }
        val tools = broker(noRandom)
        val invalid = listOf("{}", """{"min":1}""", """{"max":6}""", """{"min":7,"max":6}""",
            """{"min":"1","max":6}""", """{"min":1.5,"max":6}""", """{"min":null,"max":6}""",
            """{"min":true,"max":6}""", """{"min":[],"max":6}""", """{"min":{},"max":6}""",
            """{"min":-2147483649,"max":6}""", """{"min":1,"max":2147483648}""",
            """{"min":1,"max":6,"seed":1}""", """{"min":1,"max":""", "[]")
        invalid.forEachIndexed { index, arguments ->
            val result = tools.invoke(ToolCall("invalid-$index", "random_number", arguments))
            assertTrue(result is ToolResult.Invalid, "$arguments -> $result")
            assertFalse((result as ToolResult.Invalid).reason.contains("Must not draw"))
        }
        assertEquals(6, value(tools.invoke(call("constant", 6, 6))))
    }

    @Test fun fullWidthDrawPreservesBothIntEndpointsWithoutOverflow() {
        for (upper in listOf(false, true)) {
            val random = object : Random() {
                override fun nextBits(bitCount: Int): Int = error("Bounded draw only")
                override fun nextLong(from: Long, until: Long): Long {
                    assertEquals(-2147483648L, from)
                    assertEquals(2147483648L, until)
                    return if (upper) until - 1 else from
                }
            }
            assertEquals(if (upper) Int.MAX_VALUE else Int.MIN_VALUE,
                value(broker(random).invoke(call("full", Int.MIN_VALUE, Int.MAX_VALUE))))
        }
    }

    @Test fun repeatedInvocationUsesItsOriginalDrawAndNewInvocationDrawsAgain() {
        var draws = 0
        val random = object : Random() {
            override fun nextBits(bitCount: Int): Int = error("Bounded draw only")
            override fun nextLong(from: Long, until: Long): Long = from + draws++
        }
        val tools = broker(random)
        val first = call("die-1", 1, 20)
        assertEquals(1, value(tools.invoke(first)))
        assertTrue(tools.authorizeReplay(first))
        repeat(10) { assertEquals(1, value(tools.invoke(first))) }
        assertEquals(1, draws)
        assertTrue(tools.invoke(first.copy(argumentsJson = """{"min":1,"max":6}""")) is ToolResult.Invalid)
        assertEquals(2, value(tools.invoke(call("die-2", 1, 20))))
        assertEquals(2, draws)
    }
}
