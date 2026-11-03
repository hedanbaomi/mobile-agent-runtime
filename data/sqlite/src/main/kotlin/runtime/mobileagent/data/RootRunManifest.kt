// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import runtime.mobileagent.domain.DiffPart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.domain.ToolCallPart
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.ToolResultPart

/** Reserved local Run fact. Unknown manifest fields remain intact; absent means no Root admission. */
object RootRunManifest {
    private const val STAMP = "rootExecutionStamp"

    fun hasRootStamp(manifestJson: String): Boolean =
        runCatching { STAMP in parse(manifestJson) }.getOrDefault(false)

    fun freeze(manifestJson: String, execution: RootExecutionStamp): String {
        val manifest = parse(manifestJson)
        require(STAMP !in manifest) { "Root execution stamp must be frozen once at Run creation" }
        return JsonObject(manifest + (STAMP to Json.encodeToJsonElement(execution))).toString()
    }

    fun requireMatches(manifestJson: String, execution: RootExecutionStamp) {
        val value = requireNotNull(parse(manifestJson)[STAMP]) { "Run has no Root admission stamp" }
        require(Json.decodeFromJsonElement<RootExecutionStamp>(value) == execution) {
            "Root execution does not match the Run admission stamp"
        }
    }

    fun unchanged(before: String, after: String): Boolean = parse(before)[STAMP] == parse(after)[STAMP]

    /** Platform/session admission is local-only, including when exporting historical Runs. */
    fun forTransfer(manifestJson: String): String {
        val manifest = parse(manifestJson)
        return if (STAMP in manifest) JsonObject(manifest - STAMP).toString() else manifestJson
    }

    internal fun rootInvocationIds(db: SqlConnection, runs: List<RunRecord>, invocations: List<ToolInvocation>): Set<String> {
        val rootRunIds = runs.filter { hasRootStamp(it.manifestJson) }.mapTo(linkedSetOf()) { it.runId }
        val boundIds = db.query(
            "SELECT invocation_id FROM root_approval_bindings UNION SELECT invocation_id FROM root_dispatch_receipts",
        ).mapTo(linkedSetOf()) { it.string("invocation_id") }
        return invocations.filter { it.runId in rootRunIds || it.invocationId in boundIds }
            .mapTo(linkedSetOf()) { it.invocationId }
    }

    internal fun sanitizeInvocationsForTransfer(
        db: SqlConnection,
        runs: List<RunRecord>,
        invocations: List<ToolInvocation>,
    ): List<ToolInvocation> {
        val rootIds = rootInvocationIds(db, runs, invocations)
        if (rootIds.isEmpty()) return invocations
        return invocations.map { inv ->
            if (inv.invocationId !in rootIds) inv
            else inv.copy(
                argumentsJson = "{}",
                resultJson = null,
                permissionDecision = if (inv.permissionDecision == "PENDING") "DENIED" else inv.permissionDecision,
                state = if (inv.state.startsWith("ROOT_DISPATCH_") || inv.state in setOf("PENDING", "WAITING_APPROVAL")) {
                    "CANCELLED"
                } else {
                    inv.state
                },
            )
        }
    }

    internal fun sanitizeMessagesForTransfer(
        db: SqlConnection,
        runs: List<RunRecord>,
        invocations: List<ToolInvocation>,
        messages: List<Message>,
    ): List<Message> {
        val hasRootRun = runs.any { hasRootStamp(it.manifestJson) }
        val rootIds = rootInvocationIds(db, runs, invocations)
        if (!hasRootRun && rootIds.isEmpty()) return messages
        val rootCallIds = invocations.filter { it.invocationId in rootIds }.mapTo(linkedSetOf()) { it.callId }
        val nonRootCallIds = invocations.filter { it.invocationId !in rootIds }.mapTo(linkedSetOf()) { it.callId }
        fun isRootCall(callId: String, toolName: String? = null): Boolean =
            callId in rootCallIds ||
                (hasRootRun && (callId !in nonRootCallIds || (toolName != null && RootApprovalTools.capability(toolName) != null)))
        return messages.map { msg -> sanitizeMessage(msg, ::isRootCall) }
    }

    private fun sanitizeMessage(msg: Message, isRootCall: (String, String?) -> Boolean): Message {
        val touchesRoot = msg.parts.any { part ->
            (part is ToolCallPart && isRootCall(part.callId, part.name)) ||
                (part is ToolResultPart && isRootCall(part.callId, null))
        }
        if (!touchesRoot) return msg
        val parts = msg.parts.mapNotNull { part ->
            when {
                part is ToolCallPart && isRootCall(part.callId, part.name) -> part.copy(argumentsJson = "{}")
                part is ToolResultPart && isRootCall(part.callId, null) -> part.copy(
                    resultJson = "{}",
                    status = if (part.status.startsWith("ROOT_DISPATCH_") || part.status in setOf("PENDING", "WAITING_APPROVAL")) {
                        "CANCELLED"
                    } else {
                        part.status
                    },
                )
                part is TextPart && msg.role == MessageRole.TOOL -> TextPart("{}")
                part is DiffPart -> null
                else -> part
            }
        }
        return msg.copy(
            text = if (msg.role == MessageRole.TOOL) "{}" else msg.text,
            parts = parts,
        )
    }

    private fun parse(raw: String): JsonObject =
        requireNotNull(Json.parseToJsonElement(raw) as? JsonObject) { "Run manifest must be a JSON object" }
}
