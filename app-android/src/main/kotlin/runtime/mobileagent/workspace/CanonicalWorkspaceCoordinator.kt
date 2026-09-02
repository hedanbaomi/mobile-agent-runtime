// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import android.net.Uri
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.WorkspaceDraft
import runtime.mobileagent.domain.WorkspaceIntent
import runtime.mobileagent.domain.WorkspaceIntentPlan
import runtime.mobileagent.domain.WorkspaceTarget
import runtime.mobileagent.domain.plan
import runtime.mobileagent.integration.WorkspaceAccessErrorCode
import runtime.mobileagent.integration.WorkspaceAccessGrantSummary
import runtime.mobileagent.integration.WorkspaceAccessItem
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest

/**
 * The only write seam a workspace screen may call.
 *
 * Every selection is expressed as a [WorkspaceIntent] plus a
 * [WorkspaceTarget]; the sink resolves the resulting [WorkspaceIntentPlan] and
 * commits workspace, capability grant, Agent default and Thread binding in one
 * canonical transaction.  A screen can therefore no longer assemble its own
 * attach + grant + default combination.
 *
 * Authority selection is never decided here: a privileged attach still
 * requires the authority the user explicitly selected, and no fallback to
 * another authority is performed.
 */
interface CanonicalWorkspaceSink {
    suspend fun attachPrivileged(
        authority: Authority,
        request: WorkspaceAttachRequest,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult

    suspend fun attachSaf(
        uri: Uri,
        resultFlags: Int,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult

    /** Foreground path entry used only by the wired ADB provider. */
    suspend fun attachPrivilegedPath(
        authority: Authority,
        workspaceId: String,
        displayName: String,
        absolutePath: String,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult

    /**
     * The explicit high-risk full-device scope.  A full-device workspace can
     * never become an Agent default or a Thread binding target.
     */
    suspend fun openFullDeviceFiles(
        authority: Authority,
        request: FullDeviceFilesRequest,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult

    suspend fun useRecent(
        workspaceId: String,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult

    /**
     * Commit a staged draft for an Agent that now exists. Grant and Agent
     * default are written in the same transaction, so a failure leaves no
     * half-configured Agent behind. May reattach a privileged backend, hence
     * suspend.
     */
    suspend fun commitDraft(draft: WorkspaceDraft, agentId: String): WorkspaceAccessResult
}

/** AppContainer exposes the one canonical workspace write seam through this. */
interface CanonicalWorkspaceSinkProvider {
    val canonicalWorkspaceSink: CanonicalWorkspaceSink?
}

/** Result of one canonical workspace selection. */
sealed interface WorkspaceSelectionOutcome {
    /** The workspace and every requested binding were committed. */
    data class Committed(
        val workspace: WorkspaceAccessItem,
        val grants: List<WorkspaceAccessGrantSummary> = emptyList(),
    ) : WorkspaceSelectionOutcome

    /**
     * The workspace exists but nothing was authorized yet, because the Agent
     * is still a draft. The caller must keep [draft] and commit it after the
     * Agent is saved; abandoning the editor leaves no grant behind.
     */
    data class Staged(val draft: WorkspaceDraft) : WorkspaceSelectionOutcome

    data class Failed(val code: WorkspaceAccessErrorCode) : WorkspaceSelectionOutcome
}

/**
 * Single UI-facing entry point for workspace selection.
 *
 * Screens call one of the select* methods with a [WorkspaceIntent] and render
 * the returned outcome.  No screen may call attach/grant/default APIs
 * directly, and no screen may derive grant/default combinations on its own.
 *
 * The primary Agent flow is [WorkspaceIntent.SET_AGENT_DEFAULT]: the selection
 * attaches the workspace, grants it to the Agent and makes it the Agent's
 * default for future Threads in one transaction.  [WorkspaceIntent.ADD_TO_LIBRARY]
 * remains available as the advanced "authorize without defaulting" operation.
 */
class CanonicalWorkspaceCoordinator(private val sink: CanonicalWorkspaceSink) {

    suspend fun selectPrivileged(
        intent: WorkspaceIntent,
        authority: Authority,
        request: WorkspaceAttachRequest,
        target: WorkspaceTarget,
    ): WorkspaceSelectionOutcome {
        val plan = intent.plan(target)
        val result = sink.attachPrivileged(authority, request, plan, target)
        return result.toOutcome(plan)
    }

    suspend fun selectSaf(
        intent: WorkspaceIntent,
        uri: Uri,
        target: WorkspaceTarget,
        resultFlags: Int = DEFAULT_SAF_FLAGS,
    ): WorkspaceSelectionOutcome {
        val plan = intent.plan(target)
        val result = sink.attachSaf(uri, resultFlags, plan, target)
        return result.toOutcome(plan)
    }

    suspend fun selectPrivilegedPath(
        intent: WorkspaceIntent,
        authority: Authority,
        workspaceId: String,
        displayName: String,
        absolutePath: String,
        target: WorkspaceTarget,
    ): WorkspaceSelectionOutcome {
        val plan = intent.plan(target)
        val result = sink.attachPrivilegedPath(authority, workspaceId, displayName, absolutePath, plan, target)
        return result.toOutcome(plan)
    }

    /**
     * The full-device scope is grant-only by construction: the plan is forced
     * to ADD_TO_LIBRARY semantics regardless of the requested intent, because
     * a full-device workspace can never be an Agent default or Thread binding.
     */
    suspend fun openFullDeviceFiles(
        authority: Authority,
        request: FullDeviceFilesRequest,
        target: WorkspaceTarget,
    ): WorkspaceSelectionOutcome {
        val plan = WorkspaceIntent.ADD_TO_LIBRARY.plan(target)
        val result = sink.openFullDeviceFiles(authority, request, plan, target)
        return result.toOutcome(plan)
    }

    suspend fun selectRecent(
        intent: WorkspaceIntent,
        workspaceId: String,
        target: WorkspaceTarget,
    ): WorkspaceSelectionOutcome {
        val plan = intent.plan(target)
        val result = sink.useRecent(workspaceId, plan, target)
        return result.toOutcome(plan)
    }

    /**
     * Commit a draft staged by one of the select* methods. Called only after
     * the Agent has been persisted.
     */
    suspend fun commitDraft(draft: WorkspaceDraft, agentId: String): WorkspaceSelectionOutcome =
        sink.commitDraft(draft, agentId).toOutcome(null)

    private fun WorkspaceAccessResult.toOutcome(plan: WorkspaceIntentPlan?): WorkspaceSelectionOutcome =
        when (this) {
            is WorkspaceAccessResult.Success ->
                if (plan != null && plan.deferred) {
                    WorkspaceSelectionOutcome.Staged(
                        WorkspaceDraft(
                            workspaceId = workspace.workspaceId,
                            displayName = workspace.displayName,
                            setAsAgentDefault = plan.setAgentDefault,
                        ),
                    )
                } else {
                    WorkspaceSelectionOutcome.Committed(workspace, grants)
                }
            is WorkspaceAccessResult.Failure -> WorkspaceSelectionOutcome.Failed(code)
            is WorkspaceAccessResult.NewThreadRequired ->
                WorkspaceSelectionOutcome.Failed(WorkspaceAccessErrorCode.CONFLICT)
        }

    companion object {
        const val DEFAULT_SAF_FLAGS: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /**
         * The one intent a foreground conversation picker must use: it binds
         * the Thread and can never mutate the Agent default.
         */
        fun intentForThreadSelection(): WorkspaceIntent = WorkspaceIntent.BIND_THREAD

        /**
         * The one intent an Agent workspace selection must use: attach, grant
         * and default in a single transaction.
         */
        fun intentForAgentSelection(): WorkspaceIntent = WorkspaceIntent.SET_AGENT_DEFAULT
    }
}
