// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.domain.CharacterCardSnapshot
import runtime.mobileagent.domain.ImportedCharacterCard
import runtime.mobileagent.domain.worldbook.WorldBookContext

/** Bounded literal projection. Imported macros cannot become application template variables. */
object CharacterCardSupport {
    const val MAX_PROMPT_CHARS = 256_000
    const val MAX_METADATA_CHARS = 8 * 1024 * 1024
    // JSON escaping can expand each greeting character to six characters.
    private const val MAX_SNAPSHOT_CHARS = MAX_PROMPT_CHARS * 7 + 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(card: ImportedCharacterCard?): String? = card?.let {
        validate(it)
        json.encodeToString(it)
    }

    fun decode(raw: String?): ImportedCharacterCard? {
        if (raw.isNullOrBlank() || raw == "null") return null
        require(raw.length <= MAX_METADATA_CHARS) { "Character card metadata exceeds the storage limit" }
        return json.decodeFromString<ImportedCharacterCard>(raw).also(::validate)
    }

    fun validate(card: ImportedCharacterCard) {
        require(card.document.name.isNotBlank() && card.document.name.length <= 512) { "Character name is required (maximum 512 characters)" }
        require(card.userName.isNotBlank() && card.userName.length <= 128) { "Player name is required (maximum 128 characters)" }
        require((card.greeting?.length ?: 0) <= MAX_PROMPT_CHARS) { "Character greeting is too long" }
        require(json.encodeToString(card).length <= MAX_METADATA_CHARS) { "Character card metadata exceeds the storage limit" }
        snapshot(card, card.document.name)
    }

    fun prompt(card: ImportedCharacterCard): String {
        val doc = card.document
        val sections = listOf(
            "Character" to doc.name,
            "Description" to doc.description,
            "Personality" to doc.personality,
            "Scenario" to doc.scenario,
            "Character instructions" to doc.systemPrompt,
            "Example dialogue" to doc.exampleMessages,
            "Additional character instructions" to doc.postHistoryInstructions,
        )
        val result = StringBuilder()
        sections.filter { it.second.isNotBlank() }.forEach { (label, text) ->
            val body = render(text, doc.name, card.userName, template = true)
            append(result, "## $label\n$body\n\n")
        }
        return result.toString().trim()
    }

    fun snapshot(card: ImportedCharacterCard, agentName: String): CharacterCardSnapshot {
        require(agentName.isNotBlank() && agentName.length <= 512) { "Character name is too long" }
        val doc = card.document
        fun text(value: String) = render(value, agentName, card.userName)
        val context = WorldBookContext(
            characterName = agentName, userName = card.userName,
            characterDescription = text(doc.description), characterPersonality = text(doc.personality),
            scenario = text(doc.scenario), creatorNotes = doc.creatorNotes,
            characterTags = doc.tags,
            exampleMessages = doc.exampleMessages.takeIf { it.isNotBlank() }?.let { listOf(text(it)) }.orEmpty(),
            characterFileName = doc.name,
        )
        require(json.encodeToString(context).length <= MAX_PROMPT_CHARS) { "Character context is too large" }
        return CharacterCardSnapshot(context, card.greeting?.takeIf { it.isNotBlank() }?.let(::text))
    }

    /** Only these two placeholders are expanded, once, with an output bound checked before append. */
    fun render(value: String, characterName: String, userName: String, template: Boolean = false): String {
        require(value.length <= MAX_PROMPT_CHARS) { "Character text exceeds the prompt limit" }
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            val start = value.indexOf("{{", index)
            if (start < 0) { append(result, literal(value.substring(index), template)); break }
            append(result, literal(value.substring(index, start), template))
            val end = value.indexOf("}}", start + 2)
            if (end < 0) { append(result, literal(value.substring(start), template)); break }
            val replacement = when (value.substring(start + 2, end).trim().lowercase()) {
                "char" -> if (template) "{{agent_name}}" else characterName
                "user" -> if (template) "{{character_user}}" else userName
                else -> literal(value.substring(start, end + 2), template)
            }
            append(result, replacement)
            index = end + 2
        }
        return result.toString()
    }

    private fun literal(value: String, template: Boolean): String =
        if (template) value.replace('{', '｛').replace('}', '｝') else value

    private fun append(output: StringBuilder, value: String) {
        require(value.length <= MAX_PROMPT_CHARS - output.length) { "Character text exceeds the prompt limit" }
        output.append(value)
    }

    /** A metadata-only backup must not smuggle the embedded world book through the original card. */
    fun forTransfer(card: ImportedCharacterCard?, includeWorldBookContent: Boolean): ImportedCharacterCard? {
        if (card == null || includeWorldBookContent) return card
        val original = card.document.originalJson
        val cleaned = original.toMutableMap().apply {
            remove("character_book")
            val data = get("data") as? JsonObject
            if (data != null) put("data", JsonObject(data.filterKeys { it != "character_book" }))
        }
        return card.copy(document = card.document.copy(originalJson = JsonObject(cleaned)))
    }

    fun snapshotFromManifest(manifest: JsonObject): CharacterCardSnapshot? {
        val element = manifest["characterCard"]?.takeUnless { it == JsonNull } ?: return null
        require(element.toString().length <= MAX_SNAPSHOT_CHARS) { "Character snapshot is too large" }
        return json.decodeFromString<CharacterCardSnapshot>(element.toString()).also {
            require(json.encodeToString(it.context).length <= MAX_PROMPT_CHARS) { "Character context is too large" }
            require((it.greeting?.length ?: 0) <= MAX_PROMPT_CHARS) { "Character greeting is too long" }
        }
    }
}
