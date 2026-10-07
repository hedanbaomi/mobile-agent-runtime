// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Session-snapshotted context settings. Units are conservative estimates, not tokenizer counts. */
/** Smallest input allowance kept when the upstream window is unknown. */
private const val LOCAL_FLOOR_INPUT_UNITS = 1_024

/** Local attachment ceiling; model-window and explicit user budgets still apply. */
const val MAX_CONTEXT_IMAGES = 64
const val MAX_IMAGES_PER_REQUEST = 8
/** Local memory protection, separate from the model's image/token count. */
const val MAX_CONTEXT_IMAGE_BYTES = 16L * 1024 * 1024
const val DEFAULT_RUN_MODEL_REQUESTS = 128
const val DEFAULT_RUN_TOOL_CALLS = 100
const val DEFAULT_RUN_RUNTIME_MS = 1_800_000
const val MAX_RUN_RUNTIME_MS = 86_400_000

data class AgentContextPolicy(
    val autoCompact: Boolean = true,
    val maxInputTokens: Int? = null,
    val maxHistoryMessages: Int = 20,
    val maxHistoryTurns: Int = 10,
    val keepRecentTurns: Int = 2,
    val softLimitPercent: Int = 85,
    val targetPercent: Int = 60,
    val maxModelRoundsPerSegment: Int = 32,
    val maxModelRequestsPerRun: Int = DEFAULT_RUN_MODEL_REQUESTS,
    val maxCompactionsPerRun: Int = 16,
    val summaryOutputTokens: Int = 4096,
    val summaryMaxUnits: Int = 16384,
    val reservedOutputTokens: Int? = null,
    /**
     * The user's own per-Run fee ceiling for Python `model.invoke`.  Absent means
     * the Run carries no model-token authorization at all, so a bound skill with
     * an approved `model.invoke` grant still cannot spend: the install approval
     * and the Run authorization are separate decisions.
     */
    val pythonModelRunTokens: Int? = null,
    val knowledgeTokenBudget: Int = 3000,
    val imageBudget: Int = MAX_CONTEXT_IMAGES,
    /**
     * Local context-protection reserve used when the provider output cap is
     * unknown (AUTO).  It is a local policy number: it protects the input budget
     * and history compaction and is never sent upstream as an output cap.
     */
    val localOutputReserve: Int = 1024,
    /**
     * Local protection ceiling used while the upstream context window is
     * unknown.  It is a *local* policy, not a claim about the model: it keeps
     * the input budget finite and history compaction enabled, and it is never
     * sent to the provider (which does not accept a window parameter).
     */
    val localUnknownWindow: Int = 16_384,
    /** Capacity-based mode ignores fixed message/turn/segment triggers, including old defaults. */
    val modelAwareCompaction: Boolean = true,
    val maxToolCalls: Int = DEFAULT_RUN_TOOL_CALLS,
    /** Admission only; dispatched work still uses its independent stall/cancellation boundary. */
    val maxRuntimeMs: Int = DEFAULT_RUN_RUNTIME_MS,
) {
    init {
        require(maxInputTokens == null || maxInputTokens > 0) { "maxInputTokens must be positive" }
        require(maxHistoryMessages in 1..50_000) { "maxHistoryMessages must be 1..50000" }
        require(maxHistoryTurns in 1..10_000) { "maxHistoryTurns must be 1..10000" }
        require(keepRecentTurns in 1..20) { "keepRecentTurns must be 1..20" }
        require(softLimitPercent in 50..95) { "softLimitPercent must be 50..95" }
        require(targetPercent in 20 until softLimitPercent) { "targetPercent must be below the soft limit" }
        require(maxModelRoundsPerSegment in 2..128) { "maxModelRoundsPerSegment must be 2..128" }
        require(maxModelRequestsPerRun in 2..512) { "maxModelRequestsPerRun must be 2..512" }
        require(maxCompactionsPerRun in 1..64) { "maxCompactionsPerRun must be 1..64" }
        require(maxToolCalls in 1..1000) { "maxToolCalls must be 1..1000" }
        require(maxRuntimeMs in 1..MAX_RUN_RUNTIME_MS) { "maxRuntimeMs must be 1..$MAX_RUN_RUNTIME_MS" }
        require(summaryOutputTokens in 128..8192) { "summaryOutputTokens must be 128..8192" }
        require(summaryMaxUnits in 512..65_536) { "summaryMaxUnits must be 512..65536" }
        require(reservedOutputTokens == null || reservedOutputTokens > 0) { "reservedOutputTokens must be positive" }
        require(pythonModelRunTokens == null || pythonModelRunTokens in 1..10_000_000) {
            "pythonModelRunTokens must be 1..10000000"
        }
        require(knowledgeTokenBudget > 0) { "knowledgeTokenBudget must be positive" }
        require(imageBudget in 1..MAX_CONTEXT_IMAGES) { "imageBudget must be 1..$MAX_CONTEXT_IMAGES" }
    }

    /**
     * The reserve subtracted from the context window.  A provider cap (MANUAL)
     * and the local policy reserve (used when the cap is unknown) are separate
     * concepts: only the former is ever sent upstream as an output limit.
     */
    /** Smallest input allowance kept when the upstream window is unknown. */

    fun outputReserve(outputLimit: Int?): Long {
        val providerReserve = outputLimit?.toLong() ?: 0L
        val localReserve = (reservedOutputTokens ?: localOutputReserve).toLong()
        return maxOf(providerReserve, localReserve)
    }

    /**
     * Input budget under the current output settings.  An absent provider cap
     * (AUTO) must not disable compaction or make the input window look
     * unlimited: the local reserve still applies.
     */
    fun inputLimit(contextWindow: Int?, outputLimit: Int?): Long {
        val reserve = outputReserve(outputLimit)
        // An unknown upstream window is not unlimited: the local protection
        // ceiling keeps the input budget finite so compaction still runs.
        // Unknown upstream window is NOT a known 16k window: the local
        // protection floor grows so a user's manual output cap cannot make the
        // input budget arithmetic fail.  This is local policy, never sent upstream
        // and never presented as the provider's window.
        val window = (contextWindow ?: maxOf(localUnknownWindow, reserve.toInt() + LOCAL_FLOOR_INPUT_UNITS)).toLong()
        val available = window - reserve
        require(available > 0) { "Model window must leave space after the output reservation" }
        return minOf(maxInputTokens?.toLong() ?: available, available)
    }

    companion object {
        /** Unknown fields survive in the stored JSON; malformed known fields never silently relax limits. */
        fun fromJson(raw: String): AgentContextPolicy {
            val obj = Json.parseToJsonElement(raw) as? JsonObject
                ?: throw IllegalArgumentException("Context policy must be an object")
            fun int(name: String, default: Int): Int = obj[name]?.let {
                val value = it as? JsonPrimitive
                require(value != null && !value.isString) { "$name must be an integer" }
                value.intOrNull ?: throw IllegalArgumentException("$name must be an integer")
            } ?: default
            fun optional(name: String): Int? = if (name in obj) int(name, 0) else null
            fun bool(name: String, default: Boolean): Boolean = obj[name]?.let {
                val value = it as? JsonPrimitive
                require(value != null && !value.isString) { "$name must be boolean" }
                value.booleanOrNull ?: throw IllegalArgumentException("$name must be boolean")
            } ?: default
            return AgentContextPolicy(
                autoCompact = bool("autoCompact", true), maxInputTokens = optional("maxInputTokens"),
                maxHistoryMessages = int("maxHistoryMessages", 20), maxHistoryTurns = int("maxHistoryTurns", 10),
                keepRecentTurns = int("keepRecentTurns", 2), softLimitPercent = int("softLimitPercent", 85),
                targetPercent = int("targetPercent", 60), maxModelRoundsPerSegment = int("maxModelRoundsPerSegment", 32),
                maxModelRequestsPerRun = int("maxModelRequestsPerRun", DEFAULT_RUN_MODEL_REQUESTS), maxCompactionsPerRun = int("maxCompactionsPerRun", 16),
                summaryOutputTokens = int("summaryOutputTokens", 4096), summaryMaxUnits = int("summaryMaxUnits", 16384),
                reservedOutputTokens = optional("reservedOutputTokens"),
                pythonModelRunTokens = optional("pythonModelRunTokens"), knowledgeTokenBudget = int("knowledgeTokenBudget", 3000),
                imageBudget = int("imageBudget", MAX_CONTEXT_IMAGES), localOutputReserve = int("localOutputReserve", 1024),
                localUnknownWindow = int("localUnknownWindow", 16_384),
                modelAwareCompaction = bool("modelAwareCompaction", true),
                maxToolCalls = int("maxToolCalls", DEFAULT_RUN_TOOL_CALLS),
                maxRuntimeMs = int("maxRuntimeMs", DEFAULT_RUN_RUNTIME_MS),
            )
        }
    }
}
