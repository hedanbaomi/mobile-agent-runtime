// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import runtime.mobileagent.data.ApprovalRepository
import runtime.mobileagent.data.RootApprovalRepository
import runtime.mobileagent.data.RootDispatchPermit
import runtime.mobileagent.data.RootDispatchRepository
import runtime.mobileagent.data.RootGrantRepository
import runtime.mobileagent.data.RootSnapshotGrantRepository
import runtime.mobileagent.data.RootToolExposureGate
import runtime.mobileagent.data.RootWorkspaceLocatorRepository
import runtime.mobileagent.data.RootWorkspaceRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.finishWithNonAcceptanceProof
import runtime.mobileagent.data.toControlEnvelope
import runtime.mobileagent.domain.ApprovalDecision
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootApprovalBinding
import runtime.mobileagent.domain.RootApprovalRequest
import runtime.mobileagent.domain.RootControlEnvelope
import runtime.mobileagent.domain.RootDispatchOutcome
import runtime.mobileagent.domain.RootExecutionBudget
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootHostControlGate
import runtime.mobileagent.domain.RootRecoveryLocatorCodec
import runtime.mobileagent.domain.RootRecoveryLocatorPayload
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.domain.RootWorkspaceLocator
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.domain.RootWorkspaceRootKind
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.canonicalSha256
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

internal data class PreparedRootDispatch(
    val permit: RootDispatchPermit,
    val canonicalArgumentsJson: String,
    val effectiveArgs: RootTypedArguments,
    val budget: RootExecutionBudget,
    val effectiveWorkspaceId: String,
    val workspaceSessionHandle: String,
    val callerRequestId: String,
)

/**
 * Coordinates durable Root control-plane dispatch reservations, exposure gate verification,
 * `UID 0` service submission under [RootHostControlGate], and receipt disclosure settlement.
 *
 * Never mints synthetic grants, snapshots, workspaces, locators, runs, or approvals during
 * execution; all operations require pre-existing, authorized SQLite bindings.
 */
