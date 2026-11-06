// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.util.Collections
import kotlinx.coroutines.*
import runtime.mobileagent.skills.ResultDisclosure
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.*
import runtime.mobileagent.skills.tooling.ToolExecution

/** Runtime identity and canonical request identity, never a logging representation. */
interface ManagedToolInvocation {
    val runtimeInvocationId: String
    val modelCallId: String?
    val toolName: String
    val identity: String
    val contextIdentity: String? get() = null
}

/**
 * Shared model-call lifecycle: exact runtime reservation, approval settlement, cancellation,
 * UNKNOWN tombstones and live result-disclosure checks. Provider adapters own authorization/I/O.
 */
abstract class ManagedToolExecutor<I : ManagedToolInvocation>(specifications: List<ToolSpec>) : ToolExecutor, TypedToolExecutor {
    val toolingSpecs: List<ToolSpec> = Collections.unmodifiableList(specifications.toList())
    val exposedToolNames: Set<String> = Collections.unmodifiableSet(specifications.mapTo(linkedSetOf()) { it.name })
    override val specs: List<LegacyToolSpec> = Collections.unmodifiableList(toolingSpecs.map { it.toLegacySpec() })
    protected abstract fun normalize(call: ToolCall, typed: ToolInvocation?): I?
    protected open fun normalizationFailure(call: ToolCall): ToolResult = invalidArguments()
    protected abstract suspend fun execute(invocation: I): ToolResult
    protected abstract suspend fun approveInvocation(invocation: I): ToolResult
    protected abstract suspend fun rejectInvocation(invocation: I): ToolResult
    protected abstract suspend fun expireInvocation(invocation: I): ToolResult
    protected abstract suspend fun authorizeInvocation(invocation: I, disclosure: ResultDisclosure): Boolean
    protected open suspend fun precheckInvocation(invocation: I): Boolean = authorizeInvocation(invocation, ResultDisclosure.DEFERRED)
    protected open suspend fun withholdInvocation(invocation: I) {}
    protected abstract suspend fun cancelInvocation(invocation: I): Boolean
    protected open fun allowsDeferred(invocation: I): Boolean = true
    protected open suspend fun closeResources() {}

    internal var afterCancellationSettlement: suspend () -> Unit = {}
    internal var beforeBackendEntry: suspend () -> Unit = {}

    private val lock = Any()
    private var closed = false
    private val closeCompletion = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val byRuntimeId = linkedMapOf<String, BoundCall>()
    private val runtimeByModelCall = linkedMapOf<String, MutableSet<String>>()

    // ---- Legacy ToolExecutor boundary -------------------------------------------------

    /**
     * Legacy-boundary result for a tool name this frozen snapshot never exposed. The default
     * keeps the historical Managed/Root refusal; composition layers that own a different legacy
     * contract override this hook.
     */
    protected open fun legacyUnknownTool(call: ToolCall): ToolResult = unavailable()

    /**
     * Legacy-boundary result when no exact pending approval is owned by this executor. The
     * default keeps the historical Managed/Root wording; the composite restores its own contract.
     */
    protected open fun legacyNoPendingApproval(callId: String): ToolResult = INVALID_APPROVAL

    /**
     * `call.callId` is the runtime invocation id by RunTools contract.  This
     * boundary has no model id, so `modelCallId` stays null; a broker must read
     * the original model id from the DB by runtime id.
     */
    override suspend fun invoke(call: ToolCall): ToolResult {
        if (call.callId.isBlank()) return INVALID_CALL
        if (synchronized(lock) { closed }) return unavailable()
        if (call.name !in exposedToolNames) return legacyUnknownTool(call)
        val invocation = normalize(call, null) ?: return normalizationFailure(call)
        return dispatch(invocation)
    }

    override suspend fun approve(callId: String): ToolResult = settlePending(callId) { runtimeId ->
        approveInvocation(runtimeId)
    }

    override suspend fun reject(callId: String): ToolResult = settlePending(callId) { runtimeId ->
        rejectInvocation(runtimeId)
    }

