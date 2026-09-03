// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.InlineImage
import runtime.mobileagent.provider.ModelEvent as ProviderModelEvent
import runtime.mobileagent.provider.ParameterLayers
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.domain.DiffPart
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.domain.MessagePart
import runtime.mobileagent.domain.ReasoningPart
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult

/** Inputs for the structured runtime boundary. */
data class AgentRuntimeRequest(
    val run: AgentRun,
    val prompt: EffectivePrompt,
    val modelId: String,
    val secret: CharArray,
    val toolsEnabled: Boolean,
    val parameters: ParameterLayers = ParameterLayers(),
    val headers: Map<String, RequestHeaderValue> = emptyMap(),
    val operationId: String = run.runId,
    val emitRequestPreview: Boolean = false,
    /** Optional executor for this run; it takes precedence over constructor compatibility bridges. */
    val executor: ToolExecutor? = null,
    /**
     * Optional visual evidence returned by a tool.  The runtime places every
     * returned image in a following user multimodal message.  The callback is
     * intentionally owned by the caller so policy, asset references and byte
     * budgets remain outside the protocol adapter.
     */
    val toolImages: suspend (ToolCall, ToolResult) -> List<InlineImage> = { _, _ -> emptyList() },
    /** Conservative text UTF-8 units plus 4096 units per image; checked every round. */
    val maxInputBudgetUnits: Long? = null,
    val maxImagesPerRequest: Int = 4,
    val beforeModelRequest: suspend () -> Unit = {},
    val outputTokenLimit: Int? = null,
)

/** A bounded summary safe for persistence and inspector lists. */
data class RuntimeMessageSummary(
    val role: String,
    val textChars: Int,
    val imageAssetIds: List<String>,
    val toolCallIds: List<String>,
)

/** Structured, persistence-friendly runtime events.  Bodies and secrets are opt-in only. */
sealed interface RuntimeEvent {
    data class RunStarted(
        val runId: String,
        val snapshotId: String,
        val conversationId: String,
    ) : RuntimeEvent

    data class RequestPrepared(
        val operationId: String,
        val modelId: String,
        val messages: List<RuntimeMessageSummary>,
        val toolNames: List<String>,
        val parameterKeys: List<String>,
        val headerNames: List<String>,
        /** Null unless the caller explicitly opts into an in-memory inspector preview. */
        val requestPreview: String? = null,
    ) : RuntimeEvent

    data class ModelEvent(
        val event: ProviderModelEvent,
    ) : RuntimeEvent

    data class ToolApprovalRequested(
        val callId: String,
        val name: String,
        val argumentsJson: String,
    ) : RuntimeEvent

    data class ToolCallObserved(
        val callId: String,
        val name: String,
        val argumentsJson: String,
    ) : RuntimeEvent

    data class ToolResultProduced(
        val callId: String,
        val name: String,
        val status: String,
        val resultSummary: String,
        /** Complete bounded, redacted result for typed persistence/replay. */
        val resultJson: String = resultSummary,
    ) : RuntimeEvent

    data class ToolImagesAttached(val callId: String, val assets: List<RuntimeImageReference>) : RuntimeEvent

    data class RunFinished(
        val runId: String,
        val state: RunState,
        val stopReason: String?,
        val modelRounds: Int,
        val toolCalls: Int,
    ) : RuntimeEvent
}

data class RuntimeImageReference(val assetId: String, val mediaType: String)

internal fun ChatMessage.toRuntimeSummary(): RuntimeMessageSummary = RuntimeMessageSummary(
    role = role,
    textChars = text.length,
    imageAssetIds = images.mapNotNull { it.assetId },
    toolCallIds = toolCalls.map { it.id } + listOfNotNull(toolCallId),
)

internal fun ParameterLayers.allKeys(): List<String> = buildList {
    addAll(adapterDefaults.keys)
    addAll(modelParameters.keys)
    addAll(agentOverrides.keys)
    if (!customJson.isNullOrBlank()) add("<custom-json>")
}.distinct().sorted()

