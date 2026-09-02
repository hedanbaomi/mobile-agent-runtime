// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.ApprovalLifecycleEvent
import runtime.mobileagent.skills.tooling.ApprovalLifecycleSink
import runtime.mobileagent.skills.tooling.ApprovalLifecycleTransition
import runtime.mobileagent.skills.tooling.AuthoritySelection
import runtime.mobileagent.skills.tooling.AuthorityState
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.DangerousModeExposure
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.skills.tooling.ShellAuditEvent
import runtime.mobileagent.skills.tooling.ShellAuditSink
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecResult
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.skills.tooling.ShellLimits
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceFileStat
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceText

class ToolingOrchestrationTest {
    @Test
    fun emptyFactoryIsAValidNoToolsStateInsteadOfAFactoryFailure() = runBlocking {
        val factory = ToolExecutorFactory()

        assertTrue(factory.toolingSpecs.isEmpty())
        assertTrue(factory.executor.specs.isEmpty())
        assertNull(factory.createToolRegistryOrNull())
        assertEquals(
            ToolResult.Invalid("Unknown tool"),
            factory.executor.invoke(ToolCall("call-empty", "missing_tool", "{}")),
        )
    }

    @Test
    fun dangerousBuildPolicyIsFailClosedUntilExplicitSafeVariantIsInjected() {
        assertFalse(DangerousBuildPolicy().permitsDangerousMode())
        assertFalse(DangerousBuildPolicy.fromBuildFlags(isDebuggable = true, controlPlaneAllowed = true).permitsDangerousMode())
        assertFalse(DangerousBuildPolicy.fromBuildFlags(isDebuggable = false, controlPlaneAllowed = false).permitsDangerousMode())
        assertFalse(DangerousBuildPolicy.fromBuildFlags(false, true, variantKnown = false).permitsDangerousMode())
        assertTrue(DangerousBuildPolicy.fromBuildFlags(false, true).permitsDangerousMode())
        assertTrue(DangerousBuildPolicy.testOnlyOverride().permitsDangerousMode())
    }

    @Test
    fun dangerousModePersistsAcrossLifecycleAndUsesCas() {
        val store = InMemoryDangerousModeStateStore()
        val manager = DangerousModeManager(store, DangerousBuildPolicy.fromBuildFlags(false, true))
        assertTrue(manager.setPolicy(DangerousMode.ENABLED_CONFIRM_HIGH_RISK).accepted)
        val revision = manager.state.value.revision
        manager.onTaskEnded()
        manager.onSessionEnded()
        manager.onBackgrounded()
        manager.onActivityRecreated()
        manager.onProcessRestarted()
        manager.onAuthorityDisconnected()
        assertEquals(DangerousMode.ENABLED_CONFIRM_HIGH_RISK, manager.state.value.policy)
        assertEquals(revision, manager.state.value.revision)
        assertEquals("CAS_CONFLICT", manager.compareAndSet(revision - 1, DangerousMode.DISABLED).reason)
        assertEquals(
            DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            DangerousModeManager(store, DangerousBuildPolicy.fromBuildFlags(false, true)).state.value.policy,
        )
        assertEquals(
            DangerousMode.DISABLED,
            DangerousModeManager(store, DangerousBuildPolicy()).state.value.policy,
        )
    }

    @Test
    fun authorityDisconnectPreservesSelectionIntentGrantAndDoesNotFallback() {
        val manager = configuredAuthorityManager()
        val before = manager.state.value
        manager.onBinderDisconnected()
        val after = manager.state.value
        assertEquals(Authority.SHIZUKU, after.selectedAuthority)
        assertEquals(before.status(Authority.SHIZUKU).userIntent, after.status(Authority.SHIZUKU).userIntent)
        assertEquals(PlatformGrant.GRANTED, after.status(Authority.SHIZUKU).grant)
        assertEquals(Availability.TEMPORARILY_UNAVAILABLE, after.status(Authority.SHIZUKU).availability)
        assertEquals(Connection.DISCONNECTED, after.status(Authority.SHIZUKU).connection)
        assertEquals(Authority.SHIZUKU, manager.selectedAuthorityForExecution())
        assertTrue(manager.withSelectedBackend(mapOf(Authority.WIRED_ADB to "other")).isFailure)
    }

    @Test
    fun shizukuAuthorizationSurvivesDisconnectAndProcessRecreation() {
        val store = InMemoryAuthorityStateStore()
        val manager = AuthorityManager(store)
        assertTrue(manager.selectAuthority(Authority.SHIZUKU))
        assertTrue(manager.setUserIntent(Authority.SHIZUKU, true))
        assertTrue(manager.setConfigured(Authority.SHIZUKU, true))
        manager.updatePlatformGrant(Authority.SHIZUKU, PlatformGrant.GRANTED)
        manager.updateAvailability(Authority.SHIZUKU, Availability.READY)
        manager.updateConnection(Authority.SHIZUKU, Connection.CONNECTED)

        manager.onBinderDisconnected()
        val restarted = AuthorityManager(store)
        val restored = restarted.state.value.status(Authority.SHIZUKU)
        assertEquals(Authority.SHIZUKU, restarted.state.value.selectedAuthority)
        assertEquals(AuthorityUserIntent.SHIZUKU, restored.userIntent)
        assertTrue(restored.configured)
        assertEquals(PlatformGrant.GRANTED, restored.grant)
        assertEquals(Availability.TEMPORARILY_UNAVAILABLE, restored.availability)
        assertEquals(Connection.DISCONNECTED, restored.connection)
    }

    @Test
    fun wiredAuthorizationSurvivesUsbWifiDisconnectAndProcessRecreation() {
        val store = InMemoryAuthorityStateStore()
        val manager = AuthorityManager(store)
        assertTrue(manager.selectAuthority(Authority.WIRED_ADB))
        assertTrue(manager.setUserIntent(Authority.WIRED_ADB, true))
        assertTrue(manager.setConfigured(Authority.WIRED_ADB, true))
        manager.updatePlatformGrant(Authority.WIRED_ADB, PlatformGrant.GRANTED)
        manager.updateAvailability(Authority.WIRED_ADB, Availability.READY)
        manager.updateConnection(Authority.WIRED_ADB, Connection.CONNECTED)

        manager.onUsbDisconnected()
        val restarted = AuthorityManager(store)
        val restored = restarted.state.value.status(Authority.WIRED_ADB)
        assertEquals(Authority.WIRED_ADB, restarted.state.value.selectedAuthority)
        assertEquals(AuthorityUserIntent.WIRED_ADB, restored.userIntent)
        assertTrue(restored.configured)
        assertEquals(PlatformGrant.GRANTED, restored.grant)
        assertEquals(Availability.TEMPORARILY_UNAVAILABLE, restored.availability)
        assertEquals(Connection.DISCONNECTED, restored.connection)
    }

    @Test
    fun shellExposureUsesFourPersistentConditionsAndNotConnectionReady() {
        val configured = AuthorityState.configured(
            Authority.SHIZUKU,
            availability = Availability.TEMPORARILY_UNAVAILABLE,
            connection = Connection.DISCONNECTED,
        )
        val selected = DangerousModeExposure.decide(
            DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            setOf(CapabilityId(CapabilityId.SHELL_EXECUTE)),
            Authority.SHIZUKU,
            configured,
        )
        assertTrue(selected.exposed)
        assertFalse(DangerousModeExposure.decide(DangerousMode.DISABLED, setOf(CapabilityId(CapabilityId.SHELL_EXECUTE)), Authority.SHIZUKU, configured).exposed)
        assertFalse(DangerousModeExposure.decide(DangerousMode.ENABLED_AUTONOMOUS, emptySet(), Authority.SHIZUKU, configured).exposed)
        assertFalse(DangerousModeExposure.decide(DangerousMode.ENABLED_AUTONOMOUS, setOf(CapabilityId(CapabilityId.SHELL_EXECUTE)), null, configured).exposed)
        assertFalse(DangerousModeExposure.decide(DangerousMode.ENABLED_AUTONOMOUS, setOf(CapabilityId(CapabilityId.SHELL_EXECUTE)), Authority.SHIZUKU, configured.copy(availability = Availability.UNSUPPORTED)).exposed)
    }

    @Test
    fun approvalUsesInternalRequestIdAndRejectsEveryChangedBindingDimension() {
        val engine = ApprovalEngine()
        val binding = binding("request-1", "model-1", command = "pwd")
        val scope = ApprovalScope(CapabilityId(CapabilityId.SHELL_EXECUTE), policyVersion = 7, grantRevision = 2)
        assertTrue(engine.request("request-1", "model-1", binding, scope).requiresUserAction)
        val stale = binding.copy(command = "id")
        val rejected = engine.approve("request-1", stale, scope)
        assertTrue(rejected is ApprovalDecision.Rejected)
        assertTrue((rejected as ApprovalDecision.Rejected).staleReasons.isNotEmpty())
        assertNull(engine.pending("request-1"))
        listOf(
            binding.copy(requestId = "request-2"),
            binding.copy(callId = "model-2"),
            binding.copy(agentId = "agent-2"),
            binding.copy(snapshotId = "snapshot-2"),
            binding.copy(skillId = "skill-1", skillRevision = 1),
            binding.copy(command = "id"),
            binding.copy(normalizedCwd = "/tmp"),
            binding.copy(timeoutMs = 2),
            binding.copy(maxOutputBytes = 2),
            binding.copy(selectedAuthority = Authority.WIRED_ADB),
            binding.copy(dangerousMode = DangerousMode.ENABLED_AUTONOMOUS),
            binding.copy(toolSchemaVersion = 2),
            binding.copy(policyVersion = 8),
            binding.copy(configSnapshotHash = "config-2"),
            binding.copy(sessionIdentity = "session-2"),
        ).forEach { changed -> assertTrue(binding.staleReasons(changed).isNotEmpty()) }
        val invalidatedReplay = engine.approve("request-1", binding, scope)
        assertEquals(ToolErrorCode.TIMEOUT, (invalidatedReplay as ApprovalDecision.Rejected).code)

        // A stale binding invalidates the process-local pending approval.  The
        // caller must allocate a fresh internal request id before asking again;
        // the old model call id is metadata and never owns the approval key.
        val freshBinding = binding.copy(requestId = "request-fresh")
        assertTrue(engine.request("request-fresh", "model-1", freshBinding, scope).requiresUserAction)
        val approved = engine.approve("request-fresh", freshBinding, scope)
        assertTrue(approved is ApprovalDecision.Approved)
        val grant = (approved as ApprovalDecision.Approved).grant
        val replay = engine.consume(grant, freshBinding, scope, currentGrantRevision = 2, currentPolicyVersion = 7)
        assertTrue(replay is ApprovalDecision.Approved)
        val second = engine.consume(grant, freshBinding, scope, currentGrantRevision = 2, currentPolicyVersion = 7)
        assertEquals(ToolErrorCode.CALL_ID_REPLAY, (second as ApprovalDecision.Rejected).code)
    }

