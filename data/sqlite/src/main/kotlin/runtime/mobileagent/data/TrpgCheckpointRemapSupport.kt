// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.trpg.TrpgBranchOrigin
import runtime.mobileagent.domain.trpg.TrpgState

/**
 * Facts and story tracks are the typed state fields that point at a message identity. Both are
 * remapped (never left pointing at the source conversation), and a source outside the prefix is
 * rejected instead of being silently dropped.
 */
internal object TrpgCheckpointRemapSupport {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = false }

    fun requireSources(state: TrpgState, payload: TrpgCheckpointPayload) {
        val known = payload.messages.map { it.messageId }.toSet()
        state.campaign.facts.forEach { fact ->
            fact.sourceMessageId?.let {
                if (it !in known) TrpgCheckpointGuard.fail("TRPG checkpoint fact source is outside its message prefix")
            }
        }
        state.campaign.story.tracks.forEach { track ->
            track.sourceMessageId?.let {
                if (it !in known) TrpgCheckpointGuard.fail("TRPG checkpoint story track source is outside its message prefix")
            }
        }
    }

    fun state(
        state: TrpgState,
        idMap: Map<String, String>,
        sourceConversationId: String,
        checkpointId: String,
        sourceRevision: Long,
    ): TrpgState {
        val facts = state.campaign.facts.map { fact ->
            val source = fact.sourceMessageId ?: return@map fact
            fact.copy(sourceMessageId = idMap[source]
                ?: TrpgCheckpointGuard.fail("TRPG checkpoint fact source is outside its message prefix"))
        }
        val tracks = state.campaign.story.tracks.map { track ->
            val source = track.sourceMessageId ?: return@map track
            track.copy(sourceMessageId = idMap[source]
                ?: TrpgCheckpointGuard.fail("TRPG checkpoint story track source is outside its message prefix"))
        }
        return state.copy(
            campaign = state.campaign.copy(
                facts = facts,
                story = state.campaign.story.copy(tracks = tracks),
                origin = TrpgBranchOrigin(sourceConversationId, checkpointId, sourceRevision),
            ),
        )
    }

    fun metadata(raw: String, idMap: Map<String, String>): String {
        if (raw.isBlank() || raw == "{}") return raw
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return raw
        fun remapValue(value: JsonElement): JsonElement = when (value) {
            is JsonPrimitive -> if (value.isString) idMap[value.content]?.let { JsonPrimitive(it) } ?: value else value
            is JsonArray -> JsonArray(value.map(::remapValue))
            else -> value
        }
        fun rewrite(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) ->
                if (isMessageReferenceKey(key)) remapValue(value) else rewrite(value)
            })
            is JsonArray -> JsonArray(element.map(::rewrite))
            else -> element
        }
        return rewrite(root).toString()
    }

    private fun isMessageReferenceKey(key: String): Boolean = key == "messageId" || key == "messageIds" ||
        key == "sourceMessageId" || key == "sourceMessageIds" || key == "parentMessageId" ||
        key == "coveredMessageIds" || key == "coveredMessageIdsJson"
}
