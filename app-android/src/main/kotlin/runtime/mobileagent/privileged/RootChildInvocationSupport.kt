// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.skills.RunControlTools
import runtime.mobileagent.skills.tooling.InternalRequestIds
import runtime.mobileagent.tooling.ToolExecutionContext

internal data class ChildInvocationDescriptor(
    val kind: String,
    val index: Int,
    val parentInvocationId: String,
    val childCallId: String,
)

internal object RootChildInvocationSupport {
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val HEX_16 = Regex("[0-9a-f]{16}")
    private val INVALID_PARENT_STATES = setOf("FAILED", "CANCELLED", "COMPLETED_WITHHELD")
    val ACTIVE_RUN_STATES = setOf(
        RunStatus.WAITING_TOOL_APPROVAL.name,
        RunStatus.TOOL_EXECUTING.name,
        RunStatus.MODEL_STREAMING.name,
        RunStatus.RETRIEVING.name,
        RunStatus.ASSEMBLING.name,
        RunStatus.VALIDATING.name,
        "RUNNING",
    )

    fun isReservedChildCallId(callId: String): Boolean =
        callId.startsWith("rchild.") || callId.startsWith("rchild_call.")

    fun isValidApprovalOwner(
        owner: runtime.mobileagent.data.SqlRow?,
        invocationId: String,
        callId: String,
        typedArgs: runtime.mobileagent.domain.RootTypedArguments,
    ): Boolean {
        if (owner == null) return false
        val expectedStates = if (invocationId.startsWith("rchild.")) ACTIVE_RUN_STATES else setOf(RunStatus.WAITING_TOOL_APPROVAL.name)
        val canonical = typedArgs.canonicalJson()
        val rawArgs = owner.string("arguments_json")
        return owner.string("state") in expectedStates &&
            owner.string("call_id") == callId &&
            owner.string("name") == typedArgs.toolName &&
            (rawArgs == canonical || RootToolArguments.normalize(typedArgs.toolName, rawArgs)?.canonicalJson == canonical)
    }

    fun mintJobChildCallId(outerRuntimeId: String): String =
        mintChildRuntimeId("job", 0, outerRuntimeId)

    fun mintBatchChildCallId(outerRuntimeId: String, index: Int): String =
        mintChildRuntimeId("batch", index, outerRuntimeId)

    private fun mintChildRuntimeId(kind: String, index: Int, outerRuntimeId: String): String {
        val token = InternalRequestIds.new().replace("-", "").lowercase().take(16).padEnd(16, '0')
        return "rchild.$kind.$index.$token.$outerRuntimeId"
    }

    fun parse(runtimeInvocationId: String): ChildInvocationDescriptor? {
        if (!runtimeInvocationId.startsWith("rchild.") || !runtimeInvocationId.matches(SAFE_ID)) return null
        val parts = runtimeInvocationId.split('.', limit = 5)
        if (parts.size != 5) return null
        val kind = parts[1]
        val index = parts[2].toIntOrNull() ?: return null
        val token = parts[3]
        val parentId = parts[4]
        val validSlot = (kind == "job" && index == 0) || (kind == "batch" && index in 0..3)
        if (!validSlot || !token.matches(HEX_16) || !parentId.matches(SAFE_ID)) return null
        val childCallId = "rchild_call.$kind.$index.$parentId"
        if (!childCallId.matches(SAFE_ID)) return null
        return ChildInvocationDescriptor(kind, index, parentId, childCallId)
    }

