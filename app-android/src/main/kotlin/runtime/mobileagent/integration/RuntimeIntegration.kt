// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import runtime.mobileagent.AgentGrantPort
import runtime.mobileagent.ThreadWorkspacePort
import runtime.mobileagent.ThreadWorkspaceRuntimePort
import runtime.mobileagent.WorkspacePickerAuthoritySnapshot
import runtime.mobileagent.WorkspacePickerAuthorityStatus
import runtime.mobileagent.WorkspacePickerDirectoryAccess
import runtime.mobileagent.WorkspacePickerPort
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.SettingsAuthorityPort
import runtime.mobileagent.SettingsAuthorityMutation
import runtime.mobileagent.SettingsAuthorityProviderState
import runtime.mobileagent.SettingsAuthoritySnapshot
import runtime.mobileagent.SettingsSafGrantState
import runtime.mobileagent.SettingsWiredPairingPrompt
import runtime.mobileagent.SettingsWiredPairingRequestResult
import runtime.mobileagent.BuildConfig
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.AuditRepository
import runtime.mobileagent.data.AuthorityPolicyConflictException
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.AuthorityPreferencesRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.ConversationWorkspaceBindingRepository
import runtime.mobileagent.data.FullDeviceFilesGrantRepository
import runtime.mobileagent.data.AgentWorkspaceDefaultRepository
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.SafWorkspaceGrantRepository
import runtime.mobileagent.data.SkillMemoryRepository
import runtime.mobileagent.data.SkillRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.DangerousModeDecisionRecord
import runtime.mobileagent.diagnostics.BridgeRequestStateRecord
import runtime.mobileagent.diagnostics.DiagnosticReferenceHasher
import runtime.mobileagent.diagnostics.DiagnosticBridgeRequestState
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticAuthorityConfigurationReason
import runtime.mobileagent.diagnostics.DiagnosticBackendProbeState
import runtime.mobileagent.diagnostics.DiagnosticDangerousModeDecisionReason
import runtime.mobileagent.diagnostics.DiagnosticDangerousModePolicy
import runtime.mobileagent.diagnostics.DiagnosticLimitBucket
import runtime.mobileagent.diagnostics.DiagnosticLifecycleState
import runtime.mobileagent.diagnostics.DiagnosticOperation
import runtime.mobileagent.diagnostics.DiagnosticOperationState
import runtime.mobileagent.diagnostics.SkillMemoryOperationStateRecord
import runtime.mobileagent.diagnostics.WiredAdbLifecycleRecord
import runtime.mobileagent.diagnostics.ConversationWorkspaceEvent
import runtime.mobileagent.diagnostics.ConversationWorkspaceRecord
import runtime.mobileagent.diagnostics.AgentWorkspaceDefaultChangedRecord
import runtime.mobileagent.diagnostics.ConversationWorkspaceResolutionRecord
import runtime.mobileagent.diagnostics.DiagnosticThreadWorkspaceState
import runtime.mobileagent.diagnostics.DiagnosticWorkspaceDefaultResult
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AgentWorkspaceDefault
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.DesktopTrustStatus
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.workspace.READ_ONLY_WORKSPACE_CAPABILITIES
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceDraft
import runtime.mobileagent.domain.WorkspaceIntent
import runtime.mobileagent.domain.WorkspaceIntentPlan
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.domain.WorkspaceTarget
import runtime.mobileagent.domain.plan
import runtime.mobileagent.workspace.CanonicalWorkspaceSink
import runtime.mobileagent.workspace.WorkspaceUiKind
import runtime.mobileagent.workspace.WorkspaceUiPresentation
import runtime.mobileagent.workspace.WorkspaceUiPresentationStore
import runtime.mobileagent.workspace.fallbackWorkspaceUiPresentation
import runtime.mobileagent.workspace.persistedWorkspaceFolderLabel
import runtime.mobileagent.memory.SkillMemoryAvailabilitySnapshot
import runtime.mobileagent.memory.SkillMemoryDiagnosticOperation
import runtime.mobileagent.memory.SkillMemoryDiagnosticRefProvider
import runtime.mobileagent.memory.SkillMemoryDiagnosticReferences
import runtime.mobileagent.memory.SkillMemoryDiagnosticSink
import runtime.mobileagent.memory.SkillMemoryDiagnosticState
import runtime.mobileagent.memory.SkillMemoryRepositoryPort
import runtime.mobileagent.memory.SkillMemoryToolExecutor
import runtime.mobileagent.shizuku.ShizukuAuthorityBridge
import runtime.mobileagent.shizuku.ShizukuPermissionResult
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.security.AndroidAppInstanceIdStoreFactory
import runtime.mobileagent.security.AndroidKeystorePrivilegedWorkspaceBindingCipherFactory
import runtime.mobileagent.skills.PermissionGrant
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.tooling.AuditDegradedFuse
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.AuthorityState
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.tooling.ApprovalEngine
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.AuthorityStateStore
import runtime.mobileagent.tooling.DangerousBuildPolicy
import runtime.mobileagent.tooling.DangerousModeManager
import runtime.mobileagent.tooling.DangerousModeStateStore
import runtime.mobileagent.tooling.EffectiveCapabilityResolver
import runtime.mobileagent.tooling.ToolExecutionContext
import runtime.mobileagent.tooling.ToolExecutorFactory
import runtime.mobileagent.tooling.WorkspaceAuditFuse
import runtime.mobileagent.tooling.WorkspaceRegistry
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.wired.WiredAdbDiagnosticEvent
import runtime.mobileagent.wired.WiredAdbDiagnosticSink
import runtime.mobileagent.wired.WiredAdbErrorCode
import runtime.mobileagent.wired.WiredAdbLifecycleState
import runtime.mobileagent.wired.WiredAdbStatus
import runtime.mobileagent.wired.WiredAdbResult
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceResult

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
    /** Effective Agent-owned workspace grants surviving the frozen run gate. */
    val effectiveAgentWorkspaceCapabilityCount: Int = 0,
    /** Effective trusted-Skill workspace grants surviving the frozen run gate. */
    val effectiveSkillWorkspaceCapabilityCount: Int = 0,
    /** Registered backends with a usable descriptor and non-empty operation set. */
    val backendReadyWorkspaceCount: Int = 0,
    /** Registered backends that are missing, disabled, or expose no operations. */
    val backendProbeFailureCount: Int = 0,
    /** Privileged workspaces whose authority does not match the current selection. */
    val authorityMismatchWorkspaceCount: Int = 0,
    /** The model-facing schema was constructed from this run's frozen context. */
    val schemaFrozen: Boolean = false,
    val backendProbeState: DiagnosticBackendProbeState = DiagnosticBackendProbeState.UNKNOWN,
    /** Number of operations currently advertised by active SAF backends. */
    val safOperationCapabilityCount: Int = 0,
    val safProbeState: DiagnosticBackendProbeState = DiagnosticBackendProbeState.UNKNOWN,
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
) : SettingsAuthorityPort, runtime.mobileagent.RootRecoveryAuthorityPort, RootWorkspaceUiPort, ThreadWorkspacePort, ThreadWorkspaceRuntimePort, WorkspacePickerPort,
    CanonicalWorkspaceSink, WorkspacePresentationPort, ToolAssemblyPort, ToolApprovalAuthorityPort, RootExecutionPort, SkillMemoryAvailabilityPort, AutoCloseable {
    private val appContext = context.applicationContext
    private val workspaceUiPresentations = WorkspaceUiPresentationStore(appContext)
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val callbackGate = RuntimeCallbackGate()
    private val closed = AtomicBoolean(false)
    private val wiredReconnectInFlight = AtomicBoolean(false)
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
    private val privilegedWorkspaceBindingRepository = PrivilegedWorkspaceBindingRepository(db)
    private val conversationWorkspaceBindingRepository = ConversationWorkspaceBindingRepository(db)
    private val agentWorkspaceDefaultRepository = AgentWorkspaceDefaultRepository(db)
    private val fullDeviceFilesGrantRepository = FullDeviceFilesGrantRepository(db)
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
    private val rootCoordinatorDelegate = lazy { runtime.mobileagent.privileged.RootRuntimeCoordinator(appContext, db) }
    val rootCoordinator by rootCoordinatorDelegate
    private val workspaceRegistry = WorkspaceRegistry()
    private val bindingCipher = AndroidKeystorePrivilegedWorkspaceBindingCipherFactory.create()
    private val appInstanceId = AndroidAppInstanceIdStoreFactory.create(appContext).loadOrCreateAppInstanceId()
    private val auditFuse = AuditDegradedFuse()
    private val workspaceAuditFuse = WorkspaceAuditFuse()
    private val authorityDiagnostics = RuntimeAuthorityDiagnosticSink(diagnostics)
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
    private val privilegedWorkspaceProviders = linkedMapOf<Authority, PrivilegedWorkspaceProvider>()
    private val agentGrantPort = ContainerAgentGrantPort()
    private val workspaceAccessAdapter = RuntimeWorkspaceAccessPort()

    /** One canonical facade for SAF, selected-authority browsing, and workspace grants. */
    val workspaceAccessPort: WorkspaceAccessPort
        get() = workspaceAccessAdapter

    private val safLivenessProbe = SafLivenessProbe(
        scope = scope,
        isRegisteredBackend = { workspaceId, backend ->
            workspaceRegistry.registered(workspaceId)?.backend === backend
        },
        probeIntervalMs = SAF_LIVENESS_PROBE_INTERVAL_MS,
        probeDeadlineMs = SAF_LIVENESS_PROBE_DEADLINE_MS,
    )
    private fun isSafDown(workspaceId: String, backend: WorkspaceBackend) =
        safLivenessProbe.isDown(workspaceId, backend)

    /** Bumped whenever a SAF probe flips a workspace's health — the UI
     *  collects this to re-run workspace listings without navigation. */
    override val workspaceHealthRevision: StateFlow<Long> = safLivenessProbe.revision
    private val shizukuPermissionRequestPending = AtomicBoolean(false)
    private val shizukuStateListener: (ShizukuAuthorityState) -> Unit = { state ->
      callbackGate.run {
        authorityLifecycle.applyShizukuState(state)
        authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.PLATFORM_STATE_CHANGE)
      }
    }
    private val shizukuPermissionListener: (ShizukuPermissionResult) -> Unit = { result ->
      callbackGate.run {
        if (shizukuPermissionRequestPending.compareAndSet(true, false)) {
            // Only an explicit foreground grant may persist configuration as
            // enabled. A later live Binder denial may clear it, while Binder
            // loss, reconnect and process restart preserve the last grant.
            authorityManager.setConfigured(ElevatedAuthority.SHIZUKU, result.granted)
            authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
      }
    }

    private val workspaceGrantStore = RuntimeWorkspaceGrantStore(
        db, workspaceRepository, capabilityGrantRepository, authorityPolicyRepository,
        conversationWorkspaceBindingRepository, agentWorkspaceDefaultRepository,
    )
    private val directoryAccess = RuntimeDirectoryAccess(
        appContext, db, workspaceRepository, capabilityGrantRepository, authorityPolicyRepository,
        privilegedWorkspaceBindingRepository, safWorkspaceGrantRepository, fullDeviceFilesGrantRepository,
        workspaceRegistry, bindingCipher, appInstanceId, wiredAuthority, buildPolicy, dangerousModeManager,
        diagnostics, workspaceGrantStore, ::authorityProviderFor, ::privilegedFailure,
        isRootWorkspace = { rootWorkspaceUi.isRoot(it) },
        presentation = object : RuntimeWorkspacePresentation {
            override fun item(workspace: Workspace, agentId: String?) = workspaceAccessItem(workspace, agentId = agentId)
            override fun displayName(workspace: Workspace, ordinal: Int) = safeWorkspaceDisplayName(workspace, ordinal)
            override fun authority(workspace: Workspace) = workspace.authorityOrNull()
            override fun recordBinding(binding: ConversationWorkspaceBinding, agentId: String) =
                recordConversationWorkspaceBinding(binding, agentId, ConversationWorkspaceEvent.BOUND, previousWorkspaceId = null)
            override fun recordDefault(previous: String?, persisted: AgentWorkspaceDefault) =
                recordAgentWorkspaceDefaultChanged(previous, persisted, DiagnosticWorkspaceDefaultResult.SET)
            override fun remember(workspaceId: String, kind: WorkspaceUiKind, title: String) =
                rememberUiPresentation(workspaceId, kind, title)
        },
    )
    private val workspaceRecovery = RuntimeWorkspaceRecovery(
        appContext, agents, workspaceRepository, safWorkspaceGrantRepository, privilegedWorkspaceBindingRepository,
        fullDeviceFilesGrantRepository, capabilityGrantRepository, workspaceRegistry, authorityManager, wiredAuthority,
        bindingCipher, appInstanceId, diagnostics, scope, closed, ::authorityProviderFor,
    )

    /** Fixture seam sharing the production registry and canonical repository. */
    internal fun registerAppPrivateWorkspace(workspaceId: String, root: Path): Workspace =
        workspaceRecovery.registerAppPrivateWorkspace(workspaceId, root)

    private val runToolAssembly = RuntimeRunToolAssembly(
        db, workspaceRegistry, approvalEngine, effectiveCapabilityResolver, capabilityGrantRepository,
        authorityPolicyRepository, authorityManager, dangerousModeManager, shellBackends, auditSink, auditFuse,
        workspaceAuditFuse, skillMemoryRepositoryPort, skillMemoryDiagnosticSink, skillMemoryDiagnosticRefProvider,
        rootCoordinator = { rootCoordinator }, freezeContext = ::freezeContext,
    )

    private val authorityLifecycle = RuntimeAuthorityLifecycle(
        authorityManager, dangerousModeManager, buildPolicy, wiredAuthority, diagnostics, authorityDiagnostics,
        privilegedWorkspaceBindingRepository, workspaceRegistry, workspaceRecovery,
        rootCoordinator = { rootCoordinator }, scope = scope, closed = closed, wiredReconnectInFlight = wiredReconnectInFlight,
    )

    /** Low-level bridge guard shares the lifecycle's reconciled intent and canonical live state. */
    internal fun wiredShellPermissionAvailable(): Boolean = authorityLifecycle.wiredShellPermissionAvailable()

    init {
      try {
        rootCoordinator.reconcileStartupRecovery()
        wireShizukuBackend()
        wireWiredBackend()
        workspaceRecovery.reconcileFullDeviceGrants()
        workspaceRecovery.hydrateWorkspaces()
        authorityLifecycle.installDiagnosticsCollectors()
        shizukuAuthority?.addStateListener(shizukuStateListener)
        shizukuAuthority?.addPermissionResultListener(shizukuPermissionListener)
        shizukuAuthority?.let { authorityLifecycle.applyShizukuState(it.state.value) }
        rootCoordinator.refreshLiveSessions(shizukuAuthority?.state?.value)
        rootCoordinator.syncAuthorityManagerStates(authorityManager, shizukuAuthority?.state?.value)
        authorityLifecycle.applyWiredState(wiredAuthority.status.value)
        authorityLifecycle.scheduleWiredReconnect()
        workspaceRecovery.refreshSafWorkspace()
        authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.SNAPSHOT)
      } catch (failure: Throwable) {
          close()
          throw failure
      }
    }

    /** UI-safe, repository-backed grant port used by AgentsViewModel. */
    val grants: AgentGrantPort
        get() = agentGrantPort

    override val available: Boolean
        get() = true

    override val unavailableMessage: String
        get() = "工作区运行时未就绪。"

    override fun conversationWorkspaceBinding(conversationId: String): ConversationWorkspaceBinding? =
        conversationWorkspaceBindingRepository.get(conversationId)

    override fun bindConversationWorkspace(binding: ConversationWorkspaceBinding): ConversationWorkspaceBinding {
        val previous = conversationWorkspaceBindingRepository.get(binding.sessionId)
        if (previous != null && previous.workspaceId != binding.workspaceId) {
            throw IllegalStateException("Conversation workspace is immutable after the first bind")
        }
        val persisted = conversationWorkspaceBindingRepository.bind(
            sessionId = binding.sessionId,
            workspaceId = binding.workspaceId,
            boundAt = binding.boundAt,
        )
        if (previous == null) {
            recordConversationWorkspaceBinding(
                binding = persisted,
                agentId = agents.getSnapshot(
                    db.query("SELECT agent_snapshot_id FROM conversations WHERE id = ?", listOf(binding.sessionId))
                        .singleOrNull()
                        ?.string("agent_snapshot_id")
                        .orEmpty(),
                )?.agentId.orEmpty(),
                event = ConversationWorkspaceEvent.BOUND,
                previousWorkspaceId = null,
            )
        }
        return persisted
    }

    override fun agentWorkspaceDefault(agentId: String): AgentWorkspaceDefault? =
        agentWorkspaceDefaultRepository.get(agentId)

    override fun resolveNewThreadWorkspace(agentId: String): String? {
        val id = agentWorkspaceDefaultRepository.resolveForNewThread(agentId) ?: return null
        if (!rootWorkspaceUi.canSealNewThreadSnapshot(id)) {
            throw runtime.mobileagent.ChatRootErrors.unavailable(runtime.mobileagent.ChatRootErrors.NEW_THREAD_STAGE, agentId)
        }
        return id
    }

    override fun saveAgentWorkspaceDefault(default: AgentWorkspaceDefault): AgentWorkspaceDefault {
        val previous = agentWorkspaceDefaultRepository.get(default.agentId)
        return try {
            agentWorkspaceDefaultRepository.save(default).also { persisted ->
                recordAgentWorkspaceDefaultChanged(
                    previous?.workspaceId,
                    persisted,
                    if (persisted.workspaceId == null) {
                        DiagnosticWorkspaceDefaultResult.CLEARED
                    } else {
                        DiagnosticWorkspaceDefaultResult.SET
                    },
                )
            }
        } catch (failure: RuntimeException) {
            diagnostics.recordAgentWorkspaceDefaultChanged(
                AgentWorkspaceDefaultChangedRecord(
                    agentId = default.agentId,
                    previousWorkspaceId = previous?.workspaceId,
                    workspaceId = default.workspaceId,
                    grantGeneration = defaultGrantGeneration(default.agentId, default.workspaceId),
                    result = DiagnosticWorkspaceDefaultResult.FAILED,
                    errorCode = "persistence_failed",
                ),
            )
            throw failure
        }
    }

    override fun createSnapshotWithWorkspace(
        agentId: String, workspaceId: String?, snapshotId: String, at: String,
    ): AgentSnapshot = RootSnapshotFactory(db, rootCoordinator).withWorkspace(agentId, workspaceId, snapshotId, at)

    override fun createToolExecutionContextForWorkspace(
        snapshot: AgentSnapshot,
        workspaceId: String?,
        modelCallId: String,
        sessionIdentity: String,
        configSnapshotHash: String,
        taskIdentity: String,
        skillId: String?,
        skillRevision: Long?,
        trustedSkillEnvelope: Boolean,
    ): ToolExecutionContext {
        require(agents.getSnapshot(snapshot.id) == snapshot) { "Agent snapshot is unavailable" }
        val binding = conversationWorkspaceBindingRepository.get(sessionIdentity)
        require(binding?.workspaceId == workspaceId) { "Conversation workspace binding changed" }
        val policy = authorityPolicyRepository.getPolicy()
        return freezeContext(
            ToolExecutionContext(
                agentId = snapshot.agentId,
                snapshotId = snapshot.id,
                modelCallId = modelCallId,
                sessionIdentity = sessionIdentity,
                taskIdentity = taskIdentity,
                configSnapshotHash = configSnapshotHash,
                policyVersion = policy.policyVersion,
                skillId = skillId,
                skillRevision = skillRevision,
                trustedSkillEnvelope = trustedSkillEnvelope,
                // Freeze every snapshot Skill identity for namespaced memory
                // tools.  Discovery still filters by live grant/capability
                // bindings, so ungranted Skills expose nothing.
                trustedSkillIds = (snapshot.skillIds.toSet() + listOfNotNull(skillId)).filter { it.isNotBlank() }.toSet(),
                authoritySelection = authorityManager.selection.value,
            ),
        )
    }

    override fun recordConversationWorkspaceResolution(sessionId: String, snapshot: AgentSnapshot) {
        if (sessionId.isBlank() || snapshot.agentId.isBlank()) return
        val binding = conversationWorkspaceBindingRepository.get(sessionId)
        // Keep the configured default distinct from the currently resolvable default.  A revoked
        // or unavailable default is still useful diagnostic context, but it must not be promoted
        // to UNBOUND_AGENT_DEFAULT_AVAILABLE or used to seed a new Thread.
        val storedAgentDefaultWorkspaceId = agentWorkspaceDefaultRepository.get(snapshot.agentId)?.workspaceId
        val resolvedAgentDefaultWorkspaceId = agentWorkspaceDefaultRepository.resolveForNewThread(snapshot.agentId)
        val snapshotBindings = capabilityGrantRepository.listSnapshotBindings(snapshot.id)
        val snapshotWorkspaceIds = snapshotBindings.mapNotNull { it.workspaceId }.toSet()
        val selectedWorkspaceId = binding?.workspaceId
        val fullDeviceWorkspaceIds = activeFullDeviceWorkspaceIds(snapshot.agentId)
        val policy = authorityPolicyRepository.getPolicy()
        val grants = capabilityGrantRepository.forAgent(snapshot.agentId, includeRevoked = true)
            .filter { grant ->
                grant.workspaceId == null || grant.workspaceId == selectedWorkspaceId ||
                    grant.workspaceId in fullDeviceWorkspaceIds
            }
        val selectedBindings = snapshotBindings.filter { item ->
            item.workspaceId == null || item.workspaceId == selectedWorkspaceId
        }
        val effectiveWorkspaceGrantCount = runCatching {
            effectiveCapabilityResolver.resolveForRun(
                snapshot = snapshot,
                grants = grants,
                snapshotBindings = selectedBindings,
                currentPolicyVersion = policy.policyVersion,
                taskIdentity = null,
                sessionIdentity = sessionId,
            ).grants.count { it.workspaceId != null }
        }.getOrDefault(0)
        diagnostics.recordConversationWorkspaceResolution(
            ConversationWorkspaceResolutionRecord(
                sessionId = sessionId,
                agentId = snapshot.agentId,
                threadWorkspaceState = when {
                    binding != null -> DiagnosticThreadWorkspaceState.BOUND
                    resolvedAgentDefaultWorkspaceId != null -> DiagnosticThreadWorkspaceState.UNBOUND_AGENT_DEFAULT_AVAILABLE
                    else -> DiagnosticThreadWorkspaceState.UNBOUND_NO_AGENT_DEFAULT
                },
                workspaceId = binding?.workspaceId,
                agentDefaultWorkspaceId = storedAgentDefaultWorkspaceId,
                snapshotWorkspaceCount = snapshotWorkspaceIds.size,
                effectiveWorkspaceGrantCount = effectiveWorkspaceGrantCount,
            ),
        )
    }

    /** Optional accessibility actions assist only the explicitly selected, ready ADB authority. */
    override fun screenActionAuthority(): runtime.mobileagent.observation.ScreenActionAuthority? =
        runtime.mobileagent.observation.ScreenActionAdmission.from(authorityManager.state.value)

    override fun authoritySnapshot(): WorkspacePickerAuthoritySnapshot {
        val manager = authorityManager.state.value
        val selected = manager.selectedAuthority ?: Authority.NONE
        val status = manager.statuses[selected]
        val pickerStatus = when {
            selected == Authority.NONE -> WorkspacePickerAuthorityStatus.NOT_SELECTED
            status?.isReady == true -> WorkspacePickerAuthorityStatus.READY
            status?.availability == Availability.UNSUPPORTED -> WorkspacePickerAuthorityStatus.UNSUPPORTED
            status?.connection == Connection.CONNECTING -> WorkspacePickerAuthorityStatus.CONNECTING
            else -> WorkspacePickerAuthorityStatus.OFFLINE
        }
        return WorkspacePickerAuthoritySnapshot(
            selectedAuthority = selected,
            status = pickerStatus,
            ready = pickerStatus == WorkspacePickerAuthorityStatus.READY,
        )
    }

    override fun recentWorkspaces(agentId: String?): List<WorkspaceAccessItem> = workspaceRepository.list()
        .asSequence()
        .filter { it.enabled && it.scope == WorkspaceScope.SELECTED_DIRECTORY }
        .filter { workspace ->
            rootWorkspaceUi.includeInRecent(workspace, privilegedWorkspaceBindingRepository.get(workspace.id) != null)
        }
        .mapIndexed { index, workspace -> workspaceAccessItem(workspace, index + 1, agentId) }
        .toList()

    override suspend fun browsePrivilegedRoot(
        authority: Authority,
        maxEntries: Int,
    ): WorkspaceResult<WorkspaceDirectoryPage> = workspaceAccessAdapter.browsePrivilegedRoot(authority, maxEntries)

    override suspend fun browsePrivileged(
        authority: Authority,
        request: WorkspaceBrowseRequest,
    ): WorkspaceResult<WorkspaceDirectoryPage> = workspaceAccessAdapter.browsePrivileged(authority, request)

    override fun directoryAccess(page: WorkspaceDirectoryPage): WorkspacePickerDirectoryAccess {
        val selected = authorityManager.state.value.selectedAuthority
        val backend = selected?.let(workspaceBackends::get)
        return WorkspacePickerDirectoryAccess(
            readable = backend?.descriptor?.readable == true,
            writable = backend?.descriptor?.writable == true,
        )
    }

    private val rootWorkspaceUi by lazy { RootWorkspaceUiController(db, rootCoordinator) }
    override suspend fun browseRootDirectory(authority: Authority, path: String, cursor: String?) =
        rootWorkspaceUi.browseRootDirectory(authority, path, cursor)
    override fun rootWorkspacePermissions(workspaceId: String, agentId: String) =
        rootWorkspaceUi.rootWorkspacePermissions(workspaceId, agentId)
    override suspend fun updateRootWorkspacePermissions(
        authority: Authority, workspaceId: String, agentId: String,
        writable: Boolean, metadata: Boolean, shell: Boolean,
    ) = rootWorkspaceUi.updateRootWorkspacePermissions(authority, workspaceId, agentId, writable, metadata, shell)


    override suspend fun attachRootDirectory(
        authority: Authority, absolutePath: String, writable: Boolean, metadata: Boolean, shell: Boolean,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult = rootWorkspaceUi.attach(authority, absolutePath, writable, metadata, shell, target)

    override suspend fun attachPrivilegedDirectory(
        authority: Authority,
        request: WorkspaceAttachRequest,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult {
        val plan = planFor(target)
        return directoryAccess.attachPrivilegedDirectoryInternal(
            authority,
            request,
            grantTargetFor(plan, target.toWorkspaceTarget()),
            pickerTarget = target,
            plan = plan,
        )
    }

    override suspend fun attachSaf(
        uri: Uri,
        resultFlags: Int,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult {
        val plan = planFor(target)
        return directoryAccess.attachSafWorkspace(
            uri,
            resultFlags,
            grantTargetFor(plan, target.toWorkspaceTarget()),
            pickerTarget = target,
            plan = plan,
        )
    }

    override suspend fun useRecentWorkspace(
        workspaceId: String,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult = useRecentWorkspace(workspaceId = workspaceId, pickerTarget = target, plan = planFor(target))

    internal suspend fun useRecentWorkspace(
        workspaceId: String,
        pickerTarget: WorkspacePickerTarget?,
        plan: WorkspaceIntentPlan,
    ): WorkspaceAccessResult {
        if (rootWorkspaceUi.isRoot(workspaceId)) {
            return rootWorkspaceUi.useRecentNullable(workspaceId, pickerTarget, plan)
        }
        return selectOrdinaryWorkspace(workspaceId, pickerTarget, plan)
    }

    private suspend fun selectOrdinaryWorkspace(
        workspaceId: String,
        pickerTarget: WorkspacePickerTarget?,
        plan: WorkspaceIntentPlan,
    ): WorkspaceAccessResult {
        val workspace = workspaceRepository.get(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (!workspace.enabled || workspace.scope != WorkspaceScope.SELECTED_DIRECTORY) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        if (workspace.backendType == WorkspaceBackendType.PRIVILEGED &&
            workspaceRegistry.registered(workspaceId) == null
        ) {
            workspaceRecovery.reattachPrivilegedWorkspace(workspaceId)
        }
        val selection = try {
            db.transaction {
                val grant = grantTargetFor(plan, pickerTarget.toWorkspaceTarget())
                workspaceGrantStore.persistPickerSelection(workspace, pickerTarget, plan, grant) {
                    workspaceRegistry.registered(workspaceId)?.backend
                        ?: throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
                }
            }
        } catch (failure: RuntimeWorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        return directoryAccess.finishPickerSelection(workspace, pickerTarget, selection)
    }

    override suspend fun confirmNewThreadWorkspace(
        agentId: String,
        currentThreadId: String,
        currentWorkspaceId: String,
        requestedWorkspaceId: String,
    ): WorkspaceAccessResult {
        if (rootWorkspaceUi.isRoot(requestedWorkspaceId)) return rootWorkspaceUi.confirmNewThread(
            agentId, currentThreadId, currentWorkspaceId, requestedWorkspaceId,
        )
        val agent = agents.get(agentId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        val currentBinding = conversationWorkspaceBindingRepository.get(currentThreadId)
        if (currentBinding == null || currentBinding.workspaceId != currentWorkspaceId) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        val workspace = workspaceRepository.get(requestedWorkspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (!workspace.enabled || workspace.scope != WorkspaceScope.SELECTED_DIRECTORY) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        when (workspace.backendType) {
            WorkspaceBackendType.PRIVILEGED -> {
                val authority = workspace.authorityOrNull()
                    ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED)
                val authSnapshot = authoritySnapshot()
                if (authSnapshot.selectedAuthority != authority) {
                    return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED)
                }
                if (!authSnapshot.ready) {
                    return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
                }
                if (workspaceRegistry.registered(workspace.id) == null) {
                    workspaceRecovery.reattachPrivilegedWorkspace(workspace.id)
                }
                if (workspaceRegistry.registered(workspace.id) == null) {
                    return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
                }
            }
            WorkspaceBackendType.SAF_TREE -> {
                val uri = Uri.parse(workspace.rootReference)
                val persisted = appContext.contentResolver.persistedUriPermissions
                    .firstOrNull { it.uri == uri }
                if (persisted == null || !persisted.isReadPermission) {
                    return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
                }
                if (workspaceRegistry.registered(workspace.id) == null) {
                    try {
                        val backend = runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(
                            appContext, uri, workspace.id,
                        )
                        workspaceRegistry.registerOrReplace(workspace, backend)
                    } catch (_: RuntimeException) {
                        return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
                    }
                }
            }
            WorkspaceBackendType.INTERNAL -> {
                if (workspaceRegistry.registered(workspace.id) == null) {
                    if (workspace.id == INTERNAL_WORKSPACE_ID) {
                        workspaceRecovery.adoptInternalWorkspace()
                    } else {
                        val root = runCatching { java.nio.file.Paths.get(workspace.rootReference) }.getOrNull()
                        if (root != null && (java.nio.file.Files.exists(root) || runCatching { java.nio.file.Files.createDirectories(root) }.isSuccess)) {
                            workspaceRegistry.registerOrReplace(
                                workspace,
                                runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createInternal(root, workspace.id),
                            )
                        }
                    }
                }
            }
        }
        val registered = workspaceRegistry.registered(workspace.id)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)

        val committedGrants = try {
            db.transaction {
                workspaceGrantStore.persistWorkspaceGrantBundle(
                    workspace = workspace,
                    backend = registered.backend,
                    target = WorkspaceAccessGrantTarget(agentId = agentId),
                    preserveActiveWholeDirectoryCapabilities = true,
                )
            }
        } catch (failure: RuntimeWorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        } catch (_: AuthorityPolicyConflictException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }

        val item = committedWorkspaceAccessItem(
            workspace = workspace,
            displayName = safeWorkspaceDisplayName(workspace, 1),
            status = WorkspaceAccessStatus.ACTIVE,
            authority = workspace.authorityOrNull(),
            activeGrants = committedGrants,
            fullDeviceConfirmationPresent = true,
        )
        return WorkspaceAccessResult.Success(
            workspace = item,
            grants = committedGrants.map { it.toWorkspaceAccessSummary() },
        )
    }

    suspend fun confirmNewThreadWorkspace(
        result: WorkspaceAccessResult.NewThreadRequired,
    ): WorkspaceAccessResult = confirmNewThreadWorkspace(
        agentId = result.agentId,
        currentThreadId = result.currentThreadId,
        currentWorkspaceId = result.currentWorkspaceId,
        requestedWorkspaceId = result.requestedWorkspaceId,
    )

    // ---------------------------------------------------------------------
    // Canonical workspace sink
    //
    // These methods are the only workspace write path.  A screen resolves
    // a WorkspaceIntent + WorkspaceTarget, the intent is planned once, and
    // the transaction below derives grant / Agent default / Thread binding
    // from that plan.  No caller can compose its own combination.
    // ---------------------------------------------------------------------

    override suspend fun attachPrivileged(
        authority: Authority,
        request: WorkspaceAttachRequest,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult = directoryAccess.attachPrivilegedDirectoryInternal(
        authority = authority,
        request = request,
        grant = grantTargetFor(plan, target),
        pickerTarget = pickerTargetFor(plan, target),
        plan = plan,
    )

    override suspend fun attachSaf(
        uri: Uri,
        resultFlags: Int,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult = directoryAccess.attachSafWorkspace(
        uri = uri,
        resultFlags = resultFlags,
        grant = grantTargetFor(plan, target),
        pickerTarget = pickerTargetFor(plan, target),
        plan = plan,
    )

    override suspend fun attachPrivilegedPath(
        authority: Authority,
        workspaceId: String,
        displayName: String,
        absolutePath: String,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult = directoryAccess.attachPrivilegedPathInternal(
        authority = authority,
        workspaceId = workspaceId,
        displayName = displayName,
        absolutePath = absolutePath,
        grant = grantTargetFor(plan, target),
        pickerTarget = pickerTargetFor(plan, target),
        plan = plan,
    )

    override suspend fun openFullDeviceFiles(
        authority: Authority,
        request: FullDeviceFilesRequest,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult {
        // A full-device workspace can never become an Agent default or a
        // Thread binding; only its grant semantics follow the plan.
        if (plan.setAgentDefault || plan.bindThread) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        return directoryAccess.openFullDeviceFilesInternal(
            authority = authority,
            request = request,
            grant = grantTargetFor(plan, target),
        )
    }

    override suspend fun attachRootDirectory(
        authority: Authority,
        absolutePath: String,
        writable: Boolean,
        metadata: Boolean,
        shell: Boolean,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult = rootWorkspaceUi.attachWithPlan(
        authority = authority,
        path = absolutePath,
        writable = writable,
        metadata = metadata,
        shell = shell,
        plan = plan,
        target = target,
    )

    override suspend fun useRecent(
        workspaceId: String,
        plan: WorkspaceIntentPlan,
        target: WorkspaceTarget,
    ): WorkspaceAccessResult = useRecentWorkspace(
        workspaceId = workspaceId,
        pickerTarget = pickerTargetFor(plan, target),
        plan = plan,
    )

    /**
     * Commit a staged draft. Grant and Agent default are written in one
     * transaction, so a failure cannot leave an Agent that is granted but not
     * defaulted (or the reverse).
     */
    override suspend fun commitDraft(draft: WorkspaceDraft, agentId: String): WorkspaceAccessResult {
        if (rootWorkspaceUi.isRoot(draft.workspaceId)) {
            return rootWorkspaceUi.commitDraft(draft, agentId)
        }
        val target = WorkspacePickerTarget(agentId = agentId)
        val intent = if (draft.setAsAgentDefault) WorkspaceIntent.SET_AGENT_DEFAULT else WorkspaceIntent.ADD_TO_LIBRARY
        return selectOrdinaryWorkspace(draft.workspaceId, target, intent.plan(target.toWorkspaceTarget()))
    }

    private fun grantTargetFor(plan: WorkspaceIntentPlan, target: WorkspaceTarget): WorkspaceAccessGrantTarget? =
        if (!plan.grantRequired) null else WorkspaceAccessGrantTarget(agentId = requireNotNull(target.agentId))

    private fun pickerTargetFor(plan: WorkspaceIntentPlan, target: WorkspaceTarget): WorkspacePickerTarget? =
        if (target.agentId == null && target.threadId == null) {
            null
        } else {
            WorkspacePickerTarget(
                agentId = target.agentId,
                threadId = target.threadId.takeIf { plan.bindThread },
            )
        }

    private fun WorkspacePickerTarget?.toWorkspaceTarget(): WorkspaceTarget = WorkspaceTarget(
        agentId = this?.agentId,
        threadId = this?.threadId,
    )

    /**
     * Derive the single canonical plan for a picker identity.
     *
     * A Thread id always [WorkspaceIntent.BIND_THREAD]s and never mutates the
     * Agent default. An Agent id without a Thread is the normal editor flow
     * ([WorkspaceIntent.SET_AGENT_DEFAULT]). An empty target only adds the
     * workspace to the library. A deferred Agent-default selection attaches
     * the workspace to the library and stages grant/default for [commitDraft].
     */
    private fun planFor(target: WorkspacePickerTarget?): WorkspaceIntentPlan {
        if (target == null) return WorkspaceIntent.ADD_TO_LIBRARY.plan(WorkspaceTarget())
        val workspaceTarget = target.toWorkspaceTarget()
        val intent = when {
            target.threadId != null -> WorkspaceIntent.BIND_THREAD
            target.agentId != null -> WorkspaceIntent.SET_AGENT_DEFAULT
            else -> WorkspaceIntent.ADD_TO_LIBRARY
        }
        return intent.plan(workspaceTarget)
    }

    /**
     * Local UI projection. Never used by tool schema, prompts or diagnostics.
     * SharedPreferences labels are display metadata only; losing them cannot
     * revoke a workspace. Fallback titles come from canonical Workspace
     * displayName after rejecting URI/locator markers.
     */
    /** Attribution only: never grants access or selects a fallback Authority. */
    override fun diagnosticWorkspaceAuthority(workspaceId: String?): DiagnosticAuthority =
        workspaceId?.let(workspaceRepository::get)?.authorityOrNull()?.toDiagnostic() ?: DiagnosticAuthority.NONE

    /** Approval classification must distinguish an ordinary workspace from a failed/missing read. */
    override fun approvalWorkspaceAuthority(workspaceId: String): DiagnosticAuthority {
        val workspace = requireNotNull(workspaceRepository.get(workspaceId)) { "Workspace unavailable for approval" }
        if (workspace.backendType != WorkspaceBackendType.PRIVILEGED) return DiagnosticAuthority.NONE
        return requireNotNull(workspace.authorityOrNull()) { "Workspace authority unavailable for approval" }.toDiagnostic()
    }

    override fun workspaceUiPresentation(workspaceId: String, chinese: Boolean): WorkspaceUiPresentation? {
        workspaceUiPresentations.get(workspaceId)?.let { return it }
        val workspace = workspaceRepository.get(workspaceId) ?: return null
        return fallbackWorkspaceUiPresentation(workspace, workspace.authorityOrNull(), chinese)
    }

    private fun rememberUiPresentation(
        workspaceId: String,
        kind: WorkspaceUiKind,
        title: String,
        breadcrumb: String? = null,
    ) {
        val safeTitle = title.takeIf { it.isNotBlank() && !WorkspaceUiPresentation.containsSensitive(it) } ?: return
        runCatching {
            workspaceUiPresentations.put(
                WorkspaceUiPresentation(
                    workspaceId = workspaceId,
                    kind = kind,
                    title = safeTitle.take(WorkspaceUiPresentation.MAX_TITLE),
                    breadcrumb = breadcrumb?.takeIf { it.isNotBlank() && !WorkspaceUiPresentation.containsSensitive(it) },
                ),
            )
        }
    }

    /** Compose only the tools admitted by this run's frozen and live canonical authorization. */
    override fun createToolExecutorFactory(
        context: ToolExecutionContext,
        web: ToolExecutor?,
        mcp: ToolExecutor?,
        python: ToolExecutor?,
    ): ToolExecutorFactory = runToolAssembly.createToolExecutorFactory(context, web, mcp, python)

    /** Frozen Root routes remain reserved while their live service is unavailable. */
    override fun frozenRootWorkspaceIds(context: ToolExecutionContext): Set<String> = runToolAssembly.frozenRootWorkspaceIds(context)

    /**
     * Return a closed, aggregate-only explanation of the workspace exposure inputs for one run.
     * This is diagnostic data, not an authorization decision; executors still revalidate every
     * grant, binding, provider state, path, and one-shot lifetime immediately before dispatch.
     */
    override fun toolExposureDiagnostics(context: ToolExecutionContext): RuntimeToolExposureDiagnostics {
        val frozen = freezeContext(context)
        val agentContext = freezeContext(context.copy(skillId = null, skillRevision = null, trustedSkillEnvelope = false))
        val registeredIds = workspaceRegistry.descriptors().map { it.id }.toSet()
        val grantedIds = frozen.canonicalGrants.mapNotNull { it.workspaceId }.toSet()
        val boundIds = frozen.snapshotGrantBindings.mapNotNull { it.workspaceId }.toSet()
        val authorityState = authorityManager.state.value
        val selected = authorityState.selectedAuthority
        val selectedState = authorityState.statuses[selected]
        val safGrants = safWorkspaceGrantRepository.list(includeRevoked = true)
        val activeSafGrants = safGrants.filter { it.status == SafGrantStatus.ACTIVE }
        val enabledWorkspaces = workspaceRepository.list(enabledOnly = true)
        val readyWorkspaceIds = enabledWorkspaces.mapNotNull { workspace ->
            workspace.id.takeIf { rootWorkspaceUi.isDiagnosticWorkspaceReady(workspace, workspaceRegistry.registered(workspace.id)) }
        }.toSet()
        val emptyCapabilityWorkspaceIds = enabledWorkspaces.mapNotNull { workspace ->
            val registered = workspaceRegistry.registered(workspace.id) ?: return@mapNotNull null
            workspace.id.takeIf { registered.descriptor.enabled && registered.backend.capabilities.isEmpty() }
        }.toSet()
        val backendProbeFailureCount = (enabledWorkspaces.map { it.id }.toSet() - readyWorkspaceIds).size
        val backendProbeState = when {
            enabledWorkspaces.isEmpty() -> DiagnosticBackendProbeState.NOT_APPLICABLE
            backendProbeFailureCount == 0 -> DiagnosticBackendProbeState.READY
            readyWorkspaceIds.isEmpty() && emptyCapabilityWorkspaceIds.isNotEmpty() ->
                DiagnosticBackendProbeState.EMPTY_CAPABILITIES
            else -> DiagnosticBackendProbeState.FAILED
        }
        val authorityMismatchWorkspaceCount = enabledWorkspaces.count { workspace ->
            rootWorkspaceUi.isAuthorityMismatched(workspace, workspace.authorityOrNull(), selected)
        }
        val safBackendIds = activeSafGrants.map { it.workspaceId }.toSet()
        val readySafIds = safBackendIds.filter { it in readyWorkspaceIds }.toSet()
        val emptySafIds = safBackendIds.intersect(emptyCapabilityWorkspaceIds)
        val safProbeState = when {
            activeSafGrants.isEmpty() -> DiagnosticBackendProbeState.NOT_APPLICABLE
            readySafIds.size == safBackendIds.size -> DiagnosticBackendProbeState.READY
            readySafIds.isEmpty() && emptySafIds.isNotEmpty() -> DiagnosticBackendProbeState.EMPTY_CAPABILITIES
            else -> DiagnosticBackendProbeState.FAILED
        }
        val safOperationCapabilityCount = activeSafGrants.sumOf { grant ->
            workspaceRegistry.registered(grant.workspaceId)?.backend?.capabilities?.size ?: 0
        }
        val effectiveAgentWorkspaceCapabilityCount = agentContext.canonicalGrants.count { grant ->
            grant.workspaceId != null && grant.skillInstallId == null && grant.capability in agentContext.effectiveCapabilities
        }
        val effectiveSkillWorkspaceCapabilityCount = frozen.canonicalGrants.count { grant ->
            grant.workspaceId != null && grant.skillInstallId != null && grant.capability in frozen.effectiveCapabilities
        }
        return RuntimeToolExposureDiagnostics(
            registeredWorkspaceCount = registeredIds.size,
            grantedWorkspaceCount = grantedIds.size,
            boundWorkspaceCount = boundIds.size,
            // A fresh Agent run may intentionally use a currently active
            // unbound persistent grant. Binding is required for historical
            // snapshots/ONCE rows, not for the current Agent workspace.
            registeredGrantedWorkspaceCount = registeredIds.intersect(grantedIds).size,
            selectedAuthority = (selected ?: Authority.NONE).toDiagnostic(),
            selectedAuthorityReady = selected == null || selected == Authority.NONE || selectedState?.isReady == true,
            safGrantActive = activeSafGrants.isNotEmpty(),
            safBackendRegistered = activeSafGrants.isNotEmpty() && activeSafGrants.all { it.workspaceId in registeredIds },
            effectiveAgentWorkspaceCapabilityCount = effectiveAgentWorkspaceCapabilityCount,
            effectiveSkillWorkspaceCapabilityCount = effectiveSkillWorkspaceCapabilityCount,
            backendReadyWorkspaceCount = readyWorkspaceIds.size,
            backendProbeFailureCount = backendProbeFailureCount,
            authorityMismatchWorkspaceCount = authorityMismatchWorkspaceCount,
            schemaFrozen = true,
            backendProbeState = backendProbeState,
            safOperationCapabilityCount = safOperationCapabilityCount,
            safProbeState = safProbeState,
        )
    }

    fun createSkillMemoryToolExecutor(context: ToolExecutionContext): SkillMemoryToolExecutor =
        runToolAssembly.createSkillMemoryToolExecutor(context)

    /**
     * Read-only Skill Memory availability projection for Skills UI. The
     * canonical repository remains the sole authority; no binding, install,
     * package, path, storage, or URI identity crosses this seam.
     */
    override fun skillMemoryAvailability(
        agentId: String,
        snapshotId: String,
        trustedSkillId: String,
    ): SkillMemoryAvailabilitySnapshot = skillMemoryRepositoryPort.availability(
        agentId = agentId,
        snapshotId = snapshotId,
        trustedSkillId = trustedSkillId,
    )

    override fun currentExecutionStamp(
        authority: runtime.mobileagent.domain.Authority,
        requireShellConsent: Boolean,
    ): runtime.mobileagent.domain.RootExecutionStamp? =
        rootCoordinator.currentExecutionStamp(authority, requireShellConsent)

    override fun admitNewRun(record: runtime.mobileagent.domain.RunRecord): runtime.mobileagent.domain.RunRecord =
        rootCoordinator.admitNewRun(record)

    /**
     * Create one immutable Agent snapshot and bind the current canonical grants to it as one
     * persistence operation.  Chat must use this seam instead of creating a snapshot and copying
     * grant rows itself: the grant/binding fields are validated by CapabilityGrantRepository and
     * the exact persisted set is checked before the snapshot is returned.
     */
    fun createSnapshotWithCurrentGrants(
        agentId: String, snapshotId: String = EntityId.random().value, at: String = Utc.nowIso(),
    ): AgentSnapshot = RootSnapshotFactory(db, rootCoordinator).withCurrentGrants(agentId, snapshotId, at)

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

    override fun acknowledgeRootRecovery(authority: Authority): runtime.mobileagent.SettingsAuthorityMutation {
        val recovered = rootCoordinator.acknowledgeDegradedRecovery(
            authority, userConfirmed = true, authorityManager = authorityManager,
            shizukuState = RootPermissionEntry.resolveState(shizukuAuthority?.refresh()),
        )
        return runtime.mobileagent.SettingsAuthorityMutation(
            accepted = recovered != null, snapshot = settingsSnapshot(),
            reason = if (recovered != null) null else "ROOT_RECOVERY_RESTART_REQUIRED",
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
            authorityLifecycle.applyShizukuState(live)
        }
        workspaceRecovery.refreshSafWorkspace()
        val liveShizuku = RootPermissionEntry.resolveState(shizukuAuthority?.state?.value)
        rootCoordinator.refreshLiveSessions(liveShizuku)
        rootCoordinator.syncAuthorityManagerStates(authorityManager, liveShizuku)
        authorityLifecycle.applyWiredState(wiredAuthority.status.value)
        authorityLifecycle.scheduleWiredReconnect()
        return settingsSnapshot().also {
            authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.REFRESH)
        }
    }

    override fun selectAuthority(authority: Authority): SettingsAuthoritySnapshot {
        require(authority in Authority.entries) { "Unsupported authority" }
        check(authorityManager.selectAuthority(authority.toElevated())) { "Authority policy changed; reload and retry" }
        rootCoordinator.syncAuthorityManagerStates(authorityManager, RootPermissionEntry.resolveState(shizukuAuthority?.state?.value))
        return settingsSnapshot().also {
            authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    override fun setUserIntent(authority: Authority, enabled: Boolean): SettingsAuthoritySnapshot {
        when (authority) {
            Authority.NONE -> require(!enabled) { "NONE cannot be enabled" }
            Authority.SHIZUKU -> check(authorityManager.setUserIntent(ElevatedAuthority.SHIZUKU, enabled))
            Authority.ROOT_DIRECT, Authority.SHIZUKU_ROOT -> rootCoordinator.setUserIntent(
                authority = authority,
                enabled = enabled,
                authorityManager = authorityManager,
                dangerousMode = dangerousModeManager.policy(),
                shizukuState = RootPermissionEntry.resolveState(shizukuAuthority?.state?.value),
            )
            Authority.WIRED_ADB -> {
                check(authorityManager.setUserIntent(ElevatedAuthority.WIRED_ADB, enabled))
                wiredAuthority.setUserIntent(enabled)
            }
        }
        if (authority == Authority.WIRED_ADB && enabled) authorityLifecycle.scheduleWiredReconnect()
        return settingsSnapshot().also {
            authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
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
        // Apply the configured marker before scheduling the fresh authenticated
        // session. A committed trust record alone does not activate authority.
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

    /**
     * Root-channel permission request. Unlike the legacy shell path it never writes the SHIZUKU
     * configured marker and never binds the legacy user service; the Root handshake alone proves
     * the UID0 server. The request is only issued from this explicit foreground action.
     */
    override fun requestRootShizukuPermission(): SettingsAuthoritySnapshot {
        RootPermissionEntry.request(
            sample = { runtime.mobileagent.privileged.RootShizukuLiveSampler.sample() },
            refresh = { shizukuAuthority?.refresh() },
        )
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
            authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    override fun openShizuku(): Boolean = runtime.mobileagent.openShizuku(appContext)

    override fun openRootManager(): Boolean = runtime.mobileagent.openRootManager(appContext)

    override fun rootShizukuPlatformReady(): Boolean =
        RootPermissionEntry.platformReady(runtime.mobileagent.privileged.RootShizukuLiveSampler.sample())


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
            authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    override fun forgetWiredAdb(): SettingsAuthoritySnapshot {
        scope.launch { revokeWiredAdb() }
        return refresh()
    }

    override suspend fun revokeWiredAdb(): SettingsAuthorityMutation {
        try {
            wiredAuthority.forget()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed shutdown retains the recovery credential. Publish the
            // canonical state and let the user retry instead of claiming revoke.
            return SettingsAuthorityMutation(false, refresh(), "RESIDENT_ADB_REVOKE_FAILED")
        }
        return try {
            check(authorityManager.setConfigured(ElevatedAuthority.WIRED_ADB, false))
            val current = refresh()
            authorityLifecycle.recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
            SettingsAuthorityMutation(accepted = true, snapshot = current)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The device shutdown succeeded; distinguish a policy save failure
            // from a failed shutdown whose credential must be retained.
            SettingsAuthorityMutation(false, refresh(), "WIRED_ADB_POLICY_SAVE_FAILED")
        }
    }

    override fun authorizeSaf(uri: Uri): SettingsAuthoritySnapshot = authorizeSaf(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )

    override fun authorizeSaf(uri: Uri, resultFlags: Int): SettingsAuthoritySnapshot {
        return when (
            val result = directoryAccess.attachSafWorkspace(
                uri,
                resultFlags,
                grant = null,
                pickerTarget = null,
                plan = planFor(null),
            )
        ) {
            is WorkspaceAccessResult.Success -> settingsSnapshot()
            is WorkspaceAccessResult.NewThreadRequired -> settingsSnapshot()
            is WorkspaceAccessResult.Failure -> error("SAF authorization failed: ${result.code.name}")
        }
    }

    override fun revokeSaf(): SettingsAuthoritySnapshot {
        // SAF is now multi-workspace. The legacy settings action remains a
        // safe "revoke all user folders" escape hatch rather than silently
        // leaving newer opaque SAF bindings active.
        safWorkspaceGrantRepository.list(includeRevoked = false)
            .map { it.workspaceId }
            .forEach { workspaceId -> directoryAccess.revokeWorkspaceInternal(workspaceId) }
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
        rootCoordinator.onDangerousModeUpdated(mode, authorityManager, shizukuAuthority?.state?.value)
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

    override fun setRootShellConsent(
        authority: Authority,
        enabled: Boolean,
        confirmed: Boolean,
    ): SettingsAuthorityMutation = runtime.mobileagent.mutateRootShellConsent(
        rootCoordinator = rootCoordinator,
        authority = authority,
        enabled = enabled,
        confirmed = confirmed,
        authorityManager = authorityManager,
        shizukuState = shizukuAuthority?.state?.value,
        snapshotProvider = ::settingsSnapshot,
    )

    private fun Long.toDiagnosticGeneration(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    // ---- Backend hydration and state -------------------------------------------------------

    private fun wireShizukuBackend() {
        val bridge = shizukuAuthority ?: return
        installBackendAssembly(Authority.SHIZUKU, AuthorityBackendAssembly.shizuku(bridge, fullDeviceFilesGrantRepository))
    }

    private fun wireWiredBackend() {
        installBackendAssembly(Authority.WIRED_ADB, AuthorityBackendAssembly.wired(wiredAuthority, fullDeviceFilesGrantRepository))
    }

    private fun installBackendAssembly(authority: Authority, assembly: AuthorityBackendAssembly) {
        shellBackends[authority] = assembly.shell
        workspaceBackends[authority] = assembly.workspace
        privilegedWorkspaceProviders[authority] = assembly.browser
    }

    /**
     * Stop process-lifetime collectors and detach platform listeners. The
     * authority bridges are injected resources owned by [AppContainer], so
     * closing this facade never closes either bridge.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        shizukuPermissionRequestPending.set(false)
        runCatching { shizukuAuthority?.removeStateListener(shizukuStateListener) }
        runCatching { shizukuAuthority?.removePermissionResultListener(shizukuPermissionListener) }
        retireRuntimeCallbacks(callbackGate, scopeJob) {
            wiredReconnectInFlight.set(false)
            if (rootCoordinatorDelegate.isInitialized()) runCatching { rootCoordinator.close() }
            privilegedWorkspaceProviders.values.forEach { provider -> runCatching { provider.close() } }
            privilegedWorkspaceProviders.clear()
        }
    }

    /** Full-device access is an explicit Agent grant, independent of the Thread's directory. */
    private fun activeFullDeviceWorkspaceIds(agentId: String): Set<String> {
        if (dangerousModeManager.policy() == DangerousMode.DISABLED) return emptySet()
        val selectedAuthority = authorityManager.state.value.selectedAuthority ?: return emptySet()
        return capabilityGrantRepository.forAgent(agentId, includeRevoked = false)
            .mapNotNull { it.workspaceId }
            .toSet()
            .filterTo(mutableSetOf()) { id ->
                val workspace = workspaceRepository.get(id)
                workspace?.enabled == true && workspace.readable &&
                    workspace.scope == WorkspaceScope.FULL_DEVICE_FILES &&
                    workspace.authorityOrNull()?.toElevated() == selectedAuthority &&
                    fullDeviceFilesGrantRepository.load(id) != null
            }
    }

    private fun freezeContext(input: ToolExecutionContext): ToolExecutionContext {
        val snapshot = agents.getSnapshot(input.snapshotId)
            ?: throw IllegalArgumentException("Agent snapshot is unavailable")
        require(snapshot.agentId == input.agentId) { "Agent snapshot does not belong to the Agent" }
        val policy = authorityPolicyRepository.getPolicy()
        val bindings = capabilityGrantRepository.listSnapshotBindings(input.snapshotId)
        val conversationWorkspaceId = conversationWorkspaceBindingRepository.get(input.sessionIdentity)?.workspaceId
        val snapshotWorkspaceIds = bindings.mapNotNull { it.workspaceId }.toSet()
        require(snapshotWorkspaceIds.size <= 1) { "Snapshot contains more than one workspace" }
        val selectedWorkspaceId = conversationWorkspaceId ?: snapshotWorkspaceIds.singleOrNull()
        val fullDeviceWorkspaceIds = activeFullDeviceWorkspaceIds(input.agentId)
        require(
            conversationWorkspaceId == null || snapshotWorkspaceIds.isEmpty() || conversationWorkspaceId in snapshotWorkspaceIds,
        ) { "Conversation and snapshot workspace bindings do not match" }
        val grants = capabilityGrantRepository.forAgent(input.agentId, includeRevoked = true)
            .filter { grant ->
                grant.workspaceId == null || grant.workspaceId == selectedWorkspaceId ||
                    grant.workspaceId in fullDeviceWorkspaceIds
            }
        val selectedBindings = bindings.filter { binding ->
            binding.workspaceId == null || binding.workspaceId == selectedWorkspaceId
        }
        // A new run materializes the current Agent/Session view, including
        // grants created after an older snapshot was taken. The resolver's
        // run-specific form still requires ONCE rows to have an immutable
        // binding; historical snapshot reads remain strict.
        val resolved = effectiveCapabilityResolver.resolveForRun(
            snapshot = snapshot,
            grants = grants,
            snapshotBindings = selectedBindings,
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
        val safGrants = safWorkspaceGrantRepository.list(includeRevoked = true)
        val activeSafGrants = safGrants.filter { it.status == SafGrantStatus.ACTIVE }
        val saf = when {
            activeSafGrants.isNotEmpty() -> SettingsSafGrantState(
                configured = true,
                readGranted = activeSafGrants.any { it.readGranted },
                writeGranted = activeSafGrants.any { it.writeGranted },
                persisted = activeSafGrants.any { grant ->
                    runCatching { grant.activePersistedUri(appContext) != null }.getOrDefault(false)
                },
                status = SafGrantStatus.ACTIVE,
            )
            safGrants.any { it.status == SafGrantStatus.GRANT_LOST } -> SettingsSafGrantState(
                configured = false,
                readGranted = false,
                writeGranted = false,
                persisted = false,
                status = SafGrantStatus.GRANT_LOST,
            )
            safGrants.any { it.status == SafGrantStatus.REVOKED } -> SettingsSafGrantState(
                configured = false,
                readGranted = false,
                writeGranted = false,
                persisted = false,
                status = SafGrantStatus.REVOKED,
            )
            else -> SettingsSafGrantState()
        }
        val policy = authorityPolicyRepository.getPolicy()
        return rootCoordinator.enrichSettingsSnapshot(
            authorityManager = authorityManager,
            selected = selected,
            appPrivateAvailable = workspaceRegistry.descriptor(INTERNAL_WORKSPACE_ID) != null,
            shizuku = shizuku,
            wired = wired,
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

    private fun Authority?.toElevated(): ElevatedAuthority? = when (this) {
        null, Authority.NONE -> null
        Authority.SHIZUKU -> ElevatedAuthority.SHIZUKU
        Authority.WIRED_ADB -> ElevatedAuthority.WIRED_ADB
        Authority.ROOT_DIRECT -> ElevatedAuthority.ROOT_DIRECT
        Authority.SHIZUKU_ROOT -> ElevatedAuthority.SHIZUKU_ROOT
    }

    private fun runtime.mobileagent.skills.tooling.AuthorityState.toSettings(authority: Authority) = SettingsAuthorityProviderState(
        authority = authority,
        userIntent = when (userIntent) {
            AuthorityUserIntent.SHIZUKU -> AuthorityUserIntent.SHIZUKU
            AuthorityUserIntent.WIRED_ADB -> AuthorityUserIntent.WIRED_ADB
            AuthorityUserIntent.ROOT_DIRECT -> AuthorityUserIntent.ROOT_DIRECT
            AuthorityUserIntent.SHIZUKU_ROOT -> AuthorityUserIntent.SHIZUKU_ROOT
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

    private fun DangerousMode.toDiagnostic(): DiagnosticDangerousModePolicy = when (this) {
        DangerousMode.DISABLED -> DiagnosticDangerousModePolicy.DISABLED
        DangerousMode.ENABLED_CONFIRM_HIGH_RISK -> DiagnosticDangerousModePolicy.CONFIRM_HIGH_RISK
        DangerousMode.ENABLED_AUTONOMOUS -> DiagnosticDangerousModePolicy.AUTONOMOUS
    }

    /** Stable, non-sensitive status for a workspace row exposed to UI callers. */
    private fun workspaceAccessStatus(workspace: Workspace): WorkspaceAccessStatus {
        if (rootWorkspaceUi.isRoot(workspace.id)) return rootWorkspaceUi.status(workspace)
        val safGrant = if (workspace.backendType == WorkspaceBackendType.SAF_TREE) {
            safWorkspaceGrantRepository.get(workspace.id)
        } else {
            null
        }
        if (safGrant?.status == SafGrantStatus.REVOKED) return WorkspaceAccessStatus.REVOKED
        if (!workspace.enabled) return WorkspaceAccessStatus.DISABLED
        if (safGrant?.status == SafGrantStatus.GRANT_LOST) return WorkspaceAccessStatus.GRANT_LOST
        if (safGrant?.status == SafGrantStatus.ACTIVE && safGrant.activePersistedUri(appContext) == null) return WorkspaceAccessStatus.GRANT_LOST
        if (workspace.backendType == WorkspaceBackendType.PRIVILEGED &&
            workspace.scope == WorkspaceScope.SELECTED_DIRECTORY
        ) {
            when (privilegedWorkspaceBindingRepository.get(workspace.id)?.status) {
                PrivilegedWorkspaceBindingStatus.REVOKED -> return WorkspaceAccessStatus.REVOKED
                PrivilegedWorkspaceBindingStatus.GRANT_LOST,
                PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE,
                PrivilegedWorkspaceBindingStatus.PERMISSION_DENIED,
                    -> return WorkspaceAccessStatus.GRANT_LOST
                PrivilegedWorkspaceBindingStatus.ACTIVE -> Unit
                null,
                PrivilegedWorkspaceBindingStatus.UNAVAILABLE,
                PrivilegedWorkspaceBindingStatus.UNAVAILABLE_AUTHORITY,
                PrivilegedWorkspaceBindingStatus.REATTACHING,
                PrivilegedWorkspaceBindingStatus.WORKSPACE_NOT_FOUND,
                    -> return WorkspaceAccessStatus.UNAVAILABLE
            }
        }
        val registered = workspaceRegistry.registered(workspace.id)
        if (registered == null) return WorkspaceAccessStatus.UNAVAILABLE
        if (workspace.backendType == WorkspaceBackendType.SAF_TREE) {
            // A live SAF probe runs a provider root query + children listing;
            // doing it here would block the caller — this path runs on the UI
            // thread via listWorkspaces.  Schedule a bounded, throttled probe
            // instead.  A dead or wedged provider marks the tree transient-
            // down: it stays bound, reports UNAVAILABLE, and the throttled
            // re-probe below restores it automatically once SAF recovers.
            scheduleSafLivenessProbe(workspace.id, registered.backend)
            if (isSafDown(workspace.id, registered.backend)) return WorkspaceAccessStatus.UNAVAILABLE
        }
        if (workspace.backendType == WorkspaceBackendType.PRIVILEGED) {
            val authority = workspace.rootReference.removePrefix("authority:")
                .let { runCatching { Authority.valueOf(it) }.getOrNull() }
                ?: return WorkspaceAccessStatus.UNAVAILABLE
            val selected = authorityManager.state.value.selectedAuthority
            val state = authority.toElevated()?.let { authorityManager.state.value.statuses[it] }
            if (selected != authority.toElevated() || state?.isReady != true) {
                return WorkspaceAccessStatus.UNAVAILABLE
            }
            if (workspace.scope == WorkspaceScope.FULL_DEVICE_FILES &&
                fullDeviceFilesGrantRepository.load(workspace.id) == null
            ) {
                return WorkspaceAccessStatus.UNAVAILABLE
            }
        }
        return WorkspaceAccessStatus.ACTIVE
    }

    private fun scheduleSafLivenessProbe(workspaceId: String, backend: WorkspaceBackend) {
        safLivenessProbe.schedule(workspaceId, backend)
    }



    private fun safeWorkspaceDisplayName(
        workspace: Workspace,
        ordinal: Int,
        requestedName: String = workspace.displayName,
    ): String = persistedWorkspaceFolderLabel(
        backendType = workspace.backendType,
        requestedName = requestedName,
        ordinal = ordinal,
        fullDevice = workspace.scope == WorkspaceScope.FULL_DEVICE_FILES,
        authority = workspace.authorityOrNull(),
    )

    private fun Workspace.authorityOrNull(): Authority? = if (backendType != WorkspaceBackendType.PRIVILEGED) {
        null
    } else {
        runtime.mobileagent.data.RootWorkspaceRepository(db).getOrigin(id)?.authority
            ?: rootReference.removePrefix("authority:")
                .let { runCatching { Authority.valueOf(it) }.getOrNull() }
                ?.takeIf { it != Authority.NONE }
    }

    private fun recordConversationWorkspaceBinding(
        binding: ConversationWorkspaceBinding,
        agentId: String,
        event: ConversationWorkspaceEvent,
        previousWorkspaceId: String? = null,
    ) {
        if (agentId.isBlank()) return
        val workspace = workspaceRepository.get(binding.workspaceId) ?: return
        val snapshotVersion = db.query(
            "SELECT s.schema_version FROM conversations c JOIN agent_snapshots s ON s.id = c.agent_snapshot_id WHERE c.id = ?",
            listOf(binding.sessionId),
        ).singleOrNull()?.long("schema_version")?.toDiagnosticGeneration() ?: 0
        val grantGeneration = capabilityGrantRepository.forWorkspace(binding.workspaceId, includeRevoked = false)
            .filter { it.agentId == agentId }
            .maxOfOrNull { it.revision }
            ?.toDiagnosticGeneration()
            ?: 0
        diagnostics.recordConversationWorkspace(
            ConversationWorkspaceRecord(
                event = event,
                sessionId = binding.sessionId,
                agentId = agentId,
                workspaceId = binding.workspaceId,
                authority = workspace.authorityOrNull()?.toDiagnostic() ?: DiagnosticAuthority.NONE,
                bindingRevision = binding.revision.toDiagnosticGeneration(),
                grantGeneration = grantGeneration,
                snapshotVersion = snapshotVersion,
                previousWorkspaceId = previousWorkspaceId,
            ),
        )
    }

    private fun defaultGrantGeneration(agentId: String, workspaceId: String?): Int = workspaceId?.let { id ->
        capabilityGrantRepository.forAgent(agentId, includeRevoked = false)
            .filter { it.workspaceId == id }
            .maxOfOrNull { it.revision }
            ?.toDiagnosticGeneration()
    } ?: 0

    private fun recordAgentWorkspaceDefaultChanged(
        previousWorkspaceId: String?,
        persisted: AgentWorkspaceDefault,
        result: DiagnosticWorkspaceDefaultResult,
    ) {
        diagnostics.recordAgentWorkspaceDefaultChanged(
            AgentWorkspaceDefaultChangedRecord(
                agentId = persisted.agentId,
                previousWorkspaceId = previousWorkspaceId,
                workspaceId = persisted.workspaceId,
                grantGeneration = defaultGrantGeneration(persisted.agentId, persisted.workspaceId),
                result = result,
            ),
        )
    }

    private fun workspaceAccessItem(
        workspace: Workspace,
        ordinal: Int = 1,
        agentId: String? = null,
    ): WorkspaceAccessItem {
        val status = workspaceAccessStatus(workspace)
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val rawGrants = agentId?.let { capabilityGrantRepository.forAgent(it, includeRevoked = false) }
            .orEmpty()
            .filter { grant ->
                grant.workspaceId == workspace.id &&
                    grant.isActiveFor(now, null, null)
            }
        val grants = rootWorkspaceUi.filterDurableWorkspaceGrants(workspace, rawGrants)
        val fullDeviceConfirmationPresent = workspace.scope != WorkspaceScope.FULL_DEVICE_FILES ||
            fullDeviceFilesGrantRepository.load(workspace.id) != null
        return committedWorkspaceAccessItem(
            workspace = workspace,
            displayName = safeWorkspaceDisplayName(workspace, ordinal),
            status = status,
            authority = workspace.authorityOrNull(),
            activeGrants = grants,
            fullDeviceConfirmationPresent = fullDeviceConfirmationPresent,
        )
    }

    private fun listWorkspaceAccessItems(
        agentId: String?,
    ): List<WorkspaceAccessItem> = workspaceRepository.list().mapIndexed { index, workspace ->
        workspaceAccessItem(
            workspace = workspace,
            ordinal = index + 1,
            agentId = agentId,
        )
    }

    /** Save one canonical Agent grant without mutating grants for other workspaces. */
    private fun saveAgentGrantWithCurrentWorkspaceInvariant(grant: CapabilityGrant): CapabilityGrant {
        rootWorkspaceUi.requireOrdinaryGrantTarget(grant.workspaceId)
        grant.workspaceId?.let { workspaceId ->
            require(workspaceRepository.get(workspaceId)?.enabled == true) { "Workspace is unavailable" }
        }
        return capabilityGrantRepository.save(grant)
    }

    private fun authorityProviderFor(authority: Authority): PrivilegedWorkspaceProvider? {
        if (authority == Authority.NONE) return null
        val elevated = authority.toElevated() ?: return null
        val state = authorityManager.state.value
        if (state.selectedAuthority != elevated || state.statuses[elevated]?.isReady != true) return null
        return privilegedWorkspaceProviders[authority]
    }

    private fun privilegedFailure(authority: Authority): ToolErrorCode {
        if (authority == Authority.NONE || authorityManager.state.value.selectedAuthority != authority.toElevated()) {
            return ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED
        }
        return ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
    }

    private inner class RuntimeWorkspaceAccessPort : WorkspaceAccessPort {
        override fun listWorkspaces(agentId: String?): List<WorkspaceAccessItem> =
            listWorkspaceAccessItems(agentId)

        override fun attachSaf(uri: Uri, resultFlags: Int, grant: WorkspaceAccessGrantTarget?): WorkspaceAccessResult =
            directoryAccess.attachSafWorkspace(uri, resultFlags, grant, pickerTarget = null, plan = planFor(null))

        override fun grantWorkspace(workspaceId: String, grant: WorkspaceAccessGrantTarget): WorkspaceAccessResult =
            directoryAccess.grantWorkspaceInternal(workspaceId, grant)

        override fun renewExistingWorkspaceGrants(agentId: String, workspaceId: String): WorkspaceAccessResult =
            directoryAccess.renewExistingWorkspaceGrantsInternal(agentId, workspaceId)

        override fun revokeGrant(grantId: String, expectedRevision: Long): WorkspaceAccessResult =
            directoryAccess.revokeGrantInternal(grantId, expectedRevision)

        override fun revokeWorkspace(workspaceId: String): WorkspaceAccessResult =
            directoryAccess.revokeWorkspaceInternal(workspaceId)

        override fun fullDeviceFilesGrantRevision(workspaceId: String): Long? =
            fullDeviceFilesGrantRepository.currentRevision(workspaceId)

        override suspend fun browsePrivilegedRoot(authority: Authority, maxEntries: Int): WorkspaceResult<WorkspaceDirectoryPage> {
            val provider = authorityProviderFor(authority)
                ?: return workspaceFailure(privilegedFailure(authority))
            return runCatching { provider.directoryBrowser.root(maxEntries) }
                .getOrElse { workspaceFailure(ToolErrorCode.UNKNOWN_OUTCOME) }
        }

        override suspend fun browsePrivileged(
            authority: Authority,
            request: WorkspaceBrowseRequest,
        ): WorkspaceResult<WorkspaceDirectoryPage> {
            val provider = authorityProviderFor(authority)
                ?: return workspaceFailure(privilegedFailure(authority))
            return runCatching { provider.directoryBrowser.browse(request) }
                .getOrElse { workspaceFailure(ToolErrorCode.UNKNOWN_OUTCOME) }
        }

        override suspend fun attachPrivilegedDirectory(
            authority: Authority,
            request: WorkspaceAttachRequest,
            grant: WorkspaceAccessGrantTarget?,
        ): WorkspaceAccessResult = directoryAccess.attachPrivilegedDirectoryInternal(
            authority,
            request,
            grant,
            pickerTarget = null,
            plan = planFor(null),
        )

        override suspend fun attachPrivilegedPath(
            authority: Authority,
            workspaceId: String,
            displayName: String,
            absolutePath: String,
            grant: WorkspaceAccessGrantTarget?,
        ): WorkspaceAccessResult = directoryAccess.attachPrivilegedPathInternal(
            authority,
            workspaceId,
            displayName,
            absolutePath,
            grant,
            pickerTarget = null,
            plan = planFor(null),
        )

        override suspend fun openFullDeviceFiles(
            authority: Authority,
            request: FullDeviceFilesRequest,
            grant: WorkspaceAccessGrantTarget?,
        ): WorkspaceAccessResult = directoryAccess.openFullDeviceFilesInternal(authority, request, grant)

        override suspend fun revokeFullDeviceFiles(
            authority: Authority,
            workspaceId: String,
            expectedRevision: Long,
        ): WorkspaceAccessResult {
            val workspace = workspaceRepository.get(workspaceId)
                ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
            if (workspace.scope != WorkspaceScope.FULL_DEVICE_FILES ||
                workspace.rootReference != "authority:${authority.name}"
            ) return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
            val persisted = fullDeviceFilesGrantRepository.load(workspaceId)
                ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
            if (persisted.revision != expectedRevision) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
            }
            // Revocation is a durable local policy operation. It must remain
            // available while Wi-Fi, the desktop companion, or Shizuku is
            // temporarily disconnected; transport handles are merely stale
            // resources once the registry entry and grants are removed.
            return directoryAccess.revokeWorkspaceInternal(workspaceId).also { result ->
                if (result is WorkspaceAccessResult.Failure) workspaceRegistry.unregister(workspaceId)
            }
        }
    }

    private inner class ContainerAgentGrantPort : AgentGrantPort {
        override val available: Boolean = true
        override val unavailableMessage: String = "授权存储未就绪；请稍后重试。"

        override fun listWorkspaces(): List<Workspace> = workspaceRepository.list().map { workspace ->
            // The UI contract uses the domain type for labels, but receives a
            // non-path opaque marker instead of the persisted root/URI.
            workspace.copy(rootReference = "workspace:${workspace.id}")
        }

        override fun isRootWorkspace(workspaceId: String): Boolean = rootWorkspaceUi.isRoot(workspaceId)

        override fun listGrants(agentId: String, includeRevoked: Boolean): List<CapabilityGrant> =
            capabilityGrantRepository.forAgent(agentId, includeRevoked)

        override fun saveGrant(grant: CapabilityGrant): CapabilityGrant =
            saveAgentGrantWithCurrentWorkspaceInvariant(grant)

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
            rootWorkspaceUi.requireOrdinarySnapshotBinding(binding.workspaceId, grant.workspaceId, grant.grantId)
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
                modelProfileIds = grant.modelProfileIds,
                maxModelCalls = grant.maxModelCalls,
                maxModelTokens = grant.maxModelTokens,
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
        private const val SAF_LIVENESS_PROBE_INTERVAL_MS = 2_000L
        private const val SAF_LIVENESS_PROBE_DEADLINE_MS = 800L
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

/**
 * Canonical automatic grant plan for a workspace picker/default. New attachments
 * start read-only even when the backend can write; the Agent editor has a
 * separate explicit READ_WRITE preset. Shell and mutations stay excluded.
 */
internal fun canonicalDefaultWorkspaceGrantCapabilities(
    backendCapabilities: Set<CapabilityId>,
): Set<CapabilityId> = backendCapabilities intersect READ_ONLY_WORKSPACE_CAPABILITIES

/** Full-device access has its own explicit high-risk confirmation and grant plan. */
internal fun canonicalFullDeviceWorkspaceGrantCapabilities(
    backendCapabilities: Set<CapabilityId>,
): Set<CapabilityId> = backendCapabilities intersect CANONICAL_FULL_DEVICE_WORKSPACE_CAPABILITIES

private val CANONICAL_FULL_DEVICE_WORKSPACE_CAPABILITIES = READ_ONLY_WORKSPACE_CAPABILITIES + setOf(
    CapabilityId(CapabilityId.FILE_WRITE_TEXT),
    CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
    CapabilityId(CapabilityId.FILE_DELETE),
    CapabilityId("file.apply_patch"),
)
