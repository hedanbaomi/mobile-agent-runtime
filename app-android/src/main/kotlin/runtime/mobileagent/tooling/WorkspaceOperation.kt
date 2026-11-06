// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.skills.tooling.WorkspaceApplyPatchRequest
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest

/** Stable model names. Only the entries in [WorkspaceOperation] can expose an ordinary operation. */
internal object WorkspaceToolNames {
    const val WORKSPACE_LIST = WorkspaceDiscovery.NAME
    const val FILE_LIST = "file_list"
    const val FILE_STAT = "file_stat"
    const val FILE_READ_TEXT = "file_read_text"
    const val FILE_WRITE_TEXT = "file_write_text"
    const val FILE_CREATE_DIRECTORY = "file_create_directory"
    const val FILE_DELETE = "file_delete"
    const val FILE_GLOB = "file_glob"
    const val FILE_GREP = "file_grep"
    const val FILE_MOVE = "file_move"
    const val FILE_APPLY_PATCH = "file_apply_patch"
    const val APPLY_PATCH = "apply_patch"
}

private val targetKeys = setOf("workspaceId", "workspace_id", "relativePath", "relative_path")
private val versionedKeys = targetKeys + setOf("expectedVersion", "expected_version")

/** Ordinary workspace operations, in schema order. The executor checks each against the Run and backend. */
internal enum class WorkspaceOperation(
    val spec: ToolSpec,
    val backendMethod: String,
    val requestType: Class<*>,
    val auditOperation: WorkspaceAuditOperation,
    val allowedKeys: Set<String>,
    /** Opt-in for read_batch and read-only jobs. The delegate still checks exposure and side effects. */
    val eligibleReadChild: Boolean = false,
) {
    WORKSPACE_LIST(
        WorkspaceDiscovery.spec,
        "list", WorkspaceListRequest::class.java, WorkspaceAuditOperation.ENUMERATE, emptySet(),
        eligibleReadChild = true,
    ),
    LIST(
        workspaceSpec(WorkspaceToolNames.FILE_LIST, "List entries in an authorized workspace.", CapabilityId.FILE_LIST, sideEffect = false),
        "list", WorkspaceListRequest::class.java, WorkspaceAuditOperation.LIST,
        targetKeys + setOf("maxEntries", "max_entries", "cursor"),
        eligibleReadChild = true,
    ),
    GLOB(
        workspaceSpec(
            WorkspaceToolNames.FILE_GLOB,
            "Find workspace entries matching a glob pattern, recursively and with bounded budgets. " +
                "* and ? match inside one path segment; ** matches whole segments. A pattern without / " +
                "matches the entry name at any depth; with / it matches the full relative path. Files and " +
                "directories are both returned. truncation_reasons names the budget that stopped the scan, " +
                "so truncated=true with no matches is not the same as no match.",
            CapabilityId.FILE_LIST, sideEffect = false,
        ),
        "list", WorkspaceListRequest::class.java, WorkspaceAuditOperation.LIST, WorkspaceSearchProtocol.globKeys,
        eligibleReadChild = true,
    ),
    STAT(
        workspaceSpec(WorkspaceToolNames.FILE_STAT, "Read metadata for an authorized workspace entry.", CapabilityId.FILE_STAT, sideEffect = false),
        "stat", WorkspaceStatRequest::class.java, WorkspaceAuditOperation.STAT, targetKeys,
        eligibleReadChild = true,
    ),
    READ(
        workspaceSpec(WorkspaceToolNames.FILE_READ_TEXT, "Read a bounded UTF-8 chunk from an authorized workspace.", CapabilityId.FILE_READ_TEXT, sideEffect = false),
        "readText", WorkspaceReadTextRequest::class.java, WorkspaceAuditOperation.READ,
        targetKeys + setOf("maxBytes", "max_bytes", "offsetBytes", "offset_bytes"),
        eligibleReadChild = true,
    ),
    GREP(
        workspaceSpec(
            WorkspaceToolNames.FILE_GREP,
            "Search authorized workspace text for a literal string (never a regular expression), " +
                "recursively and with bounded budgets. Hits are reported one per line with line number and " +
                "column; case_sensitive defaults to false. Every limit is reported in truncation_reasons, and " +
                "unreadable non-UTF-8 files are counted in scanned.unreadable_entries instead of being " +
                "reported as no match.",
            CapabilityId.FILE_READ_TEXT, sideEffect = false,
        ),
        "readText", WorkspaceReadTextRequest::class.java, WorkspaceAuditOperation.READ, WorkspaceSearchProtocol.grepKeys,
        eligibleReadChild = true,
    ),
    WRITE(
        workspaceSpec(WorkspaceToolNames.FILE_WRITE_TEXT, "Create or replace UTF-8 text in an authorized workspace.", CapabilityId.FILE_WRITE_TEXT, sideEffect = true),
        "writeText", WorkspaceWriteTextRequest::class.java, WorkspaceAuditOperation.WRITE,
        versionedKeys + setOf("text", "replace"),
    ),
    CREATE_DIRECTORY(
        workspaceSpec(WorkspaceToolNames.FILE_CREATE_DIRECTORY, "Create a directory in an authorized workspace.", CapabilityId.FILE_CREATE_DIRECTORY, sideEffect = true),
        "createDirectory", WorkspaceCreateDirectoryRequest::class.java, WorkspaceAuditOperation.MKDIR, versionedKeys,
    ),
    DELETE(
        workspaceSpec(WorkspaceToolNames.FILE_DELETE, "Delete one authorized workspace file or empty directory.", CapabilityId.FILE_DELETE, sideEffect = true),
        "delete", WorkspaceDeleteRequest::class.java, WorkspaceAuditOperation.DELETE, versionedKeys,
    ),
    APPLY_PATCH(
        workspaceSpec(WorkspaceToolNames.FILE_APPLY_PATCH, "Apply a conditional text patch in an authorized workspace.", "file.apply_patch", sideEffect = true),
        "applyPatch", WorkspaceApplyPatchRequest::class.java, WorkspaceAuditOperation.WRITE,
        versionedKeys + setOf("patch", "format"),
    );

    val capability = requireNotNull(spec.capability)
    val isMutation: Boolean get() = spec.sideEffect
    val isSearch: Boolean get() = this == GLOB || this == GREP

    /** Each required capability must cover the whole requested subtree. */
    val requiredOperations: List<WorkspaceOperation> get() = when (this) {
        GLOB -> listOf(LIST)
        GREP -> listOf(LIST, READ)
        else -> listOf(this)
    }

    val requiredCapabilities: List<CapabilityId> by lazy {
        java.util.Collections.unmodifiableList(requiredOperations.map { it.capability }.distinct())
    }

    /**
     * Check every capability at the same workspace and path. A recursive read requires a grant
     * covering its root. Only the in-flight request may supply its consumed ONCE receipts.
     */
    fun revalidateAt(
        resolver: EffectiveCapabilityResolver,
        context: ToolExecutionContext,
        workspaceId: String?,
        path: String?,
        consumedReceipts: List<CapabilityGrant> = emptyList(),
    ): Boolean = requiredCapabilities.all { required ->
        resolver.revalidate(context, required, workspaceId, path, isMutation) ||
            consumedReceipts.any { receipt ->
                receipt.capability == required &&
                    resolver.revalidateInFlight(context, required, receipt, GrantScope(workspaceId, path, isMutation))
            }
    }

    val isReadOnlyDisclosable: Boolean get() = when (this) {
        WORKSPACE_LIST, LIST, STAT, READ, GLOB, GREP -> true
        WRITE, CREATE_DIRECTORY, DELETE, APPLY_PATCH -> false
    }

    companion object {
        val workspaceEntries = entries.filterNot { it == WORKSPACE_LIST }
        private val byName = entries.associateBy { it.spec.name }

        /** The legacy patch alias is input-only. Retired ordinary file_move is rejected before parsing. */
        fun forInput(name: String): WorkspaceOperation? = if (name == WorkspaceToolNames.APPLY_PATCH) APPLY_PATCH else byName[name]
    }
}

private fun workspaceSpec(name: String, description: String, capability: String, sideEffect: Boolean) =
    ToolSpec(name, description, ModelToolArguments.workspaceSchema(name), CapabilityId(capability), sideEffect, ModelToolArguments.WORKSPACE_SCHEMA_VERSION)
