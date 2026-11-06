// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import runtime.mobileagent.skills.tooling.ToolErrorCode

/** Model-facing name of the explicit batch read tool. */
internal const val READ_BATCH_TOOL_NAME = RunControlTools.READ_BATCH

/**
 * Runs bounded read batches in request order. [ReadBatchProtocol] validates input, filters
 * the explicit allowlist and supplies the schema. The delegate authorizes each actual call.
 * Child IDs are single-use within the Run. Reserve the budget before dispatch; a child that
 * needs approval is reported to the caller. Replays revalidate captured child results.
 *
 * @param delegate executor whose specs and live gates are reused; all
 *   non-batch calls are forwarded unchanged.
 * @param liveAuthorized live authorization predicate owned by the Run.
 * @param replayAuthorized live revalidation used for cached disclosure of a
 *   child call. Pass `{ delegate.authorizeReplay(it) }` unless a stricter check
 *   is available.
 * @param reserveCalls atomic cost interface: given the number of child calls the
 *   batch wants, it returns how many are granted (`0..requested`). Wire it to
 *   `PythonRunBudget.reserveBrokerCall` or the Run's own tool budget so the batch
 *   cannot bypass `ToolResultBudget`-style accounting. A throwing callback is
 *   treated as "grant nothing".
 * @param eligibleTools frozen allowlist of read tools usable inside a batch;
 *   empty by default.
 */
