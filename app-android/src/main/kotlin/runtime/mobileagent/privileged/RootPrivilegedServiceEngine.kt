// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootConfinementRejection
import runtime.mobileagent.domain.RootControlEnvelope
import runtime.mobileagent.domain.RootControlRevokeResult
import runtime.mobileagent.domain.RootControlStatusSnapshot
import runtime.mobileagent.domain.RootControlSubmitResult
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootFileMetadataSnapshot
import runtime.mobileagent.domain.RootFileOperationResult
import runtime.mobileagent.domain.RootNonAcceptanceProof
import runtime.mobileagent.domain.RootServiceLifecycleCoordinator
import runtime.mobileagent.domain.RootServiceSession
import runtime.mobileagent.domain.RootServiceSessionState
import runtime.mobileagent.domain.RootServiceExecutionStage
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.domain.RootWorkspaceFileExecutor
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellLimits

data class RootServiceHostBootstrap(
    val expectedAppUid: Int,
    val expectedAndroidUserId: Int,
    val expectedPackageName: String,
    val expectedSigningSha256: String,
    val expectedApkSha256: String,
    val expectedCeDataDir: String,
    val expectedDeDataDir: String,
) {
    override fun toString(): String =
        "RootServiceHostBootstrap(expectedAppUid=$expectedAppUid,expectedAndroidUserId=$expectedAndroidUserId,redacted=true)"
}

@Serializable
data class RootServiceHandshakeRequest(
    val authority: Authority,
    val identityEpoch: Long,
    val appUid: Int,
    val androidUserId: Int,
    val appInstanceId: String,
    val packageName: String,
    val signingSha256: String,
    val apkSha256: String,
    val ceDataDir: String,
    val deDataDir: String,
    @kotlinx.serialization.Required val cancellationProtocolVersion: Int = 1,
) {
    override fun toString(): String =
        "RootServiceHandshakeRequest(authority=$authority,identityEpoch=$identityEpoch,redacted=true)"
}

@Serializable
data class RootServiceHandshakeResponse(
    val accepted: Boolean,
    val identity: PrivilegedSessionIdentity? = null,
    val errorReason: String? = null,
)


@Serializable
data class RootServiceBindWorkspaceRequest(
    val targetServiceInstanceId: String,
    val targetSessionId: String,
    val targetIdentityEpoch: Long,
    val workspaceId: String,
    val workspaceGenerationId: String,
    val workspaceSessionHandle: String,
    val workspaceRootPath: String,
    val confirmedMountIds: Set<Long> = emptySet(),
    val expectedRootDeviceId: Long = 0L,
    val expectedRootInode: Long = 0L,
    val expectedRootMountId: Long = 0L,
    @Serializable(with = RootWorkspaceOriginWireSerializer::class)
    val origin: RootWorkspaceOrigin? = null,
    val recoveryLocator: ByteArray? = null,
) {
    override fun toString(): String =
        "RootServiceBindWorkspaceRequest(workspaceId=$workspaceId,generationId=$workspaceGenerationId,redacted=true)"
}

/** Closed wire adapter; the shared origin model intentionally has no Android transport dependency. */
object RootWorkspaceOriginWireSerializer : KSerializer<RootWorkspaceOrigin> {
    @Serializable
    private data class Wire(
        val workspaceId: String, val authority: Authority, val appInstanceId: String,
        val packageName: String, val appUid: Int, val androidUserId: Int,
        val signingSha256: String, val generationId: String,
    )
    override val descriptor = Wire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: RootWorkspaceOrigin) = encoder.encodeSerializableValue(
        Wire.serializer(), Wire(value.workspaceId, value.authority, value.appInstanceId, value.packageName,
            value.appUid, value.androidUserId, value.signingSha256, value.generationId),
    )
    override fun deserialize(decoder: Decoder): RootWorkspaceOrigin = decoder.decodeSerializableValue(Wire.serializer()).let {
        RootWorkspaceOrigin(it.workspaceId, it.authority, it.appInstanceId, it.packageName,
            it.appUid, it.androidUserId, it.signingSha256, it.generationId)
    }
}

@Serializable
data class RootServiceBindWorkspaceResponse(
    val accepted: Boolean,
    val rootInode: Long? = null,
    val errorReason: String? = null,
    val phase: String? = null,
    val provisionalHandle: String? = null,
    val workspaceId: String? = null,
    val workspaceGenerationId: String? = null,
    val workspaceSessionHandle: String? = null,
    val stagedToken: String? = null,
    val locatorBytes: ByteArray? = null,
    val rootDeviceId: Long? = null,
    val rootMountId: Long? = null,
    val rootOwnerUid: Int? = null,
    val rootKindName: String? = null,
) {
    override fun toString(): String =
        "RootServiceBindWorkspaceResponse(accepted=$accepted,phase=$phase,workspaceId=$workspaceId,redacted=true)"
}