    fun resolveOrMaterializeOwnerCallId(
        db: SqlConnection,
        context: ToolExecutionContext,
        runtimeInvocationId: String,
        invocation: RootToolInvocation,
    ): String? {
        if (!runtimeInvocationId.startsWith("rchild.")) {
            return resolveDirectOwnerCallId(db, context, runtimeInvocationId, invocation)
        }
        val descriptor = parse(runtimeInvocationId) ?: return null
        return db.transaction {
            val parent = db.query(
                "SELECT i.name,i.arguments_json,i.permission_decision,i.state AS invocation_state," +
                    "r.state,r.snapshot_id,r.conversation_id " +
                    "FROM tool_invocations i JOIN runs r ON r.run_id=i.run_id WHERE i.invocation_id=? AND i.run_id=?",
                listOf(descriptor.parentInvocationId, context.taskIdentity),
            ).singleOrNull() ?: return@transaction null
            val parentValid = parent.string("snapshot_id") == context.snapshotId &&
                parent.string("conversation_id") == context.sessionIdentity &&
                parent.string("permission_decision") != "DENIED" &&
                parent.string("invocation_state") !in INVALID_PARENT_STATES &&
                parent.string("state") in ACTIVE_RUN_STATES &&
                matchesParentArguments(descriptor, parent.string("name"), parent.string("arguments_json"), invocation)
            if (!parentValid) return@transaction null
            if (parent.string("invocation_state") == "WAITING_APPROVAL") {
                db.execute(
                    "UPDATE tool_invocations SET state='PENDING',permission_decision='APPROVED' WHERE invocation_id=? AND run_id=? AND state='WAITING_APPROVAL'",
                    listOf(descriptor.parentInvocationId, context.taskIdentity),
                )
            }
            val existingForSlot = db.query(
                "SELECT invocation_id,name,arguments_json,state FROM tool_invocations WHERE run_id=? AND call_id=?",
                listOf(context.taskIdentity, descriptor.childCallId),
            )
            if (existingForSlot.any { it.string("invocation_id") != runtimeInvocationId }) return@transaction null
            val existingSelf = existingForSlot.singleOrNull()
            if (existingSelf != null) {
                val validExisting = existingSelf.string("state") == "PENDING" &&
                    existingSelf.string("name") == invocation.toolName &&
                    existingSelf.string("arguments_json") == invocation.request.canonicalJson
                return@transaction if (validExisting) descriptor.childCallId else null
            }
            RunRepository(db).recordInvocation(
                ToolInvocation(
                    invocationId = runtimeInvocationId,
                    runId = context.taskIdentity,
                    callId = descriptor.childCallId,
                    name = invocation.toolName,
                    argumentsJson = invocation.request.canonicalJson,
                    permissionDecision = "APPROVED",
                    state = "PENDING",
                    createdAt = Utc.nowIso(),
                ),
            )
            descriptor.childCallId
        }
    }

    private fun resolveDirectOwnerCallId(
        db: SqlConnection,
        context: ToolExecutionContext,
        runtimeInvocationId: String,
        invocation: RootToolInvocation,
    ): String? {
        val owner = db.query(
            "SELECT i.call_id,i.name,i.arguments_json,i.state AS invocation_state,r.state,r.snapshot_id,r.conversation_id " +
                "FROM tool_invocations i JOIN runs r ON r.run_id=i.run_id WHERE i.invocation_id=? AND i.run_id=?",
            listOf(runtimeInvocationId, context.taskIdentity),
        ).singleOrNull() ?: return null
        val normalized = RootToolArguments.normalize(owner.string("name"), owner.string("arguments_json"))
        val valid = owner.string("state") == RunStatus.WAITING_TOOL_APPROVAL.name &&
            owner.string("invocation_state") in setOf("PENDING", "WAITING_APPROVAL") &&
            owner.string("snapshot_id") == context.snapshotId &&
            owner.string("conversation_id") == context.sessionIdentity &&
            owner.string("name") == invocation.toolName &&
            invocation.modelCallId?.let { it != owner.string("call_id") } != true &&
            normalized?.canonicalJson == invocation.request.canonicalJson
        if (!valid) return null
        if (owner.string("invocation_state") == "WAITING_APPROVAL") {
            db.execute(
                "UPDATE tool_invocations SET state='PENDING' WHERE invocation_id=? AND run_id=? AND state='WAITING_APPROVAL' AND call_id=? AND name=? AND arguments_json=?",
                listOf(runtimeInvocationId, context.taskIdentity, owner.string("call_id"), invocation.toolName, owner.string("arguments_json")),
            )
        }
        return owner.string("call_id")
    }

    private fun matchesParentArguments(
        descriptor: ChildInvocationDescriptor,
        parentName: String,
        parentArgumentsJson: String,
        invocation: RootToolInvocation,
    ): Boolean {
        val obj = runCatching { Json.parseToJsonElement(parentArgumentsJson) as? JsonObject }.getOrNull() ?: return false
        val childSpec = when (descriptor.kind) {
            "job" -> if (parentName == RunControlTools.JOB_START) obj else null
            "batch" -> {
                val readOnly = !RootApprovalTools.requiresWritableWorkspace(invocation.capability) &&
                    invocation.capability.value != CapabilityId.SHELL_EXECUTE
                val calls = if (parentName == RunControlTools.READ_BATCH && readOnly) obj["calls"] as? JsonArray else null
                calls?.getOrNull(descriptor.index) as? JsonObject
            }
            else -> null
        } ?: return false
        val tool = (childSpec["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return false
        val rawArgs = (childSpec["arguments"] as? JsonObject)?.toString() ?: return false
        return tool == invocation.toolName &&
            RootToolArguments.normalize(invocation.toolName, rawArgs)?.canonicalJson == invocation.request.canonicalJson
    }
}
