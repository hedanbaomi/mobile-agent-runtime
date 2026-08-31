// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.AuditEvent
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ToolAuditDetail

class AuditRepositoryTest {
    @Test
    fun typedToolAuditStoresOnlyHashesCountersAndImmutableLifecycleRows() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repository = AuditRepository(db)
            val hash = "c".repeat(64)
            val started = ToolAuditDetail.builder(
                auditId = "audit-start",
                requestId = "request-1",
                agentId = "agent-1",
                capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
                result = "STARTED",
                createdAt = "2026-08-30T00:00:00Z",
            ).authority(Authority.WIRED_ADB)
                .dangerousMode(DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
                .policyVersion(2)
                .workspace("workspace-1", "private/file.txt")
                .cwd("C:/private")
                .commandHash(hash)
                .build()
            val completed = started.copy(auditId = "audit-complete", result = "COMPLETED", exitCode = 0, durationMs = 5)

            assertEquals(started, repository.append(started))
            assertEquals(completed, repository.append(completed))
            assertEquals(started, repository.getDetail("audit-start"))
            assertEquals(2, repository.listDetails("request-1").size)
            assertThrows(AppException::class.java) {
                repository.append(started.copy(result = "CHANGED"))
            }
            val names = db.query("PRAGMA table_info(tool_audit_details)").map { it.string("name") }.toSet()
            assertTrue("command_sha256" in names)
            assertTrue("cwd_sha256" in names)
            assertTrue("relative_path_sha256" in names)
            assertTrue("command_preview" !in names)
            assertTrue("stdout" !in names && "stderr" !in names && "uri" !in names && "serial" !in names)
        }
    }

    @Test
    fun legacyAuditMetadataRejectsSensitiveFieldsAndEventsRemainImmutable() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repository = AuditRepository(db)
            val event = AuditEvent(
                id = "audit-legacy",
                createdAt = "now",
                component = "test",
                action = "operation",
                result = "OK",
                summary = "safe",
                metadataJson = "{\"command\":\"echo secret\"}",
            )
            assertThrows(AppException::class.java) { repository.append(event) }
            val safe = event.copy(metadataJson = "{\"capability\":\"file.read_text\",\"count\":1}")
            assertEquals(safe, repository.append(safe))
            assertThrows(AppException::class.java) { repository.append(safe.copy(summary = "changed")) }
        }
    }
}