/**
 * Project only provider-declared message parts.  In particular, ordinary answer text is never
 * reclassified as reasoning: providers must emit [ProviderModelEvent.ReasoningDelta] explicitly.
 */
fun ProviderModelEvent.toMessagePartOrNull(): MessagePart? = when (this) {
    is ProviderModelEvent.ReasoningDelta ->
        text.takeIf { it.isNotBlank() }?.let { ReasoningPart(it, streaming = true) }
    is ProviderModelEvent.Failed -> toSafeErrorPart(sanitizedMessage)
    else -> null
}

/** Convert provider/runtime failure labels into a closed, non-sensitive durable error. */
fun toSafeErrorPart(value: String): ErrorPart {
    val normalized = value.trim().uppercase()
    val token = normalized
        .substringBefore(':')
        .substringBefore(' ')
    val code = when {
        token == "UNKNOWN_OUTCOME" || normalized.contains("UNKNOWN_OUTCOME") -> MessageErrorCode.UNKNOWN_OUTCOME
        token == "INVALID_CONFIG" || token == "CONFIG_INVALID" -> MessageErrorCode.CONFIG_INVALID
        token == "SECRET_UNAVAILABLE" -> MessageErrorCode.SECRET_UNAVAILABLE
        token == "PROVIDER_UNAUTHORIZED" || normalized.contains("UNAUTHORIZED") -> MessageErrorCode.PROVIDER_UNAUTHORIZED
        token == "RATE_LIMITED" -> MessageErrorCode.RATE_LIMITED
        token == "NETWORK_UNAVAILABLE" || normalized.contains("NETWORK") -> MessageErrorCode.NETWORK_UNAVAILABLE
        token == "CONTEXT_OVERFLOW" || token == "CONTEXT_BUDGET_EXCEEDED" || normalized.contains("CONTEXT") -> MessageErrorCode.CONTEXT_OVERFLOW
        token == "PERMISSION_DENIED" || token == "CAPABILITY_DENIED" -> MessageErrorCode.PERMISSION_DENIED
        token == "RESOURCE_LIMIT" || token == "BUDGET_EXHAUSTED" || normalized.contains("BUDGET") -> MessageErrorCode.BUDGET_EXHAUSTED
        token == "TIMEOUT" || token == "TIMED_OUT" -> MessageErrorCode.TIMEOUT
        token == "CANCELLED" || token == "CANCELED" -> MessageErrorCode.CANCELLED
        token == "INVALID_RESPONSE" || token == "SCHEMA_UNSUPPORTED" || normalized.contains("SCHEMA") -> MessageErrorCode.INVALID_RESPONSE
        token == "TOOL_FAILED" || token == "TOOL_ERROR" -> MessageErrorCode.TOOL_FAILED
        else -> MessageErrorCode.INTERNAL
    }
    return ErrorPart(code, code.safeMessage(), retryable = code in RETRYABLE_ERROR_CODES)
}

/**
 * Diff parts are accepted only from an explicit structured tool result.  A normal result string,
 * patch argument, or assistant answer is intentionally not enough evidence to create a diff.
 */
fun RuntimeEvent.ToolResultProduced.toDiffPartOrNull(): DiffPart? {
    val objectValue = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
    val kind = listOf("type", "kind", "result_type", "resultType")
        .asSequence()
        .mapNotNull { objectValue[it]?.jsonPrimitive?.contentOrNull }
        .firstOrNull { it.equals("diff", ignoreCase = true) }
        ?: return null
    if (kind.isBlank()) return null
    val summary = listOf("summary", "diff_summary", "diffSummary")
        .asSequence()
        .mapNotNull { objectValue[it]?.jsonPrimitive?.contentOrNull }
        .firstOrNull { it.isNotBlank() }
        ?: return null
    val preview = listOf("patch_preview", "patchPreview", "patch")
        .asSequence()
        .mapNotNull { objectValue[it]?.jsonPrimitive?.contentOrNull }
        .firstOrNull()
        ?: ""
    val changedFiles = listOf("changed_files", "changedFiles")
        .asSequence()
        .mapNotNull { objectValue[it]?.jsonPrimitive?.intOrNull }
        .firstOrNull()
        ?: 0
    return runCatching { DiffPart(summary, preview, changedFiles) }.getOrNull()
}

