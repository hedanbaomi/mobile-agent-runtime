// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import runtime.mobileagent.AgentGrantPort
import runtime.mobileagent.SettingsAuthorityPort
import runtime.mobileagent.SettingsAuthorityPortProvider
import runtime.mobileagent.SettingsAuthorityMutation
import runtime.mobileagent.SettingsAuthorityProviderState
import runtime.mobileagent.SettingsAuthoritySnapshot
import runtime.mobileagent.SettingsSafGrantState
import runtime.mobileagent.SettingsWiredPairingPrompt
import runtime.mobileagent.SettingsWiredPairingRequestResult
import runtime.mobileagent.BuildConfig
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuditRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.AuthorityPreferencesRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.SafWorkspaceGrantRepository
import runtime.mobileagent.data.SkillMemoryRepository
import runtime.mobileagent.data.SkillRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.AuthoritySelectionChangedRecord
import runtime.mobileagent.diagnostics.AuthorityStateChangedRecord
import runtime.mobileagent.diagnostics.AuthorityConfigurationStateRecord
import runtime.mobileagent.diagnostics.DangerousModeChangedRecord
import runtime.mobileagent.diagnostics.DangerousModeDecisionRecord
import runtime.mobileagent.diagnostics.BridgeRequestStateRecord
import runtime.mobileagent.diagnostics.DiagnosticReferenceHasher
import runtime.mobileagent.diagnostics.DiagnosticBridgeRequestState
import runtime.mobileagent.diagnostics.DiagnosticApprovalState
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticAuthorityState
import runtime.mobileagent.diagnostics.DiagnosticAuthorityConfigurationReason
import runtime.mobileagent.diagnostics.DiagnosticAvailability
import runtime.mobileagent.diagnostics.DiagnosticConnection
import runtime.mobileagent.diagnostics.DiagnosticDangerousModeDecisionReason
import runtime.mobileagent.diagnostics.DiagnosticDangerousModePolicy
import runtime.mobileagent.diagnostics.DiagnosticExposureState
import runtime.mobileagent.diagnostics.DiagnosticGrantScope
import runtime.mobileagent.diagnostics.DiagnosticLimitBucket
import runtime.mobileagent.diagnostics.DiagnosticLifecycleState
import runtime.mobileagent.diagnostics.DiagnosticOperation
import runtime.mobileagent.diagnostics.DiagnosticOperationState
import runtime.mobileagent.diagnostics.DiagnosticPlatformGrant
import runtime.mobileagent.diagnostics.DiagnosticTerminalState
import runtime.mobileagent.diagnostics.ShizukuLifecycleRecord
import runtime.mobileagent.diagnostics.SkillMemoryOperationStateRecord
import runtime.mobileagent.diagnostics.ShellExecutionStateRecord
import runtime.mobileagent.diagnostics.ShellToolExposureChangedRecord
import runtime.mobileagent.diagnostics.WiredAdbLifecycleRecord
import runtime.mobileagent.diagnostics.WorkspaceGrantChangedRecord
import runtime.mobileagent.diagnostics.WorkspaceOperationStateRecord
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityPolicy
import runtime.mobileagent.domain.AuthorityPreferences
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.DesktopTrustStatus
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.domain.SafWorkspaceGrant
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.ToolAuditDetail
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.memory.SkillMemoryBinding
import runtime.mobileagent.memory.SkillMemoryAvailabilitySnapshot
import runtime.mobileagent.memory.SkillMemoryDiagnosticEvent
import runtime.mobileagent.memory.SkillMemoryDiagnosticOperation
import runtime.mobileagent.memory.SkillMemoryDiagnosticRefProvider
import runtime.mobileagent.memory.SkillMemoryDiagnosticReferences
import runtime.mobileagent.memory.SkillMemoryDiagnosticRefRequest
import runtime.mobileagent.memory.SkillMemoryDiagnosticSink
import runtime.mobileagent.memory.SkillMemoryDiagnosticState
import runtime.mobileagent.memory.SkillMemoryException
import runtime.mobileagent.memory.SkillMemoryFailureCode
import runtime.mobileagent.memory.SkillMemoryListResult
import runtime.mobileagent.memory.SkillMemoryReadResult
import runtime.mobileagent.memory.SkillMemorySearchHit
import runtime.mobileagent.memory.SkillMemorySearchResult
import runtime.mobileagent.memory.SkillMemoryWriteResult
import runtime.mobileagent.memory.SkillMemoryRepositoryPort
import runtime.mobileagent.memory.SkillMemoryToolExecutor
import runtime.mobileagent.shizuku.ShizukuAuthorityBridge
import runtime.mobileagent.shizuku.ShizukuPermissionResult
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.shizuku.ShizukuBackendFactory
import runtime.mobileagent.skills.PermissionGrant
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.tooling.AuditDegradedFuse
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.ApprovalLifecycleEvent
import runtime.mobileagent.skills.tooling.ApprovalLifecycleSink
import runtime.mobileagent.skills.tooling.ApprovalLifecycleTransition
import runtime.mobileagent.skills.tooling.AuthoritySelection
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.skills.tooling.ShellAuditSink
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.tooling.ApprovalEngine
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.AuthorityManagerState
import runtime.mobileagent.tooling.AuthorityPersistentState
import runtime.mobileagent.tooling.AuthorityStateStore
import runtime.mobileagent.tooling.DangerousBuildPolicy
import runtime.mobileagent.tooling.DangerousModeManager
import runtime.mobileagent.tooling.DangerousModePersistentState
import runtime.mobileagent.tooling.DangerousModeStateStore
import runtime.mobileagent.tooling.EffectiveCapabilityResolver
import runtime.mobileagent.tooling.ShellToolExecutor
import runtime.mobileagent.tooling.ToolExecutionContext
import runtime.mobileagent.tooling.ToolExecutorFactory
import runtime.mobileagent.tooling.ToolingClock
import runtime.mobileagent.tooling.UnifiedWorkspaceToolExecutor
import runtime.mobileagent.tooling.WorkspaceAuditEvent
import runtime.mobileagent.tooling.WorkspaceAuditFuse
import runtime.mobileagent.tooling.WorkspaceAuditSink
import runtime.mobileagent.tooling.WorkspaceRegistry
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.wired.WiredAdbConnectionState
import runtime.mobileagent.wired.WiredAdbDiagnosticEvent
import runtime.mobileagent.wired.WiredAdbDiagnosticSink
import runtime.mobileagent.wired.WiredAdbErrorCode
import runtime.mobileagent.wired.WiredAdbLifecycleState
import runtime.mobileagent.wired.WiredAdbPlatformGrant
import runtime.mobileagent.wired.WiredAdbStatus
import runtime.mobileagent.wired.WiredAdbUserIntent
import runtime.mobileagent.wired.WiredAdbResult
import runtime.mobileagent.skills.tooling.ToolError

/**
 * The one Android runtime facade. It owns process-lifetime policy, repository,
 * backend and diagnostic objects, but creates every tool executor per run.
 * Provider connections are runtime facts only; durable selection and grants
 * are never replaced when a connection disappears.
 */
