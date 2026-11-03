// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.integration.RootSnapshotFactory
import runtime.mobileagent.integration.RootWorkspaceUiController
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore
import runtime.mobileagent.tooling.ToolExecutionContext
import runtime.mobileagent.tooling.ToolExecutorFactory
import runtime.mobileagent.workspace.CanonicalWorkspaceCoordinator
import runtime.mobileagent.workspace.CanonicalWorkspaceSink
import runtime.mobileagent.workspace.WorkspaceSelectionOutcome
import runtime.mobileagent.workspace.WorkspaceUiKind
import runtime.mobileagent.workspace.fallbackWorkspaceUiPresentation
import runtime.mobileagent.workspace.persistedWorkspaceFolderLabel
import runtime.mobileagent.workspace.workspaceUiKind
import runtime.mobileagent.ui.offlinePrivilegedAuthoritySummary

class RootCrossModuleIsolationTest {
    @Test
    fun nonRootAuthoritiesCreateSnapshotsWithoutCrashingExecutionStamp() = withHarness { h ->
        val factory = RootSnapshotFactory(h.db, h.coordinator)
        val internalWorkspace = Workspace(
            id = "internal",
            displayName = "App-private directory",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = "internal://files/workspace",
            scope = WorkspaceScope.SELECTED_DIRECTORY,
            readable = true,
            writable = true,
            enabled = true,
        )
        WorkspaceRepository(h.db).save(internalWorkspace)

        for (authority in listOf(Authority.NONE, Authority.SHIZUKU, Authority.WIRED_ADB)) {
            val currentPolicy = AuthorityPolicyRepository(h.db).getPolicy()
            AuthorityPolicyRepository(h.db).updatePolicy(
                expectedPolicyVersion = currentPolicy.policyVersion,
                selectedAuthority = authority,
                dangerousMode = DangerousMode.DISABLED,
            )
            val refreshedGrant = CapabilityGrant(
                grantId = "grant-${authority.name.lowercase()}",
                agentId = "agent",
                capability = CapabilityId(CapabilityId.FILE_LIST),
                workspaceId = "internal",
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = AuthorityPolicyRepository(h.db).getPolicy().policyVersion,
                createdAt = Utc.nowIso(),
            )
            CapabilityGrantRepository(h.db).save(refreshedGrant)
            assertNull(h.coordinator.currentExecutionStamp(authority))
            val snapWorkspace = factory.withWorkspace("agent", "internal", "snap-ws-${authority.name}", Utc.nowIso())
            assertTrue(CapabilityGrantRepository(h.db).listSnapshotBindings(snapWorkspace.id).any { it.grantId == refreshedGrant.grantId })
            val snapCurrent = factory.withCurrentGrants("agent", "snap-cur-${authority.name}", Utc.nowIso())
            assertTrue(CapabilityGrantRepository(h.db).listSnapshotBindings(snapCurrent.id).any { it.grantId == refreshedGrant.grantId })
        }
    }

