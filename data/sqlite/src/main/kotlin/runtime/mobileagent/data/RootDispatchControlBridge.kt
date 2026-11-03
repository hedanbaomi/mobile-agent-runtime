// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.*

/**
 * Host-to-service control-plane helpers bridging SQLite dispatch reservations with
 * [RootControlEnvelope], [RootNonAcceptanceProof], and [RootServiceSession].
 */
fun RootTransportInvocation.toControlEnvelope(
    workspaceSessionHandle: String,
    arguments: RootTypedArguments,
    budget: RootExecutionBudget = RootExecutionBudget(),
): RootControlEnvelope {
    require(arguments.canonicalJson() == normalizedArgumentsJson) {
        "Typed arguments canonical JSON does not match redeemed transport invocation"
    }
    val b = claim.approval
    val r = b.request
    return RootControlEnvelope(
        requestId = claim.requestId,
        runId = r.runId,
        invocationId = r.invocationId,
        snapshotId = r.snapshotId,
        grantId = r.grantId,
        grantBeforeRevision = b.grantRevision,
        grantAfterRevision = claim.grantAfterRevision,
        once = claim.once,
        grantConsumedAt = claim.grantConsumedAt,
        execution = r.execution,
        capability = b.capability,
        toolName = b.invocationName,
        workspaceId = b.workspaceId,
        workspaceGenerationId = snapshot.root.workspaceGenerationId,
        workspaceSessionHandle = workspaceSessionHandle,
        relativePathScope = snapshot.grant.pathScope,
        arguments = arguments,
        rawArgumentsSha256 = b.invocationArgumentsSha256,
        normalizedArgumentsSha256 = r.normalizedArgumentsSha256,
        budget = budget,
        commandHash = r.commandHash,
        cwdHash = r.cwdHash,
    )
}

fun RootDispatchRepository.finishWithNonAcceptanceProof(
    permit: RootDispatchPermit,
    envelope: RootControlEnvelope,
    proof: RootNonAcceptanceProof,
    liveIdentity: PrivilegedSessionIdentity?,
): RootDispatchReceipt {
    val expected = RootTransportInvocation(
        claim = permit.claim,
        snapshot = permit.snapshot,
        normalizedArgumentsJson = envelope.arguments.canonicalJson(),
    ).toControlEnvelope(
        workspaceSessionHandle = envelope.workspaceSessionHandle,
        arguments = envelope.arguments,
        budget = envelope.budget,
    )
    require(envelope == expected) { "Root control envelope does not match the live dispatch permit" }
    RootNonAcceptanceVerifier.requireValid(proof, expected, liveIdentity)
    return finish(permit, RootDispatchOutcome.REJECTED_BEFORE_ACCEPTED)
}

fun RootDispatchRepository.canRotateSession(serviceSession: RootServiceSession): Boolean =
    serviceSession.canRotate() &&
        canRotate(serviceSession.identity.sessionId, serviceSession.openContentStreamCount())

