// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class AuthorityToolingTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun policyIsSerializable() {
        val policy = AuthorityPolicy(Authority.WIRED_ADB, DangerousMode.ENABLED_AUTONOMOUS, 7, "now")
        assertEquals(policy, json.decodeFromString<AuthorityPolicy>(json.encodeToString(policy)))
    }

    @Test
    fun capabilityGrantLifetimeRequiresExplicitOwnerAndSurvivesRestartByIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityGrant("grant-task-invalid", "agent-1", CapabilityId(CapabilityId.FILE_READ_TEXT), lifetime = GrantLifetime.TASK)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityGrant("grant-session-invalid", "agent-1", CapabilityId(CapabilityId.FILE_READ_TEXT), lifetime = GrantLifetime.SESSION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityGrant(
                "grant-persistent-invalid", "agent-1", CapabilityId(CapabilityId.FILE_READ_TEXT),
                lifetime = GrantLifetime.PERSISTENT, taskId = "task-1",
            )
        }

        val task = CapabilityGrant(
            grantId = "grant-task",
            agentId = "agent-1",
            capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
            lifetime = GrantLifetime.TASK,
            taskId = "task-1",
        )
        assertTrue(task.isUsableFor(taskIdentity = "task-1", sessionIdentity = "session-after-restart"))
        assertFalse(task.isUsableFor(taskIdentity = "task-2", sessionIdentity = "session-after-restart"))
        assertFalse(task.isUsableFor(taskIdentity = null, sessionIdentity = "session-after-restart"))
        assertFalse(
            task.copy(expiresAt = "2026-08-29T23:59:59Z")
                .isActiveFor(Instant.parse("2026-08-30T00:00:00Z"), taskIdentity = "task-1"),
        )
        assertFalse(task.copy(revokedAt = "2026-08-30T00:00:00Z").isUsableFor("task-1", null))

        val once = CapabilityGrant(
            grantId = "grant-once",
            agentId = "agent-1",
            capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
            lifetime = GrantLifetime.ONCE,
            consumedAt = "2026-08-30T00:00:00Z",
        )
        assertFalse(once.isActiveFor(Instant.parse("2026-08-30T00:00:01Z")))
        assertEquals(once, json.decodeFromString<CapabilityGrant>(json.encodeToString(once)))
    }

    @Test
    fun boundaryValuesRejectUnsafeCapabilitiesPaths() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityId("../secret") }
        assertThrows(IllegalArgumentException::class.java) {
            Workspace("ws-1", "Workspace", WorkspaceBackendType.INTERNAL, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillMemoryEntry("entry-1", "space-1", "install-1", "package-1", "journal/2026-02-30.md", "x", 1, 1, "now", "now")
        }
    }

    @Test
    fun domainSourceDoesNotDuplicateSkillsToolingContracts() {
        val relativeSource = Path.of("src/main/kotlin/runtime/mobileagent/domain/AuthorityTooling.kt")
        val sourcePath = sequenceOf(
            relativeSource,
            Path.of("shared/domain").resolve(relativeSource),
        ).firstOrNull(Files::isRegularFile)
            ?: error("Unable to locate AuthorityTooling.kt from the domain test working directory")
        val source = Files.readString(sourcePath)
        val duplicateDeclaration = Regex(
            """(?m)^\s*(?:data\s+class|enum\s+class|typealias)\s+(?:ShellExecRequest|ShellExecResult|ToolErrorCode|ToolError|UnifiedToolError)\b""",
        )

        assertFalse(duplicateDeclaration.containsMatchIn(source))
        assertFalse(source.contains("toToolingError"))
    }

    @Test
    fun typedAuditBuilderHashesSensitiveLocationAndRetainsNoPlaintext() {
        val detail = ToolAuditDetail.builder(
            auditId = "audit-1",
            requestId = "request-1",
            agentId = "agent-1",
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
            result = "STARTED",
            createdAt = "now",
        ).workspace("workspace-1", "private/file.txt")
            .cwd("C:/private/secret")
            .commandHash("a".repeat(64))
            .build()

        assertTrue(detail.relativePathSha256!!.matches(Regex("[0-9a-f]{64}")))
        assertTrue(detail.cwdSha256!!.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals("private/file.txt", detail.relativePathSha256)
        assertNotEquals("C:/private/secret", detail.cwdSha256)
    }

    @Test
    fun typedAuditBuilderUsesCanonicalCwdBound() {
        val maxCwd = "x".repeat(4096)
        ToolAuditDetail.builder("audit-1", "request-1", "agent-1", CapabilityId(CapabilityId.SHELL_EXECUTE), "STARTED", "now")
            .cwd(maxCwd)
            .build()

        assertThrows(IllegalArgumentException::class.java) {
            ToolAuditDetail.builder("audit-1", "request-1", "agent-1", CapabilityId(CapabilityId.SHELL_EXECUTE), "STARTED", "now")
                .cwd("x".repeat(4097))
        }
    }

    @Test
    fun desktopTrustRequiresBridgeSecretReference() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopTrust("desktop-1", "app-1", "raw-secret", revision = 1)
        }
        val trust = DesktopTrust("desktop-1", "app-1", "bridge:desktop:desktop-1")
        assertEquals(DesktopTrustStatus.TRUSTED, trust.status)
    }
}