internal class RootCoordinatorDispatchBridge(
    private val db: SqlConnection,
    private val exposureGate: RootToolExposureGate,
    private val dispatchRepository: RootDispatchRepository,
    private val locatorCipher: RootLocatorCipher,
) {
    private val approvalRepository = RootApprovalRepository(db)
    private val confirmedWorkspaceHandles = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rememberConfirmedWorkspace(execution: RootExecutionStamp, origin: RootWorkspaceOrigin, handle: String) {
        require(origin.matches(execution.identity) && handle.length in 16..128)
        confirmedWorkspaceHandles["${execution.identity.sessionId}:${origin.canonicalSha256()}"] = handle
    }

    fun forgetSession(sessionId: String) {
        confirmedWorkspaceHandles.keys.removeAll { it.startsWith("$sessionId:") }
    }

    fun claimApprovedShellDispatch(
        execution: RootExecutionStamp,
        transport: RootChannelTransport,
        request: ShellExecRequest,
        budget: RootExecutionBudget,
    ): PreparedRootDispatch? = runCatching {
        val binding = findUnconsumedShellApproval(execution, request) ?: return@runCatching null
        val effectiveArgs = RootTypedArguments.ShellExec(
            workspaceId = binding.workspaceId,
            command = request.command,
            cwdRelativePath = null,
        )
        claimVerifiedBinding(
            execution = execution,
            transport = transport,
            binding = binding,
            effectiveArgs = effectiveArgs,
            budget = budget,
            callerRequestId = request.requestId,
        )
    }.getOrNull()

    fun claimApprovedFileDispatch(
        execution: RootExecutionStamp,
        transport: RootChannelTransport,
        rawArgs: RootTypedArguments,
        budget: RootExecutionBudget,
        invocationId: String,
    ): PreparedRootDispatch? = runCatching {
        val binding = findUnconsumedFileApproval(execution, rawArgs, invocationId) ?: return@runCatching null
        claimVerifiedBinding(
            execution = execution,
            transport = transport,
            binding = binding,
            effectiveArgs = rawArgs,
            budget = budget,
            callerRequestId = binding.request.invocationId,
        )
    }.getOrNull()

    private fun claimVerifiedBinding(
        execution: RootExecutionStamp,
        transport: RootChannelTransport,
        binding: RootApprovalBinding,
        effectiveArgs: RootTypedArguments,
        budget: RootExecutionBudget,
        callerRequestId: String,
    ): PreparedRootDispatch? {
        val canonicalJson = effectiveArgs.canonicalJson()
        val argsSha256 = effectiveArgs.canonicalSha256()
        if (binding.request.normalizedArgumentsSha256 != argsSha256 || binding.request.execution != execution) {
            return null
        }
        val exposure = exposureGate.evaluateExposure(
            snapshotId = binding.request.snapshotId,
            grantIds = listOf(binding.request.grantId),
            execution = execution,
            taskIdentity = binding.request.taskIdentity,
            sessionIdentity = binding.request.sessionIdentity,
        )
        val toolExposed = effectiveArgs.toolName in exposure.visibleToolNames &&
            (effectiveArgs !is RootTypedArguments.ShellExec || exposure.shellExposed)
        if (!toolExposed) return null

        val origin = RootWorkspaceRepository(db).getOrigin(binding.workspaceId)
            ?.takeIf { it.matches(execution.identity) }
            ?: return null
        val locator = RootWorkspaceLocatorRepository(db).getCurrent(origin.workspaceId, execution) ?: return null
        val locatorPayload = verifyLocatorPayload(origin, locator) ?: return null
        val workspaceKey = "${execution.identity.sessionId}:${origin.canonicalSha256()}"
        val confirmedHandle = confirmedWorkspaceHandles[workspaceKey]
        val sessionHandle = confirmedHandle ?: "ws-handle-${java.util.UUID.randomUUID()}"
        val bindResp = if (confirmedHandle != null) transport.bindWorkspace(kotlinx.serialization.json.buildJsonObject {
            put("phase", kotlinx.serialization.json.JsonPrimitive("CHECK"))
            put("targetServiceInstanceId", kotlinx.serialization.json.JsonPrimitive(execution.identity.serviceInstanceId))
            put("targetSessionId", kotlinx.serialization.json.JsonPrimitive(execution.identity.sessionId))
            put("targetIdentityEpoch", kotlinx.serialization.json.JsonPrimitive(execution.identity.identityEpoch))
            put("workspaceId", kotlinx.serialization.json.JsonPrimitive(origin.workspaceId))
            put("workspaceGenerationId", kotlinx.serialization.json.JsonPrimitive(origin.generationId))
            put("workspaceSessionHandle", kotlinx.serialization.json.JsonPrimitive(sessionHandle))
        }.toString()) else transport.bindWorkspace(
            RootServiceBindWorkspaceRequest(
                targetServiceInstanceId = execution.identity.serviceInstanceId,
                targetSessionId = execution.identity.sessionId,
                targetIdentityEpoch = execution.identity.identityEpoch,
                workspaceId = origin.workspaceId,
                workspaceGenerationId = origin.generationId,
                workspaceSessionHandle = sessionHandle,
                workspaceRootPath = locatorPayload.absoluteRootPath,
                expectedRootDeviceId = locatorPayload.rootDeviceId,
                expectedRootInode = locatorPayload.rootInode,
                expectedRootMountId = locatorPayload.rootMountId,
                origin = origin,
                recoveryLocator = RootRecoveryLocatorCodec.encode(locatorPayload),
            ),
        )
        if (bindResp?.accepted != true) {
            confirmedWorkspaceHandles.remove(workspaceKey)
            return null
        }
        confirmedWorkspaceHandles[workspaceKey] = sessionHandle

        val permit = synchronized(dispatchRepository) {
            dispatchRepository.claim(binding.approval.approvalId, binding.request)
        }
        return PreparedRootDispatch(
            permit = permit,
            canonicalArgumentsJson = canonicalJson,
            effectiveArgs = effectiveArgs,
            budget = budget,
            effectiveWorkspaceId = origin.workspaceId,
            workspaceSessionHandle = sessionHandle,
            callerRequestId = callerRequestId,
        )
    }

    private fun findUnconsumedShellApproval(
        execution: RootExecutionStamp,
        request: ShellExecRequest,
    ): RootApprovalBinding? {
        val rows = db.query(
            "SELECT b.approval_id, b.binding_json FROM root_approval_bindings b " +
                "JOIN approval_records a ON a.approval_id = b.approval_id " +
                "WHERE b.binding_version = 1 AND a.decision = ?",
            listOf(ApprovalDecision.APPROVED.name),
        )
        val cmdHash = RootPrivilegedServiceEngine.sha256Hex(request.command)
        val expectedConfigHash = normalizeSha256(request.configSnapshotHash)
        for (row in rows) {
            val stored = decodeStoredBinding(row.string("binding_json")) ?: continue
            val current = approvalRepository.getCurrent(stored.approval.approvalId, stored.request) ?: continue
            val idMatches = current.request.invocationId == request.requestId &&
                current.approval.callId == request.callId
            val contextMatches = idMatches &&
                current.invocationName == "shell_exec" &&
                current.approval.agentId == request.agentId &&
                current.request.snapshotId == request.snapshotId &&
                current.approval.selectedAuthority == execution.identity.authority &&
                current.request.execution == execution &&
                current.request.execution.policyVersion == request.policyVersion &&
                current.request.configSnapshotHash == expectedConfigHash &&
                current.request.commandHash == cmdHash
            if (contextMatches) return current
        }
        return null
    }

    private fun findUnconsumedFileApproval(
        execution: RootExecutionStamp,
        rawArgs: RootTypedArguments,
        invocationId: String,
    ): RootApprovalBinding? {
        if (invocationId.isBlank()) return null
        val rows = db.query(
            "SELECT b.approval_id, b.binding_json FROM root_approval_bindings b " +
                "JOIN approval_records a ON a.approval_id = b.approval_id " +
                "WHERE b.binding_version = 1 AND a.decision = ? AND b.invocation_id = ?",
            listOf(ApprovalDecision.APPROVED.name, invocationId),
        )
        val argsSha256 = rawArgs.canonicalSha256()
        for (row in rows) {
            val stored = decodeStoredBinding(row.string("binding_json")) ?: continue
            val current = approvalRepository.getCurrent(stored.approval.approvalId, stored.request) ?: continue
            val matches = current.request.invocationId == invocationId &&
                current.workspaceId == rawArgs.workspaceId &&
                current.invocationName == rawArgs.toolName &&
                current.request.normalizedArgumentsSha256 == argsSha256 &&
                current.request.execution == execution
            if (matches) return current
        }
        return null
    }

    private fun decodeStoredBinding(bindingJson: String): RootApprovalBinding? =
        runCatching { kotlinx.serialization.json.Json.decodeFromString<RootApprovalBinding>(bindingJson) }.getOrNull()

    private fun verifyLocatorPayload(
        origin: RootWorkspaceOrigin,
        locator: RootWorkspaceLocator,
    ): RootRecoveryLocatorPayload? = runCatching {
        val envelope = locator.encryptedEnvelopeCopy()
        val plaintext = try {
            locatorCipher.open(
                origin = origin,
                ciphertext = envelope,
                locatorVersion = locator.locatorVersion,
                keyVersion = locator.keyVersion,
                aadVersion = locator.aadVersion,
            )
        } finally {
            envelope.fill(0)
        } ?: return@runCatching null
        val payload = try { RootRecoveryLocatorCodec.decode(plaintext) } finally { plaintext.fill(0) }
        val valid = payload != null &&
            payload.locatorVersion == locator.locatorVersion &&
            payload.originSha256 == origin.canonicalSha256() &&
            payload.workspaceId == origin.workspaceId &&
            payload.workspaceGenerationId == origin.generationId &&
            payload.authority == origin.authority
        if (valid) payload else null
    }.getOrNull()

    fun submitAndSettle(
        prepared: PreparedRootDispatch,
        hostGate: RootHostControlGate,
        transport: RootChannelTransport,
        liveIdentity: PrivilegedSessionIdentity,
        payloadBytes: ByteArray,
        onDegradeRequired: () -> Unit,
        deferDisclosure: Boolean = false,
    ): RootServiceExecuteResponse? {
        val permit = prepared.permit
        var wireSendAttempted = false
        var sentEnvelope: RootControlEnvelope? = null
        val submitResponse = hostGate.submitUnderLock { seq ->
            val transportInv = dispatchRepository.redeemForWireAttempt(permit, prepared.canonicalArgumentsJson)
                ?: return@submitUnderLock null
            val envelope = transportInv.toControlEnvelope(
                workspaceSessionHandle = prepared.workspaceSessionHandle,
                arguments = prepared.effectiveArgs,
                budget = prepared.budget,
            )
            sentEnvelope = envelope
            wireSendAttempted = true
            transport.submitEnvelope(seq, envelope.canonicalJson())
        }
        if (!wireSendAttempted) {
            dispatchRepository.abortClaimBeforeAttempt(permit)
            return buildUnknownDegradedResponse(null)
        }
        val envelope = requireNotNull(sentEnvelope)
        if (submitResponse == null) {
            val quiescent = RootCoordinatorQuiescenceSupport.isInvocationQuiescent(
                transport, liveIdentity, permit.requestId, envelope.invocationId,
            )
            settleUnknownAndDegrade(permit, quiescent, onDegradeRequired)
            return buildUnknownDegradedResponse(null)
        }
        if (submitResponse.submitStage == "REJECTED_BEFORE_ACCEPTED" && submitResponse.rejectedProof != null) {
            val settled = runCatching {
                dispatchRepository.finishWithNonAcceptanceProof(
                    permit = permit,
                    envelope = envelope,
                    proof = submitResponse.rejectedProof,
                    liveIdentity = liveIdentity,
                )
                dispatchRepository.markRemoteAttemptQuiescent(permit.requestId)
                dispatchRepository.closePermit(permit, disclosed = false)
            }.isSuccess
            if (!settled) {
                settleUnknownAndDegrade(permit, false, onDegradeRequired)
            }
            return null
        }
        if (submitResponse.submitStage != "ACCEPTED") {
            val quiescent = RootCoordinatorQuiescenceSupport.isInvocationQuiescent(
                transport, liveIdentity, permit.requestId, envelope.invocationId,
            )
            settleUnknownAndDegrade(permit, quiescent, onDegradeRequired)
            return buildUnknownDegradedResponse(submitResponse.acceptedStatus)
        }
        if (!RootCoordinatorQuiescenceSupport.acknowledgeIfStillActive(dispatchRepository, permit)) {
            return buildUnknownDegradedResponse(null)
        }
        val execPayload = if (envelope.arguments is RootTypedArguments.ShellExec) {
            prepared.callerRequestId.toByteArray(StandardCharsets.UTF_8)
        } else {
            payloadBytes
        }
        val rpcResponse = transport.executeAccepted(envelope.requestId, execPayload)
        if (rpcResponse == null || hostGate.isRevoked()) {
            val quiescent = rpcResponse?.remoteQuiescent == true ||
                RootCoordinatorQuiescenceSupport.isInvocationQuiescent(
                    transport, liveIdentity, permit.requestId, envelope.invocationId,
                )
            settleUnknownAndDegrade(permit, quiescent, onDegradeRequired)
            return buildUnknownDegradedResponse(submitResponse.acceptedStatus)
        }
        val outcome = when (rpcResponse.terminalOutcome) {
            RootServiceTerminalOutcome.SUCCEEDED -> RootDispatchOutcome.SUCCEEDED
            RootServiceTerminalOutcome.FAILED -> RootDispatchOutcome.FAILED
            else -> RootDispatchOutcome.UNKNOWN_OUTCOME
        }
        return synchronized(dispatchRepository) {
            val current = dispatchRepository.getReceipt(permit.requestId)
            if (current == null || current.closed) return@synchronized buildUnknownDegradedResponse(null)
            if (current.stage == runtime.mobileagent.domain.RootDispatchStage.TERMINAL) {
                if (rpcResponse.remoteQuiescent) dispatchRepository.markRemoteAttemptQuiescent(permit.requestId)
                return@synchronized buildUnknownDegradedResponse(submitResponse.acceptedStatus)
            }
            val finalOutcome = if (rpcResponse.remoteQuiescent) outcome else RootDispatchOutcome.UNKNOWN_OUTCOME
            dispatchRepository.finish(permit, finalOutcome, rpcResponse.resultSha256)
            if (rpcResponse.requiresDegradedEpoch || finalOutcome == RootDispatchOutcome.UNKNOWN_OUTCOME) {
                if (rpcResponse.remoteQuiescent) dispatchRepository.closePermit(permit, disclosed = false)
                onDegradeRequired()
                return@synchronized buildUnknownDegradedResponse(submitResponse.acceptedStatus)
            }
            val canDisclose = dispatchRepository.authorizeDisclosure(permit)
            if (deferDisclosure && finalOutcome == RootDispatchOutcome.SUCCEEDED) {
                dispatchRepository.markRemoteWorkerDrainedKeepPermitOpen(permit.requestId)
                if (!canDisclose) {
                    dispatchRepository.closePermit(permit, disclosed = false)
                    return@synchronized null
                }
                return@synchronized rpcResponse
            }
            dispatchRepository.closePermit(permit, disclosed = canDisclose)
            if (canDisclose) rpcResponse else null
        }
    }

    private fun settleUnknownAndDegrade(
        permit: RootDispatchPermit,
        remoteQuiescent: Boolean,
        onDegradeRequired: () -> Unit,
    ) {
        runCatching {
            dispatchRepository.finish(permit, RootDispatchOutcome.UNKNOWN_OUTCOME)
            if (remoteQuiescent) {
                dispatchRepository.markRemoteAttemptQuiescent(permit.requestId)
                dispatchRepository.closePermit(permit, disclosed = false)
            }
        }
        onDegradeRequired()
    }

    private fun buildUnknownDegradedResponse(
        acceptedStatus: runtime.mobileagent.domain.RootControlStatusSnapshot?,
    ): RootServiceExecuteResponse = RootServiceExecuteResponse(
        submitStage = "ACCEPTED",
        acceptedStatus = acceptedStatus,
        terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
        requiresDegradedEpoch = true,
        shellOutcome = RootShellOutcomePayload(
            statusName = ShellExecutionStatus.UNKNOWN_OUTCOME.name,
            cancelled = true,
            terminalOutcomeName = RootServiceTerminalOutcome.UNKNOWN_OUTCOME.name,
            requiresDegradedEpoch = true,
        ),
    )

    fun approveToolInvocation(
        execution: RootExecutionStamp,
        runId: String,
        invocationId: String,
        callId: String,
        snapshotId: String,
        grantId: String,
        typedArgs: RootTypedArguments,
        configSnapshotHash: String,
        sessionIdentity: String? = null,
    ): RootApprovalBinding {
        val owner = db.query(
            "SELECT r.state,i.call_id,i.name,i.arguments_json FROM runs r " +
                "JOIN tool_invocations i ON i.run_id=r.run_id WHERE r.run_id=? AND i.invocation_id=? AND r.snapshot_id=?",
            listOf(runId, invocationId, snapshotId),
        ).singleOrNull()
        require(RootChildInvocationSupport.isValidApprovalOwner(owner, invocationId, callId, typedArgs)) {
            "Root approval requires the original admitted Run and pending invocation"
        }
        val cmdHash = (typedArgs as? RootTypedArguments.ShellExec)?.let {
            RootPrivilegedServiceEngine.sha256Hex(it.command)
        }
        val cwdHash = (typedArgs as? RootTypedArguments.ShellExec)?.cwdRelativePath?.let(
            RootPrivilegedServiceEngine::sha256Hex,
        )
        val pending = approvalRepository.createPending(
            RootApprovalRequest(
                runId = runId,
                invocationId = invocationId,
                snapshotId = snapshotId,
                grantId = grantId,
                normalizedArgumentsSha256 = typedArgs.canonicalSha256(),
                configSnapshotHash = normalizeSha256(configSnapshotHash),
                execution = execution,
                commandHash = cmdHash,
                cwdHash = cwdHash,
                sessionIdentity = sessionIdentity,
            ),
        )
        return approvalRepository.approve(pending.approval.approvalId)
    }

    private fun normalizeSha256(value: String): String =
        value.lowercase(java.util.Locale.ROOT).takeIf {
            it.length == 64 && it.all { ch -> ch in "0123456789abcdef" }
        } ?: RootPrivilegedServiceEngine.sha256Hex(value)


}
