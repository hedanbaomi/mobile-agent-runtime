// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.integration

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.privileged.InMemoryRootSqlConnection
import runtime.mobileagent.skills.tooling.WorkspaceAttachment
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceRecoveryLocator

class PrivilegedAttachmentPersistenceTest {
    @Test
    fun firstWorkspaceReadFailureClearsProviderLocatorWithoutPersistingAnything() {
        verifyReadFailure(IllegalStateException("Synthetic first workspace lookup failure"))
    }

    @Test
    fun cancellationFromFirstWorkspaceReadIsPreservedAndClearsLocator() {
        verifyReadFailure(CancellationException("Synthetic lookup cancellation"))
    }

    private fun verifyReadFailure(failure: RuntimeException) = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        var queried = false
        val failing = object : SqlConnection by db {
            override fun query(sql: String, args: List<Any?>): List<SqlRow> {
                if (sql == "SELECT * FROM workspaces WHERE id = ?") {
                    queried = true
                    throw failure
                }
                return db.query(sql, args)
            }
        }
        val attachment = attachment()
        val locator = requireNotNull(attachment.recoveryLocator)
        var persisted = false
        val actual = assertThrows(failure.javaClass) {
            persistPrivilegedAttachment(attachment, "selected", WorkspaceRepository(failing)) {
                persisted = true
                error("Persistence must not run after failed lookup")
            }
        }
        assertSame(failure, actual)
        assertTrue(queried)
        assertFalse(persisted)
        assertTrue(locator.isCleared, "Provider-returned plaintext must be cleared even before sealing starts")
        for (table in listOf("workspaces", "privileged_workspace_bindings", "capability_grants")) {
            assertEquals(0L, db.query("SELECT count(*) AS n FROM $table").single().long("n"))
        }
    }

    @Test
    fun persistenceFailureAlsoClearsConsumedLocatorAndPreservesException() = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        val attachment = attachment()
        val failure = IllegalArgumentException("Synthetic attachment consumer failure")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            persistPrivilegedAttachment(attachment, "selected", WorkspaceRepository(db)) {
                assertNull(it)
                throw failure
            }
        }
        assertSame(failure, actual)
        assertTrue(requireNotNull(attachment.recoveryLocator).isCleared)
    }

    @Test
    fun successfulConsumptionClearsOnlyItsInputLocatorAndKeepsResult() = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        val repository = WorkspaceRepository(db)
        val workspace = repository.save(Workspace("selected", "Synthetic selected directory", WorkspaceBackendType.PRIVILEGED,
            rootReference = "authority:SHIZUKU"))
        val attachment = attachment()
        val freshLocator = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(8, 9, 10))
        val expected = workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        try {
            val actual = persistPrivilegedAttachment(attachment, "selected", repository) {
                assertEquals(workspace, it)
                assertFalse(requireNotNull(attachment.recoveryLocator).isCleared)
                expected
            }
            assertSame(expected, actual)
            assertTrue(requireNotNull(attachment.recoveryLocator).isCleared)
            assertFalse(freshLocator.isCleared)
            assertArrayEquals(byteArrayOf(8, 9, 10), freshLocator.copyBytes())
        } finally { freshLocator.clear() }
    }

    @Test
    fun descriptorConflictClearsLocatorBeforeAnyRepositoryRead() = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        val neverRead = object : SqlConnection by db {
            override fun query(sql: String, args: List<Any?>): List<SqlRow> = error("Mismatched id must not reach SQLite")
        }
        val attachment = attachment("different")
        val actual = persistPrivilegedAttachment(attachment, "selected", WorkspaceRepository(neverRead)) {
            error("Mismatched id must not be persisted")
        }
        assertEquals(workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT), actual)
        assertTrue(requireNotNull(attachment.recoveryLocator).isCleared)
    }

    @Test
    fun absentLocatorStillReachesExistingConsumerFailureClassification() = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        val actual = persistPrivilegedAttachment(attachment(locator = null), "selected", WorkspaceRepository(db)) {
            workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED)
        }
        assertEquals(workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED), actual)
    }

    private fun attachment(
        id: String = "selected",
        locator: WorkspaceRecoveryLocator? = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(1, 2, 3)),
    ): WorkspaceAttachment {
        val descriptor = WorkspaceDescriptor(id, "Synthetic directory", WorkspaceBackendType.PRIVILEGED)
        val backend = object : WorkspaceBackend { override val descriptor = descriptor }
        return WorkspaceAttachment(descriptor, backend, locator)
    }
}
