// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.encodeToJsonElement
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ImportedCharacterCard
import runtime.mobileagent.serialization.CharacterCardSupport

/** Additive schema and bounded metadata persistence, separate from privileges and model selection. */
internal object CharacterCardStorage {
    const val OVERVIEW_COLUMNS = "id,name,prompt_revision_id,chat_profile_id,vision_profile_id,embedding_profile_id,reranker_profile_id,knowledge_base_ids,skill_ids,retrieval_mode,revision,parameter_overrides_json,context_policy_json,permission_settings_json,world_book_ids,mode"
    private val json = Json { encodeDefaults = true }

    fun install(db: SqlConnection) {
        if (db.query("PRAGMA table_info(agent_profiles)").none { it.string("name") == "character_card_json" }) {
            db.execute("ALTER TABLE agent_profiles ADD COLUMN character_card_json TEXT")
        }
    }

    fun encode(card: ImportedCharacterCard?): String? = CharacterCardSupport.encode(card)
    fun decode(row: SqlRow): ImportedCharacterCard? = CharacterCardSupport.decode(row.columns["character_card_json"]?.toString())
    fun validate(profile: AgentProfile) { profile.characterCard?.let(CharacterCardSupport::validate) }

    fun addToManifest(builder: JsonObjectBuilder, profile: AgentProfile) {
        profile.characterCard?.let { card ->
            builder.put("characterCard", json.encodeToJsonElement(CharacterCardSupport.snapshot(card, profile.name)))
        }
    }
}
