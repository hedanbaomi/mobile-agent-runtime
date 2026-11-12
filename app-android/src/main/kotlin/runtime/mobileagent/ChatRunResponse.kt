// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.domain.*
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.provider.openai.StreamingSecretRedactor

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

    private val answerBuilder = StringBuilder()
    var answer: String
        get() = answerBuilder.toString()
        set(value) {
            answerBuilder.setLength(0)
            answerBuilder.append(value)
        }

    private val reasoningBuilder = StringBuilder()
    var reasoning: String
        get() = reasoningBuilder.toString()
        set(value) {
            reasoningBuilder.setLength(0)
            appendBoundedReasoning(reasoningBuilder, value)
        }

    var terminalError: ErrorPart? = null
    var metadata = "{}"
    private var lastCheckpoint = 0L
    val observed = linkedMapOf<String, ToolCallPart>()
    private var lastUiFlush = 0L

    private val redactor = StreamingSecretRedactor()

    internal fun pendingRedactorLength(): Int = redactor.pendingLength()

    private fun settleTails(tails: List<Pair<StreamingSecretRedactor.Channel, String>>) {
        for ((channel, tail) in tails) {
            if (tail.isNotEmpty()) {
                when (channel) {
                    StreamingSecretRedactor.Channel.TEXT,
                    StreamingSecretRedactor.Channel.REFUSAL -> answerBuilder.append(tail)
                    StreamingSecretRedactor.Channel.REASONING -> appendBoundedReasoning(reasoningBuilder, tail)
                }
            }
        }
    }

    fun flushStreamingAnswer(id: String? = assistantId, force: Boolean = false) {
        val now = nowMillis()
        if (!force && now - lastUiFlush < 50) return
        lastUiFlush = now
        onFlush(id, answerBuilder.toString(), reasoningBuilder.toString(), force)
    }

    fun flushStreamingAnswer(
        id: String?,
        text: String,
        force: Boolean,
        reasoningText: String? = null,
    ) {
        val now = nowMillis()
        if (!force && now - lastUiFlush < 50) return
        lastUiFlush = now
        onFlush(id, text, reasoningText ?: reasoningBuilder.toString(), force)
    }

    suspend fun beginModelResponse(initialAnswer: String, messageId: String) {
        if (assistantId != null) {
            finish()
            checkpoint("COMPLETE")
        }
        discard()
        observed.clear()
        reasoningBuilder.setLength(0)
        terminalError = null
        answer = initialAnswer
        assistantId = withContext(Dispatchers.IO) {
            conversations.append(
                conversationId, MessageRole.ASSISTANT, initialAnswer,
                status = "STREAMING", metadataJson = metadata, messageId = messageId,
            ).id
        }
    }

    suspend fun appendText(delta: String, secrets: List<String> = emptyList()) {
        if (delta.isEmpty()) return
        redactor.registerSecrets(secrets)
        val clean = redactor.accept(delta, StreamingSecretRedactor.Channel.TEXT)
        if (clean.isNotEmpty()) {
            answerBuilder.append(clean)
        }
        flushStreamingAnswer(assistantId, force = false)
        checkpointIfDue()
    }

    suspend fun appendRefusal(delta: String, secrets: List<String> = emptyList()) {
        if (delta.isEmpty()) return
        redactor.registerSecrets(secrets)
        val clean = redactor.accept(delta, StreamingSecretRedactor.Channel.REFUSAL)
        if (clean.isNotEmpty()) {
            answerBuilder.append(clean)
        }
        flushStreamingAnswer(assistantId, force = false)
        checkpointIfDue()
    }

    suspend fun appendReasoning(delta: String, secrets: List<String> = emptyList()) {
        if (delta.isEmpty()) return
        appendDeclaredReasoning(reasoningBuilder, redactor, delta, secrets)
        flushStreamingAnswer(assistantId, force = false)
        checkpointIfDue()
    }

    private suspend fun checkpointIfDue() {
        val now = nowMillis()
        if (now - lastCheckpoint >= 500) {
            checkpoint()
            lastCheckpoint = now
        }
    }

    suspend fun finish() {
        if (redactor.hasPending()) {
            settleTails(redactor.finish())
        }
        flushStreamingAnswer(assistantId, force = true)
        checkpoint()
        lastCheckpoint = nowMillis()
    }

    fun discard() {
        redactor.discard()
    }

    suspend fun completeRoundForTransition() {
        if (assistantId != null) checkpoint("COMPLETE")
        discard()
        assistantId = null
        answerBuilder.setLength(0)
        reasoningBuilder.setLength(0)
        observed.clear()
        terminalError = null
    }

    suspend fun failModelResponse(projected: ErrorPart): String {
        discard()
        val safeMessage = projected.message
        val current = answerBuilder.toString()
        answer = if (current.isBlank()) safeMessage else current.trimEnd() + "\n" + safeMessage
        terminalError = projected
        flushStreamingAnswer(assistantId, force = true)
        if (assistantId == null) persistTerminalError(projected) else checkpoint("ERROR")
        return safeMessage
    }

    suspend fun finishRun(completed: Boolean) {
        if (completed) {
            finish()
        } else {
            discard()
            flushStreamingAnswer(assistantId, force = true)
            checkpoint()
        }
    }

    suspend fun checkpoint(status: String = "STREAMING") {
        if (status in TERMINAL_DISCARD_STATUSES) {
            discard()
        } else if (status in NORMAL_COMPLETE_STATUSES && redactor.hasPending()) {
            settleTails(redactor.finish())
        }
        val id = assistantId ?: return
        val currentAnswer = answerBuilder.toString()
        val currentReasoning = reasoningBuilder.toString()
        val parts = buildList<MessagePart> {
            if (currentAnswer.isNotEmpty()) add(TextPart(currentAnswer))
            if (currentReasoning.isNotEmpty()) add(ReasoningPart(currentReasoning, streaming = status == "STREAMING"))
            terminalError?.let(::add)
            addAll(observed.values)
            addAll(citations.values.filter { it.first.runId == runId }.map { CitationPart(it.first.citationId) })
        }
        withContext(NonCancellable + Dispatchers.IO) {
            conversations.checkpointAssistant(id, currentAnswer, parts, metadata, status)
        }
    }

    suspend fun persistTerminalError(part: ErrorPart) {
        discard()
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

    private companion object {
        val NORMAL_COMPLETE_STATUSES = setOf("COMPLETE", "COMPLETED", "SUCCEEDED")
        val TERMINAL_DISCARD_STATUSES = setOf(
            "ERROR",
            "CANCELLED",
            "CANCELED",
            "UNKNOWN_OUTCOME",
            "FAILED",
            "BUDGET_EXHAUSTED",
            "TIMED_OUT",
            "TIMEOUT",
            "INTERRUPTED",
        )
    }
}