    @Test
    fun approvalLifecycleIsBoundedOrderedExactlyOnceAndProcessLocal() {
        var now = 0L
        val events = mutableListOf<ApprovalLifecycleEvent>()
        val engine = ApprovalEngine(
            clock = ToolingClock { now },
            pendingTtlMs = 10L,
            lifecycleSink = ApprovalLifecycleSink { event -> events += event; true },
        )
        val binding = binding("request-life", "model-life", command = "rm -f secret")
        val scope = ApprovalScope(CapabilityId(CapabilityId.SHELL_EXECUTE), policyVersion = 7, grantRevision = 2)

        val pending = engine.request("request-life", "model-life", binding, scope).pending!!
        assertEquals(listOf(ApprovalLifecycleTransition.REQUESTED), events.map { it.transition })
        assertEquals(ToolErrorCode.APPROVAL_REQUIRED, events.single().reasonCode)
        assertTrue(events.all { it.agentId == "agent-1" && it.sessionIdentity == "session-1" })
        val grant = (engine.approve("request-life", binding, scope) as ApprovalDecision.Approved).grant
        assertEquals(
            listOf(ApprovalLifecycleTransition.REQUESTED, ApprovalLifecycleTransition.APPROVED),
            events.map { it.transition },
        )
        assertEquals(pending.approvalId, grant.approvalId)
        assertTrue(engine.consume(grant, binding, scope, 2, 7) is ApprovalDecision.Approved)
        assertEquals(
            listOf(
                ApprovalLifecycleTransition.REQUESTED,
                ApprovalLifecycleTransition.APPROVED,
                ApprovalLifecycleTransition.CONSUMED,
            ),
            events.map { it.transition },
        )
        // A one-shot grant cannot be consumed twice and does not duplicate
        // the CONSUMED lifecycle transition.
        assertEquals(ToolErrorCode.CALL_ID_REPLAY, (engine.consume(grant, binding, scope, 2, 7) as ApprovalDecision.Rejected).code)
        assertEquals(3, events.size)

        val deniedBinding = binding.copy(requestId = "request-deny", callId = "model-deny")
        engine.request("request-deny", "model-deny", deniedBinding, scope)
        assertEquals(ToolErrorCode.APPROVAL_DENIED, (engine.reject("request-deny") as ApprovalDecision.Rejected).code)
        assertEquals(ApprovalLifecycleTransition.DENIED, events.last().transition)

        val expiringBinding = binding.copy(requestId = "request-expire", callId = "model-expire")
        engine.request("request-expire", "model-expire", expiringBinding, scope)
        assertEquals(ToolErrorCode.TIMEOUT, (engine.expire("request-expire") as ApprovalDecision.Rejected).code)
        assertEquals(ApprovalLifecycleTransition.EXPIRED, events.last().transition)
        assertEquals(ToolErrorCode.TIMEOUT, events.last().reasonCode)

        val ttlBinding = binding.copy(requestId = "request-ttl", callId = "model-ttl")
        engine.request("request-ttl", "model-ttl", ttlBinding, scope)
        now = 10L
        assertNull(engine.pending("request-ttl"))
        assertEquals(ApprovalLifecycleTransition.EXPIRED, events.last().transition)

        val staleBinding = binding.copy(requestId = "request-stale", callId = "model-stale")
        engine.request("request-stale", "model-stale", staleBinding, scope)
        val replacement = engine.request("request-stale", "model-stale", staleBinding.copy(command = "id"), scope)
        assertTrue(replacement.requiresUserAction)
        assertEquals(ApprovalLifecycleTransition.INVALIDATED, events[events.size - 2].transition)
        assertEquals(ApprovalLifecycleTransition.REQUESTED, events.last().transition)

        // A new engine has an empty pending map even when given the same clock;
        // no durable command/cwd data is consulted across process restart.
        val restarted = ApprovalEngine(clock = ToolingClock { now }, pendingTtlMs = 10L)
        assertTrue(restarted.approve("request-stale", staleBinding, scope) is ApprovalDecision.Rejected)
        assertTrue(runCatching { ApprovalEngine(pendingTtlMs = ApprovalEngine.MAX_PENDING_TTL_MS + 1) }.isFailure)
    }