class ReadBatchToolExecutor(
    private val delegate: ToolExecutor,
    private val liveAuthorized: suspend () -> Boolean,
    private val replayAuthorized: suspend (ToolCall) -> Boolean,
    private val reserveCalls: (requested: Int) -> Int,
    eligibleTools: Set<String> = emptySet(),
    private val budget: ReadBatchBudget = ReadBatchBudget(),
    /**
     * Deferred-disclosure check for the batch's own execution: its first final
     * envelope.  Defaults to [replayAuthorized]; repeating an outer call id still
     * goes through the strict replay path ([authorizeReplay]).
     */
    private val deferredResultAuthorized: suspend (ToolCall) -> Boolean = replayAuthorized,
    private val childCallId: (outerCallId: String, index: Int, childCallId: String) -> String = { _, _, child -> child },
    approvalRequiredTools: Set<String> = emptySet(),
    /** Runtime-owned authority facts may require approval for this exact bounded child call. */
    private val approvalRequiredForCall: (ToolCall) -> Boolean = { false },
) : ToolExecutor, BackgroundWorkSummary {
    private val lock = Any()

    private val delegateSpecs: List<ToolSpec> = delegate.specs
    private val delegateByName: Map<String, ToolSpec> = delegateSpecs.associateBy { it.name }

    /**
     * The explicit allowlist intersected with the delegate's own read-only
     * declarations; `sideEffect` is only ever used to *narrow* the allowlist,
     * never to grant eligibility.
     */
    private val frozenEligible: Set<String> = ReadBatchProtocol.eligibleReadTools(eligibleTools, delegateByName)

    private val frozenApprovalRequired: Set<String> = approvalRequiredTools intersect frozenEligible

    init {
        val unknown = eligibleTools - delegateByName.keys
        require(unknown.isEmpty()) {
            "Batch eligible tool names are not exposed by the delegate: ${unknown.sorted().joinToString()}"
        }
        require(delegateByName[READ_BATCH_TOOL_NAME] == null) {
            "The delegate already exposes $READ_BATCH_TOOL_NAME"
        }
    }

    override val specs: List<ToolSpec> = buildList {
        addAll(delegateSpecs)
        add(ReadBatchProtocol.spec(frozenEligible, budget.maxCallsPerBatch))
    }

    private val calls = linkedMapOf<String, OuterCall>()
    private val usedChildCallIds = linkedSetOf<String>()
    private val inFlight: MutableSet<CoroutineJob> = Collections.synchronizedSet(linkedSetOf())
    private var dispatchedCalls = 0
    private var unknownOutcomeCalls = 0
    private var outstandingCalls = 0
    private var unknownBatchCount = 0
    private var closed = false

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (call.callId.isBlank()) return ToolResult.Invalid("Tool call ID is missing")
        if (call.name != READ_BATCH_TOOL_NAME) return delegate.invoke(call)
        if (closed) return ToolResult.Denied(CLOSED_REASON)

        var settled: ToolResult? = null
        var replayDenied = false
        var inFlight = false
        val entry = synchronized(lock) {
            val found = calls[call.callId]
            if (found == null) {
                OuterCall(call).also { calls[call.callId] = it }
            } else {
                if (found.call != call) return ToolResult.Invalid(CALL_ID_REUSED)
                if (found.pendingApproval) return ToolResult.NeedsApproval
                settled = found.result
                replayDenied = found.replayDenied
                inFlight = found.running
                found
            }
        }
        val cached = settled
        if (cached != null) {
            // Repeating a call id re-discloses the earlier envelope, so it must
            // pass this executor's own live revalidation (live authorization plus
            // every content child) instead of trusting liveness alone.
            return if (authorizeReplay(call)) cached else ToolResult.Denied(REPLAY_DENIED_REASON)
        }
        if (replayDenied) return ToolResult.Denied(REPLAY_DENIED_REASON)
        if (inFlight) return ToolResult.UnknownOutcome(ALREADY_RUNNING)

        return executeBatchEntry(entry, batchApproved = false)
    }

    private suspend fun executeBatchEntry(entry: OuterCall, batchApproved: Boolean): ToolResult {
        synchronized(lock) { entry.running = true }
        val result = try {
            runBatch(entry, batchApproved)
        } catch (cancelled: CancellationException) {
            withholdDispatchedChildren(entry)
            throw cancelled
        } catch (_: Exception) {
            settleOutstandingCalls()
            withholdDispatchedChildren(entry)
            synchronized(lock) { unknownBatchCount += 1 }
            ToolResult.UnknownOutcome(UNKNOWN_BATCH)
        } finally {
            synchronized(lock) { entry.running = false }
        }
        if (result != ToolResult.NeedsApproval) {
            synchronized(lock) { if (entry.result == null) entry.result = result }
        }
        return result
    }

    /**
     * Disclosure of a cached batch result is allowed only while the Run is still
     * authorized *and* every child call that produced content still passes the
     * live replay check.  A denial purges the cached payload.
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean {
        if (call.name != READ_BATCH_TOOL_NAME) return delegate.authorizeReplay(call)
        val entry = synchronized(lock) {
            val found = calls[call.callId] ?: return false
            if (found.call != call || found.replayDenied || found.result == null) return false
            found
        }
        if (closed || !liveAuthorizedSafely()) return purgeAndDenyReplay(entry)
        val children = synchronized(lock) { entry.contentChildren.toList() }
        children.forEach { child ->
            if (!replayAuthorizedSafely(child)) return purgeAndDenyReplay(entry)
        }
        return true
    }

    override suspend fun approve(callId: String): ToolResult {
        val entry = synchronized(lock) { calls[callId] }
        if (entry == null) return delegate.approve(callId)
        val canApprove = synchronized(lock) { entry.pendingApproval.also { entry.pendingApproval = false } }
        if (!canApprove) return ToolResult.Invalid("A read batch never requires approval")
        return if (closed) settlePendingBatch(entry, ToolResult.Denied(CLOSED_REASON))
        else executeBatchEntry(entry, batchApproved = true)
    }

    override suspend fun authorizeDeferredResult(call: ToolCall): Boolean =
        call.takeIf { it.name != READ_BATCH_TOOL_NAME }?.let { delegate.authorizeDeferredResult(it) } == true

    override suspend fun precheckDeferredResult(call: ToolCall): Boolean =
        call.takeIf { it.name != READ_BATCH_TOOL_NAME }?.let { delegate.precheckDeferredResult(it) } == true

    override suspend fun withholdDeferredResult(call: ToolCall) {
        call.takeIf { it.name != READ_BATCH_TOOL_NAME }?.let { delegate.withholdDeferredResult(it) }
    }

    override suspend fun reject(callId: String): ToolResult =
        forwardSettlement(callId, ToolErrorCode.APPROVAL_DENIED.name) { delegate.reject(it) }

    override suspend fun expire(callId: String): ToolResult =
        forwardSettlement(callId, ToolErrorCode.TIMEOUT.name) { delegate.expire(it) }

    private suspend fun forwardSettlement(
        callId: String,
        reason: String,
        fallback: suspend (String) -> ToolResult,
    ): ToolResult {
        val entry = synchronized(lock) { calls[callId] }
        if (entry == null) return fallback(callId)
        val pending = synchronized(lock) { entry.pendingApproval.also { entry.pendingApproval = false } }
        return if (pending) settlePendingBatch(entry, ToolResult.Denied(reason))
        else ToolResult.Invalid("No pending approval for a read batch")
    }

    private fun settlePendingBatch(entry: OuterCall, result: ToolResult): ToolResult {
        synchronized(lock) { entry.result = result }
        return result
    }

    /** Cancel and join every in-flight batch; nothing survives the Run. */
    override suspend fun close() {
        val entriesSnapshot = synchronized(lock) {
            closed = true
            calls.values.toList()
        }
        val snapshot = synchronized(inFlight) { inFlight.toList() }
        snapshot.forEach { it.cancel() }
        snapshot.forEach { it.join() }
        entriesSnapshot.forEach { withholdDispatchedChildren(it) }
        synchronized(lock) {
            calls.values.forEach { it.result = null }
            calls.clear()
            usedChildCallIds.clear()
        }
    }

    override val hasRunningJobs: Boolean
        get() = synchronized(inFlight) { inFlight.isNotEmpty() }

    override val hasUnknownOutcomes: Boolean
        get() = synchronized(lock) { unknownOutcomeCalls > 0 || unknownBatchCount > 0 }

    override val dispatchedChildren: Int
        get() = synchronized(lock) { dispatchedCalls }

    override val unknownOutcomeChildren: Int
        get() = synchronized(lock) { unknownOutcomeCalls }

    override val unknownBatches: Int
        get() = synchronized(lock) { unknownBatchCount }

    // ------------------------------------------------------------------ batching

    private suspend fun runBatch(entry: OuterCall, batchApproved: Boolean = false): ToolResult {
        val requests = mutableListOf<ChildRequest>()
        val error = ReadBatchProtocol.visitChildren(entry.call.argumentsJson, budget.maxCallsPerBatch) { index, child ->
            val arguments = child.arguments.toString()
            if (arguments.toByteArray(StandardCharsets.UTF_8).size > budget.maxArgumentsBytes) {
                return@visitChildren "calls[$index].arguments exceed the batch argument budget"
            }
            val runtimeId = childCallId(entry.call.callId, index, child.callId)
            requests += ChildRequest(index, child.callId, runtimeId, child.tool, arguments)
            null
        }
        if (error != null) return ToolResult.Invalid(error)

        if (!batchApproved && requests.filter { it.tool in frozenEligible }.any { request ->
            request.tool in frozenApprovalRequired || !guardBoolean {
                !approvalRequiredForCall(ToolCall(request.runtimeCallId, request.tool, request.argumentsJson))
            }
        }) {
            synchronized(lock) { entry.pendingApproval = true }
            return ToolResult.NeedsApproval
        }

        // Unknown, ineligible, side-effectful, and already-used child call ids
        // are settled without dispatch, so they can never become a way in.
        val outcomes = arrayOfNulls<JsonObject>(requests.size)
        val dispatchable = ArrayList<ChildRequest>(requests.size)
        synchronized(lock) {
            requests.forEach { request ->
                when {
                    request.tool !in frozenEligible ->
                        outcomes[request.index] =
                            failureEntry(request, DENIED_STATUS, ToolErrorCode.CAPABILITY_DENIED, NOT_ELIGIBLE)
                    !usedChildCallIds.add(request.childCallId) ->
                        outcomes[request.index] =
                            failureEntry(request, INVALID_STATUS, ToolErrorCode.CALL_ID_REPLAY, DUPLICATE)
                    else -> dispatchable.add(request)
                }
            }
        }

        if (dispatchable.isNotEmpty() && !liveAuthorizedSafely()) {
            return ToolResult.Denied(AUTHORIZATION_CHANGED)
        }

        // One atomic reservation for the whole batch: the outer call cannot buy
        // more child calls than the Run's budget grants.
        val granted = try {
            reserveCalls(dispatchable.size).coerceIn(0, dispatchable.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }
        val dispatch = dispatchable.take(granted)
        dispatchable.drop(granted).forEach { request ->
            outcomes[request.index] = failureEntry(request, BUDGET_STATUS, ToolErrorCode.QUOTA_EXCEEDED, BUDGET_EXHAUSTED)
        }

        var dispatched = 0
        var complete = true
        var offset = 0
        var remainingOutput = budget.maxTotalResultBytes
        while (offset < dispatch.size) {
            if (!liveAuthorizedSafely()) {
                dispatch.drop(offset).forEach { request ->
                    outcomes[request.index] =
                        failureEntry(request, DENIED_STATUS, ToolErrorCode.PERMISSION_DENIED, AUTHORIZATION_CHANGED)
                }
                complete = false
                break
            }
            val window = dispatch.subList(offset, minOf(offset + budget.maxConcurrency, dispatch.size))
            val chunk = dispatchChunk(entry, window, batchApproved)
            dispatched += window.size
            chunk.forEach { outcome ->
                val rendered = renderChild(entry, outcome, remainingOutput)
                remainingOutput = (remainingOutput - rendered.first).coerceAtLeast(0)
                outcomes[outcome.request.index] = rendered.second
            }
            offset += window.size
        }

        // A child that produced content early may lose authorization while later
        // children are still running, so every content child is revalidated
        // before the envelope leaves the executor.  A failure withholds the
        // whole envelope and purges the cached copy.
        val disclosed = synchronized(lock) { entry.contentChildren.toList() }
        if (disclosed.isNotEmpty() && !contentStillAuthorized(disclosed)) {
            withholdDispatchedChildren(entry)
            purgeAndDenyReplay(entry)
            return ToolResult.Denied(BATCH_WITHHELD_REASON)
        }
        val unreleased = synchronized(lock) { (entry.dispatchedChildren - disclosed.toSet()).toList() }
        unreleased.forEach { child -> runCatching { delegate.withholdDeferredResult(child) } }

        return ToolResult.Value(envelope(entry, requests, outcomes, dispatched, granted, complete).toString())
    }

    /** Live authorization plus the deferred check for every content child. */
    private suspend fun contentStillAuthorized(children: List<ToolCall>): Boolean =
        liveAuthorizedSafely()
            .takeIf { it }
            ?.let { children.all { child -> precheckDeferredResultSafely(child) } }
            ?.takeIf { it }
            ?.let { children.all { child -> deferredResultAuthorizedSafely(child) } } == true

    /**
     * Runs one window concurrently.  A cancellation cancels the siblings that
     * already started and propagates, so later windows are never begun.
     */
    private suspend fun dispatchChunk(
        entry: OuterCall,
        window: List<ChildRequest>,
        batchApproved: Boolean,
    ): List<ChildOutcome> =
        coroutineScope {
            val self = coroutineContext.job
            inFlight.add(self)
            try {
                window.map { request -> async { runChild(entry, request, batchApproved) } }.awaitAll()
            } finally {
                // Covers cancellation, an ordinary failure, and an Error escaping
                // a child: any dispatched child that never reported is unknown,
                // so the Run is lifted instead of completing.  On the normal
                // path every child has already settled and this is a no-op.
                settleOutstandingCalls()
                inFlight.remove(self)
            }
        }

    private suspend fun runChild(entry: OuterCall, request: ChildRequest, batchApproved: Boolean): ChildOutcome {
        val childCall = ToolCall(request.runtimeCallId, request.tool, request.argumentsJson)
        synchronized(lock) {
            entry.dispatchedChildren.add(childCall)
            dispatchedCalls += 1
            outstandingCalls += 1
        }
        val result = try {
            val first = delegate.invoke(childCall)
            if (batchApproved && first == ToolResult.NeedsApproval) delegate.approve(childCall.callId) else first
        } catch (cancelled: CancellationException) {
            // Batch children are side-effect-free reads, so a cancelled child
            // changed nothing: it is a definite stop, not an unknown outcome.
            settleCall(unknown = false)
            throw cancelled
        } catch (_: Exception) {
            // A backend disconnect after dispatch still changed nothing for a
            // read; report a definite failure the model may retry explicitly.
            settleCall(unknown = false)
            return ChildOutcome(request, ChildOutcomeKind.FAILURE, null)
        }
        settleCall(unknown = result is ToolResult.UnknownOutcome)
        return when (result) {
            is ToolResult.Value -> ChildOutcome(request, ChildOutcomeKind.VALUE, result)
            is ToolResult.Denied -> ChildOutcome(request, ChildOutcomeKind.DENIED, result)
            is ToolResult.Invalid -> ChildOutcome(request, ChildOutcomeKind.INVALID, result)
            is ToolResult.Failure -> ChildOutcome(request, ChildOutcomeKind.FAILURE, result)
            is ToolResult.UnknownOutcome -> ChildOutcome(request, ChildOutcomeKind.UNKNOWN, result)
            ToolResult.NeedsApproval -> ChildOutcome(request, ChildOutcomeKind.NEEDS_APPROVAL, null)
        }
    }

    /**
     * Renders one child result.  Content is embedded as parsed JSON when it fits
     * the remaining output budget and as a truncated UTF-8 string when it does
     * not, so the batch envelope is always valid JSON and never splits a code
     * point.
     */
    private fun renderChild(entry: OuterCall, outcome: ChildOutcome, remainingOutput: Int): Pair<Int, JsonObject> {
        val request = outcome.request
        val result = outcome.result
        if (outcome.kind == ChildOutcomeKind.VALUE && result is ToolResult.Value) {
            val bytes = result.json.toByteArray(StandardCharsets.UTF_8)
            val limit = minOf(budget.maxResultBytesPerCall, remainingOutput)
            if (bytes.size in 1..limit) {
                val element = runCatching { Json.parseToJsonElement(result.json) }.getOrNull()
                if (element is JsonObject || element is JsonArray) {
                    recordContentChild(entry, request)
                    return bytes.size to valueEntry(request, element, bytes.size, truncated = false)
                }
                recordContentChild(entry, request)
                return bytes.size to stringEntry(request, result.json, bytes.size, truncated = false)
            }
            var kept = utf8Boundary(bytes, limit.coerceAtLeast(0))
            // Never answer with an empty truncated chunk when content exists:
            // one full code point may exceed the per-child cap by at most 3
            // bytes and keeps the result usable.
            if (kept == 0 && limit > 0) kept = utf8NextBoundary(bytes, 0)
            val text = if (kept > 0) String(bytes, 0, kept, StandardCharsets.UTF_8) else ""
            if (kept > 0) recordContentChild(entry, request)
            return kept to stringEntry(request, text, kept, truncated = true)
        }
        val (status, code, message) = when (outcome.kind) {
            ChildOutcomeKind.DENIED -> Triple(DENIED_STATUS, ToolErrorCode.PERMISSION_DENIED, DENIED_NOTICE)
            ChildOutcomeKind.INVALID -> Triple(INVALID_STATUS, ToolErrorCode.INVALID_REQUEST, INVALID_NOTICE)
            ChildOutcomeKind.NEEDS_APPROVAL ->
                Triple(NEEDS_APPROVAL_STATUS, ToolErrorCode.APPROVAL_REQUIRED, APPROVAL_NOTICE)
            ChildOutcomeKind.UNKNOWN -> Triple(UNKNOWN_STATUS, ToolErrorCode.UNKNOWN_OUTCOME, UNKNOWN_NOTICE)
            ChildOutcomeKind.FAILURE -> {
                val failure = (result as? ToolResult.Failure)?.error
                Triple(FAILED_STATUS, failure?.code ?: ToolErrorCode.INTERNAL_ERROR, failure?.message ?: INTERNAL_NOTICE)
            }
            ChildOutcomeKind.VALUE -> Triple(FAILED_STATUS, ToolErrorCode.INTERNAL_ERROR, INTERNAL_NOTICE)
        }
        val fields = linkedMapOf<String, JsonElement>(
            "index" to num(request.index),
            "call_id" to str(request.childCallId),
            "tool" to str(request.tool),
            "status" to str(status),
            "result_bytes" to num(0),
            "truncated" to bool(false),
            "failure_code" to str(code.name),
            "failure_message" to str(message),
        )
        if (outcome.kind == ChildOutcomeKind.UNKNOWN || outcome.kind == ChildOutcomeKind.NEEDS_APPROVAL) {
            fields["outcome"] = str(ToolErrorCode.UNKNOWN_OUTCOME.name)
            fields["automaticReplayAllowed"] = bool(false)
        }
        return 0 to JsonObject(fields)
    }

    /** Records a child that actually produced content, for later replay checks. */
    private fun recordContentChild(entry: OuterCall, request: ChildRequest) {
        synchronized(lock) {
            entry.contentChildren.add(ToolCall(request.runtimeCallId, request.tool, request.argumentsJson))
        }
    }

    private fun envelope(
        entry: OuterCall,
        requests: List<ChildRequest>,
        outcomes: Array<JsonObject?>,
        dispatched: Int,
        reserved: Int,
        complete: Boolean,
    ): JsonObject {
        val rendered = requests.map { request -> outcomes[request.index] ?: fatalEntry(request) }
        val contentBytes = rendered.sumOf { element ->
            ((element as? JsonObject)?.get("result_bytes") as? JsonPrimitive)?.intOrNull ?: 0
        }
        return JsonObject(
            linkedMapOf(
                "batch_call_id" to str(entry.call.callId),
                "results" to JsonArray(rendered),
                "requested_calls" to num(requests.size),
                "dispatched_calls" to num(dispatched),
                "reserved_calls" to num(reserved),
                "complete" to bool(complete),
                "result_bytes" to num(contentBytes),
                "max_total_result_bytes" to num(budget.maxTotalResultBytes),
                "max_concurrency" to num(budget.maxConcurrency),
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Closes one dispatched child, recording an unknown outcome when it has none. */
    private fun settleCall(unknown: Boolean) {
        synchronized(lock) {
            if (outstandingCalls > 0) outstandingCalls -= 1
            if (unknown) unknownOutcomeCalls += 1
        }
    }

    /**
     * Any dispatched child that never reported is unknown.  Cancellation normally
     * settles each child itself, so this is the fail-closed net for a path that
     * never reached its bookkeeping.
     */
    private fun settleOutstandingCalls() {
        synchronized(lock) {
            if (outstandingCalls > 0) {
                unknownOutcomeCalls += outstandingCalls
                outstandingCalls = 0
            }
        }
    }

    private suspend fun guardBoolean(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun liveAuthorizedSafely(): Boolean = guardBoolean { liveAuthorized() }

    private suspend fun replayAuthorizedSafely(call: ToolCall): Boolean = guardBoolean { replayAuthorized(call) }

    private suspend fun deferredResultAuthorizedSafely(call: ToolCall): Boolean =
        guardBoolean { deferredResultAuthorized(call) }

    private suspend fun precheckDeferredResultSafely(call: ToolCall): Boolean =
        guardBoolean { delegate.precheckDeferredResult(call) }

    private suspend fun withholdDispatchedChildren(entry: OuterCall) {
        val children = synchronized(lock) { entry.dispatchedChildren.toList() }
        children.forEach { child -> runCatching { delegate.withholdDeferredResult(child) } }
    }

    private fun purgeAndDenyReplay(entry: OuterCall): Boolean {
        synchronized(lock) {
            entry.replayDenied = true
            entry.result = null
        }
        return false
    }

    private fun valueEntry(
        request: ChildRequest,
        content: JsonElement,
        bytes: Int,
        truncated: Boolean,
    ): JsonObject = JsonObject(
        linkedMapOf(
            "index" to num(request.index),
            "call_id" to str(request.childCallId),
            "tool" to str(request.tool),
            "status" to str(VALUE_STATUS),
            "result" to content,
            "result_bytes" to num(bytes),
            "truncated" to bool(truncated),
        ),
    )

    private fun stringEntry(request: ChildRequest, text: String, bytes: Int, truncated: Boolean): JsonObject =
        JsonObject(
            linkedMapOf(
                "index" to num(request.index),
                "call_id" to str(request.childCallId),
                "tool" to str(request.tool),
                "status" to str(VALUE_STATUS),
                "result_text" to str(text),
                "result_bytes" to num(bytes),
                "truncated" to bool(truncated),
            ),
        )

    private fun failureEntry(
        request: ChildRequest,
        status: String,
        code: ToolErrorCode,
        message: String,
    ): JsonObject = JsonObject(
        linkedMapOf(
            "index" to num(request.index),
            "call_id" to str(request.childCallId),
            "tool" to str(request.tool),
            "status" to str(status),
            "result_bytes" to num(0),
            "truncated" to bool(false),
            "failure_code" to str(code.name),
            "failure_message" to str(message),
        ),
    )

    private fun fatalEntry(request: ChildRequest): JsonObject =
        failureEntry(request, FAILED_STATUS, ToolErrorCode.INTERNAL_ERROR, INTERNAL_NOTICE)

    private class ChildRequest(
        val index: Int,
        val childCallId: String,
        val runtimeCallId: String,
        val tool: String,
        val argumentsJson: String,
    )

    private enum class ChildOutcomeKind { VALUE, DENIED, INVALID, FAILURE, UNKNOWN, NEEDS_APPROVAL }

    private class ChildOutcome(
        val request: ChildRequest,
        val kind: ChildOutcomeKind,
        val result: ToolResult?,
    )

    private class OuterCall(
        val call: ToolCall,
        var pendingApproval: Boolean = false,
        var result: ToolResult? = null,
        var replayDenied: Boolean = false,
        var running: Boolean = false,
        val contentChildren: MutableList<ToolCall> = mutableListOf(),
        val dispatchedChildren: MutableList<ToolCall> = mutableListOf(),
    )

    private companion object {
        const val ALREADY_RUNNING = "Tool call is already executing; use a new call ID"
        const val CALL_ID_REUSED = "Tool call ID was already used for a different request"
        const val CLOSED_REASON = "Read batch executor is closed"
        const val REPLAY_DENIED_REASON = "Tool authorization changed; cached tool output is unavailable"
        const val UNKNOWN_BATCH = "The batch outcome is unknown; it cannot be replayed automatically"

        const val VALUE_STATUS = "VALUE"
        const val DENIED_STATUS = "DENIED"
        const val INVALID_STATUS = "INVALID"
        const val FAILED_STATUS = "FAILED"
        const val UNKNOWN_STATUS = "UNKNOWN_OUTCOME"
        const val NEEDS_APPROVAL_STATUS = "NEEDS_APPROVAL"
        const val BUDGET_STATUS = "BUDGET_EXHAUSTED"

        const val NOT_ELIGIBLE = "The tool is not eligible for a read batch"
        const val DUPLICATE = "The child call id was already used in this Run"
        const val BUDGET_EXHAUSTED = "The Run tool budget did not admit this child call"
        const val AUTHORIZATION_CHANGED = "Authorization changed; nothing was dispatched for this child call"
        const val BATCH_WITHHELD_REASON =
            "Batch authorization changed while children were running; the whole batch output is withheld. " +
                "Do not retry automatically."
        const val DENIED_NOTICE = "The delegate denied this read call"
        const val INVALID_NOTICE = "The delegate rejected this read call as invalid"
        const val UNKNOWN_NOTICE = "The child call outcome is unknown; it cannot be replayed automatically"
        const val INTERNAL_NOTICE = "The child call failed"
        const val APPROVAL_NOTICE =
            "This child call needs its own approval; a batch never approves it. Call the tool directly to approve."
    }
}

/** Hard limits for [ReadBatchToolExecutor]. */
data class ReadBatchBudget(
    /** Children running at once. */
    val maxConcurrency: Int = 2,
    /** Hard ceiling for `calls`, matching the model-facing schema. */
    val maxCallsPerBatch: Int = MAX_BATCH_CALLS,
    /** Embedded result bytes per child. */
    val maxResultBytesPerCall: Int = 32 * 1024,
    /** Embedded result bytes across the whole batch. */
    val maxTotalResultBytes: Int = 96 * 1024,
    /** Ceiling for one child's serialized `arguments` object. */
    val maxArgumentsBytes: Int = 32 * 1024,
) {
    init {
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
        require(maxCallsPerBatch in 1..MAX_BATCH_CALLS) { "maxCallsPerBatch must be within the protocol limit" }
        require(maxConcurrency <= maxCallsPerBatch) { "maxConcurrency must not exceed the batch size" }
        require(maxResultBytesPerCall > 0) { "maxResultBytesPerCall must be positive" }
        require(maxTotalResultBytes >= maxResultBytesPerCall) { "maxTotalResultBytes must cover one child" }
        require(maxArgumentsBytes > 0) { "maxArgumentsBytes must be positive" }
    }

    companion object {
        /** Protocol limit for one explicit batch. */
        const val MAX_BATCH_CALLS = 4
    }
}

private fun str(value: String): JsonElement = JsonPrimitive(value)

private fun num(value: Int): JsonElement = JsonPrimitive(value)

private fun bool(value: Boolean): JsonElement = JsonPrimitive(value)
