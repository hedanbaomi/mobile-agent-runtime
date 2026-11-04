// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.serialization.json.*
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolSpec

/** Common model protocol. Callers supply only live-authorized views, never raw descriptors. */
object WorkspaceDiscovery {
    const val NAME = "workspace_list"
    val spec = ToolSpec(NAME, "List authorized workspaces.",
        """{"type":"object","additionalProperties":false,"properties":{}}""",
        CapabilityId(CapabilityId.WORKSPACE_ENUMERATE), false, UnifiedWorkspaceToolExecutor.TOOL_SCHEMA_VERSION)

    fun invoke(call: ToolCall, views: List<AgentWorkspaceView>): ToolResult {
        if (call.callId.isBlank() || call.name != NAME || !validArguments(call.argumentsJson)) {
            return ToolResult.Invalid("INVALID_REQUEST")
        }
        if (views.isEmpty()) return ToolResult.Denied("CAPABILITY_DENIED")
        return ToolResult.Value(render(views))
    }

    fun validArguments(arguments: String): Boolean =
        ModelToolArguments.objectOrNull(arguments, 1024, 2, 4)?.isEmpty() == true

    fun render(views: List<AgentWorkspaceView>): String = buildJsonObject {
        put("success", true)
        putJsonArray("workspaces") { views.forEach { add(it.toJson()) } }
    }.toString()

    fun combine(values: List<ToolResult.Value>): ToolResult = runCatching {
        val entries = values.flatMap { Json.parseToJsonElement(it.json).jsonObject.getValue("workspaces").jsonArray }
        val ids = entries.map { it.jsonObject.getValue("workspace_id").jsonPrimitive.content }
        if (ids.distinct().size != ids.size) return ToolResult.Denied("CAPABILITY_DENIED")
        ToolResult.Value(buildJsonObject { put("success", true); put("workspaces", JsonArray(entries)) }.toString())
    }.getOrElse { ToolResult.Invalid("INVALID_REQUEST") }
}

/** Model-facing workspace entry; never built from a raw backend descriptor. */
data class AgentWorkspaceView(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val workspaceScope: String,
    val readable: Boolean,
    val writable: Boolean,
    val atomicReplace: Boolean,
    val createOnlyOperations: List<String>,
    val authorizedOperations: List<String>,
    val expectedVersionOperations: List<String>,
    val operationScopes: Map<String, OperationScopeView>,
    val operationConditions: Map<String, String> = emptyMap(),
    val patchFormats: List<String> = emptyList(),
    val conditionalExpectedVersionOperations: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("workspace_id", id)
        put("display_name", displayName)
        put("enabled", enabled)
        put("readable", readable)
        put("writable", writable)
        put("workspace_scope", workspaceScope)
        put("atomic_replace", atomicReplace)
        putJsonArray("create_only_operations") { createOnlyOperations.forEach { add(it) } }
        putJsonArray("authorized_operations") { authorizedOperations.forEach { add(it) } }
        put("version_scope", "target_entry")
        put("version_token_type", "opaque_string")
        putJsonObject("operation_conditions") { operationConditions.forEach { (tool, condition) -> put(tool, condition) } }
        putJsonArray("patch_formats") { patchFormats.forEach { add(it) } }
        putJsonArray("expected_version_operations") { expectedVersionOperations.forEach { add(it) } }
        putJsonArray("conditional_expected_version_operations") { conditionalExpectedVersionOperations.forEach { add(it) } }
        putJsonObject("operation_scopes") {
            operationScopes.forEach { (toolName, scope) ->
                putJsonObject(toolName) {
                    put("whole_workspace", scope.wholeWorkspace)
                    putJsonArray("path_scopes") { scope.pathScopes.forEach { add(it) } }
                }
            }
        }
    }
}

/** One operation's resolved scope: whole-workspace, bounded relative scopes, or neither. */
data class OperationScopeView(
    val wholeWorkspace: Boolean,
    val pathScopes: List<String>,
)
