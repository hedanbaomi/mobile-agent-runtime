// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

/** Host-normalized facts for one Runtime invocation; raw arguments never belong here. */
@Serializable
data class RootApprovalRequest(
    val runId: String,
    val invocationId: String,
    val snapshotId: String,
    val grantId: String,
    val normalizedArgumentsSha256: String,
    val configSnapshotHash: String,
    val execution: RootExecutionStamp,
    val toolSchemaVersion: Int = 1,
    val commandHash: String? = null,
    val cwdHash: String? = null,
    val taskIdentity: String? = null,
    val sessionIdentity: String? = null,
) {
    init {
        require(listOf(runId,invocationId,snapshotId,grantId).all {
            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
        })
        require(normalizedArgumentsSha256.matches(Regex("[0-9a-f]{64}")))
        require(configSnapshotHash.matches(Regex("[0-9a-f]{64}")) && toolSchemaVersion > 0)
        require(listOfNotNull(taskIdentity,sessionIdentity).all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) })
    }

    override fun toString(): String = "RootApprovalRequest(authority=${execution.identity.authority})"
}

/** Frozen approval evidence, not an OS credential or a consumed dispatch receipt. */
@Serializable
data class RootApprovalBinding(
    val request: RootApprovalRequest,
    val grantSnapshotSha256: String,
    val grantRevision: Long,
    val capability: CapabilityId,
    val workspaceId: String,
    val approval: ApprovalRecord,
    val invocationName: String,
    val invocationArgumentsSha256: String,
) {
    init {
        require(RootApprovalTools.capability(invocationName) == capability)
        require(grantSnapshotSha256.matches(Regex("[0-9a-f]{64}")) && grantRevision > 0)
        require(workspaceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        require(invocationArgumentsSha256.matches(Regex("[0-9a-f]{64}")))
        require(approval.requestId.isNotBlank() && approval.skillId == null)
        require(approval.selectedAuthority == request.execution.identity.authority &&
            approval.policyVersion == request.execution.policyVersion)
        require(approval.toolSchemaVersion == request.toolSchemaVersion &&
            approval.configSnapshotHash == request.configSnapshotHash &&
            approval.commandHash == request.commandHash && approval.cwdHash == request.cwdHash)
        require(approval.decision != ApprovalDecision.CONSUMED && approval.consumedAt == null)
    }

    override fun toString(): String = "RootApprovalBinding(authority=${approval.selectedAuthority}, decision=${approval.decision})"
}


/** Closed first-wave Root tool mapping; unsupported names cannot borrow another tool's grant. */
object RootApprovalTools {
    private val readOnlyCapabilities = setOf(
        CapabilityId.FILE_LIST,
        CapabilityId.FILE_STAT,
        CapabilityId.FILE_READ_TEXT,
        CapabilityId.FILE_METADATA_READ,
    )
    private val writeCapabilities = setOf(
        CapabilityId.FILE_WRITE_TEXT,
        CapabilityId.FILE_CREATE_DIRECTORY,
        CapabilityId.FILE_MOVE,
        CapabilityId.FILE_DELETE,
        CapabilityId.FILE_METADATA_WRITE,
        CapabilityId.SHELL_EXECUTE,
    )
    private val supportedCapabilities = readOnlyCapabilities + writeCapabilities

    fun isSupportedCapability(capability: CapabilityId): Boolean =
        capability.value in supportedCapabilities

    fun requiresWritableWorkspace(capability: CapabilityId): Boolean =
        capability.value in writeCapabilities

    fun capability(name: String): CapabilityId? = when (name) {
        "file_list", "file_glob" -> CapabilityId(CapabilityId.FILE_LIST)
        "file_stat" -> CapabilityId(CapabilityId.FILE_STAT)
        "file_read_text", "file_grep" -> CapabilityId(CapabilityId.FILE_READ_TEXT)
        "file_write_text", "file_apply_patch", "apply_patch" -> CapabilityId(CapabilityId.FILE_WRITE_TEXT)
        "file_create_directory" -> CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY)
        "file_move" -> CapabilityId(CapabilityId.FILE_MOVE)
        "file_delete" -> CapabilityId(CapabilityId.FILE_DELETE)
        "file_get_metadata" -> CapabilityId(CapabilityId.FILE_METADATA_READ)
        "file_set_metadata" -> CapabilityId(CapabilityId.FILE_METADATA_WRITE)
        "shell_exec" -> CapabilityId(CapabilityId.SHELL_EXECUTE)
        else -> null
    }
}
