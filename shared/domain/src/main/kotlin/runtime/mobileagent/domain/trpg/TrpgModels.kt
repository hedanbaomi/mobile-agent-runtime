// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class TrpgRuleset { CUSTOM, DND_3R, DND_5E, COC_7 }

@Serializable
data class TrpgResource(val name: String, val current: Int, val maximum: Int? = null)

/** Untrusted user/model content; none of these fields conveys tool authority. */
@Serializable
data class TrpgState(
    val ruleset: TrpgRuleset = TrpgRuleset.CUSTOM,
    val playerName: String = "",
    val characterSheet: String = "",
    val scene: String = "",
    val notes: String = "",
    val resources: List<TrpgResource> = emptyList(),
)

@Serializable
data class TrpgSession(val conversationId: String, val revision: Long = 0, val state: TrpgState = TrpgState())

/** Validate before encoding so oversized caller objects are rejected before allocation. */
object TrpgStateValidation {
    const val MAX_ENCODED_BYTES = 64 * 1024
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    fun validate(state: TrpgState) {
        require(state.playerName.length <= 128) { "TRPG player name exceeds 128 characters" }
        require(state.characterSheet.length <= 8192) { "TRPG character sheet exceeds 8192 characters" }
        require(state.scene.length <= 4096) { "TRPG scene exceeds 4096 characters" }
        require(state.notes.length <= 8192) { "TRPG notes exceed 8192 characters" }
        require(state.resources.size <= 32) { "TRPG resources exceed 32 entries" }
        require(state.resources.map { it.name }.distinct().size == state.resources.size) { "TRPG resource names must be unique" }
        state.resources.forEach {
            require(it.name.isNotBlank() && it.name.length <= 64) { "TRPG resource name is empty or too long" }
            require(it.current in -1_000_000..1_000_000) { "TRPG resource value is out of range" }
            require(it.maximum == null || it.maximum in 0..1_000_000) { "TRPG resource maximum is out of range" }
        }
        require(json.encodeToString(state).toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) { "TRPG state exceeds 64 KiB" }
    }
    fun validate(session: TrpgSession) {
        require(session.conversationId.isNotBlank() && session.conversationId.length <= 256) { "Invalid TRPG conversation id" }
        require(session.revision >= 0) { "Invalid TRPG revision" }
        validate(session.state)
    }
}
