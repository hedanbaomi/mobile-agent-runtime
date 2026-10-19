// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.agent.ContextPreflight
import runtime.mobileagent.agent.EffectivePrompt
import runtime.mobileagent.agent.RuntimeContext
import runtime.mobileagent.agent.worldbook.WorldBookEngine
import runtime.mobileagent.agent.worldbook.WorldBookPromptComposer
import runtime.mobileagent.agent.worldbook.WorldBookPromptPlan
import runtime.mobileagent.data.WorldBookRepository
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelRequest

internal data class PreparedWorldBookRequest(val prompt: EffectivePrompt, val request: ModelRequest, val evaluation: WorldBookEvaluation)

/** Runs on the storage dispatcher. The engine is local and uses no extra provider request. */
internal object WorldBookRunPreparation {
    private val json = Json { encodeDefaults = true }

    fun prepare(
        frozen: PreparedWorldBooks,
        prompt: EffectivePrompt,
        runtimeContext: RuntimeContext,
        request: ModelRequest,
        adapter: ModelAdapter,
        inputBudget: Int,
        history: List<Message>,
        agentName: String,
        runId: String,
        repository: WorldBookRepository,
    ): PreparedWorldBookRequest {
        val context = frozen.context.copy(
            characterName = frozen.context.characterName.ifBlank { agentName },
            characterDescription = frozen.context.characterDescription.ifBlank { prompt.userSystemPrompt },
            availableOutlets = WorldBookPromptComposer.outletNames(prompt.userSystemPrompt) +
                WorldBookPromptComposer.outletNames(prompt.globalRootPrompt),
        )
        val messages = scanMessages(history, agentName, context.userName) + WorldBookScanMessage(prompt.currentUser, "user", context.userName)
        val seed = seed(runId)
        val scan = WorldBookScanRequest(frozen.sources, messages, frozen.settings, context, messages.size,
            previousEffects = json.decodeFromString(repository.previousEffects(frozen.conversationId, frozen.bindingRevision)), seed = seed)
        val base = minimum(prompt, runtimeContext, request)
        val fixedUnits = adapter.estimateInput(base).units
        var hardBudget = (inputBudget.toLong() - fixedUnits).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val softBudget = budget(frozen.settings, inputBudget)
        val emptyUnits = adapter.estimateInput(request.copy(messages = emptyList())).units
        val estimates = HashMap<String, Int>()
        val estimate: (String) -> Int = { content -> estimates.getOrPut(content) {
            val message = ChatMessage("system", WorldBookPromptComposer.wrap(content))
            (adapter.estimateInput(request.copy(messages = listOf(message))).units - emptyUnits)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        } }
        var evaluation = WorldBookEngine.evaluate(scan, minOf(softBudget, hardBudget), hardBudget, estimate)
        var selected = project(prompt, request, evaluation, context)
        // Account for placement/role envelopes with the same adapter that will send the request.
        // Re-evaluation keeps the same seed and pre-run effect state; it cannot re-roll a winner.
        repeat(3) {
            val actual = adapter.estimateInput(minimum(selected.prompt, runtimeContext, selected.request)).units
            val excess = (actual - inputBudget).coerceAtLeast(0)
            if (excess > 0 && evaluation.activated.isNotEmpty()) {
                hardBudget = (hardBudget.toLong() - excess - 64).coerceAtLeast(0).toInt()
                evaluation = WorldBookEngine.evaluate(scan, minOf(softBudget, hardBudget), hardBudget, estimate)
                selected = project(prompt, request, evaluation, context)
            }
        }
        val actual = adapter.estimateInput(minimum(selected.prompt, runtimeContext, selected.request)).units
        if (actual > inputBudget && evaluation.activated.isNotEmpty()) {
            // The unchanged base request is left to the existing fixed-context overflow gate.
            evaluation = WorldBookEngine.evaluate(scan, 0, 0, estimate)
            selected = project(prompt, request, evaluation, context)
        }
        val unavailable = frozen.unavailable.map {
            WorldBookDecision(it.bookId, "", it.name, WorldBookDecisionStatus.INELIGIBLE, it.reason)
        }
        evaluation = evaluation.copy(decisions = evaluation.decisions + unavailable)
        val receipt = evaluation.copy(activated = evaluation.activated.map { it.copy(content = "") })
        repository.recordEvaluation(runId, frozen, json.encodeToString(receipt), json.encodeToString(evaluation.nextEffects),
            messages.size, minOf(softBudget, hardBudget), seed)
        return selected.copy(evaluation = evaluation)
    }

    private fun project(prompt: EffectivePrompt, request: ModelRequest, evaluation: WorldBookEvaluation,
        context: WorldBookContext): PreparedWorldBookRequest {
        val projected = prompt.copy(worldBooks = WorldBookPromptPlan(evaluation.activated, context))
        return PreparedWorldBookRequest(projected, request.copy(messages = projected.asMessages()), evaluation)
    }

    private fun minimum(prompt: EffectivePrompt, context: RuntimeContext, request: ModelRequest): ModelRequest =
        if (context.policy.autoCompact) ContextPreflight.minimumRequest(prompt, context, request) else request

    fun budget(settings: WorldBookSettings, inputBudget: Int): Int {
        val percent = (inputBudget.toLong() * settings.budgetPercent.coerceIn(0, 100) / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (settings.budgetCap > 0) minOf(percent, settings.budgetCap) else percent
    }

    fun scanMessages(history: List<Message>, agentName: String, userName: String): List<WorldBookScanMessage> = history.mapNotNull { message ->
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) return@mapNotNull null
        val text = message.text.ifBlank { message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.value } }
        if (text.isBlank() && message.role == MessageRole.ASSISTANT) return@mapNotNull null
        WorldBookScanMessage(text, message.role.name.lowercase(), if (message.role == MessageRole.USER) userName else agentName)
    }

    private fun seed(id: String): Long = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(8)
        .fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
}
