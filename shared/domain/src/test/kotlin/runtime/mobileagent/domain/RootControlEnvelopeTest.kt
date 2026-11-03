// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootControlEnvelopeTest {
    private fun identity(
        epoch: Long = 1L,
        serviceInstanceId: String = "service-instance-001",
        sessionId: String = "session-instance-001",
    ) = PrivilegedSessionIdentity(
        authority = Authority.ROOT_DIRECT,
        privilegeTier = PrivilegeTier.ROOT,
        serviceUid = 0,
        appUid = 10148,
        androidUserId = 0,
        appInstanceId = "app-instance-000001",
        packageName = "runtime.mobileagent",
        signingSha256 = "a".repeat(64),
        identityEpoch = epoch,
        serviceInstanceId = serviceInstanceId,
        sessionId = sessionId,
        protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
    )

    private fun stamp(
        id: PrivilegedSessionIdentity = identity(),
        shellRevision: Long = 1L,
    ) = RootExecutionStamp(
        identity = id,
        consentRevision = 1L,
        shellConsentRevision = shellRevision,
        policyVersion = 1L,
    )

    private fun envelope(
        arguments: RootTypedArguments = RootTypedArguments.FileReadText(
            workspaceId = "root-workspace",
            relativePath = "private-scope/data.txt",
            offsetBytes = 0L,
            maxBytes = 4096,
        ),
        toolName: String = arguments.toolName,
        capability: CapabilityId = arguments.requiredCapability,
        execution: RootExecutionStamp = stamp(),
        relativePathScope: String? = "private-scope",
        once: Boolean = true,
        grantBeforeRevision: Long = 1L,
        grantAfterRevision: Long = if (once) 2L else 1L,
        grantConsumedAt: String? = if (once) "2026-10-30T00:00:00Z" else null,
        commandHash: String? = (arguments as? RootTypedArguments.ShellExec)?.command?.let(RootControlJson::sha256Hex),
        cwdHash: String? = (arguments as? RootTypedArguments.ShellExec)?.cwdRelativePath?.let(RootControlJson::sha256Hex),
    ): RootControlEnvelope = RootControlEnvelope(
        requestId = "req-001",
        runId = "run-001",
        invocationId = "inv-001",
        snapshotId = "snap-001",
        grantId = "grant-001",
        grantBeforeRevision = grantBeforeRevision,
        grantAfterRevision = grantAfterRevision,
        once = once,
        grantConsumedAt = grantConsumedAt,
        execution = execution,
        capability = capability,
        toolName = toolName,
        workspaceId = "root-workspace",
        workspaceGenerationId = "workspace-gen-0001",
        workspaceSessionHandle = "workspace-handle-0001",
        relativePathScope = relativePathScope,
        arguments = arguments,
        rawArgumentsSha256 = "b".repeat(64),
        normalizedArgumentsSha256 = arguments.canonicalSha256(),
        budget = RootExecutionBudget(timeoutMs = 15_000L, maxOutputBytes = 32_768, maxEntries = 128),
        commandHash = commandHash,
        cwdHash = cwdHash,
    )

    @Test
    fun serviceReNormalizesFormattingAndBindsDigestToSessionInstanceAndEpoch() {
        val original = envelope()
        val pretty = """
            {
              "schemaVersion": 1,
              "budget": { "maxEntries": 128, "maxOutputBytes": 32768, "timeoutMs": 15000 },
              "normalizedArgumentsSha256": "${original.normalizedArgumentsSha256}",
              "rawArgumentsSha256": "${original.rawArgumentsSha256}",
              "arguments": {
                "type": "file_read_text",
                "maxBytes": 4096,
                "offsetBytes": 0,
                "relativePath": "private-scope/data.txt",
                "workspaceId": "root-workspace"
              },
              "relativePathScope": "private-scope",
              "workspaceSessionHandle": "workspace-handle-0001",
              "workspaceGenerationId": "workspace-gen-0001",
              "workspaceId": "root-workspace",
              "toolName": "file_read_text",
              "capability": "file.read_text",
              "execution": {
                "policyVersion": 1,
                "shellConsentRevision": 1,
                "consentRevision": 1,
                "identity": {
                  "protocolVersion": 4,
                  "sessionId": "session-instance-001",
                  "serviceInstanceId": "service-instance-001",
                  "identityEpoch": 1,
                  "signingSha256": "${"a".repeat(64)}",
                  "packageName": "runtime.mobileagent",
                  "appInstanceId": "app-instance-000001",
                  "androidUserId": 0,
                  "appUid": 10148,
                  "serviceUid": 0,
                  "privilegeTier": "ROOT",
                  "authority": "ROOT_DIRECT"
                }
              },
              "grantConsumedAt": "2026-10-30T00:00:00Z",
              "once": true,
              "grantAfterRevision": 2,
              "grantBeforeRevision": 1,
              "grantId": "grant-001",
              "snapshotId": "snap-001",
              "invocationId": "inv-001",
              "runId": "run-001",
              "requestId": "req-001",
              "commandHash": null,
              "cwdHash": null
            }
        """.trimIndent()
        val decoded = RootControlEnvelope.decodeAndCanonicalize(pretty)
        assertEquals(original, decoded)
        assertEquals(original.canonicalJson(), decoded.canonicalJson())
        assertEquals(original.canonicalSha256(), decoded.canonicalSha256())

        val differentInstance = envelope(execution = stamp(identity(serviceInstanceId = "service-instance-002")))
        val differentSession = envelope(execution = stamp(identity(sessionId = "session-instance-002")))
        val differentEpoch = envelope(execution = stamp(identity(epoch = 2L)))
        assertNotEquals(original.canonicalSha256(), differentInstance.canonicalSha256())
        assertNotEquals(original.canonicalSha256(), differentSession.canonicalSha256())
        assertNotEquals(original.canonicalSha256(), differentEpoch.canonicalSha256())
        assertFalse(original.toString().contains("private-scope"))
    }

    @Test
    fun rejectsUnknownFieldsDuplicateKeysOversizedPayloadsAndDigestTampering() {
        val validJson = envelope().canonicalJson()
        val withUnknownField = validJson.removeSuffix("}") + ""","extra":1}"""
        assertThrows(Exception::class.java) {
            RootControlEnvelope.decodeAndCanonicalize(withUnknownField)
        }

        val withDuplicateKey = validJson.replaceFirst(
            """"requestId":"req-001"""",
            """"requestId":"req-001","requestId":"req-002"""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            RootControlEnvelope.decodeAndCanonicalize(withDuplicateKey)
        }

        val withEscapedDuplicateKey = validJson.replaceFirst(
            """"requestId":"req-001"""",
            """"requestId":"req-001","\u0072equestId":"req-002"""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            RootControlEnvelope.decodeAndCanonicalize(withEscapedDuplicateKey)
        }

        val tamperedPath = validJson.replace("private-scope/data.txt", "private-scope/other.txt")
        assertThrows(IllegalArgumentException::class.java) {
            RootControlEnvelope.decodeAndCanonicalize(tamperedPath)
        }

        val oversized = validJson.removeSuffix("}") + " ".repeat(RootControlEnvelope.MAX_ENVELOPE_BYTES) + "}"
        assertThrows(IllegalArgumentException::class.java) {
            RootControlEnvelope.decodeAndCanonicalize(oversized)
        }
    }

    @Test
    fun enforcesClosedToolCapabilitiesRelativeScopeMetadataAndShellGuards() {
        assertThrows(IllegalArgumentException::class.java) {
            RootTypedArguments.FileReadText("root-workspace", "/data/data/pkg/secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootTypedArguments.FileReadText("root-workspace", "private-scope/../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootTypedArguments.FileReadText("root-workspace", "private-scope\\secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(
                arguments = RootTypedArguments.FileReadText("root-workspace", "private-scope-sibling/file.txt"),
                relativePathScope = "private-scope",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(
                arguments = RootTypedArguments.FileReadText("root-workspace", "private-scope/file.txt"),
                toolName = "file_write_text",
                capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT),
            )
        }

        val applyPatchAlias = envelope(
            arguments = RootTypedArguments.FileApplyPatch(
                workspaceId = "root-workspace",
                relativePath = "private-scope/file.txt",
                patchSha256 = "c".repeat(64),
                patchByteLength = 64,
                expectedVersion = "v1",
            ),
            toolName = "apply_patch",
            capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT),
        )
        assertEquals(CapabilityId(CapabilityId.FILE_WRITE_TEXT), applyPatchAlias.capability)

        assertThrows(IllegalArgumentException::class.java) {
            RootTypedArguments.FileSetMetadata(
                workspaceId = "root-workspace",
                relativePath = "private-scope/file.txt",
                expectedVersion = "v1",
                mode = 0b100_111_101_101,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootTypedArguments.FileSetMetadata(
                workspaceId = "root-workspace",
                relativePath = "private-scope/file.txt",
                expectedVersion = "v1",
                uid = 10148,
                gid = 10148,
                ownerTransferAuthorized = false,
            )
        }
        val rootChown = RootTypedArguments.FileSetMetadata(
            workspaceId = "root-workspace",
            relativePath = "private-scope/file.txt",
            expectedVersion = "v1",
            mode = 384,
            uid = 0,
            gid = 0,
            ownerTransferAuthorized = false,
        )
        assertEquals("file_set_metadata", envelope(arguments = rootChown).toolName)

        val shellArgs = RootTypedArguments.ShellExec(
            workspaceId = "root-workspace",
            command = "id -u",
            cwdRelativePath = "private-scope/sub",
        )
        val validShell = envelope(arguments = shellArgs)
        assertEquals(RootControlJson.sha256Hex("id -u"), validShell.commandHash)
        assertThrows(IllegalArgumentException::class.java) {
            envelope(arguments = shellArgs, execution = stamp(shellRevision = 0L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(arguments = shellArgs, commandHash = "d".repeat(64))
        }
        // P2-3 regression: null cwdRelativePath must not bypass non-null relativePathScope
        val rootCwdShell = RootTypedArguments.ShellExec(
            workspaceId = "root-workspace",
            command = "id -u",
            cwdRelativePath = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            envelope(arguments = rootCwdShell, relativePathScope = "private-scope")
        }
        assertEquals("shell_exec", envelope(arguments = rootCwdShell, relativePathScope = null).toolName)
    }
}
