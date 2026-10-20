// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookFormat

class WorldBookNativeCompactTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `legacy documents canonicalize to compact for all six source formats`() {
        val source = """{"name":"Book","unknown":{"kept":[true,null,1.00]},"entries":{"7":{"uid":7,"content":"${"long original content ".repeat(15)}","key":["a"],"future":{"keep":1}}}}"""
        val imported = WorldBookCodec.decode(source).document
        WorldBookFormat.entries.forEach { format ->
            val document = imported.copy(format = format)
            val legacy = WorldBookCodec.encodeLegacyNative(document)
            val legacyDocument = WorldBookCodec.decode(legacy).document
            val compact = WorldBookCodec.encodeNative(legacyDocument)
            assertEquals(2, parse(compact).getValue("version").jsonPrimitive.int)
            assertEquals(legacyDocument, WorldBookCodec.decode(compact).document)
            assertEquals(compact, WorldBookCodec.encodeNative(WorldBookCodec.decode(compact).document))
            assertTrue(compact.length < legacy.length)
        }
    }

    @Test
    fun `source formats including complete cards retain wrappers and source entry identity`() {
        val cc = """{"name":"C","entries":[{"id":8,"keys":["x"],"content":"${"body ".repeat(60)}","extensions":{"unknown":[false,null]}}]}"""
        val sources = listOf(
            cc,
            """{"spec":"lorebook_v3","data":$cc,"outer":{"a":1}}""",
            """{"character_book":$cc,"unknown":true}""",
            """{"spec":"chara_card_v2","data":{"name":"Card","description":"private card text","character_book":$cc},"unknown":[1,2]}""",
            """{"spec":"chara_card_v3","data":{"name":"Card","character_book":$cc},"unknown":{"keep":true}}""",
        )
        sources.forEach { source ->
            val document = WorldBookCodec.decode(source).document
            val compact = WorldBookCodec.encodeNative(document)
            assertTrue(parse(compact).getValue("entries").jsonArray.single().jsonObject.containsKey("references"))
            assertEquals(document, WorldBookCodec.decode(compact).document)
            assertEquals(
                parse(WorldBookCodec.encode(document)),
                parse(WorldBookCodec.encode(WorldBookCodec.decode(compact).document)),
            )
        }
    }

    @Test
    fun `edits deletions insertions and unrelated originals survive without restoring source entries`() {
        val source = """{"entries":{"0":{"uid":0,"content":"old zero"},"1":{"uid":1,"content":"old one"},"2":{"uid":2,"content":"old two"}}}"""
        val imported = WorldBookCodec.decode(source).document
        val document = imported.copy(
            name = "Edited",
            description = "new description",
            entries = listOf(
                imported.entries[2].copy(uid = "renamed", content = "new two", originalKey = "other"),
                imported.entries[0].copy(enabled = false, keys = listOf("edited")),
                WorldBookEntry("new", content = "added", originalJson = parse("""{"notInSource":[null,true,{"z":1}]}""")),
            ),
        )
        assertEquals(document, WorldBookCodec.decode(WorldBookCodec.encodeNative(document)).document)
        assertEquals(3, document.originalJson.getValue("entries").jsonObject.size)
    }

    @Test
    fun `arbitrary original trees and pointer escaping remain literal data`() {
        val original = parse("""{"~slash/key":{"":{"values":{"references":[null,false,1.00,"${"data".repeat(60)}"]}}},"entries":"not a source book"}""")
        val entryOriginal = original.getValue("~slash/key").jsonObject.getValue("").jsonObject
        val document = WorldBookDocument("Arbitrary", listOf(WorldBookEntry("u", originalJson = entryOriginal)), originalJson = original)
        val encoded = WorldBookCodec.encodeNative(document)
        assertTrue(encoded.contains("~0slash~1key/"))
        assertEquals(document, WorldBookCodec.decode(encoded).document)
    }

    @Test
    fun `five thousand dense source entries save and load beyond old native node ceiling`() {
        val source = buildJsonObject {
            put("name", "Dense")
            put("entries", buildJsonObject {
                repeat(5_000) { index ->
                    put(index.toString(), buildJsonObject {
                        put("uid", index)
                        put("content", "body $index")
                        put("key", JsonArray(listOf(JsonPrimitive("k$index"))))
                        repeat(69) { put("future$it", it) }
                    })
                }
            })
        }.toString()
        val document = WorldBookCodec.decode(source).document
        val legacy = WorldBookCodec.encodeLegacyNative(document)
        assertEquals(WorldBookNoticeCodes.JSON_NODE_LIMIT, assertThrows(WorldBookImportException::class.java) {
            WorldBookJsonGuard.parse(legacy, WorldBookImportLimits(maxChars = legacy.length + 1))
        }.code)
        val compact = WorldBookCodec.encodeNative(document)
        assertTrue(compact.length < legacy.length * 0.65)
        assertEquals(document, WorldBookCodec.decode(compact).document)
        assertEquals(document, WorldBookCodec.decode(legacy).document)
    }
}
