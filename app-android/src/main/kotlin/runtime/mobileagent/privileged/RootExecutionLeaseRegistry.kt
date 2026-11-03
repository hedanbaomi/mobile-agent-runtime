// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.os.Process
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlLedger
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootControlStatusSnapshot
import runtime.mobileagent.domain.RootQuiescenceQueryRequest
import runtime.mobileagent.domain.RootRemoteCancelStage
import runtime.mobileagent.domain.RootRemoteQuiescenceSnapshot
import runtime.mobileagent.domain.RootServiceSession
import runtime.mobileagent.domain.RootServiceSessionState
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

internal class RootExecutionLeaseRegistry {
    @Volatile
    private var pinnedIdentity: PrivilegedSessionIdentity? = null

    @Volatile
    private var pinnedCaller: RootCallerObservation? = null

    private val cancelStages = ConcurrentHashMap<String, RootRemoteCancelStage>()
    private val aliasGroups = ConcurrentHashMap<String, Set<String>>()
    private val attachedHandles = ConcurrentHashMap<String, ConcurrentHashMap<String, AutoCloseable>>()

    fun recordSession(identity: PrivilegedSessionIdentity, caller: RootCallerObservation) {
        pinnedIdentity = identity
        pinnedCaller = caller
        cancelStages.clear()
        aliasGroups.clear()
        closeAllHandles()
    }

    @Synchronized
    fun recordAliases(aliases: Set<String>) {
        val clean = aliases.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (clean.isEmpty()) return
        val merged = clean.flatMapTo(linkedSetOf()) { aliasGroups[it] ?: setOf(it) }
        if (aliasGroups.size >= MAX_TRACKED_ENTRIES) {
            aliasGroups.keys.take(merged.size).forEach { aliasGroups.remove(it) }
        }
        merged.forEach { key -> aliasGroups[key] = merged }
    }

    fun resolveTargetAliases(id: String): Set<String> = aliasGroups[id] ?: setOf(id)

    fun resolveAllAliases(ids: Collection<String>): Set<String> =
        ids.flatMapTo(linkedSetOf()) { resolveTargetAliases(it) }