    override suspend fun expire(callId: String): ToolResult = settlePending(callId) { runtimeId ->
        expireInvocation(runtimeId)
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean = authorize(call, ResultDisclosure.REPLAY)

    override suspend fun authorizeDeferredResult(call: ToolCall): Boolean =
        authorize(call, ResultDisclosure.DEFERRED)

    override suspend fun precheckDeferredResult(call: ToolCall): Boolean =
        authorize(call, ResultDisclosure.DEFERRED, precheckOnly = true)

    override suspend fun withholdDeferredResult(call: ToolCall) {
        resolve(call.callId)?.let { bound ->
            synchronized(lock) {
                bound.disclosureDenied = true
                bound.result = replayDenied()
            }
            runCatching { withholdInvocation(bound.invocation) }
        }
    }

    override suspend fun close() {
        val pending = synchronized(lock) {
            if (closed) null else {
                closed = true
                byRuntimeId.values.filter { it.inFlight || it.approvalPending || it.cancelRequired }.toList()
            }
        }
        withContext(NonCancellable) {
            if (pending == null) {
                closeCompletion.await()
                return@withContext
            }
            try {
                coroutineScope {
                    pending.map { bound -> async(Dispatchers.IO) { cancelBound(bound, awaitExisting = true) } }.awaitAll()
                }
            } finally {
                synchronized(lock) {
                    byRuntimeId.values.forEach {
                        it.cancelled = true
                        it.approvalPending = false
                        it.result = ToolResult.UnknownOutcome(CANCELLED_REASON)
                    }
                    byRuntimeId.clear()
                    runtimeByModelCall.clear()
                }
                try { closeResources() } finally { closeCompletion.complete(Unit) }
            }
        }
    }

    // ---- Typed boundary --------------------------------------------------------------

    /**
     * Shared skills-api execution entry.  Unlike the legacy bridge, this
     * preserves the model call id as correlation while the runtime request id
     * remains the sole execution/approval key.
     */
    override suspend fun invoke(invocation: ToolInvocation): ToolExecution {
        if (synchronized(lock) { closed } || invocation.name !in exposedToolNames) return ToolExecution.Failed(unavailableError())
        val call = ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)
        val normalized = normalize(call, invocation) ?: return normalizationFailure(call).toToolExecution()
        return dispatch(normalized).toToolExecution()
    }

    /**
     * Cancel exactly one runtime invocation.  `requestId` may also be a unique
     * model correlation id for compatibility, but it is resolved to the exact
     * runtime id before the backend sees it.
     */
    override suspend fun cancel(requestId: String): Boolean =
        resolve(requestId)?.let { cancelBound(it) } ?: false

    private suspend fun cancelBound(bound: BoundCall, awaitExisting: Boolean = false): Boolean = withContext(NonCancellable) {
        val claimed = synchronized(lock) {
            bound.cancelled = true
            bound.cancelRequired = true
            bound.approvalPending = false
            bound.result = ToolResult.UnknownOutcome(CANCELLED_REASON)
            if (bound.cancelSent) false else { bound.cancelSent = true; true }
        }
        if (!claimed) return@withContext if (awaitExisting) bound.cancelConfirmation.await() else false
        val confirmed = try {
            withContext(Dispatchers.IO) {
                cancelInvocation(bound.invocation)
            }
        } catch (_: Exception) {
            // A failed stop remains unknown and may never authorize disclosure or retry.
            false
        }
        bound.cancelConfirmation.complete(confirmed)
        confirmed
    }

    private fun settleCancellation(bound: BoundCall) = synchronized(lock) {
        bound.cancelRequired = true
        bound.cancelled = true
        settle(bound, ToolResult.UnknownOutcome(CANCELLED_REASON))
    }

    // ---- Internals -------------------------------------------------------------------

    private suspend fun dispatch(invocation: I): ToolResult {
        var replay = false
        val binding = synchronized(lock) {
            if (closed) return unavailable()
            val existing = byRuntimeId[invocation.runtimeInvocationId]
            if (existing != null) {
                if (!existing.matches(invocation)) return INVALID_REPLAY
                if (existing.cancelled) return ToolResult.UnknownOutcome(CANCELLED_REASON)
                if (existing.result != null) {
                    if (existing.result is ToolResult.UnknownOutcome) return existing.result!!
                    replay = true
                    return@synchronized existing
                }
                if (existing.approvalPending) return ToolResult.NeedsApproval
                return ToolResult.UnknownOutcome(IN_FLIGHT_REASON)
            }
            BoundCall(invocation).also {
                it.inFlight = true
                byRuntimeId[invocation.runtimeInvocationId] = it
                invocation.modelCallId?.let { modelId ->
                    runtimeByModelCall.getOrPut(modelId) { linkedSetOf() }.add(invocation.runtimeInvocationId)
                }
            }
        }
        if (replay) {
            if (!authorizeBound(binding, ResultDisclosure.REPLAY)) return replayDenied()
            return synchronized(lock) {
                if (closed || binding.cancelled || binding.disclosureDenied) replayDenied()
                else binding.result ?: replayDenied()
            }
        }
        val result = try {
            beforeBackendEntry()
            execute(invocation)
        } catch (signal: CancellationException) {
            settleCancellation(binding)
            withContext(NonCancellable) {
                afterCancellationSettlement()
                cancelBound(binding)
            }
            throw signal
        } catch (_: Exception) {
            ToolResult.UnknownOutcome(UNKNOWN_REASON)
        }
        return settle(binding, result)
    }

