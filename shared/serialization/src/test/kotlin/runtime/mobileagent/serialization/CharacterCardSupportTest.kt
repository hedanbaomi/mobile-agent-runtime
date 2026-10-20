// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ImportedCharacterCard
import runtime.mobileagent.domain.charactercard.CharacterCardDocument

class CharacterCardSupportTest {
    @Test fun maximumEscapedGreetingAndLargeContextCanBeReadBackFromSnapshot() {
        val card = ImportedCharacterCard(CharacterCardDocument("青禾", description = "x".repeat(250_000)),
            greeting = "\u0001".repeat(256_000))
        CharacterCardSupport.validate(card)
        val snapshot = CharacterCardSupport.snapshot(card, "青禾")
        val manifest = buildJsonObject { put("characterCard", Json.encodeToJsonElement(snapshot)) }
        assertEquals(snapshot, CharacterCardSupport.snapshotFromManifest(manifest))
    }
    @Test fun importedMacrosAreLiteralExceptCharacterAndUserAndNeverExpandTwice() {
        val source = CharacterCardDocument("青禾", description = "{{char}}欢迎{{user}}。{{setvar::tool::shell}} {{date}}",
            firstMessage = "你好{{user}}，我是{{char}}。")
        val card = ImportedCharacterCard(source, "{{char}}", source.firstMessage)
        val prompt = CharacterCardSupport.prompt(card)
        assertTrue(prompt.contains("{{agent_name}}欢迎{{character_user}}"))
        assertTrue(prompt.contains("｛｛setvar::tool::shell｝｝ ｛｛date｝｝"))
        assertEquals("你好{{char}}，我是青禾。", CharacterCardSupport.snapshot(card, "青禾").greeting)
        assertEquals(source, card.document)
    }

    @Test fun literalJsonBracesRemainTextInsteadOfBreakingTheHostTemplateGrammar() {
        val source = CharacterCardDocument("青禾", exampleMessages = "{\"greeting\":\"你好\"}")
        val prompt = CharacterCardSupport.prompt(ImportedCharacterCard(source))
        assertTrue(prompt.contains("｛\"greeting\":\"你好\"｝"))
        assertFalse(prompt.contains('{'))
        assertEquals("{\"greeting\":\"你好\"}", source.exampleMessages)
    }

    @Test fun snapshotUsesCurrentNameAndSelectedGreetingWithoutRawSourceExtensions() {
        val doc = CharacterCardDocument("旧名字", personality = "耐心", scenario = "白塔", tags = listOf("法师"),
            firstMessage = "原开场白", alternateGreetings = listOf("{{char}}向{{user}}问好。"),
            originalJson = Json.parseToJsonElement("""{"extensions":{"script":"never execute"}}""").jsonObject)
        val card = ImportedCharacterCard(doc, "旅人", doc.alternateGreetings.single())
        val frozen = CharacterCardSupport.snapshot(card, "新名字")
        assertEquals("新名字向旅人问好。", frozen.greeting)
        assertEquals("新名字", frozen.context.characterName)
        assertEquals(listOf("法师"), frozen.context.characterTags)
        assertEquals("耐心", frozen.context.characterPersonality)
        assertNull(CharacterCardSupport.snapshot(card.copy(greeting = null), "新名字").greeting)
    }

    @Test fun expansionAndPersistedChoicesHaveFixedBounds() {
        val source = CharacterCardDocument("A")
        assertThrows(IllegalArgumentException::class.java) { CharacterCardSupport.validate(ImportedCharacterCard(source, "x".repeat(129))) }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardSupport.render("{{char}}".repeat(1000), "a".repeat(512), "User")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardSupport.validate(ImportedCharacterCard(source, greeting = "x".repeat(256_001)))
        }
    }

    @Test fun metadataOnlyBackupOmitsEmbeddedBookButKeepsOtherUnknownData() {
        val raw = Json.parseToJsonElement("""{"spec":"chara_card_v3","data":{"name":"青禾","character_book":{"entries":[]},"future":{"ok":true}},"character_book":{"legacy":true},"futureRoot":9}""").jsonObject
        val card = ImportedCharacterCard(CharacterCardDocument("青禾", originalJson = raw))
        val safe = CharacterCardSupport.forTransfer(card, false)!!
        assertFalse(safe.document.originalJson.containsKey("character_book"))
        assertFalse(safe.document.originalJson["data"]!!.jsonObject.containsKey("character_book"))
        assertEquals(raw["futureRoot"], safe.document.originalJson["futureRoot"])
        assertEquals(raw["data"]!!.jsonObject["future"], safe.document.originalJson["data"]!!.jsonObject["future"])
        assertEquals(card, CharacterCardSupport.forTransfer(card, true))
        assertEquals(card, CharacterCardSupport.decode(CharacterCardSupport.encode(card)))
    }
}
