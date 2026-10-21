// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*

class TrpgPlayerExportTest {
    @Test fun exportedRecordOmitsSecretsPrivatePreferencesAndUnresolvableMessageLinks() {
        val session = TrpgSession("private-conversation", 7, TrpgState(scene = "Square", campaign = TrpgCampaign(
            preferences = TrpgPreferences(boundaries = "PRIVATE_BOUNDARY", viewRole = TrpgViewRole.GAME_MASTER),
            facts = listOf(TrpgFact("known", "The gate is open", sourceMessageId = "private-source"),
                TrpgFact("hidden", "SECRET", visibility = TrpgVisibility.GAME_MASTER)),
        )))
        val raw = TrpgPlayerExport.encode(session, "now")
        listOf("SECRET", "PRIVATE_BOUNDARY", "private-source", "private-conversation").forEach { assertFalse(raw.contains(it), it) }
        assertTrue(raw.contains("The gate is open"))
        assertTrue(raw.contains("Square"))
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals("\"mobileagent.trpg.player\"", root["format"].toString())
        assertEquals("7", root["sourceRevision"].toString())
        assertEquals("SECRET", session.state.campaign.facts.last().text)
    }
}