    private suspend fun settlePending(
        callId: String,
        operation: suspend (invocation: I) -> ToolResult,
    ): ToolResult {
        val bound = resolve(callId) ?: return legacyNoPendingApproval(callId)
        synchronized(lock) {
            if (closed || bound.result != null || !bound.approvalPending || bound.inFlight || bound.cancelled) {
                return legacyNoPendingApproval(callId)
            }
            bound.approvalPending = false
            bound.inFlight = true
        }
        val result = try {
            beforeBackendEntry()
            operation(bound.invocation)
        } catch (signal: CancellationException) {
            settleCancellation(bound)
            withContext(NonCancellable) {
                afterCancellationSettlement()
                cancelBound(bound)
            }
            throw signal
        } catch (_: Exception) {
            ToolResult.UnknownOutcome(UNKNOWN_REASON)
        }
        return settle(bound, result)
    }

    private suspend fun authorize(
        call: ToolCall,
        disclosure: ResultDisclosure,
        precheckOnly: Boolean = false,
    ): Boolean {
        val bound = resolve(call.callId) ?: return false
        val request = normalize(call, null) ?: return false
        if (bound.invocation.toolName != call.name || bound.invocation.identity != request.identity) return false
        return authorizeBound(bound, disclosure, precheckOnly)
    }

    private suspend fun authorizeBound(bound: BoundCall, disclosure: ResultDisclosure, precheckOnly: Boolean = false): Boolean {
        val settled = synchronized(lock) {
            if (closed || bound.cancelled || bound.disclosureDenied) return false
            if (bound.inFlight || bound.approvalPending) return false
            if (disclosure == ResultDisclosure.DEFERRED && !allowsDeferred(bound.invocation)) return false
            val current = bound.result ?: return false
            if (current is ToolResult.UnknownOutcome) return false
            if (disclosure == ResultDisclosure.DEFERRED && current !is ToolResult.Value) return false
            current
        }
        val allowed = try {
            if (precheckOnly) precheckInvocation(bound.invocation)
            else authorizeInvocation(bound.invocation, disclosure)
        } catch (signal: CancellationException) {
            throw signal
        } catch (_: Exception) {
            false
        }
        if (!allowed && !precheckOnly) synchronized(lock) {
            if (bound.result === settled) {
                bound.disclosureDenied = true
                bound.result = replayDenied()
            }
        }
        return allowed && synchronized(lock) { !closed && !bound.cancelled && bound.result === settled }
    }

    private fun settle(bound: BoundCall, result: ToolResult): ToolResult = synchronized(lock) {
        bound.inFlight = false
        val visible = if (closed || bound.cancelled) ToolResult.UnknownOutcome(CANCELLED_REASON) else result
        bound.approvalPending = visible == ToolResult.NeedsApproval
        if (visible != ToolResult.NeedsApproval) bound.result =
            if (visible is ToolResult.UnknownOutcome) ToolResult.UnknownOutcome(UNKNOWN_REASON) else visible
        visible
    }

    private fun resolve(id: String): BoundCall? = synchronized(lock) {
        byRuntimeId[id] ?: runtimeByModelCall[id]?.let { runtimeIds ->
            val candidates = runtimeIds.mapNotNull(byRuntimeId::get)
            candidates.singleOrNull { it.approvalPending || it.inFlight } ?: candidates.singleOrNull()
        }
    }

    private inner class BoundCall(val invocation: I) {
        var result: ToolResult? = null
        var approvalPending: Boolean = false
        var inFlight: Boolean = false
        var cancelled: Boolean = false
        var cancelRequired: Boolean = false
        var cancelSent: Boolean = false
        var disclosureDenied: Boolean = false
        val cancelConfirmation = kotlinx.coroutines.CompletableDeferred<Boolean>()

        fun matches(candidate: I): Boolean = invocation.identity == candidate.identity && (
            // The legacy runtime boundary has no typed metadata. It may observe the same exact
            // reservation, but never replaces the original invocation used for authorization.
            candidate.modelCallId == null && candidate.contextIdentity == null ||
                invocation.modelCallId == candidate.modelCallId && invocation.contextIdentity == candidate.contextIdentity
            )

    }

    private companion object {
        val INVALID_CALL = ToolResult.Invalid("Tool call ID is missing")
        val INVALID_REPLAY = ToolResult.Invalid("Tool call ID was already used for a different request")
        val INVALID_APPROVAL = ToolResult.Invalid("No pending tool approval")
        const val IN_FLIGHT_REASON = "Tool call is already executing"
        const val CANCELLED_REASON = "Tool call was cancelled; outcome cannot be confirmed"
        const val UNKNOWN_REASON = "Tool execution failed; outcome is unknown"

        fun unavailableError(): ToolError = ToolError(
            ToolErrorCode.OPERATION_UNAVAILABLE,
            message = "Tool is not available in this run",
        )

        fun unavailable(): ToolResult = ToolResult.Failure(unavailableError())

        fun invalidArguments(): ToolResult = ToolResult.Invalid("Invalid tool arguments")

        fun invalidRequestError(): ToolError = ToolError(ToolErrorCode.INVALID_REQUEST)
        fun replayDenied(): ToolResult = ToolResult.Denied("Tool authorization changed; cached tool output is unavailable")
    }
}
