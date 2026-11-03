// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.*

/** Synthetic local state only; does not authenticate an OS Root session. */
internal class RootApprovalFixture {
    val now = "2026-10-29T11:00:00Z"
    fun execution() = RootExecutionStamp(
        PrivilegedSessionIdentity(Authority.ROOT_DIRECT,PrivilegeTier.ROOT,0,10123,0,
            "app-instance-000001","runtime.mobileagent","a".repeat(64),1,
            "service-instance-001","session-instance-001",4),1,1,1,
    )
    fun request() = RootApprovalRequest(
        "run-root","invocation-root","snapshot-root","root-grant","b".repeat(64),"c".repeat(64),execution(),
    )
    fun setup(db: SqlConnection, pathScope: String? = null, lifetime: GrantLifetime = GrantLifetime.PERSISTENT, expiresAt: String? = null) {
        Migrations.apply(db)
        AuthorityPolicyRepository(db).updatePolicy(0,Authority.ROOT_DIRECT,DangerousMode.DISABLED)
        val roots = RootAuthorityRepository(db)
        val consent = roots.confirmRoot(Authority.ROOT_DIRECT,0,1)!!
        roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,consent.revision)
        RootWorkspaceRepository(db) { "root-workspace" }.createNew("Root fixture",true,execution())
        RootGrantRepository(db) { now }.create(CapabilityGrant(
            "root-grant","agent-root",CapabilityId(CapabilityId.FILE_READ_TEXT),
            workspaceId="root-workspace",policyVersion=1,pathScope=pathScope,lifetime=lifetime,expiresAt=expiresAt,
        ),execution())
        RootSnapshotGrantRepository(db) { now }.createNewSnapshot(
            "snapshot-root","agent-root",setOf("root-grant"),execution(),
        ) {
            db.execute(
                "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf("snapshot-root",1,"agent-root","prompt-1","model-1",1,"[]","[]",now,"provider-1",1,"{}","{}","{}","{}","{}"),
            )
        }
        db.execute("INSERT INTO conversations(id,snapshot_id,title,created_at,updated_at,archived) VALUES(?,?,?,?,?,0)",
            listOf("conversation-root","snapshot-root","Fixture",now,now))
        val run = RootRunAdmission(db) { now }.create(
            RunRecord("run-root", "snapshot-root", "conversation-root", createdAt = now), execution(),
        )
        RunRepository(db).save(run.copy(state = RunStatus.WAITING_TOOL_APPROVAL))
        db.execute("INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf("invocation-root","run-root","model-call","file_read_text","{}","PENDING","PENDING",now,now))
    }

}
