// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import runtime.mobileagent.SettingsAuthorityProviderState
import runtime.mobileagent.SettingsAuthoritySnapshot
import runtime.mobileagent.SettingsSafGrantState
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RootDispatchRepository
import runtime.mobileagent.data.RootToolExposureGate
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootApprovalBinding
import runtime.mobileagent.domain.RootAuthorityPresentation
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootChannelUiModel
import runtime.mobileagent.domain.RootChannelUiStatus
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootExecutionBudget
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootFileMetadataSnapshot
import runtime.mobileagent.domain.RootFileOperationResult
import runtime.mobileagent.domain.RootHostControlGate
import runtime.mobileagent.domain.RootServiceLifecycleCoordinator
import runtime.mobileagent.domain.RootServiceSession
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.domain.RootWorkspaceFileExecutor
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.security.AndroidAppInstanceIdStoreFactory
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.AuthorityState
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrantStore
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecResult
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.tooling.AuthorityManager

/**
 * Production Android coordinator for Root execution channels ([Authority.ROOT_DIRECT] and
 * [Authority.SHIZUKU_ROOT]).
 */
class RootRuntimeCoordinator(
    context: Context,
    private val db: SqlConnection,
    private val fdOps: PosixFdOperations = AndroidOsPosixFdOperations(),
    private val sessionConnector: RootSessionConnector = AndroidRootSessionConnector(context),
    private val shellRunner: RootShellCommandRunner = Uid0ProcessGroupShellRunner(),
    private val controlPlaneBoundaryOverride: RootControlPlaneBoundary? = null,
    private val locatorCipher: RootLocatorCipher = AndroidRootLocatorCipher(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val authorityPolicyRepository = AuthorityPolicyRepository(db)
    private val appInstanceId: String by lazy {
        runCatching {
            AndroidAppInstanceIdStoreFactory.create(appContext).loadOrCreateAppInstanceId()
        }.getOrDefault("root-app-instance-fallback-0001")
    }
    val rootAuthorityRepository = RootAuthorityRepository(db)
    val rootToolExposureGate = RootToolExposureGate(db)
    val rootDispatchRepository = RootDispatchRepository(db)
    internal val dispatchBridge = RootCoordinatorDispatchBridge(
        db = db,
        exposureGate = rootToolExposureGate,
        dispatchRepository = rootDispatchRepository,
        locatorCipher = locatorCipher,
    )

    private val activeSessions = ConcurrentHashMap<Authority, ManagedRootSessionState>()
    private val retiringSessions = ConcurrentHashMap<Authority, ManagedRootSessionState>()

    /**
     * Close/disable fencing for in-flight acquisitions. `close()` and a disable both bump the
     * per-authority generation under the coordinator lock, so a handshake that returns afterwards
     * can neither complete nor attach. The lock never wraps an RPC or a transport close.
     */
    private var acquisitionsClosed = false

    /**
     * Live manager-authorization observation. A revocation does not kill the running UID0 child,
     * so the coordinator asks the *service* (already authorized, non-interactive, read-only) for
     * the manager's persistent policy. No new `su` request is issued from this app.
     */
    internal var managerObservationIntervalMs: Long = 3_000L
    private val managerObserverRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var managerObserverThread: Thread? = null

    /** Last observation for diagnostics/tests: `RESULT:reason` or `unavailable`. */
    @Volatile
    var lastManagerObservation: String? = null
        private set

    /**
     * Bound authorization observation for the live session. Only a GRANTED value may allow
     * dispatch; every other state (unknown, unsupported, unreadable, mismatched identity) blocks
     * new work and retires the session.
     */
    private val managerAuthorization = java.util.concurrent.atomic.AtomicReference<RootManagerAuthorization?>(null)

    /** Last bounded passive observation of the live Shizuku authorization. */
    @Volatile
    private var lastShizukuFacts: RootShizukuLiveFacts? = null

    private val shizukuWatcher = RootShizukuAuthorizationWatcher(
        intervalMs = { shizukuObservationIntervalMs },
        currentSession = { activeSessions[Authority.SHIZUKU_ROOT] },
        currentRecord = {
            runCatching { rootAuthorityRepository.get(Authority.SHIZUKU_ROOT) }.getOrNull()
        },
        isClosed = { acquisitionsClosed },
        sample = { RootShizukuLiveSampler.sample(shizukuObservationTimeoutMs) },
        applyDecision = { active, record, facts ->
            shizukuDenialFence.applyObserved(
                capturedTransport = active.transport,
                capturedIdentity = active.session.identity,
                recordRevision = record.revision,
                recordEpoch = record.identityEpoch,
                facts = facts,
                closed = { acquisitionsClosed },
                currentTransport = { activeSessions[Authority.SHIZUKU_ROOT]?.transport },
                currentIdentity = { activeSessions[Authority.SHIZUKU_ROOT]?.session?.identity },
                currentStamp = {
                    runCatching { rootAuthorityRepository.get(Authority.SHIZUKU_ROOT) }
                        .getOrNull()?.let { current -> current.revision to current.identityEpoch }
                },
            )
        },
    )

    internal var shizukuObservationIntervalMs: Long = 3_000L

    internal var shizukuObservationTimeoutMs: Long = 1_500L

    private val shizukuDenialFence = RootShizukuPlatformDenialFence(
        lock = this,
        isClosed = { acquisitionsClosed },
        record = { rootAuthorityRepository.get(Authority.SHIZUKU_ROOT) },
        invalidateAcquisitions = { invalidateAcquisitions(Authority.SHIZUKU_ROOT) },
        hostGate = { activeSessions[Authority.SHIZUKU_ROOT]?.hostControlGate },
        markDegraded = { revision, epoch ->
            runCatching {
                rootAuthorityRepository.markDegraded(Authority.SHIZUKU_ROOT, revision, epoch)
            }
        },
        detach = { reason -> detachSession(Authority.SHIZUKU_ROOT, reason) },
    )

    private val managerWatcher = RootManagerAuthorizationWatcher(
        intervalMs = { managerObservationIntervalMs },
        currentSession = { activeSessions[Authority.ROOT_DIRECT] },
        currentRecord = {
            runCatching { rootAuthorityRepository.get(Authority.ROOT_DIRECT) }.getOrNull()
        },
        onObservation = { lastManagerObservation = it },
        isClosed = { acquisitionsClosed },
        applyObservation = { identity, transport, record, decision, reason ->
            // Identity/transport/record/closed validation, decision write and retire planning are
            // one atomic step under the coordinator lock; the RPC happened outside it.
            synchronized(this) {
                if (acquisitionsClosed) return@synchronized false
                val current = activeSessions[Authority.ROOT_DIRECT]
                if (current == null || current.session.identity != identity ||
                    current.transport !== transport
                ) {
                    return@synchronized false
                }
                val live = runCatching { rootAuthorityRepository.get(Authority.ROOT_DIRECT) }.getOrNull()
                if (live == null || live.revision != record.revision ||
                    live.identityEpoch != record.identityEpoch
                ) {
                    return@synchronized false
                }
                if (decision == RootManagerAuthorization.GRANTED) {
                    managerAuthorization.set(RootManagerAuthorization.GRANTED)
                    return@synchronized true
                }
                managerAuthorization.set(null)
                runCatching {
                    rootAuthorityRepository.markDegraded(
                        Authority.ROOT_DIRECT,
                        live.revision,
                        live.identityEpoch,
                    )
                }
                detachSession(Authority.ROOT_DIRECT, "manager_authorization_${reason.take(32)}")
                false
            }
        },
    )

    private val acquisitionGenerations = HashMap<Authority, Long>()

    private fun nextAcquisitionGeneration(authority: Authority): Long? = synchronized(this) {
        if (acquisitionsClosed) return null
        val next = (acquisitionGenerations[authority] ?: 0L) + 1L
        acquisitionGenerations[authority] = next
        next
    }

    /** Invalidates in-flight acquisitions; callers cancel the connector outside the lock. */
    private fun invalidateAcquisitions(authority: Authority? = null): Unit = synchronized(this) {
        if (authority == null) {
            acquisitionsClosed = true
            acquisitionGenerations.keys.toList().forEach { key ->
                acquisitionGenerations[key] = (acquisitionGenerations[key] ?: 0L) + 1L
            }
        } else {
            acquisitionGenerations[authority] = (acquisitionGenerations[authority] ?: 0L) + 1L
        }
    }


    @Volatile
    var lastRecoveredRequestIds: List<String> = emptyList()
        private set

    /** Admit only a fresh Run under the same controller lock as session replacement. */
    @Synchronized
    fun admitNewRun(record: runtime.mobileagent.domain.RunRecord): runtime.mobileagent.domain.RunRecord =
        runtime.mobileagent.data.RootRunAdmission(db).admitOrCreateOrdinaryRun(
            record = record,
            selectedAuthority = authorityPolicyRepository.getPolicy().selectedAuthority,
            resolveExecution = { authority -> resolveExecutionStamp(authority, requireShellConsent = false) },
        )

    fun reconcileStartupRecovery(): List<String> {
        val excluded = rootDispatchRepository.activePermitRequestIds()
        val recovered = rootToolExposureGate.reconcileStartupUncertainPermits(excluded)
        lastRecoveredRequestIds = recovered
        return recovered
    }

    fun registerBackends(
        shellBackends: MutableMap<ElevatedAuthority, ShellExecutor>,
        workspaceBackends: MutableMap<ElevatedAuthority, WorkspaceBackend>,
        privilegedWorkspaceProviders: MutableMap<Authority, PrivilegedWorkspaceProvider>,
        fullDeviceGrantStore: FullDeviceFilesGrantStore,
    ) {
        // Production admission is intentionally closed. OS-pinned bootstrap, retained
        // workspace FD lifecycle, atomic mutation and Run/approval UI must be wired and
        // reviewed together before these adapters can enter the tool factory.
        // Keep the isolated adapters available to deterministic regression tests only.
    }

    fun setUserIntent(
        authority: Authority,
        enabled: Boolean,
        authorityManager: AuthorityManager,
        dangerousMode: DangerousMode,
        shizukuState: ShizukuAuthorityState?,
    ) {
        require(authority.isRootAuthority) { "Expected Root authority: $authority" }
        // The manager's intent CAS advances the policy version, so it must run before the consent is
        // bound; otherwise the fresh consent immediately references a stale policy version and the
        // execution stamp silently becomes null.
        val elevated = toElevatedRoot(authority)
        check(authorityManager.setUserIntent(elevated, enabled))
        val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
        if (enabled) {
            enableConsentInRepo(authority, policyVersion)
        } else {
            detachSession(authority, "user_intent_disabled")
            val current = rootAuthorityRepository.get(authority)
            if (current.consent.enabled) {
                rootAuthorityRepository.revokeRoot(authority, current.revision)
            }
        }
        refreshLiveSessions(shizukuState)
        if (enabled) requestSessionFromForeground(authority, shizukuState)
        syncAuthorityManagerStates(authorityManager, shizukuState)
    }

    /**
     * Explicit per-channel Root Shell consent mutation.
     * Enabling [Authority.ROOT_DIRECT] or [Authority.SHIZUKU_ROOT] shell access requires explicit
     * user confirmation (`userConfirmed == true`) for that specific channel and is never granted
     * automatically by global [DangerousMode].
     */
    fun setRootShellConsent(
        authority: Authority,
        enabled: Boolean,
        userConfirmed: Boolean,
        authorityManager: AuthorityManager? = null,
        shizukuState: ShizukuAuthorityState? = null,
    ): RootAuthorityRecord? {
        require(authority.isRootAuthority) { "Expected Root authority: $authority" }
        if (enabled && !userConfirmed) return null
        val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
        val record = rootAuthorityRepository.get(authority)
        if (!record.consent.enabled || record.consent.policyVersion != policyVersion) return null
        val updated = rootAuthorityRepository.setShellConsent(
            authority,
            record.revision,
            enabled,
            policyVersion,
        )
        if (authorityManager != null) {
            syncAuthorityManagerStates(authorityManager, shizukuState)
        }
        return updated
    }

    private fun enableConsentInRepo(
        authority: Authority,
        policyVersion: Long,
    ) {
        var current = rootAuthorityRepository.get(authority)
        if (!current.consent.enabled || current.consent.policyVersion != policyVersion) {
            current = rootAuthorityRepository.confirmRoot(authority, current.revision, policyVersion)
                ?: rootAuthorityRepository.get(authority)
        }
    }

    fun onDangerousModeUpdated(
        mode: DangerousMode,
        authorityManager: AuthorityManager,
        shizukuState: ShizukuAuthorityState?,
    ) {
        // Global Dangerous Mode never auto-enables per-channel Root Shell consent.
        syncAuthorityManagerStates(authorityManager, shizukuState)
    }

    /** Passive observation must never start su or reset a live session's sequence/handles. */
    fun refreshLiveSessions(shizukuState: ShizukuAuthorityState?) {
        // Explicit state from RuntimeIntegration: record it and fence on an explicit denial. The
        // passive watcher covers the no-event path; neither requests permission/binds a service/su.
        if (shizukuDenialFence.applyExplicitState(shizukuState)) return
        for ((authority, _) in ROOT_AUTHORITY_PAIRS) {
            val record = rootAuthorityRepository.get(authority)
            if (shizukuDenialFence.needsDetach(record)) {
                detachSession(authority, "consent_or_session_unavailable")
            }
        }
    }

    /** Only an explicit foreground request for this channel may acquire a new service. */
    fun requestSessionFromForeground(authority: Authority, shizukuState: ShizukuAuthorityState?): Boolean {
        val acquisition = RootForegroundAcquisition(
            connector = sessionConnector,
            currentRecord = { rootAuthorityRepository.get(authority) },
            hasActiveSession = { activeSessions.containsKey(authority) },
            isReady = { isAuthorityReadyForDispatch(authority) },
            advanceEpoch = { revision -> rootAuthorityRepository.advanceIdentityEpoch(authority, revision) },
            nextGeneration = { nextAcquisitionGeneration(authority) },
            currentGeneration = { synchronized(this) { acquisitionGenerations[authority] } },
            isClosed = { acquisitionsClosed },
            completeAndAttach = { generation, acquired ->
                synchronized(this) {
                    if (acquisitionsClosed || acquisitionGenerations[authority] != generation ||
                        !rootAuthorityRepository.get(authority).consent.enabled ||
                        !sessionConnector.completeAcquisition(authority, acquired.attemptToken)
                    ) {
                        false
                    } else {
                        attachVerifiedRootSession(
                            authority,
                            acquired.identity,
                            acquired.caller,
                            acquired.transport,
                            acquired.controlPlane,
                        ) != null
                    }
                }
            },
            abort = { acquired -> sessionConnector.abortAcquisition(authority, acquired.attemptToken) },
            verifyAuthorization = { observeManagerAuthorizationBound() },
        )
        return acquisition.request(authority, appInstanceId, shizukuState)
    }

    @Synchronized
    fun attachVerifiedRootSession(
        authority: Authority,
        identity: PrivilegedSessionIdentity,
        caller: RootCallerObservation,
        transport: RootChannelTransport? = null,
        observedBoundary: RootControlPlaneBoundary? = null,
    ): RootServiceSession? {
        if (acquisitionsClosed) return null
        require(authority.isRootAuthority) { "Expected Root authority: $authority" }
        if (identity.authority != authority || identity.privilegeTier != PrivilegeTier.ROOT || identity.serviceUid != 0) {
            return null
        }
        if (!caller.matches(identity) || identity.protocolVersion != PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION) return null
        val resolvedTransport = transport ?: return null
        val boundary = observedBoundary ?: observeControlPlaneBoundary() ?: return null
        val record = rootAuthorityRepository.get(authority)
        if (!record.consent.enabled || record.degraded || record.identityEpoch != identity.identityEpoch) return null
        activeSessions[authority]?.let { existing ->
            return if (existing.session.identity == identity && existing.transport === resolvedTransport)
                existing.session else null
        }
        val session = RootServiceSession(
            identity = identity,
            controlPlane = boundary,
        )
        val lifecycle = RootServiceLifecycleCoordinator(
            session = session,
            onStopService = {
                activeSessions[authority]?.takeIf { it.session === session }?.let { activeSessions.remove(authority, it) }
            },
        )
        RootCoordinatorQuiescenceSupport.clearVerifiedSnapshot(authority)
        if (authority == Authority.ROOT_DIRECT) {
            managerAuthorization.set(null)
            armManagerObserver()
        }
        if (authority == Authority.SHIZUKU_ROOT) shizukuWatcher.start()
        activeSessions.put(
            authority,
            ManagedRootSessionState(
                session = session,
                lifecycle = lifecycle,
                caller = caller,
                transport = resolvedTransport,
                hostControlGate = RootHostControlGate(identity),
            ),
        )?.let { previous ->
            runCatching { previous.transport.close() }
        }
        return session
    }

    fun detachSession(authority: Authority, reason: String) {
        if (authority == Authority.ROOT_DIRECT) managerAuthorization.set(null)
        invalidateAcquisitions(authority)
        RootCoordinatorQuiescenceSupport.detachAndRetireSession(
            authority = authority,
            reason = reason,
            forceCloseImmediately = false,
            activeSessions = activeSessions,
            retiringSessions = retiringSessions,
            dispatchBridge = dispatchBridge,
            dispatchRepository = rootDispatchRepository,
        )
    }

    fun acknowledgeDegradedRecovery(
        authority: Authority,
        userConfirmed: Boolean,
        authorityManager: AuthorityManager? = null,
        shizukuState: ShizukuAuthorityState? = null,
    ): RootAuthorityRecord? {
        val recovered = RootCoordinatorQuiescenceSupport.acknowledgeDegradedRecovery(
            db = db,
            authority = authority,
            userConfirmed = userConfirmed,
            activeSessions = activeSessions,
            retiringSessions = retiringSessions,
            dispatchBridge = dispatchBridge,
            dispatchRepository = rootDispatchRepository,
            authorityRepository = rootAuthorityRepository,
        )
        if (recovered != null && authorityManager != null) {
            syncAuthorityManagerStates(authorityManager, shizukuState)
        }
        return recovered
    }

    /**
     * Periodically asks the live UID0 service whether the manager still grants this app root.
     *
     * F7 fencing: the session identity is captured *before* the probe, and any state change is
     * applied under the coordinator lock only if the very same session (identity + transport) is
     * still active. A slow answer can therefore never retire a replacement session, and a close
     * during the probe stops the loop before it writes anything.
     */
    /** Thin delegate kept so external probes can drive one real observation. */
    private fun armManagerObserver() = managerWatcher.start()

    private fun observeManagerAuthorizationBound(): Boolean = managerWatcher.observeOnce()

    private fun stopManagerObserver() = managerWatcher.stop()

    override fun close() {
        stopManagerObserver()
        shizukuWatcher.stop()
        invalidateAcquisitions(null)
        // Cancelling the connector is an RPC/close path: never inside the coordinator lock.
        ROOT_AUTHORITY_PAIRS.forEach { (authority, _) ->
            runCatching { sessionConnector.cancelPendingAcquisition(authority) }
        }
        for ((authority, _) in ROOT_AUTHORITY_PAIRS) {
            RootCoordinatorQuiescenceSupport.detachAndRetireSession(
                authority = authority,
                reason = "coordinator_closed",
                forceCloseImmediately = true,
                activeSessions = activeSessions,
                retiringSessions = retiringSessions,
                dispatchBridge = dispatchBridge,
                dispatchRepository = rootDispatchRepository,
            )
        }
    }

    fun markDegradedUncertainDescendants(authority: Authority) {
        if (!authority.isRootAuthority) return
        var current = rootAuthorityRepository.get(authority)
        if (current.identityEpoch == 0L) {
            current = rootAuthorityRepository.advanceIdentityEpoch(authority, current.revision)
                ?: rootAuthorityRepository.get(authority)
        }
        if (current.identityEpoch > 0L) {
            rootAuthorityRepository.markDegraded(authority, current.revision, current.identityEpoch)
        }
    }

    fun isAuthorityReadyForDispatch(authority: Authority): Boolean {
        if (!authority.isRootAuthority) return false
        val shizukuDenied = authority == Authority.SHIZUKU_ROOT && shizukuDenialFence.lastDenies()
        return RootCoordinatorReadiness.readyForDispatch(
            authority = authority,
            record = rootAuthorityRepository.get(authority),
            active = activeSessions[authority],
            authorization = managerAuthorization.get(),
            shizukuDenied = shizukuDenied,
            livePolicyVersion = authorityPolicyRepository.getPolicy().policyVersion,
        )
    }

    internal fun executeReceiptedFileGetMetadata(
        authority: Authority,
        workspaceRootPath: Path,
        args: RootTypedArguments.FileGetMetadata,
        invocationId: String? = null,
    ): RootFileOperationResult<RootFileMetadataSnapshot>? {
        return invocationId?.takeIf { it.isNotBlank() }?.let { exactInvocation ->
        val resp = dispatchFileOperation(
            authority = authority,
            invocationId = exactInvocation,
            typedArgs = args,
            budget = RootExecutionBudget(),
            payloadBytes = ByteArray(0),
        ) ?: return@let null
        RootCoordinatorPresentation.normalizeFileResultWorkspaceId(resp.toFileResult(), args.workspaceId)
        }
    }

    internal fun executeReceiptedFileWriteText(
        authority: Authority,
        workspaceRootPath: Path,
        args: RootTypedArguments.FileWriteText,
        utf8Bytes: ByteArray,
        invocationId: String? = null,
    ): RootFileOperationResult<RootFileMetadataSnapshot>? {
        return invocationId?.takeIf { it.isNotBlank() }?.let { exactInvocation ->
        val resp = dispatchFileOperation(
            authority = authority,
            invocationId = exactInvocation,
            typedArgs = args,
            budget = RootExecutionBudget(),
            payloadBytes = utf8Bytes,
        ) ?: return@let null
        RootCoordinatorPresentation.normalizeFileResultWorkspaceId(resp.toFileResult(), args.workspaceId)
        }
    }

    fun executeControlledShell(
        authority: Authority,
        request: ShellExecRequest,
    ): ShellExecResult {
        val startedAt = System.currentTimeMillis()
        val budget = RootExecutionBudget(
            timeoutMs = request.timeoutMs.coerceIn(100L, 120_000L),
            maxOutputBytes = request.maxOutputBytes.toInt().coerceIn(1024, 262_144),
        )
        val resp = dispatchShellOperation(authority, request, budget)
        val outcome = resp?.shellOutcome?.toOutcome() ?: return ShellExecResult(
            status = ShellExecutionStatus.FAILED,
            authority = authority,
            requestId = request.requestId,
            error = ToolError(ToolErrorCode.AUTHORITY_NOT_GRANTED),
        )
        val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        return ShellExecResult(
            status = outcome.status,
            exitCode = outcome.exitCode,
            stdout = outcome.stdout,
            stderr = outcome.stderr,
            stdoutTruncated = outcome.stdoutTruncated,
            stderrTruncated = outcome.stderrTruncated,
            timedOut = outcome.timedOut,
            cancelled = outcome.cancelled,
            authority = authority,
            durationMs = duration,
            requestId = request.requestId,
            error = mapShellOutcomeError(outcome.status),
        )
    }

    fun cancelControlledShell(authority: Authority, requestId: String): Boolean {
        val execution = rootDispatchRepository.executionForInvocation(authority, requestId) ?: return false
        return cancellationRoute(execution)(requestId)
    }

    internal fun cancellationRoute(execution: RootExecutionStamp): (String) -> Boolean {
        val active = activeSessions[execution.identity.authority]
        return RootCancelInvocationRequest.dispatchCancellationRoute(
            activeIdentity = active?.session?.identity,
            transport = active?.transport,
            execution = execution,
            onUnconfirmed = { invocationId ->
                rootDispatchRepository.cancelUnconfirmedInvocation(execution, invocationId)
            },
        )
    }

    fun currentExecutionStamp(
        authority: Authority,
        requireShellConsent: Boolean = false,
    ): RootExecutionStamp? = resolveExecutionStamp(authority, requireShellConsent)

    @Synchronized
    fun admitWorkspaceFromForeground(
        authority: Authority, path: String, writable: Boolean, metadata: Boolean, shell: Boolean, agentId: String?,
    ): RootForegroundWorkspaceGrant? = activeSessions[authority]?.let { active ->
        resolveExecutionStamp(authority, requireShellConsent = shell)?.let { execution ->
            RootForegroundAdmissionController(db, locatorCipher, dispatchBridge::rememberConfirmedWorkspace)
                .admit(execution, active.transport, path, writable, metadata, shell, agentId)
        }
    }

    @Synchronized
    fun admitDraftWorkspaceGrants(
        authority: Authority,
        workspaceId: String,
        agentId: String,
        capabilities: Set<CapabilityId>,
    ): List<CapabilityGrant>? = resolveExecutionStamp(
        authority,
        requireShellConsent = CapabilityId(CapabilityId.SHELL_EXECUTE) in capabilities,
    )?.let { execution ->
        RootForegroundAdmissionController(db, locatorCipher, dispatchBridge::rememberConfirmedWorkspace)
            .admitDraftGrants(execution, workspaceId, agentId, capabilities)
    }

    /** New snapshots are sealed under the same verified-session lock as new Run admission. */
    @Synchronized
    fun <T> sealNewSnapshot(
        authority: Authority, snapshotId: String, agentId: String, grantIds: Set<String>,
        createCanonicalSnapshot: () -> T,
    ): T {
        val execution = requireNotNull(resolveExecutionStamp(authority, requireShellConsent = false))
        return runtime.mobileagent.data.RootSnapshotGrantRepository(db).createNewSnapshot(
            snapshotId, agentId, grantIds, execution, createCanonicalSnapshot,
        )
    }

    fun approveShellInvocation(
        authority: Authority,
        runId: String,
        request: ShellExecRequest,
        grantId: String,
        workspaceId: String,
    ): RootApprovalBinding? = resolveExecutionStamp(authority, requireShellConsent = true)?.let { execution ->
        val typedArgs = RootTypedArguments.ShellExec(
            workspaceId = workspaceId,
            command = request.command,
            cwdRelativePath = null,
        )
        runCatching {
            dispatchBridge.approveToolInvocation(
                execution = execution,
                runId = runId,
                invocationId = request.requestId,
                callId = request.callId,
                snapshotId = request.snapshotId,
                grantId = grantId,
                typedArgs = typedArgs,
                configSnapshotHash = request.configSnapshotHash,
            )
        }.getOrNull()
    }

    fun approveFileInvocation(
        authority: Authority,
        runId: String,
        invocationId: String,
        callId: String,
        snapshotId: String,
        grantId: String,
        typedArgs: RootTypedArguments,
        configSnapshotHash: String = typedArgs.canonicalSha256(),
        sessionIdentity: String? = null,
    ): RootApprovalBinding? = resolveExecutionStamp(authority, requireShellConsent = typedArgs is RootTypedArguments.ShellExec)?.let { execution ->
        runCatching {
            dispatchBridge.approveToolInvocation(
                execution = execution,
                runId = runId,
                invocationId = invocationId,
                callId = callId,
                snapshotId = snapshotId,
                grantId = grantId,
                typedArgs = typedArgs,
                configSnapshotHash = configSnapshotHash,
                sessionIdentity = sessionIdentity,
            )
        }.getOrNull()
    }

    fun createWorkspaceFileExecutor(
        workspaceRootPath: Path,
        confirmedMountIds: Set<Long> = emptySet(),
    ): RootWorkspaceFileExecutor? {
        val boundary = observeControlPlaneBoundary() ?: return null
        val rootIdentity = fdOps.inspectDirectoryIdentityNoFollow(workspaceRootPath) ?: return null
        if (!rootIdentity.isDirectory || rootIdentity.isSymlink) return null
        val driver = AndroidRootPosixFileDriver(
            workspaceRootPath = workspaceRootPath,
            fdOps = fdOps,
        )
        return RootWorkspaceFileExecutor(
            workspaceRootIdentity = rootIdentity,
            controlPlane = boundary,
            driver = driver,
            confirmedMountIds = confirmedMountIds,
        )
    }

    private fun resolveExecutionStamp(
        authority: Authority,
        requireShellConsent: Boolean,
    ): RootExecutionStamp? = RootCoordinatorReadiness.resolveExecutionStamp(
        authority = authority,
        active = activeSessions[authority],
        loadRecord = rootAuthorityRepository::get,
        policy = authorityPolicyRepository.getPolicy(),
        requireShellConsent = requireShellConsent,
    )

    private fun dispatchShellOperation(
        authority: Authority,
        request: ShellExecRequest,
        budget: RootExecutionBudget,
    ): RootServiceExecuteResponse? = activeSessions[authority]?.let { active ->
        resolveExecutionStamp(authority, requireShellConsent = true)?.let { execution ->
            dispatchBridge.claimApprovedShellDispatch(execution, active.transport, request, budget)?.let { prepared ->
                dispatchBridge.submitAndSettle(
                    prepared = prepared,
                    hostGate = active.hostControlGate,
                    transport = active.transport,
                    liveIdentity = active.session.identity,
                    payloadBytes = ByteArray(0),
                    onDegradeRequired = { markDegradedUncertainDescendants(authority) },
                )
            }
        }
    }

    /** Invocation identity comes only from Runtime routing, never from model arguments. */
    internal fun executeFileInvocation(
        authority: Authority,
        invocationId: String,
        args: RootTypedArguments,
        payload: ByteArray = ByteArray(0),
        deferDisclosure: Boolean = false,
    ): RootServiceExecuteResponse? = dispatchFileOperation(
        authority, invocationId, args, RootExecutionBudget(), payload, deferDisclosure,
    )

    private fun dispatchFileOperation(
        authority: Authority,
        invocationId: String,
        typedArgs: RootTypedArguments,
        budget: RootExecutionBudget,
        payloadBytes: ByteArray,
        deferDisclosure: Boolean = false,
    ): RootServiceExecuteResponse? = activeSessions[authority]?.let { active ->
        resolveExecutionStamp(authority, requireShellConsent = typedArgs is RootTypedArguments.ShellExec)?.let { execution ->
            dispatchBridge.claimApprovedFileDispatch(execution, active.transport, typedArgs, budget, invocationId)?.let { prepared ->
                dispatchBridge.submitAndSettle(
                    prepared = prepared,
                    hostGate = active.hostControlGate,
                    transport = active.transport,
                    liveIdentity = active.session.identity,
                    payloadBytes = payloadBytes,
                    onDegradeRequired = { markDegradedUncertainDescendants(authority) },
                    deferDisclosure = deferDisclosure,
                )
            }
        }
    }

    internal fun authorizeResultDisclosure(
        authority: Authority,
        invocationId: String,
        disclosure: runtime.mobileagent.skills.ResultDisclosure,
    ): Boolean {
        if (!invocationId.startsWith("rchild.")) return false
        val requireShell = rootDispatchRepository.requiresShellForInvocation(authority, invocationId)
        val execution = resolveExecutionStamp(authority, requireShellConsent = requireShell) ?: return false
        return when (disclosure) {
            runtime.mobileagent.skills.ResultDisclosure.DEFERRED ->
                rootDispatchRepository.discloseDeferredInvocation(execution, invocationId)
            runtime.mobileagent.skills.ResultDisclosure.REPLAY ->
                rootDispatchRepository.authorizeReplayInvocation(execution, invocationId)
        }
    }

    internal fun precheckResultDisclosure(authority: Authority, invocationId: String): Boolean {
        if (!invocationId.startsWith("rchild.")) return false
        val requireShell = rootDispatchRepository.requiresShellForInvocation(authority, invocationId)
        val execution = resolveExecutionStamp(authority, requireShellConsent = requireShell) ?: return false
        return rootDispatchRepository.precheckDeferredInvocation(execution, invocationId)
    }

    internal fun withholdDeferredResult(authority: Authority, invocationId: String) {
        val execution = rootDispatchRepository.executionForInvocation(authority, invocationId) ?: return
        rootDispatchRepository.withholdDeferredInvocation(execution, invocationId)
    }

    fun syncAuthorityManagerStates(
        authorityManager: AuthorityManager,
        shizukuState: ShizukuAuthorityState?,
    ) {
        val selected = authorityManager.state.value.selectedAuthority ?: Authority.NONE
        for ((authority, elevated) in ROOT_AUTHORITY_PAIRS) {
            val record = rootAuthorityRepository.get(authority)
            val model = buildUiModel(
                authority = authority,
                selectedAuthority = selected,
                dangerousMode = DangerousMode.DISABLED,
                shizukuState = shizukuState,
                record = record,
            )
            val live = activeSessions[authority]?.session?.identity
            authorityManager.updateRootRuntimeState(
                authority = elevated,
                configured = record.consent.enabled,
                grant = RootCoordinatorPresentation.mapPlatformGrant(record, model),
                availability = RootCoordinatorPresentation.mapAvailability(model),
                connection = RootCoordinatorPresentation.mapConnection(model),
                identity = live?.let { "uid=${it.serviceUid},epoch=${it.identityEpoch}" },
            )
        }
    }

    fun enrichSettingsSnapshot(
        authorityManager: AuthorityManager,
        selected: Authority,
        appPrivateAvailable: Boolean,
        shizuku: SettingsAuthorityProviderState,
        wired: SettingsAuthorityProviderState,
        saf: SettingsSafGrantState,
        dangerousMode: DangerousMode,
        durableDangerousMode: DangerousMode,
        dangerousModeBuildAllowed: Boolean,
        dangerousModeBuildKnown: Boolean,
        dangerousModeReason: String,
        revision: Long,
    ): SettingsAuthoritySnapshot = SettingsAuthoritySnapshot(
        selectedAuthority = selected,
        appPrivateAvailable = appPrivateAvailable,
        shizuku = shizuku,
        wiredAdb = wired,
        rootDirect = buildSettingsProviderState(Authority.ROOT_DIRECT, authorityManager),
        shizukuRoot = buildSettingsProviderState(Authority.SHIZUKU_ROOT, authorityManager),
        saf = saf,
        dangerousMode = dangerousMode,
        durableDangerousMode = durableDangerousMode,
        dangerousModeBuildAllowed = dangerousModeBuildAllowed,
        dangerousModeBuildKnown = dangerousModeBuildKnown,
        dangerousModeReason = dangerousModeReason,
        revision = revision,
    )

    fun buildSettingsProviderState(
        authority: Authority,
        authorityManager: AuthorityManager,
    ): SettingsAuthorityProviderState = RootCoordinatorPresentation.buildSettingsProviderState(
        authority = authority,
        elevated = toElevatedRoot(authority),
        authorityManager = authorityManager,
        record = rootAuthorityRepository.get(authority),
    )

    fun buildUiModel(
        authority: Authority,
        selectedAuthority: Authority,
        dangerousMode: DangerousMode,
        shizukuState: ShizukuAuthorityState?,
        record: RootAuthorityRecord = rootAuthorityRepository.get(authority),
    ): RootChannelUiModel = RootCoordinatorPresentation.buildUiModel(
        authority = authority,
        selectedAuthority = selectedAuthority,
        dangerousMode = dangerousMode,
        shizukuState = shizukuState,
        record = record,
        liveIdentity = activeSessions[authority]?.session?.identity,
        livePolicyVersion = authorityPolicyRepository.getPolicy().policyVersion,
    )

    internal fun observeControlPlaneBoundary(): RootControlPlaneBoundary? =
        RootCoordinatorPresentation.observeControlPlaneBoundary(appContext, fdOps, controlPlaneBoundaryOverride)

    companion object {
        private val ROOT_AUTHORITY_PAIRS = listOf(
            Authority.ROOT_DIRECT to ElevatedAuthority.ROOT_DIRECT,
            Authority.SHIZUKU_ROOT to ElevatedAuthority.SHIZUKU_ROOT,
        )

        private fun toElevatedRoot(authority: Authority): ElevatedAuthority = when (authority) {
            Authority.ROOT_DIRECT -> ElevatedAuthority.ROOT_DIRECT
            Authority.SHIZUKU_ROOT -> ElevatedAuthority.SHIZUKU_ROOT
            else -> error("Expected Root authority: $authority")
        }

        private fun mapShellOutcomeError(status: ShellExecutionStatus): ToolError? =
            RootCoordinatorPresentation.mapShellOutcomeError(status)
    }
}
