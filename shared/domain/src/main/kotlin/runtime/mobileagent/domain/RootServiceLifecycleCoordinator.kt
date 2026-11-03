// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

data class RootForcedShutdownReport(
    val reason: String,
    val serviceStopped: Boolean,
    val confirmedRequestIds: List<String>,
    val unconfirmedRequestIds: List<String>,
    val timedOut: Boolean,
) {
    val requiresDegradedEpoch: Boolean
        get() = unconfirmedRequestIds.isNotEmpty()
}

data class RootShellTerminationEvidence(
    val directProcessExited: Boolean,
    val processGroupSignaledAndEmpty: Boolean,
    val watchdogPipeReachedEof: Boolean,
    val knownDescendantsAlive: Int,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val exitCode: Int? = null,
) {
    init {
        require(knownDescendantsAlive >= 0)
    }

    val descendantsConfirmedDead: Boolean
        get() = directProcessExited &&
            processGroupSignaledAndEmpty &&
            watchdogPipeReachedEof &&
            knownDescendantsAlive == 0

    fun classifyOutcome(): Pair<RootServiceTerminalOutcome, Boolean> {
        if (!descendantsConfirmedDead) {
            return RootServiceTerminalOutcome.UNKNOWN_OUTCOME to true
        }
        return when {
            cancelled -> RootServiceTerminalOutcome.CANCELLED to false
            timedOut -> RootServiceTerminalOutcome.FAILED to false
            exitCode == 0 -> RootServiceTerminalOutcome.SUCCEEDED to false
            else -> RootServiceTerminalOutcome.FAILED to false
        }
    }
}

class RootForcedShutdownHandle internal constructor(
    val reason: String,
    val startedAtElapsedMs: Long,
    val deadlineElapsedMs: Long,
    initialRunningIds: List<String>,
    private val stopServiceOnce: (String) -> Unit,
) {
    private val trackedIds = initialRunningIds.toList()
    private val confirmedIds = linkedSetOf<String>()
    private var settledReport: RootForcedShutdownReport? = null

    init {
        if (trackedIds.isEmpty()) {
            stopServiceOnce(reason)
        }
    }

    @Synchronized
    fun confirmRequestTerminated(requestId: String, descendantsConfirmedDead: Boolean): Boolean {
        if (settledReport != null) return false
        if (requestId !in trackedIds || !descendantsConfirmedDead) return false
        confirmedIds += requestId
        if (confirmedIds.size == trackedIds.size) {
            stopServiceOnce(reason)
        }
        return true
    }

    @Synchronized
    fun isReadyToSettle(nowElapsedMs: Long): Boolean =
        confirmedIds.size == trackedIds.size || nowElapsedMs >= deadlineElapsedMs

    @Synchronized
    fun settle(nowElapsedMs: Long): RootForcedShutdownReport? {
        settledReport?.let { return it }
        if (!isReadyToSettle(nowElapsedMs)) return null
        stopServiceOnce(reason)
        val confirmed = trackedIds.filter { it in confirmedIds }
        val unconfirmed = trackedIds.filterNot { it in confirmedIds }
        val report = RootForcedShutdownReport(
            reason = reason,
            serviceStopped = true,
            confirmedRequestIds = confirmed,
            unconfirmedRequestIds = unconfirmed,
            timedOut = unconfirmed.isNotEmpty() && nowElapsedMs >= deadlineElapsedMs,
        )
        settledReport = report
        return report
    }
}

/**
 * Enforces the non-daemon Root service lifecycle contract (`ADR-0053`, `root-support-20261029.md` §4.3):
 * - Foreground or active in-flight work / open content streams keep the session alive;
 * - Continuous background idle for [BACKGROUND_IDLE_TIMEOUT_MS] (30s) with zero in-flight work
 *   and zero open streams closes all workspace FDs/streams and stops the service;
 * - Controller Binder death, explicit deactivate/unbind, or protocol failure immediately stops
 *   accepting work, cancels in-flight executions, closes FDs/streams, and bounds termination
 *   collection to [SHUTDOWN_GRACE_TIMEOUT_MS] (5s), reporting any unconfirmed executions so the
 *   Host records `UNKNOWN_OUTCOME` and `DEGRADED`.
 */
class RootServiceLifecycleCoordinator(
    val session: RootServiceSession,
    private val onStopService: (reason: String) -> Unit = {},
) {
    private var inBackground: Boolean = false
    private var backgroundIdleSinceElapsedMs: Long? = null
    private var serviceStopped: Boolean = false
    private var stopReason: String? = null
    private var activeShutdown: RootForcedShutdownHandle? = null

    @Synchronized
    fun isServiceStopped(): Boolean = serviceStopped

    @Synchronized
    fun stoppedReason(): String? = stopReason

    @Synchronized
    fun onAppBackgroundChanged(appInBackground: Boolean, nowElapsedMs: Long) {
        require(nowElapsedMs >= 0L)
        inBackground = appInBackground
        refreshIdleBaseline(nowElapsedMs)
    }

    @Synchronized
    fun onWorkStateChanged(nowElapsedMs: Long) {
        require(nowElapsedMs >= 0L)
        refreshIdleBaseline(nowElapsedMs)
    }

    @Synchronized
    fun pollBackgroundIdleStop(nowElapsedMs: Long): Boolean {
        require(nowElapsedMs >= 0L)
        if (serviceStopped) return true
        refreshIdleBaseline(nowElapsedMs)
        val idleSince = backgroundIdleSinceElapsedMs ?: return false
        if (nowElapsedMs - idleSince < BACKGROUND_IDLE_TIMEOUT_MS) return false
        if (!session.closeSessionIfIdle()) {
            backgroundIdleSinceElapsedMs = null
            return false
        }
        stopOnceInternal("background_idle_30s")
        return true
    }

    @Synchronized
    fun initiateForcedShutdown(
        reason: String,
        nowElapsedMs: Long,
        extraRunningIds: Collection<String> = emptyList(),
    ): RootForcedShutdownHandle {
        require(reason.isNotBlank() && reason.length <= 128)
        require(nowElapsedMs >= 0L)
        activeShutdown?.let { return it }
        backgroundIdleSinceElapsedMs = null
        val runningIds = (session.closeSession() + extraRunningIds).distinct()
        val handle = RootForcedShutdownHandle(
            reason = reason,
            startedAtElapsedMs = nowElapsedMs,
            deadlineElapsedMs = Math.addExact(nowElapsedMs, SHUTDOWN_GRACE_TIMEOUT_MS),
            initialRunningIds = runningIds,
            stopServiceOnce = ::stopOnceInternal,
        )
        activeShutdown = handle
        return handle
    }

    private fun refreshIdleBaseline(nowElapsedMs: Long) {
        if (serviceStopped || activeShutdown != null) {
            backgroundIdleSinceElapsedMs = null
            return
        }
        if (!inBackground || session.hasActiveWork()) {
            backgroundIdleSinceElapsedMs = null
        } else if (backgroundIdleSinceElapsedMs == null) {
            backgroundIdleSinceElapsedMs = nowElapsedMs
        }
    }

    @Synchronized
    private fun stopOnceInternal(reason: String) {
        if (serviceStopped) return
        serviceStopped = true
        stopReason = reason
        runCatching { onStopService(reason) }
    }

    companion object {
        const val BACKGROUND_IDLE_TIMEOUT_MS: Long = 30_000L
        const val SHUTDOWN_GRACE_TIMEOUT_MS: Long = 5_000L
    }
}
