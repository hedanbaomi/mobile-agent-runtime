// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookCharacterFilter
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookFormat
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookRole
import runtime.mobileagent.domain.worldbook.WorldBookSelectiveLogic
import runtime.mobileagent.domain.worldbook.WorldBookSettings

/**
 * 编解码往返与跨格式测试：完整字段、未知扩展、五种载体（含 native 权威格式）。
 * 全部使用固定构造的合成样例。
 */
class WorldBookCodecRoundTripTest {

    private fun parse(text: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject

    private fun fullEntry(uid: String = "7", mapKey: String? = "7"): WorldBookEntry = WorldBookEntry(
        uid = uid,
        keys = listOf("alpha", "/be.*ta/i", "中文键"),
        content = "内容 {{user}} tail",
        title = "标题",
        enabled = false,
        secondaryKeys = listOf("s1", "s2"),
        constant = true,
        vectorized = true,
        selective = false,
        selectiveLogic = WorldBookSelectiveLogic.NOT_ALL,
        addMemo = true,
        order = 42,
        position = WorldBookPosition.OUTLET,
        ignoreBudget = true,
        excludeRecursion = true,
        preventRecursion = true,
        matchPersonaDescription = true,
        matchCharacterDescription = true,
        matchCharacterPersonality = true,
        matchCharacterDepthPrompt = true,
        matchScenario = true,
        matchCreatorNotes = true,
        delayUntilRecursion = 2,
        probability = 37,
        useProbability = false,
        depth = 9,
        outletName = "outlet-a",
        group = "g1, g2",
        groupOverride = true,
        groupWeight = 5,
        scanDepth = 3,
        caseSensitive = true,
        matchWholeWords = true,
        useGroupScoring = false,
        automationId = "auto-1",
        role = WorldBookRole.ASSISTANT,
        sticky = 4,
        cooldown = 5,
        delay = 6,
        triggers = listOf("normal", "continue"),
        useRegex = true,
        characterFilter = WorldBookCharacterFilter(true, listOf("Hero.png"), listOf("tag1")),
        extensions = buildJsonObject { put("vendorExt", "keep-me"); put("nested", buildJsonObject { put("n", 1) }) },
        displayIndex = 3,
        originalJson = buildJsonObject {
            put("futureField", "keep")
            put("keysecondary", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("old"))))
        },
        originalKey = mapKey,
    )

    private fun fullDocument(format: WorldBookFormat = WorldBookFormat.SILLY_TAVERN): WorldBookDocument =
        WorldBookDocument(
            name = "Synthetic Book",
            entries = listOf(fullEntry("7", "7"), fullEntry("9", "9")),
            description = "合成样例",
            settings = WorldBookSettings(
                scanDepth = 5,
                minActivations = 2,
                minActivationsDepthMax = 12,
                budgetPercent = 30,
                budgetCap = 900,
                includeNames = false,
                recursive = true,
                overflowAlert = true,
                caseSensitive = true,
                matchWholeWords = true,
                useGroupScoring = true,
                maxRecursionSteps = 7,
            ),
            originalJson = buildJsonObject { put("rootUnknown", "root-keep") },
            format = format,
        )

    @Test
    fun `native round trip preserves every executable field`() {
        val document = fullDocument()
        val encoded = WorldBookCodec.encodeNative(document)
        val decoded = WorldBookCodec.decode(encoded).document
        assertEquals(document, decoded)
        assertEquals(WorldBookFormat.SILLY_TAVERN, decoded.format)
    }

    @Test
    fun `native round trip preserves non ST provenance and unknown root fields`() {
        val document = fullDocument(WorldBookFormat.CHARACTER_CARD_V3)
        val decoded = WorldBookCodec.decode(WorldBookCodec.encodeNative(document)).document
        assertEquals(WorldBookFormat.CHARACTER_CARD_V3, decoded.format)
        assertEquals("root-keep", decoded.originalJson["rootUnknown"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodeNative keeps unknown entry fields inside originalJson`() {
        val document = fullDocument()
        val encoded = WorldBookCodec.encodeNative(document)
        val entry = parse(encoded).getValue("entries").jsonArray.first().jsonObject
        assertEquals(
            "keep",
            entry.getValue("originalJson").jsonObject.getValue("futureField").jsonPrimitive.content,
        )
    }

    @Test
    fun `silly tavern import reads all typed fields and keeps unknown extension`() {
        val text = """
            {
              "name": "ST Book",
              "description": "desc",
              "rootUnknown": {"a": 1},
              "entries": {
                "0": {
                  "uid": 0,
                  "key": ["alpha"],
                  "keysecondary": ["beta"],
                  "comment": "c0",
                  "content": "body 0",
                  "constant": true,
                  "vectorized": false,
                  "selective": true,
                  "selectiveLogic": 3,
                  "addMemo": true,
                  "order": 12,
                  "position": 4,
                  "disable": true,
                  "ignoreBudget": true,
                  "excludeRecursion": true,
                  "preventRecursion": true,
                  "matchPersonaDescription": true,
                  "matchCharacterDescription": true,
                  "matchCharacterPersonality": true,
                  "matchCharacterDepthPrompt": true,
                  "matchScenario": true,
                  "matchCreatorNotes": true,
                  "delayUntilRecursion": 2,
                  "probability": 40,
                  "useProbability": false,
                  "depth": 6,
                  "outletName": "o",
                  "group": "g",
                  "groupOverride": true,
                  "groupWeight": 9,
                  "scanDepth": 3,
                  "caseSensitive": true,
                  "matchWholeWords": true,
                  "useGroupScoring": false,
                  "automationId": "auto",
                  "role": 2,
                  "sticky": 3,
                  "cooldown": 4,
                  "delay": 5,
                  "triggers": ["normal"],
                  "characterFilter": {"isExclude": true, "names": ["A.png"], "tags": ["t"]},
                  "extensions": {"vendorExt": "v"},
                  "displayIndex": 2,
                  "futureField": {"deep": true}
                }
              }
            }
        """.trimIndent()

        val result = WorldBookCodec.decode(text)
        val document = result.document
        assertEquals(WorldBookFormat.SILLY_TAVERN, document.format)
        assertEquals("ST Book", document.name)
        assertEquals("desc", document.description)
        val entry = document.entries.single()
        assertEquals("0", entry.uid)
        assertEquals("0", entry.originalKey)
        assertEquals(listOf("alpha"), entry.keys)
        assertEquals(listOf("beta"), entry.secondaryKeys)
        assertEquals("c0", entry.title)
        assertEquals(false, entry.enabled)
        assertEquals(WorldBookSelectiveLogic.AND_ALL, entry.selectiveLogic)
        assertEquals(WorldBookPosition.AT_DEPTH, entry.position)
        assertEquals(WorldBookRole.ASSISTANT, entry.role)
        assertEquals(2, entry.delayUntilRecursion)
        assertEquals(40, entry.probability)
        assertFalse(entry.useProbability)
        assertEquals(6, entry.depth)
        assertEquals("o", entry.outletName)
        assertEquals("g", entry.group)
        assertTrue(entry.groupOverride)
        assertEquals(9, entry.groupWeight)
        assertEquals(3, entry.scanDepth)
        assertEquals(true, entry.caseSensitive)
        assertEquals(true, entry.matchWholeWords)
        assertEquals(false, entry.useGroupScoring)
        assertEquals("auto", entry.automationId)
        assertEquals(3, entry.sticky)
        assertEquals(listOf("normal"), entry.triggers)
        assertTrue(entry.characterFilter.isExclude)
        assertEquals("v", entry.extensions.getValue("vendorExt").jsonPrimitive.content)
        assertEquals(2, entry.displayIndex)
        assertEquals(
            true,
            entry.originalJson.getValue("futureField").jsonObject.getValue("deep").jsonPrimitive.content
                .let { it == "true" },
        )
    }

    @Test
    fun `entry map keys are ordered numerically then by insertion`() {
        val text = """{"entries": {"10": {"key": ["a"]}, "2": {"key": ["b"]}, "z": {"key": ["c"]}}}"""
        val entries = WorldBookCodec.decode(text).document.entries
        assertEquals(listOf("2", "10", "z"), entries.map { it.uid })
    }

    @Test
    fun `edited fields are written and unknown fields survive silly tavern export`() {
        val text = """
            {"name": "B", "entries": {"0": {"key": ["a"], "content": "old", "futureField": 1}}}
        """.trimIndent()
        val document = WorldBookCodec.decode(text).document
        val edited = document.copy(entries = document.entries.map { it.copy(content = "new", title = "T2") })
        val encoded = WorldBookCodec.encode(edited, WorldBookFormat.SILLY_TAVERN)
        val entry = parse(encoded).getValue("entries").jsonObject.getValue("0").jsonObject
        assertEquals("new", entry.getValue("content").jsonPrimitive.content)
        assertEquals("T2", entry.getValue("comment").jsonPrimitive.content)
        assertEquals(1, entry.getValue("futureField").jsonPrimitive.int)
        val reimported = WorldBookCodec.decode(encoded).document
        assertEquals("new", reimported.entries.single().content)
    }

    @Test
    fun `character card v2 import maps book level fields and keeps unknown card fields`() {
        val text = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "Hero",
                "description": "hero desc",
                "tags": ["x"],
                "creator": "someone",
                "character_book": {
                  "name": "Hero Lore",
                  "description": "book desc",
                  "scan_depth": 11,
                  "token_budget": 512,
                  "recursive_scanning": true,
                  "extensions": {"bookExt": 1},
                  "entries": [
                    {
                      "keys": ["k1"],
                      "secondary_keys": ["k2"],
                      "content": "cc body",
                      "enabled": true,
                      "insertion_order": 7,
                      "case_sensitive": true,
                      "selective": false,
                      "constant": true,
                      "position": "after_char",
                      "id": 4,
                      "comment": "cc comment",
                      "name": "cc name",
                      "extensions": {
                        "position": 5,
                        "depth": 8,
                        "probability": 25,
                        "group": "cg",
                        "group_weight": 3,
                        "sticky": 2,
                        "vendor": "keep"
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = WorldBookCodec.decode(text)
        val document = result.document
        assertEquals(WorldBookFormat.CHARACTER_CARD_V2, document.format)
        assertEquals("Hero Lore", document.name)
        assertEquals("book desc", document.description)
        assertEquals(11, document.settings.scanDepth)
        assertEquals(512, document.settings.budgetCap)
        assertTrue(document.settings.recursive)
        val entry = document.entries.single()
        assertEquals("4", entry.uid)
        assertEquals(WorldBookPosition.EM_TOP, entry.position)
        assertEquals(8, entry.depth)
        assertEquals(25, entry.probability)
        assertEquals("cg", entry.group)
        assertEquals(3, entry.groupWeight)
        assertEquals(2, entry.sticky)
        assertFalse(entry.selective)
        assertEquals(true, entry.caseSensitive)

        val reencoded = parse(WorldBookCodec.encode(document, WorldBookFormat.CHARACTER_CARD_V2))
        assertEquals("chara_card_v2", reencoded.getValue("spec").jsonPrimitive.content)
        assertEquals("someone", reencoded.getValue("data").jsonObject.getValue("creator").jsonPrimitive.content)
        val book = reencoded.getValue("data").jsonObject.getValue("character_book").jsonObject
        assertEquals(1, book.getValue("extensions").jsonObject.getValue("bookExt").jsonPrimitive.int)
        val ccEntry = book.getValue("entries").jsonArray.single().jsonObject
        assertEquals("keep", ccEntry.getValue("extensions").jsonObject.getValue("vendor").jsonPrimitive.content)
        assertEquals(7, ccEntry.getValue("insertion_order").jsonPrimitive.int)
        assertEquals(4, ccEntry.getValue("id").jsonPrimitive.int)
    }

    @Test
    fun `lorebook v3 standalone import and export use spec data wrapper`() {
        val text = """
            {
              "spec": "lorebook_v3",
              "data": {
                "name": "V3 Book",
                "description": "v3",
                "scan_depth": 4,
                "recursive_scanning": false,
                "extensions": {"v3ext": true},
                "entries": [
                  {"keys": ["\\\\bfoo\\\\d+"], "content": "v3 body", "enabled": true, "insertion_order": 0,
                   "use_regex": true, "constant": true, "id": "entry-a", "extensions": {}}
                ]
              }
            }
        """.trimIndent()

        val document = WorldBookCodec.decode(text).document
        assertEquals(WorldBookFormat.LOREBOOK_V3, document.format)
        assertEquals("V3 Book", document.name)
        val entry = document.entries.single()
        assertEquals("entry-a", entry.uid)
        assertTrue(entry.useRegex)
        assertTrue(entry.constant)

        val encoded = parse(WorldBookCodec.encode(document, WorldBookFormat.LOREBOOK_V3))
        assertEquals("lorebook_v3", encoded.getValue("spec").jsonPrimitive.content)
        val data = encoded.getValue("data").jsonObject
        assertNotNull(data["entries"])
        assertEquals(true, data.getValue("extensions").jsonObject.getValue("v3ext").jsonPrimitive.content.let { it == "true" })
        assertEquals(true, data.getValue("entries").jsonArray.single().jsonObject.getValue("use_regex").jsonPrimitive.content.let { it == "true" })
        val reimported = WorldBookCodec.decode(encoded.toString()).document
        assertEquals("entry-a", reimported.entries.single().uid)
        assertTrue(reimported.entries.single().useRegex)
    }

    @Test
    fun `character card v3 without book imports as empty book with notice`() {
        val text = """{"spec": "chara_card_v3", "spec_version": "3.0", "data": {"name": "Nova"}}"""
        val result = WorldBookCodec.decode(text)
        assertEquals(WorldBookFormat.CHARACTER_CARD_V3, result.document.format)
        assertTrue(result.document.entries.isEmpty())
        assertTrue(result.notices.any { it.code == WorldBookNoticeCodes.FORMAT_CHARACTER_BOOK_MISSING })
        assertEquals("Nova's Lorebook", result.document.name)
    }

    @Test
    fun `cross format conversion preserves typed field values`() {
        val ccText = """
            {"entries": [{"keys": ["k"], "content": "body", "enabled": true, "insertion_order": 3,
              "position": "before_char", "id": 1, "comment": "t", "extensions": {}}]}
        """.trimIndent()
        val fromCc = WorldBookCodec.decode(ccText).document
        assertEquals(WorldBookFormat.CHARACTER_BOOK, fromCc.format)

        val stText = WorldBookCodec.encode(fromCc, WorldBookFormat.SILLY_TAVERN)
        val fromSt = WorldBookCodec.decode(stText).document
        assertEquals(WorldBookFormat.SILLY_TAVERN, fromSt.format)
        assertEquals(fromCc.entries.single().keys, fromSt.entries.single().keys)
        assertEquals(fromCc.entries.single().content, fromSt.entries.single().content)
        assertEquals(fromCc.entries.single().order, fromSt.entries.single().order)
        assertEquals(fromCc.entries.single().position, fromSt.entries.single().position)
        assertEquals(fromCc.entries.single().title, fromSt.entries.single().title)

        val cardText = WorldBookCodec.encode(fromCc, WorldBookFormat.CHARACTER_CARD_V3)
        val fromCard = WorldBookCodec.decode(cardText).document
        assertEquals(WorldBookFormat.CHARACTER_CARD_V3, fromCard.format)
        assertEquals(fromCc.entries.single().content, fromCard.entries.single().content)
        assertEquals(fromCc.entries.single().order, fromCard.entries.single().order)
    }

    @Test
    fun `object entries and array entries both decode with stable uids`() {
        val objectForm = WorldBookCodec.decode("""{"entries": {"5": {"key": ["a"], "content": "x"}}}""")
        assertEquals("5", objectForm.document.entries.single().uid)
        val arrayForm = WorldBookCodec.decode("""{"entries": [{"keys": ["a"], "content": "x", "id": "e5"}]}""")
        assertEquals("e5", arrayForm.document.entries.single().uid)
        val noIdForm = WorldBookCodec.decode("""{"entries": [{"keys": ["a"], "content": "x"}]}""")
        assertEquals("0", noIdForm.document.entries.single().uid)
        assertTrue(noIdForm.notices.any { it.code == WorldBookNoticeCodes.ENTRY_UID_MISSING })
    }

    // ---------------------------------------------------------------- carrier / 包装回归

    @Test
    fun `card to silly tavern edit reimports the edited entries`() {
        val card = """
            {"spec": "chara_card_v2", "spec_version": "2.0",
             "data": {"name": "Hero", "creator": "someone",
               "character_book": {"name": "Hero Lore",
                 "entries": [{"keys": ["k"], "content": "old", "enabled": true,
                   "insertion_order": 1, "id": 1, "extensions": {"vendor": 1}}]}}}
        """.trimIndent()
        val document = WorldBookCodec.decode(card).document
        val edited = document.copy(entries = document.entries.map { it.copy(content = "new") })

        val exported = parse(WorldBookCodec.encode(edited, WorldBookFormat.SILLY_TAVERN))
        assertFalse("data" in exported, "carrier data must not be copied")
        assertFalse("character_book" in exported, "stale character_book must not be copied")
        assertFalse("spec" in exported, "carrier spec must not be copied")
        assertNotNull(exported["entries"])

        val reimported = WorldBookCodec.decode(exported.toString()).document
        assertEquals(WorldBookFormat.SILLY_TAVERN, reimported.format)
        assertEquals("new", reimported.entries.single().content)
        assertEquals(1, reimported.entries.single().extensions.getValue("vendor").jsonPrimitive.int)
    }

    @Test
    fun `bare character book wrapper is not carried into silly tavern export`() {
        val wrapped = """
            {"character_book": {"name": "W", "entries": [
              {"keys": ["k"], "content": "old", "enabled": true, "insertion_order": 2,
               "extensions": {"vendor": 2}}]}}
        """.trimIndent()
        val document = WorldBookCodec.decode(wrapped).document
        assertEquals(WorldBookFormat.CHARACTER_BOOK, document.format)

        val edited = document.copy(entries = document.entries.map { it.copy(content = "edited") })
        val exported = parse(WorldBookCodec.encode(edited, WorldBookFormat.SILLY_TAVERN))
        assertFalse("character_book" in exported)

        val reimported = WorldBookCodec.decode(exported.toString()).document
        assertEquals("edited", reimported.entries.single().content)
        assertEquals(2, reimported.entries.single().order)
        assertEquals(2, reimported.entries.single().extensions.getValue("vendor").jsonPrimitive.int)
    }

    @Test
    fun `book level unknowns stay with the book and never leak into the carrier`() {
        val stText = """{"name": "B", "vendorBook": "keep", "entries": {"0": {"key": ["k"], "content": "c"}}}"""
        val document = WorldBookCodec.decode(stText).document

        val card = parse(WorldBookCodec.encode(document, WorldBookFormat.CHARACTER_CARD_V3))
        assertFalse("entries" in card, "stale entries must not stay on the card root")
        assertFalse("vendorBook" in card)
        val book = card.getValue("data").jsonObject.getValue("character_book").jsonObject
        assertEquals("keep", book.getValue("vendorBook").jsonPrimitive.content)
        assertEquals("c", WorldBookCodec.decode(card.toString()).document.entries.single().content)

        val v3 = parse(WorldBookCodec.encode(document, WorldBookFormat.LOREBOOK_V3))
        assertFalse("vendorBook" in v3)
        assertEquals("keep", v3.getValue("data").jsonObject.getValue("vendorBook").jsonPrimitive.content)

        val cc = parse(WorldBookCodec.encode(document, WorldBookFormat.CHARACTER_BOOK))
        assertEquals("keep", cc.getValue("vendorBook").jsonPrimitive.content)
        assertEquals("c", WorldBookCodec.decode(cc.toString()).document.entries.single().content)
    }

    @Test
    fun `card export always includes its target spec version`() {
        val document = WorldBookCodec.decode("""{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Character","character_book":{"entries":[{"id":1,"keys":["k"],"content":"old","enabled":true,"insertion_order":100,"extensions":{}}]}}}""").document
        val edited = document.copy(entries = document.entries.map { it.copy(content = "new") })
        for ((format, version) in listOf(WorldBookFormat.CHARACTER_CARD_V2 to "2.0", WorldBookFormat.CHARACTER_CARD_V3 to "3.0")) {
            val raw = WorldBookCodec.encode(edited, format)
            assertEquals(version, parse(raw).getValue("spec_version").jsonPrimitive.content)
            assertEquals("new", WorldBookCodec.decode(raw).document.entries.single().content)
        }
    }

    @Test
    fun `priority survives character book and silly tavern round trips without conflation`() {
        val ccText = """
            {"entries": [{"keys": ["k"], "content": "c", "enabled": true,
              "insertion_order": 100, "priority": 999, "id": 1, "extensions": {}}]}
        """.trimIndent()
        val document = WorldBookCodec.decode(ccText).document
        assertEquals(100, document.entries.single().order)
        assertEquals(999, document.entries.single().priority)

        val ccOut = parse(WorldBookCodec.encode(document, WorldBookFormat.CHARACTER_BOOK))
            .getValue("entries").jsonArray.single().jsonObject
        assertEquals(100, ccOut.getValue("insertion_order").jsonPrimitive.int)
        assertEquals(999, ccOut.getValue("priority").jsonPrimitive.int)

        val ccBack = WorldBookCodec.decode(WorldBookCodec.encode(document, WorldBookFormat.CHARACTER_BOOK)).document
        assertEquals(100, ccBack.entries.single().order)
        assertEquals(999, ccBack.entries.single().priority)

        val stBack = WorldBookCodec.decode(WorldBookCodec.encode(document, WorldBookFormat.SILLY_TAVERN)).document
        assertEquals(100, stBack.entries.single().order)
        assertEquals(999, stBack.entries.single().priority)
    }

    @Test
    fun `character filter unknown nested keys survive silly tavern export`() {
        val text = """
            {"entries": {"0": {"key": ["k"], "content": "c",
              "characterFilter": {"isExclude": false, "names": [], "tags": [],
                "vendorFlag": true, "vendorNested": {"a": 1}}}}}
        """.trimIndent()
        val document = WorldBookCodec.decode(text).document
        val edited = document.copy(entries = document.entries.map { it.copy(title = "renamed") })

        val entry = parse(WorldBookCodec.encode(edited, WorldBookFormat.SILLY_TAVERN))
            .getValue("entries").jsonObject.getValue("0").jsonObject
        val filter = entry.getValue("characterFilter").jsonObject
        assertEquals("renamed", entry.getValue("comment").jsonPrimitive.content)
        assertTrue(filter.getValue("vendorFlag").jsonPrimitive.content == "true")
        assertEquals(1, filter.getValue("vendorNested").jsonObject.getValue("a").jsonPrimitive.int)
        assertEquals(false, filter.getValue("isExclude").jsonPrimitive.content == "true")
    }
}