@Serializable
data class RootShellOutcomePayload(
    val statusName: String,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val terminalOutcomeName: String,
    val requiresDegradedEpoch: Boolean = false,
) {
    override fun toString(): String =
        "RootShellOutcomePayload(status=$statusName,exitCode=$exitCode,terminalOutcome=$terminalOutcomeName,redacted=true)"

    fun toOutcome(): RootShellRunOutcome = RootShellRunOutcome(
        status = ShellExecutionStatus.valueOf(statusName),
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = stderrTruncated,
        timedOut = timedOut,
        cancelled = cancelled,
        terminalOutcome = RootServiceTerminalOutcome.valueOf(terminalOutcomeName),
        requiresDegradedEpoch = requiresDegradedEpoch,
    )

    companion object {
        fun fromOutcome(outcome: RootShellRunOutcome): RootShellOutcomePayload = RootShellOutcomePayload(
            statusName = outcome.status.name,
            exitCode = outcome.exitCode,
            stdout = outcome.stdout,
            stderr = outcome.stderr,
            stdoutTruncated = outcome.stdoutTruncated,
            stderrTruncated = outcome.stderrTruncated,
            timedOut = outcome.timedOut,
            cancelled = outcome.cancelled,
            terminalOutcomeName = outcome.terminalOutcome.name,
            requiresDegradedEpoch = outcome.requiresDegradedEpoch,
        )
    }
}

@Serializable
data class RootServiceExecuteResponse(
    val submitStage: String,
    val acceptedStatus: RootControlStatusSnapshot? = null,
    val rejectedProof: RootNonAcceptanceProof? = null,
    val terminalOutcome: RootServiceTerminalOutcome? = null,
    val resultSha256: String? = null,
    val requiresDegradedEpoch: Boolean = false,
    val fileSnapshot: RootFileMetadataSnapshot? = null,
    val fileRejection: RootConfinementRejection? = null,
    val fileFailureStage: String? = null,
    val fileErrno: String? = null,
    val fileSelinuxDenied: Boolean = false,
    val fileUnsupportedAttr: String? = null,
    val fileCleanedTemp: Boolean = true,
    /** Bounded provider-neutral model JSON; `resultSha256` binds this exact string. */
    val fileResultJson: String? = null,
    val shellOutcome: RootShellOutcomePayload? = null,
    val errorReason: String? = null,
    val remoteQuiescent: Boolean = false,
) {
    override fun toString(): String =
        "RootServiceExecuteResponse(submitStage=$submitStage,terminalOutcome=$terminalOutcome,redacted=true)"

    fun toFileResult(): RootFileOperationResult<RootFileMetadataSnapshot> = when {
        fileSnapshot != null -> RootFileOperationResult.Succeeded(
            value = fileSnapshot,
            resultDigestSha256 = resultSha256 ?: RootPrivilegedServiceEngine.sha256Hex(fileSnapshot.version),
        )
        fileFailureStage != null -> RootFileOperationResult.PostMutationFailure(
            reason = fileFailureStage,
            rejection = fileRejection,
            terminalOutcome = terminalOutcome ?: RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
            requiresDegradedEpoch = requiresDegradedEpoch,
        )
        fileErrno != null -> RootFileOperationResult.PermissionDenied(
            selinuxConfirmed = fileSelinuxDenied,
            errnoName = fileErrno,
        )
        fileUnsupportedAttr != null -> RootFileOperationResult.UnsupportedAttributePreservation(
            attribute = fileUnsupportedAttr,
        )
        fileRejection != null -> RootFileOperationResult.Rejected(
            rejection = fileRejection,
            reason = errorReason ?: fileRejection.name,
        )
        else -> RootFileOperationResult.Rejected(
            rejection = RootConfinementRejection.SPECIAL_NODE_REJECTED,
            reason = errorReason ?: "stat_failed",
        )
    }
}

/**
 * Controlled `UID 0` service execution engine.
 *
 * Owns the active [RootServiceSession], [RootServiceLifecycleCoordinator], [PosixFdOperations],
 * [AndroidRootPosixFileDriver], [RootWorkspaceFileExecutor], and [RootShellCommandRunner] inside
 * the `UID 0` process (`RootPrivilegedProcessMain` for `ROOT_DIRECT` or `RootShizukuUserService`
 * for `SHIZUKU_ROOT`).
 */
