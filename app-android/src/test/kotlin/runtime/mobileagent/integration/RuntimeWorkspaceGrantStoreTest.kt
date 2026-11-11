// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AgentWorkspaceDefaultRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.ConversationWorkspaceBindingRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceIntent
import runtime.mobileagent.domain.WorkspaceTarget
import runtime.mobileagent.domain.plan
import runtime.mobileagent.privileged.InMemoryRootSqlConnection
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor

/** Real in-memory SQLite; no Android permission, service, provider or user data is accessed. */
class RuntimeWorkspaceGrantStoreTest {
    private val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
    private val write = CapabilityId(CapabilityId.FILE_WRITE_TEXT)
    private val delete = CapabilityId(CapabilityId.FILE_DELETE)

    @Test
    fun defaultReselectionPreservesExplicitWritesAndDoesNotReviveUnrelatedConsent() = fixture { f ->
        val retained = f.save("write", write)
        val revoked = f.save("revoked-delete", delete, revokedAt = "2026-01-01T00:00:00Z")
        val expired = f.save("expired-delete", delete, expiresAt = "2026-01-01T00:00:00Z")
        val scoped = f.save("scoped-delete", delete, pathScope = "books")
        val otherAgent = f.save("other-agent", delete, agentId = "other")
        val once = f.save("once-delete", delete, lifetime = GrantLifetime.ONCE)

        val granted = f.db.transaction {
            f.store.persistWorkspaceGrantBundle(f.workspace, f.backend, WorkspaceAccessGrantTarget("agent"))
        }

        assertTrue(granted.any { it == retained })
        assertFalse(granted.any { it.capability == delete })
        listOf(revoked, expired, scoped, otherAgent, once).forEach { row ->
            assertEquals(row, f.grants.get(row.grantId), "Automatic selection must not rewrite an independent grant")
        }
    }

    @Test
    fun explicitReadOnlySelectionRevokesOnlyItsWholeDirectoryBundle() = fixture { f ->
        val writable = f.save("write", write)
        val scoped = f.save("scoped-write", write, pathScope = "books")
        val otherAgent = f.save("other-write", write, agentId = "other")

        val granted = f.db.transaction {
            f.store.persistWorkspaceGrantBundle(
                f.workspace, f.backend, WorkspaceAccessGrantTarget("agent", capabilities = setOf(read)),
            )
        }

        assertEquals(setOf(read), granted.map { it.capability }.toSet())
        assertTrue(requireNotNull(f.grants.get(writable.grantId)).revoked)
        assertEquals(scoped, f.grants.get(scoped.grantId))
        assertEquals(otherAgent, f.grants.get(otherAgent.grantId))
    }

    @Test
    fun losingThreadSelectionDoesNotProbeBackendGrantOrRebind() = fixture { f ->
        val original = f.bindings.bind("thread", f.workspace.id)
        val candidate = f.workspaces.save(f.workspace.copy(id = "candidate"))
        val before = f.grants.forAgent("agent", includeRevoked = true)
        val plan = WorkspaceIntent.BIND_THREAD.plan(WorkspaceTarget("agent", "thread"))
        val result = f.db.transaction {
            f.store.persistPickerSelection(candidate, WorkspacePickerTarget("agent", "thread"), plan,
                WorkspaceAccessGrantTarget("agent")) { error("A losing selection cannot require a backend") }
        }

        assertEquals(RuntimePickerSelection.NewThread("agent", original), result)
        assertEquals(original, f.bindings.get("thread"))
        assertEquals(before, f.grants.forAgent("agent", includeRevoked = true))
        assertNull(f.defaults.get("agent"))
    }

