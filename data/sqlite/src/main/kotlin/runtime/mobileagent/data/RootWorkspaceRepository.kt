// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.UUID
import runtime.mobileagent.domain.*

/** Creates fresh Root workspace provenance; encrypted locator attachment is a separate required step. */
class RootWorkspaceRepository(
    private val db: SqlConnection,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun createNew(displayName: String, writable: Boolean, execution: RootExecutionStamp): Workspace = db.transaction {
        requireLocalRootState(db,execution)
        val id = newId()
        require(WorkspaceRepository(db).get(id) == null && getOrigin(id) == null &&
            PrivilegedWorkspaceBindingRepository(db).get(id) == null) {
            "Root cannot reuse an existing workspace"
        }
        val identity = execution.identity
        val generation = UUID.randomUUID().toString()
        val workspace = Workspace(id,displayName,WorkspaceBackendType.PRIVILEGED,"root:$generation",writable=writable)
        val persisted = WorkspaceRepository(db).save(workspace)
        db.execute(
            "INSERT INTO root_workspace_origins(workspace_id,authority,app_instance_id,package_name,app_uid,android_user_id,signing_sha256,generation_id) VALUES(?,?,?,?,?,?,?,?)",
            listOf(id,identity.authority.name,identity.appInstanceId,identity.packageName,identity.appUid,
                identity.androidUserId,identity.signingSha256,generation),
        )
        check(getOrigin(id)?.matches(identity) == true)
        persisted
    }

    fun getOrigin(workspaceId: String): RootWorkspaceOrigin? = db.query(
        "SELECT * FROM root_workspace_origins WHERE workspace_id=?",listOf(workspaceId),
    ).singleOrNull()?.let { row ->
        RootWorkspaceOrigin(
            row.string("workspace_id"),Authority.valueOf(row.string("authority")),row.string("app_instance_id"),
            row.string("package_name"),Math.toIntExact(row.long("app_uid")),
            Math.toIntExact(row.long("android_user_id")),row.string("signing_sha256"),row.string("generation_id"),
        )
    }

    internal fun requireOrigin(workspaceId: String, execution: RootExecutionStamp): RootWorkspaceOrigin {
        val origin = requireNotNull(getOrigin(workspaceId)) { "A fresh Root workspace is required" }
        require(origin.matches(execution.identity))
        val workspace = requireNotNull(WorkspaceRepository(db).get(workspaceId))
        require(workspace.backendType == WorkspaceBackendType.PRIVILEGED && workspace.enabled &&
            workspace.rootReference == "root:${origin.generationId}")
        require(PrivilegedWorkspaceBindingRepository(db).get(workspaceId) == null) {
            "An ADB workspace binding cannot be used for Root"
        }
        return origin
    }

    companion object {
        internal fun install(db: SqlConnection) {
            db.execute("CREATE TABLE IF NOT EXISTS root_workspace_origins(workspace_id TEXT NOT NULL PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,authority TEXT NOT NULL CHECK(authority IN('ROOT_DIRECT','SHIZUKU_ROOT')),app_instance_id TEXT NOT NULL,package_name TEXT NOT NULL,app_uid INTEGER NOT NULL,android_user_id INTEGER NOT NULL,signing_sha256 TEXT NOT NULL,generation_id TEXT NOT NULL UNIQUE)")
            db.execute("CREATE TRIGGER IF NOT EXISTS root_workspace_origins_immutable BEFORE UPDATE ON root_workspace_origins BEGIN SELECT RAISE(ABORT,'Root workspace origin is immutable'); END")
        }
    }
}