    @Test
    fun persistentCapabilityStoreCannotSatisfyPendingCommandApproval() {
        val store = InMemoryApprovalStateStore()
        val engine = ApprovalEngine(persistentStore = store)
        val binding = binding("request-persistent", "model-persistent", command = "id")
        val scope = ApprovalScope(CapabilityId(CapabilityId.SHELL_EXECUTE), policyVersion = 7, grantRevision = 2)
        val request = engine.request(
            "request-persistent",
            "model-persistent",
            binding,
            scope,
            suggestedLifetime = runtime.mobileagent.domain.GrantLifetime.PERSISTENT,
        )
        assertTrue(request.requiresUserAction)
        assertNotNull(request.pending)
        assertTrue(store.load().isEmpty())
        assertEquals(
            ToolErrorCode.INVALID_REQUEST,
            (engine.approve("request-persistent", binding, scope, runtime.mobileagent.domain.GrantLifetime.PERSISTENT) as ApprovalDecision.Rejected).code,
        )
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun resolverReadsLiveCanonicalGrantAndBindingBeforeAutonomousDispatch() {
        var liveGrant = CapabilityGrant(
            grantId = "grant-shell",
            agentId = "agent-1",
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
            policyVersion = 1,
            revision = 3,
        )
        var liveBinding = SnapshotGrantBinding(
            snapshotId = "snapshot-1",
            grantId = "grant-shell",
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
            policyVersion = 1,
        )
        val resolver = EffectiveCapabilityResolver(
            grants = CapabilityGrantReader { _, _ -> listOf(liveGrant) },
            bindings = SnapshotGrantBindingReader { listOf(liveBinding) },
        )
        val context = context().copy(
            effectiveCapabilities = setOf(CapabilityId(CapabilityId.SHELL_EXECUTE)),
            canonicalGrants = listOf(liveGrant),
            snapshotGrantBindings = listOf(liveBinding),
        )
        assertTrue(resolver.revalidate(context, CapabilityId(CapabilityId.SHELL_EXECUTE)))
        liveGrant = liveGrant.copy(revokedAt = "2026-08-30T00:00:00Z", revision = 4)
        assertFalse(resolver.revalidate(context, CapabilityId(CapabilityId.SHELL_EXECUTE)))
        liveGrant = liveGrant.copy(revokedAt = null, policyVersion = 2, revision = 5)
        assertFalse(resolver.revalidate(context, CapabilityId(CapabilityId.SHELL_EXECUTE)))
        liveGrant = liveGrant.copy(policyVersion = 1, revision = 6)
        liveBinding = liveBinding.copy(pathScope = "different")
        assertFalse(resolver.revalidate(context, CapabilityId(CapabilityId.SHELL_EXECUTE)))
    }

    @Test
    fun resolverEnforcesGrantLifetimeOwnerAndConsumedStateAcrossRestart() {
        val capability = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val taskGrant = CapabilityGrant(
            grantId = "grant-task",
            agentId = "agent-1",
            capability = capability,
            lifetime = GrantLifetime.TASK,
            taskId = "task-1",
            policyVersion = 1,
        )
        val taskBinding = SnapshotGrantBinding("snapshot-1", taskGrant.grantId, capability, policyVersion = 1)
        val taskContext = context().copy(
            taskIdentity = "task-1",
            canonicalGrants = listOf(taskGrant),
            snapshotGrantBindings = listOf(taskBinding),
            effectiveCapabilities = setOf(capability),
        )
        val resolver = EffectiveCapabilityResolver()
        assertTrue(resolver.revalidate(taskContext, capability))
        assertFalse(resolver.revalidate(taskContext.copy(taskIdentity = "task-2"), capability))
        assertFalse(resolver.revalidate(taskContext.copy(taskIdentity = ""), capability))
        // Reusing the same durable task id after process recreation is valid;
        // changing it is not an implicit renewal of the grant.
        assertTrue(resolver.revalidate(taskContext.copy(modelCallId = "model-after-restart"), capability))

        val sessionGrant = taskGrant.copy(
            grantId = "grant-session",
            lifetime = GrantLifetime.SESSION,
            taskId = null,
            sessionId = "session-1",
        )
        val sessionContext = taskContext.copy(
            sessionIdentity = "session-1",
            canonicalGrants = listOf(sessionGrant),
            snapshotGrantBindings = listOf(taskBinding.copy(grantId = sessionGrant.grantId)),
        )
        assertTrue(resolver.revalidate(sessionContext, capability))
        assertFalse(resolver.revalidate(sessionContext.copy(sessionIdentity = "session-2"), capability))
        assertTrue(resolver.revalidate(sessionContext.copy(modelCallId = "model-session-after-restart"), capability))

        val onceGrant = taskGrant.copy(
            grantId = "grant-once",
            lifetime = GrantLifetime.ONCE,
            taskId = null,
            consumedAt = "2026-08-30T00:00:00Z",
        )
        val onceContext = taskContext.copy(
            canonicalGrants = listOf(onceGrant),
            snapshotGrantBindings = listOf(taskBinding.copy(grantId = onceGrant.grantId)),
        )
        assertFalse(resolver.revalidate(onceContext, capability))
    }

    @Test
    fun resolverConsumeOnceDelegatesToExplicitCasConsumer() {
        val capability = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val grant = CapabilityGrant(
            grantId = "grant-once-consume",
            agentId = "agent-1",
            capability = capability,
            lifetime = GrantLifetime.ONCE,
            policyVersion = 1,
        )
        val binding = SnapshotGrantBinding("snapshot-1", grant.grantId, capability, policyVersion = 1)
        val context = context().copy(
            canonicalGrants = listOf(grant),
            snapshotGrantBindings = listOf(binding),
            effectiveCapabilities = setOf(capability),
        )
        var expectedRevision: Long? = null
        assertTrue(EffectiveCapabilityResolver().consumeOnce(context, capability, consumer = { consumed ->
            expectedRevision = consumed.revision
            true
        }))
        assertEquals(grant.revision, expectedRevision)
    }

    @Test
    fun resolverAuthorizeForDispatchDistinguishesDurableLifetimeAndOneShotCas() {
        val capability = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val resolver = EffectiveCapabilityResolver()
        var consumerCalls = 0

        val persistent = CapabilityGrant(
            grantId = "grant-dispatch-persistent",
            agentId = "agent-1",
            capability = capability,
            lifetime = GrantLifetime.PERSISTENT,
            policyVersion = 1,
        )
        val persistentContext = context().copy(
            effectiveCapabilities = setOf(capability),
            canonicalGrants = listOf(persistent),
            snapshotGrantBindings = listOf(bindingFor(persistent)),
        )
        assertEquals(
            DispatchAuthorization.ALLOWED_EXISTING_GRANT,
            resolver.authorizeForDispatch(persistentContext, capability, consumer = { consumerCalls++; true }),
        )
        assertEquals(0, consumerCalls)

        val task = persistent.copy(
            grantId = "grant-dispatch-task",
            lifetime = GrantLifetime.TASK,
            taskId = "task-1",
        )
        val taskContext = persistentContext.copy(
            taskIdentity = "task-1",
            canonicalGrants = listOf(task),
            snapshotGrantBindings = listOf(bindingFor(task)),
        )
        assertEquals(DispatchAuthorization.ALLOWED_EXISTING_GRANT, resolver.authorizeForDispatch(taskContext, capability, consumer = { consumerCalls++; true }))
        assertEquals(DispatchAuthorization.DENIED, resolver.authorizeForDispatch(taskContext.copy(taskIdentity = "task-2"), capability, consumer = { consumerCalls++; true }))

        val session = persistent.copy(
            grantId = "grant-dispatch-session",
            lifetime = GrantLifetime.SESSION,
            sessionId = "session-1",
        )
        val sessionContext = persistentContext.copy(
            sessionIdentity = "session-1",
            canonicalGrants = listOf(session),
            snapshotGrantBindings = listOf(bindingFor(session)),
        )
        assertEquals(DispatchAuthorization.ALLOWED_EXISTING_GRANT, resolver.authorizeForDispatch(sessionContext, capability, consumer = { consumerCalls++; true }))
        assertEquals(DispatchAuthorization.DENIED, resolver.authorizeForDispatch(sessionContext.copy(sessionIdentity = "session-2"), capability, consumer = { consumerCalls++; true }))

        val once = persistent.copy(grantId = "grant-dispatch-once", lifetime = GrantLifetime.ONCE)
        val onceContext = persistentContext.copy(
            canonicalGrants = listOf(once),
            snapshotGrantBindings = listOf(bindingFor(once)),
        )
        val olderOnce = once.copy(grantId = "grant-dispatch-once-older", revision = 1)
        val newerOnce = once.copy(grantId = "grant-dispatch-once-newer", revision = 2)
        val selected = mutableListOf<String>()
        assertEquals(
            DispatchAuthorization.ALLOWED_AFTER_ONCE_CONSUMPTION,
            resolver.authorizeForDispatch(
                onceContext.copy(
                    canonicalGrants = listOf(olderOnce, newerOnce),
                    snapshotGrantBindings = listOf(bindingFor(olderOnce), bindingFor(newerOnce)),
                ),
                capability,
                consumer = { grant ->
                    selected += grant.grantId
                    true
                },
            ),
        )
        assertEquals(listOf(newerOnce.grantId), selected)

        val cas = AtomicBoolean(true)
        assertEquals(
            DispatchAuthorization.ALLOWED_AFTER_ONCE_CONSUMPTION,
            resolver.authorizeForDispatch(onceContext, capability, consumer = { grant ->
                assertEquals(once.grantId, grant.grantId)
                cas.compareAndSet(true, false)
            }),
        )
        assertEquals(
            DispatchAuthorization.DENIED,
            resolver.authorizeForDispatch(onceContext, capability, consumer = { cas.compareAndSet(true, false) }),
        )

        val mixedContext = persistentContext.copy(
            canonicalGrants = listOf(persistent, once),
            snapshotGrantBindings = listOf(bindingFor(persistent), bindingFor(once)),
        )
        assertEquals(DispatchAuthorization.ALLOWED_EXISTING_GRANT, resolver.authorizeForDispatch(mixedContext, capability, consumer = { consumerCalls++; true }))
        assertEquals(0, consumerCalls)

        val noProof = persistentContext.copy(canonicalGrants = emptyList(), snapshotGrantBindings = emptyList())
        assertEquals(DispatchAuthorization.DENIED, resolver.authorizeForDispatch(noProof, capability, consumer = { consumerCalls++; true }))
    }

    @Test
    fun resolverAuthorizeForDispatchRejectsRevokedAndOutOfScopeRows() {
        val capability = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val resolver = EffectiveCapabilityResolver()
        val scoped = CapabilityGrant(
            grantId = "grant-dispatch-scoped",
            agentId = "agent-1",
            capability = capability,
            workspaceId = "workspace-1",
            pathScope = "allowed",
            lifetime = GrantLifetime.ONCE,
            policyVersion = 1,
        )
        val context = context().copy(
            effectiveCapabilities = setOf(capability),
            canonicalGrants = listOf(scoped),
            snapshotGrantBindings = listOf(bindingFor(scoped)),
        )
        val accept = { _: CapabilityGrant -> true }
        assertEquals(
            DispatchAuthorization.ALLOWED_AFTER_ONCE_CONSUMPTION,
            resolver.authorizeForDispatch(context, capability, accept, workspaceId = "workspace-1", path = "allowed/file.txt"),
        )
        assertEquals(
            DispatchAuthorization.DENIED,
            resolver.authorizeForDispatch(context, capability, accept, workspaceId = "workspace-1", path = "outside/file.txt"),
        )
        assertEquals(
            DispatchAuthorization.DENIED,
            resolver.authorizeForDispatch(context, capability, accept, workspaceId = "workspace-2", path = "allowed/file.txt"),
        )
        assertEquals(
            DispatchAuthorization.DENIED,
            resolver.authorizeForDispatch(context, capability, accept, workspaceId = "workspace-1"),
        )

        val revoked = scoped.copy(grantId = "grant-dispatch-revoked", revokedAt = "2026-08-30T00:00:00Z")
        val revokedContext = context.copy(
            canonicalGrants = listOf(revoked),
            snapshotGrantBindings = listOf(bindingFor(revoked)),
        )
        assertEquals(
            DispatchAuthorization.DENIED,
            resolver.authorizeForDispatch(revokedContext, capability, accept, workspaceId = "workspace-1", path = "allowed/file.txt"),
        )
    }

    private fun bindingFor(grant: CapabilityGrant): SnapshotGrantBinding = SnapshotGrantBinding(
        snapshotId = "snapshot-1",
        grantId = grant.grantId,
        capability = grant.capability,
        workspaceId = grant.workspaceId,
        pathScope = grant.pathScope,
        policyVersion = grant.policyVersion,
    )

    @Test
    fun effectiveCapabilitySetWithoutCanonicalGrantProofFailsClosed() {
        val context = ToolExecutionContext(
            agentId = "agent-1",
            snapshotId = "snapshot-1",
            modelCallId = "model-1",
            sessionIdentity = "session-1",
            configSnapshotHash = "config-1",
            policyVersion = 1,
            effectiveCapabilities = setOf(CapabilityId(CapabilityId.SHELL_EXECUTE)),
        )
        assertFalse(EffectiveCapabilityResolver().revalidate(context, CapabilityId(CapabilityId.SHELL_EXECUTE)))
    }

    @Test
    fun shellSchemaIsProviderIndependentAndHighRiskApprovalDoesNotRewriteCommand() = runBlocking {
        val authority = configuredAuthorityManager()
        val dangerous = DangerousModeManager(
            InMemoryDangerousModeStateStore(),
            DangerousBuildPolicy.fromBuildFlags(false, true),
        )
        dangerous.setPolicy(DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
        val dispatched = AtomicInteger(0)
        var received: String? = null
        val backend = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                dispatched.incrementAndGet()
                received = request.command
                return ShellExecResult.succeeded(request, 0, "ok", "", 1)
            }
            override suspend fun cancel(requestId: String): Boolean = true
        }
        val audit = object : ShellAuditSink {
            override suspend fun recordStarted(event: ShellAuditEvent) = true
            override suspend fun recordCompleted(event: ShellAuditEvent) = true
        }
        val executor = ShellToolExecutor(
            authorityManager = authority,
            dangerousModeManager = dangerous,
            approvalEngine = ApprovalEngine(),
            contextProvider = ::context,
            backends = mapOf(Authority.SHIZUKU to backend),
            auditSink = audit,
        )
        assertEquals(1, executor.toolingSpecs.size)
        val schema = executor.toolingSpecs.single().inputSchema
        assertTrue(schema.contains("command"))
        assertTrue(schema.contains("timeout_ms"))
        assertTrue(schema.contains("max_output_bytes"))
        assertFalse(schema.contains("timeoutMs"))
        assertFalse(schema.contains("maxOutputBytes"))
        assertFalse(schema.contains("authority"))
        assertFalse(schema.contains("serial"))
        assertFalse(schema.contains("endpoint"))
        assertTrue(executor.invoke(ToolCall("bad-type", "shell_exec", "{\"command\":\"pwd\",\"timeout_ms\":\"1\"}"), context()) is ToolResult.Invalid)
        val first = executor.invoke(ToolCall("model-1", "shell_exec", "{\"command\":\"rm -f x\"}"), context())
        assertEquals(ToolResult.NeedsApproval, first)
        assertEquals(0, dispatched.get())
        val result = executor.approve("model-1")
        assertTrue(result is ToolResult.Value)
        assertEquals("rm -f x", received)
        assertEquals(1, dispatched.get())
    }