    @Test
    fun defaultPersistenceFailureRollsBackTheGrantBundleInTheEnclosingTransaction() = fixture { f ->
        f.db.execute(
            "CREATE TRIGGER reject_default BEFORE INSERT ON agent_workspace_defaults BEGIN SELECT RAISE(ABORT, 'fixture'); END",
        )
        val plan = WorkspaceIntent.SET_AGENT_DEFAULT.plan(WorkspaceTarget("agent"))

        assertThrows(Exception::class.java) {
            f.db.transaction {
                f.store.persistPickerSelection(f.workspace, WorkspacePickerTarget("agent"), plan,
                    WorkspaceAccessGrantTarget("agent")) { f.backend }
            }
        }

        assertTrue(f.grants.forAgent("agent", includeRevoked = true).isEmpty())
        assertNull(f.defaults.get("agent"))
        assertNull(f.bindings.get("thread"))
    }

    @Test
    fun successfulThreadSelectionBindsOnceAndNeverChangesTheAgentDefault() = fixture { f ->
        val plan = WorkspaceIntent.BIND_THREAD.plan(WorkspaceTarget("agent", "thread"))
        fun select() = f.db.transaction {
            f.store.persistPickerSelection(f.workspace, WorkspacePickerTarget("agent", "thread"), plan,
                WorkspaceAccessGrantTarget("agent")) { f.backend }
        } as RuntimePickerSelection.Committed

        val first = select()
        val repeated = select()

        assertEquals(first.grants, repeated.grants)
        assertEquals(first.conversation, repeated.conversation)
        assertEquals(f.workspace.id, f.bindings.get("thread")?.workspaceId)
        assertNull(f.defaults.get("agent"))
    }

    @Test
    fun scopedOneShotOverlayCannotEnterThePersistentWorkspaceFacade() = fixture { f ->
        val failure = assertThrows(RuntimeWorkspaceAccessException::class.java) {
            f.store.persistWorkspaceAndGrants(f.workspace.copy(displayName = "Uncommitted"), f.backend,
                WorkspaceAccessGrantTarget("agent", capabilities = setOf(read), lifetime = GrantLifetime.ONCE))
        }
        assertEquals(WorkspaceAccessErrorCode.INVALID_REQUEST, failure.accessCode)
        assertEquals(f.workspace, f.workspaces.get(f.workspace.id))
        assertTrue(f.grants.forAgent("agent", includeRevoked = true).isEmpty())
    }

    private fun fixture(block: (Fixture) -> Unit) = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "fixture", emptySet(),
            contextLimit = 16_384, outputLimit = 512, revision = 1))
        val agents = AgentRepository(db)
        listOf("agent", "other").forEach { agentId ->
            agents.saveWithPrompt(AgentProfile(agentId, "Fixture", "pending", "model", revision = 0), "Synthetic")
        }
        agents.createSnapshot("agent", "snapshot")
        ConversationRepository(db).create("snapshot", "Fixture", "thread")
        block(Fixture(db))
    }

    private inner class Fixture(val db: InMemoryRootSqlConnection) {
        val workspaces = WorkspaceRepository(db)
        val grants = CapabilityGrantRepository(db)
        val bindings = ConversationWorkspaceBindingRepository(db)
        val defaults = AgentWorkspaceDefaultRepository(db)
        val workspace = workspaces.save(Workspace("workspace", "Fixture", WorkspaceBackendType.INTERNAL, "fixture-root"))
        val backend = object : WorkspaceBackend {
            override val descriptor = WorkspaceDescriptor("workspace", "Fixture", WorkspaceBackendType.INTERNAL)
            override val capabilities = setOf(read, write, delete)
        }
        val store = RuntimeWorkspaceGrantStore(db, workspaces, grants, AuthorityPolicyRepository(db), bindings, defaults)

        fun save(
            id: String,
            capability: CapabilityId,
            agentId: String = "agent",
            pathScope: String? = null,
            lifetime: GrantLifetime = GrantLifetime.PERSISTENT,
            revokedAt: String? = null,
            expiresAt: String? = null,
        ) = grants.save(CapabilityGrant(id, agentId, capability = capability, workspaceId = workspace.id,
            pathScope = pathScope, lifetime = lifetime, policyVersion = 0, revokedAt = revokedAt, expiresAt = expiresAt))
    }
}
