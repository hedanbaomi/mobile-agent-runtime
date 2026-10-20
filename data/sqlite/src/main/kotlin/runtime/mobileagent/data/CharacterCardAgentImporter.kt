// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.worldbook.WorldBookDocument

data class CharacterCardAgentSave(val profile: AgentProfile, val createdWorldBookId: String? = null)

/** Creates new resources only. A bad model/binding/prompt rolls the optional world book back too. */
class CharacterCardAgentImporter(private val db: SqlConnection) {
    fun saveNew(profile: AgentProfile, prompt: String, worldBook: WorldBookDocument?): CharacterCardAgentSave = db.transaction {
        val agents = AgentRepository(db)
        require(agents.get(profile.id) == null) { "Character import must create a new Agent" }
        require(profile.characterCard != null) { "Character import metadata is missing" }
        val book = worldBook?.let { WorldBookRepository(db).save(it) }
        val selected = profile.copy(worldBookIds = profile.worldBookIds + listOfNotNull(book?.summary?.id))
        CharacterCardAgentSave(agents.saveWithPrompt(selected, prompt), book?.summary?.id)
    }
}