    @Test
    fun shellDispatchGateFailsClosedForOnceConsumerBeforeBackend() = runBlocking {
        val authority = configuredAuthorityManager()
        val dangerous = DangerousModeManager(
            InMemoryDangerousModeStateStore(),
            DangerousBuildPolicy.fromBuildFlags(false, true),
        )
        dangerous.setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        val dispatched = AtomicInteger(0)
        val consumerCalls = AtomicInteger(0)
        val backend = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                dispatched.incrementAndGet()
                return ShellExecResult.succeeded(request, 0, "should-not-run", "", 1)
            }

            override suspend fun cancel(requestId: String): Boolean = true
        }
        val context = context().copy(
            canonicalGrants = listOf(context().canonicalGrants.single().copy(lifetime = GrantLifetime.ONCE)),
        )
        val executor = ShellToolExecutor(
            authorityManager = authority,
            dangerousModeManager = dangerous,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            backends = mapOf(Authority.SHIZUKU to backend),
            onceGrantConsumer = { consumerCalls.incrementAndGet(); false },
            auditSink = object : ShellAuditSink {
                override suspend fun recordStarted(event: ShellAuditEvent): Boolean = true
                override suspend fun recordCompleted(event: ShellAuditEvent): Boolean = true
            },
        )
        val call = ToolCall("model-once-denied", "shell_exec", "{\"command\":\"pwd\"}")
        val result = executor.invoke(call, context)
        assertTrue(result is ToolResult.Denied)
        assertEquals(ToolErrorCode.SHELL_CAPABILITY_DENIED.name, (result as ToolResult.Denied).reason)
        assertEquals(1, consumerCalls.get())
        assertEquals(0, dispatched.get())

        // The failed final gate is terminal for this model call; retrying the
        // same key does not re-consume or reach the backend.
        assertEquals(result, executor.invoke(call, context))
        assertEquals(1, consumerCalls.get())
        assertEquals(0, dispatched.get())
    }

    @Test
    fun concurrentOnceGrantConsumerRaceAllowsOneDispatchOnly() = runBlocking {
        val authority = configuredAuthorityManager()
        val dangerous = DangerousModeManager(
            InMemoryDangerousModeStateStore(),
            DangerousBuildPolicy.fromBuildFlags(false, true),
        )
        dangerous.setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        val dispatched = AtomicInteger(0)
        val consumerCalls = AtomicInteger(0)
        val onceConsumed = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                dispatched.incrementAndGet()
                started.complete(Unit)
                release.await()
                return ShellExecResult.succeeded(request, 0, "ok", "", 1)
            }

            override suspend fun cancel(requestId: String): Boolean = true
        }
        val baseContext = context()
        val context = baseContext.copy(
            canonicalGrants = listOf(baseContext.canonicalGrants.single().copy(lifetime = GrantLifetime.ONCE)),
        )
        val executor = ShellToolExecutor(
            authorityManager = authority,
            dangerousModeManager = dangerous,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            backends = mapOf(Authority.SHIZUKU to backend),
            onceGrantConsumer = {
                consumerCalls.incrementAndGet()
                onceConsumed.compareAndSet(false, true)
            },
            auditSink = object : ShellAuditSink {
                override suspend fun recordStarted(event: ShellAuditEvent): Boolean = true
                override suspend fun recordCompleted(event: ShellAuditEvent): Boolean = true
            },
        )
        val first = async(Dispatchers.Default) {
            executor.invoke(ToolCall("model-once-race-a", "shell_exec", "{\"command\":\"pwd\"}"), context)
        }
        val second = async(Dispatchers.Default) {
            executor.invoke(ToolCall("model-once-race-b", "shell_exec", "{\"command\":\"id\"}"), context)
        }
        withTimeout(1_000) { started.await() }
        release.complete(Unit)
        val results = listOf(withTimeout(1_000) { first.await() }, withTimeout(1_000) { second.await() })
        assertEquals(2, consumerCalls.get())
        assertTrue(onceConsumed.get())
        assertEquals(1, dispatched.get())
        assertEquals(1, results.count { it is ToolResult.Value })
        assertEquals(1, results.count {
            it is ToolResult.Denied && it.reason == ToolErrorCode.SHELL_CAPABILITY_DENIED.name
        })
    }

    @Test
    fun onceGrantAcceptedThenUnknownOutcomeIsTerminalAndNotReplayed() = runBlocking {
        val authority = configuredAuthorityManager()
        val dangerous = DangerousModeManager(
            InMemoryDangerousModeStateStore(),
            DangerousBuildPolicy.fromBuildFlags(false, true),
        )
        dangerous.setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        val dispatched = AtomicInteger(0)
        val consumerCalls = AtomicInteger(0)
        val backend = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                dispatched.incrementAndGet()
                return ShellExecResult.unknownOutcome(request)
            }

            override suspend fun cancel(requestId: String): Boolean = true
        }
        val baseContext = context()
        val context = baseContext.copy(
            canonicalGrants = listOf(baseContext.canonicalGrants.single().copy(lifetime = GrantLifetime.ONCE)),
        )
        val executor = ShellToolExecutor(
            authorityManager = authority,
            dangerousModeManager = dangerous,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            backends = mapOf(Authority.SHIZUKU to backend),
            onceGrantConsumer = { consumerCalls.incrementAndGet(); true },
            auditSink = object : ShellAuditSink {
                override suspend fun recordStarted(event: ShellAuditEvent): Boolean = true
                override suspend fun recordCompleted(event: ShellAuditEvent): Boolean = true
            },
        )
        val call = ToolCall("model-once-unknown", "shell_exec", "{\"command\":\"pwd\"}")
        val first = executor.invoke(call, context)
        assertTrue(first is ToolResult.UnknownOutcome)
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME.name, (first as ToolResult.UnknownOutcome).reason)
        assertEquals(1, consumerCalls.get())
        assertEquals(1, dispatched.get())

        val replay = executor.invoke(call, context)
        assertEquals(first, replay)
        assertEquals(1, consumerCalls.get())
        assertEquals(1, dispatched.get())
    }

    @Test
    fun typedInvocationKeepsInternalRequestIdAsApprovalKey() = runBlocking {
        val authority = configuredAuthorityManager()
        val dangerous = DangerousModeManager(
            InMemoryDangerousModeStateStore(),
            DangerousBuildPolicy.fromBuildFlags(false, true),
        )
        dangerous.setPolicy(DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
        var transportRequestId: String? = null
        val auditEvents = mutableListOf<ShellAuditEvent>()
        val backend = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                transportRequestId = request.requestId
                return ShellExecResult.succeeded(request, 0, "ok", "", 1)
            }

            override suspend fun cancel(requestId: String): Boolean = true
        }
        val audit = object : ShellAuditSink {
            override suspend fun recordStarted(event: ShellAuditEvent): Boolean {
                auditEvents += event
                return true
            }
            override suspend fun recordCompleted(event: ShellAuditEvent): Boolean {
                auditEvents += event
                return true
            }
        }
        val executor = ShellToolExecutor(
            authorityManager = authority,
            dangerousModeManager = dangerous,
            approvalEngine = ApprovalEngine(),
            contextProvider = ::context,
            backends = mapOf(Authority.SHIZUKU to backend),
            auditSink = audit,
        )
        val invocation = ToolInvocation.fromRuntime(
            callId = "typed-model",
            snapshotId = "snapshot-1",
            agentId = "agent-1",
            name = "shell_exec",
            argumentsJson = "{\"command\":\"rm -f x\"}",
        )
        val pending = executor.invoke(invocation, context())
        assertTrue(pending is ToolExecution.Failed)
        assertEquals(ToolErrorCode.APPROVAL_REQUIRED, (pending as ToolExecution.Failed).error.code)
        val result = executor.approve(invocation.requestId, context())
        assertTrue(result is ToolExecution.Value)
        assertNotNull(transportRequestId)
        assertTrue(transportRequestId != invocation.requestId)
        assertEquals(2, auditEvents.size)
        assertEquals(setOf(invocation.requestId), auditEvents.map { it.requestId }.toSet())
        assertEquals(1, auditEvents.map { it.approvalId }.distinct().size)
        assertTrue(auditEvents.all { it.approvalId != null })
        assertTrue(auditEvents.all { !it.toString().contains("rm -f x") })
        // A model call id is correlation only; it cannot be used as the
        // cancellation identity for an in-flight internal request.
        assertFalse(executor.cancel(invocation.callId))
    }

    @Test
    fun concurrentDuplicateInvocationReservesBeforeDispatch() = runBlocking {
        val authority = configuredAuthorityManager()
        val dangerous = DangerousModeManager(
            InMemoryDangerousModeStateStore(),
            DangerousBuildPolicy.fromBuildFlags(false, true),
        )
        dangerous.setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        val dispatched = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                dispatched.incrementAndGet()
                started.complete(Unit)
                release.await()
                return ShellExecResult.succeeded(request, 0, "ok", "", 1)
            }

            override suspend fun cancel(requestId: String): Boolean = true
        }
        val executor = ShellToolExecutor(
            authorityManager = authority,
            dangerousModeManager = dangerous,
            approvalEngine = ApprovalEngine(),
            contextProvider = ::context,
            backends = mapOf(Authority.SHIZUKU to backend),
            auditSink = object : ShellAuditSink {
                override suspend fun recordStarted(event: ShellAuditEvent): Boolean = true
                override suspend fun recordCompleted(event: ShellAuditEvent): Boolean = true
            },
        )
        val call = ToolCall("model-concurrent", "shell_exec", "{\"command\":\"pwd\"}")
        val first = async(Dispatchers.Default) { executor.invoke(call, context()) }
        withTimeout(1_000) { started.await() }

        // The first call is suspended inside provider execution.  The second
        // call must see the atomically registered request and never dispatch.
        val duplicate = async(Dispatchers.Default) { executor.invoke(call, context()) }
        val replay = withTimeout(1_000) { duplicate.await() }
        assertTrue(replay is ToolResult.UnknownOutcome)
        assertEquals(ToolErrorCode.CALL_ID_REPLAY.name, (replay as ToolResult.UnknownOutcome).reason)
        assertEquals(1, dispatched.get())

        release.complete(Unit)
        assertTrue(withTimeout(1_000) { first.await() } is ToolResult.Value)
        assertEquals(1, dispatched.get())
    }

    @Test
    fun completionAuditFailureIsUnknownAndReplayDenied() = runBlocking {
        val authority = configuredAuthorityManager()
        val dangerous = DangerousModeManager(
            InMemoryDangerousModeStateStore(),
            DangerousBuildPolicy.fromBuildFlags(false, true),
        )
        dangerous.setPolicy(DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
        val dispatched = AtomicInteger(0)
        val backend = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                dispatched.incrementAndGet()
                return ShellExecResult.succeeded(request, 0, "secret-output", "", 1)
            }

            override suspend fun cancel(requestId: String): Boolean = true
        }
        val executor = ShellToolExecutor(
            authorityManager = authority,
            dangerousModeManager = dangerous,
            approvalEngine = ApprovalEngine(),
            contextProvider = ::context,
            backends = mapOf(Authority.SHIZUKU to backend),
            auditSink = object : ShellAuditSink {
                override suspend fun recordStarted(event: ShellAuditEvent): Boolean = true
                override suspend fun recordCompleted(event: ShellAuditEvent): Boolean = false
            },
        )
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-audit-failure",
            snapshotId = "snapshot-1",
            agentId = "agent-1",
            name = "shell_exec",
            argumentsJson = "{\"command\":\"rm -f x\"}",
        )
        val pending = executor.invoke(invocation, context())
        assertTrue(pending is ToolExecution.Failed)
        assertEquals(ToolErrorCode.APPROVAL_REQUIRED, (pending as ToolExecution.Failed).error.code)

        val first = executor.approve(invocation.requestId, context())
        assertTrue(first is ToolExecution.Unknown)
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, (first as ToolExecution.Unknown).error.code)
        assertEquals(1, dispatched.get())

        // The completion audit was not proven.  Re-delivery returns the
        // cached unknown outcome and never retries the provider side effect.
        val replay = executor.invoke(invocation, context())
        assertTrue(replay is ToolExecution.Unknown)
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, (replay as ToolExecution.Unknown).error.code)
        assertEquals(1, dispatched.get())
    }

    @Test
    fun workspacePersistentGrantDispatchesWithoutSecondApprovalAndAuditsInternalRequest() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-audit",
            displayName = "Audit workspace",
            backendType = WorkspaceBackendType.INTERNAL,
            writable = true,
        )
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor

            override suspend fun writeText(request: runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> =
                WorkspaceResult.Success(WorkspaceMutation(request.relativePath, WorkspaceEntryType.FILE, 12, 2))
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT)
        val grant = CapabilityGrant(
            grantId = "grant-workspace-audit",
            agentId = "agent-1",
            capability = capability,
            workspaceId = descriptor.id,
            pathScope = "secret.txt",
            policyVersion = 1,
            revision = 2,
        )
        val snapshotBinding = SnapshotGrantBinding(
            snapshotId = "snapshot-1",
            grantId = grant.grantId,
            capability = grant.capability,
            workspaceId = descriptor.id,
            pathScope = grant.pathScope,
            policyVersion = 1,
        )
        val context = ToolExecutionContext(
            agentId = "agent-1",
            snapshotId = "snapshot-1",
            modelCallId = "model-workspace-audit",
            sessionIdentity = "session-1",
            configSnapshotHash = "config-1",
            policyVersion = 1,
            effectiveCapabilities = setOf(capability),
            canonicalGrants = listOf(grant),
            snapshotGrantBindings = listOf(snapshotBinding),
        )
        val auditEvents = mutableListOf<WorkspaceAuditEvent>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = object : WorkspaceAuditSink {
                override suspend fun record(event: WorkspaceAuditEvent): Boolean {
                    auditEvents += event
                    return true
                }
            },
            dangerousModeProvider = { DangerousMode.ENABLED_AUTONOMOUS },
        )
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-workspace-audit",
            snapshotId = context.snapshotId,
            agentId = context.agentId,
            name = UnifiedWorkspaceToolExecutor.FILE_WRITE_TEXT,
            argumentsJson = "{\"workspaceId\":\"workspace-audit\",\"relativePath\":\"secret.txt\",\"text\":\"secret-output\"}",
        )
        val result = executor.invoke(invocation, context)
        assertTrue(result is ToolExecution.Value)
        assertEquals(2, auditEvents.size)
        assertEquals(setOf(invocation.requestId), auditEvents.map { it.requestId }.toSet())
        assertTrue(auditEvents.all { it.approvalId == null })
        assertTrue(auditEvents.all { !it.toString().contains("secret.txt") })
        assertTrue(auditEvents.all { !it.toString().contains("secret-output") })
    }

    @Test
    fun concurrentDuplicateWorkspaceModelCallDispatchesExactlyOnce() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-duplicate-call",
            displayName = "Duplicate call workspace",
            backendType = WorkspaceBackendType.INTERNAL,
        )
        val dispatched = AtomicInteger(0)
        val backendStarted = CompletableDeferred<Unit>()
        val releaseBackend = CompletableDeferred<Unit>()
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT))

            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
                dispatched.incrementAndGet()
                backendStarted.complete(Unit)
                releaseBackend.await()
                return WorkspaceResult.Success(WorkspaceText(request.relativePath, "ok"))
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val context = workspaceContext(
            workspaceGrants(grant("grant-duplicate-call", CapabilityId(CapabilityId.FILE_READ_TEXT), descriptor.id, "shared.txt")),
        )
        val bindBarrier = CountDownLatch(2)
        val barrierArmed = AtomicBoolean(false)
        val resolver = EffectiveCapabilityResolver(
            nowEpochMs = {
                if (barrierArmed.get()) {
                    bindBarrier.countDown()
                    assertTrue(bindBarrier.await(1, TimeUnit.SECONDS))
                }
                System.currentTimeMillis()
            },
        )
        val auditStarted = AtomicInteger(0)
        val auditTerminal = AtomicInteger(0)
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            resolver = resolver,
            contextProvider = { context },
            auditSink = object : WorkspaceAuditSink {
                override suspend fun record(event: WorkspaceAuditEvent): Boolean {
                    when (event.phase) {
                        WorkspaceAuditPhase.STARTED -> auditStarted.incrementAndGet()
                        WorkspaceAuditPhase.TERMINAL -> auditTerminal.incrementAndGet()
                        WorkspaceAuditPhase.COMPLETED -> Unit
                    }
                    return true
                }
            },
        )
        val calls = List(2) {
            ToolInvocation.fromRuntime(
                callId = "model-duplicate-call",
                snapshotId = context.snapshotId,
                agentId = context.agentId,
                name = UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
                argumentsJson = "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"shared.txt\"}",
            )
        }
        barrierArmed.set(true)
        val executions = calls.map { invocation ->
            async(Dispatchers.Default) { executor.invoke(invocation, context) }
        }
        withTimeout(1_000) { backendStarted.await() }
        releaseBackend.complete(Unit)
        val results = executions.map { deferred -> withTimeout(1_000) { deferred.await() } }

        assertEquals(1, dispatched.get())
        assertEquals(1, results.count { it is ToolExecution.Value })
        assertEquals(1, results.count { it is ToolExecution.Unknown && it.error.code == ToolErrorCode.CALL_ID_REPLAY })
        assertEquals(1, auditStarted.get())
        assertEquals(1, auditTerminal.get())
    }

    @Test
    fun livePolicyRevisionChangeInvalidatesFrozenWorkspaceExecutor() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-policy-revision",
            displayName = "Policy revision workspace",
            backendType = WorkspaceBackendType.INTERNAL,
        )
        val dispatched = AtomicInteger(0)
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT))

            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
                dispatched.incrementAndGet()
                return WorkspaceResult.Success(WorkspaceText(request.relativePath, "ok"))
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val grant = grant("grant-policy-revision", CapabilityId(CapabilityId.FILE_READ_TEXT), descriptor.id, "note.txt")
        val context = workspaceContext(workspaceGrants(grant))
        var livePolicyVersion = 1L
        val resolver = EffectiveCapabilityResolver(
            grants = CapabilityGrantReader { _, _ -> listOf(grant) },
            bindings = SnapshotGrantBindingReader { context.snapshotGrantBindings },
            currentPolicyVersionReader = { livePolicyVersion },
        )
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            resolver = resolver,
            contextProvider = { context },
            auditSink = acceptingWorkspaceAuditSink(mutableListOf()),
        )
        fun invocation(callId: String) = ToolInvocation.fromRuntime(
            callId = callId,
            snapshotId = context.snapshotId,
            agentId = context.agentId,
            name = UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
            argumentsJson = "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"note.txt\"}",
        )

        assertTrue(executor.invoke(invocation("model-policy-v1"), context) is ToolExecution.Value)
        assertEquals(1, dispatched.get())

        livePolicyVersion = 2L
        val denied = executor.invoke(invocation("model-policy-v2"), context)
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, (denied as ToolExecution.Failed).error.code)
        assertEquals(1, dispatched.get())
    }

    @Test
    fun workspaceExceptionAfterStartedIsUnknownAndNeverReplayed() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-unknown-outcome",
            displayName = "Unknown outcome workspace",
            backendType = WorkspaceBackendType.INTERNAL,
        )
        val dispatched = AtomicInteger(0)
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT))

            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
                dispatched.incrementAndGet()
                error("transport ended after dispatch")
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val context = workspaceContext(
            workspaceGrants(grant("grant-unknown-outcome", CapabilityId(CapabilityId.FILE_READ_TEXT), descriptor.id, "note.txt")),
        )
        val auditEvents = mutableListOf<WorkspaceAuditEvent>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = acceptingWorkspaceAuditSink(auditEvents),
        )
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-unknown-outcome",
            snapshotId = context.snapshotId,
            agentId = context.agentId,
            name = UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
            argumentsJson = "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"note.txt\"}",
        )

        val first = executor.invoke(invocation, context)
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, (first as ToolExecution.Unknown).error.code)
        val replay = executor.invoke(invocation, context)
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, (replay as ToolExecution.Unknown).error.code)
        assertEquals(1, dispatched.get())
        assertEquals(2, auditEvents.size)
        assertEquals(WorkspaceAuditOutcome.UNKNOWN, auditEvents.single { it.phase == WorkspaceAuditPhase.TERMINAL }.outcome)
    }

    @Test
    fun selectedDisconnectRemainsExposedButInvocationFailsWithoutFallback() = runBlocking {
        val authority = configuredAuthorityManager()
        val dangerous = DangerousModeManager(InMemoryDangerousModeStateStore(), DangerousBuildPolicy.fromBuildFlags(false, true))
        dangerous.setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        val invoked = AtomicInteger(0)
        val backend = object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                invoked.incrementAndGet()
                return ShellExecResult.succeeded(request, 0, "bad", "", 1)
            }
            override suspend fun cancel(requestId: String): Boolean = true
        }
        val executor = ShellToolExecutor(
            authority,
            dangerous,
            ApprovalEngine(),
            ::context,
            mapOf(Authority.SHIZUKU to backend),
            auditSink = object : ShellAuditSink {
                override suspend fun recordStarted(event: ShellAuditEvent) = true
                override suspend fun recordCompleted(event: ShellAuditEvent) = true
            },
        )
        assertTrue(executor.toolingSpecs.isNotEmpty())
        authority.onBinderDisconnected()
        val result = executor.invoke(ToolCall("model-disconnect", "shell_exec", "{\"command\":\"pwd\"}"), context())
        assertTrue(result is ToolResult.Denied)
        assertEquals(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE.name, (result as ToolResult.Denied).reason)
        assertEquals(0, invoked.get())
    }

    @Test
    fun typedPathGuardRejectsTraversalBackslashAndAbsolutePath() {
        assertEquals("safe/file.txt", WorkspacePathPolicy.normalize("safe/file.txt", false))
        assertTrue(runCatching { WorkspacePathPolicy.normalize("../file.txt", false) }.isFailure)
        assertTrue(runCatching { WorkspacePathPolicy.normalize("safe\\file.txt", false) }.isFailure)
        assertTrue(runCatching { WorkspacePathPolicy.normalize("/absolute", false) }.isFailure)
    }

    @Test
    fun workspaceSpecsUseOnlyCanonicalAgentFacingNames() {
        val executor = UnifiedWorkspaceToolExecutor(
            registry = WorkspaceRegistry(),
            approvalEngine = ApprovalEngine(),
            contextProvider = ::context,
        )
        // A process-global inventory is not a valid model schema.  Without a
        // registered workspace and a snapshot grant, no workspace tool is
        // exposed at all.
        assertTrue(executor.toolingSpecs.isEmpty())
        assertTrue(executor.toolingSpecs.none { it.name == "file_copy" })
        assertTrue(executor.toolingSpecs.none { it.inputSchema.contains("backend") || it.inputSchema.contains("serial") || it.inputSchema.contains("endpoint") })
    }

    @Test
    fun workspaceSpecsIntersectSnapshotGrantAndImplementedBackendMethods() {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-capabilities",
            displayName = "Capabilities workspace",
            backendType = WorkspaceBackendType.INTERNAL,
            writable = true,
        )
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor

            // Deliberately over-advertise here: the executor must also check
            // the exact typed method instead of trusting a stale/default bit.
            override val capabilities: Set<CapabilityId> = workspaceCapabilities()

            override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> =
                WorkspaceResult.Success(WorkspaceListing(request.relativePath ?: ".", emptyList()))

            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> =
                WorkspaceResult.Success(WorkspaceText(request.relativePath, "ok"))
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val list = CapabilityId(CapabilityId.FILE_LIST)
        val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val enumerate = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE)
        val context = workspaceContext(
            workspaceGrants(
                grant("grant-enumerate", enumerate, descriptor.id),
                grant("grant-list", list, descriptor.id),
                grant("grant-read", read, descriptor.id, "note.txt"),
            ),
        )
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
        )

        assertEquals(
            listOf("workspace_list", "file_list", "file_read_text"),
            executor.toolingSpecs.map { it.name },
        )
        assertTrue(executor.toolingSpecs.none { it.name == "file_stat" })
        assertTrue(executor.toolingSpecs.none { it.name == "file_write_text" })
        assertTrue(executor.toolingSpecs.none { it.name == "file_move" })
    }

    @Test
    fun workspaceSchemasRejectArgumentsThatTheTypedBackendCannotHonor() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-schema-contract",
            displayName = "Schema contract workspace",
            backendType = WorkspaceBackendType.INTERNAL,
            writable = true,
        )
        val dispatched = AtomicInteger(0)
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(
                CapabilityId(CapabilityId.FILE_READ_TEXT),
                CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
                CapabilityId(CapabilityId.FILE_MOVE),
                CapabilityId(CapabilityId.FILE_DELETE),
            )

            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
                dispatched.incrementAndGet()
                return WorkspaceResult.Success(WorkspaceText(request.relativePath, "ok"))
            }

            override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> {
                dispatched.incrementAndGet()
                return WorkspaceResult.Success(WorkspaceMutation(request.relativePath, WorkspaceEntryType.DIRECTORY))
            }

            override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> {
                dispatched.incrementAndGet()
                return WorkspaceResult.Success(WorkspaceMutation(request.destinationPath, WorkspaceEntryType.FILE))
            }

            override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> {
                dispatched.incrementAndGet()
                return WorkspaceResult.Success(WorkspaceMutation(request.relativePath, WorkspaceEntryType.FILE))
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val context = workspaceContext(
            workspaceGrants(
                grant("grant-schema-read", CapabilityId(CapabilityId.FILE_READ_TEXT), descriptor.id),
                grant("grant-schema-create", CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY), descriptor.id),
                grant("grant-schema-move", CapabilityId(CapabilityId.FILE_MOVE), descriptor.id),
                grant("grant-schema-delete", CapabilityId(CapabilityId.FILE_DELETE), descriptor.id),
            ),
        )
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
        )
        val schemas = executor.toolingSpecs.associate { it.name to it.inputSchema }

        assertFalse(schemas.getValue(UnifiedWorkspaceToolExecutor.FILE_READ_TEXT).contains("expectedVersion"))
        assertFalse(schemas.getValue(UnifiedWorkspaceToolExecutor.FILE_MOVE).contains("\"replace\""))
        assertTrue(schemas.getValue(UnifiedWorkspaceToolExecutor.FILE_CREATE_DIRECTORY).contains("expectedVersion"))
        assertTrue(schemas.getValue(UnifiedWorkspaceToolExecutor.FILE_DELETE).contains("expectedVersion"))

        assertTrue(
            executor.invoke(
                ToolCall(
                    "read-unsupported-version",
                    UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
                    "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"note.txt\",\"expectedVersion\":1}",
                ),
                context,
            ) is ToolResult.Invalid,
        )
        assertTrue(
            executor.invoke(
                ToolCall(
                    "move-unsupported-replace",
                    UnifiedWorkspaceToolExecutor.FILE_MOVE,
                    "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"from.txt\",\"destinationRelativePath\":\"to.txt\",\"replace\":true}",
                ),
                context,
            ) is ToolResult.Invalid,
        )
        assertEquals(0, dispatched.get())
    }

    @Test
    fun workspaceListOnlyReturnsEnabledDescriptorsAuthorizedForCurrentAgent() = runBlocking {
        val alpha = WorkspaceDescriptor(
            id = "workspace-alpha",
            displayName = "Alpha",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = "C:/private/alpha",
        )
        val beta = WorkspaceDescriptor(
            id = "workspace-beta",
            displayName = "Beta unrelated",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = "C:/private/beta",
        )
        val disabled = WorkspaceDescriptor(
            id = "workspace-disabled",
            displayName = "Disabled",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = "C:/private/disabled",
            enabled = false,
        )
        val backend = { descriptor: WorkspaceDescriptor ->
            object : WorkspaceBackend {
                override val descriptor: WorkspaceDescriptor = descriptor
                override val capabilities: Set<CapabilityId> = setOf(
                    CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
                )
                override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> =
                    WorkspaceResult.Success(WorkspaceListing(request.relativePath ?: ".", emptyList()))
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(alpha, backend(alpha)))
        assertTrue(registry.register(beta, backend(beta)))
        assertTrue(registry.register(disabled, backend(disabled)))
        val enumerate = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE)
        val context = workspaceContext(
            workspaceGrants(
                grant("grant-alpha-enumerate", enumerate, alpha.id),
                grant("grant-disabled-enumerate", enumerate, disabled.id),
            ),
        )
        val auditEvents = mutableListOf<WorkspaceAuditEvent>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = acceptingWorkspaceAuditSink(auditEvents),
        )
        assertEquals(listOf("workspace_list"), executor.toolingSpecs.map { it.name })

        val invocation = ToolInvocation.fromRuntime(
            callId = "model-workspace-list",
            snapshotId = context.snapshotId,
            agentId = context.agentId,
            name = UnifiedWorkspaceToolExecutor.WORKSPACE_LIST,
            argumentsJson = "{}",
        )
        val result = executor.invoke(invocation, context)
        val json = (result as ToolExecution.Value).json
        assertTrue(json.contains("workspace-alpha"))
        assertTrue(json.contains("Alpha"))
        assertFalse(json.contains("workspace-beta"))
        assertFalse(json.contains("workspace-disabled"))
        assertFalse(json.contains("C:/private"))
        assertFalse(json.contains("rootReference"))
        assertEquals(2, auditEvents.size)
        assertTrue(auditEvents.all { it.operation == WorkspaceAuditOperation.ENUMERATE })
    }

    @Test
    fun workspaceReadListAndStatEmitRedactedStartedAndTerminalAudit() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-audit-read",
            displayName = "Read audit workspace",
            backendType = WorkspaceBackendType.INTERNAL,
        )
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(
                CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
                CapabilityId(CapabilityId.FILE_LIST),
                CapabilityId(CapabilityId.FILE_STAT),
                CapabilityId(CapabilityId.FILE_READ_TEXT),
            )

            override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> =
                WorkspaceResult.Success(WorkspaceListing(request.relativePath ?: ".", emptyList()))

            override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> =
                WorkspaceResult.Success(WorkspaceFileStat(request.relativePath, WorkspaceEntryType.FILE, 7L))

            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> =
                WorkspaceResult.Success(WorkspaceText(request.relativePath, "private text"))
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val list = CapabilityId(CapabilityId.FILE_LIST)
        val stat = CapabilityId(CapabilityId.FILE_STAT)
        val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val context = workspaceContext(
            workspaceGrants(
                grant("grant-list-audit", list, descriptor.id),
                grant("grant-stat-audit", stat, descriptor.id, "secret.txt"),
                grant("grant-read-audit", read, descriptor.id, "secret.txt"),
            ),
        )
        val auditEvents = mutableListOf<WorkspaceAuditEvent>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = acceptingWorkspaceAuditSink(auditEvents),
        )
        assertEquals(
            listOf("file_list", "file_stat", "file_read_text"),
            executor.toolingSpecs.map { it.name },
        )

        val calls = listOf(
            UnifiedWorkspaceToolExecutor.FILE_LIST to "{\"workspaceId\":\"${descriptor.id}\"}",
            UnifiedWorkspaceToolExecutor.FILE_STAT to "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"secret.txt\"}",
            UnifiedWorkspaceToolExecutor.FILE_READ_TEXT to "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"secret.txt\"}",
        )
        calls.forEachIndexed { index, (name, arguments) ->
            val invocation = ToolInvocation.fromRuntime(
                callId = "model-read-audit-$index",
                snapshotId = context.snapshotId,
                agentId = context.agentId,
                name = name,
                argumentsJson = arguments,
            )
            assertTrue(executor.invoke(invocation, context) is ToolExecution.Value)
        }

        assertEquals(6, auditEvents.size)
        assertEquals(3, auditEvents.count { it.phase == WorkspaceAuditPhase.STARTED })
        assertEquals(3, auditEvents.count { it.phase == WorkspaceAuditPhase.TERMINAL })
        assertEquals(setOf(WorkspaceAuditOperation.LIST, WorkspaceAuditOperation.STAT, WorkspaceAuditOperation.READ), auditEvents.map { it.operation }.toSet())
        auditEvents.groupBy { it.requestId }.values.forEach { events ->
            assertEquals(2, events.size)
            assertTrue(events.all { it.approvalId == null })
            assertEquals(WorkspaceAuditOutcome.SUCCEEDED, events.single { it.phase == WorkspaceAuditPhase.TERMINAL }.outcome)
            assertTrue(events.all { !it.toString().contains("secret.txt") })
            assertTrue(events.all { !it.toString().contains("private text") })
        }
        assertTrue(auditEvents.all { it.relativePathSha256.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun workspaceOnceGrantCasDenialNeverDispatchesAndAuditsDenied() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-once-denied",
            displayName = "Once denied workspace",
            backendType = WorkspaceBackendType.INTERNAL,
        )
        val dispatched = AtomicInteger(0)
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT))

            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
                dispatched.incrementAndGet()
                return WorkspaceResult.Success(WorkspaceText(request.relativePath, "must-not-run"))
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val context = workspaceContext(
            workspaceGrants(grant("grant-once-denied", read, descriptor.id, "secret.txt").copy(lifetime = GrantLifetime.ONCE)),
        )
        val consumerCalls = AtomicInteger(0)
        val auditEvents = mutableListOf<WorkspaceAuditEvent>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = acceptingWorkspaceAuditSink(auditEvents),
            onceGrantConsumer = {
                consumerCalls.incrementAndGet()
                false
            },
        )
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-once-denied",
            snapshotId = context.snapshotId,
            agentId = context.agentId,
            name = UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
            argumentsJson = "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"secret.txt\"}",
        )
        val result = executor.invoke(invocation, context)
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, (result as ToolExecution.Failed).error.code)
        assertEquals(1, consumerCalls.get())
        assertEquals(0, dispatched.get())
        assertEquals(2, auditEvents.size)
        assertEquals(WorkspaceAuditOutcome.DENIED, auditEvents.single { it.phase == WorkspaceAuditPhase.TERMINAL }.outcome)
    }

    @Test
    fun concurrentWorkspaceOnceGrantCasAllowsOneDispatchOnly() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-once-race",
            displayName = "Once race workspace",
            backendType = WorkspaceBackendType.INTERNAL,
        )
        val dispatched = AtomicInteger(0)
        val consumerCalls = AtomicInteger(0)
        val onceConsumed = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT))

            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
                dispatched.incrementAndGet()
                started.complete(Unit)
                release.await()
                return WorkspaceResult.Success(WorkspaceText(request.relativePath, "ok"))
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val baseContext = workspaceContext(
            workspaceGrants(grant("grant-once-race", read, descriptor.id, "shared.txt").copy(lifetime = GrantLifetime.ONCE)),
        )
        val auditEvents = mutableListOf<WorkspaceAuditEvent>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { baseContext },
            auditSink = acceptingWorkspaceAuditSink(auditEvents),
            onceGrantConsumer = {
                consumerCalls.incrementAndGet()
                onceConsumed.compareAndSet(false, true)
            },
        )
        val invocations = listOf("model-once-race-a", "model-once-race-b").map { callId ->
            ToolInvocation.fromRuntime(
                callId = callId,
                snapshotId = baseContext.snapshotId,
                agentId = baseContext.agentId,
                name = UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
                argumentsJson = "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"shared.txt\"}",
            )
        }
        val executions = invocations.map { invocation ->
            async(Dispatchers.Default) { executor.invoke(invocation, baseContext) }
        }
        withTimeout(1_000) { started.await() }
        release.complete(Unit)
        val results = executions.map { deferred -> withTimeout(1_000) { deferred.await() } }
        assertTrue(onceConsumed.get())
        assertEquals(2, consumerCalls.get())
        assertEquals(1, dispatched.get())
        assertEquals(1, results.count { it is ToolExecution.Value })
        assertEquals(1, results.count { it is ToolExecution.Failed && it.error.code == ToolErrorCode.CAPABILITY_DENIED })
        assertEquals(4, auditEvents.size)
        assertEquals(2, auditEvents.count { it.phase == WorkspaceAuditPhase.STARTED })
        assertEquals(2, auditEvents.count { it.phase == WorkspaceAuditPhase.TERMINAL })
    }

    @Test
    fun privilegedWorkspaceSwitchToNoneOrAnotherAuthorityNeverFallsBack() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-shizuku",
            displayName = "Selected provider workspace",
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "authority:SHIZUKU",
        )
        val invoked = AtomicInteger(0)
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT))
            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
                invoked.incrementAndGet()
                return WorkspaceResult.Success(WorkspaceText(request.relativePath, "must-not-run"))
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val shizuku = AuthorityState.configured(Authority.SHIZUKU)
        val wired = AuthorityState.configured(Authority.WIRED_ADB)
        val selectedShizuku = AuthoritySelection(Authority.SHIZUKU, mapOf(Authority.SHIZUKU to shizuku, Authority.WIRED_ADB to wired))
        val context = workspaceContext(
            workspaceGrants(grant("grant-shizuku-read", read, descriptor.id, "secret.txt")),
            authoritySelection = selectedShizuku,
        )
        var liveSelection: AuthoritySelection? = selectedShizuku
        val auditEvents = mutableListOf<WorkspaceAuditEvent>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = acceptingWorkspaceAuditSink(auditEvents),
            authoritySelectionProvider = { liveSelection },
        )
        assertEquals(listOf("file_read_text"), executor.toolingSpecs.map { it.name })

        // Switch to NONE after schema publication.  The frozen schema is not a
        // routing permission; the live check must reject without dispatch.
        liveSelection = AuthoritySelection(selected = null, states = mapOf(Authority.SHIZUKU to shizuku, Authority.WIRED_ADB to wired))
        val noneInvocation = ToolInvocation.fromRuntime(
            callId = "model-shizuku-none",
            snapshotId = context.snapshotId,
            agentId = context.agentId,
            name = UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
            argumentsJson = "{\"workspaceId\":\"${descriptor.id}\",\"relativePath\":\"secret.txt\"}",
        )
        val noneResult = executor.invoke(noneInvocation, context)
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, (noneResult as ToolExecution.Failed).error.code)
        assertEquals(0, invoked.get())

        // A context selecting another authority is not allowed to use a
        // Shizuku descriptor, even when Shizuku is also ready in the state map.
        val otherContext = context.copy(
            authoritySelection = AuthoritySelection(Authority.WIRED_ADB, mapOf(Authority.SHIZUKU to shizuku, Authority.WIRED_ADB to wired)),
            modelCallId = "model-wired-context",
        )
        val otherExecutor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { otherContext },
            auditSink = acceptingWorkspaceAuditSink(mutableListOf()),
        )
        assertTrue(otherExecutor.toolingSpecs.isEmpty())
    }

    @Test
    fun disconnectedPrivilegedWorkspaceRemainsExposedButDispatchFailsTemporarilyUnavailable() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-shizuku-offline",
            displayName = "Persisted Shizuku workspace",
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "authority:SHIZUKU",
        )
        val dispatched = AtomicInteger(0)
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.WORKSPACE_ENUMERATE))

            override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
                dispatched.incrementAndGet()
                return WorkspaceResult.Success(WorkspaceListing(request.relativePath ?: ".", emptyList()))
            }
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val enumerate = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE)
        val disconnected = AuthorityState.configured(Authority.SHIZUKU).preservingGrantAfterDisconnect()
        val selection = AuthoritySelection(
            selected = Authority.SHIZUKU,
            states = mapOf(Authority.SHIZUKU to disconnected),
        )
        val context = workspaceContext(
            workspaceGrants(grant("grant-shizuku-offline", enumerate, descriptor.id)),
            authoritySelection = selection,
        )
        val auditEvents = mutableListOf<WorkspaceAuditEvent>()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = acceptingWorkspaceAuditSink(auditEvents),
            authoritySelectionProvider = { selection },
        )

        assertEquals(listOf("workspace_list"), executor.toolingSpecs.map { it.name })
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-shizuku-offline",
            snapshotId = context.snapshotId,
            agentId = context.agentId,
            name = UnifiedWorkspaceToolExecutor.WORKSPACE_LIST,
            argumentsJson = "{}",
        )
        val result = executor.invoke(invocation, context)

        assertEquals(
            ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            (result as ToolExecution.Failed).error.code,
        )
        assertEquals(0, dispatched.get())
    }

    @Test
    fun workspaceEnumerationCannotCrossAgentSnapshotBoundary() = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-agent-one",
            displayName = "Agent one",
            backendType = WorkspaceBackendType.INTERNAL,
        )
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor
            override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.WORKSPACE_ENUMERATE))
            override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> =
                WorkspaceResult.Success(WorkspaceListing(request.relativePath ?: ".", emptyList()))
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val enumerate = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE)
        val agentOne = workspaceContext(
            workspaceGrants(grant("grant-agent-one-enumerate", enumerate, descriptor.id)),
        )
        val agentTwoGrant = grant("grant-agent-one-enumerate", enumerate, descriptor.id).copy(agentId = "agent-1")
        val agentTwo = agentOne.copy(
            agentId = "agent-2",
            modelCallId = "model-agent-two",
            effectiveCapabilities = setOf(enumerate),
            canonicalGrants = listOf(agentTwoGrant),
            snapshotGrantBindings = listOf(
                SnapshotGrantBinding(
                    snapshotId = agentOne.snapshotId,
                    grantId = agentTwoGrant.grantId,
                    capability = enumerate,
                    workspaceId = descriptor.id,
                ),
            ),
        )
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { agentTwo },
            auditSink = acceptingWorkspaceAuditSink(mutableListOf()),
        )
        assertTrue(executor.toolingSpecs.isEmpty())
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-agent-two-list",
            snapshotId = agentTwo.snapshotId,
            agentId = agentTwo.agentId,
            name = UnifiedWorkspaceToolExecutor.WORKSPACE_LIST,
            argumentsJson = "{}",
        )
        val result = executor.invoke(invocation, agentTwo)
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, (result as ToolExecution.Failed).error.code)
    }

    @Test
    fun factoryIncludesRuntimeMemoryToolsWithoutDuplicatingPublicInventory() {
        val memory = object : ToolExecutor {
            override val specs = listOf(
                memorySpec("memory_read", CapabilityId.MEMORY_READ),
                memorySpec("memory_search", CapabilityId.MEMORY_SEARCH),
                memorySpec("memory_append", CapabilityId.MEMORY_APPEND, sideEffect = true),
                memorySpec("memory_replace", CapabilityId.MEMORY_REPLACE, sideEffect = true),
            )

            override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Value("{}")
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("No pending approval")
        }
        val factory = ToolExecutorFactory(memory = memory)
        val names = factory.toolingSpecs.map { it.name }
        assertEquals(
            listOf("memory_read", "memory_search", "memory_append", "memory_replace"),
            names,
        )
        assertEquals(names, factory.executor.specs.map { it.name })
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun factorySettlesLegacyApprovalThroughTheOwningExecutorOnly() = runBlocking {
        class RecordingExecutor(private val toolName: String) : ToolExecutor {
            override val specs = listOf(
                LegacyToolSpec(
                    name = toolName,
                    description = "test executor",
                    parametersJson = "{\"type\":\"object\"}",
                    capability = "",
                    sideEffect = true,
                ),
            )
            var invocations = 0
            var approvals = 0
            var rejections = 0
            var expirations = 0

            override suspend fun invoke(call: ToolCall): ToolResult {
                invocations++
                return ToolResult.NeedsApproval
            }

            override suspend fun approve(callId: String): ToolResult {
                approvals++
                return ToolResult.Value("{\"approvedBy\":\"$toolName\"}")
            }

            override suspend fun reject(callId: String): ToolResult {
                rejections++
                return ToolResult.Value("{\"rejectedBy\":\"$toolName\"}")
            }

            override suspend fun expire(callId: String): ToolResult {
                expirations++
                return ToolResult.Value("{\"expiredBy\":\"$toolName\"}")
            }
        }

        val first = RecordingExecutor("tool_first")
        val second = RecordingExecutor("tool_second")
        val factory = ToolExecutorFactory(web = first, mcp = second)

        assertEquals(
            ToolResult.NeedsApproval,
            factory.executor.invoke(ToolCall("call-first", "tool_first", "{}")),
        )
        assertEquals(
            ToolResult.Value("{\"rejectedBy\":\"tool_first\"}"),
            factory.reject("call-first"),
        )
        assertEquals(1, first.rejections)
        assertEquals(0, second.rejections)
        assertEquals(
            ToolResult.Invalid("No pending approval"),
            factory.reject("call-first"),
        )
        assertEquals(1, first.rejections)
        assertEquals(0, first.approvals)
        assertEquals(0, second.approvals)

        assertEquals(
            ToolResult.NeedsApproval,
            factory.executor.invoke(ToolCall("call-approve", "tool_first", "{}")),
        )
        assertEquals(
            ToolResult.Value("{\"approvedBy\":\"tool_first\"}"),
            factory.approve("call-approve"),
        )
        assertEquals(1, first.approvals)
        assertEquals(0, second.approvals)

        assertEquals(
            ToolResult.NeedsApproval,
            factory.executor.invoke(ToolCall("call-second", "tool_second", "{}")),
        )
        assertEquals(
            ToolResult.Value("{\"expiredBy\":\"tool_second\"}"),
            factory.expire("call-second"),
        )
        assertEquals(0, first.expirations)
        assertEquals(1, second.expirations)
        assertEquals(
            ToolResult.Invalid("No pending approval"),
            factory.expire("call-second"),
        )
        assertEquals(1, second.expirations)

        val unknown = factory.executor.reject("not-seen") as ToolResult.Invalid
        assertEquals("No pending approval", unknown.reason)
        assertEquals(3, first.invocations + second.invocations)

        // A legacy call id cannot migrate to another owner between invoke and
        // settlement, even when the model supplies a different tool name.
        assertEquals(
            ToolResult.NeedsApproval,
            factory.executor.invoke(ToolCall("collision", "tool_first", "{}")),
        )
        assertTrue(
            factory.executor.invoke(ToolCall("collision", "tool_second", "{}")) is ToolResult.Invalid,
        )
        assertEquals(3, first.invocations)
        assertEquals(1, second.invocations)
    }

    private fun workspaceCapabilities(): Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
        CapabilityId(CapabilityId.FILE_LIST),
        CapabilityId(CapabilityId.FILE_STAT),
        CapabilityId(CapabilityId.FILE_READ_TEXT),
        CapabilityId(CapabilityId.FILE_WRITE_TEXT),
        CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
        CapabilityId(CapabilityId.FILE_MOVE),
        CapabilityId(CapabilityId.FILE_DELETE),
    )

    private fun grant(
        grantId: String,
        capability: CapabilityId,
        workspaceId: String?,
        pathScope: String? = null,
        agentId: String = "agent-1",
    ): CapabilityGrant = CapabilityGrant(
        grantId = grantId,
        agentId = agentId,
        capability = capability,
        workspaceId = workspaceId,
        pathScope = pathScope,
        policyVersion = 1,
        revision = 1,
    )

    private fun workspaceGrants(vararg grants: CapabilityGrant): List<CapabilityGrant> = grants.toList()

    private fun workspaceContext(
        grants: List<CapabilityGrant>,
        authoritySelection: AuthoritySelection = AuthoritySelection(selected = null),
    ): ToolExecutionContext {
        require(grants.isNotEmpty())
        val snapshotId = "snapshot-workspace"
        return ToolExecutionContext(
            agentId = grants.first().agentId,
            snapshotId = snapshotId,
            modelCallId = "model-workspace",
            sessionIdentity = "session-1",
            configSnapshotHash = "config-1",
            policyVersion = 1,
            effectiveCapabilities = grants.map { it.capability }.toSet(),
            canonicalGrants = grants,
            snapshotGrantBindings = grants.map { grant ->
                SnapshotGrantBinding(
                    snapshotId = snapshotId,
                    grantId = grant.grantId,
                    capability = grant.capability,
                    workspaceId = grant.workspaceId,
                    pathScope = grant.pathScope,
                    policyVersion = grant.policyVersion,
                )
            },
            authoritySelection = authoritySelection,
        )
    }

    private fun acceptingWorkspaceAuditSink(events: MutableList<WorkspaceAuditEvent>): WorkspaceAuditSink = object : WorkspaceAuditSink {
        override suspend fun record(event: WorkspaceAuditEvent): Boolean {
            events += event
            return true
        }
    }

    private fun configuredAuthorityManager(): AuthorityManager {
        val manager = AuthorityManager()
        manager.selectAuthority(Authority.SHIZUKU)
        manager.setUserIntent(Authority.SHIZUKU, true)
        manager.setConfigured(Authority.SHIZUKU, true)
        manager.updatePlatformGrant(Authority.SHIZUKU, PlatformGrant.GRANTED)
        manager.updateAvailability(Authority.SHIZUKU, Availability.READY)
        manager.updateConnection(Authority.SHIZUKU, Connection.CONNECTED, "opaque")
        return manager
    }

    private fun memorySpec(name: String, capability: String, sideEffect: Boolean = false) = LegacyToolSpec(
        name = name,
        description = "Test memory adapter",
        parametersJson = "{\"type\":\"object\",\"additionalProperties\":false}",
        capability = capability,
        sideEffect = sideEffect,
    )

    private fun context(): ToolExecutionContext {
        val grant = CapabilityGrant(
            grantId = "grant-shell",
            agentId = "agent-1",
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
            policyVersion = 1,
            revision = 1,
        )
        val binding = SnapshotGrantBinding(
            snapshotId = "snapshot-1",
            grantId = grant.grantId,
            capability = grant.capability,
            policyVersion = 1,
        )
        return ToolExecutionContext(
            agentId = "agent-1",
            snapshotId = "snapshot-1",
            modelCallId = "model-1",
            sessionIdentity = "session-1",
            configSnapshotHash = "config-1",
            policyVersion = 1,
            effectiveCapabilities = setOf(CapabilityId(CapabilityId.SHELL_EXECUTE)),
            canonicalGrants = listOf(grant),
            snapshotGrantBindings = listOf(binding),
        )
    }

    private fun binding(requestId: String, callId: String, command: String) = runtime.mobileagent.skills.tooling.ApprovalBinding(
        requestId = requestId,
        callId = callId,
        agentId = "agent-1",
        snapshotId = "snapshot-1",
        command = command,
        selectedAuthority = Authority.SHIZUKU,
        dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
        policyVersion = 7,
        configSnapshotHash = "config-1",
        sessionIdentity = "session-1",
    )
}
