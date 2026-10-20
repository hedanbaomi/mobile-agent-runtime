// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable
import runtime.mobileagent.domain.charactercard.CharacterCardDocument
import runtime.mobileagent.domain.worldbook.WorldBookContext

/** User-selected character content; never a provider, tool, script or permission configuration. */
@Serializable
data class ImportedCharacterCard(
    val document: CharacterCardDocument,
    val userName: String = "User",
    /** Null means the user chose to start conversations without an imported greeting. */
    val greeting: String? = null,
)

/** Only usable text enters immutable conversation snapshots; source extensions stay on the Agent. */
@Serializable
data class CharacterCardSnapshot(
    val context: WorldBookContext = WorldBookContext(),
    val greeting: String? = null,
)