class RootPrivilegedServiceEngine(
    private val fdOps: PosixFdOperations = AndroidOsPosixFdOperations(),
    private val shellRunner: RootShellCommandRunner = Uid0ProcessGroupShellRunner(),
    private val serviceUidProvider: () -> Int = { android.os.Process.myUid() },
    private val controlPlaneOverride: RootControlPlaneBoundary? = null,
    private val onStopService: () -> Unit = {},
    private val hostBootstrapProvider: ((observedCallingUid: Int) -> RootServiceHostBootstrap?)? = null,
    private val lifecycleConfig: RootServiceLifecycleConfig = RootServiceLifecycleConfig(),
    /** Test seam only: production reads the manager policy from the platform. */
    private val managerAuthorizationOverride: (() -> RootManagerAuthorization)? = null,
) : AutoCloseable {

    @Volatile
    var activeSession: RootServiceSession? = null
        private set

    @Volatile
    private var activeLifecycle: RootServiceLifecycleCoordinator? = null

    @Volatile
    private var verifiedCaller: RootCallerObservation? = null

    @Volatile
    private var activeControlPlane: RootControlPlaneBoundary? = null

    @Volatile
    private var pinnedHostBootstrap: RootServiceHostBootstrap? = null

    @Volatile
    private var closed = false
    private val elapsedOriginNanos = System.nanoTime()
    private var shutdownScheduled = false

    private val workspaceExecutors = ConcurrentHashMap<String, BoundWorkspaceExecutor>()
    private val workspaceSelection = RootServiceWorkspaceSelection(fdOps, workspaceExecutors)

    @Volatile
    private var lifecycleWatchdog: RootServiceLifecycleWatchdog? = null

    private val acceptedEnvelopes = ConcurrentHashMap<String, Pair<RootServiceSession, RootControlSubmitResult.Accepted>>()
    private val inFlightCancelAliases = ConcurrentHashMap<String, Set<String>>()
    private val inFlightThreads = ConcurrentHashMap<String, WorkerOwnership>()
    private val cancelledRequestIds = ConcurrentHashMap.newKeySet<String>()
    private val leaseRegistry = RootExecutionLeaseRegistry()

    fun acquireExecutionLeaseHandle(targetId: String, onClose: () -> Unit = {}): AutoCloseable =
        leaseRegistry.acquireHandle(targetId, onClose)

    /**
     * Answers [RootManagerAuthorization] without issuing a new `su` request.
     *
     * The answer is bound to *this service's* session: the caller uid, service uid, authority and
     * the full session identity must match, and the response echoes the service's real identity
     * (client-supplied fields are never reflected back as proof).
     */
    fun queryManagerAuthorization(requestJson: String, observedCallingUid: Int): String =
        RootManagerAuthorizationReader.authorizationJson(
            sessionIdentity = activeSession?.identity,
            observedCallingUid = observedCallingUid,
            liveServiceUid = serviceUidProvider(),
            requestJson = requestJson,
            override = managerAuthorizationOverride,
        )

    private fun observeManagerAuthorization(
        identity: PrivilegedSessionIdentity,
    ): Pair<RootManagerAuthorization, String> {
        managerAuthorizationOverride?.let { override ->
            return override() to "override"
        }
        return RootManagerAuthorizationReader.observe(identity.appUid, identity.androidUserId)
    }

    /**
     * Thin delegate kept so the external independent probe still exercises the real command path.
     * All bounds, decoding and process cleanup live in [RootManagerAuthorizationReader].
     */
    private fun runManagerCommand(sqlite: String, vararg args: String): String? =
        RootManagerAuthorizationReader.runCommand(sqlite, *args)

    /** Thin delegate for the external probe: reads the manager major version. */
    private fun managerMajorVersion(sqlite: String): Int? =
        RootManagerAuthorizationReader.majorVersion(sqlite)

    fun queryQuiescence(requestJson: String, observedCallingUid: Int): String =
        leaseRegistry.queryQuiescenceJson(
            requestJson = requestJson,
            observedCallingUid = observedCallingUid,
            observedServiceUid = serviceUidProvider(),
            activeSession = activeSession,
            inFlightThreads = inFlightThreads,
            workspaceExecutors = workspaceExecutors,
            openStagingFdCount = workspaceSelection.openStagingFdCount(),
            shellRunner = shellRunner,
            signals = lifecycleConfig.signals,
        )

    @Synchronized
    fun pinHostBootstrap(bootstrap: RootServiceHostBootstrap) {
        // Only a trusted, independently OS-verified startup path may call this; never an RPC.
        check(!closed && activeSession == null) { "Bootstrap must precede the session" }
        check(pinnedHostBootstrap == null || pinnedHostBootstrap == bootstrap) { "Bootstrap identity is immutable" }
        pinnedHostBootstrap = bootstrap
    }

    /**
     * Arms the pre-handshake 30s deadline. Production entrypoints call this after pinning the
     * OS-observed bootstrap; a service without a valid controller Binder must not live forever.
     */
    @Synchronized
    fun armLifecycleWatchdog() {
        if (closed || lifecycleWatchdog != null) return
        lifecycleWatchdog = RootServiceLifecycleWatchdog(
            config = lifecycleConfig,
            startedAtMs = lifecycleConfig.clock(),
            controllerPid = -1,
            controllerStartTime = -1L,
            lifecycle = null,
            onControllerLost = { close() },
            onHandshakeTimeout = { abortBeforeHandshake() },
        ).also { it.start() }
    }

    /** The immutable pin comes from trusted startup or OS observation, never from the request. */
    private fun resolveHostBootstrap(observedCallingUid: Int): RootServiceHostBootstrap? {
        pinnedHostBootstrap?.let { return it }
        val observed = hostBootstrapProvider?.invoke(observedCallingUid) ?: return null
        pinnedHostBootstrap = observed
        return observed
    }

    private fun armControllerWatchdog(controllerPid: Int, coordinator: RootServiceLifecycleCoordinator) {
        lifecycleWatchdog?.stop()
        lifecycleWatchdog = RootServiceLifecycleWatchdog(
            config = lifecycleConfig,
            startedAtMs = lifecycleConfig.clock(),
            controllerPid = controllerPid,
            controllerStartTime = lifecycleConfig.signals.stat(controllerPid)?.startTime ?: -1L,
            lifecycle = coordinator,
            onControllerLost = { close() },
            onHandshakeTimeout = { abortBeforeHandshake() },
        ).also { it.start() }
    }

    @Synchronized
    private fun abortBeforeHandshake() {
        closed = true
        closeWorkspaces()
        runCatching { onStopService() }
    }

    @Synchronized
    fun handshake(
        requestJson: String,
        observedCallingUid: Int,
        observedControllerPid: Int = -1,
    ): String {
        if (closed || activeSession != null) {
            return json.encodeToString(RootServiceHandshakeResponse(false, errorReason = "engine_session_already_used"))
        }
        val req = runCatching { json.decodeFromString<RootServiceHandshakeRequest>(requestJson) }.getOrNull()
            ?: return json.encodeToString(RootServiceHandshakeResponse(false, errorReason = "invalid_request"))
        if (req.cancellationProtocolVersion != 1) {
            return json.encodeToString(RootServiceHandshakeResponse(false, errorReason = "cancellation_protocol_unsupported"))
        }
        val pin = resolveHostBootstrap(observedCallingUid)
            ?: return json.encodeToString(RootServiceHandshakeResponse(false, errorReason = "trusted_bootstrap_required"))
        val serviceUid = serviceUidProvider()
        val boundary = observeBoundaryInService(req.ceDataDir, req.deDataDir)
            ?: return json.encodeToString(RootServiceHandshakeResponse(false, errorReason = "control_plane_unobservable"))
        if (!RootServiceBootstrapValidation.matches(pin, req, observedCallingUid, serviceUid, boundary)) {
            return json.encodeToString(RootServiceHandshakeResponse(false, errorReason = "host_identity_rejected"))
        }
        val caller = RootCallerObservation(
            callingUid = observedCallingUid,
            observedServiceUid = serviceUid,
            packageName = req.packageName,
            androidUserId = req.androidUserId,
            signingSha256 = req.signingSha256,
            binderAlive = true,
        )
        val epoch = req.identityEpoch.coerceAtLeast(1L)
        val prefix = if (req.authority == Authority.SHIZUKU_ROOT) "shizuku-root" else "root-direct"
        val identity = PrivilegedSessionIdentity(
            authority = req.authority,
            privilegeTier = PrivilegeTier.ROOT,
            serviceUid = serviceUid,
            appUid = req.appUid,
            androidUserId = req.androidUserId,
            appInstanceId = req.appInstanceId,
            packageName = req.packageName,
            signingSha256 = req.signingSha256,
            identityEpoch = epoch,
            serviceInstanceId = "$prefix-svc-${UUID.randomUUID()}",
            sessionId = "$prefix-ses-${UUID.randomUUID()}",
            protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
        )
        if (!caller.matches(identity)) {
            return json.encodeToString(RootServiceHandshakeResponse(false, errorReason = "caller_mismatch"))
        }
        closeWorkspaces()
        cancelledRequestIds.clear()
        val session = RootServiceSession(
            identity = identity,
            onCancelInFlight = { reqId -> cancelInFlightInternal(reqId) },
            controlPlane = boundary,
        )
        val lifecycle = RootServiceLifecycleCoordinator(
            session = session,
            onStopService = { stopReason ->
                closeWorkspaces()
                if (stopReason != "background_idle_30s" ||
                    (inFlightThreads.isEmpty() &&
                        leaseRegistry.totalOpenHandleCount() == 0 &&
                        shellRunner.activeRequestIds().isEmpty() &&
                        shellRunner.unconfirmedRequestIds().isEmpty())
                ) {
                    onStopService()
                }
            },
        )
        activeSession = session
        activeLifecycle = lifecycle
        verifiedCaller = caller
        activeControlPlane = boundary
        leaseRegistry.recordSession(identity, caller)
        if (observedControllerPid > 0) armControllerWatchdog(observedControllerPid, lifecycle)
        return json.encodeToString(RootServiceHandshakeResponse(true, identity = identity))
    }

    @Synchronized
    fun bindWorkspace(requestJson: String, observedCallingUid: Int): String {
        val caller = verifiedCaller?.takeIf { it.callingUid == observedCallingUid }
            ?.copy(callingUid = observedCallingUid, observedServiceUid = serviceUidProvider())
        return json.encodeToString(
            workspaceSelection.dispatch(requestJson, activeSession, caller, activeControlPlane, closed),
        )
    }

    internal var afterAcceptedBeforePublish: () -> Unit = {}

    @Synchronized
    fun submitEnvelope(
        controlSequence: Long,
        rawEnvelopeJson: String,
        observedCallingUid: Int,
    ): String {
        val session = activeSession
        val caller = verifiedCaller?.takeIf { it.callingUid == observedCallingUid }
            ?.copy(callingUid = observedCallingUid, observedServiceUid = serviceUidProvider())
        if (session == null || caller == null) {
            return json.encodeToString(RootServiceExecuteResponse(submitStage = "UNTRUSTED_OR_INACTIVE"))
        }
        val id = session.identity
        return synchronized(session) {
            val submitResult = session.submit(
                caller = caller,
                targetServiceInstanceId = id.serviceInstanceId,
                targetSessionId = id.sessionId,
                targetIdentityEpoch = id.identityEpoch,
                controlSequence = controlSequence,
                rawEnvelopeJson = rawEnvelopeJson,
            )
            when (submitResult) {
                is RootControlSubmitResult.RejectedBeforeAccepted -> json.encodeToString(
                    RootServiceExecuteResponse(
                        submitStage = "REJECTED_BEFORE_ACCEPTED",
                        rejectedProof = submitResult.proof,
                    ),
                )
                is RootControlSubmitResult.DuplicateSameDigest -> json.encodeToString(
                    RootServiceExecuteResponse(
                        submitStage = "DUPLICATE_SAME_DIGEST",
                        acceptedStatus = submitResult.status,
                    ),
                )
                is RootControlSubmitResult.Accepted -> {
                    afterAcceptedBeforePublish()
                    val reqId = submitResult.envelope.requestId
                    acceptedEnvelopes[reqId] = session to submitResult
                    registerCancelAliases(setOf(reqId, submitResult.envelope.invocationId))
                    json.encodeToString(
                        RootServiceExecuteResponse(
                            submitStage = "ACCEPTED",
                            acceptedStatus = submitResult.status,
                        ),
                    )
                }
                else -> json.encodeToString(
                    RootServiceExecuteResponse(
                        submitStage = "SUBMIT_DENIED",
                        errorReason = submitResult::class.simpleName,
                    ),
                )
            }
        }
    }

    fun executeAccepted(
        requestId: String,
        payloadBytes: ByteArray,
        observedCallingUid: Int,
    ): String {
        val pair = synchronized(this) {
            val caller = verifiedCaller?.takeIf { it.callingUid == observedCallingUid }
            if (closed || caller == null) null else acceptedEnvelopes.remove(requestId)?.takeIf {
                it.first === activeSession && caller.copy(observedServiceUid = serviceUidProvider()).matches(it.first.identity)
            }
        }
        if (pair == null) {
            return json.encodeToString(RootServiceExecuteResponse(submitStage = "UNTRUSTED_OR_INACTIVE"))
        }
        return executeAcceptedEnvelope(pair.first, pair.second, payloadBytes)
    }

    fun submitAndExecute(
        controlSequence: Long,
        rawEnvelopeJson: String,
        payloadBytes: ByteArray,
        observedCallingUid: Int,
    ): String {
        val submittedRaw = submitEnvelope(controlSequence, rawEnvelopeJson, observedCallingUid)
        val submitted = runCatching { json.decodeFromString<RootServiceExecuteResponse>(submittedRaw) }.getOrNull()
        val acceptedReqId = submitted?.acceptedStatus?.requestId
        return if (submitted?.submitStage == "ACCEPTED" && acceptedReqId != null) {
            executeAccepted(acceptedReqId, payloadBytes, observedCallingUid)
        } else {
            submittedRaw
        }
    }

    fun cancelCommand(requestId: String): Boolean =
        cancelCommand(requestId, verifiedCaller?.callingUid ?: -1)

    fun cancelCommand(requestId: String, observedCallingUid: Int): Boolean {
        val session = activeSession ?: return false
        val id = session.identity
        return cancelInvocation(json.encodeToString(RootCancelInvocationRequest(
            targetServiceInstanceId = id.serviceInstanceId, targetSessionId = id.sessionId,
            targetIdentityEpoch = id.identityEpoch, invocationId = requestId)), observedCallingUid)
    }

    fun cancelInvocation(requestJson: String, observedCallingUid: Int): Boolean {
        val request = RootCancelInvocationRequest.decode(requestJson) ?: return false
        val session = activeSession ?: return false
        val ids = synchronized(session) {
            RootCancelInvocationRequest.cancelMatchedSessionInvocation(
                request = request,
                session = session,
                activeSession = activeSession,
                verifiedCaller = verifiedCaller,
                observedCallingUid = observedCallingUid,
                observedServiceUid = serviceUidProvider(),
                cancelledRequestIds = cancelledRequestIds,
            )
        } ?: return false
        ids.forEach { cancelInFlightInternal(it) }
        // A tombstone prevents future admission; signalling an already-running task does not
        // prove it stopped (nor that its side effects are known). Never wait under the gate.
        return leaseRegistry.isConfirmedCancelled(
            ids = ids,
            ledger = session.ledger,
            inFlightThreads = inFlightThreads,
            shellRunner = shellRunner,
            workspaceExecutors = workspaceExecutors,
            openStagingFdCount = workspaceSelection.openStagingFdCount(),
        )
    }

    internal var afterCancelWorkerLookup: (String) -> Unit = {}

    private fun cancelInFlightInternal(requestId: String): Boolean {
        val aliases = inFlightCancelAliases[requestId] ?: leaseRegistry.resolveTargetAliases(requestId)
        var matched = false
        var signalled = false
        for (id in aliases) {
            val pending = acceptedEnvelopes.remove(id)
            if (pending != null) {
                cancelledRequestIds += id
                val (session, accepted) = pending
                if (session.ledger.observe(id)?.stage == RootServiceExecutionStage.ACCEPTED) {
                    session.ledger.completeExecution(accepted.ticket, RootServiceTerminalOutcome.CANCELLED)
                }
                matched = true
            }
            if (inFlightCancelAliases.containsKey(id)) cancelledRequestIds += id
            inFlightThreads[id]?.let { afterCancelWorkerLookup(id); it.interrupt(); matched = true; signalled = true }
            if (shellRunner.cancel(id)) { matched = true; signalled = true }
        }
        leaseRegistry.markCancelSignalled(aliases, signalled)
        return matched
    }

    private fun registerCancelAliases(aliases: Set<String>) {
        leaseRegistry.recordAliases(aliases)
        val merged = leaseRegistry.resolveAllAliases(aliases)
        merged.forEach { key -> inFlightCancelAliases[key] = merged }
    }

    private fun unregisterCancelAliases(aliases: Set<String>) {
        aliases.forEach { key -> inFlightCancelAliases.remove(key) }
    }

    @Synchronized
    fun revokeAndShutdown(controlSequence: Long, reason: String, observedCallingUid: Int): String {
        val session = activeSession ?: return "INACTIVE"
        val caller = verifiedCaller?.takeIf { it.callingUid == observedCallingUid }
            ?.copy(callingUid = observedCallingUid, observedServiceUid = serviceUidProvider())
            ?: return "UNTRUSTED"
        val id = session.identity
        val res = session.revoke(
            caller = caller,
            targetServiceInstanceId = id.serviceInstanceId,
            targetSessionId = id.sessionId,
            targetIdentityEpoch = id.identityEpoch,
            controlSequence = controlSequence,
            reason = reason.ifBlank { "host_revoked" },
        )
        inFlightCancelAliases.keys.toList().forEach { cancelInFlightInternal(it) }
        closed = true
        acceptedEnvelopes.clear()
        scheduleForcedShutdown(reason.ifBlank { "host_revoked" })
        closeWorkspaces()
        activeSession = null
        return when (res) {
            is RootControlRevokeResult.Revoked -> "REVOKED:${res.controlSequence}"
            else -> "REJECTED"
        }
    }

    @Synchronized
    override fun close() {
        lifecycleWatchdog?.stop()
        closed = true
        acceptedEnvelopes.clear()
        inFlightCancelAliases.keys.toList().forEach { cancelInFlightInternal(it) }
        scheduleForcedShutdown("engine_closed")
        closeWorkspaces()
        activeSession = null
    }

    // Called under the engine gate. A deadline field alone cannot stop an unresponsive worker.
    private fun scheduleForcedShutdown(reason: String) {
        if (shutdownScheduled) return
        shutdownScheduled = true
        val lifecycle = activeLifecycle ?: return
        val elapsedMs = (System.nanoTime() - elapsedOriginNanos) / 1_000_000L
        val extraIds = leaseRegistry.resolveAllAliases(
            inFlightThreads.keys +
                leaseRegistry.activeTargetIds() +
                shellRunner.activeRequestIds() +
                shellRunner.unconfirmedRequestIds(),
        ).toList()
        val handle = lifecycle.initiateForcedShutdown(reason.take(128), elapsedMs, extraIds)
        if (handle.settle(elapsedMs) != null) return
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "root-service-shutdown").apply { isDaemon = true }
        }
        scheduler.schedule(
            {
                RootServiceShutdownCollector.terminateInflight(
                    handle = handle,
                    runners = listOf(shellRunner),
                    aliasResolver = leaseRegistry::resolveTargetAliases,
                )
            },
            0L,
            TimeUnit.MILLISECONDS,
        )
        scheduler.schedule({
            try {
                // ScheduledExecutorService uses elapsed delay, independent of wall-clock changes.
                handle.settle(handle.deadlineElapsedMs)
            } finally {
                scheduler.shutdown()
            }
        }, (handle.deadlineElapsedMs - elapsedMs).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun executeAcceptedEnvelope(
        session: RootServiceSession,
        accepted: RootControlSubmitResult.Accepted,
        payloadBytes: ByteArray,
    ): String {
        val ticket = accepted.ticket
        val envelope = accepted.envelope
        // Match the session's revoke/content-stream gate. Never acquire the engine monitor
        // while holding this gate: engine control calls acquire engine -> session.
        val lease = synchronized(session) {
            if (closed || activeSession !== session || envelope.requestId in cancelledRequestIds ||
                session.ledger.sessionState() != RootServiceSessionState.ACTIVE ||
                !session.ledger.beginExecution(ticket)) return@synchronized null
            session.openContentStream(ticket)?.also {
                inFlightThreads[envelope.requestId] = WorkerOwnership(Thread.currentThread())
            }
        }
        if (lease == null) {
            synchronized(session) {
                if (session.ledger.observe(envelope.requestId)?.stage != RootServiceExecutionStage.TERMINAL) {
                    session.ledger.completeExecution(ticket, RootServiceTerminalOutcome.CANCELLED)
                }
                unregisterCancelAliases(setOf(envelope.requestId, envelope.invocationId))
            }
            return json.encodeToString(executionAborted(accepted.status))
        }
        val aliases = setOf(envelope.requestId, envelope.invocationId)
        val execHandle = leaseRegistry.acquireHandle(envelope.requestId)
        return try {
            val rawResponse = dispatchTypedArguments(session, envelope, accepted.status, payloadBytes)
            execHandle.close()
            val resolvedAliases = leaseRegistry.resolveAllAliases(aliases)
            val openFds = workspaceExecutors.values.sumOf { it.openFdCount() } + workspaceSelection.openStagingFdCount()
            val activeShell = leaseRegistry.resolveAllAliases(shellRunner.activeRequestIds())
            val unconfirmedShell = leaseRegistry.resolveAllAliases(shellRunner.unconfirmedRequestIds())
            val quiescentAfterRun = leaseRegistry.openHandleCountFor(resolvedAliases) == 0 &&
                openFds == 0 &&
                resolvedAliases.none { it in activeShell || it in unconfirmedShell }
            val response = RootExecutionLeaseRegistry.finalizeExecuteResponseAfterRun(
                closed = closed,
                activeSession = activeSession,
                session = session,
                aliases = resolvedAliases,
                cancelledRequestIds = cancelledRequestIds,
                acceptedStatus = accepted.status,
                rawResponse = rawResponse,
                quiescentAfterRun = quiescentAfterRun,
            )
            val outcome = response.terminalOutcome ?: RootServiceTerminalOutcome.FAILED
            session.ledger.completeExecution(ticket, outcome, response.resultSha256)
            json.encodeToString(response)
        } finally {
            execHandle.close()
            // Detach before returning to a pooled executor. A canceller retaining this handle
            // can no longer signal the thread once it starts another invocation.
            inFlightThreads[envelope.requestId]?.detach()
            inFlightThreads.remove(envelope.requestId)
            unregisterCancelAliases(leaseRegistry.resolveAllAliases(aliases))
            lease.close()
            if (closed || activeSession !== session) closeWorkspaces()
            leaseRegistry.markSettledIfQuiescent(
                aliases = aliases,
                inFlightThreads = inFlightThreads,
                shellRunner = shellRunner,
                workspaceExecutors = workspaceExecutors,
                openStagingFdCount = workspaceSelection.openStagingFdCount(),
            )
        }
    }

    private fun executionAborted(status: RootControlStatusSnapshot) = RootServiceExecuteResponse(
        submitStage = "EXECUTION_ABORTED",
        acceptedStatus = status,
        terminalOutcome = RootServiceTerminalOutcome.CANCELLED,
    )

    private fun dispatchTypedArguments(
        session: RootServiceSession,
        envelope: RootControlEnvelope,
        acceptedStatus: RootControlStatusSnapshot,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse = when (val args = envelope.arguments) {
        is RootTypedArguments.ShellExec ->
            dispatchShell(session, envelope, args, acceptedStatus, payloadBytes)
        // Every typed file/metadata branch is owned by RootServiceFileDispatch (FD worker helper).
        else -> RootServiceFileDispatch.execute(
            executor = workspaceExecutors[envelope.workspaceId]?.takeIf { !it.isClosed() }?.executor,
            args = args,
            acceptedStatus = acceptedStatus,
            payloadBytes = payloadBytes,
        )
    }

    private fun dispatchShell(
        session: RootServiceSession,
        envelope: RootControlEnvelope,
        args: RootTypedArguments.ShellExec,
        acceptedStatus: RootControlStatusSnapshot,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse {
        val wsRoot = workspaceExecutors[envelope.workspaceId]?.rootPath
        val resolvedCwd = args.cwdRelativePath?.let { rel ->
            if (wsRoot != null) "${wsRoot.trimEnd('/')}/$rel" else null
        } ?: wsRoot
        val callerReqId = payloadBytes.takeIf { it.isNotEmpty() }
            ?.toString(StandardCharsets.UTF_8)
            ?: envelope.invocationId
        val shellReq = ShellExecRequest.fromRuntime(
            callId = callerReqId,
            command = args.command,
            cwd = resolvedCwd,
            limits = ShellLimits(
                timeoutMs = envelope.budget.timeoutMs,
                maxOutputBytes = envelope.budget.maxOutputBytes.toLong(),
            ),
            agentId = "root-service",
            snapshotId = envelope.snapshotId,
            selectedAuthority = session.identity.authority,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            policyVersion = envelope.execution.policyVersion,
            configSnapshotHash = envelope.rawArgumentsSha256,
            sessionIdentity = session.identity.sessionId,
            toolSchemaVersion = 1,
        )
        val aliases = setOf(envelope.requestId, envelope.invocationId, callerReqId, shellReq.requestId, shellReq.callId)
        val permitted = synchronized(session) {
            if (closed || activeSession !== session || envelope.requestId in cancelledRequestIds ||
                session.ledger.sessionState() != RootServiceSessionState.ACTIVE) false else {
                registerCancelAliases(aliases)
                true
            }
        }
        if (!permitted) return executionAborted(acceptedStatus)
        val rawOutcome = try {
            shellRunner.runCommand(session.identity.authority, shellReq)
        } finally {
            unregisterCancelAliases(aliases)
        }
        val outcome = normalizeShellOutcomeAfterRun(session, rawOutcome)
        val payload = RootShellOutcomePayload.fromOutcome(outcome)
        val digest = sha256Hex("${outcome.status.name}:${outcome.exitCode}:${outcome.stdout}:${outcome.stderr}")
        return RootServiceExecuteResponse(
            submitStage = "ACCEPTED",
            acceptedStatus = acceptedStatus,
            terminalOutcome = outcome.terminalOutcome,
            resultSha256 = digest,
            requiresDegradedEpoch = outcome.requiresDegradedEpoch,
            shellOutcome = payload,
        )
    }

    private fun normalizeShellOutcomeAfterRun(
        session: RootServiceSession,
        raw: RootShellRunOutcome,
    ): RootShellRunOutcome =
        RootExecutionLeaseRegistry.normalizeShellOutcomeAfterRun(activeSession, session, raw)

    private fun observeBoundaryInService(ceDataDir: String, deDataDir: String): RootControlPlaneBoundary? =
        RootExecutionLeaseRegistry.observeBoundaryInService(controlPlaneOverride, fdOps, ceDataDir, deDataDir)

    private fun closeWorkspaces() {
        workspaceSelection.close()
        workspaceExecutors.entries.toList().forEach { (key, executor) ->
            executor.close()
            if (executor.openFdCount() == 0) {
                workspaceExecutors.remove(key, executor)
            }
        }
    }

    companion object {
        internal val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        private val CONTROL_PLANE_SUBTREES = listOf(
            "databases",
            "shared_prefs",
            "files",
            "no_backup",
            "code_cache",
            "cache",
        )

        internal fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}

/** Request body of the non-interactive manager observation RPC. */
@kotlinx.serialization.Serializable
internal data class RootManagerAuthorizationRequest(
    val serviceInstanceId: String,
    val sessionId: String,
    val identityEpoch: Long,
)

/** Serialized answer, bound to the requesting session identity. */
@kotlinx.serialization.Serializable
internal data class RootManagerAuthorizationResponse(
    val provider: String,
    val result: String,
    val serviceInstanceId: String?,
    val sessionId: String?,
    val identityEpoch: Long?,
    val reason: String,
)

internal val SUPPORTED_MANAGER_MAJORS = 26..31

internal const val MANAGER_QUERY_TIMEOUT_MS = 2_000L
internal const val MANAGER_OUTPUT_LIMIT_BYTES = 4_096
internal val MANAGER_SQLITE_PATHS = listOf("/sbin/magisk", "/debug_ramdisk/magisk", "/system/bin/magisk")

/** Magisk `--sqlite` prints `column=value` pairs joined by `|` (see db.cpp exec_sql). */
internal fun String.managerField(key: String): String? =
    split('|').map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
