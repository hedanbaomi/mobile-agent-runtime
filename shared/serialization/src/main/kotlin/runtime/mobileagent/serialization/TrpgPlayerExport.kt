// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.trpg.*

/** A shareable player record, deliberately distinct from a complete private backup. */
object TrpgPlayerExport {
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
    @Serializable private data class Document(val format: String = "mobileagent.trpg.player", val version: Int = 1,
        val exportedAt: String, val sourceRevision: Long, val state: TrpgState)

    fun encode(session: TrpgSession, exportedAt: String): String {
        TrpgStateValidation.validate(session)
        require(exportedAt.isNotBlank() && exportedAt.length <= 64)
        val visible = TrpgProjection.player(session.state)
        val state = visible.copy(campaign = visible.campaign.copy(
            // Device/player editing preferences and orphan source-message IDs are not shared.
            preferences = TrpgPreferences(ruleEdition = visible.campaign.preferences.ruleEdition),
            story = visible.campaign.story.copy(tracks = visible.campaign.story.tracks.map { it.copy(sourceMessageId = null) }),
            facts = visible.campaign.facts.map { it.copy(sourceMessageId = null) },
        ))
        return json.encodeToString(Document(exportedAt = exportedAt, sourceRevision = session.revision, state = state))
    }
}