    @Test
    fun internalAndSafWorkspacesKeepToolsAndRunAdmissionWhileRootSelectedOrDisconnected() = withHarness { h ->
        h.enableRootDirect()
        val internal = Workspace(
            id = "internal",
            displayName = "Internal",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = "internal://files/workspace",
            scope = WorkspaceScope.SELECTED_DIRECTORY,
            readable = true,
            writable = true,
            enabled = true,
        )
        val saf = Workspace(
            id = "saf-tree-1",
            displayName = "SAF Documents",
            backendType = WorkspaceBackendType.SAF_TREE,
            rootReference = "content://com.android.externalstorage.documents/tree/primary%3ADocs",
            scope = WorkspaceScope.SELECTED_DIRECTORY,
            readable = true,
            writable = true,
            enabled = true,
        )
        WorkspaceRepository(h.db).save(internal)
        WorkspaceRepository(h.db).save(saf)

        val policyVersion = AuthorityPolicyRepository(h.db).getPolicy().policyVersion
        val internalGrant = CapabilityGrant(
            grantId = "grant-internal",
            agentId = "agent",
            capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
            workspaceId = internal.id,
            lifetime = GrantLifetime.PERSISTENT,
            policyVersion = policyVersion,
            createdAt = Utc.nowIso(),
        )
        CapabilityGrantRepository(h.db).save(internalGrant)
        val snapshotFactory = RootSnapshotFactory(h.db, h.coordinator)
        val internalSnapshot = snapshotFactory.withWorkspace("agent", internal.id, "snap-internal", Utc.nowIso())
        val safSnapshot = snapshotFactory.withWorkspace("agent", saf.id, "snap-saf", Utc.nowIso())
        val unboundSnapshot = snapshotFactory.withWorkspace("agent", null, "snap-unbound", Utc.nowIso())
        h.insertConversation("conv-internal", internalSnapshot.id)
        h.insertConversation("conv-saf", safSnapshot.id)
        h.insertConversation("conv-unbound", unboundSnapshot.id)
        ConversationWorkspaceBindingRepository(h.db).bind("conv-internal", internal.id)
        ConversationWorkspaceBindingRepository(h.db).bind("conv-saf", saf.id)

        // Disconnect Root while ROOT_DIRECT stays selected globally.
        h.allowSessionConnect = false
        h.coordinator.detachSession(Authority.ROOT_DIRECT, "idle_disconnect")
        h.coordinator.markDegradedUncertainDescendants(Authority.ROOT_DIRECT)
        assertNull(h.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT))

        // Ordinary unbound, INTERNAL, and SAF runs still admit cleanly without a Root manifest.
        val unboundRun = h.coordinator.admitNewRun(RunRecord("run-unbound", unboundSnapshot.id, "conv-unbound", createdAt = Utc.nowIso()))
        val internalRun = h.coordinator.admitNewRun(RunRecord("run-internal", internalSnapshot.id, "conv-internal", createdAt = Utc.nowIso()))
        val safRun = h.coordinator.admitNewRun(RunRecord("run-saf", safSnapshot.id, "conv-saf", createdAt = Utc.nowIso()))
        assertFalse(RootRunManifest.hasRootStamp(unboundRun.manifestJson))
        assertEquals("{}", internalRun.manifestJson)
        assertEquals("{}", safRun.manifestJson)