fun RootWorkspaceLocatorRepository.attachProvisionalAndPersist(
    serviceSession: RootServiceSession,
    caller: RootCallerObservation,
    provisionalHandle: String,
    workspaceSessionHandle: String,
    origin: RootWorkspaceOrigin,
    execution: RootExecutionStamp,
    keyVersion: Int = 1,
    sealLocator: (origin: RootWorkspaceOrigin, locatorVersion: Int, keyVersion: Int, plaintext: ByteArray) -> ByteArray,
): RootWorkspaceLocator {
    require(origin.matches(serviceSession.identity) && execution.identity == serviceSession.identity) {
        "Root workspace origin and execution stamp must match the active service session"
    }
    val originSha256 = origin.canonicalSha256()
    val attachResult = serviceSession.attachProvisionalWorkspace(
        caller = caller,
        targetServiceInstanceId = serviceSession.identity.serviceInstanceId,
        targetSessionId = serviceSession.identity.sessionId,
        targetIdentityEpoch = serviceSession.identity.identityEpoch,
        provisionalHandle = provisionalHandle,
        workspaceSessionHandle = workspaceSessionHandle,
        origin = origin,
        originSha256 = originSha256,
    )
    val staged = attachResult as? RootWorkspaceAttachResult.Staged
        ?: error("Root workspace provisional attach refused: $attachResult")
    try {
        check(
            staged.workspaceId == origin.workspaceId &&
                staged.workspaceGenerationId == origin.generationId &&
                staged.workspaceSessionHandle == workspaceSessionHandle &&
                staged.originSha256 == originSha256 &&
                staged.locatorVersion == RootRecoveryLocatorCodec.CURRENT_VERSION,
        ) { "Staged Root workspace binding mismatch" }
        val plaintext = staged.locatorBytesCopy()
        val ciphertext = try {
            sealLocator(origin, staged.locatorVersion, keyVersion, plaintext)
        } finally {
            plaintext.fill(0)
        }
        val locator = try {
            RootWorkspaceLocator(
                origin = origin,
                encryptedEnvelope = ciphertext,
                locatorVersion = staged.locatorVersion,
                keyVersion = keyVersion,
            )
        } finally {
            ciphertext.fill(0)
        }
        val persisted = attachNew(locator, execution)
        val confirmed = serviceSession.confirmStagedWorkspace(
            caller = caller,
            targetServiceInstanceId = serviceSession.identity.serviceInstanceId,
            targetSessionId = serviceSession.identity.sessionId,
            targetIdentityEpoch = serviceSession.identity.identityEpoch,
            workspaceId = origin.workspaceId,
            workspaceGenerationId = origin.generationId,
            workspaceSessionHandle = workspaceSessionHandle,
            originSha256 = originSha256,
        )
        check(confirmed) { "Root service session refused to activate staged workspace handle" }
        return persisted
    } catch (failure: Throwable) {
        serviceSession.releaseStagedWorkspace(
            caller = caller,
            targetServiceInstanceId = serviceSession.identity.serviceInstanceId,
            targetSessionId = serviceSession.identity.sessionId,
            targetIdentityEpoch = serviceSession.identity.identityEpoch,
            workspaceSessionHandle = workspaceSessionHandle,
        )
        throw failure
    } finally {
        staged.zeroize()
    }
}

fun RootWorkspaceLocatorRepository.reattachPersistedToSession(
    serviceSession: RootServiceSession,
    caller: RootCallerObservation,
    workspaceId: String,
    workspaceSessionHandle: String,
    execution: RootExecutionStamp,
    reopenedRootPath: String,
    reopenedIdentity: RootFdNodeIdentity,
    onCloseFd: () -> Unit = {},
    openLocator: (origin: RootWorkspaceOrigin, locatorVersion: Int, keyVersion: Int, aadVersion: Int, ciphertext: ByteArray) -> ByteArray,
): RootWorkspaceReattachResult {
    if (execution.identity != serviceSession.identity) {
        runCatching { onCloseFd() }
        return RootWorkspaceReattachResult.Rejected(RootConfinementRejection.IDENTITY_MISMATCH)
    }
    val persisted = getCurrent(workspaceId, execution) ?: run {
        runCatching { onCloseFd() }
        return RootWorkspaceReattachResult.Rejected(RootConfinementRejection.LOCATOR_INVALID)
    }
    if (!persisted.origin.matches(serviceSession.identity) ||
        persisted.locatorVersion != RootRecoveryLocatorCodec.CURRENT_VERSION
    ) {
        runCatching { onCloseFd() }
        return RootWorkspaceReattachResult.Rejected(RootConfinementRejection.ORIGIN_MISMATCH)
    }
    val ciphertext = persisted.encryptedEnvelopeCopy()
    val plaintext = try {
        runCatching {
            openLocator(
                persisted.origin,
                persisted.locatorVersion,
                persisted.keyVersion,
                persisted.aadVersion,
                ciphertext,
            )
        }.getOrElse {
            runCatching { onCloseFd() }
            return RootWorkspaceReattachResult.Rejected(RootConfinementRejection.LOCATOR_INVALID)
        }
    } finally {
        ciphertext.fill(0)
    }
    return try {
        serviceSession.reattachWorkspace(
            caller = caller,
            targetServiceInstanceId = serviceSession.identity.serviceInstanceId,
            targetSessionId = serviceSession.identity.sessionId,
            targetIdentityEpoch = serviceSession.identity.identityEpoch,
            workspaceSessionHandle = workspaceSessionHandle,
            locatorBytes = plaintext,
            origin = persisted.origin,
            originSha256 = persisted.origin.canonicalSha256(),
            reopenedRootPath = reopenedRootPath,
            reopenedIdentity = reopenedIdentity,
            onCloseFd = onCloseFd,
        )
    } finally {
        plaintext.fill(0)
    }
}