    fun acquireHandle(targetId: String, onClose: () -> Unit = {}): AutoCloseable {
        val handleId = UUID.randomUUID().toString()
        val bucket = attachedHandles.computeIfAbsent(targetId) { ConcurrentHashMap() }
        val closed = AtomicBoolean(false)
        val handle = AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                bucket.remove(handleId)
                if (bucket.isEmpty()) attachedHandles.remove(targetId, bucket)
                onClose()
            }
        }
        bucket[handleId] = handle
        return handle
    }

    fun activeTargetIds(): Set<String> =
        attachedHandles.entries.mapNotNullTo(linkedSetOf()) { (k, v) -> k.takeIf { v.isNotEmpty() } }

    fun openHandleCountFor(ids: Set<String>): Int =
        resolveAllAliases(ids).sumOf { id -> attachedHandles[id]?.size ?: 0 }

    fun totalOpenHandleCount(): Int =
        attachedHandles.values.sumOf { it.size }

    fun markCancelSignalled(aliases: Set<String>, workerOrShellSignalled: Boolean) {
        recordAliases(aliases)
        val resolved = resolveAllAliases(aliases)
        val nextStage = if (workerOrShellSignalled) {
            RootRemoteCancelStage.INTERRUPT_SENT
        } else {
            RootRemoteCancelStage.CANCEL_RECORDED
        }
        if (cancelStages.size >= MAX_TRACKED_ENTRIES) {
            cancelStages.keys.take(resolved.size).forEach { cancelStages.remove(it) }
        }
        resolved.forEach { alias ->
            cancelStages.compute(alias) { _, existing ->
                if (existing == RootRemoteCancelStage.INTERRUPT_SENT) existing else nextStage
            }
        }
    }

    fun markSettledIfQuiescent(
        aliases: Set<String>,
        inFlightThreads: Map<String, *>,
        shellRunner: RootShellCommandRunner,
        workspaceExecutors: Map<String, BoundWorkspaceExecutor>,
        openStagingFdCount: Int = 0,
    ): Boolean {
        val resolved = resolveAllAliases(aliases)
        val activeShell = resolveAllAliases(shellRunner.activeRequestIds())
        val unconfirmedShell = resolveAllAliases(shellRunner.unconfirmedRequestIds())
        val fdCount = workspaceExecutors.values.sumOf { it.openFdCount() } + openStagingFdCount
        val quiescent = resolved.none { it in inFlightThreads || it in activeShell || it in unconfirmedShell } &&
            openHandleCountFor(resolved) == 0 &&
            fdCount == 0
        if (quiescent) {
            resolved.forEach { alias ->
                cancelStages.computeIfPresent(alias) { _, _ -> RootRemoteCancelStage.QUIESCENT_CONFIRMED }
            }
        }
        return quiescent
    }

    fun isConfirmedCancelled(
        ids: List<String>,
        ledger: RootControlLedger,
        inFlightThreads: Map<String, WorkerOwnership>,
        shellRunner: RootShellCommandRunner,
        workspaceExecutors: Map<String, BoundWorkspaceExecutor>,
        openStagingFdCount: Int = 0,
    ): Boolean {
        val resolved = resolveAllAliases(ids)
        val baseSettled = RootCancelInvocationRequest.isSettledConfirmedCancellation(ids, ledger, inFlightThreads)
        val drained = markSettledIfQuiescent(resolved, inFlightThreads, shellRunner, workspaceExecutors, openStagingFdCount)
        return baseSettled && drained
    }

    fun closeAllHandles() {
        attachedHandles.values.forEach { bucket ->
            bucket.values.toList().forEach { runCatching { it.close() } }
            bucket.clear()
        }
        attachedHandles.clear()
    }

    fun queryQuiescenceJson(
        requestJson: String,
        observedCallingUid: Int,
        observedServiceUid: Int,
        activeSession: RootServiceSession?,
        inFlightThreads: Map<String, *>,
        workspaceExecutors: Map<String, BoundWorkspaceExecutor>,
        openStagingFdCount: Int = 0,
        shellRunner: RootShellCommandRunner,
        signals: RootProcessSignals,
    ): String {
        val snapshot = buildSnapshot(
            requestJson = requestJson,
            observedCallingUid = observedCallingUid,
            observedServiceUid = observedServiceUid,
            activeSession = activeSession,
            inFlightThreads = inFlightThreads,
            workspaceExecutors = workspaceExecutors,
            openStagingFdCount = openStagingFdCount,
            shellRunner = shellRunner,
            signals = signals,
        )
        return RootPrivilegedServiceEngine.json.encodeToString(snapshot)
    }

    private fun buildSnapshot(
        requestJson: String,
        observedCallingUid: Int,
        observedServiceUid: Int,
        activeSession: RootServiceSession?,
        inFlightThreads: Map<String, *>,
        workspaceExecutors: Map<String, BoundWorkspaceExecutor>,
        openStagingFdCount: Int,
        shellRunner: RootShellCommandRunner,
        signals: RootProcessSignals,
    ): RootRemoteQuiescenceSnapshot {
        val req = runCatching {
            RootPrivilegedServiceEngine.json.decodeFromString<RootQuiescenceQueryRequest>(requestJson)
        }.getOrNull() ?: return RootRemoteQuiescenceSnapshot(accepted = false)
        val identity = activeSession?.identity ?: pinnedIdentity ?: return RootRemoteQuiescenceSnapshot(accepted = false)
        val caller = pinnedCaller?.takeIf { it.callingUid == observedCallingUid }
            ?.copy(callingUid = observedCallingUid, observedServiceUid = observedServiceUid)
            ?: return RootRemoteQuiescenceSnapshot(accepted = false)
        val validTarget = setOf(
            req.schemaVersion == RootQuiescenceQueryRequest.CURRENT_VERSION,
            caller.matches(identity),
            req.targetAuthority == identity.authority,
            req.targetServiceInstanceId == identity.serviceInstanceId,
            req.targetSessionId == identity.sessionId,
            req.targetIdentityEpoch == identity.identityEpoch,
        ).all { it }
        if (!validTarget) return RootRemoteQuiescenceSnapshot(accepted = false)

        val ledgerInFlight = activeSession?.ledger?.inFlightRequestIds().orEmpty()
        val rawActiveShell = shellRunner.activeRequestIds()
        val rawUnconfirmedShell = shellRunner.unconfirmedRequestIds()
        val activeReqs = resolveAllAliases(inFlightThreads.keys + ledgerInFlight + rawActiveShell)
        val unconfirmedReqs = resolveAllAliases(rawUnconfirmedShell)
        val streamCount = (activeSession?.openContentStreamCount() ?: 0) + totalOpenHandleCount()
        val fdCount = workspaceExecutors.values.sumOf { it.openFdCount() } + openStagingFdCount
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val startTime = signals.stat(pid)?.startTime ?: -1L
        val allQuiescent = listOf(
            inFlightThreads.size,
            streamCount,
            fdCount,
            rawActiveShell.size,
            rawUnconfirmedShell.size,
            activeReqs.size,
        ).all { it == 0 }
        val stage = resolveStage(req.invocationId, allQuiescent, activeReqs, unconfirmedReqs, fdCount)
        return RootRemoteQuiescenceSnapshot(
            accepted = true,
            authority = identity.authority,
            serviceInstanceId = identity.serviceInstanceId,
            sessionId = identity.sessionId,
            identityEpoch = identity.identityEpoch,
            servicePid = pid,
            serviceStartTime = startTime,
            activeWorkerCount = inFlightThreads.size,
            activeStreamLeaseCount = streamCount,
            activeRetainedFdCount = fdCount,
            activeShellProcessCount = rawActiveShell.size,
            unconfirmedShellProcessCount = rawUnconfirmedShell.size,
            activeRequestIds = activeReqs,
            unconfirmedRequestIds = unconfirmedReqs,
            cancelStage = stage,
            quiescent = allQuiescent,
        )
    }

    private fun resolveStage(
        invocationId: String?,
        allQuiescent: Boolean,
        activeReqs: Set<String>,
        unconfirmedReqs: Set<String>,
        fdCount: Int,
    ): RootRemoteCancelStage {
        if (invocationId != null) {
            val aliases = resolveTargetAliases(invocationId)
            val recorded = aliases.firstNotNullOfOrNull { cancelStages[it] } ?: return RootRemoteCancelStage.NONE
            val targetDrained = aliases.none { it in activeReqs || it in unconfirmedReqs } &&
                openHandleCountFor(aliases) == 0 &&
                fdCount == 0
            return if (targetDrained) RootRemoteCancelStage.QUIESCENT_CONFIRMED
            else if (recorded == RootRemoteCancelStage.QUIESCENT_CONFIRMED) RootRemoteCancelStage.CANCEL_RECORDED
            else recorded
        }
        if (cancelStages.isEmpty()) return RootRemoteCancelStage.NONE
        if (allQuiescent) return RootRemoteCancelStage.QUIESCENT_CONFIRMED
        return cancelStages.values.firstOrNull { it != RootRemoteCancelStage.QUIESCENT_CONFIRMED }
            ?: RootRemoteCancelStage.CANCEL_RECORDED
    }

    companion object {
        private const val MAX_TRACKED_ENTRIES = 1024

        private val CONTROL_PLANE_SUBTREES = listOf(
            "databases",
            "shared_prefs",
            "files",
            "no_backup",
            "code_cache",
            "cache",
        )

        fun observeBoundaryInService(
            controlPlaneOverride: RootControlPlaneBoundary?,
            fdOps: PosixFdOperations,
            ceDataDir: String,
            deDataDir: String,
        ): RootControlPlaneBoundary? {
            controlPlaneOverride?.let { return it }
            val cePath = Paths.get(ceDataDir)
            val dePath = Paths.get(deDataDir)
            fun inspectDir(dir: java.nio.file.Path) =
                fdOps.inspectDirectoryIdentityNoFollow(dir)
                    ?: (fdOps as? AndroidOsPosixFdOperations)?.inspectOwnAppDirectoryIdentityNoFollow(dir)
            val ceIdentity = inspectDir(cePath) ?: return null
            val deIdentity = inspectDir(dePath) ?: return null
            val cePosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(cePath)
            val dePosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(dePath)
            val subtrees = CONTROL_PLANE_SUBTREES.mapNotNull { name ->
                inspectDir(cePath.resolve(name))
            }
            return RootControlPlaneBoundary(
                credentialEncryptedDataDir = cePosix,
                credentialEncryptedIdentity = ceIdentity,
                deviceProtectedDataDir = dePosix,
                deviceProtectedIdentity = deIdentity,
                pinnedSubtreeIdentities = subtrees,
            )
        }

        fun normalizeShellOutcomeAfterRun(
            activeSession: RootServiceSession?,
            session: RootServiceSession,
            raw: RootShellRunOutcome,
        ): RootShellRunOutcome = when {
            activeSession !== session || session.ledger.sessionState() != RootServiceSessionState.ACTIVE -> raw.copy(
                status = ShellExecutionStatus.UNKNOWN_OUTCOME,
                cancelled = true,
                terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                requiresDegradedEpoch = true,
            )
            raw.cancelled && raw.status == ShellExecutionStatus.SUCCEEDED -> raw.copy(
                status = ShellExecutionStatus.CANCELLED,
                terminalOutcome = RootServiceTerminalOutcome.FAILED,
            )
            else -> raw
        }

        fun finalizeExecuteResponseAfterRun(
            closed: Boolean,
            activeSession: RootServiceSession?,
            session: RootServiceSession,
            aliases: Set<String>,
            cancelledRequestIds: Set<String>,
            acceptedStatus: RootControlStatusSnapshot,
            rawResponse: RootServiceExecuteResponse,
            quiescentAfterRun: Boolean,
        ): RootServiceExecuteResponse {
            val revokedOrCancelled = closed ||
                activeSession !== session ||
                session.ledger.sessionState() != RootServiceSessionState.ACTIVE ||
                aliases.any { it in cancelledRequestIds }
            if (revokedOrCancelled || !quiescentAfterRun) {
                return rawResponse.copy(
                    submitStage = "ACCEPTED",
                    acceptedStatus = acceptedStatus,
                    terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                    resultSha256 = null,
                    requiresDegradedEpoch = true,
                    fileSnapshot = null,
                    fileResultJson = null,
                    shellOutcome = null,
                    remoteQuiescent = quiescentAfterRun,
                )
            }
            return rawResponse.copy(remoteQuiescent = true)
        }
    }
}