/** Project only closed workspace/tool error codes into safe, actionable UI copy. */
fun toolResultUserMessage(resultJson: String): String? {
    val root = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
    val error = root["error"] as? JsonObject ?: return null
    return when (error["code"]?.jsonPrimitive?.contentOrNull) {
        "FILE_TOO_LARGE" -> "文件太大，无法作为文本读取。"
        "INVALID_CURSOR" -> "目录内容已发生变化，请从第一页重新列举。"
        "PERMISSION_DENIED" -> "没有权限访问该工作区，请检查工作区授权。"
        "SYMLINK_FORBIDDEN" -> "不允许从工作区跟随符号链接。"
        "PATH_OUT_OF_SCOPE" -> "请求路径超出已授权工作区。"
        "WORKSPACE_NOT_FOUND" -> "工作区或目标条目已不可用。"
        "AUTHORITY_TEMPORARILY_UNAVAILABLE",
        "BRIDGE_DISCONNECTED",
        "ADB_DEVICE_OFFLINE",
        "ADB_DEVICE_DISCONNECTED",
            -> "工作区暂时不可用，请重新连接后重试。"
        "QUOTA_EXCEEDED" -> "操作超过工作区大小或输出限制。"
        "CONFLICT" -> "工作区内容已变化，请读取最新状态后重试。"
        "UNSUPPORTED_ENTRY" -> "该工作区条目类型不受支持，未打开该条目。"
        "OPERATION_UNAVAILABLE" -> "所选工作区后端暂不支持该操作。"
        else -> null
    }
}

private val RETRYABLE_ERROR_CODES = setOf(
    MessageErrorCode.NETWORK_UNAVAILABLE,
    MessageErrorCode.RATE_LIMITED,
    MessageErrorCode.TIMEOUT,
)

private fun MessageErrorCode.safeMessage(): String = when (this) {
    MessageErrorCode.CONFIG_INVALID -> "配置无效。"
    MessageErrorCode.SECRET_UNAVAILABLE -> "服务商凭据不可用。"
    MessageErrorCode.PROVIDER_UNAUTHORIZED -> "服务商拒绝了请求，请检查授权。"
    MessageErrorCode.RATE_LIMITED -> "服务商暂时限流，请稍后再试。"
    MessageErrorCode.NETWORK_UNAVAILABLE -> "网络不可用，未能完成请求。"
    MessageErrorCode.CONTEXT_OVERFLOW -> "上下文或输出预算不足。"
    MessageErrorCode.PERMISSION_DENIED -> "当前权限不允许执行该操作。"
    MessageErrorCode.WORKSPACE_UNAVAILABLE -> "工作区当前不可用。"
    MessageErrorCode.RESOURCE_LIMIT -> "已达到运行资源限制。"
    MessageErrorCode.BUDGET_EXHAUSTED -> "已达到运行预算。"
    MessageErrorCode.TIMEOUT -> "请求超时。"
    MessageErrorCode.CANCELLED -> "请求已取消。"
    MessageErrorCode.INVALID_RESPONSE -> "服务商返回了无法识别的结果。"
    MessageErrorCode.TOOL_FAILED -> "工具执行失败。"
    MessageErrorCode.UNKNOWN_OUTCOME -> "请求结果未知，可能已产生外部影响；不会自动重试。"
    MessageErrorCode.INTERNAL -> "运行时发生内部错误。"
}
