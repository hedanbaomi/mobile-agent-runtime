// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookDocument

class WorldBookNativeRejectionTest {
    private fun native(entries: JsonArray, original: JsonObject = buildJsonObject {
        put("array", JsonArray(listOf(JsonPrimitive("scalar"))))
    }): String = buildJsonObject {
        put("spec", "mobileagent.worldbook")
        put("version", 2)
        put("format", "NATIVE")
        put("name", "Hostile")
        put("originalJson", original)
        put("entries", entries)
    }.toString()

    @Test
    fun `unknown native versions fail rather than falling back to permissive source detection`() {
        listOf("0", "3", "-1", "null", "true", "1.5").forEach { version ->
            assertThrows(WorldBookImportException::class.java) {
                WorldBookCodec.decode("""{"spec":"mobileagent.worldbook","version":$version,"entries":[]}""")
            }
        }
    }

    @Test
    fun `malformed compact references fail before normalization`() {
        listOf("relative", "/missing", "/array/99", "/array/-1", "/array/00", "/array/0/nope", "/bad~2escape").forEach { pointer ->
            val entries = buildJsonArray {
                add(buildJsonObject {
                    put("values", buildJsonObject { put("uid", "a") })
                    put("references", buildJsonObject { put("content", pointer) })
                })
            }
            assertThrows(WorldBookImportException::class.java) { WorldBookCodec.decode(native(entries)) }
        }
        listOf(JsonPrimitive(0), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap())).forEach { pointer ->
            val entries = buildJsonArray {
                add(buildJsonObject {
                    put("values", buildJsonObject { put("uid", "a") })
                    put("references", buildJsonObject { put("content", pointer) })
                })
            }
            assertThrows(WorldBookImportException::class.java) { WorldBookCodec.decode(native(entries)) }
        }
    }

    @Test
    fun `overlapping unknown or malformed compact fields are rejected`() {
        listOf(
            """{"values":{"uid":"a","content":"x"},"references":{"content":"/array/0"}}""",
            """{"values":{"uid":"a","future":true}}""",
            """{"values":{"uid":"a"},"references":{"future":"/array/0"}}""",
            """{"values":{"uid":"a"},"future":true}""",
            """{"values":[],"references":{}}""",
            """{"values":{"uid":"a"},"references":[]}""",
        ).forEach { entry ->
            assertThrows(WorldBookImportException::class.java) {
                WorldBookCodec.decode(native(JsonArray(listOf(Json.parseToJsonElement(entry)))))
            }
        }
    }

    @Test
    fun `reference amplification is bounded before typed list allocation`() {
        val original = buildJsonObject { put("payload", JsonArray(List(30_000) { JsonPrimitive("x") })) }
        val entries = buildJsonArray {
            repeat(300) {
                add(buildJsonObject {
                    put("values", buildJsonObject { put("uid", it.toString()) })
                    put("references", buildJsonObject { put("triggers", "/payload") })
                })
            }
        }
        assertEquals(WorldBookNoticeCodes.JSON_NODE_LIMIT, assertThrows(WorldBookImportException::class.java) {
            WorldBookCodec.decode(native(entries, original))
        }.code)
    }

    @Test
    fun `compact envelope preserves strict duplicate string source depth and source node limits`() {
        assertEquals(WorldBookNoticeCodes.JSON_DUPLICATE_KEY, assertThrows(WorldBookImportException::class.java) {
            WorldBookCodec.decode("""{"spec":"mobileagent.worldbook","version":2,"entries":[],"entries":[]}""")
        }.code)
        val empty = WorldBookCodec.encodeNative(WorldBookDocument("Small"))
        assertEquals(WorldBookNoticeCodes.JSON_STRING_LIMIT, assertThrows(WorldBookImportException::class.java) {
            WorldBookCodec.decode(empty.replace("Small", "x".repeat(100)), limits = WorldBookImportLimits(maxStringChars = 50))
        }.code)
        val original = buildJsonObject { put("payload", JsonArray(List(101) { JsonPrimitive(it) })) }
        assertEquals(WorldBookNoticeCodes.JSON_NODE_LIMIT, assertThrows(WorldBookImportException::class.java) {
            WorldBookCodec.decode(native(JsonArray(emptyList()), original), limits = WorldBookImportLimits(maxNodes = 100))
        }.code)
        assertEquals(WorldBookNoticeCodes.JSON_DEPTH_LIMIT, assertThrows(WorldBookImportException::class.java) {
            WorldBookCodec.decode(native(JsonArray(emptyList()), original), limits = WorldBookImportLimits(maxDepth = 1))
        }.code)
    }
}
