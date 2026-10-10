// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.domain.*
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.provider.SecretRedactor

/**
 * One run's durable assistant response. Owns partial text, declared reasoning, tool/citation
 * evidence and terminal checkpoints across model rounds. The VM supplies only UI projections;
 * checkpoint durability survives cancellation and never borrows another selected conversation.
 */
internal class ChatRunResponse(
    private val conversations: ConversationRepository,
    private val conversationId: String,
    private val runId: String,
    private val citations: Map<String, Pair<Citation, String>>,
    private val onFlush: (String?, String, String, Boolean) -> Unit,
    private val onAppend: (Message) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    var assistantId: String? = null
    var answer = ""
    var reasoning = ""
    var terminalError: ErrorPart? = null
    var metadata = "{}"
    private var lastCheckpoint = 0L
    val observed = linkedMapOf<String, ToolCallPart>()
    private var lastUiFlush = 0L

    fun flushStreamingAnswer(id: String?, text: String, force: Boolean, reasoningText: String = reasoning) {
        val now = nowMillis()
        if (!force && now - lastUiFlush < 50) return
        lastUiFlush = now
        onFlush(id, text, reasoningText, force)
    }

    suspend fun beginModelResponse(initialAnswer: String, messageId: String) {
        if (assistantId != null) checkpoint("COMPLETE")
        observed.clear()
        reasoning = ""
        terminalError = null
        answer = initialAnswer
        assistantId = withContext(Dispatchers.IO) {
            conversations.append(conversationId, MessageRole.ASSISTANT, answer,
                status = "STREAMING", metadataJson = metadata, messageId = messageId).id
        }
    }

    suspend fun appendText(delta: String, secrets: List<String>) {
        answer = SecretRedactor.redact(answer + delta, secrets)
        flushStreamingAnswer(assistantId, answer, force = false)
        checkpointIfDue()
    }

    suspend fun appendReasoning(delta: String, secrets: List<String>) {
        if (delta.isEmpty()) return
        reasoning = appendDeclaredReasoning(reasoning, delta, secrets)
        flushStreamingAnswer(assistantId, answer, force = false, reasoningText = reasoning)
        checkpointIfDue()
    }

    private suspend fun checkpointIfDue() {
        if (nowMillis() - lastCheckpoint >= 500) {
            checkpoint()
            lastCheckpoint = nowMillis()
        }
    }

    suspend fun checkpoint(status: String = "STREAMING") {
        val id = assistantId ?: return
        val parts = buildList<MessagePart> {
            if (answer.isNotEmpty()) add(TextPart(answer))
            if (reasoning.isNotEmpty()) add(ReasoningPart(reasoning, streaming = status == "STREAMING"))
            terminalError?.let(::add)
            addAll(observed.values)
            addAll(citations.values.filter { it.first.runId == runId }.map { CitationPart(it.first.citationId) })
        }
        withContext(NonCancellable + Dispatchers.IO) {
            conversations.checkpointAssistant(id, answer, parts, metadata, status)
        }
    }

    suspend fun persistTerminalError(part: ErrorPart) {
        terminalError = part
        if (assistantId == null) {
            val message = withContext(Dispatchers.IO) {
                conversations.append(
                    conversationId, MessageRole.ASSISTANT, part.message, status = "ERROR",
                    parts = listOf(part), metadataJson = metadata,
                )
            }
            assistantId = message.id
            onAppend(message)
        } else {
            checkpoint("ERROR")
        }
    }
}
