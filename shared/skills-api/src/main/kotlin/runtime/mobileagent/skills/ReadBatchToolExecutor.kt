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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import runtime.mobileagent.skills.tooling.ToolErrorCode

/** Model-facing name of the explicit batch read tool. */
internal const val READ_BATCH_TOOL_NAME = RunControlTools.READ_BATCH

/**
 * Transparent [ToolExecutor] wrapper that adds one explicit `read_batch` tool.
 *
 * ## Why an explicit batch instead of a smarter single-call loop
 *
 * The existing single-call loop is correct and stays untouched; this wrapper
 * only adds a *declared* batch.  Every child call still goes through the
 * delegate's own `invoke`, so the delegate keeps its live authorization,
 * approval, capability, and replay semantics for each child: a batch can never
 * become a way to skip a per-call gate.
 *
 * ## Safety properties
 *
 * - **Explicit read-only allowlist.** Only names passed as `eligibleTools` may
 *   appear in a batch, and a name the delegate declares side-effectful
 *   (`ToolSpec.sideEffect == true`) is never eligible even if listed.  The
 *   default allowlist is empty.
 * - **Batch calls still cost budget.** The batch asks [reserveCalls] for one
 *   reservation covering every child call *before* dispatching anything; only
 *   the granted prefix is dispatched and the rest are reported as
 *   `BUDGET_EXHAUSTED`.  A single outer tool call therefore cannot buy unbounded
 *   child calls, which is exactly why this seam exists instead of a hidden
 *   fan-out.
 * - **No silent approval.** If a child answers [ToolResult.NeedsApproval] the
 *   batch reports a `NEEDS_APPROVAL` entry and never calls
 *   [ToolExecutor.approve], so a batch cannot widen the user's approval.
 * - **Per-child call-id dedup.** Child call ids are unique for the whole Run; a
 *   repeat is rejected with `CALL_ID_REPLAY` and never double-dispatched.
 * - **Bounded and ordered.** At most [ReadBatchBudget.maxCallsPerBatch] children
 *   and [ReadBatchBudget.maxConcurrency] running at once.  Results keep the
 *   requested order regardless of completion order.
 * - **Cancellation stops started work only.** Cancelling the batch cancels the
 *   children that already started and never starts the rest.
 * - **Disclosure is revalidated.** Every child call that produced content must
 *   still pass `replayAuthorized` before a cached batch result is disclosed
 *   again; otherwise the payload is purged and replay is denied.
 * - **Nothing leaks at teardown.** [close] cancels and joins every in-flight
 *   batch.  Every child is side-effect-free, so a cancelled or failing child
 *   changed nothing and settles as a definite stop or failure.  Only a child the
 *   delegate itself reports as [ToolResult.UnknownOutcome], or one lost to an
 *   unexpected error, is unknown; [hasUnknownOutcomes] reports those so the Run
 *   is persisted as an unknown outcome instead of a false completion.
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
) : ToolExecutor, BackgroundWorkSummary {
    private val lock = Any()

    private val delegateSpecs: List<ToolSpec> = delegate.specs
    private val delegateByName: Map<String, ToolSpec> = delegateSpecs.associateBy { it.name }

    /**
     * The explicit allowlist intersected with the delegate's own read-only
     * declarations; `sideEffect` is only ever used to *narrow* the allowlist,
     * never to grant eligibility.
     */
    private val frozenEligible: Set<String> = eligibleTools
        .filter { name ->
            val spec = delegateByName[name]
            spec != null && !spec.sideEffect && name !in RESERVED_NAMES
        }
        .toSet()

    init {
        val unknown = eligibleTools - delegateByName.keys
        require(unknown.isEmpty()) {
            "Batch eligible tool names are not exposed by the delegate: ${unknown.sorted().joinToString()}"
        }
        require(delegateByName[READ_BATCH_TOOL_NAME] == null) {
            "The delegate already exposes $READ_BATCH_TOOL_NAME"
        }
    }

    private val readBatchSpec = ToolSpec(
        name = READ_BATCH_TOOL_NAME,
        description = "Run up to ${budget.maxCallsPerBatch} eligible read tool calls in one explicit batch. " +
            "Results keep the requested order. A batch never approves a call: a child that needs approval " +
            "is reported as NEEDS_APPROVAL.",
        parametersJson = readBatchSchema(frozenEligible.sorted(), budget.maxCallsPerBatch),
        capability = "",
        sideEffect = false,
    )

    override val specs: List<ToolSpec> = buildList {
        delegateSpecs.forEach { spec -> if (spec.name != READ_BATCH_TOOL_NAME) add(spec) }
        add(readBatchSpec)
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

        synchronized(lock) { entry.running = true }
        val result = try {
            runBatch(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A batch-level failure may have dispatched children already; the
            // net below settles them as unknown, and the batch itself counts
            // once so the Run cannot be reported as completed.
            settleOutstandingCalls()
            synchronized(lock) { unknownBatchCount += 1 }
            ToolResult.UnknownOutcome(UNKNOWN_BATCH)
        } finally {
            synchronized(lock) { entry.running = false }
        }
        synchronized(lock) { if (entry.result == null) entry.result = result }
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

    /**
     * `read_batch` never asks for approval, so an approval for one of its own
     * call ids is refused; every other id belongs to the delegate.
     */
    override suspend fun approve(callId: String): ToolResult {
        val entry = synchronized(lock) { calls[callId] }
        if (entry != null && entry.call.name == READ_BATCH_TOOL_NAME) {
            return ToolResult.Invalid("A read batch never requires approval")
        }
        return delegate.approve(callId)
    }

    /**
     * Deferred disclosure of the batch's own envelope.  Calls this wrapper does
     * not own are proxied to the delegate, so a wrapper never blocks or widens
     * another owner's deferred channel.
     */
    override suspend fun authorizeDeferredResult(call: ToolCall): Boolean {
        if (call.name != READ_BATCH_TOOL_NAME) return delegate.authorizeDeferredResult(call)
        // Batches cannot themselves be nested in the managed-job allowlist.
        return false
    }

    /**
     * Approval settlement for a name this wrapper owns.  `read_batch` never
     * asks for approval, so anything else is a pass-through call whose pending
     * approval still belongs to the delegate.
     */
    override suspend fun reject(callId: String): ToolResult = forwardSettlement(callId) { delegate.reject(it) }

    override suspend fun expire(callId: String): ToolResult = forwardSettlement(callId) { delegate.expire(it) }

    private suspend fun forwardSettlement(
        callId: String,
        fallback: suspend (String) -> ToolResult,
    ): ToolResult {
        val entry = synchronized(lock) { calls[callId] }
        if (entry != null && entry.call.name == READ_BATCH_TOOL_NAME) {
            return ToolResult.Invalid("No pending approval for a read batch")
        }
        return fallback(callId)
    }

    /** Cancel and join every in-flight batch; nothing survives the Run. */
    override suspend fun close() {
        synchronized(lock) { closed = true }
        val snapshot = synchronized(inFlight) { inFlight.toList() }
        snapshot.forEach { it.cancel() }
        snapshot.forEach { it.join() }
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

    private suspend fun runBatch(entry: OuterCall): ToolResult {
        val args = parseObject(entry.call.argumentsJson) ?: return ToolResult.Invalid(ARGS_INCOMPLETE)
        strictKeys(args, READ_BATCH_KEYS)?.let { return ToolResult.Invalid(it) }
        val rawCalls = args["calls"] as? JsonArray
            ?: return ToolResult.Invalid("calls must be a JSON array")
        if (rawCalls.isEmpty()) return ToolResult.Invalid("calls must not be empty")
        if (rawCalls.size > budget.maxCallsPerBatch) {
            return ToolResult.Invalid("calls exceeds the per-batch limit of ${budget.maxCallsPerBatch}")
        }
        val requests = ArrayList<ChildRequest>(rawCalls.size)
        rawCalls.forEachIndexed { index, element ->
            val child = element as? JsonObject
                ?: return ToolResult.Invalid("calls[$index] must be a JSON object")
            strictKeys(child, CHILD_KEYS)?.let { return ToolResult.Invalid("calls[$index]: $it") }
            val childCallId = child.stringOrNull("call_id")
                ?: return ToolResult.Invalid("calls[$index].call_id must be a nonblank string")
            val tool = child.stringOrNull("tool")
                ?: return ToolResult.Invalid("calls[$index].tool must be a nonblank string")
            val arguments = child["arguments"] as? JsonObject
                ?: return ToolResult.Invalid("calls[$index].arguments must be a JSON object")
            val argumentsJson = arguments.toString()
            if (argumentsJson.toByteArray(StandardCharsets.UTF_8).size > budget.maxArgumentsBytes) {
                return ToolResult.Invalid("calls[$index].arguments exceed the batch argument budget")
            }
            requests.add(ChildRequest(index, childCallId, tool, argumentsJson))
        }

        // Unknown, ineligible, side-effectful, and already-used child call ids
        // are settled without dispatch, so they can never become a way in.
        val outcomes = arrayOfNulls<JsonObject>(requests.size)
        val dispatchable = ArrayList<ChildRequest>(requests.size)
        synchronized(lock) {
            requests.forEach { request ->
                val spec = delegateByName[request.tool]
                when {
                    spec == null || spec.sideEffect || request.tool !in frozenEligible ->
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
            val chunk = dispatchChunk(window)
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
            purgeAndDenyReplay(entry)
            return ToolResult.Denied(BATCH_WITHHELD_REASON)
        }

        return ToolResult.Value(envelope(entry, requests, outcomes, dispatched, granted, complete).toString())
    }

    /** Live authorization plus the deferred check for every content child. */
    private suspend fun contentStillAuthorized(children: List<ToolCall>): Boolean {
        if (!liveAuthorizedSafely()) return false
        for (child in children) {
            if (!deferredResultAuthorizedSafely(child)) return false
        }
        return true
    }

    /**
     * Runs one window concurrently.  A cancellation cancels the siblings that
     * already started and propagates, so later windows are never begun.
     */
    private suspend fun dispatchChunk(window: List<ChildRequest>): List<ChildOutcome> =
        coroutineScope {
            val self = coroutineContext.job
            inFlight.add(self)
            try {
                window.map { request -> async { runChild(request) } }.awaitAll()
            } finally {
                // Covers cancellation, an ordinary failure, and an Error escaping
                // a child: any dispatched child that never reported is unknown,
                // so the Run is lifted instead of completing.  On the normal
                // path every child has already settled and this is a no-op.
                settleOutstandingCalls()
                inFlight.remove(self)
            }
        }

    private suspend fun runChild(request: ChildRequest): ChildOutcome {
        val childCall = ToolCall(request.childCallId, request.tool, request.argumentsJson)
        synchronized(lock) {
            dispatchedCalls += 1
            outstandingCalls += 1
        }
        val result = try {
            delegate.invoke(childCall)
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
            entry.contentChildren.add(ToolCall(request.childCallId, request.tool, request.argumentsJson))
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

    private suspend fun liveAuthorizedSafely(): Boolean = try {
        liveAuthorized()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun replayAuthorizedSafely(call: ToolCall): Boolean = try {
        replayAuthorized(call)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun deferredResultAuthorizedSafely(call: ToolCall): Boolean = try {
        deferredResultAuthorized(call)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
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
        var result: ToolResult? = null,
        var replayDenied: Boolean = false,
        var running: Boolean = false,
        val contentChildren: MutableList<ToolCall> = mutableListOf(),
    )

    private companion object {
        const val ARGS_INCOMPLETE = "Tool arguments are incomplete JSON"
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

        val READ_BATCH_KEYS = setOf("calls")
        val CHILD_KEYS = setOf("call_id", "tool", "arguments")
        val RESERVED_NAMES = RunControlTools.ALL
    }
}

/** Hard limits for [ReadBatchToolExecutor]. */
data class ReadBatchBudget(
    /** Children running at once. */
    val maxConcurrency: Int = 2,
    /** Hard ceiling for `calls`, matching the model-facing schema. */
    val maxCallsPerBatch: Int = 4,
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

private fun readBatchSchema(names: List<String>, maxCalls: Int): String {
    val enum = names.joinToString(separator = ",") { "\"$it\"" }
    return """{"type":"object","additionalProperties":false,"required":["calls"],""" +
        """"properties":{"calls":{"type":"array","minItems":1,"maxItems":$maxCalls,""" +
        """"description":"Read calls to run together, in order.",""" +
        """"items":{"type":"object","additionalProperties":false,""" +
        """"required":["call_id","tool","arguments"],"properties":{""" +
        """"call_id":{"type":"string","minLength":1,"maxLength":128},""" +
        """"tool":{"type":"string","enum":[$enum]},""" +
        """"arguments":{"type":"object","additionalProperties":true,""" +
        """"description":"Arguments object passed unchanged to the selected tool."}}}}}}"""
}

private fun parseObject(raw: String): JsonObject? {
    if (raw.isBlank()) return null
    return runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
}

private fun JsonObject.stringOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun strictKeys(args: JsonObject, allowed: Set<String>): String? {
    val unknown = args.keys - allowed
    if (unknown.isEmpty()) return null
    return "Unknown parameter: ${unknown.sorted().joinToString()}"
}

private fun str(value: String): JsonElement = JsonPrimitive(value)

private fun num(value: Int): JsonElement = JsonPrimitive(value)

private fun bool(value: Boolean): JsonElement = JsonPrimitive(value)
