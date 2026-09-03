/*
 * SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package runtime.mobileagent.skills.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime

class ToolingContractsTest {
    private val shell = CapabilityId(CapabilityId.SHELL_EXECUTE)

    @Test
    fun capabilityNamesUseDomainValueAndRejectUnsafeManifests() {
        assertTrue(CapabilityNaming.isValid("shell.execute"))
        assertTrue(CapabilityNaming.isValid("file.read_text"))
        assertFalse(CapabilityNaming.isValid("shell"))
        assertFalse(CapabilityNaming.isValid("Shell.execute"))
        assertFalse(CapabilityNaming.isValid("shell..execute"))
        assertThrows(IllegalArgumentException::class.java) { CapabilityNaming.requireValid("shell/execute") }
        assertEquals(CapabilityId(CapabilityId.SHELL_EXECUTE), ToolCapabilities.SHELL_EXECUTE)
    }

    @Test
    fun toolErrorEnvelopeKeepsSnakeCaseAndCanonicalUnknownOutcome() {
        val unknown = ToolError.unknownOutcome()
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, unknown.code)
        assertFalse(unknown.retryable)

        val action = "Inspect the device"
        val envelope = ToolError(
            ToolErrorCode.UNKNOWN_OUTCOME,
            userAction = action,
        ).envelope()
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, envelope.error)
        assertEquals(action, envelope.user_action)
    }

    @Test
    fun workspaceFailuresExposeTypedCursorPermissionAndLargeFileCodes() {
        assertEquals("INVALID_CURSOR", ToolError(ToolErrorCode.INVALID_CURSOR).wireCode)
        assertEquals("PERMISSION_DENIED", ToolError(ToolErrorCode.PERMISSION_DENIED).wireCode)
        assertEquals("FILE_TOO_LARGE", ToolError(ToolErrorCode.FILE_TOO_LARGE).wireCode)
        assertEquals("UNSUPPORTED_ENTRY", ToolError(ToolErrorCode.UNSUPPORTED_ENTRY).wireCode)
        assertEquals("OPERATION_UNAVAILABLE", ToolError(ToolErrorCode.OPERATION_UNAVAILABLE).wireCode)

        val failure = ToolError(ToolErrorCode.FILE_TOO_LARGE)
        assertEquals(ToolErrorCode.FILE_TOO_LARGE, failure.envelope().error)
        assertEquals("FILE_TOO_LARGE", failure.userMessage)
    }

    @Test
    fun workspaceListingCarriesBoundedSafeSkipWarnings() {
        val listing = WorkspaceListing(
            relativePath = ".",
            entries = emptyList(),
            skippedEntries = 3,
            warnings = listOf(
                WorkspaceListingWarning(WorkspaceListingWarningCode.SYMLINK_SKIPPED, count = 2),
                WorkspaceListingWarning(WorkspaceListingWarningCode.METADATA_UNAVAILABLE, count = 1),
            ),
        )

        assertEquals(3, listing.skippedEntries)
        assertEquals(
            listOf("SYMLINK_SKIPPED", "METADATA_UNAVAILABLE"),
            listing.warnings.map(WorkspaceListingWarning::wireCode),
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceListing(
                relativePath = ".",
                entries = emptyList(),
                skippedEntries = 100_001,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceListingWarning(WorkspaceListingWarningCode.UNSUPPORTED_ENTRY_SKIPPED, count = 0)
        }
    }

    @Test
    fun shellRequestAndResultKeepCanonicalCwdAndOutputBounds() {
        val maxCwd = "x".repeat(ShellExecRequest.MAX_CWD_LENGTH)
        val request = ShellExecRequest.fromRuntime(
            callId = "call-1",
            command = "pwd",
            cwd = maxCwd,
            limits = ShellLimits(maxOutputBytes = ShellLimits.MAX_OUTPUT_BYTES),
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = 1,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        assertEquals(4096, ShellExecRequest.MAX_CWD_LENGTH)
        assertEquals(maxCwd, request.cwd)
        assertThrows(IllegalArgumentException::class.java) {
            ShellExecRequest.fromRuntime(
                callId = "call-2",
                command = "pwd",
                cwd = maxCwd + "x",
                agentId = "agent",
                snapshotId = "snapshot",
                selectedAuthority = Authority.SHIZUKU,
                dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
                policyVersion = 1,
                configSnapshotHash = "config",
                sessionIdentity = "session",
            )
        }

        val result = ShellExecResult.succeeded(
            request,
            exitCode = 0,
            stdout = "x".repeat((ShellLimits.MAX_OUTPUT_BYTES + 1).toInt()),
            stderr = "",
            durationMs = 1,
        )
        assertEquals(ShellLimits.MAX_OUTPUT_BYTES, result.outputBytes)
        assertTrue(result.stdoutTruncated)
    }

    @Test
    fun effectiveCapabilitiesIntersectAllApplicableGates() {
        val scope = WorkspaceCapabilityScope(
            workspaceId = "ws",
            allowedRoots = setOf("docs"),
            writableRoots = setOf("docs/out"),
            capabilities = setOf(shell),
        )
        val request = CapabilityResolutionRequest(
            globalPolicy = setOf(shell),
            agentGrant = CapabilityGrantSet(setOf(shell), ownerId = "agent", workspaceId = "ws"),
            skillGrant = CapabilityGrantSet(setOf(shell), ownerId = "skill"),
            skillApplicable = true,
            workspaceScope = scope,
            workspaceId = "ws",
            relativePath = "docs/out/result.txt",
            writeOperation = true,
            budget = CapabilityBudget(maxCalls = 1),
            agentId = "agent",
            skillId = "skill",
        )
        val resolved = EffectiveCapabilityResolver().resolveDetailed(request)
        assertEquals(setOf(shell), resolved.capabilities)
        assertTrue(resolved.fullyPermitted)

        assertTrue(EffectiveCapabilityResolver().resolveDetailed(request.copy(relativePath = "private/x")).capabilities.isEmpty())
        val disjointAgentScope = request.copy(
            agentGrant = CapabilityGrantSet(
                setOf(shell),
                ownerId = "agent",
                workspaceId = "ws",
                pathScope = CapabilityPathScope(setOf("private")),
            ),
        )
        val disjointPath = EffectiveCapabilityResolver().resolveDetailed(disjointAgentScope)
        assertFalse(disjointPath.workspaceAllowed)
        assertTrue(disjointPath.capabilities.isEmpty())
        val disjointWorkspace = EffectiveCapabilityResolver().resolveDetailed(
            request.copy(agentGrant = request.agentGrant.copy(workspaceId = "other"), workspaceId = null),
        )
        assertFalse(disjointWorkspace.workspaceAllowed)
        assertTrue(disjointWorkspace.capabilities.isEmpty())
        assertTrue(EffectiveCapabilityResolver().resolveDetailed(request.copy(budget = CapabilityBudget(maxCalls = 0))).capabilities.isEmpty())
        assertTrue(EffectiveCapabilityResolver().resolveDetailed(request.copy(skillGrant = null)).capabilities.isEmpty())
    }

    @Test
    fun effectiveCapabilitiesRequireMatchingGrantLifetimeAndRejectConsumedOnceGrant() {
        val taskGrant = CapabilityGrantSet(
            capabilities = setOf(shell),
            ownerId = "agent",
            lifetime = GrantLifetime.TASK,
            taskId = "task-1",
        )
        val request = CapabilityResolutionRequest(
            globalPolicy = setOf(shell),
            agentGrant = taskGrant,
            agentId = "agent",
            taskIdentity = "task-1",
        )
        assertEquals(setOf(shell), EffectiveCapabilityResolver().resolve(request))
        assertTrue(EffectiveCapabilityResolver().resolve(request.copy(taskIdentity = "task-2")).isEmpty())
        assertTrue(EffectiveCapabilityResolver().resolve(request.copy(taskIdentity = null)).isEmpty())
        assertTrue(EffectiveCapabilityResolver().resolve(request.copy(agentId = null)).isEmpty())

        val consumed = request.copy(
            agentGrant = taskGrant.copy(
                lifetime = GrantLifetime.ONCE,
                taskId = null,
                consumedAtEpochMs = 1L,
            ),
        )
        assertTrue(EffectiveCapabilityResolver().resolve(consumed).isEmpty())
    }

    @Test
    fun exposureMatrixUsesPersistentSelectionAndNeverFallbacks() {
        val disconnected = AuthorityState.configured(
            Authority.SHIZUKU,
            availability = Availability.TEMPORARILY_UNAVAILABLE,
            connection = Connection.DISCONNECTED,
        )
        val exposed = DangerousModeExposure.decide(
            DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            setOf(shell),
            Authority.SHIZUKU,
            disconnected,
        )
        assertTrue(exposed.exposed)
        assertFalse(disconnected.isReady)

        assertFalse(DangerousModeExposure.shouldExpose(DangerousMode.DISABLED, setOf(shell), Authority.SHIZUKU, disconnected))
        assertFalse(DangerousModeExposure.shouldExpose(DangerousMode.ENABLED_CONFIRM_HIGH_RISK, emptySet(), Authority.SHIZUKU, disconnected))
        assertFalse(DangerousModeExposure.shouldExpose(DangerousMode.ENABLED_CONFIRM_HIGH_RISK, setOf(shell), null, disconnected))
        assertFalse(
            DangerousModeExposure.shouldExpose(
                DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                setOf(shell),
                Authority.SHIZUKU,
                AuthorityState.configured(Authority.WIRED_ADB),
            ),
        )
        assertFalse(
            DangerousModeExposure.shouldExpose(
                DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
                setOf(shell),
                Authority.SHIZUKU,
                AuthorityState(
                    authority = Authority.SHIZUKU,
                    userIntent = runtime.mobileagent.domain.AuthorityUserIntent.WIRED_ADB,
                    grant = PlatformGrant.GRANTED,
                    availability = Availability.READY,
                    configured = true,
                ),
            ),
        )
    }

    @Test
    fun autonomousDispatchStillRevalidatesConnectionPerInvocation() {
        val request = ShellExecRequest.fromRuntime(
            callId = "call-1",
            command = "pwd",
            agentId = "agent",
            snapshotId = "snapshot-1",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = 1,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        val unavailable = AuthorityState.configured(
            Authority.SHIZUKU,
            availability = Availability.TEMPORARILY_UNAVAILABLE,
            connection = Connection.DISCONNECTED,
        )
        val result = ShellDispatchValidator.validate(request, setOf(shell), unavailable)
        assertFalse(result.allowed)
        assertEquals(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, result.error?.code)
        assertTrue(DangerousModeExposure.shouldExpose(request.dangerousMode, setOf(shell), Authority.SHIZUKU, unavailable))
    }

    @Test
    fun registrySnapshotsAreImmutableAndOldRoutesFailClosed() = runBlocking {
        val spec = ToolSpec("probe", "read-only", "{\"type\":\"object\"}", shell)
        val calls = mutableListOf<String>()
        val registry = ToolRegistry(
            listOf(ToolRegistration(spec, "shizuku")),
            handlers = mapOf("shizuku" to ToolHandler { invocation ->
                calls += invocation.requestId
                ToolExecution.Value("{}")
            }),
        )
        val snapshot = registry.snapshot()
        val invocation = ToolInvocation.fromRuntime("call-1", snapshot.snapshotId, "agent", "probe", "{}")
        val route = registry.bind(invocation)
        assertTrue(route is ToolRouteResult.Resolved)
        assertEquals("shizuku", (route as ToolRouteResult.Resolved).route.ownerId)
        assertEquals(ToolExecution.Value("{}"), registry.dispatch(invocation))
        assertEquals(ToolExecution.Value("{}"), registry.dispatch(invocation))
        assertEquals(1, calls.size)

        val ownerChanged = registry.switchOwner("probe", "wired-adb")
        assertEquals(snapshot.schemaDigest, ownerChanged.schemaDigest)
        assertNotEquals(snapshot.snapshotId, ownerChanged.snapshotId)
        val stale = registry.bind(invocation)
        assertEquals(ToolErrorCode.SNAPSHOT_STALE, (stale as ToolRouteResult.Rejected).error.code)

        val replay = ToolInvocation(
            callId = invocation.callId,
            snapshotId = ownerChanged.snapshotId,
            agentId = invocation.agentId,
            name = invocation.name,
            argumentsJson = invocation.argumentsJson,
            requestId = invocation.requestId,
        )
        assertEquals(ToolErrorCode.CALL_ID_REPLAY, (registry.bind(replay) as ToolRouteResult.Rejected).error.code)
        assertSame(snapshot.specs.first(), snapshot.immutableSpecs().first())
    }

    @Test
    fun registryExecutionAndReplayKeysAreRuntimeInvocationIds() = runBlocking {
        val spec = ToolSpec("probe", "read-only", "{\"type\":\"object\"}", shell)
        val calls = mutableListOf<String>()
        val registry = ToolRegistry(
            listOf(ToolRegistration(spec, "provider")),
            handlers = mapOf("provider" to ToolHandler { invocation ->
                calls += invocation.invocationId
                ToolExecution.Value(invocation.argumentsJson)
            }),
        )
        val snapshot = registry.snapshot()
        val first = ToolInvocation.fromRuntime("same-model-call", snapshot.snapshotId, "agent", "probe", "{}")
        val second = ToolInvocation.fromRuntime("same-model-call", snapshot.snapshotId, "agent", "probe", "{\"n\":2}")

        assertNotEquals(first.invocationId, second.invocationId)
        assertEquals(ToolExecution.Value("{}"), registry.dispatch(first))
        assertEquals(ToolExecution.Value("{\"n\":2}"), registry.dispatch(second))
        assertEquals(2, calls.size)

        // An internal id cannot be reused to substitute a different payload;
        // dispatch must validate the binding before consulting its cache.
        val reusedId = ToolInvocation(
            callId = first.callId,
            snapshotId = first.snapshotId,
            agentId = first.agentId,
            name = first.name,
            argumentsJson = "{\"substituted\":true}",
            requestId = first.requestId,
        )
        val rejected = registry.dispatch(reusedId) as ToolExecution.Failed
        assertEquals(ToolErrorCode.CALL_ID_REPLAY, rejected.error.code)
        assertEquals(2, calls.size)
    }

    @Test
    fun approvalDigestChangesForEveryBoundField() {
        val request = ShellExecRequest.fromRuntime(
            callId = "call-1",
            command = "rm safe.txt",
            cwd = "/workspace/one",
            limits = ShellLimits(timeoutMs = 500, maxOutputBytes = 1000),
            agentId = "agent",
            snapshotId = "snapshot-1",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            policyVersion = 1,
            configSnapshotHash = "config-1",
            sessionIdentity = "session-1",
            skill = TrustedSkillInvocation("skill", 1),
            toolSchemaVersion = 1,
        )
        val base = ApprovalBinding.fromRequest(request)
        val changed = listOf(
            base.copy(requestId = "request-2") to ApprovalStaleReason.REQUEST_ID,
            base.copy(callId = "call-2") to ApprovalStaleReason.CALL_ID,
            base.copy(agentId = "agent-2") to ApprovalStaleReason.AGENT,
            base.copy(snapshotId = "snapshot-2") to ApprovalStaleReason.SNAPSHOT,
            base.copy(skillId = "skill-2", skillRevision = 2) to ApprovalStaleReason.SKILL,
            base.copy(command = "rm other.txt") to ApprovalStaleReason.COMMAND,
            base.copy(normalizedCwd = "/workspace/two") to ApprovalStaleReason.CWD,
            base.copy(timeoutMs = base.timeoutMs + 1) to ApprovalStaleReason.LIMITS,
            base.copy(maxOutputBytes = base.maxOutputBytes + 1) to ApprovalStaleReason.LIMITS,
            base.copy(selectedAuthority = Authority.WIRED_ADB) to ApprovalStaleReason.AUTHORITY,
            base.copy(dangerousMode = DangerousMode.ENABLED_AUTONOMOUS) to ApprovalStaleReason.DANGEROUS_MODE,
            base.copy(toolSchemaVersion = 2) to ApprovalStaleReason.TOOL_SCHEMA,
            base.copy(policyVersion = 2) to ApprovalStaleReason.POLICY,
            base.copy(configSnapshotHash = "config-2") to ApprovalStaleReason.CONFIG_SNAPSHOT,
            base.copy(sessionIdentity = "session-2") to ApprovalStaleReason.SESSION,
        )
        changed.forEach { (other, reason) ->
            assertNotEquals(base.digest, other.digest, reason.name)
            assertTrue(reason in base.staleReasons(other), reason.name)
        }
        assertEquals(base.digest, ApprovalBinding.canonicalSha256(base))
    }

    @Test
    fun shellRiskIsConfirmationOnlyAndConservative() {
        assertEquals(ShellRiskLevel.LOW, HighRiskDetector.assess("pwd").level)
        assertEquals(ShellRiskLevel.LOW, HighRiskDetector.assess("ls docs").level)
        val high = listOf(
            "echo x > out.txt" to ShellRiskReason.REDIRECTION,
            "cat a | grep x" to ShellRiskReason.PIPELINE_OR_COMPOSITION,
            "ls \$env:HOME" to ShellRiskReason.VARIABLE_EXPANSION,
            "cat $(pwd)" to ShellRiskReason.SUBSHELL,
            "cat *.txt" to ShellRiskReason.GLOB,
            "cat .env" to ShellRiskReason.SENSITIVE_READ,
            "curl https://example.invalid" to ShellRiskReason.DATA_EXFILTRATION,
            "whoami" to ShellRiskReason.PERMISSION_PROBE,
            "nohup sleep 5 &" to ShellRiskReason.BACKGROUND_RESIDENCY,
            "unknown_tool --version" to ShellRiskReason.UNKNOWN_COMMAND,
        )
        high.forEach { (command, reason) ->
            val assessment = HighRiskDetector.assess(command)
            assertEquals(ShellRiskLevel.HIGH, assessment.level, command)
            assertTrue(reason in assessment.reasons, command)
        }
    }

    @Test
    fun cancelUsesInternalRequestIdAndUnknownOutcomeIsNotRetryable() = runBlocking {
        var cancelledId: String? = null
        val executor = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult = ShellExecResult.unknownOutcome(request)
            override suspend fun cancel(requestId: String): Boolean {
                cancelledId = requestId
                return true
            }
        }
        val request = ShellExecRequest.fromRuntime(
            callId = "model-call",
            command = "pwd",
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = 1,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        assertTrue(executor.cancel(request.requestId))
        assertEquals(request.requestId, cancelledId)
        val result = executor.execute(request)
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, result.status)
        assertFalse(result.automaticReplayAllowed)

        assertFalse(ShellExecResult.failed(request).automaticReplayAllowed)
        assertFalse(ShellExecResult.timedOut(request, durationMs = 1).automaticReplayAllowed)
        assertFalse(ShellExecResult.cancelled(request, durationMs = 1).automaticReplayAllowed)
    }

    @Test
    fun runtimeFactoryKeepsNormalShellUnscopedAndRequiresTrustedSkillEnvelope() {
        val normal = ShellExecRequest.fromRuntime(
            callId = "model-call",
            command = "pwd",
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = 1,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        assertEquals(null, normal.skillId)
        assertThrows(IllegalArgumentException::class.java) {
            ShellExecRequest.fromRuntime(
                callId = "model-call-2",
                command = "pwd",
                agentId = "agent",
                snapshotId = "snapshot",
                selectedAuthority = Authority.SHIZUKU,
                dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
                policyVersion = "1",
                configSnapshotHash = "config",
                sessionIdentity = "session",
                skillId = "untrusted-skill",
            )
        }
        val trustedCompatibility = ShellExecRequest.fromRuntime(
            callId = "model-call-compat-trusted",
            command = "pwd",
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = "1",
            configSnapshotHash = "config",
            sessionIdentity = "session",
            skillId = "trusted-skill",
            trustedSkillEnvelope = true,
            skillRevision = 4,
        )
        assertEquals("trusted-skill", trustedCompatibility.skillId)
        assertEquals(4, trustedCompatibility.skillRevision)
        assertThrows(IllegalArgumentException::class.java) {
            ShellExecRequest.fromRuntime(
                callId = "model-call-compat-missing-revision",
                command = "pwd",
                agentId = "agent",
                snapshotId = "snapshot",
                selectedAuthority = Authority.SHIZUKU,
                dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
                policyVersion = "1",
                configSnapshotHash = "config",
                sessionIdentity = "session",
                skillId = "trusted-skill",
                trustedSkillEnvelope = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellExecRequest.fromRuntime(
                callId = "model-call-compat-invalid-revision",
                command = "pwd",
                agentId = "agent",
                snapshotId = "snapshot",
                selectedAuthority = Authority.SHIZUKU,
                dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
                policyVersion = "1",
                configSnapshotHash = "config",
                sessionIdentity = "session",
                skillId = "trusted-skill",
                trustedSkillEnvelope = true,
                skillRevision = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellExecRequest.fromRuntime(
                callId = "model-call-compat-empty-trusted-envelope",
                command = "pwd",
                agentId = "agent",
                snapshotId = "snapshot",
                selectedAuthority = Authority.SHIZUKU,
                dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
                policyVersion = "1",
                configSnapshotHash = "config",
                sessionIdentity = "session",
                trustedSkillEnvelope = true,
            )
        }
        val trusted = ShellExecRequest.fromTrustedSkill(
            callId = "model-call-3",
            command = "pwd",
            skill = TrustedSkillInvocation("trusted-skill", 4),
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = 1,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        assertEquals("trusted-skill", trusted.skillId)
        assertEquals(4, trusted.skillRevision)
    }

    @Test
    fun oneShotAdapterNeverDispatchesTheSameInternalRequestTwice() = runBlocking {
        var calls = 0
        val delegate = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                calls++
                return ShellExecResult.succeeded(request, 0, "ok", "", 1)
            }

            override suspend fun cancel(requestId: String): Boolean = false
        }
        val executor = OneShotShellExecutor(delegate)
        val request = ShellExecRequest.fromRuntime(
            callId = "model-call",
            command = "pwd",
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = 1,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        assertEquals(ShellExecutionStatus.SUCCEEDED, executor.execute(request).status)
        val duplicate = executor.execute(request)
        assertEquals(ShellExecutionStatus.FAILED, duplicate.status)
        assertEquals(ToolErrorCode.CALL_ID_REPLAY, duplicate.error?.code)
        assertEquals(1, calls)
    }

    @Test
    fun shellResultBudgetAccountsForEscapingAndSchemaIsBackendNeutral() {
        val request = ShellExecRequest.fromRuntime(
            callId = "call",
            command = "pwd",
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = 1,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        val quoted = "\\\"\\\\".repeat(10_000)
        val result = ShellExecResult.succeeded(request, 0, quoted, quoted, 1)
        assertTrue(result.estimatedSerializedBytes() < ShellLimits.MAX_SERIALIZED_RESULT_BYTES)
        assertTrue(ToolResultBudget.withinSerializedBudget("{\"result\":\"ok\"}"))
        assertFalse(ToolRegistry.SHELL_EXEC_SCHEMA.lowercase().contains("backend"))
        assertFalse(ToolRegistry.SHELL_EXEC_SCHEMA.lowercase().contains("serial"))
        assertFalse(ToolRegistry.SHELL_EXEC_SCHEMA.lowercase().contains("endpoint"))
        assertThrows(IllegalArgumentException::class.java) {
            ToolSpec("bad", "bad", "{\"backend\":\"secret\"}")
        }
        assertNotNull(result.requestId)
    }

    @Test
    fun privilegedDispatchRequiresStartedAuditAndCompletionFailureTripsFuse() = runBlocking {
        val request = ShellExecRequest.fromRuntime(
            callId = "call-audit",
            command = "pwd",
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS,
            policyVersion = 1,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        var calls = 0
        var started = 0
        var completed = 0
        val delegate = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                calls++
                return ShellExecResult.succeeded(request, 0, "ok", "", 1)
            }

            override suspend fun cancel(requestId: String): Boolean = false
        }
        val audit = object : ShellAuditSink {
            override suspend fun recordStarted(event: ShellAuditEvent): Boolean {
                started++
                return true
            }

            override suspend fun recordCompleted(event: ShellAuditEvent): Boolean {
                completed++
                return false
            }
        }
        val audited = AuditedShellExecutor(delegate, audit)
        val uncertain = audited.execute(request)
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, uncertain.status)
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, uncertain.error?.code)
        assertEquals(1, calls)
        assertEquals(1, started)
        assertEquals(1, completed)
        assertTrue(audited.degradedFuse.isOpen)
        assertEquals(ToolErrorCode.AUDIT_FUSE_OPEN, audited.execute(request).error?.code)
        assertEquals(1, calls)
    }

    @Test
    fun approvalLifecycleEventIsRedactedAndShellAuditLinksInternalRequestAndApproval() = runBlocking {
        val command = "printf secret-command"
        val cwd = "/private/secret-cwd"
        val request = ShellExecRequest.fromRuntime(
            callId = "model-call",
            command = command,
            cwd = cwd,
            agentId = "agent",
            snapshotId = "snapshot",
            selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            policyVersion = 3,
            configSnapshotHash = "config",
            sessionIdentity = "session",
        )
        val approvalId = "approval-1"
        val events = mutableListOf<ShellAuditEvent>()
        val audit = object : ShellAuditSink {
            override suspend fun recordStarted(event: ShellAuditEvent): Boolean {
                events += event
                return true
            }

            override suspend fun recordCompleted(event: ShellAuditEvent): Boolean {
                events += event
                return true
            }
        }
        val delegate = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult =
                ShellExecResult.succeeded(request, 0, "secret-output", "stderr-输出", 1)

            override suspend fun cancel(requestId: String): Boolean = false
        }
        val audited = AuditedShellExecutor(
            delegate = delegate,
            audit = audit,
            requestIdProvider = { "runtime-request-1" },
            approvalIdProvider = { approvalId },
        )
        assertEquals(ShellExecutionStatus.SUCCEEDED, audited.execute(request).status)
        assertEquals(2, events.size)
        assertEquals(setOf(ShellAuditPhase.STARTED, ShellAuditPhase.COMPLETED), events.map { it.phase }.toSet())
        assertEquals(setOf("runtime-request-1"), events.map { it.requestId }.toSet())
        assertEquals(setOf(approvalId), events.map { it.approvalId }.toSet())
        assertEquals(setOf(sha256Hex(command)), events.map { it.commandSha256 }.toSet())
        assertEquals(setOf(sha256Hex(normalizeCwd(cwd)!!)), events.map { it.cwdSha256 }.toSet())
        val completed = events.single { it.phase == ShellAuditPhase.COMPLETED }
        assertEquals("secret-output".toByteArray(Charsets.UTF_8).size.toLong(), completed.stdoutBytes)
        assertEquals("stderr-输出".toByteArray(Charsets.UTF_8).size.toLong(), completed.stderrBytes)
        assertEquals(completed.stdoutBytes + completed.stderrBytes, completed.outputBytes)
        events.forEach {
            assertFalse(it.toString().contains(command))
            assertFalse(it.toString().contains(cwd))
            assertFalse(it.toString().contains("secret-output"))
        }

        val lifecycle = ApprovalLifecycleEvent(
            approvalId = approvalId,
            requestId = "runtime-request-1",
            agentId = "agent",
            skillId = null,
            sessionIdentity = "session",
            transition = ApprovalLifecycleTransition.REQUESTED,
            bindingSha256 = sha256Hex("binding"),
            capability = shell,
            authority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            grantLifetime = GrantLifetime.ONCE,
            grantRevision = 1,
            policyVersion = 3,
            timestampMs = 1,
            reasonCode = ToolErrorCode.APPROVAL_REQUIRED,
        )
        assertFalse(lifecycle.toString().contains(command))
        assertFalse(lifecycle.toString().contains(cwd))
        assertFalse(lifecycle.toString().contains("secret-output"))
    }
}
