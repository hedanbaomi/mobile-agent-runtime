// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.data.RootRunAdmission
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.skills.tooling.ShellExecRequest

/** Test-only admission; production approval must not manufacture runs/invocations. */
internal fun approveFixtureFile(db: SqlConnection, coordinator: RootRuntimeCoordinator,
    authority: Authority, runId: String, invocationId: String, callId: String, snapshotId: String,
    grantId: String, typedArgs: RootTypedArguments) = run {
    admitFixtureInvocation(db, coordinator.currentExecutionStamp(authority)!!, runId, invocationId,
        callId, snapshotId, typedArgs)
    coordinator.approveFileInvocation(authority, runId, invocationId, callId, snapshotId, grantId, typedArgs)
}

internal fun approveFixtureShell(db: SqlConnection, coordinator: RootRuntimeCoordinator,
    authority: Authority, runId: String, request: ShellExecRequest, grantId: String, workspaceId: String) = run {
    admitFixtureInvocation(db, coordinator.currentExecutionStamp(authority, true)!!, runId,
        request.requestId, request.callId, request.snapshotId, RootTypedArguments.ShellExec(workspaceId, request.command))
    coordinator.approveShellInvocation(authority, runId, request, grantId, workspaceId)
}

private fun admitFixtureInvocation(db: SqlConnection, execution: RootExecutionStamp, runId: String,
    invocationId: String, callId: String, snapshotId: String, args: RootTypedArguments) {
    val now = Utc.nowIso()
    val conversation = "conv-$runId"
    db.execute("INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
        listOf(conversation, snapshotId, snapshotId, "Test only", now, now))
    RootRunAdmission(db).create(RunRecord(runId, snapshotId, conversation, createdAt = now), execution)
    db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId))
    RunRepository(db).addInvocation(ToolInvocation(invocationId, runId, callId, args.toolName,
        args.canonicalJson(), permissionDecision = "PENDING", createdAt = now))
}
