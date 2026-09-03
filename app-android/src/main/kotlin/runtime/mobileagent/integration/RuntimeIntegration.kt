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
import java.time.Instant
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
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
import runtime.mobileagent.diagnostics.DiagnosticBackendProbeState
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
import runtime.mobileagent.diagnostics.ConversationWorkspaceEvent
import runtime.mobileagent.diagnostics.ConversationWorkspaceRecord
import runtime.mobileagent.diagnostics.DiagnosticWorkspaceReattachPhase
import runtime.mobileagent.diagnostics.PrivilegedWorkspaceBindingPersistedRecord
import runtime.mobileagent.diagnostics.PrivilegedWorkspaceReattachRecord
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AgentWorkspaceDefault
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityPolicy
import runtime.mobileagent.domain.AuthorityPreferences
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.DesktopTrustStatus
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.domain.SafWorkspaceGrant
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.domain.ToolAuditDetail
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
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
import runtime.mobileagent.workspace.privilegedUiTitle
import runtime.mobileagent.workspace.safTreeUiTitle
import runtime.mobileagent.workspace.workspaceUiKind
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
import runtime.mobileagent.shizuku.ShizukuPrivilegedWorkspaceFactory
import runtime.mobileagent.security.AndroidAppInstanceIdStoreFactory
import runtime.mobileagent.security.AndroidKeystorePrivilegedWorkspaceBindingCipherFactory
import runtime.mobileagent.security.PrivilegedWorkspaceBindingAad
import runtime.mobileagent.security.PrivilegedWorkspaceBindingOpenResult
import runtime.mobileagent.security.PrivilegedWorkspaceBindingSealResult
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
import runtime.mobileagent.tooling.WorkspacePathPolicy
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.wired.WiredAdbPrivilegedWorkspaceFactory
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
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrant
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceReattachRequest
import runtime.mobileagent.skills.tooling.WorkspaceRecoveryLocator
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
) : SettingsAuthorityPort, ThreadWorkspacePort, ThreadWorkspaceRuntimePort, WorkspacePickerPort,
    CanonicalWorkspaceSink, AutoCloseable {
    private val appContext = context.applicationContext
    private val workspaceUiPresentations = WorkspaceUiPresentationStore(appContext)
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
    private val workspaceRegistry = WorkspaceRegistry()
    private val bindingCipher = AndroidKeystorePrivilegedWorkspaceBindingCipherFactory.create()
    private val appInstanceId = AndroidAppInstanceIdStoreFactory.create(appContext).loadOrCreateAppInstanceId()
    private val reattachInFlight = ConcurrentHashMap.newKeySet<String>()
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
    private val privilegedWorkspaceProviders = linkedMapOf<Authority, PrivilegedWorkspaceProvider>()
    private val agentGrantPort = ContainerAgentGrantPort()
    private val workspaceAccessAdapter = RuntimeWorkspaceAccessPort()

    /** One canonical facade for SAF, selected-authority browsing, and workspace grants. */
    val workspaceAccessPort: WorkspaceAccessPort
        get() = workspaceAccessAdapter

    private var previousSelection: Authority? = null
    private val shizukuPermissionRequestPending = AtomicBoolean(false)
    private val shizukuStateListener: (ShizukuAuthorityState) -> Unit = { state ->
        applyShizukuState(state)
        recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.PLATFORM_STATE_CHANGE)
    }
    private val shizukuPermissionListener: (ShizukuPermissionResult) -> Unit = { result ->
        if (shizukuPermissionRequestPending.compareAndSet(true, false)) {
            // Only an explicit foreground grant may persist configuration as
            // enabled. A later live Binder denial may clear it, while Binder
            // loss, reconnect and process restart preserve the last grant.
            authorityManager.setConfigured(ElevatedAuthority.SHIZUKU, result.granted)
            recordAuthorityConfigurationSnapshot(DiagnosticAuthorityConfigurationReason.USER_ACTION)
        }
    }

    init {
        wireShizukuBackend()
        wireWiredBackend()
        reconcileFullDeviceGrants()
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

    override fun resolveNewThreadWorkspace(agentId: String): String? =
        agentWorkspaceDefaultRepository.resolveForNewThread(agentId)

    override fun saveAgentWorkspaceDefault(default: AgentWorkspaceDefault): AgentWorkspaceDefault =
        agentWorkspaceDefaultRepository.save(default)

    override fun createSnapshotWithWorkspace(
        agentId: String,
        workspaceId: String?,
        snapshotId: String,
        at: String,
    ): AgentSnapshot = db.transaction {
        val workspace = workspaceId?.let { id ->
            workspaceRepository.get(id)?.takeIf { it.enabled && it.readable }
                ?: error("Workspace is unavailable")
        }
        val snapshot = agents.createSnapshot(agentId, snapshotId, at)
        if (workspace != null) {
            val now = Instant.ofEpochMilli(System.currentTimeMillis())
            val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
            capabilityGrantRepository.forAgent(agentId, includeRevoked = false)
                .filter { grant ->
                    grant.workspaceId == workspace.id &&
                        grant.policyVersion == policyVersion &&
                        grant.isActiveFor(now, null, null)
                }
                .forEach { grant ->
                    capabilityGrantRepository.bindSnapshot(
                        SnapshotGrantBinding(
                            snapshotId = snapshot.id,
                            grantId = grant.grantId,
                            capability = grant.capability,
                            workspaceId = grant.workspaceId,
                            pathScope = grant.pathScope,
                            policyVersion = grant.policyVersion,
                            boundAt = at,
                        ),
                    )
                }
        }
        snapshot
    }

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
                authoritySelection = authorityManager.selection.value,
            ),
        )
    }

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
            workspace.backendType != WorkspaceBackendType.PRIVILEGED ||
                privilegedWorkspaceBindingRepository.get(workspace.id) != null
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

    override suspend fun attachPrivilegedDirectory(
        authority: Authority,
        request: WorkspaceAttachRequest,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult {
        val plan = planFor(target)
        return attachPrivilegedDirectoryInternal(
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
        return attachSafWorkspace(
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
    ): WorkspaceAccessResult = useRecentWorkspace(
        workspaceId = workspaceId,
        pickerTarget = target,
        plan = planFor(target),
    )

    internal suspend fun useRecentWorkspace(
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
            reattachPrivilegedWorkspace(workspaceId)
        }
        val existingBinding = pickerTarget?.threadId
            ?.takeIf { plan.bindThread }
            ?.let(conversationWorkspaceBindingRepository::get)
        val switchingBoundThread = existingBinding != null && existingBinding.workspaceId != workspaceId
        if (switchingBoundThread) {
            val agentId = pickerTarget?.agentId
            val existingGrants = if (agentId != null) {
                val now = Instant.ofEpochMilli(System.currentTimeMillis())
                val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
                capabilityGrantRepository.forAgent(agentId, includeRevoked = false)
                    .filter { it.workspaceId == workspace.id && it.policyVersion == policyVersion && it.isActiveFor(now, null, null) }
            } else {
                emptyList()
            }
            return newThreadRequiredResult(
                pickerTarget = pickerTarget,
                existingBinding = existingBinding!!,
                workspace = workspace,
                grants = existingGrants,
                authorizationState = if (existingGrants.isNotEmpty()) {
                    NewThreadAuthorizationState.ALREADY_GRANTED
                } else {
                    NewThreadAuthorizationState.REQUIRES_CONFIRMATION_COMMIT
                },
            )
        }
        val registered = workspaceRegistry.registered(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        val grant = grantTargetFor(plan, pickerTarget.toWorkspaceTarget())
        var pickerCommit = PickerBindingCommit()
        val grants = try {
            db.transaction {
                val committed = grant?.let { persistWorkspaceGrantBundle(workspace, registered.backend, it) }.orEmpty()
                pickerCommit = persistPickerTarget(
                    workspace = workspace,
                    target = pickerTarget,
                    grants = committed,
                    setAsAgentDefault = plan.persistDefaultNow(),
                    bindThread = plan.bindThread,
                )
                committed
            }
        } catch (failure: WorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        return finishPickerCommit(
            workspace = workspace,
            displayName = safeWorkspaceDisplayName(workspace, 1),
            grants = grants,
            pickerTarget = pickerTarget,
            pickerCommit = pickerCommit,
            authority = workspace.authorityOrNull(),
        )
    }

    override suspend fun confirmNewThreadWorkspace(
        agentId: String,
        currentThreadId: String,
        currentWorkspaceId: String,
        requestedWorkspaceId: String,
    ): WorkspaceAccessResult {
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
                    reattachPrivilegedWorkspace(workspace.id)
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
                        adoptInternalWorkspace()
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
                persistWorkspaceGrantBundle(
                    workspace = workspace,
                    backend = registered.backend,
                    target = WorkspaceAccessGrantTarget(agentId = agentId),
                )
            }
        } catch (failure: WorkspaceAccessException) {
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
    ): WorkspaceAccessResult = attachPrivilegedDirectoryInternal(
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
    ): WorkspaceAccessResult = attachSafWorkspace(
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
    ): WorkspaceAccessResult = attachPrivilegedPathInternal(
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
        return openFullDeviceFilesInternal(
            authority = authority,
            request = request,
            grant = grantTargetFor(plan, target),
        )
    }

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
        val workspace = workspaceRepository.get(draft.workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (!workspace.enabled || workspace.scope != WorkspaceScope.SELECTED_DIRECTORY) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        if (workspace.backendType == WorkspaceBackendType.PRIVILEGED &&
            workspaceRegistry.registered(workspace.id) == null
        ) {
            reattachPrivilegedWorkspace(workspace.id)
        }
        val registered = workspaceRegistry.registered(workspace.id)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        val grants = try {
            db.transaction {
                val committed = persistWorkspaceGrantBundle(
                    workspace,
                    registered.backend,
                    WorkspaceAccessGrantTarget(agentId = agentId),
                )
                persistPickerTarget(
                    workspace = workspace,
                    target = WorkspacePickerTarget(agentId = agentId),
                    grants = committed,
                    setAsAgentDefault = draft.setAsAgentDefault,
                    bindThread = false,
                )
                committed
            }
        } catch (failure: WorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        return WorkspaceAccessResult.Success(
            committedWorkspaceAccessItem(
                workspace = workspace,
                displayName = safeWorkspaceDisplayName(workspace, 1),
                status = WorkspaceAccessStatus.ACTIVE,
                authority = workspace.authorityOrNull(),
                activeGrants = grants,
                fullDeviceConfirmationPresent = true,
            ),
            grants.map { it.toWorkspaceAccessSummary() },
        )
    }

    /** Agent default is persisted only when the Agent already exists. */
    private fun WorkspaceIntentPlan.persistDefaultNow(): Boolean = setAgentDefault && !deferred

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
    fun workspaceUiPresentation(workspaceId: String, chinese: Boolean = true): WorkspaceUiPresentation? {
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
        val safGrants = safWorkspaceGrantRepository.list(includeRevoked = true)
        val activeSafGrants = safGrants.filter { it.status == SafGrantStatus.ACTIVE }
        val enabledWorkspaces = workspaceRepository.list(enabledOnly = true)
        val readyWorkspaceIds = enabledWorkspaces.mapNotNull { workspace ->
            val registered = workspaceRegistry.registered(workspace.id) ?: return@mapNotNull null
            val descriptor = registered.descriptor
            val usable = descriptor.enabled &&
                (descriptor.readable || descriptor.writable) &&
                registered.backend.capabilities.isNotEmpty()
            workspace.id.takeIf { usable }
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
            if (workspace.backendType != WorkspaceBackendType.PRIVILEGED) return@count false
            val authority = workspace.rootReference.removePrefix("authority:")
                .let { runCatching { Authority.valueOf(it) }.getOrNull() }
            authority == null || authority.toElevated() != selected
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
        val effectiveAgentWorkspaceCapabilityCount = frozen.canonicalGrants.count { grant ->
            grant.workspaceId != null && grant.skillInstallId == null && grant.capability in frozen.effectiveCapabilities
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
        return when (
            val result = attachSafWorkspace(
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
            .forEach { workspaceId -> revokeWorkspaceInternal(workspaceId) }
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
        val workspace = ShizukuBackendFactory.createWorkspaceBackend(bridge)
        workspaceBackends[ElevatedAuthority.SHIZUKU] = workspace
        privilegedWorkspaceProviders[Authority.SHIZUKU] = ShizukuPrivilegedWorkspaceFactory.createDeviceRoot(
            bridge = bridge,
            workspaceId = workspace.descriptor.id,
            displayName = workspace.descriptor.displayName,
            fullDeviceGrantStore = fullDeviceFilesGrantRepository,
        )
    }

    private fun wireWiredBackend() {
        shellBackends[ElevatedAuthority.WIRED_ADB] = WiredShellExecutor(wiredAuthority)
        val workspace = WiredWorkspaceBackend(wiredAuthority)
        workspaceBackends[ElevatedAuthority.WIRED_ADB] = workspace
        privilegedWorkspaceProviders[Authority.WIRED_ADB] = WiredAdbPrivilegedWorkspaceFactory.create(
            authority = wiredAuthority,
            workspaceId = workspace.descriptor.id,
            displayName = workspace.descriptor.displayName,
            fullDeviceGrantStore = fullDeviceFilesGrantRepository,
        )
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
        // Authority root backends are navigation/attach providers, not Agent
        // workspaces.  Only a user-attached directory may enter the runtime
        // registry and become model-facing.
        privilegedWorkspaceBindingRepository.list()
            .filter { it.status != PrivilegedWorkspaceBindingStatus.REVOKED }
            .forEach { binding -> workspaceRegistry.unregister(binding.workspaceId) }
        schedulePrivilegedWorkspaceReattach()
    }

    private fun schedulePrivilegedWorkspaceReattach() {
        if (closed.get()) return
        val manager = authorityManager.state.value
        val selected = manager.selectedAuthority ?: return
        if (manager.statuses[selected]?.isReady != true) return
        privilegedWorkspaceBindingRepository.forAuthority(selected)
            .asSequence()
            .filter { binding ->
                binding.scope == WorkspaceScope.SELECTED_DIRECTORY &&
                    binding.status !in setOf(
                        PrivilegedWorkspaceBindingStatus.REVOKED,
                        PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE,
                        PrivilegedWorkspaceBindingStatus.GRANT_LOST,
                    ) &&
                    workspaceRegistry.registered(binding.workspaceId) == null &&
                    workspaceRepository.get(binding.workspaceId)?.enabled == true
            }
            .forEach { binding ->
                if (!reattachInFlight.add(binding.workspaceId)) return@forEach
                scope.launch {
                    try {
                        reattachPrivilegedWorkspace(binding.workspaceId)
                    } finally {
                        reattachInFlight.remove(binding.workspaceId)
                    }
                }
            }
    }

    private suspend fun reattachPrivilegedWorkspace(workspaceId: String) {
        val startedAt = System.currentTimeMillis()
        var binding = privilegedWorkspaceBindingRepository.get(workspaceId) ?: return
        val workspace = workspaceRepository.get(workspaceId) ?: return
        val authority = binding.authority
        val provider = authorityProviderFor(authority)
        if (provider == null) {
            markPrivilegedBindingStatus(binding, PrivilegedWorkspaceBindingStatus.UNAVAILABLE_AUTHORITY)
            return
        }
        binding = runCatching {
            privilegedWorkspaceBindingRepository.updateStatus(
                workspaceId,
                binding.revision,
                PrivilegedWorkspaceBindingStatus.REATTACHING,
            )
        }.getOrNull() ?: return
        diagnostics.recordPrivilegedWorkspaceReattachStarted(
            reattachDiagnostic(binding, DiagnosticWorkspaceReattachPhase.STARTED),
        )
        if (binding.aadAppInstanceId != appInstanceId || binding.aadWorkspaceId != workspaceId) {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "BINDING_IDENTITY_MISMATCH", startedAt)
            return
        }
        val aad = runCatching {
            PrivilegedWorkspaceBindingAad(appInstanceId, workspaceId, authority, binding.locatorVersion)
        }.getOrNull()
        if (aad == null) {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "BINDING_AAD_INVALID", startedAt)
            return
        }
        val opened = bindingCipher.open(binding.encryptedLocatorCopy(), binding.locatorNonceCopy(), aad)
        val recovered = when (opened) {
            is PrivilegedWorkspaceBindingOpenResult.Failure -> {
                failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, opened.error.code.name, startedAt)
                return
            }
            is PrivilegedWorkspaceBindingOpenResult.Success -> opened.locator
        }
        val locator = try {
            WorkspaceRecoveryLocator.fromBytes(recovered)
        } catch (_: RuntimeException) {
            recovered.fill(0)
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "LOCATOR_INVALID", startedAt)
            return
        } finally {
            recovered.fill(0)
        }
        val reopened = try {
            provider.reopenDirectory(
                WorkspaceReattachRequest(
                    workspaceId = workspaceId,
                    displayName = workspace.displayName,
                    recoveryLocator = locator,
                    scope = binding.scope,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            WorkspaceResult.Failure(ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
        } finally {
            locator.clear()
        }
        val attachment = when (reopened) {
            is WorkspaceResult.Failure -> {
                val status = when (reopened.error.code) {
                    ToolErrorCode.WORKSPACE_NOT_FOUND -> PrivilegedWorkspaceBindingStatus.WORKSPACE_NOT_FOUND
                    ToolErrorCode.AUTHORITY_NOT_GRANTED,
                    ToolErrorCode.SHIZUKU_PERMISSION_DENIED,
                    ToolErrorCode.CAPABILITY_DENIED,
                        -> PrivilegedWorkspaceBindingStatus.PERMISSION_DENIED
                    ToolErrorCode.CONFLICT,
                    ToolErrorCode.INVALID_REQUEST,
                    ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH,
                        -> PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE
                    else -> PrivilegedWorkspaceBindingStatus.UNAVAILABLE
                }
                failPrivilegedReattach(binding, status, reopened.error.code.name, startedAt)
                return
            }
            is WorkspaceResult.Success -> reopened.value
        }
        if (attachment.descriptor.id != workspaceId) {
            attachment.recoveryLocator?.clear()
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "WORKSPACE_ID_MISMATCH", startedAt)
            return
        }
        val freshLocator = attachment.recoveryLocator
        if (freshLocator == null) {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "LOCATOR_MISSING", startedAt)
            return
        }
        val freshBytes = runCatching { freshLocator.copyBytes() }.getOrNull()
        if (freshBytes == null) {
            freshLocator.clear()
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "LOCATOR_INVALID", startedAt)
            return
        }
        val envelope = try {
            when (val sealed = bindingCipher.seal(freshBytes, aad)) {
                is PrivilegedWorkspaceBindingSealResult.Success -> sealed.envelope
                is PrivilegedWorkspaceBindingSealResult.Failure -> {
                    failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, sealed.error.code.name, startedAt)
                    return
                }
            }
        } finally {
            freshBytes.fill(0)
            freshLocator.clear()
        }
        val active = try {
            privilegedWorkspaceBindingRepository.save(
                binding.copy(
                    encryptedLocator = envelope.encryptedLocator,
                    locatorNonce = envelope.locatorNonce,
                    status = PrivilegedWorkspaceBindingStatus.ACTIVE,
                    revision = binding.revision + 1L,
                    updatedAt = Utc.nowIso(),
                ),
            )
        } catch (_: RuntimeException) {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.UNAVAILABLE, "PERSISTENCE_FAILED", startedAt)
            return
        }
        try {
            workspaceRegistry.registerOrReplace(workspace, attachment.backend)
        } catch (_: RuntimeException) {
            failPrivilegedReattach(active, PrivilegedWorkspaceBindingStatus.UNAVAILABLE, "REGISTRY_FAILED", startedAt)
            return
        }
        diagnostics.recordPrivilegedWorkspaceReattachSucceeded(
            reattachDiagnostic(
                active,
                DiagnosticWorkspaceReattachPhase.COMPLETED,
                durationMs = System.currentTimeMillis() - startedAt,
            ),
        )
    }

    private fun markPrivilegedBindingStatus(
        binding: PrivilegedWorkspaceBinding,
        status: PrivilegedWorkspaceBindingStatus,
    ): PrivilegedWorkspaceBinding? = if (binding.status == status) {
        binding
    } else {
        runCatching {
            privilegedWorkspaceBindingRepository.updateStatus(binding.workspaceId, binding.revision, status)
        }.getOrNull()
    }

    private fun failPrivilegedReattach(
        binding: PrivilegedWorkspaceBinding,
        status: PrivilegedWorkspaceBindingStatus,
        errorCode: String,
        startedAt: Long,
    ) {
        workspaceRegistry.unregister(binding.workspaceId)
        val persisted = markPrivilegedBindingStatus(binding, status) ?: binding
        diagnostics.recordPrivilegedWorkspaceReattachFailed(
            reattachDiagnostic(
                persisted,
                DiagnosticWorkspaceReattachPhase.FAILED,
                durationMs = System.currentTimeMillis() - startedAt,
                errorCode = errorCode,
            ),
        )
    }

    private fun reattachDiagnostic(
        binding: PrivilegedWorkspaceBinding,
        phase: DiagnosticWorkspaceReattachPhase,
        durationMs: Long? = null,
        errorCode: String = "none",
    ): PrivilegedWorkspaceReattachRecord = PrivilegedWorkspaceReattachRecord(
        phase = phase,
        workspaceId = binding.workspaceId,
        authority = binding.authority.toDiagnostic(),
        bindingRevision = binding.revision.toDiagnosticGeneration(),
        grantGeneration = capabilityGrantRepository.forWorkspace(binding.workspaceId, includeRevoked = false)
            .maxOfOrNull { it.revision }
            ?.toDiagnosticGeneration()
            ?: 0,
        durationMs = durationMs,
        errorCode = errorCode.toDiagnosticErrorCode(),
    )

    private fun Long.toDiagnosticGeneration(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun String.toDiagnosticErrorCode(): String {
        val normalized = uppercase(Locale.ROOT)
            .map { character -> if (character.isLetterOrDigit() || character == '_') character else '_' }
            .joinToString("")
            .trim('_')
            .take(64)
        return normalized.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) } ?: "UNKNOWN"
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
        safWorkspaceGrantRepository.list(includeRevoked = true).forEach { grant ->
            val workspaceId = grant.workspaceId
            val uri = runCatching { Uri.parse(grant.uriReference) }.getOrNull()
            if (grant.status == SafGrantStatus.ACTIVE && uri != null && hasPersistedSafGrant(uri, grant)) {
                val workspace = workspaceRepository.get(workspaceId)
                if (workspace != null && workspace.enabled) {
                    runCatching {
                        workspaceRegistry.registerOrReplace(
                            workspace,
                            runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(appContext, uri, workspaceId),
                        )
                    }.onFailure { workspaceRegistry.unregister(workspaceId) }
                } else if (workspace == null) {
                    // An active SAF row without its canonical workspace is an
                    // incomplete old transaction. Retain the row for user
                    // recovery, but do not leave it looking active to the
                    // runtime or to diagnostics.
                    safWorkspaceGrantRepository.markLost(workspaceId)
                    workspaceRegistry.unregister(workspaceId)
                }
            } else if (grant.status == SafGrantStatus.ACTIVE) {
                safWorkspaceGrantRepository.markLost(workspaceId)
                workspaceRegistry.unregister(workspaceId)
            } else if (grant.status == SafGrantStatus.REVOKED) {
                workspaceRegistry.unregister(workspaceId)
            }
        }
    }

    private fun reconcileFullDeviceGrants() {
        fullDeviceFilesGrantRepository.activeWorkspaceIds().forEach { workspaceId ->
            val workspace = workspaceRepository.get(workspaceId)
            if (workspace == null || !workspace.enabled || workspace.scope != WorkspaceScope.FULL_DEVICE_FILES) {
                fullDeviceFilesGrantRepository.load(workspaceId)?.let { grant ->
                    fullDeviceFilesGrantRepository.revoke(workspaceId, grant.revision)
                }
            }
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

    /**
     * Register another app-private workspace beside the canonical internal
     * root. Production UI does not create a second INTERNAL backend; tests use
     * this to prove a bound Thread cannot be rewritten onto a different
     * registered workspace.
     */
    internal fun registerAppPrivateWorkspace(workspaceId: String, root: Path): Workspace {
        require(workspaceId != INTERNAL_WORKSPACE_ID) {
            "The canonical app-private workspace is adopted by the host"
        }
        runCatching { Files.createDirectories(root) }.getOrElse {
            error("App-private workspace root is unavailable")
        }
        val existing = workspaceRepository.get(workspaceId)
        val workspace = Workspace(
            id = workspaceId,
            displayName = existing?.displayName?.takeIf { it.isNotBlank() } ?: "Application workspace",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = root.toAbsolutePath().normalize().toString(),
            readable = true,
            writable = true,
            quotaBytes = existing?.quotaBytes ?: (4L * 1024L * 1024L),
            maxFileBytes = existing?.maxFileBytes ?: (256L * 1024L),
            enabled = true,
            revision = existing?.revision ?: 0,
            createdAt = existing?.createdAt.orEmpty(),
            updatedAt = existing?.updatedAt.orEmpty(),
            scope = WorkspaceScope.SELECTED_DIRECTORY,
        )
        val persisted = workspaceRepository.save(workspace)
        workspaceRegistry.registerOrReplace(
            persisted,
            runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createInternal(root, persisted.id),
        )
        return persisted
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
        val definitiveGrant = when {
            state.permissionGranted -> PlatformGrant.GRANTED
            state.binderAlive -> PlatformGrant.DENIED
            else -> null
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
        // Binder loss cannot prove that Shizuku permission was revoked. Keep
        // the last confirmed grant across disconnects and process restarts;
        // only a live Binder reporting denial invalidates the durable setup.
        definitiveGrant?.let { grant ->
            authorityManager.updatePlatformGrant(ElevatedAuthority.SHIZUKU, grant)
            val canonical = authorityManager.state.value.statuses[ElevatedAuthority.SHIZUKU]
            when {
                // User intent is the explicit app-side authorization, while
                // Shizuku's live GRANTED result is the platform-side proof.
                // Their conjunction may durably restore configuration even
                // when the user granted access from Shizuku Manager instead
                // of this app's permission button. Grant alone never selects
                // or enables the authority.
                grant == PlatformGrant.GRANTED &&
                    canonical?.userIntent == AuthorityUserIntent.SHIZUKU &&
                    canonical?.configured == false -> {
                    authorityManager.setConfigured(ElevatedAuthority.SHIZUKU, true)
                }
                grant == PlatformGrant.DENIED && canonical?.configured == true -> {
                    authorityManager.setConfigured(ElevatedAuthority.SHIZUKU, false)
                }
            }
        }
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
        val bindingInvalidated = state.lastError in setOf(
            WiredAdbErrorCode.BRIDGE_BINDING_MISMATCH,
            WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH,
            WiredAdbErrorCode.BRIDGE_AUTH_FAILED,
            WiredAdbErrorCode.BRIDGE_SECRET_UNAVAILABLE,
        )
        val definitiveGrant = when {
            bindingInvalidated -> PlatformGrant.REVOKED
            else -> when (state.platformGrant) {
                WiredAdbPlatformGrant.GRANTED -> PlatformGrant.GRANTED
                WiredAdbPlatformGrant.DENIED -> PlatformGrant.DENIED
                WiredAdbPlatformGrant.REVOKED -> PlatformGrant.REVOKED
                WiredAdbPlatformGrant.UNKNOWN -> null
            }
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
        // A missing cable, Wi-Fi route or desktop process yields UNKNOWN and
        // must retain paired trust. Only a definitive trust/binding failure
        // invalidates the persistent configured state.
        definitiveGrant?.let { grant ->
            authorityManager.updatePlatformGrant(ElevatedAuthority.WIRED_ADB, grant)
            if (grant in setOf(PlatformGrant.DENIED, PlatformGrant.REVOKED) &&
                authorityManager.state.value.statuses[ElevatedAuthority.WIRED_ADB]?.configured == true
            ) {
                authorityManager.setConfigured(ElevatedAuthority.WIRED_ADB, false)
            }
        }
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
                schedulePrivilegedWorkspaceReattach()
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
        privilegedWorkspaceProviders.values.forEach { provider -> runCatching { provider.close() } }
        privilegedWorkspaceProviders.clear()
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
        val bindings = capabilityGrantRepository.listSnapshotBindings(input.snapshotId)
        val conversationWorkspaceId = conversationWorkspaceBindingRepository.get(input.sessionIdentity)?.workspaceId
        val snapshotWorkspaceIds = bindings.mapNotNull { it.workspaceId }.toSet()
        require(snapshotWorkspaceIds.size <= 1) { "Snapshot contains more than one workspace" }
        val selectedWorkspaceId = conversationWorkspaceId ?: snapshotWorkspaceIds.singleOrNull()
        require(
            conversationWorkspaceId == null || snapshotWorkspaceIds.isEmpty() || conversationWorkspaceId in snapshotWorkspaceIds,
        ) { "Conversation and snapshot workspace bindings do not match" }
        val grants = capabilityGrantRepository.forAgent(input.agentId, includeRevoked = true)
            .filter { grant -> grant.workspaceId == null || grant.workspaceId == selectedWorkspaceId }
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
                    runCatching { hasPersistedSafGrant(Uri.parse(grant.uriReference), grant) }
                        .getOrDefault(false)
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

    /** Stable, non-sensitive status for a workspace row exposed to UI callers. */
    private fun workspaceAccessStatus(workspace: Workspace): WorkspaceAccessStatus {
        val safGrant = if (workspace.backendType == WorkspaceBackendType.SAF_TREE) {
            safWorkspaceGrantRepository.get(workspace.id)
        } else {
            null
        }
        if (safGrant?.status == SafGrantStatus.REVOKED) return WorkspaceAccessStatus.REVOKED
        if (!workspace.enabled) return WorkspaceAccessStatus.DISABLED
        if (safGrant?.status == SafGrantStatus.GRANT_LOST) return WorkspaceAccessStatus.GRANT_LOST
        if (safGrant?.status == SafGrantStatus.ACTIVE) {
            val uri = runCatching { Uri.parse(safGrant.uriReference) }.getOrNull()
            if (uri == null || !hasPersistedSafGrant(uri, safGrant)) return WorkspaceAccessStatus.GRANT_LOST
        }
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

    private fun safeWorkspaceDisplayName(
        workspace: Workspace,
        ordinal: Int,
        requestedName: String = workspace.displayName,
    ): String = persistedWorkspaceFolderLabel(
        backendType = workspace.backendType,
        requestedName = requestedName,
        ordinal = ordinal,
        fullDevice = workspace.scope == WorkspaceScope.FULL_DEVICE_FILES,
    )

    private fun Workspace.authorityOrNull(): Authority? = if (backendType != WorkspaceBackendType.PRIVILEGED) {
        null
    } else {
        rootReference.removePrefix("authority:")
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

    private fun workspaceAccessItem(
        workspace: Workspace,
        ordinal: Int = 1,
        agentId: String? = null,
    ): WorkspaceAccessItem {
        val status = workspaceAccessStatus(workspace)
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val grants = agentId?.let { capabilityGrantRepository.forAgent(it, includeRevoked = false) }
            .orEmpty()
            .filter { grant ->
                grant.workspaceId == workspace.id &&
                    grant.isActiveFor(now, null, null)
            }
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

    private class WorkspaceAccessException(
        val accessCode: WorkspaceAccessErrorCode,
    ) : IllegalStateException()

    private data class PickerBindingCommit(
        val conversation: ConversationWorkspaceBinding? = null,
        val previousConversationWorkspaceId: String? = null,
        val agentDefault: AgentWorkspaceDefault? = null,
        val requiresNewThread: Boolean = false,
    )

    /**
     * Called only from the surrounding workspace/grant transaction.
     *
     * [setAsAgentDefault] and [bindThread] come from the resolved
     * [WorkspaceIntentPlan], never from a screen: a Thread selection therefore
     * can never mutate the Agent default, and a default selection can never
     * rebind an existing Thread.
     */
    private fun persistPickerTarget(
        workspace: Workspace,
        target: WorkspacePickerTarget?,
        grants: List<CapabilityGrant>,
        setAsAgentDefault: Boolean = false,
        bindThread: Boolean = target?.threadId != null,
    ): PickerBindingCommit {
        if (target == null || target.agentId == null) {
            require(target?.threadId == null && !bindThread && !setAsAgentDefault) {
                "A workspace binding target requires an Agent"
            }
            return PickerBindingCommit()
        }
        require(grants.any { it.agentId == target.agentId && it.workspaceId == workspace.id }) {
            "Workspace binding requires an active Agent grant"
        }
        val threadId = target.threadId.takeIf { bindThread }
        val priorConversation = threadId?.let(conversationWorkspaceBindingRepository::get)
        val requiresNewThread = priorConversation != null && priorConversation.workspaceId != workspace.id
        val conversation = if (threadId != null && !requiresNewThread) {
            conversationWorkspaceBindingRepository.bind(
                sessionId = threadId,
                workspaceId = workspace.id,
            )
        } else {
            null
        }
        require(!setAsAgentDefault || workspace.scope == WorkspaceScope.SELECTED_DIRECTORY) {
            "Full-device workspace cannot be an Agent default"
        }
        val defaultRequested = setAsAgentDefault
        val priorDefault = if (defaultRequested) {
            agentWorkspaceDefaultRepository.get(target.agentId)
        } else {
            null
        }
        val agentDefault = if (defaultRequested) {
            agentWorkspaceDefaultRepository.set(
                agentId = target.agentId,
                workspaceId = workspace.id,
                expectedRevision = priorDefault?.revision,
            )
        } else {
            null
        }
        return PickerBindingCommit(
            conversation = conversation,
            previousConversationWorkspaceId = priorConversation?.workspaceId,
            agentDefault = agentDefault,
            requiresNewThread = requiresNewThread,
        )
    }

    private fun finishPickerCommit(
        workspace: Workspace,
        displayName: String,
        grants: List<CapabilityGrant>,
        pickerTarget: WorkspacePickerTarget?,
        pickerCommit: PickerBindingCommit,
        authority: Authority?,
    ): WorkspaceAccessResult {
        pickerCommit.conversation?.let { binding ->
            recordConversationWorkspaceBinding(
                binding = binding,
                agentId = pickerTarget?.agentId.orEmpty(),
                event = ConversationWorkspaceEvent.BOUND,
                previousWorkspaceId = null,
            )
        }
        val item = committedWorkspaceAccessItem(
            workspace = workspace,
            displayName = displayName,
            status = WorkspaceAccessStatus.ACTIVE,
            authority = authority,
            activeGrants = grants,
            fullDeviceConfirmationPresent = true,
        )
        val summaries = grants.map { it.toWorkspaceAccessSummary() }
        if (pickerCommit.requiresNewThread) {
            val agentId = pickerTarget?.agentId
            val threadId = pickerTarget?.threadId
            val currentWorkspaceId = pickerCommit.previousConversationWorkspaceId
            if (agentId != null && threadId != null && currentWorkspaceId != null) {
                return WorkspaceAccessResult.NewThreadRequired(
                    agentId = agentId,
                    currentThreadId = threadId,
                    currentWorkspaceId = currentWorkspaceId,
                    requestedWorkspaceId = workspace.id,
                    workspace = item,
                    grants = summaries,
                    authorizationState = if (grants.isNotEmpty()) {
                        NewThreadAuthorizationState.ALREADY_GRANTED
                    } else {
                        NewThreadAuthorizationState.REQUIRES_CONFIRMATION_COMMIT
                    },
                )
            }
        }
        return WorkspaceAccessResult.Success(item, summaries)
    }

    private fun newThreadRequiredResult(
        pickerTarget: WorkspacePickerTarget?,
        existingBinding: ConversationWorkspaceBinding,
        workspace: Workspace,
        grants: List<CapabilityGrant>,
        authorizationState: NewThreadAuthorizationState = if (grants.isNotEmpty()) {
            NewThreadAuthorizationState.ALREADY_GRANTED
        } else {
            NewThreadAuthorizationState.REQUIRES_CONFIRMATION_COMMIT
        },
    ): WorkspaceAccessResult {
        val agentId = pickerTarget?.agentId
        val threadId = pickerTarget?.threadId
        if (agentId == null || threadId == null) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        return WorkspaceAccessResult.NewThreadRequired(
            agentId = agentId,
            currentThreadId = threadId,
            currentWorkspaceId = existingBinding.workspaceId,
            requestedWorkspaceId = workspace.id,
            workspace = committedWorkspaceAccessItem(
                workspace = workspace,
                displayName = safeWorkspaceDisplayName(workspace, 1),
                status = WorkspaceAccessStatus.ACTIVE,
                authority = workspace.authorityOrNull(),
                activeGrants = grants,
                fullDeviceConfirmationPresent = true,
            ),
            grants = grants.map { it.toWorkspaceAccessSummary() },
            authorizationState = authorizationState,
        )
    }

    private fun ToolErrorCode.toWorkspaceAccessCode(): WorkspaceAccessErrorCode = when (this) {
        ToolErrorCode.WORKSPACE_NOT_FOUND -> WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND
        ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED -> WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED
        ToolErrorCode.AUTHORITY_NOT_GRANTED,
        ToolErrorCode.SHIZUKU_PERMISSION_DENIED,
        ToolErrorCode.SHELL_CAPABILITY_DENIED,
            -> WorkspaceAccessErrorCode.CAPABILITY_DENIED
        ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
        ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE,
        ToolErrorCode.BRIDGE_NOT_PAIRED,
        ToolErrorCode.BRIDGE_DISCONNECTED,
        ToolErrorCode.ADB_DEVICE_UNAUTHORIZED,
        ToolErrorCode.ADB_DEVICE_OFFLINE,
        ToolErrorCode.ADB_DEVICE_DISCONNECTED,
            -> WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE
        ToolErrorCode.CONFLICT -> WorkspaceAccessErrorCode.CONFLICT
        ToolErrorCode.INVALID_REQUEST,
        ToolErrorCode.PATH_OUT_OF_SCOPE,
        ToolErrorCode.ROOT_OPERATION_FORBIDDEN,
            -> WorkspaceAccessErrorCode.INVALID_REQUEST
        ToolErrorCode.AUDIT_UNAVAILABLE,
        ToolErrorCode.AUDIT_FUSE_OPEN,
        ToolErrorCode.IO_ERROR,
            -> WorkspaceAccessErrorCode.PERSISTENCE_FAILED
        ToolErrorCode.UNKNOWN_OUTCOME,
        ToolErrorCode.TIMEOUT,
        ToolErrorCode.SHELL_TIMED_OUT,
        ToolErrorCode.SHELL_CANCELLED,
            -> WorkspaceAccessErrorCode.UNKNOWN_OUTCOME
        else -> WorkspaceAccessErrorCode.UNSUPPORTED
    }

    private fun <T> workspaceFailure(code: ToolErrorCode): WorkspaceResult<T> =
        WorkspaceResult.Failure(ToolError(code))

    private fun workspaceAccessFailure(code: WorkspaceAccessErrorCode): WorkspaceAccessResult =
        WorkspaceAccessResult.Failure(code)

    private fun persistWorkspaceGrantBundle(
        workspace: Workspace,
        backend: runtime.mobileagent.skills.tooling.WorkspaceBackend,
        target: WorkspaceAccessGrantTarget,
    ): List<CapabilityGrant> {
        val requestedCapabilities = target.capabilities.ifEmpty {
            backend.capabilities.filterTo(linkedSetOf()) { capability ->
                capability.value == CapabilityId.WORKSPACE_ENUMERATE || capability.value.startsWith("file.")
            }
        }
        if (requestedCapabilities.isEmpty()) throw WorkspaceAccessException(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        if (requestedCapabilities.any { it !in backend.capabilities }) {
            throw WorkspaceAccessException(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        if (target.lifetime != runtime.mobileagent.domain.GrantLifetime.PERSISTENT) {
            // Workspace access is an Agent-level setting shared by all of its
            // sessions. Scoped or one-shot overlays are intentionally not part
            // of this facade; they would create a second session truth.
            throw WorkspaceAccessException(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        val normalizedPath = try {
            WorkspacePathPolicy.normalize(target.pathScope, allowRoot = true)
                .takeIf { it.isNotEmpty() }
        } catch (_: RuntimeException) {
            throw WorkspaceAccessException(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val existing = capabilityGrantRepository.forAgent(target.agentId, includeRevoked = true)
        // An Agent may own several workspaces.  Updating one workspace only
        // retires stale grants for that same workspace; it must never revoke a
        // sibling workspace merely because both use SELECTED_DIRECTORY.
        existing.asSequence()
            .filter { old ->
                val oldWorkspaceId = old.workspaceId ?: return@filter false
                if (old.revoked) return@filter false
                if (old.skillInstallId != null || old.packageHash != null) return@filter false
                if (oldWorkspaceId != workspace.id) return@filter false
                old.capability !in requestedCapabilities ||
                    old.pathScope != normalizedPath ||
                    old.lifetime != target.lifetime ||
                    old.taskId != null ||
                    old.sessionId != null ||
                    old.policyVersion != policyVersion
            }
            .forEach { old -> capabilityGrantRepository.revoke(old.grantId, old.revision) }
        return requestedCapabilities.sortedBy { it.value }.map { capability ->
            val reusable = existing.firstOrNull { grant ->
                !grant.revoked && !grant.consumed &&
                    grant.agentId == target.agentId &&
                    grant.capability == capability &&
                    grant.skillInstallId == null &&
                    grant.packageHash == null &&
                    grant.workspaceId == workspace.id &&
                    grant.pathScope == normalizedPath &&
                    grant.lifetime == target.lifetime &&
                    grant.taskId == null &&
                    grant.sessionId == null &&
                    grant.policyVersion == policyVersion &&
                    grant.isActiveFor(now, null, null)
            }
            reusable ?: capabilityGrantRepository.save(
                CapabilityGrant(
                    grantId = EntityId.random().value,
                    agentId = target.agentId,
                    capability = capability,
                    workspaceId = workspace.id,
                    pathScope = normalizedPath,
                    lifetime = target.lifetime,
                    policyVersion = policyVersion,
                    createdAt = Utc.nowIso(),
                    revision = 1L,
                ),
            )
        }
    }

    private fun persistWorkspaceAndGrants(
        workspace: Workspace,
        backend: runtime.mobileagent.skills.tooling.WorkspaceBackend,
        target: WorkspaceAccessGrantTarget?,
    ): List<CapabilityGrant> = try {
        db.transaction {
            workspaceRepository.save(workspace)
            target?.let { persistWorkspaceGrantBundle(workspace, backend, it) }.orEmpty()
        }
    } catch (failure: WorkspaceAccessException) {
        throw failure
    } catch (_: AuthorityPolicyConflictException) {
        throw WorkspaceAccessException(WorkspaceAccessErrorCode.CONFLICT)
    } catch (_: RuntimeException) {
        throw WorkspaceAccessException(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
    }

    /** Save one canonical Agent grant without mutating grants for other workspaces. */
    private fun saveAgentGrantWithCurrentWorkspaceInvariant(grant: CapabilityGrant): CapabilityGrant {
        grant.workspaceId?.let { workspaceId ->
            require(workspaceRepository.get(workspaceId)?.enabled == true) { "Workspace is unavailable" }
        }
        return capabilityGrantRepository.save(grant)
    }

    private fun attachSafWorkspace(
        uri: Uri,
        resultFlags: Int,
        grant: WorkspaceAccessGrantTarget?,
        pickerTarget: WorkspacePickerTarget? = null,
        plan: WorkspaceIntentPlan,
    ): WorkspaceAccessResult {
        if (pickerTarget?.agentId != null && grant?.agentId != pickerTarget.agentId) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank()) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        val requestedFlags = safRequestedFlags(resultFlags)
        try {
            // OpenDocumentTree may return a read-only grant. Preserve exactly
            // the provider's actual flags rather than assuming write access.
            appContext.contentResolver.takePersistableUriPermission(uri, requestedFlags)
        } catch (_: RuntimeException) {
            if (requestedFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
            }
            try {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: RuntimeException) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
            }
        }
        val persisted = appContext.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
        val actualFlags = safPersistableFlags(persisted.isReadPermission, persisted.isWritePermission)
        if (actualFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
        }

        val existingGrant = safWorkspaceGrantRepository.list(includeRevoked = true)
            .firstOrNull { it.uriReference == uri.toString() }
        val existingWorkspace = existingGrant?.let { workspaceRepository.get(it.workspaceId) }
        val id = existingGrant?.workspaceId ?: "saf-" + UUID.randomUUID().toString().replace("-", "")
        if (existingWorkspace != null && existingWorkspace.backendType != WorkspaceBackendType.SAF_TREE) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        val safTitle = safTreeUiTitle(appContext, uri)
        val workspace = Workspace(
            id = id,
            displayName = persistedWorkspaceFolderLabel(
                backendType = WorkspaceBackendType.SAF_TREE,
                requestedName = safTitle,
                ordinal = (workspaceRepository.list().count { it.backendType == WorkspaceBackendType.SAF_TREE } + 1),
                fullDevice = false,
            ),
            backendType = WorkspaceBackendType.SAF_TREE,
            rootReference = uri.toString(),
            readable = true,
            writable = actualFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
            quotaBytes = 4L * 1024L * 1024L,
            maxFileBytes = 256L * 1024L,
            enabled = true,
            revision = (existingWorkspace?.revision ?: 0L) + 1L,
            createdAt = existingWorkspace?.createdAt.orEmpty(),
            scope = WorkspaceScope.SELECTED_DIRECTORY,
        )
        val backend = try {
            runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(appContext, uri, id)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        }
        val safGrant = SafWorkspaceGrant(
            workspaceId = id,
            uriReference = uri.toString(),
            readGranted = true,
            writeGranted = actualFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
            persistedFlags = actualFlags,
            status = SafGrantStatus.ACTIVE,
            createdAt = existingGrant?.createdAt ?: existingWorkspace?.createdAt.orEmpty(),
        )
        val existingBinding = pickerTarget?.threadId
            ?.takeIf { plan.bindThread }
            ?.let(conversationWorkspaceBindingRepository::get)
        val switchingBoundThread = existingBinding != null && existingBinding.workspaceId != id
        if (switchingBoundThread) {
            try {
                db.transaction {
                    workspaceRepository.save(workspace)
                    safWorkspaceGrantRepository.save(safGrant)
                }
            } catch (failure: WorkspaceAccessException) {
                return workspaceAccessFailure(failure.accessCode)
            } catch (_: AuthorityPolicyConflictException) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
            } catch (_: RuntimeException) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
            }
            try {
                workspaceRegistry.registerOrReplace(workspace, backend)
            } catch (_: RuntimeException) {
                runCatching {
                    workspaceRepository.save(
                        workspace.copy(
                            enabled = false,
                            readable = false,
                            writable = false,
                            revision = workspace.revision + 1L,
                        ),
                    )
                }
                return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
            }
            rememberUiPresentation(
                workspaceId = workspace.id,
                kind = WorkspaceUiKind.SAF,
                title = safTitle,
            )
            val agentId = pickerTarget?.agentId
            val existingGrants = if (agentId != null) {
                val now = Instant.ofEpochMilli(System.currentTimeMillis())
                val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
                capabilityGrantRepository.forAgent(agentId, includeRevoked = false)
                    .filter { it.workspaceId == workspace.id && it.policyVersion == policyVersion && it.isActiveFor(now, null, null) }
            } else {
                emptyList()
            }
            return newThreadRequiredResult(
                pickerTarget = pickerTarget,
                existingBinding = existingBinding!!,
                workspace = workspace,
                grants = existingGrants,
                authorizationState = if (existingGrants.isNotEmpty()) {
                    NewThreadAuthorizationState.ALREADY_GRANTED
                } else {
                    NewThreadAuthorizationState.REQUIRES_CONFIRMATION_COMMIT
                },
            )
        }
        var pickerCommit = PickerBindingCommit()
        val grants = try {
            db.transaction {
                workspaceRepository.save(workspace)
                safWorkspaceGrantRepository.save(safGrant)
                val committedGrants = grant?.let { persistWorkspaceGrantBundle(workspace, backend, it) }.orEmpty()
                pickerCommit = persistPickerTarget(
                    workspace = workspace,
                    target = pickerTarget,
                    grants = committedGrants,
                    setAsAgentDefault = plan.persistDefaultNow(),
                    bindThread = plan.bindThread,
                )
                committedGrants
            }
        } catch (failure: WorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        } catch (_: AuthorityPolicyConflictException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        try {
            workspaceRegistry.registerOrReplace(workspace, backend)
        } catch (_: RuntimeException) {
            runCatching {
                workspaceRepository.save(
                    workspace.copy(
                        enabled = false,
                        readable = false,
                        writable = false,
                        revision = workspace.revision + 1L,
                    ),
                )
            }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        }
        rememberUiPresentation(
            workspaceId = workspace.id,
            kind = WorkspaceUiKind.SAF,
            title = safTitle,
        )
        diagnostics.recordWorkspaceGrantChanged(
            WorkspaceGrantChangedRecord(
                id,
                if (workspace.writable) DiagnosticGrantScope.READ_WRITE else DiagnosticGrantScope.READ,
                true,
            ),
        )
        return finishPickerCommit(
            workspace = workspace,
            displayName = workspace.displayName,
            grants = grants,
            pickerTarget = pickerTarget,
            pickerCommit = pickerCommit,
            authority = null,
        )
    }

    private fun CapabilityGrant.toWorkspaceAccessSummary(): WorkspaceAccessGrantSummary =
        WorkspaceAccessGrantSummary(
            grantId = grantId,
            capability = capability,
            lifetime = lifetime,
            revision = revision,
        )

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

    private suspend fun attachPrivilegedDirectoryInternal(
        authority: Authority,
        request: WorkspaceAttachRequest,
        grant: WorkspaceAccessGrantTarget?,
        pickerTarget: WorkspacePickerTarget? = null,
        plan: WorkspaceIntentPlan,
    ): WorkspaceAccessResult {
        if (pickerTarget?.agentId != null && grant?.agentId != pickerTarget.agentId) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        val provider = authorityProviderFor(authority)
            ?: return workspaceAccessFailure(privilegedFailure(authority).toWorkspaceAccessCode())
        val attachment = try {
            provider.attachDirectory(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        }
        val value = when (attachment) {
            is WorkspaceResult.Failure -> return workspaceAccessFailure(attachment.error.code.toWorkspaceAccessCode())
            is WorkspaceResult.Success -> attachment.value
        }
        return persistPrivilegedAttachment(authority, request.workspaceId, request.displayName, value, grant, pickerTarget, plan)
    }

    private suspend fun attachPrivilegedPathInternal(
        authority: Authority,
        workspaceId: String,
        displayName: String,
        absolutePath: String,
        grant: WorkspaceAccessGrantTarget?,
        pickerTarget: WorkspacePickerTarget? = null,
        plan: WorkspaceIntentPlan,
    ): WorkspaceAccessResult {
        if (authority != Authority.WIRED_ADB) return workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED)
        val provider = authorityProviderFor(authority) as? runtime.mobileagent.wired.WiredAdbDeviceWorkspaceProvider
            ?: return workspaceAccessFailure(privilegedFailure(authority).toWorkspaceAccessCode())
        val attachment = try {
            provider.attachUserPath(workspaceId, displayName, absolutePath)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        }
        val value = when (attachment) {
            is WorkspaceResult.Failure -> return workspaceAccessFailure(attachment.error.code.toWorkspaceAccessCode())
            is WorkspaceResult.Success -> attachment.value
        }
        return persistPrivilegedAttachment(authority, workspaceId, displayName, value, grant, pickerTarget, plan)
    }

    private fun persistPrivilegedAttachment(
        authority: Authority,
        workspaceId: String,
        displayName: String,
        value: runtime.mobileagent.skills.tooling.WorkspaceAttachment,
        grant: WorkspaceAccessGrantTarget?,
        pickerTarget: WorkspacePickerTarget? = null,
        plan: WorkspaceIntentPlan,
    ): WorkspaceAccessResult {
        if (value.descriptor.id != workspaceId) {
            value.recoveryLocator?.clear()
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        val existing = workspaceRepository.get(workspaceId)
        if (existing != null && (
            existing.backendType != WorkspaceBackendType.PRIVILEGED ||
                existing.scope == WorkspaceScope.FULL_DEVICE_FILES
            )
        ) {
            value.recoveryLocator?.clear()
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        val recoveryLocator = value.recoveryLocator
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED)
        val locatorBytes = runCatching { recoveryLocator.copyBytes() }.getOrNull()
            ?: run {
                recoveryLocator.clear()
                return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
            }
        val locatorVersion = PRIVILEGED_LOCATOR_VERSION
        val aad = PrivilegedWorkspaceBindingAad(
            appInstanceId = appInstanceId,
            workspaceId = workspaceId,
            authority = authority,
            locatorVersion = locatorVersion,
        )
        val envelope = try {
            when (val sealed = bindingCipher.seal(locatorBytes, aad)) {
                is PrivilegedWorkspaceBindingSealResult.Success -> sealed.envelope
                is PrivilegedWorkspaceBindingSealResult.Failure -> {
                    return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
                }
            }
        } finally {
            locatorBytes.fill(0)
            recoveryLocator.clear()
        }
        val workspace = Workspace(
            id = value.descriptor.id,
            displayName = safeWorkspaceDisplayName(
                Workspace(
                    id = value.descriptor.id,
                    displayName = displayName,
                    backendType = WorkspaceBackendType.PRIVILEGED,
                    rootReference = "authority:${authority.name}",
                    scope = WorkspaceScope.SELECTED_DIRECTORY,
                ),
                ordinal = workspaceRepository.list().count { it.backendType == WorkspaceBackendType.PRIVILEGED } + 1,
            ),
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "authority:${authority.name}",
            readable = value.descriptor.readable,
            writable = value.descriptor.writable,
            quotaBytes = value.descriptor.quotaBytes,
            maxFileBytes = value.descriptor.maxFileBytes,
            enabled = true,
            revision = (existing?.revision ?: 0L) + 1L,
            createdAt = existing?.createdAt.orEmpty(),
            scope = WorkspaceScope.SELECTED_DIRECTORY,
        )
        val previousBinding = privilegedWorkspaceBindingRepository.get(workspaceId)
        val binding = PrivilegedWorkspaceBinding(
            workspaceId = workspaceId,
            authority = authority,
            encryptedLocator = envelope.encryptedLocator,
            locatorNonce = envelope.locatorNonce,
            locatorVersion = locatorVersion,
            keyVersion = PRIVILEGED_BINDING_KEY_VERSION,
            aadAppInstanceId = appInstanceId,
            scope = WorkspaceScope.SELECTED_DIRECTORY,
            status = PrivilegedWorkspaceBindingStatus.ACTIVE,
            revision = (previousBinding?.revision ?: 0L) + 1L,
            createdAt = previousBinding?.createdAt.orEmpty(),
        )
        val existingBinding = pickerTarget?.threadId
            ?.takeIf { plan.bindThread }
            ?.let(conversationWorkspaceBindingRepository::get)
        val switchingBoundThread = existingBinding != null && existingBinding.workspaceId != workspace.id
        if (switchingBoundThread) {
            try {
                db.transaction {
                    workspaceRepository.save(workspace)
                    privilegedWorkspaceBindingRepository.save(binding)
                }
            } catch (failure: WorkspaceAccessException) {
                return workspaceAccessFailure(failure.accessCode)
            } catch (_: RuntimeException) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
            }
            try {
                workspaceRegistry.registerOrReplace(workspace, value.backend)
            } catch (_: RuntimeException) {
                runCatching {
                    privilegedWorkspaceBindingRepository.updateStatus(
                        workspaceId,
                        binding.revision,
                        PrivilegedWorkspaceBindingStatus.UNAVAILABLE,
                    )
                }
                return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
            }
            diagnostics.recordPrivilegedWorkspaceBindingPersisted(
                PrivilegedWorkspaceBindingPersistedRecord(
                    workspaceId = workspaceId,
                    authority = authority.toDiagnostic(),
                    bindingRevision = binding.revision.toDiagnosticGeneration(),
                    grantGeneration = 0,
                ),
            )
            val privilegedKind = if (authority == Authority.WIRED_ADB) {
                WorkspaceUiKind.PRIVILEGED_WIRED
            } else {
                WorkspaceUiKind.PRIVILEGED_SHIZUKU
            }
            rememberUiPresentation(
                workspaceId = workspaceId,
                kind = privilegedKind,
                title = privilegedUiTitle(displayName) ?: displayName.substringAfterLast('/').ifBlank { displayName },
            )
            val agentId = pickerTarget?.agentId
            val existingGrants = if (agentId != null) {
                val now = Instant.ofEpochMilli(System.currentTimeMillis())
                val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
                capabilityGrantRepository.forAgent(agentId, includeRevoked = false)
                    .filter { it.workspaceId == workspace.id && it.policyVersion == policyVersion && it.isActiveFor(now, null, null) }
            } else {
                emptyList()
            }
            return newThreadRequiredResult(
                pickerTarget = pickerTarget,
                existingBinding = existingBinding!!,
                workspace = workspace,
                grants = existingGrants,
                authorizationState = if (existingGrants.isNotEmpty()) {
                    NewThreadAuthorizationState.ALREADY_GRANTED
                } else {
                    NewThreadAuthorizationState.REQUIRES_CONFIRMATION_COMMIT
                },
            )
        }
        var pickerCommit = PickerBindingCommit()
        val grants = try {
            db.transaction {
                workspaceRepository.save(workspace)
                privilegedWorkspaceBindingRepository.save(binding)
                val committedGrants = grant?.let { persistWorkspaceGrantBundle(workspace, value.backend, it) }.orEmpty()
                pickerCommit = persistPickerTarget(
                    workspace = workspace,
                    target = pickerTarget,
                    grants = committedGrants,
                    setAsAgentDefault = plan.persistDefaultNow(),
                    bindThread = plan.bindThread,
                )
                committedGrants
            }
        } catch (failure: WorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        try {
            workspaceRegistry.registerOrReplace(workspace, value.backend)
        } catch (_: RuntimeException) {
            runCatching {
                privilegedWorkspaceBindingRepository.updateStatus(
                    workspaceId,
                    binding.revision,
                    PrivilegedWorkspaceBindingStatus.UNAVAILABLE,
                )
            }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        }
        diagnostics.recordPrivilegedWorkspaceBindingPersisted(
            PrivilegedWorkspaceBindingPersistedRecord(
                workspaceId = workspaceId,
                authority = authority.toDiagnostic(),
                bindingRevision = binding.revision.toDiagnosticGeneration(),
                grantGeneration = grants.maxOfOrNull { it.revision }?.toDiagnosticGeneration() ?: 0,
            ),
        )
        val privilegedKind = if (authority == Authority.WIRED_ADB) {
            WorkspaceUiKind.PRIVILEGED_WIRED
        } else {
            WorkspaceUiKind.PRIVILEGED_SHIZUKU
        }
        rememberUiPresentation(
            workspaceId = workspaceId,
            kind = privilegedKind,
            title = privilegedUiTitle(displayName) ?: displayName.substringAfterLast('/').ifBlank { displayName },
        )
        return finishPickerCommit(
            workspace = workspace,
            displayName = workspace.displayName,
            grants = grants,
            pickerTarget = pickerTarget,
            pickerCommit = pickerCommit,
            authority = authority,
        )
    }

    private suspend fun openFullDeviceFilesInternal(
        authority: Authority,
        request: FullDeviceFilesRequest,
        grant: WorkspaceAccessGrantTarget?,
    ): WorkspaceAccessResult {
        if (!buildPolicy.permitsDangerousMode() || dangerousModeManager.policy() == DangerousMode.DISABLED) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        if (!request.confirmedByUser) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        val provider = authorityProviderFor(authority)
            ?: return workspaceAccessFailure(privilegedFailure(authority).toWorkspaceAccessCode())
        if (!provider.supportsFullDeviceFiles) return workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED)

        // The provider must observe a durable, monotonic confirmation before
        // it opens the device-root handle. A new confirmation starts at one;
        // a revoked tombstone can only be re-enabled with its next revision.
        // This keeps a stale toggle or replayed request from reactivating an
        // older full-device grant.
        val existingFullDeviceGrant = fullDeviceFilesGrantRepository.load(request.workspaceId)
        val currentFullDeviceRevision = fullDeviceFilesGrantRepository.currentRevision(request.workspaceId)
        val expectedFullDeviceRevision = when {
            existingFullDeviceGrant != null -> existingFullDeviceGrant.revision
            currentFullDeviceRevision != null -> currentFullDeviceRevision + 1L
            else -> 1L
        }
        if (request.grantRevision != expectedFullDeviceRevision) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        val fullDeviceGrant = FullDeviceFilesGrant(
            workspaceId = request.workspaceId,
            revision = request.grantRevision,
            confirmedAtEpochMs = System.currentTimeMillis(),
        )
        when (val saved = fullDeviceFilesGrantRepository.save(fullDeviceGrant)) {
            is WorkspaceResult.Failure -> return workspaceAccessFailure(saved.error.code.toWorkspaceAccessCode())
            is WorkspaceResult.Success -> Unit
        }
        val newlyCreatedFullDeviceGrant = existingFullDeviceGrant == null

        fun rollbackNewFullDeviceGrant(): WorkspaceAccessResult.Failure? {
            if (!newlyCreatedFullDeviceGrant) return null
            return when (val rollback = fullDeviceFilesGrantRepository.revoke(request.workspaceId, request.grantRevision)) {
                is WorkspaceResult.Success -> null
                is WorkspaceResult.Failure -> WorkspaceAccessResult.Failure(
                    rollback.error.code.toWorkspaceAccessCode().takeIf { it != WorkspaceAccessErrorCode.UNSUPPORTED }
                        ?: WorkspaceAccessErrorCode.UNKNOWN_OUTCOME,
                )
            }
        }

        val attachment = try {
            provider.openFullDeviceFiles(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        }
        val value = when (attachment) {
            is WorkspaceResult.Failure -> {
                rollbackNewFullDeviceGrant()?.let { return it }
                return workspaceAccessFailure(attachment.error.code.toWorkspaceAccessCode())
            }
            is WorkspaceResult.Success -> attachment.value
        }
        val existing = workspaceRepository.get(request.workspaceId)
        if (existing != null && (
            existing.backendType != WorkspaceBackendType.PRIVILEGED ||
                existing.scope != WorkspaceScope.FULL_DEVICE_FILES
            )
        ) {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        val workspace = Workspace(
            id = value.descriptor.id,
            displayName = "设备文件区",
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "authority:${authority.name}",
            readable = value.descriptor.readable,
            writable = value.descriptor.writable,
            quotaBytes = value.descriptor.quotaBytes,
            maxFileBytes = value.descriptor.maxFileBytes,
            enabled = true,
            revision = (existing?.revision ?: 0L) + 1L,
            createdAt = existing?.createdAt.orEmpty(),
            scope = WorkspaceScope.FULL_DEVICE_FILES,
        )
        val grants = try {
            persistWorkspaceAndGrants(workspace, value.backend, grant)
        } catch (failure: WorkspaceAccessException) {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(failure.accessCode)
        }
        try {
            workspaceRegistry.registerOrReplace(workspace, value.backend)
        } catch (_: RuntimeException) {
            rollbackNewFullDeviceGrant()?.let { return it }
            runCatching {
                workspaceRepository.save(
                    workspace.copy(
                        enabled = false,
                        readable = false,
                        writable = false,
                        revision = workspace.revision + 1L,
                    ),
                )
            }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        }
        return WorkspaceAccessResult.Success(
            workspaceAccessItem(workspace, agentId = grant?.agentId),
            grants.map { it.toWorkspaceAccessSummary() },
        )
    }

    private fun grantWorkspaceInternal(
        workspaceId: String,
        target: WorkspaceAccessGrantTarget,
    ): WorkspaceAccessResult {
        val workspace = workspaceRepository.get(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (!workspace.enabled) return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        val registered = workspaceRegistry.registered(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        if (workspace.backendType == WorkspaceBackendType.SAF_TREE) {
            val saf = safWorkspaceGrantRepository.get(workspaceId)
                ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
            if (saf.status != SafGrantStatus.ACTIVE) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
            }
            val uri = runCatching { Uri.parse(saf.uriReference) }.getOrNull()
                ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
            if (!hasPersistedSafGrant(uri, saf)) return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
        } else if (workspace.scope == WorkspaceScope.FULL_DEVICE_FILES) {
            if (fullDeviceFilesGrantRepository.load(workspaceId) == null) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
            }
            val authority = workspace.rootReference.removePrefix("authority:")
                .let { runCatching { Authority.valueOf(it) }.getOrNull() }
                ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
            if (authorityProviderFor(authority) == null) {
                return workspaceAccessFailure(privilegedFailure(authority).toWorkspaceAccessCode())
            }
        }
        val grants = try {
            persistWorkspaceAndGrants(workspace, registered.backend, target)
        } catch (failure: WorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        }
        return WorkspaceAccessResult.Success(
            workspaceAccessItem(workspace, agentId = target.agentId),
            grants.map { it.toWorkspaceAccessSummary() },
        )
    }

    private fun revokeGrantInternal(grantId: String, expectedRevision: Long): WorkspaceAccessResult {
        val current = capabilityGrantRepository.get(grantId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (current.revision != expectedRevision) return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        val workspace = current.workspaceId?.let(workspaceRepository::get)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        val revoked = try {
            capabilityGrantRepository.revoke(grantId, expectedRevision)
            capabilityGrantRepository.get(grantId)
        } catch (_: AuthorityPolicyConflictException) {
            null
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        } ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        return WorkspaceAccessResult.Success(
            workspaceAccessItem(workspace, agentId = revoked.agentId),
            listOf(revoked.toWorkspaceAccessSummary()),
        )
    }

    private fun revokeWorkspaceInternal(workspaceId: String): WorkspaceAccessResult {
        val workspace = workspaceRepository.get(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        val activeGrants = capabilityGrantRepository.forWorkspace(workspaceId, includeRevoked = false)
        try {
            db.transaction {
                activeGrants.forEach { grant ->
                    capabilityGrantRepository.revoke(grant.grantId, grant.revision)
                }
                if (workspace.backendType == WorkspaceBackendType.SAF_TREE) {
                    safWorkspaceGrantRepository.markRevoked(workspaceId)
                }
                privilegedWorkspaceBindingRepository.get(workspaceId)?.let { binding ->
                    if (binding.status != PrivilegedWorkspaceBindingStatus.REVOKED) {
                        privilegedWorkspaceBindingRepository.updateStatus(
                            workspaceId,
                            binding.revision,
                            PrivilegedWorkspaceBindingStatus.REVOKED,
                        )
                    }
                }
                workspaceRepository.save(
                    workspace.copy(
                        enabled = false,
                        readable = false,
                        writable = false,
                        revision = workspace.revision + 1L,
                    ),
                )
            }
        } catch (_: AuthorityPolicyConflictException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        if (workspace.scope == WorkspaceScope.FULL_DEVICE_FILES) {
            val fullDeviceGrant = fullDeviceFilesGrantRepository.load(workspaceId)
            if (fullDeviceGrant != null) {
                when (val revoked = fullDeviceFilesGrantRepository.revoke(workspaceId, fullDeviceGrant.revision)) {
                    is WorkspaceResult.Success -> Unit
                    is WorkspaceResult.Failure -> {
                        // The provider-side grant is the high-risk gate. If
                        // its durable revoke cannot be confirmed, remove the
                        // in-memory backend before returning so a stale
                        // registry entry cannot dispatch against the scope.
                        workspaceRegistry.unregister(workspaceId)
                        return workspaceAccessFailure(revoked.error.code.toWorkspaceAccessCode())
                    }
                }
            }
        }
        var platformReleaseFailed = false
        val saf = safWorkspaceGrantRepository.get(workspaceId)
        val safUri = saf?.uriReference
        val sharedSafUriStillHeld = safUri != null && safWorkspaceGrantRepository.list(includeRevoked = false).any { other ->
            other.workspaceId != workspaceId &&
                other.status == SafGrantStatus.ACTIVE &&
                other.uriReference == safUri
        }
        if (saf != null && !sharedSafUriStillHeld) {
            platformReleaseFailed = runCatching {
                val uri = Uri.parse(saf.uriReference)
                val actual = appContext.contentResolver.persistedUriPermissions
                    .firstOrNull { it.uri == uri }
                    ?.let { safPersistableFlags(it.isReadPermission, it.isWritePermission) }
                    ?: saf.persistedFlags
                if (actual != 0) appContext.contentResolver.releasePersistableUriPermission(uri, actual)
            }.isFailure
        }
        workspaceRegistry.unregister(workspaceId)
        diagnostics.recordWorkspaceGrantChanged(
            WorkspaceGrantChangedRecord(workspaceId, DiagnosticGrantScope.NONE, false),
        )
        if (platformReleaseFailed) return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        val disabled = workspaceRepository.get(workspaceId) ?: workspace.copy(enabled = false)
        return WorkspaceAccessResult.Success(workspaceAccessItem(disabled), emptyList())
    }

    private inner class RuntimeWorkspaceAccessPort : WorkspaceAccessPort {
        override fun listWorkspaces(agentId: String?): List<WorkspaceAccessItem> =
            listWorkspaceAccessItems(agentId)

        override fun attachSaf(uri: Uri, resultFlags: Int, grant: WorkspaceAccessGrantTarget?): WorkspaceAccessResult =
            attachSafWorkspace(uri, resultFlags, grant, pickerTarget = null, plan = planFor(null))

        override fun grantWorkspace(workspaceId: String, grant: WorkspaceAccessGrantTarget): WorkspaceAccessResult =
            grantWorkspaceInternal(workspaceId, grant)

        override fun revokeGrant(grantId: String, expectedRevision: Long): WorkspaceAccessResult =
            revokeGrantInternal(grantId, expectedRevision)

        override fun revokeWorkspace(workspaceId: String): WorkspaceAccessResult =
            revokeWorkspaceInternal(workspaceId)

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
        ): WorkspaceAccessResult = attachPrivilegedDirectoryInternal(
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
        ): WorkspaceAccessResult = attachPrivilegedPathInternal(
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
        ): WorkspaceAccessResult = openFullDeviceFilesInternal(authority, request, grant)

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
            return revokeWorkspaceInternal(workspaceId).also { result ->
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
        private const val PRIVILEGED_LOCATOR_VERSION = 1
        private const val PRIVILEGED_BINDING_KEY_VERSION = 1
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
