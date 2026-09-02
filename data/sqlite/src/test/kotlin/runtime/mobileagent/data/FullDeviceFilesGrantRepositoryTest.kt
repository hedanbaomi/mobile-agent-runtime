// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrant
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceResult

class FullDeviceFilesGrantRepositoryTest {
    @Test
    fun fullDeviceGrantSurvivesRepositoryRecreationUntilExplicitRevoke() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val grant = FullDeviceFilesGrant("durable-device", revision = 1, confirmedAtEpochMs = 100)
            assertTrue(FullDeviceFilesGrantRepository(db).save(grant) is WorkspaceResult.Success)

            // Provider connectivity is intentionally absent from this durable
            // store. A disconnect or process recreation cannot revoke consent.
            val afterRestart = FullDeviceFilesGrantRepository(db)
            assertEquals(grant, afterRestart.load(grant.workspaceId))
            assertEquals(listOf(grant.workspaceId), afterRestart.activeWorkspaceIds())

            assertTrue(afterRestart.revoke(grant.workspaceId, grant.revision) is WorkspaceResult.Success)
            assertNull(FullDeviceFilesGrantRepository(db).load(grant.workspaceId))
        }
    }

    @Test
    fun saveRevokeAndReauthorizeUseMonotonicCasTombstone() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repository = FullDeviceFilesGrantRepository(db)
            val first = FullDeviceFilesGrant("device-workspace", revision = 1, confirmedAtEpochMs = 100)

            assertTrue(repository.save(first) is WorkspaceResult.Success)
            assertEquals(first, repository.load(first.workspaceId))
            assertEquals(1L, repository.currentRevision(first.workspaceId))

            val staleSave = repository.save(first.copy(confirmedAtEpochMs = 101, revision = 2))
            assertEquals(ToolErrorCode.CONFLICT, assertFailure(staleSave).error.code)
            assertTrue(repository.save(first.copy(confirmedAtEpochMs = 101)) is WorkspaceResult.Success)
            assertEquals(101, repository.load(first.workspaceId)?.confirmedAtEpochMs)

            val staleRevoke = repository.revoke(first.workspaceId, expectedRevision = 2)
            assertEquals(ToolErrorCode.CONFLICT, assertFailure(staleRevoke).error.code)
            assertTrue(repository.revoke(first.workspaceId, expectedRevision = 1) is WorkspaceResult.Success)
            assertNull(repository.load(first.workspaceId))
            assertEquals(2L, repository.currentRevision(first.workspaceId))

            val replay = repository.save(first)
            assertEquals(ToolErrorCode.CONFLICT, assertFailure(replay).error.code)
            val reauthorized = first.copy(revision = 3, confirmedAtEpochMs = 200)
            assertTrue(repository.save(reauthorized) is WorkspaceResult.Success)
            assertEquals(reauthorized, repository.load(first.workspaceId))
        }
    }

    @Test
    fun migrationAddsScopeAndPreservesExistingWorkspaceAsSelectedDirectory() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute(
                "INSERT INTO workspaces(id,display_name,backend_type,root_reference,readable,writable,max_file_bytes,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                listOf("ordinary", "Ordinary", "INTERNAL", "internal-root", 1, 0, 1024, 1, 1, "now", "now"),
            )
            val workspace = WorkspaceRepository(db).get("ordinary")
            assertNotNull(workspace)
            assertEquals(runtime.mobileagent.domain.WorkspaceScope.SELECTED_DIRECTORY, workspace?.scope)
            assertTrue(db.query("PRAGMA table_info(workspaces)").any { it.string("name") == "scope" })
        }
    }

    private fun <T> assertFailure(result: WorkspaceResult<T>): WorkspaceResult.Failure = result as WorkspaceResult.Failure
}