internal fun safPersistableFlags(readGranted: Boolean, writeGranted: Boolean): Int =
    (if (readGranted) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
        (if (writeGranted) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)

internal fun safRequestedFlags(resultFlags: Int): Int =
    (resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        .takeIf { it != 0 }
        ?: (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

/**
 * Non-sensitive counts used to explain why a run did or did not receive workspace tools.
 * Workspace ids, roots, URIs, paths, serials, and grant identifiers are intentionally absent.
 */
data class RuntimeToolExposureDiagnostics(
    val registeredWorkspaceCount: Int,
    val grantedWorkspaceCount: Int,
    val boundWorkspaceCount: Int,
    val registeredGrantedWorkspaceCount: Int,
    val selectedAuthority: DiagnosticAuthority,
    val selectedAuthorityReady: Boolean,
    val safGrantActive: Boolean,
    val safBackendRegistered: Boolean,
)

class RuntimeIntegration(
    private val context: Context,
    private val db: runtime.mobileagent.data.SqlConnection,
    private val agents: AgentRepository,
    private val skills: SkillRepository,
    private val auditRepository: AuditRepository,
    private val diagnostics: AndroidDiagnosticLogger,
    private val shizukuAuthority: ShizukuAuthorityBridge? = null,
    private val wiredAuthority: WiredAdbAuthorityPort,
) : SettingsAuthorityPort, AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val wiredReconnectInFlight = AtomicBoolean(false)
    /** True only after the bridge's successfully loaded intent matches the canonical manager. */
    private val wiredIntentReconciled = AtomicBoolean(false)
    private val buildPolicy = DangerousBuildPolicy.fromBuildFlags(
        isDebuggable = BuildConfig.DEBUG,
        controlPlaneAllowed = BuildConfig.HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED,
        // Recognize only the three explicitly configured Android build types.
        // A custom/partially-generated variant is unknown and remains
        // fail-closed even if its boolean values happen to look permissive.
        variantKnown = when (BuildConfig.BUILD_TYPE) {
            "debug" -> BuildConfig.DEBUG && !BuildConfig.HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED
            "review", "release" -> !BuildConfig.DEBUG && BuildConfig.HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED
            else -> false
        },
    )

    private val authorityPolicyRepository = AuthorityPolicyRepository(db)
    private val authorityPreferencesRepository = AuthorityPreferencesRepository(db)
    private val workspaceRepository = WorkspaceRepository(db)
    private val capabilityGrantRepository = CapabilityGrantRepository(db)
    private val safWorkspaceGrantRepository = SafWorkspaceGrantRepository(db)
    private val skillMemoryRepository = SkillMemoryRepository(
        db,
        File(appContext.filesDir, "skill-memory").toPath(),
    )

    private val authorityStateStore: AuthorityStateStore = SqliteAuthorityStateStore(
        authorityPolicyRepository,
        authorityPreferencesRepository,
    )
    private val dangerousModeStateStore: DangerousModeStateStore = SqliteDangerousModeStateStore(
        authorityPolicyRepository,
    )

    private val authorityManager = AuthorityManager(authorityStateStore)
    private val dangerousModeManager = DangerousModeManager(dangerousModeStateStore, buildPolicy)
    private val workspaceRegistry = WorkspaceRegistry()
    private val auditFuse = AuditDegradedFuse()
    private val workspaceAuditFuse = WorkspaceAuditFuse()
    private val auditSink = SqliteRuntimeAuditSink(auditRepository, diagnostics)
    private val approvalEngine = ApprovalEngine(lifecycleSink = auditSink)
    private val effectiveCapabilityResolver = EffectiveCapabilityResolver(
        grants = { agentId, workspaceId ->
            capabilityGrantRepository.list(agentId, workspaceId)
        },
        bindings = { snapshotId -> capabilityGrantRepository.listSnapshotBindings(snapshotId) },
        currentPolicyVersionReader = { authorityPolicyRepository.getPolicy().policyVersion },
    )

    /** One canonical DB/sidecar adapter owns binding, availability, and all memory operations. */
    private val skillMemoryRepositoryPort: SkillMemoryRepositoryPort = SqliteSkillMemoryRepositoryPort(
        agents = agents,
        skills = skills,
        capabilityGrants = capabilityGrantRepository,
        effectiveCapabilityResolver = effectiveCapabilityResolver,
        currentPolicyVersion = { authorityPolicyRepository.getPolicy().policyVersion },
        memoryRepository = skillMemoryRepository,
    )
    private val skillMemoryDiagnosticHasher = DiagnosticReferenceHasher.session()
    private val skillMemoryDiagnosticRefProvider = SkillMemoryDiagnosticRefProvider { request ->
        SkillMemoryDiagnosticReferences(
            // The memory handle is opaque to this adapter. Hash it once more at
            // the Android boundary so the logger never receives a model- or
            // user-controlled identifier in clear text.
            skillRef = skillMemoryDiagnosticHasher.hash(request.memoryHandle),
            requestRef = skillMemoryDiagnosticHasher.hash(request.callId),
        )
    }
    private val skillMemoryDiagnosticSink = SkillMemoryDiagnosticSink { event ->
        diagnostics.recordSkillMemoryOperationState(
            SkillMemoryOperationStateRecord(
                skillId = event.references.skillRef ?: "unknown",
                operation = event.operation.toDiagnosticOperation(),
                state = event.state.toDiagnosticOperationState(),
                count = event.count,
                requestRef = event.references.requestRef,
                errorCode = event.errorCode,
            ),
        )
    }

    private val shellBackends = linkedMapOf<ElevatedAuthority, ShellExecutor>()
    private val workspaceBackends = linkedMapOf<ElevatedAuthority, runtime.mobileagent.skills.tooling.WorkspaceBackend>()
    private val agentGrantPort = ContainerAgentGrantPort()

    private var previousSelection: Authority? = null
    private val shizukuPermissionRequestPending = AtomicBoolean(false)
    private val shizukuStateListener: (ShizukuAuthorityState) -> Unit = { state ->
        applyShizukuState(state)
        recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.PLATFORM_STATE_CHANGE)
    }
    private val shizukuPermissionListener: (ShizukuPermissionResult) -> Unit = { result ->
        if (shizukuPermissionRequestPending.compareAndSet(true, false)) {
            // This is the only path that persists Shizuku configuration.  A
            // refresh, Binder reconnect, or process restart only updates live
            // grant/availability/connection facts.
            authorityManager.setConfigured(ElevatedAuthority.SHIZUKU, result.granted)
            recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    init {
        wireShizukuBackend()
        wireWiredBackend()
        hydrateWorkspaces()
        installDiagnosticsCollectors()
        shizukuAuthority?.addStateListener(shizukuStateListener)
        shizukuAuthority?.addPermissionResultListener(shizukuPermissionListener)
        shizukuAuthority?.let { applyShizukuState(it.state.value) }
        applyWiredState(wiredAuthority.status.value)
        scheduleWiredReconnect()
        refreshSafWorkspace()
        recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.SNAPSHOT)
    }

    /** UI-safe, repository-backed grant port used by AgentsViewModel. */
    val grants: AgentGrantPort
        get() = agentGrantPort

    /**
     * Build the tool set for exactly one frozen run context. Null web/MCP/
     * Python inputs mean that provider is absent for this run; this method
     * never creates or substitutes one.
     */
    fun createToolExecutorFactory(
        context: ToolExecutionContext,
        web: ToolExecutor? = null,
        mcp: ToolExecutor? = null,
        python: ToolExecutor? = null,
    ): ToolExecutorFactory {
        val frozen = freezeContext(context)
        val workspace = UnifiedWorkspaceToolExecutor(
            registry = workspaceRegistry,
            approvalEngine = approvalEngine,
            resolver = effectiveCapabilityResolver,
            contextProvider = { frozen },
            auditSink = auditSink,
            auditFuse = workspaceAuditFuse,
            dangerousModeProvider = { dangerousModeManager.policy() },
            authoritySelectionProvider = { authorityManager.selection.value },
            onceGrantConsumer = { grant ->
                capabilityGrantRepository.tryConsumeOnce(
                    grantId = grant.grantId,
                    expectedRevision = grant.revision,
                    taskIdentity = frozen.taskIdentity.takeIf { it.isNotBlank() },
                    sessionIdentity = frozen.sessionIdentity,
                )
            },
        )
        val shell = ShellToolExecutor(
            authorityManager = authorityManager,
            dangerousModeManager = dangerousModeManager,
            approvalEngine = approvalEngine,
            contextProvider = { frozen },
            backends = shellBackends.toMap(),
            resolver = effectiveCapabilityResolver,
            auditSink = auditSink,
            auditFuse = auditFuse,
            onceGrantConsumer = { grant ->
                capabilityGrantRepository.tryConsumeOnce(
                    grantId = grant.grantId,
                    expectedRevision = grant.revision,
                    taskIdentity = frozen.taskIdentity.takeIf { it.isNotBlank() },
                    sessionIdentity = frozen.sessionIdentity,
                )
            },
        )
        val memory = createSkillMemoryToolExecutor(frozen)
        return ToolExecutorFactory(
            web = web,
            mcp = mcp,
            python = python,
            memory = memory,
            workspace = workspace,
            shell = shell,
        )
    }

    /**
     * Return a closed, aggregate-only explanation of the workspace exposure inputs for one run.
     * This is diagnostic data, not an authorization decision; executors still revalidate every
     * grant, binding, provider state, path, and one-shot lifetime immediately before dispatch.
     */
    fun toolExposureDiagnostics(context: ToolExecutionContext): RuntimeToolExposureDiagnostics {
        val frozen = freezeContext(context)
        val registeredIds = workspaceRegistry.descriptors().map { it.id }.toSet()
        val grantedIds = frozen.canonicalGrants.mapNotNull { it.workspaceId }.toSet()
        val boundIds = frozen.snapshotGrantBindings.mapNotNull { it.workspaceId }.toSet()
        val authorityState = authorityManager.state.value
        val selected = authorityState.selectedAuthority
        val selectedState = authorityState.statuses[selected]
        val safGrant = safWorkspaceGrantRepository.get(SAF_WORKSPACE_ID)
        return RuntimeToolExposureDiagnostics(
            registeredWorkspaceCount = registeredIds.size,
            grantedWorkspaceCount = grantedIds.size,
            boundWorkspaceCount = boundIds.size,
            registeredGrantedWorkspaceCount = registeredIds.intersect(grantedIds).intersect(boundIds).size,
            selectedAuthority = (selected ?: Authority.NONE).toDiagnostic(),
            selectedAuthorityReady = selected == null || selected == Authority.NONE || selectedState?.isReady == true,
            safGrantActive = safGrant?.status == SafGrantStatus.ACTIVE,
            safBackendRegistered = SAF_WORKSPACE_ID in registeredIds,
        )
    }

    /** Create the memory executor for this run; bindings are rechecked at each approval. */
    fun createSkillMemoryToolExecutor(context: ToolExecutionContext): SkillMemoryToolExecutor {
        val frozen = freezeContext(context)
        return SkillMemoryToolExecutor(
            repository = skillMemoryRepositoryPort,
            agentId = frozen.agentId,
            snapshotId = frozen.snapshotId,
            trustedSkillId = frozen.skillId,
            effectiveCapabilities = frozen.effectiveCapabilityNames,
            diagnosticSink = skillMemoryDiagnosticSink,
            diagnosticRefProvider = skillMemoryDiagnosticRefProvider,
        )
    }

    /**
     * Read-only Skill Memory availability projection for Skills UI. The
     * canonical repository remains the sole authority; no binding, install,
     * package, path, storage, or URI identity crosses this seam.
     */
    fun skillMemoryAvailability(
        agentId: String,
        snapshotId: String,
        trustedSkillId: String,
    ): SkillMemoryAvailabilitySnapshot = skillMemoryRepositoryPort.availability(
        agentId = agentId,
        snapshotId = snapshotId,
        trustedSkillId = trustedSkillId,
    )

    /**
     * Create one immutable Agent snapshot and bind the current canonical grants to it as one
     * persistence operation.  Chat must use this seam instead of creating a snapshot and copying
     * grant rows itself: the grant/binding fields are validated by CapabilityGrantRepository and
     * the exact persisted set is checked before the snapshot is returned.
     */
    fun createSnapshotWithCurrentGrants(
        agentId: String,
        snapshotId: String = EntityId.random().value,
        at: String = Utc.nowIso(),
    ): AgentSnapshot = db.transaction {
        require(agentId.isNotBlank()) { "Agent id is missing" }
        require(snapshotId.isNotBlank()) { "Snapshot id is missing" }
        require(at.isNotBlank()) { "Snapshot timestamp is missing" }
        val snapshot = agents.createSnapshot(agentId = agentId, snapshotId = snapshotId, at = at)
        val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
        val now = Utc.nowIso()
        val expected = capabilityGrantRepository.forAgent(agentId, includeRevoked = false)
            .asSequence()
            .filter { it.policyVersion == policyVersion }
            // Validate a scoped grant against its persisted owner while taking the snapshot. The
            // runtime supplies the active task/session identity again before dispatch.
            .filter { grant -> grant.isActiveFor(java.time.Instant.parse(now), grant.taskId, grant.sessionId) }
            .map { grant ->
                SnapshotGrantBinding(
                    snapshotId = snapshot.id,
                    grantId = grant.grantId,
                    capability = grant.capability,
                    workspaceId = grant.workspaceId,
                    pathScope = grant.pathScope,
                    policyVersion = grant.policyVersion,
                    boundAt = now,
                )
            }
            .toList()
        expected.forEach { binding ->
            val persisted = capabilityGrantRepository.bindSnapshot(binding)
            require(persisted == binding) {
                "Snapshot grant binding save returned an unexpected binding"
            }
        }
        val persisted = capabilityGrantRepository.listSnapshotBindings(snapshot.id)
        require(persisted.size == expected.size && persisted.toSet() == expected.toSet()) {
            "Snapshot grant binding verification failed"
        }
        snapshot
    }

    /** Build a run context from an immutable Agent snapshot and current DB grants. */
    fun createToolExecutionContext(
        snapshot: AgentSnapshot,
        modelCallId: String,
        sessionIdentity: String,
        configSnapshotHash: String,
        taskIdentity: String = "",
        skillId: String? = null,
        skillRevision: Long? = null,
        trustedSkillEnvelope: Boolean = skillId != null,
    ): ToolExecutionContext = freezeContext(
        ToolExecutionContext(
            agentId = snapshot.agentId,
            snapshotId = snapshot.id,
            modelCallId = modelCallId,
            sessionIdentity = sessionIdentity,
            taskIdentity = taskIdentity,
            configSnapshotHash = configSnapshotHash,
            policyVersion = authorityPolicyRepository.getPolicy().policyVersion,
            skillId = skillId,
            skillRevision = skillRevision,
            trustedSkillEnvelope = trustedSkillEnvelope,
        ),
    )

    fun createToolExecutionContext(
        snapshotId: String,
        modelCallId: String,
        sessionIdentity: String,
        configSnapshotHash: String,
        taskIdentity: String = "",
        skillId: String? = null,
        skillRevision: Long? = null,
        trustedSkillEnvelope: Boolean = skillId != null,
    ): ToolExecutionContext {
        val snapshot = agents.getSnapshot(snapshotId) ?: error("Agent snapshot is unavailable")
        return createToolExecutionContext(
            snapshot = snapshot,
            modelCallId = modelCallId,
            sessionIdentity = sessionIdentity,
            configSnapshotHash = configSnapshotHash,
            taskIdentity = taskIdentity,
            skillId = skillId,
            skillRevision = skillRevision,
            trustedSkillEnvelope = trustedSkillEnvelope,
        )
    }

    // ---- SettingsAuthorityPort -------------------------------------------------------------

    override fun snapshot(): SettingsAuthoritySnapshot = settingsSnapshot()

    override fun refresh(): SettingsAuthoritySnapshot {
        shizukuAuthority?.let { bridge ->
            var live = bridge.refresh()
            val manager = authorityManager.state.value
            val configured = manager.statuses[ElevatedAuthority.SHIZUKU]
            val reconnectEligible = manager.selectedAuthority == ElevatedAuthority.SHIZUKU &&
                configured?.isConfiguredForSelection == true &&
                configured.userIntent == AuthorityUserIntent.SHIZUKU &&
                live.permissionGranted &&
                !live.ready
            if (reconnectEligible) {
                // Refresh is an explicit foreground recovery action. Re-bind only the already
                // selected/configured provider; never request permission or choose a fallback.
                bridge.bindUserService()
                live = bridge.refresh()
            }
            applyShizukuState(live)
        }
        refreshSafWorkspace()
        applyWiredState(wiredAuthority.status.value)
        scheduleWiredReconnect()
        return settingsSnapshot().also {
            recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.REFRESH)
        }
    }

    override fun selectAuthority(authority: Authority): SettingsAuthoritySnapshot {
        require(authority in Authority.entries) { "Unsupported authority" }
        check(authorityManager.selectAuthority(authority.toElevated())) { "Authority policy changed; reload and retry" }
        return settingsSnapshot().also {
            recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    override fun setUserIntent(authority: Authority, enabled: Boolean): SettingsAuthoritySnapshot {
        when (authority) {
            Authority.NONE -> require(!enabled) { "NONE cannot be enabled" }
            Authority.SHIZUKU -> check(authorityManager.setUserIntent(ElevatedAuthority.SHIZUKU, enabled))
            Authority.WIRED_ADB -> {
                check(authorityManager.setUserIntent(ElevatedAuthority.WIRED_ADB, enabled))
                wiredAuthority.setUserIntent(enabled)
            }
        }
        if (authority == Authority.WIRED_ADB && enabled) scheduleWiredReconnect()
        return settingsSnapshot().also {
            recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    override fun requestWiredAdbPairingToken(
        replaceExistingTrust: Boolean,
    ): SettingsWiredPairingRequestResult {
        val result = runCatching {
            wiredAuthority.requestPairingFromForeground(replaceExistingTrust)
        }.getOrElse {
            return SettingsWiredPairingRequestResult(
                accepted = false,
                snapshot = refresh(),
                reason = WiredAdbErrorCode.INTERNAL_ERROR.name,
            )
        }
        return when (result) {
            is WiredAdbResult.Success -> SettingsWiredPairingRequestResult(
                accepted = true,
                prompt = SettingsWiredPairingPrompt(
                    token = result.value.tokenDisplay(),
                    expiresAtEpochMs = result.value.expiresAtEpochMs,
                    remainingAttempts = result.value.remainingAttempts,
                ),
                snapshot = refresh(),
            )
            is WiredAdbResult.Failure -> SettingsWiredPairingRequestResult(
                accepted = false,
                snapshot = refresh(),
                reason = result.code.name,
            )
        }
    }

    override suspend fun completeWiredAdbPairing(): SettingsAuthorityMutation {
        val result = try {
            wiredAuthority.pair()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            WiredAdbResult.Failure(WiredAdbErrorCode.INTERNAL_ERROR)
        }
        if (result !is WiredAdbResult.Success) {
            val failure = result as WiredAdbResult.Failure
            return SettingsAuthorityMutation(false, refresh(), failure.code.name)
        }
        val configured = runCatching {
            authorityManager.setConfigured(ElevatedAuthority.WIRED_ADB, true)
        }.getOrDefault(false)
        val snapshot = refresh()
        return if (configured) {
            SettingsAuthorityMutation(true, snapshot)
        } else {
            SettingsAuthorityMutation(false, snapshot, WiredAdbErrorCode.INTERNAL_ERROR.name)
        }
    }

    override fun cancelWiredAdbPairing(): SettingsAuthoritySnapshot {
        wiredAuthority.cancelPairing()
        return refresh()
    }

    override fun requestShizukuPermission(): SettingsAuthoritySnapshot {
        val bridge = shizukuAuthority ?: error("Shizuku is unavailable")
        shizukuPermissionRequestPending.set(true)
        val accepted = bridge.requestPermission()
        if (!accepted) {
            shizukuPermissionRequestPending.set(false)
            error("Shizuku permission request was not accepted")
        }
        // If permission was already granted, Shizuku does not necessarily emit
        // a result callback; the explicit foreground request still authorizes
        // persisting the configured marker once.
        if (bridge.state.value.permissionGranted && shizukuPermissionRequestPending.compareAndSet(true, false)) {
            authorityManager.setConfigured(ElevatedAuthority.SHIZUKU, true)
        }
        if (bridge.state.value.permissionGranted) bridge.bindUserService()
        return refresh().also {
            recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    override fun openShizuku(): Boolean = runtime.mobileagent.openShizuku(appContext)

    override fun reauthorizeWiredAdb(): SettingsAuthoritySnapshot {
        wiredAuthority.setUserIntent(true)
        check(authorityManager.setUserIntent(ElevatedAuthority.WIRED_ADB, true))
        refresh()
        // Trust/configuration is persisted only after this explicit foreground
        // action; a reconnect or status refresh alone never creates it.
        if (wiredAuthority.status.value.trusted) {
            check(authorityManager.setConfigured(ElevatedAuthority.WIRED_ADB, true))
        }
        return settingsSnapshot().also {
            recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    override fun forgetWiredAdb(): SettingsAuthoritySnapshot {
        scope.launch { runCatching { wiredAuthority.forget() } }
        authorityManager.setConfigured(ElevatedAuthority.WIRED_ADB, false)
        return refresh().also {
            recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    override fun authorizeSaf(uri: Uri): SettingsAuthoritySnapshot = authorizeSaf(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )

    override fun authorizeSaf(uri: Uri, resultFlags: Int): SettingsAuthoritySnapshot {
        require(uri.scheme.equals("content", ignoreCase = true)) { "SAF tree URI is invalid" }
        val requestedFlags = safRequestedFlags(resultFlags)
        // OpenDocumentTree providers are allowed to return a read-only grant.
        // Use the activity result flags first, then retry with READ only for
        // legacy callers that did not receive those flags.
        runCatching { appContext.contentResolver.takePersistableUriPermission(uri, requestedFlags) }
            .recoverCatching {
                if (requestedFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION) throw it
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            .getOrElse { error -> throw IllegalStateException("SAF permission could not be persisted", error) }
        val persisted = appContext.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            ?: error("SAF permission is unavailable after persistence")
        val actualFlags = safPersistableFlags(persisted.isReadPermission, persisted.isWritePermission)
        require(actualFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "SAF read permission is required"
        }
        val id = SAF_WORKSPACE_ID
        val existing = workspaceRepository.get(id)
        val workspace = Workspace(
            id = id,
            displayName = "User-authorized files",
            backendType = WorkspaceBackendType.SAF_TREE,
            rootReference = uri.toString(),
            readable = actualFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            writable = actualFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
            quotaBytes = 4L * 1024L * 1024L,
            maxFileBytes = 256L * 1024L,
            enabled = true,
            revision = (existing?.revision ?: 0L) + 1L,
        )
        workspaceRepository.save(workspace)
        safWorkspaceGrantRepository.save(
            SafWorkspaceGrant(
                workspaceId = id,
                uriReference = uri.toString(),
                readGranted = actualFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
                writeGranted = actualFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
                persistedFlags = actualFlags,
                status = SafGrantStatus.ACTIVE,
                createdAt = existing?.createdAt.orEmpty(),
            ),
        )
        val backend = runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(appContext, uri, id)
        workspaceRegistry.registerOrReplace(workspace, backend)
        diagnostics.recordWorkspaceGrantChanged(
            WorkspaceGrantChangedRecord(id, if (actualFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) DiagnosticGrantScope.READ_WRITE else DiagnosticGrantScope.READ, true),
        )
        return settingsSnapshot()
    }

    override fun revokeSaf(): SettingsAuthoritySnapshot {
        val grant = safWorkspaceGrantRepository.get(SAF_WORKSPACE_ID)
        if (grant != null) {
            runCatching {
                val uri = Uri.parse(grant.uriReference)
                val actual = appContext.contentResolver.persistedUriPermissions
                    .firstOrNull { it.uri == uri }
                    ?.let { safPersistableFlags(it.isReadPermission, it.isWritePermission) }
                    ?: grant.persistedFlags
                if (actual != 0) {
                    appContext.contentResolver.releasePersistableUriPermission(uri, actual)
                }
            }
            safWorkspaceGrantRepository.markRevoked(SAF_WORKSPACE_ID)
        }
        workspaceRegistry.unregister(SAF_WORKSPACE_ID)
        workspaceRepository.get(SAF_WORKSPACE_ID)?.let { existing ->
            workspaceRepository.save(existing.copy(enabled = false, readable = false, writable = false, revision = existing.revision + 1L))
        }
        diagnostics.recordWorkspaceGrantChanged(
            WorkspaceGrantChangedRecord(SAF_WORKSPACE_ID, DiagnosticGrantScope.NONE, false),
        )
        return settingsSnapshot()
    }

    override fun setDangerousMode(mode: DangerousMode, confirmed: Boolean): SettingsAuthorityMutation {
        if (mode != DangerousMode.DISABLED && !confirmed) {
            val snapshot = settingsSnapshot()
            diagnostics.recordDangerousModeDecision(
                DangerousModeDecisionRecord(
                    requestedPolicy = mode.toDiagnostic(),
                    accepted = false,
                    buildAllowed = snapshot.dangerousModeBuildAllowed,
                    buildKnown = snapshot.dangerousModeBuildKnown,
                    authority = snapshot.selectedAuthority.toDiagnostic(),
                    reason = DiagnosticDangerousModeDecisionReason.USER_REJECTED,
                ),
            )
            return SettingsAuthorityMutation(false, snapshot, "DANGEROUS_MODE_CONFIRMATION_REQUIRED")
        }
        val changed = dangerousModeManager.setPolicy(mode)
        val snapshot = settingsSnapshot()
        diagnostics.recordDangerousModeDecision(
            DangerousModeDecisionRecord(
                requestedPolicy = mode.toDiagnostic(),
                accepted = changed.accepted,
                buildAllowed = snapshot.dangerousModeBuildAllowed,
                buildKnown = snapshot.dangerousModeBuildKnown,
                authority = snapshot.selectedAuthority.toDiagnostic(),
                reason = when {
                    changed.accepted -> DiagnosticDangerousModeDecisionReason.ACCEPTED
                    changed.reason == "DANGEROUS_MODE_BUILD_DENIED" -> DiagnosticDangerousModeDecisionReason.BUILD_DENIED
                    changed.reason?.contains("AUTHORITY", ignoreCase = true) == true ->
                        DiagnosticDangerousModeDecisionReason.AUTHORITY_UNAVAILABLE
                    else -> DiagnosticDangerousModeDecisionReason.MUTATION_FAILED
                },
            ),
        )
        return SettingsAuthorityMutation(changed.accepted, snapshot, changed.reason)
    }

    // ---- Backend hydration and state -------------------------------------------------------

    private fun wireShizukuBackend() {
        val bridge = shizukuAuthority ?: return
        shellBackends[ElevatedAuthority.SHIZUKU] = ShizukuBackendFactory.createShellExecutor(bridge)
        workspaceBackends[ElevatedAuthority.SHIZUKU] = ShizukuBackendFactory.createWorkspaceBackend(bridge)
    }

    private fun wireWiredBackend() {
        shellBackends[ElevatedAuthority.WIRED_ADB] = WiredShellExecutor(wiredAuthority)
        workspaceBackends[ElevatedAuthority.WIRED_ADB] = WiredWorkspaceBackend(wiredAuthority)
    }

    private fun hydrateWorkspaces() {
        adoptInternalWorkspace()
        adoptLegacyWorkspaces()
        workspaceRepository.list(enabledOnly = true)
            .filter { it.backendType == WorkspaceBackendType.SAF_TREE }
            .forEach { workspace ->
                val grant = safWorkspaceGrantRepository.get(workspace.id)
                if (grant != null && grant.status == SafGrantStatus.ACTIVE) {
                    val uri = runCatching { Uri.parse(grant.uriReference) }.getOrNull()
                    if (uri != null && hasPersistedSafGrant(uri, grant)) {
                        runCatching {
                            workspaceRegistry.registerOrReplace(
                                workspace,
                                runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(appContext, uri, workspace.id),
                            )
                        }
                    } else {
                        safWorkspaceGrantRepository.markLost(workspace.id)
                    }
                }
            }
        workspaceBackends.forEach { (authority, backend) ->
            val descriptor = backend.descriptor
            val workspace = workspaceRepository.get(descriptor.id) ?: Workspace(
                id = descriptor.id,
                displayName = descriptor.displayName,
                backendType = WorkspaceBackendType.PRIVILEGED,
                rootReference = "authority:${authority.name}",
                readable = descriptor.readable,
                writable = descriptor.writable,
                quotaBytes = descriptor.quotaBytes,
                maxFileBytes = descriptor.maxFileBytes,
                enabled = descriptor.enabled,
            ).also(workspaceRepository::save)
            workspaceRegistry.registerOrReplace(workspace, backend)
        }
    }

    /**
     * Defense-in-depth gate for the low-level Wired shell port. The shared
     * ShellToolExecutor remains the authority for per-call capability and
     * approval checks; this callback only prevents direct bridge dispatch
     * while Wired is not the selected, configured, ready provider.
     */
    internal fun wiredShellPermissionAvailable(): Boolean {
        if (closed.get()) return false
        val state = authorityManager.state.value
        val wired = state.statuses[ElevatedAuthority.WIRED_ADB] ?: return false
        return state.selectedAuthority == ElevatedAuthority.WIRED_ADB &&
            wired.isConfiguredForSelection &&
            wired.isReady &&
            wiredIntentReconciled.get() &&
            dangerousModeManager.policy() != DangerousMode.DISABLED &&
            buildPolicy.permitsDangerousMode()
    }

    private fun refreshSafWorkspace() {
        val grant = safWorkspaceGrantRepository.get(SAF_WORKSPACE_ID) ?: return
        val uri = runCatching { Uri.parse(grant.uriReference) }.getOrNull()
        if (grant.status == SafGrantStatus.ACTIVE && uri != null && hasPersistedSafGrant(uri, grant)) {
            val workspace = workspaceRepository.get(SAF_WORKSPACE_ID)
            if (workspace != null) {
                runCatching {
                    workspaceRegistry.registerOrReplace(
                        workspace,
                        runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(appContext, uri, SAF_WORKSPACE_ID),
                    )
                }
            }
        } else if (grant.status == SafGrantStatus.ACTIVE) {
            safWorkspaceGrantRepository.markLost(SAF_WORKSPACE_ID)
            workspaceRegistry.unregister(SAF_WORKSPACE_ID)
        }
    }

    private fun adoptInternalWorkspace() {
        val id = INTERNAL_WORKSPACE_ID
        val existing = workspaceRepository.get(id)
        val defaultRoot = File(appContext.filesDir, "agent-workspace").toPath().toAbsolutePath().normalize()
        val root = existing?.takeIf { it.backendType == WorkspaceBackendType.INTERNAL }
            ?.rootReference?.let { safePrivatePath(it) } ?: defaultRoot
        runCatching { Files.createDirectories(root) }.getOrElse { return }
        val workspace = existing ?: Workspace(
            id = id,
            displayName = "Application workspace",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = root.toString(),
            readable = true,
            writable = true,
            quotaBytes = 4L * 1024L * 1024L,
            maxFileBytes = 256L * 1024L,
            enabled = true,
        ).also(workspaceRepository::save)
        runCatching {
            workspaceRegistry.registerOrReplace(
                workspace,
                runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createInternal(root, id),
            )
        }
    }

    /** Map the old snapshot namespace to an opaque, neutral workspace id. */
    private fun adoptLegacyWorkspaces() {
        val legacyParent = File(appContext.filesDir, "agent-workspaces").toPath()
        val snapshots = runCatching { agents.listSnapshots() }.getOrDefault(emptyList())
        snapshots.forEach { snapshot ->
            val namespace = legacyNamespace(snapshot)
            val root = legacyParent.resolve(namespace).normalize()
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@forEach
            val id = "legacy-" + sha256("legacy-workspace-id\u0000${snapshot.agentId}\u0000${snapshot.id}").take(48)
            val existing = workspaceRepository.get(id)
            val workspace = existing ?: Workspace(
                id = id,
                displayName = "Legacy application workspace",
                backendType = WorkspaceBackendType.INTERNAL,
                rootReference = root.toString(),
                readable = true,
                writable = true,
                quotaBytes = 4L * 1024L * 1024L,
                maxFileBytes = 256L * 1024L,
                enabled = true,
            ).also(workspaceRepository::save)
            runCatching {
                workspaceRegistry.registerOrReplace(
                    workspace,
                    runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createInternal(root, id),
                )
            }
        }
    }

    private fun applyShizukuState(state: ShizukuAuthorityState) {
        val grant = when {
            state.permissionGranted -> PlatformGrant.GRANTED
            state.binderAlive -> PlatformGrant.DENIED
            else -> PlatformGrant.UNKNOWN
        }
        val availability = when {
            !state.installedHint || state.preV11 -> Availability.UNSUPPORTED
            state.ready -> Availability.READY
            else -> Availability.TEMPORARILY_UNAVAILABLE
        }
        val connection = when {
            state.ready -> Connection.CONNECTED
            state.binderAlive && state.permissionGranted && !state.userServiceAlive -> Connection.CONNECTING
            state.binderAlive && state.permissionGranted -> Connection.DEGRADED
            else -> Connection.DISCONNECTED
        }
        authorityManager.updatePlatformGrant(ElevatedAuthority.SHIZUKU, grant)
        authorityManager.updateAvailability(ElevatedAuthority.SHIZUKU, availability)
        authorityManager.updateConnection(ElevatedAuthority.SHIZUKU, connection)
        diagnostics.recordShizukuLifecycle(
            ShizukuLifecycleRecord(
                state = when {
                    state.ready -> DiagnosticLifecycleState.READY
                    state.binderAlive && state.permissionGranted && !state.userServiceAlive -> DiagnosticLifecycleState.STARTED
                    state.binderAlive && state.permissionGranted -> DiagnosticLifecycleState.FAILED
                    state.binderAlive -> DiagnosticLifecycleState.DISCONNECTED
                    else -> DiagnosticLifecycleState.UNKNOWN
                },
                errorCode = state.errorCode ?: "unknown",
            ),
        )
    }

    private fun applyWiredState(state: WiredAdbStatus) {
        val grant = when (state.platformGrant) {
            WiredAdbPlatformGrant.GRANTED -> PlatformGrant.GRANTED
            WiredAdbPlatformGrant.DENIED -> PlatformGrant.DENIED
            WiredAdbPlatformGrant.REVOKED -> PlatformGrant.REVOKED
            WiredAdbPlatformGrant.UNKNOWN -> PlatformGrant.UNKNOWN
        }
        val availability = when (state.availability) {
            runtime.mobileagent.wired.WiredAdbAvailability.READY -> Availability.READY
            runtime.mobileagent.wired.WiredAdbAvailability.TEMPORARILY_UNAVAILABLE -> Availability.TEMPORARILY_UNAVAILABLE
            runtime.mobileagent.wired.WiredAdbAvailability.UNSUPPORTED -> Availability.UNSUPPORTED
        }
        val connection = when (state.connection) {
            WiredAdbConnectionState.CONNECTED -> Connection.CONNECTED
            WiredAdbConnectionState.CONNECTING -> Connection.CONNECTING
            WiredAdbConnectionState.DEGRADED -> Connection.DEGRADED
            WiredAdbConnectionState.DISCONNECTED -> Connection.DISCONNECTED
        }
        authorityManager.updatePlatformGrant(ElevatedAuthority.WIRED_ADB, grant)
        authorityManager.updateAvailability(ElevatedAuthority.WIRED_ADB, availability)
        authorityManager.updateConnection(ElevatedAuthority.WIRED_ADB, connection)
        // Reconcile both enabled and disabled bridge intent. An older one-way
        // sync could leave a canonical manager enabled after a restart where
        // the durable bridge intent had been cleared. Never write a value when
        // the bridge reports a persistence/load failure; in that case the
        // shell gate stays closed until a later healthy status is observed.
        val intentLoadSucceeded = state.lastError != WiredAdbErrorCode.INTERNAL_ERROR &&
            !(state.state == WiredAdbLifecycleState.REAUTH_REQUIRED && state.lastError == null)
        if (!intentLoadSucceeded) {
            wiredIntentReconciled.set(false)
        } else {
            val desired = if (state.userIntent == WiredAdbUserIntent.ENABLED) {
                AuthorityUserIntent.WIRED_ADB
            } else {
                AuthorityUserIntent.NONE
            }
            val current = authorityManager.state.value.statuses[ElevatedAuthority.WIRED_ADB]?.userIntent
            val matches = current == desired || runCatching {
                authorityManager.setUserIntent(
                    ElevatedAuthority.WIRED_ADB,
                    state.userIntent == WiredAdbUserIntent.ENABLED,
                )
            }.getOrDefault(false)
            wiredIntentReconciled.set(
                matches && authorityManager.state.value.statuses[ElevatedAuthority.WIRED_ADB]?.userIntent == desired,
            )
        }
        diagnostics.recordWiredAdbLifecycle(
            WiredAdbLifecycleRecord(
                state = when (state.state) {
                    WiredAdbLifecycleState.READY -> DiagnosticLifecycleState.READY
                    WiredAdbLifecycleState.CONNECTING,
                    WiredAdbLifecycleState.AUTHENTICATING,
                        -> DiagnosticLifecycleState.STARTED
                    WiredAdbLifecycleState.REAUTH_REQUIRED -> DiagnosticLifecycleState.FAILED
                    WiredAdbLifecycleState.DISCONNECTED -> DiagnosticLifecycleState.DISCONNECTED
                    else -> DiagnosticLifecycleState.UNKNOWN
                },
                errorCode = state.lastError?.name ?: "unknown",
            ),
        )
    }

    private fun installDiagnosticsCollectors() {
        scope.launch {
            authorityManager.selection.collect { selection ->
                val selected = selection.selected ?: Authority.NONE
                val previous = previousSelection
                if (previous != selected) {
                    diagnostics.recordAuthoritySelectionChanged(
                        AuthoritySelectionChangedRecord(selected.toDiagnostic(), previous?.toDiagnostic()),
                    )
                    previousSelection = selected
                }
            }
        }
        scope.launch {
            dangerousModeManager.state.collect { state ->
                diagnostics.recordDangerousModeChanged(
                    DangerousModeChangedRecord(
                        enabled = state.enabled,
                        policy = state.policy.toDiagnostic(),
                    ),
                )
            }
        }
        scope.launch {
            var previous: WiredAdbStatus? = null
            wiredAuthority.status.collect { state ->
                val prior = previous
                previous = state
                applyWiredState(state)
                recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.PLATFORM_STATE_CHANGE)
                // A transport/provider transition may be the only signal that
                // USB became available again. Retry only on the meaningful
                // disconnected -> trusted edge; reconnect failures themselves
                // never create a tight retry loop.
                if (prior?.state == WiredAdbLifecycleState.DISCONNECTED &&
                    state.state == WiredAdbLifecycleState.TRUSTED
                ) {
                    scheduleWiredReconnect()
                }
            }
        }
        scope.launch {
            authorityManager.state.collect { state ->
                state.statuses.values.forEach { status ->
                    diagnostics.recordAuthorityStateChanged(
                        AuthorityStateChangedRecord(
                            authority = status.authority.toDiagnostic(),
                            state = status.toDiagnostic(),
                        ),
                    )
                }
                recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.SNAPSHOT, state)
            }
        }
    }

    private fun recordAuthorityConfigurationSnapshot(
        reason: DiagnosticAuthorityConfigurationReason,
        state: AuthorityManagerState = authorityManager.state.value,
    ) {
        state.statuses.values.forEach { status ->
            diagnostics.recordAuthorityConfigurationState(
                AuthorityConfigurationStateRecord(
                    authority = status.authority.toDiagnostic(),
                    userIntentEnabled = status.userIntent != AuthorityUserIntent.NONE,
                    selected = state.selectedAuthority == status.authority,
                    platformGrant = when (status.grant) {
                        PlatformGrant.GRANTED -> DiagnosticPlatformGrant.GRANTED
                        PlatformGrant.DENIED, PlatformGrant.REVOKED -> DiagnosticPlatformGrant.DENIED
                        PlatformGrant.UNKNOWN -> DiagnosticPlatformGrant.UNKNOWN
                    },
                    availability = when (status.availability) {
                        Availability.READY -> DiagnosticAvailability.READY
                        Availability.TEMPORARILY_UNAVAILABLE -> DiagnosticAvailability.TEMPORARILY_UNAVAILABLE
                        Availability.UNSUPPORTED -> DiagnosticAvailability.UNSUPPORTED
                    },
                    connection = when (status.connection) {
                        Connection.CONNECTED -> DiagnosticConnection.CONNECTED
                        Connection.CONNECTING -> DiagnosticConnection.CONNECTING
                        Connection.DISCONNECTED -> DiagnosticConnection.DISCONNECTED
                        Connection.DEGRADED -> DiagnosticConnection.DEGRADED
                    },
                    configured = status.configured,
                    reason = reason,
                ),
            )
        }
    }

    /**
     * Stop process-lifetime collectors and detach platform listeners. The
     * authority bridges are injected resources owned by [AppContainer], so
     * closing this facade never closes either bridge.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        shizukuPermissionRequestPending.set(false)
        shizukuAuthority?.removeStateListener(shizukuStateListener)
        shizukuAuthority?.removePermissionResultListener(shizukuPermissionListener)
        scope.cancel()
        wiredReconnectInFlight.set(false)
    }

    /**
     * Best-effort process/foreground reconnect for an already trusted, selected
     * Wired provider. This never pairs, changes selection, or retries in a
     * loop; a failed attempt is retried only by a later explicit refresh or
     * disconnected -> trusted status transition.
     */
    private fun scheduleWiredReconnect() {
        if (closed.get() || wiredReconnectInFlight.get()) return
        val manager = authorityManager.state.value
        val wired = manager.statuses[ElevatedAuthority.WIRED_ADB] ?: return
        val bridge = runCatching { wiredAuthority.status.value }.getOrNull() ?: return
        if (manager.selectedAuthority != ElevatedAuthority.WIRED_ADB ||
            !wired.isConfiguredForSelection ||
            wired.userIntent != AuthorityUserIntent.WIRED_ADB ||
            bridge.userIntent != WiredAdbUserIntent.ENABLED ||
            !bridge.trusted ||
            bridge.state == WiredAdbLifecycleState.REAUTH_REQUIRED ||
            bridge.connection == WiredAdbConnectionState.CONNECTED
        ) return
        if (!wiredReconnectInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!closed.get()) runCatching { wiredAuthority.connect() }
            } finally {
                wiredReconnectInFlight.set(false)
            }
        }
    }

    private fun freezeContext(input: ToolExecutionContext): ToolExecutionContext {
        val snapshot = agents.getSnapshot(input.snapshotId)
            ?: throw IllegalArgumentException("Agent snapshot is unavailable")
        require(snapshot.agentId == input.agentId) { "Agent snapshot does not belong to the Agent" }
        val policy = authorityPolicyRepository.getPolicy()
        val grants = capabilityGrantRepository.forAgent(input.agentId, includeRevoked = true)
        val bindings = capabilityGrantRepository.listSnapshotBindings(input.snapshotId)
        val resolved = effectiveCapabilityResolver.resolve(
            snapshot = snapshot,
            grants = grants,
            snapshotBindings = bindings,
            currentPolicyVersion = policy.policyVersion,
            taskIdentity = input.taskIdentity.takeIf { it.isNotBlank() },
            sessionIdentity = input.sessionIdentity,
        )
        val effective = if (input.skillId == null) resolved.capabilities
        else resolved.capabilities intersect resolved.forSkill(input.skillId)
        return input.copy(
            policyVersion = policy.policyVersion,
            effectiveCapabilities = effective,
            canonicalGrants = resolved.grants,
            snapshotGrantBindings = resolved.bindings,
        )
    }

    private fun settingsSnapshot(): SettingsAuthoritySnapshot {
        val state = authorityManager.state.value
        val selected = state.selectedAuthority ?: Authority.NONE
        val shizuku = state.statuses[ElevatedAuthority.SHIZUKU]?.toSettings(Authority.SHIZUKU)
            ?: SettingsAuthorityProviderState(Authority.SHIZUKU)
        val wired = state.statuses[ElevatedAuthority.WIRED_ADB]?.toSettings(Authority.WIRED_ADB)?.copy(
            trust = runCatching { wiredAuthority.status.value.toDesktopTrustStatus() }.getOrNull(),
        ) ?: SettingsAuthorityProviderState(Authority.WIRED_ADB)
        val saf = safWorkspaceGrantRepository.get(SAF_WORKSPACE_ID)?.let { grant ->
            SettingsSafGrantState(
                configured = grant.status == SafGrantStatus.ACTIVE,
                readGranted = grant.readGranted,
                writeGranted = grant.writeGranted,
                persisted = grant.status == SafGrantStatus.ACTIVE && runCatching {
                    hasPersistedSafGrant(Uri.parse(grant.uriReference), grant)
                }.getOrDefault(false),
                status = grant.status,
            )
        } ?: SettingsSafGrantState()
        val policy = authorityPolicyRepository.getPolicy()
        return SettingsAuthoritySnapshot(
            selectedAuthority = selected,
            appPrivateAvailable = workspaceRegistry.descriptor(INTERNAL_WORKSPACE_ID) != null,
            shizuku = shizuku,
            wiredAdb = wired,
            saf = saf,
            // DangerousModeManager exposes the effective, build-admitted
            // policy. Keep it separate from the durable DB policy below so a
            // disallowed build renders and gates as DISABLED while still
            // allowing the user to clear the durable value.
            dangerousMode = dangerousModeManager.policy(),
            durableDangerousMode = policy.dangerousMode,
            dangerousModeBuildAllowed = buildPolicy.permitsDangerousMode(),
            dangerousModeBuildKnown = buildPolicy.variantKnown,
            dangerousModeReason = if (buildPolicy.permitsDangerousMode()) "unknown" else "DANGEROUS_MODE_BUILD_DENIED",
            revision = maxOf(state.persistenceRevision, policy.policyVersion, dangerousModeManager.state.value.revision),
        )
    }

    private fun hasPersistedSafGrant(uri: Uri, grant: SafWorkspaceGrant): Boolean {
        val permission = appContext.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return false
        val actualFlags = safPersistableFlags(permission.isReadPermission, permission.isWritePermission)
        if (actualFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return false
        if (grant.writeGranted && actualFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) return false
        return grant.readGranted || grant.writeGranted
    }

    private fun safePrivatePath(raw: String): Path? = runCatching {
        val candidate = File(raw).toPath().toAbsolutePath().normalize()
        val privateRoot = appContext.filesDir.toPath().toAbsolutePath().normalize()
        candidate.takeIf { it.startsWith(privateRoot) }
    }.getOrNull()

    private fun legacyNamespace(snapshot: AgentSnapshot): String =
        sha256("mobile-agent-runtime/workspace-v1\n${snapshot.agentId}\n${snapshot.id}")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun Authority?.toElevated(): ElevatedAuthority? = when (this) {
        null, Authority.NONE -> null
        Authority.SHIZUKU -> ElevatedAuthority.SHIZUKU
        Authority.WIRED_ADB -> ElevatedAuthority.WIRED_ADB
    }

    private fun Authority.toDiagnostic(): DiagnosticAuthority = when (this) {
        Authority.NONE -> DiagnosticAuthority.NONE
        Authority.SHIZUKU -> DiagnosticAuthority.SHIZUKU
        Authority.WIRED_ADB -> DiagnosticAuthority.WIRED_ADB
    }

    private fun runtime.mobileagent.skills.tooling.AuthorityState.toSettings(authority: Authority) = SettingsAuthorityProviderState(
        authority = authority,
        userIntent = when (userIntent) {
            AuthorityUserIntent.SHIZUKU -> AuthorityUserIntent.SHIZUKU
            AuthorityUserIntent.WIRED_ADB -> AuthorityUserIntent.WIRED_ADB
            AuthorityUserIntent.NONE -> AuthorityUserIntent.NONE
        },
        platformGrant = grant,
        availability = availability,
        connection = connection,
        configured = configured,
    )

    private fun WiredAdbStatus.toDesktopTrustStatus(): DesktopTrustStatus = when {
        state == WiredAdbLifecycleState.REAUTH_REQUIRED -> DesktopTrustStatus.REAUTH_REQUIRED
        trusted -> DesktopTrustStatus.TRUSTED
        else -> DesktopTrustStatus.FORGOTTEN
    }

    private fun runtime.mobileagent.skills.tooling.AuthorityState.toDiagnostic(): DiagnosticAuthorityState = when {
        isReady -> DiagnosticAuthorityState.CONNECTED
        availability == Availability.TEMPORARILY_UNAVAILABLE -> DiagnosticAuthorityState.DISCONNECTED
        connection == Connection.CONNECTING -> DiagnosticAuthorityState.CONNECTING
        grant == PlatformGrant.DENIED || grant == PlatformGrant.REVOKED -> DiagnosticAuthorityState.REAUTH_REQUIRED
        availability == Availability.READY -> DiagnosticAuthorityState.AVAILABLE
        else -> DiagnosticAuthorityState.UNAVAILABLE
    }

    private fun DangerousMode.toDiagnostic(): DiagnosticDangerousModePolicy = when (this) {
        DangerousMode.DISABLED -> DiagnosticDangerousModePolicy.DISABLED
        DangerousMode.ENABLED_CONFIRM_HIGH_RISK -> DiagnosticDangerousModePolicy.CONFIRM_HIGH_RISK
        DangerousMode.ENABLED_AUTONOMOUS -> DiagnosticDangerousModePolicy.AUTONOMOUS
    }

    private inner class ContainerAgentGrantPort : AgentGrantPort {
        override val available: Boolean = true
        override val unavailableMessage: String = "授权存储未就绪；请稍后重试。"

        override fun listWorkspaces(): List<Workspace> = workspaceRepository.list().map { workspace ->
            // The UI contract uses the domain type for labels, but receives a
            // non-path opaque marker instead of the persisted root/URI.
            workspace.copy(rootReference = "workspace:${workspace.id}")
        }

        override fun listGrants(agentId: String, includeRevoked: Boolean): List<CapabilityGrant> =
            capabilityGrantRepository.forAgent(agentId, includeRevoked)

        override fun saveGrant(grant: CapabilityGrant): CapabilityGrant = capabilityGrantRepository.save(grant)

        override fun revokeGrant(grantId: String, expectedRevision: Long): CapabilityGrant {
            val current = capabilityGrantRepository.get(grantId) ?: error("Capability grant is missing")
            require(current.revision == expectedRevision) { "Capability grant revision changed" }
            capabilityGrantRepository.revoke(grantId, expectedRevision)
            return capabilityGrantRepository.get(grantId) ?: error("Capability grant revoke failed")
        }

        override fun currentPolicyVersion(): Long = authorityPolicyRepository.getPolicy().policyVersion

        override fun listSnapshotBindings(snapshotId: String): List<SnapshotGrantBinding> =
            capabilityGrantRepository.listSnapshotBindings(snapshotId)

        override fun bindSnapshot(binding: SnapshotGrantBinding): SnapshotGrantBinding {
            val snapshot = agents.getSnapshot(binding.snapshotId)
                ?: error("Agent snapshot is missing")
            val grant = capabilityGrantRepository.get(binding.grantId)
                ?: error("Capability grant is missing")
            require(grant.agentId == snapshot.agentId) { "Snapshot grant belongs to another Agent" }
            return capabilityGrantRepository.bindSnapshot(binding)
        }

        override fun listInstalledSkills(): List<runtime.mobileagent.data.InstalledSkill> = skills.list()

        override fun listSkillGrants(installId: String): List<PermissionGrant> = skills.grantsFor(installId)

        override fun saveSkillGrant(grant: PermissionGrant): PermissionGrant {
            val skill = skills.get(grant.installId) ?: error("Skill install is missing")
            require(skill.packageHash == grant.packageHash) { "Skill package changed" }
            return skills.approvePermissions(
                installId = grant.installId,
                capabilities = grant.capabilities,
                knowledgeBaseIds = grant.knowledgeBaseIds,
                hosts = grant.hosts,
                methods = grant.methods,
            )
        }

        override fun revokeSkillGrant(grantId: String, expectedRevision: Int): PermissionGrant {
            val match = skills.list().asSequence()
                .flatMap { skill -> skills.grantsFor(skill.installId).asSequence() }
                .firstOrNull { it.grantId == grantId }
                ?: error("Skill grant is missing")
            require(match.revision == expectedRevision) { "Skill grant revision changed" }
            skills.revoke(match.installId)
            return skills.grantsFor(match.installId).firstOrNull { it.grantId == grantId }
                ?: error("Skill grant revoke failed")
        }
    }

    companion object {
        const val INTERNAL_WORKSPACE_ID = "internal"
        const val SAF_WORKSPACE_ID = "saf-tree"
    }
}

/**
 * Adapt the Wired worker's already-redacted event stream to the Android
 * diagnostic vocabulary. The operation text and every request payload are
 * deliberately ignored; only typed state, enum error codes, and the worker's
 * pre-hashed request reference cross this boundary.
 */
internal fun createWiredAdbDiagnosticSink(
    diagnostics: AndroidDiagnosticLogger,
): WiredAdbDiagnosticSink = WiredAdbDiagnosticSink { event ->
    runCatching {
        val errorCode = event.error?.name ?: "unknown"
        val requestRef = event.requestIdHash
        if (event.state != null) {
            diagnostics.recordWiredAdbLifecycle(
                WiredAdbLifecycleRecord(
                    state = event.state.toDiagnosticLifecycleState(),
                    errorCode = errorCode,
                    requestRef = requestRef,
                ),
            )
            return@runCatching
        }
        if (requestRef.isNullOrBlank()) return@runCatching
        diagnostics.recordBridgeRequestState(
            BridgeRequestStateRecord(
                state = event.toDiagnosticBridgeRequestState(),
                authority = DiagnosticAuthority.WIRED_ADB,
                requestRef = requestRef,
                errorCode = errorCode,
                durationBucket = event.durationMs.toDiagnosticLimitBucket(),
                count = 1,
            ),
        )
    }
}

private fun WiredAdbLifecycleState.toDiagnosticLifecycleState(): DiagnosticLifecycleState = when (this) {
    WiredAdbLifecycleState.READY -> DiagnosticLifecycleState.READY
    WiredAdbLifecycleState.CONNECTING,
    WiredAdbLifecycleState.AUTHENTICATING,
    WiredAdbLifecycleState.PAIRING,
        -> DiagnosticLifecycleState.STARTED
    WiredAdbLifecycleState.REAUTH_REQUIRED -> DiagnosticLifecycleState.FAILED
    WiredAdbLifecycleState.DISCONNECTED -> DiagnosticLifecycleState.DISCONNECTED
    WiredAdbLifecycleState.TRUSTED,
    WiredAdbLifecycleState.UNPAIRED,
        -> DiagnosticLifecycleState.UNKNOWN
}

private fun WiredAdbDiagnosticEvent.toDiagnosticBridgeRequestState(): DiagnosticBridgeRequestState {
    val normalizedOutcome = outcome.lowercase(java.util.Locale.ROOT)
    return when {
        error == WiredAdbErrorCode.BRIDGE_DISCONNECTED ||
            error == WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE ||
            normalizedOutcome == "disconnected" -> DiagnosticBridgeRequestState.DISCONNECTED
        normalizedOutcome == "received" -> DiagnosticBridgeRequestState.RECEIVED
        normalizedOutcome == "authenticated" || normalizedOutcome == "auth" -> DiagnosticBridgeRequestState.AUTHENTICATED
        normalizedOutcome == "rejected" || normalizedOutcome == "denied" -> DiagnosticBridgeRequestState.REJECTED
        normalizedOutcome == "started" || normalizedOutcome == "running" -> DiagnosticBridgeRequestState.STARTED
        normalizedOutcome == "success" || normalizedOutcome == "succeeded" ||
            normalizedOutcome == "complete" || normalizedOutcome == "completed" -> DiagnosticBridgeRequestState.COMPLETED
        normalizedOutcome == "timeout" || normalizedOutcome == "timed_out" ||
            normalizedOutcome == "cancelled" || error == WiredAdbErrorCode.TIMEOUT ||
            error == WiredAdbErrorCode.REQUEST_CANCELLED || error == WiredAdbErrorCode.UNKNOWN_OUTCOME ->
            DiagnosticBridgeRequestState.UNKNOWN
        else -> DiagnosticBridgeRequestState.FAILED
    }
}

private fun Long?.toDiagnosticLimitBucket(): DiagnosticLimitBucket = when {
    this == null || this < 0L -> DiagnosticLimitBucket.UNKNOWN
    this < 100L -> DiagnosticLimitBucket.TINY
    this < 1_000L -> DiagnosticLimitBucket.SMALL
    this < 10_000L -> DiagnosticLimitBucket.MEDIUM
    else -> DiagnosticLimitBucket.LARGE
}

private fun SkillMemoryDiagnosticOperation.toDiagnosticOperation(): DiagnosticOperation = when (this) {
    SkillMemoryDiagnosticOperation.READ -> DiagnosticOperation.READ
    SkillMemoryDiagnosticOperation.SEARCH -> DiagnosticOperation.SEARCH
    SkillMemoryDiagnosticOperation.APPEND -> DiagnosticOperation.APPEND
    SkillMemoryDiagnosticOperation.REPLACE -> DiagnosticOperation.REPLACE
    SkillMemoryDiagnosticOperation.UNKNOWN -> DiagnosticOperation.UNKNOWN
}

private fun SkillMemoryDiagnosticState.toDiagnosticOperationState(): DiagnosticOperationState = when (this) {
    SkillMemoryDiagnosticState.STARTED -> DiagnosticOperationState.STARTED
    SkillMemoryDiagnosticState.SUCCEEDED -> DiagnosticOperationState.SUCCEEDED
    SkillMemoryDiagnosticState.FAILED -> DiagnosticOperationState.FAILED
    SkillMemoryDiagnosticState.DENIED -> DiagnosticOperationState.DENIED
    // Expired approval/binding is a terminal non-success state, but the
    // Android diagnostics vocabulary has no separate expiry value.
    SkillMemoryDiagnosticState.EXPIRED -> DiagnosticOperationState.CANCELLED
    SkillMemoryDiagnosticState.CANCELLED -> DiagnosticOperationState.CANCELLED
    SkillMemoryDiagnosticState.UNKNOWN -> DiagnosticOperationState.UNKNOWN
}

/** SQLite-backed adapter for the existing AuthorityManager store contract. */
private class SqliteAuthorityStateStore(
    private val policy: AuthorityPolicyRepository,
    private val preferences: AuthorityPreferencesRepository,
) : AuthorityStateStore {
    override fun load(): AuthorityPersistentState {
        val current = policy.getPolicy()
        return AuthorityPersistentState(
            revision = current.policyVersion,
            selectedAuthority = current.selectedAuthority,
            preferences = policy.listPreferences().associateBy { it.authority },
        )
    }

    override fun compareAndSet(expectedRevision: Long, next: AuthorityPersistentState): Boolean {
        if (!policy.compareAndSet(expectedRevision, next.selectedAuthority, policy.getPolicy().dangerousMode)) return false
        next.preferences.values.forEach { preferences.save(it) }
        return true
    }
}

/** SQLite-backed adapter for the existing DangerousModeManager contract. */
private class SqliteDangerousModeStateStore(
    private val policy: AuthorityPolicyRepository,
) : DangerousModeStateStore {
    override fun load(): DangerousModePersistentState {
        val current = policy.getPolicy()
        return DangerousModePersistentState(current.policyVersion, current)
    }

    override fun compareAndSet(expectedRevision: Long, next: DangerousModePersistentState): Boolean =
        policy.compareAndSet(expectedRevision, next.policy.selectedAuthority, next.policy.dangerousMode)
}

/**
 * Canonical DB/sidecar memory adapter. Binding, availability, and every
 * memory operation use the same repository so the agent executor and the
 * read-only UI cannot drift to a second filesystem truth.
 */
private class SqliteSkillMemoryRepositoryPort(
    private val agents: AgentRepository,
    private val skills: SkillRepository,
    private val capabilityGrants: CapabilityGrantRepository,
    private val effectiveCapabilityResolver: EffectiveCapabilityResolver,
    private val currentPolicyVersion: () -> Long,
    private val memoryRepository: SkillMemoryRepository,
) : SkillMemoryRepositoryPort {
    override fun bindings(agentId: String, snapshotId: String): List<SkillMemoryBinding> {
        val snapshot = agents.getSnapshot(snapshotId) ?: return emptyList()
        if (snapshot.agentId != agentId) return emptyList()
        val policyVersion = currentPolicyVersion()
        // Resolve from the canonical Agent grants and immutable snapshot bindings.  The old
        // package-permission table is only a second trust boundary below; it is never treated as
        // an Agent grant or as proof that a snapshot is authorized.
        val resolved = effectiveCapabilityResolver.resolve(
            snapshot = snapshot,
            grants = capabilityGrants.forAgent(agentId, includeRevoked = true),
            snapshotBindings = capabilityGrants.listSnapshotBindings(snapshotId),
            currentPolicyVersion = policyVersion,
        )
        return snapshot.skillIds.mapNotNull { installId ->
            val skill = skills.get(installId) ?: return@mapNotNull null
            // A package grant establishes that the installed bytes were trusted.  Its
            // capabilities are intersected with the canonical Agent/snapshot/policy result;
            // package approval alone can never expose memory to an Agent.
            val packageGrant = skills.grantsFor(installId)
                .asSequence()
                .filter { it.packageHash == skill.packageHash && !it.revoked }
                .maxByOrNull { it.revision }
            if (!skill.enabled || packageGrant == null) return@mapNotNull null
            val packageCapabilities = packageGrant.capabilities
            val effectiveCapabilities = resolved.forSkill(installId).mapTo(linkedSetOf()) { it.value }
            val memoryCapabilities = (effectiveCapabilities intersect packageCapabilities).filterTo(linkedSetOf()) {
                it == runtime.mobileagent.memory.SKILL_MEMORY_READ_CAPABILITY ||
                    it == runtime.mobileagent.memory.SKILL_MEMORY_SEARCH_CAPABILITY ||
                    it == runtime.mobileagent.memory.SKILL_MEMORY_APPEND_CAPABILITY ||
                    it == runtime.mobileagent.memory.SKILL_MEMORY_REPLACE_CAPABILITY
            }
            if (memoryCapabilities.isEmpty()) return@mapNotNull null
            // Keep a stable canonical grant identity for the binding.  Capabilities may be
            // represented by several rows, so the binding carries one anchor revision while the
            // complete capability intersection is retained. Any row change changes the resolved
            // capability set and is caught by the executor's current-binding revalidation.
            val grant = resolved.grants
                .asSequence()
                .filter { it.agentId == agentId && it.skillInstallId == installId }
                .filter { it.packageHash == skill.packageHash && it.capability.value in memoryCapabilities }
                .minWithOrNull(compareBy<CapabilityGrant> { it.grantId }.thenBy { it.revision })
                ?: return@mapNotNull null
            val grantRevision = grant.revision
                .takeIf { it in 1..Int.MAX_VALUE.toLong() }
                ?.toInt()
                ?: return@mapNotNull null
            // A repository/store failure must remain distinguishable from a
            // missing grant. The canonical availability default maps thrown
            // failures to UNAVAILABLE; swallowing them here would misreport
            // a persistence outage as GRANT_LOST.
            val space = memoryRepository.ensureSpace(skill.installId, skill.packageHash)
            SkillMemoryBinding(
                installId = skill.installId,
                packageHash = skill.packageHash,
                memorySpaceId = space.spaceId,
                agentId = agentId,
                snapshotId = snapshotId,
                capabilities = memoryCapabilities,
                enabled = true,
                grantId = grant.grantId,
                grantRevision = grantRevision,
                memoryMetadataRevision = space.version,
            )
        }
    }

    override fun current(agentId: String, snapshotId: String, original: SkillMemoryBinding): SkillMemoryBinding? =
        bindings(agentId, snapshotId).singleOrNull { current ->
            current.installId == original.installId &&
                current.packageHash == original.packageHash &&
                current.memorySpaceId == original.memorySpaceId &&
                current.agentId == original.agentId &&
                current.snapshotId == original.snapshotId &&
                current.enabled == original.enabled &&
                current.grantId == original.grantId &&
                current.grantRevision == original.grantRevision &&
                current.memoryMetadataRevision == original.memoryMetadataRevision
        }

    override fun list(binding: SkillMemoryBinding): SkillMemoryListResult =
        memoryRepository.listEntries(binding.memorySpaceId).map { entry ->
            requireSameBinding(entry, binding)
            runtime.mobileagent.memory.SkillMemoryEntry(
                path = entry.path,
                bytes = entry.byteLength,
                version = entry.version.toString(),
            )
        }.let(::SkillMemoryListResult)

    override fun read(
        binding: SkillMemoryBinding,
        path: String,
        maxBytes: Int,
    ): SkillMemoryReadResult {
        val entry = memoryRepository.get(binding.memorySpaceId, path)
            ?: throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND)
        requireSameBinding(entry, binding)
        if (entry.byteLength > maxBytes.toLong()) {
            throw SkillMemoryException(SkillMemoryFailureCode.FILE_TOO_LARGE)
        }
        return SkillMemoryReadResult(
            path = entry.path,
            text = entry.content,
            bytes = entry.byteLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            version = entry.version.toString(),
        )
    }

    override fun search(
        binding: SkillMemoryBinding,
        query: String,
        maxResults: Int,
    ): SkillMemorySearchResult {
        if (maxResults !in 1..100) throw SkillMemoryException(SkillMemoryFailureCode.INVALID_QUERY)
        val queryLimit = if (maxResults < 100) maxResults + 1 else maxResults
        val entries = memoryRepository.search(binding.memorySpaceId, query, queryLimit)
        val truncated = entries.size > maxResults
        return SkillMemorySearchResult(
            hits = entries.take(maxResults).map { entry ->
                requireSameBinding(entry, binding)
                val lineStart = entry.content.lastIndexOf('\n', startIndex = entry.content.indexOf(query).coerceAtLeast(0))
                val line = entry.content.substring(0, lineStart.coerceAtLeast(0)).count { it == '\n' } + 1
                val snippet = entry.content.lineSequence().firstOrNull { query in it }?.take(512)
                    ?: entry.path
                SkillMemorySearchHit(entry.path, line, snippet)
            },
            truncated = truncated,
        )
    }

    override fun append(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
    ): SkillMemoryWriteResult = write(binding, path, text, expectedVersion, append = true)

    override fun replace(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
    ): SkillMemoryWriteResult = write(binding, path, text, expectedVersion, append = false)

    private fun write(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
        append: Boolean,
    ): SkillMemoryWriteResult {
        val previous = memoryRepository.get(binding.memorySpaceId, path)
        val expected = expectedVersion?.let {
            it.toLongOrNull() ?: throw SkillMemoryException(SkillMemoryFailureCode.CONFLICT)
        }
        val entry = if (append) {
            memoryRepository.append(binding.installId, binding.packageHash, path, text, expected)
        } else {
            memoryRepository.replace(binding.installId, binding.packageHash, path, text, expected)
        }
        requireSameBinding(entry, binding)
        return SkillMemoryWriteResult(
            path = entry.path,
            bytes = entry.byteLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            version = entry.version.toString(),
            created = previous == null,
        )
    }

    private fun requireSameBinding(
        entry: runtime.mobileagent.domain.SkillMemoryEntry,
        binding: SkillMemoryBinding,
    ) {
        if (entry.spaceId != binding.memorySpaceId ||
            entry.installId != binding.installId ||
            entry.packageHash != binding.packageHash
        ) {
            throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        }
    }
}

/** Redacted, append-only audit adapter shared by workspace and shell executors. */
private class SqliteRuntimeAuditSink(
    private val repository: AuditRepository,
    private val diagnostics: AndroidDiagnosticLogger,
) : WorkspaceAuditSink, ShellAuditSink, ApprovalLifecycleSink {
    override suspend fun record(event: WorkspaceAuditEvent): Boolean {
        val detail = runCatching {
            ToolAuditDetail.builder(
                auditId = UUID.randomUUID().toString(),
                requestId = event.requestId,
                agentId = event.agentId,
                capability = event.capability,
                result = event.resultCode ?: event.phase.name,
                createdAt = java.time.Instant.now().toString(),
            // workspace_list enumerates the authorized set and therefore has no single
            // workspace id.  The executor represents that scope as an empty string, while the
            // canonical audit domain requires an absent id rather than an invalid blank id.
            // Normalize only at this persistence boundary; concrete workspace operations keep
            // their exact opaque id.
            ).workspaceHash(event.workspaceId.takeIf { it.isNotBlank() }, event.relativePathSha256)
                .approval(event.approvalId)
                .duration(event.durationMs)
                .build()
        }.getOrNull() ?: return false
        return runCatching {
            repository.append(detail)
            diagnostics.recordWorkspaceOperationState(
                WorkspaceOperationStateRecord(
                    workspaceId = event.workspaceId,
                    operation = event.toDiagnosticOperation(),
                    state = event.toDiagnosticOperationState(),
                    count = if (event.phase == runtime.mobileagent.tooling.WorkspaceAuditPhase.STARTED) 0 else 1,
                    requestRef = event.requestId,
                    errorCode = event.resultCode?.lowercase(Locale.ROOT) ?: "none",
                ),
            )
            true
        }.getOrDefault(false)
    }

    override suspend fun recordStarted(event: runtime.mobileagent.skills.tooling.ShellAuditEvent): Boolean = appendShell(event)

    override suspend fun recordCompleted(event: runtime.mobileagent.skills.tooling.ShellAuditEvent): Boolean = appendShell(event)

    private fun appendShell(event: runtime.mobileagent.skills.tooling.ShellAuditEvent): Boolean {
        val detail = runCatching {
            ToolAuditDetail.builder(
                auditId = UUID.randomUUID().toString(),
                requestId = event.requestId,
                agentId = event.agentId,
                capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
                result = event.status?.name ?: event.phase.name,
                createdAt = java.time.Instant.now().toString(),
            ).skill(event.skillId)
                .authority(event.authority ?: Authority.NONE)
                .approval(event.approvalId)
                .dangerousMode(event.dangerousMode)
                .commandHash(event.commandSha256)
                .cwdHash(event.cwdSha256)
                .exitCode(event.exitCode)
                .timeout(event.timedOut)
                .cancel(event.cancelled)
                .outputBytes(event.stdoutBytes, event.stderrBytes)
                .duration(event.durationMs)
                .build()
        }.getOrNull() ?: return false
        return runCatching {
            repository.append(detail)
            diagnostics.recordShellExecutionState(
                ShellExecutionStateRecord(
                    commandSha256 = event.commandSha256,
                    terminalState = when (event.status) {
                        runtime.mobileagent.skills.tooling.ShellExecutionStatus.SUCCEEDED -> DiagnosticTerminalState.SUCCEEDED
                        runtime.mobileagent.skills.tooling.ShellExecutionStatus.TIMED_OUT -> DiagnosticTerminalState.TIMED_OUT
                        runtime.mobileagent.skills.tooling.ShellExecutionStatus.CANCELLED -> DiagnosticTerminalState.CANCELLED
                        runtime.mobileagent.skills.tooling.ShellExecutionStatus.UNKNOWN_OUTCOME -> DiagnosticTerminalState.UNKNOWN
                        null -> DiagnosticTerminalState.RUNNING
                        else -> DiagnosticTerminalState.FAILED
                    },
                    authority = when (event.authority) {
                        Authority.SHIZUKU -> DiagnosticAuthority.SHIZUKU
                        Authority.WIRED_ADB -> DiagnosticAuthority.WIRED_ADB
                        else -> DiagnosticAuthority.NONE
                    },
                    stdoutBytes = event.stdoutBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    stderrBytes = event.stderrBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    requestRef = event.requestId,
                    callId = event.callId,
                    agentId = event.agentId,
                    skillId = event.skillId,
                ),
            )
            true
        }.getOrDefault(false)
    }

    override fun record(event: ApprovalLifecycleEvent): Boolean {
        val detail = runCatching {
            ToolAuditDetail.builder(
                auditId = UUID.randomUUID().toString(),
                requestId = event.requestId,
                agentId = event.agentId,
                capability = event.capability,
                result = "APPROVAL_${event.transition.name}",
                createdAt = java.time.Instant.ofEpochMilli(event.timestampMs).toString(),
            ).skill(event.skillId)
                .authority(event.authority)
                .approval(event.approvalId)
                .dangerousMode(event.dangerousMode)
                .policyVersion(event.policyVersion)
                .commandHash(event.bindingSha256)
                .build()
        }.getOrNull() ?: return false
        return runCatching {
            repository.append(detail)
            diagnostics.recordToolApprovalState(
                runtime.mobileagent.diagnostics.ToolApprovalStateRecord(
                    callId = event.requestId,
                    state = event.transition.toDiagnosticApprovalState(),
                    approvalId = event.approvalId,
                    requestRef = event.requestId,
                    agentId = event.agentId,
                    skillId = event.skillId,
                    reasonCode = event.reasonCode?.name ?: "unknown",
                    capability = event.capability.toDiagnosticToolCapability(),
                    authority = event.authority.toDiagnosticAuthority(),
                    sessionRef = event.sessionIdentity,
                ),
            )
            true
        }.getOrDefault(false)
    }
}

/** Map the provider-neutral workspace operation into the diagnostics vocabulary. */
internal fun WorkspaceAuditEvent.toDiagnosticOperation(): DiagnosticOperation = when (operation) {
    runtime.mobileagent.tooling.WorkspaceAuditOperation.ENUMERATE,
    runtime.mobileagent.tooling.WorkspaceAuditOperation.LIST,
    runtime.mobileagent.tooling.WorkspaceAuditOperation.STAT,
        -> DiagnosticOperation.ENUMERATE
    runtime.mobileagent.tooling.WorkspaceAuditOperation.READ -> DiagnosticOperation.READ
    runtime.mobileagent.tooling.WorkspaceAuditOperation.WRITE,
    runtime.mobileagent.tooling.WorkspaceAuditOperation.MKDIR,
    runtime.mobileagent.tooling.WorkspaceAuditOperation.MOVE,
        -> DiagnosticOperation.WRITE
    runtime.mobileagent.tooling.WorkspaceAuditOperation.DELETE -> DiagnosticOperation.DELETE
}

/**
 * Terminal workspace diagnostics are derived from the redacted result code.
 * A terminal record with no known code remains UNKNOWN; it is never promoted
 * to success merely because the audit sink received the record.
 */
internal fun WorkspaceAuditEvent.toDiagnosticOperationState(): DiagnosticOperationState {
    if (phase == runtime.mobileagent.tooling.WorkspaceAuditPhase.STARTED) {
        return DiagnosticOperationState.STARTED
    }
    val code = resultCode?.trim()?.uppercase(Locale.ROOT)
    return when (code) {
        "SUCCEEDED", "SUCCESS", "COMPLETED", "COMPLETE" -> DiagnosticOperationState.SUCCEEDED
        "DENIED", "CAPABILITY_DENIED", "APPROVAL_REQUIRED", "APPROVAL_DENIED",
        "AUTHORITY_NOT_GRANTED", "AUTHORITY_PROVIDER_NOT_SELECTED", "AUTHORITY_TEMPORARILY_UNAVAILABLE",
        "SHIZUKU_PERMISSION_DENIED", "SHIZUKU_SERVICE_UNAVAILABLE", "BRIDGE_NOT_PAIRED",
        "ADB_DEVICE_UNAUTHORIZED", "DANGEROUS_MODE_DISABLED", "SHELL_CAPABILITY_DENIED",
        "SHELL_HIGH_RISK_APPROVAL_REQUIRED", "SNAPSHOT_STALE",
            -> DiagnosticOperationState.DENIED
        "CANCELLED", "CANCELED", "SHELL_CANCELLED", "REQUEST_CANCELLED" -> DiagnosticOperationState.CANCELLED
        "UNKNOWN", "UNKNOWN_OUTCOME" -> DiagnosticOperationState.UNKNOWN
        "FAILED", "ERROR", "IO_ERROR", "TIMEOUT" -> DiagnosticOperationState.FAILED
        // resultCode is the redacted provider contract for terminal state.
        // Missing or newly introduced values must fail closed to UNKNOWN;
        // the typed outcome is deliberately not used as a success fallback.
        null, "" -> DiagnosticOperationState.UNKNOWN
        else -> DiagnosticOperationState.UNKNOWN
    }
}

private fun ApprovalLifecycleTransition.toDiagnosticApprovalState(): DiagnosticApprovalState = when (this) {
    ApprovalLifecycleTransition.REQUESTED -> DiagnosticApprovalState.REQUESTED
    ApprovalLifecycleTransition.APPROVED -> DiagnosticApprovalState.APPROVED
    ApprovalLifecycleTransition.DENIED -> DiagnosticApprovalState.DENIED
    ApprovalLifecycleTransition.EXPIRED -> DiagnosticApprovalState.EXPIRED
    ApprovalLifecycleTransition.INVALIDATED -> DiagnosticApprovalState.INVALIDATED
    // There is no separate terminal diagnostic state for consumption; it is
    // the successful use of an approved grant, so retain APPROVED semantics.
    ApprovalLifecycleTransition.CONSUMED -> DiagnosticApprovalState.APPROVED
}

private fun runtime.mobileagent.domain.CapabilityId.toDiagnosticToolCapability(): runtime.mobileagent.diagnostics.DiagnosticToolCapability = when {
    value == CapabilityId.SHELL_EXECUTE -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.SHELL_EXECUTE
    value == CapabilityId.MEMORY_READ || value == CapabilityId.MEMORY_SEARCH -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.MEMORY_READ
    value == CapabilityId.MEMORY_APPEND || value == CapabilityId.MEMORY_REPLACE -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.MEMORY_WRITE
    value == CapabilityId.WORKSPACE_ENUMERATE || value == CapabilityId.FILE_LIST || value == CapabilityId.FILE_STAT || value == CapabilityId.FILE_READ_TEXT ->
        runtime.mobileagent.diagnostics.DiagnosticToolCapability.WORKSPACE_READ
    value == CapabilityId.FILE_WRITE_TEXT || value == CapabilityId.FILE_CREATE_DIRECTORY || value == CapabilityId.FILE_MOVE || value == CapabilityId.FILE_DELETE ->
        runtime.mobileagent.diagnostics.DiagnosticToolCapability.WORKSPACE_WRITE
    value == "search" -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.SEARCH
    else -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.UNKNOWN
}

private fun Authority.toDiagnosticAuthority(): DiagnosticAuthority = when (this) {
    Authority.NONE -> DiagnosticAuthority.NONE
    Authority.SHIZUKU -> DiagnosticAuthority.SHIZUKU
    Authority.WIRED_ADB -> DiagnosticAuthority.WIRED_ADB
}
