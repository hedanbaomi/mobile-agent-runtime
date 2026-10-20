// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.CharacterCardAgentImporter
import runtime.mobileagent.data.CharacterCardAgentSave
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorldBookRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.feature.agents.AgentsUiState

internal fun canImportCharacterCard(state: AgentsUiState, saving: Boolean): Boolean = !saving && state.editor == null

internal object CharacterCardAgentPersistence {
    fun save(db: SqlConnection, agents: AgentRepository, profile: AgentProfile, prompt: String,
        newAgent: Boolean, pendingBook: WorldBookDocument?): CharacterCardAgentSave {
        if (newAgent && profile.characterCard != null) return CharacterCardAgentImporter(db).saveNew(profile, prompt, pendingBook)
        require(pendingBook == null) { "Embedded world books can only be created with a new character Agent" }
        return CharacterCardAgentSave(agents.saveWithPrompt(profile, prompt))
    }

    fun rollbackBook(db: SqlConnection, id: String?) {
        if (id != null) WorldBookRepository(db).delete(id, expectedRevision = 1)
    }
}