        // RootToolExecutorAdmission returns null for non-Root contexts so UnifiedWorkspaceToolExecutor stays exposed.
        val internalCtx = ToolExecutionContext(
            agentId = "agent",
            snapshotId = internalSnapshot.id,
            modelCallId = "call-1",
            sessionIdentity = "conv-internal",
            taskIdentity = internalRun.runId,
            configSnapshotHash = "cfg",
            policyVersion = AuthorityPolicyRepository(h.db).getPolicy().policyVersion,
            canonicalGrants = listOf(internalGrant),
            snapshotGrantBindings = CapabilityGrantRepository(h.db).listSnapshotBindings(internalSnapshot.id),
        )
        assertNull(RootToolExecutorAdmission.create(h.db, h.coordinator, internalCtx))
    }

    @Test
    fun rootBoundWorkspaceStillFailsClosedWithoutFallbackWhenSessionLostOrAuthoritySwitched() = withHarness { h ->
        h.enableRootDirect()
        val attached = RootWorkspaceUiController(h.db, h.coordinator).attach(
            Authority.ROOT_DIRECT,
            "/data/local/root-workspace",
            writable = true,
            metadata = false,
            shell = false,
            target = WorkspacePickerTarget("agent"),
        )
        assertTrue(attached is WorkspaceAccessResult.Success)
        val rootWorkspaceId = (attached as WorkspaceAccessResult.Success).workspace.workspaceId
        val rootSnapshot = RootSnapshotFactory(h.db, h.coordinator)
            .withWorkspace("agent", rootWorkspaceId, "snap-root-bound", Utc.nowIso())
        h.insertConversation("conv-root", rootSnapshot.id)
        ConversationWorkspaceBindingRepository(h.db).bind("conv-root", rootWorkspaceId)

        // Active Root session admits a stamped Root run.
        val activeRun = h.coordinator.admitNewRun(RunRecord("run-root-ok", rootSnapshot.id, "conv-root", createdAt = Utc.nowIso()))
        assertTrue(RootRunAdmission(h.db).matchesAdmittedRun(activeRun.runId, h.initialExecution))

        // Unbound conversation on the same Agent must NOT inherit Root grants into its snapshot.
        val unbound = RootSnapshotFactory(h.db, h.coordinator)
            .withWorkspace("agent", null, "snap-explicit-unbound", Utc.nowIso())
        assertTrue(CapabilityGrantRepository(h.db).listSnapshotBindings(unbound.id).isEmpty())

        // Degrade/disconnect Root session -> Root run admission AND explicit Root workspace snapshot creation reject without fallback.
        h.coordinator.detachSession(Authority.ROOT_DIRECT, "test_disconnect")
        h.coordinator.markDegradedUncertainDescendants(Authority.ROOT_DIRECT)
        assertThrows(IllegalArgumentException::class.java) {
            RootSnapshotFactory(h.db, h.coordinator).withWorkspace("agent", rootWorkspaceId, "snap-root-offline-explicit", Utc.nowIso())
        }
        assertThrows(IllegalArgumentException::class.java) {
            h.coordinator.admitNewRun(RunRecord("run-root-dead", rootSnapshot.id, "conv-root", createdAt = Utc.nowIso()))
        }
        val deadRootCtx = ToolExecutionContext(
            agentId = "agent",
            snapshotId = rootSnapshot.id,
            modelCallId = "call-dead",
            sessionIdentity = "conv-root",
            taskIdentity = activeRun.runId,
            configSnapshotHash = "cfg",
            policyVersion = AuthorityPolicyRepository(h.db).getPolicy().policyVersion,
            canonicalGrants = CapabilityGrantRepository(h.db).forAgent("agent", includeRevoked = false),
            snapshotGrantBindings = CapabilityGrantRepository(h.db).listSnapshotBindings(rootSnapshot.id),
        )
        val deadRootExecutor = RootToolExecutorAdmission.create(h.db, h.coordinator, deadRootCtx)
        assertNotNull(deadRootExecutor)
        val nonRootWeb = object : ToolExecutor {
            override val specs = listOf(ToolSpec("web_search", "search", "{}", capability = "network", sideEffect = false))
            override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Value("{}")
            override suspend fun approve(callId: String): ToolResult = ToolResult.Denied("UNSUPPORTED")
        }
        val factoryWhenDead = ToolExecutorFactory(web = nonRootWeb, root = deadRootExecutor)
        assertEquals(listOf("web_search"), factoryWhenDead.toolingSpecs.map { it.name })

        // Switching global authority to SHIZUKU also rejects Root run admission without switching channels.
        val policy = AuthorityPolicyRepository(h.db).getPolicy()
        AuthorityPolicyRepository(h.db).updatePolicy(policy.policyVersion, Authority.SHIZUKU, DangerousMode.DISABLED)
        assertThrows(IllegalArgumentException::class.java) {
            h.coordinator.admitNewRun(RunRecord("run-root-wrong-channel", rootSnapshot.id, "conv-root", createdAt = Utc.nowIso()))
        }
    }

    @Test
    fun persistentRootDefaultSurvivesIdentityEpochRotationButRejectsConsentPolicyAndUnsealedInvalidation() = withHarness { h ->
        h.enableRootDirect()
        val ui = RootWorkspaceUiController(h.db, h.coordinator)
        val defaults = AgentWorkspaceDefaultRepository(h.db)
        val attached = ui.attach(
            Authority.ROOT_DIRECT,
            "/data/local/root-workspace",
            writable = true,
            metadata = false,
            shell = false,
            target = WorkspacePickerTarget("agent"),
        )
        assertTrue(attached is WorkspaceAccessResult.Success)
        val rootWorkspaceId = (attached as WorkspaceAccessResult.Success).workspace.workspaceId
        val epoch1 = h.initialExecution.identity.identityEpoch
        assertEquals(rootWorkspaceId, defaults.resolveForNewThread("agent"))
        assertTrue(ui.canSealNewThreadSnapshot(rootWorkspaceId))

        // 1. Pure session rotation: idle session detaches, then foreground reconnect advances identityEpoch while consentRevision and policyVersion stay intact.
        h.coordinator.detachSession(Authority.ROOT_DIRECT, "idle_rotation")
        assertNull(h.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT))
        assertTrue(h.coordinator.requestSessionFromForeground(Authority.ROOT_DIRECT, null))
        val epoch2Stamp = requireNotNull(h.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT))
        assertNotEquals(epoch1, epoch2Stamp.identity.identityEpoch)
        assertEquals(h.initialExecution.consentRevision, epoch2Stamp.consentRevision)
        assertEquals(h.initialExecution.policyVersion, epoch2Stamp.policyVersion)

        // Valid persistent Root grant is preserved across identityEpoch rotation and seals fresh snapshots!
        assertEquals(rootWorkspaceId, defaults.resolveForNewThread("agent"))
        assertTrue(ui.canSealNewThreadSnapshot(rootWorkspaceId))
        val rotatedSnapshot = RootSnapshotFactory(h.db, h.coordinator)
            .withWorkspace("agent", rootWorkspaceId, "snap-epoch2", Utc.nowIso())
        assertTrue(CapabilityGrantRepository(h.db).listSnapshotBindings(rotatedSnapshot.id).isNotEmpty())
        assertNotNull(
            RootSnapshotGrantRepository(h.db).revalidate(
                rotatedSnapshot.id,
                CapabilityGrantRepository(h.db).listSnapshotBindings(rotatedSnapshot.id).first().grantId,
                epoch2Stamp,
            ),
        )

        // 2. Temporary Root disconnect keeps durable SQLite default intact, while gating unsealable new-thread snapshots.
        h.allowSessionConnect = false
        h.coordinator.detachSession(Authority.ROOT_DIRECT, "temporary_offline")
        h.coordinator.markDegradedUncertainDescendants(Authority.ROOT_DIRECT)
        assertEquals(rootWorkspaceId, defaults.resolveForNewThread("agent"))
        assertFalse(ui.canSealNewThreadSnapshot(rootWorkspaceId))
        h.allowSessionConnect = true
        var degraded = RootAuthorityRepository(h.db).get(Authority.ROOT_DIRECT)
        degraded = requireNotNull(RootAuthorityRepository(h.db).advanceIdentityEpoch(Authority.ROOT_DIRECT, degraded.revision))
        RootReceiptRecoveryCoordinator(h.db).acknowledgeRecovery(
            authority = Authority.ROOT_DIRECT,
            expectedRevision = degraded.revision,
            verifiedIdentityEpoch = degraded.identityEpoch,
            userConfirmed = true,
        )
        assertTrue(h.coordinator.requestSessionFromForeground(Authority.ROOT_DIRECT, null))
        assertTrue(ui.canSealNewThreadSnapshot(rootWorkspaceId))

        // 3. Unsealed ordinary CapabilityGrant on a Root workspace is never treated as valid or backfilled with Root identity.
        assertThrows(IllegalArgumentException::class.java) {
            ui.requireOrdinaryGrantTarget(rootWorkspaceId)
        }
        AgentRepository(h.db).saveWithPrompt(AgentProfile("agent-unsealed", "Unsealed", "pending", "model", revision = 0), "Prompt")
        val forgedOrdinary = CapabilityGrant(
            grantId = "forged-ordinary-grant",
            agentId = "agent-unsealed",
            capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
            workspaceId = rootWorkspaceId,
            lifetime = GrantLifetime.PERSISTENT,
            policyVersion = AuthorityPolicyRepository(h.db).getPolicy().policyVersion,
            createdAt = Utc.nowIso(),
        )
        CapabilityGrantRepository(h.db).save(forgedOrdinary)
        val rootWorkspace = requireNotNull(WorkspaceRepository(h.db).get(rootWorkspaceId))
        assertTrue(ui.filterDurableWorkspaceGrants(rootWorkspace, listOf(forgedOrdinary)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            defaults.set("agent-unsealed", rootWorkspaceId, null)
        }

        // 4. Consent revocation (even if later re-enabled with a new consentRevision) invalidates old default binding.
        h.coordinator.setUserIntent(Authority.ROOT_DIRECT, false, h.manager, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
        assertNull(defaults.resolveForNewThread("agent"))
        h.coordinator.setUserIntent(Authority.ROOT_DIRECT, true, h.manager, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
        assertNull(defaults.resolveForNewThread("agent"))
    }

    @Test
    fun draftRootSelectionRecentInclusionDiagnosticsAndUiLabelsMatchRootAuthority() = withHarness { h ->
        h.enableRootDirect()
        val ui = RootWorkspaceUiController(h.db, h.coordinator)
        val sink = object : CanonicalWorkspaceSink {
            override suspend fun attachPrivileged(
                authority: Authority,
                request: runtime.mobileagent.skills.tooling.WorkspaceAttachRequest,
                plan: WorkspaceIntentPlan,
                target: WorkspaceTarget,
            ) = error("unused")

            override suspend fun attachSaf(
                uri: android.net.Uri,
                resultFlags: Int,
                plan: WorkspaceIntentPlan,
                target: WorkspaceTarget,
            ) = error("unused")

            override suspend fun attachPrivilegedPath(
                authority: Authority,
                workspaceId: String,
                displayName: String,
                absolutePath: String,
                plan: WorkspaceIntentPlan,
                target: WorkspaceTarget,
            ) = error("unused")

            override suspend fun attachRootDirectory(
                authority: Authority,
                absolutePath: String,
                writable: Boolean,
                metadata: Boolean,
                shell: Boolean,
                plan: WorkspaceIntentPlan,
                target: WorkspaceTarget,
            ) = ui.attachWithPlan(authority, absolutePath, writable, metadata, shell, plan, target)

            override suspend fun openFullDeviceFiles(
                authority: Authority,
                request: runtime.mobileagent.skills.tooling.FullDeviceFilesRequest,
                plan: WorkspaceIntentPlan,
                target: WorkspaceTarget,
            ) = error("unused")

            override suspend fun useRecent(
                workspaceId: String,
                plan: WorkspaceIntentPlan,
                target: WorkspaceTarget,
            ) = ui.useRecentNullable(workspaceId, WorkspacePickerTarget(target.agentId, target.threadId), plan)

            override suspend fun commitDraft(draft: WorkspaceDraft, agentId: String) = ui.commitDraft(draft, agentId)
        }
        val coordinator = CanonicalWorkspaceCoordinator(sink)

        // Draft Agent selection stages without persisting orphan grants; saving Agent commits sealed Root grants + default.
        val staged = runBlocking {
            coordinator.selectRootDirectory(
                intent = WorkspaceIntent.SET_AGENT_DEFAULT,
                authority = Authority.ROOT_DIRECT,
                absolutePath = "/data/local/root-workspace",
                writable = true,
                metadata = true,
                shell = false,
                target = WorkspaceTarget(),
            )
        }
        assertTrue(staged is WorkspaceSelectionOutcome.Staged)
        val draft = (staged as WorkspaceSelectionOutcome.Staged).draft
        AgentRepository(h.db).saveWithPrompt(AgentProfile("draft-agent", "Draft Agent", "pending", "model", revision = 0), "Prompt")
        val committed = runBlocking { coordinator.commitDraft(draft, "draft-agent") }
        assertTrue(committed is WorkspaceSelectionOutcome.Committed)
        assertEquals(draft.workspaceId, AgentWorkspaceDefaultRepository(h.db).resolveForNewThread("draft-agent"))

        // Unsaved draft Agents cannot bypass per-Agent foreground Root admission via selectRecent or empty-capability drafts,
        // and writable=false + shell=true never escalates to file write/move/delete capabilities.
        val draftRecentAttempt = runBlocking {
            coordinator.selectRecent(WorkspaceIntent.SET_AGENT_DEFAULT, draft.workspaceId, WorkspaceTarget())
        }
        assertTrue(draftRecentAttempt is WorkspaceSelectionOutcome.Failed)
        val emptyCapCommit = ui.commitDraft(draft.copy(requestedCapabilities = emptySet()), "draft-agent")
        assertTrue(emptyCapCommit is WorkspaceAccessResult.Failure)

        h.coordinator.setRootShellConsent(Authority.ROOT_DIRECT, enabled = true, userConfirmed = true, authorityManager = h.manager)
        val roShellStaged = runBlocking {
            coordinator.selectRootDirectory(
                intent = WorkspaceIntent.SET_AGENT_DEFAULT,
                authority = Authority.ROOT_DIRECT,
                absolutePath = "/data/local/root-workspace",
                writable = false,
                metadata = false,
                shell = true,
                target = WorkspaceTarget(),
            )
        }
        assertTrue(roShellStaged is WorkspaceSelectionOutcome.Staged)
        AgentRepository(h.db).saveWithPrompt(AgentProfile("ro-shell-agent", "RO Shell Agent", "pending", "model", revision = 0), "Prompt")
        val roShellCommitted = runBlocking { coordinator.commitDraft((roShellStaged as WorkspaceSelectionOutcome.Staged).draft, "ro-shell-agent") }
        assertTrue(roShellCommitted is WorkspaceSelectionOutcome.Committed)
        val roShellCaps = CapabilityGrantRepository(h.db).forAgent("ro-shell-agent", includeRevoked = false).mapTo(mutableSetOf()) { it.capability.value }
        assertEquals(
            setOf(CapabilityId.FILE_LIST, CapabilityId.FILE_STAT, CapabilityId.FILE_READ_TEXT, CapabilityId.SHELL_EXECUTE),
            roShellCaps,
        )

        val rootWorkspace = requireNotNull(WorkspaceRepository(h.db).get(draft.workspaceId))
        assertTrue(ui.includeInRecent(rootWorkspace, hasPrivilegedBinding = false))
        assertTrue(ui.isDiagnosticWorkspaceReady(rootWorkspace, registered = null))
        assertFalse(ui.isAuthorityMismatched(rootWorkspace, Authority.ROOT_DIRECT, ElevatedAuthority.ROOT_DIRECT))
        assertTrue(ui.isAuthorityMismatched(rootWorkspace, Authority.ROOT_DIRECT, ElevatedAuthority.SHIZUKU_ROOT))

        // UI presentation labels distinguish ROOT_DIRECT and SHIZUKU_ROOT from ADB channels.
        assertEquals(WorkspaceUiKind.PRIVILEGED_ROOT_DIRECT, workspaceUiKind(rootWorkspace, Authority.ROOT_DIRECT))
        assertEquals(WorkspaceUiKind.PRIVILEGED_SHIZUKU_ROOT, workspaceUiKind(rootWorkspace, Authority.SHIZUKU_ROOT))
        val namelessRoot = rootWorkspace.copy(displayName = "recovery_locator")
        assertEquals("直接 Root 目录", fallbackWorkspaceUiPresentation(namelessRoot, Authority.ROOT_DIRECT, chinese = true)?.title)
        assertEquals("Shizuku Root directory", fallbackWorkspaceUiPresentation(namelessRoot, Authority.SHIZUKU_ROOT, chinese = false)?.title)
        assertEquals(
            "Root 直连目录 1",
            persistedWorkspaceFolderLabel(WorkspaceBackendType.PRIVILEGED, "recovery_locator", 1, fullDevice = false, authority = Authority.ROOT_DIRECT),
        )
        assertTrue(offlinePrivilegedAuthoritySummary(Authority.ROOT_DIRECT, chinese = true).contains("Root"))
        assertTrue(offlinePrivilegedAuthoritySummary(Authority.SHIZUKU_ROOT, chinese = false).contains("Root"))
        assertTrue(offlinePrivilegedAuthoritySummary(Authority.SHIZUKU, chinese = true).contains("ADB"))
    }

    private fun withHarness(block: (Harness) -> Unit) {
        val dir = Files.createTempDirectory("root-cross-module-isolation")
        InMemoryRootSqlConnection().use { db ->
            val h = Harness(db, dir.toFile())
            try {
                block(h)
            } finally {
                h.coordinator.close()
            }
        }
        dir.toFile().deleteRecursively()
    }

    private class Harness(val db: SqlConnection, dir: java.io.File) {
        val signing = "b".repeat(64)
        val boundary = RootControlPlaneBoundary(
            "/data/user/0/runtime.mobileagent",
            RootFdNodeIdentity(1, 9101, 77, true, false, false, 2, 448, 10148, 10148, "label"),
            "/data/user_de/0/runtime.mobileagent",
            RootFdNodeIdentity(1, 9102, 77, true, false, false, 2, 448, 10148, 10148, "label"),
        )
        val fd = InMemoryServiceFdOps("/data/local/root-workspace")
        val manager = AuthorityManager(InMemoryAuthorityStateStore())
        var allowSessionConnect: Boolean = true
        val coordinator: RootRuntimeCoordinator
        lateinit var initialExecution: RootExecutionStamp

        init {
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1))
            profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", emptySet(), contextLimit = 16384, outputLimit = 512, revision = 1))
            AgentRepository(db).saveWithPrompt(AgentProfile("agent", "Agent", "pending", "model", revision = 0), "Prompt")
            coordinator = RootRuntimeCoordinator(
                FakeTestContext(dir),
                db,
                fdOps = fd,
                sessionConnector = object : RootSessionConnector {
                    override fun tryAcquireSession(
                        authority: Authority,
                        identityEpoch: Long,
                        appInstanceId: String,
                        shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
                    ): RootAcquiredSession? {
                        if (!allowSessionConnect) return null
                        val freshEngine = RootPrivilegedServiceEngine(
                            managerAuthorizationOverride = { RootManagerAuthorization.GRANTED },
                            fdOps = fd,
                            serviceUidProvider = { 0 },
                            controlPlaneOverride = boundary,
                        ).pinTestHost(boundary, signing)
                        val freshTransport = EngineRootChannelTransport(freshEngine, 10148)
                        val request = RootServiceHandshakeRequest(
                            authority, identityEpoch, 10148, 0, appInstanceId,
                            "runtime.mobileagent", signing, signing,
                            boundary.credentialEncryptedDataDir, boundary.deviceProtectedDataDir,
                        )
                        val identity = freshTransport.handshake(request)?.identity ?: run {
                            freshTransport.close()
                            return null
                        }
                        return RootAcquiredSession(
                            identity,
                            RootCallerObservation(10148, 0, "runtime.mobileagent", 0, signing),
                            freshTransport,
                            boundary,
                        )
                    }
                },
                controlPlaneBoundaryOverride = boundary,
                locatorCipher = TestRootLocatorCipher(),
            )
        }

        fun enableRootDirect() {
            val policy = AuthorityPolicyRepository(db).getPolicy()
            AuthorityPolicyRepository(db).updatePolicy(policy.policyVersion, Authority.ROOT_DIRECT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            coordinator.setUserIntent(Authority.ROOT_DIRECT, true, manager, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
            initialExecution = requireNotNull(coordinator.currentExecutionStamp(Authority.ROOT_DIRECT))
        }

        fun insertConversation(id: String, snapshotId: String) {
            val now = Utc.nowIso()
            db.execute(
                "INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                listOf(id, snapshotId, snapshotId, "Fixture", now, now),
            )
        }
    }
}
