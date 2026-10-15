// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.skills.tooling.ToolErrorCode

/**
 * Transparent [ToolExecutor] wrapper that adds bounded, **run-owned** background
 * jobs to the model-facing tool loop.
 *
 * ## Why a wrapper
 *
 * The existing single-call tool loop already owns approval, live authorization,
 * budget, and replay semantics.  Background execution must not fork that logic,
 * so this class is a *wrapper*: it exposes the delegate's specs plus four job
 * control tools, forwards every other call unchanged, and reuses the delegate's
 * own live gates instead of reimplementing them.  Integration opts in
 * explicitly by wrapping the run's executor; nothing here changes the
 * single-call path.
 *
 * Everything that is pure protocol — names, schemas, strict argument parsing,
 * cursors, UTF-8 windows, result classification, status envelopes — lives in
 * `ManagedJobProtocol.kt`.  This class owns the single lock, the job lifecycle,
 * and every authorization decision.
 *
 * ## What a job is (and is not)
 *
 * [ToolExecutor] only returns a *final* [ToolResult], so there is no live stdout
 * to forward.  A job is: approve once -> run one delegate call on a run-owned
 * coroutine -> buffer the final result -> page that buffered result with
 * `job_output`.  Chunks are slices of an already-produced result or of the job's
 * status, never a fake live stream.
 *
 * ## Safety properties
 *
 * - **Allowlist, not a heuristic.** Only names passed as `eligibleTools` can be
 *   backgrounded.  `ToolSpec.sideEffect` is deliberately *not* trusted to infer
 *   eligibility, the default allowlist is empty, and `job_*` plus
 *   [READ_BATCH_TOOL_NAME] are never eligible, so a job cannot start a job.
 * - **Approval before dispatch.** `job_start` always answers
 *   [ToolResult.NeedsApproval] without dispatching.  The pending binding is the
 *   original tool name, the exact arguments JSON, and the originating callId;
 *   [approve] re-checks live authorization and capacity before starting.
 * - **Live gates on every step.** `liveAuthorized` is re-read before dispatch,
 *   before resolving a nested delegate approval, and before disclosure.  A
 *   watchdog also polls it while a job runs, so a revocation cancels the running
 *   child instead of waiting for the next `job_output`.
 * - **Deferred disclosure is not cached replay.** Every `job_output` page of a
 *   job's own result is released through `deferredResultAuthorized`, while
 *   repeating an outer call id re-discloses through the strict
 *   [authorizeReplay]/`replayAuthorized` channel.  Revocation withholds content,
 *   cancels the job, and purges the buffer, so a later call cannot resurrect it.
 * - **Bounded.** At most [ManagedJobBudget.maxConcurrentJobs] live jobs, at most
 *   [ManagedJobBudget.maxJobsPerRun] starts per run, and per-job plus per-run
 *   UTF-8 byte caps.  Chunks are never cut inside a code point, so a truncated
 *   chunk is still well-formed text and the envelope around it is always valid
 *   JSON.
 * - **Terminal states are honest.** A deadline is `TIMED_OUT`; an operator or
 *   parent-scope cancellation is `CANCELLED`.  For a tool that may have side
 *   effects both keep an unknown outcome and forbid automatic replay, because a
 *   cancelled delegate call may already have dispatched, and a delegate failure
 *   after dispatch is an unknown outcome too.  Only a tool in `readOnlyTools`
 *   whose delegate spec is also side-effect-free settles a stop or failure as a
 *   definite result: it changed nothing, so it does not lift the Run.  The job deadline is never shortened because the
 *   tool call that started the job has returned.
 * - **No side channel in status.** Status and `job_list` payloads carry opaque
 *   job ids, the eligible tool name, a typed status, and byte counts only: never
 *   arguments, commands, paths, or child call ids.
 * - **Nothing leaks at teardown.** [close] cancels and joins every job and
 *   watchdog; `job_cancel` cancels and joins its target before answering.  After
 *   teardown [hasUnknownOutcomes] tells the Run whether a dispatched child ended
 *   without a definite result, so the Run is persisted as an unknown outcome
 *   instead of a false completion.
 * - **Cost is counted per real child call.** The `job_start` outer control call
 *   is counted by the Run's model loop as usual, and the one child call the job
 *   really dispatches is reserved through `reserveCalls(1)` immediately before
 *   dispatch.  A denied approval, a capacity denial, or a job revoked before
 *   dispatch never spends that budget.
 *
 * The executor never creates a `GlobalScope` or a service of its own: all work
 * runs in the [scope] passed by the caller, which owns the run's lifetime.
 *
 * @param delegate executor whose specs, live gates, and replay checks are
 *   reused; all non-job calls are forwarded to it unchanged.
 * @param scope run-owned scope for job coroutines. Cancelling it (or [close])
 *   terminates every job.
 * @param liveAuthorized live authorization predicate owned by the Run. Must be
 *   cheap and must not suspend indefinitely; it is polled while jobs run.
 *   Exceptions are treated as "not authorized".
 * @param replayAuthorized live revalidation for disclosing content produced by
 *   its [ToolCall]. Pass `{ delegate.authorizeReplay(it) }` unless a stricter
 *   check is available.
 * @param reserveCalls atomic cost interface: given how many child calls the job
 *   wants, it returns how many are granted (`0..requested`).  `job_start`
 *   reserves exactly one child call, and only immediately before the first real
 *   dispatch, so a denied approval, a capacity denial, or a revoked-before-
 *   dispatch job never spends the Run's tool budget.  A throwing callback
 *   grants nothing.
 * @param eligibleTools frozen allowlist of tool names that may be backgrounded;
 *   empty by default, and every name must exist in `delegate.specs`.
 * @param deferredResultAuthorized deferred-disclosure check for the same
 *   execution: every `job_output` page of a job, including the first one.
 *   Defaults to [replayAuthorized]; a repeated outer call id still goes through
 *   the strict replay path ([authorizeReplay]).
 * @param readOnlyTools explicit subset of eligible tools that change nothing.
 *   It only narrows: a name also needs a side-effect-free delegate spec.  Empty
 *   by default, so every stopped job keeps an unknown outcome.
 */
class ManagedJobToolExecutor(
    private val delegate: ToolExecutor,
    private val scope: CoroutineScope,
    private val liveAuthorized: suspend () -> Boolean,
    private val replayAuthorized: suspend (ToolCall) -> Boolean,
    private val reserveCalls: (requested: Int) -> Int,
    eligibleTools: Set<String> = emptySet(),
    private val budget: ManagedJobBudget = ManagedJobBudget(),
    private val childCallId: (outerCallId: String, jobId: String) -> String = { outer, job -> "$outer/job/$job" },
    private val newJobId: () -> String = ::randomJobId,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deferredResultAuthorized: suspend (ToolCall) -> Boolean = replayAuthorized,
    readOnlyTools: Set<String> = emptySet(),
) : ToolExecutor, BackgroundWorkSummary {
    private val lock = Any()

    /** Frozen allowlist; a name that is not here can never be backgrounded. */
    private val frozenEligible: Set<String> = eligibleTools.toSet()

    private val delegateSpecs: Map<String, ToolSpec> = delegate.specs.associateBy { it.name }

    /** Both the caller and the delegate must agree that the tool changes nothing. */
    private val frozenReadOnly: Set<String> = sideEffectFreeTools(readOnlyTools, frozenEligible, delegateSpecs)

    init {
        val collisions = frozenEligible intersect MANAGED_JOB_RESERVED_NAMES
        require(collisions.isEmpty()) {
            "Job control tools cannot be backgrounded: ${collisions.sorted().joinToString()}"
        }
        val unknown = frozenEligible - delegateSpecs.keys
        require(unknown.isEmpty()) {
            "Eligible tool names are not exposed by the delegate: ${unknown.sorted().joinToString()}"
        }
        val shadowed = delegateSpecs.keys intersect MANAGED_JOB_NAMES
        require(shadowed.isEmpty()) {
            "Managed job tool names are already exposed by the delegate: ${shadowed.sorted().joinToString()}"
        }
    }

    private val jobStartSpec = ToolSpec(
        name = MANAGED_JOB_START,
        description = "Start one eligible tool in the background of this Run. Requires approval; " +
            "the job id is returned after approval. Poll job_output for the bounded result and status.",
        parametersJson = managedJobStartSchema(frozenEligible.sorted()),
        capability = "",
        sideEffect = true,
    )

    private val jobOutputSpec = ToolSpec(
        name = MANAGED_JOB_OUTPUT,
        description = "Page the buffered result and status of a background job by UTF-8 byte offset. " +
            "Continue with the returned next_offset_bytes or cursor until truncated is false.",
        parametersJson = managedJobOutputSchema(budget.maxChunkBytes, budget.maxWaitMs),
        capability = "",
        sideEffect = false,
    )

    private val jobListSpec = ToolSpec(
        name = MANAGED_JOB_LIST,
        description = "List background jobs of this Run with typed status and byte counts. " +
            "Job arguments, commands, and paths are never included.",
        parametersJson = managedJobListSchema(),
        capability = "",
        sideEffect = false,
    )

    private val jobCancelSpec = ToolSpec(
        name = MANAGED_JOB_CANCEL,
        description = "Cancel a background job and wait until it has stopped. A read-only job has a definite " +
            "cancelled result; a job with possible side effects keeps an unknown outcome and is never replayed automatically.",
        parametersJson = managedJobCancelSchema(),
        capability = "",
        sideEffect = true,
    )

    override val specs: List<ToolSpec> = buildList {
        delegate.specs.forEach { spec -> if (spec.name !in MANAGED_JOB_NAMES) add(spec) }
        add(jobStartSpec)
        add(jobOutputSpec)
        add(jobListSpec)
        add(jobCancelSpec)
    }

    /** Opaque per-executor cursor key; never disclosed to the model. */
    private val cursor = JobCursor(randomHex(16))

    private val jobs = linkedMapOf<String, ManagedJob>()
    private val calls = linkedMapOf<String, OuterCall>()
    private var jobsStarted = 0
    private var totalBufferedBytes = 0L
    private var closed = false

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (call.callId.isBlank()) return ToolResult.Invalid("Tool call ID is missing")
        if (call.name !in MANAGED_JOB_NAMES) return delegate.invoke(call)
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
                if (found.pending != null) return ToolResult.NeedsApproval
                settled = found.result
                replayDenied = found.replayDenied
                inFlight = found.running
                found
            }
        }
        val cached = settled
        if (cached != null) {
            // A repeated call id is a *disclosure* of the earlier answer, so it
            // must pass the same live revalidation as any other disclosure.  A
            // revoked grant denies and purges instead of repeating stale output.
            return if (authorizeReplay(call)) cached else ToolResult.Denied(REPLAY_DENIED_REASON)
        }
        if (replayDenied) return ToolResult.Denied(REPLAY_DENIED_REASON)
        if (inFlight) return ToolResult.UnknownOutcome(ALREADY_RUNNING)

        synchronized(lock) { entry.running = true }
        val result = try {
            when (call.name) {
                MANAGED_JOB_START -> requestJobStart(entry)
                MANAGED_JOB_OUTPUT -> jobOutput(entry)
                MANAGED_JOB_LIST -> ToolResult.Value(jobListEnvelope().toString())
                MANAGED_JOB_CANCEL -> jobCancel(entry)
                else -> ToolResult.Invalid("Unknown tool ${call.name}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The call id must never become usable again as a fresh request.
            ToolResult.UnknownOutcome(JOB_TOOL_FAILED)
        } finally {
            synchronized(lock) { entry.running = false }
        }
        if (result != ToolResult.NeedsApproval) {
            synchronized(lock) { if (entry.result == null) entry.result = result }
        }
        return result
    }

    /**
     * A single explicit approval covers the whole nested request: the user saw
     * the target tool and its full arguments. If the delegate still reports
     * [ToolResult.NeedsApproval] for the child call, that approval is settled
     * through [ToolExecutor.approve] on the same child call id, so the
     * delegate's own live revalidation still runs and is never bypassed.
     */
    override suspend fun approve(callId: String): ToolResult {
        val entry = synchronized(lock) { calls[callId] } ?: return delegate.approve(callId)
        val pending = synchronized(lock) {
            val current = entry.pending ?: return@synchronized null
            entry.pending = null
            current
        } ?: return entry.result ?: ToolResult.Invalid("No pending job start for this call id")
        if (closed) return settle(entry, ToolResult.Denied(CLOSED_REASON))
        if (!liveAuthorizedSafely()) return settle(entry, ToolResult.Denied(REVOKED_START_REASON))

        var job: ManagedJob? = null
        var denial: ToolResult? = null
        synchronized(lock) {
            when {
                jobsStarted >= budget.maxJobsPerRun -> denial = ToolResult.Denied(JOBS_EXHAUSTED)
                activeJobCountLocked() >= budget.maxConcurrentJobs -> denial = ToolResult.Denied(CONCURRENCY_EXHAUSTED)
                else -> {
                    val created = ManagedJob(
                        id = pending.jobId,
                        toolName = pending.tool,
                        sideEffect = pending.tool !in frozenReadOnly,
                        childCall = ToolCall(
                            callId = childCallId(entry.call.callId, pending.jobId),
                            name = pending.tool,
                            argumentsJson = pending.argumentsJson,
                        ),
                    )
                    jobs[created.id] = created
                    jobsStarted += 1
                    job = created
                }
            }
        }
        denial?.let { return settle(entry, it) }
        val started = job ?: return settle(entry, ToolResult.Denied(CONCURRENCY_EXHAUSTED))
        start(started)
        return settle(entry, ToolResult.Value(envelope(started).toString()))
    }

    override suspend fun reject(callId: String): ToolResult =
        settlePending(callId, "Job start approval was denied") { delegate.reject(it) }

    override suspend fun expire(callId: String): ToolResult =
        settlePending(callId, "Job start approval expired") { delegate.expire(it) }

    /**
     * Disclosure is re-validated live for our own calls and forwarded verbatim
     * for everything else, so wrapping the executor never weakens or blocks the
     * delegate's existing cached-result semantics.
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean {
        if (call.name !in MANAGED_JOB_NAMES) return delegate.authorizeReplay(call)
        val entry = synchronized(lock) {
            val found = calls[call.callId] ?: return false
            if (found.call != call || found.replayDenied || found.result == null) return false
            found
        }
        if (closed || !liveAuthorizedSafely()) {
            purgeAndDenyReplay(entry)
            return false
        }
        val disclosed = synchronized(lock) { entry.disclosedJobId }
        if (disclosed != null) {
            val job = synchronized(lock) { jobs[disclosed] }
            if (job != null && !replayAuthorizedSafely(job.childCall)) {
                purgeAndDenyReplay(entry)
                revoke(job)
                return false
            }
        }
        return true
    }

    /**
     * Page disclosure of a job's own result.  Calls this wrapper does not own
     * are proxied to the delegate, so a wrapper never blocks or widens another
     * owner's deferred channel.
     */
    override suspend fun authorizeDeferredResult(call: ToolCall): Boolean {
        if (call.name !in MANAGED_JOB_NAMES) return delegate.authorizeDeferredResult(call)
        // Management tools cannot themselves be nested in another background job.
        return false
    }

    /** Cancel and join every job and watchdog; nothing survives the Run. */
    override suspend fun close() {
        val snapshot = synchronized(lock) {
            closed = true
            jobs.values.toList()
        }
        snapshot.forEach { job ->
            job.work?.cancel(JobClosed())
            job.watchdog?.cancel()
        }
        snapshot.forEach { job ->
            job.work?.join()
            job.watchdog?.join()
        }
        snapshot.forEach { job -> finish(job, ManagedJobStatus.CANCELLED, REASON_CLOSED, withheld = true) }
        synchronized(lock) { snapshot.forEach { dropContentLocked(it) } }
    }

    override val hasRunningJobs: Boolean
        get() = synchronized(lock) { jobs.values.any { !it.status.isTerminal } }

    override val hasUnknownOutcomes: Boolean
        get() = synchronized(lock) { jobs.values.any { it.dispatched && it.unknownOutcome } }

    override val dispatchedChildren: Int
        get() = synchronized(lock) { jobs.values.count { it.dispatched } }

    override val unknownOutcomeChildren: Int
        get() = synchronized(lock) { jobs.values.count { it.dispatched && it.unknownOutcome } }

    // ---------------------------------------------------------------- job_start

    private fun requestJobStart(entry: OuterCall): ToolResult {
        val args = when (val parsed = parseJobStartArguments(entry.call.argumentsJson)) {
            is JobArguments.Rejected -> return ToolResult.Invalid(parsed.reason)
            is JobArguments.Accepted -> parsed.value
        }
        if (args.tool !in frozenEligible) {
            return ToolResult.Invalid("Tool is not eligible for background execution")
        }
        if (args.argumentsJson.toByteArray(StandardCharsets.UTF_8).size > budget.maxArgumentsBytes) {
            return ToolResult.Invalid("arguments exceed the background argument budget")
        }
        // Capacity is checked before asking for approval, so a full Run never
        // asks the user to approve work it cannot start.
        synchronized(lock) {
            if (jobsStarted >= budget.maxJobsPerRun) return ToolResult.Denied(JOBS_EXHAUSTED)
            if (activeJobCountLocked() >= budget.maxConcurrentJobs) return ToolResult.Denied(CONCURRENCY_EXHAUSTED)
            entry.pending = PendingStart(newJobId(), args.tool, args.argumentsJson)
        }
        return ToolResult.NeedsApproval
    }

    private fun start(job: ManagedJob) {
        // Both coroutines are lazy and the watchdog starts *after* the work, so
        // it can never observe an inactive, not-yet-started job and return
        // before it has anything to watch.  A revocation is therefore monitored
        // for as long as the child call can run.
        val watchdog = scope.launch(start = CoroutineStart.LAZY) { watchAuthorization(job) }
        job.watchdog = watchdog
        val work = scope.launch(start = CoroutineStart.LAZY) { runJob(job) }
        job.work = work
        work.invokeOnCompletion { cause ->
            watchdog.cancel()
            // Guarantees a terminal state even when the body never ran (for
            // example an already-cancelled scope) or died outside the handled
            // paths.
            if (cause != null) finish(job, ManagedJobStatus.CANCELLED, REASON_CALLER, withheld = true)
        }
        work.start()
        watchdog.start()
    }

    private suspend fun runJob(job: ManagedJob) {
        try {
            synchronized(lock) {
                if (job.status == ManagedJobStatus.PENDING_APPROVAL) {
                    job.status = ManagedJobStatus.RUNNING
                    job.startedAtMs = nowMillis()
                }
            }
            val deadlineAt = nowMillis() + budget.jobDeadlineMs
            // Re-read the gate immediately before dispatch: the approval may
            // already be stale, and a revoked grant must not dispatch at all.
            if (!liveAuthorizedSafely()) {
                finish(job, ManagedJobStatus.CANCELLED, REASON_REVOKED, withheld = true)
                return
            }
            val first = try {
                // Reserve the one child call this job really makes, immediately
                // before dispatch: a later revocation must not be charged.
                if (reserveChildCall() < 1) {
                    finish(job, ManagedJobStatus.FAILED, ToolErrorCode.QUOTA_EXCEEDED.name, withheld = true)
                    return
                }
                synchronized(lock) { job.dispatched = true }
                withJobDeadline(deadlineAt) { delegate.invoke(job.childCall) }
            } catch (_: TimeoutCancellationException) {
                finish(job, ManagedJobStatus.TIMED_OUT, ToolErrorCode.TIMEOUT.name)
                return
            }
            val result = if (first == ToolResult.NeedsApproval) {
                if (!liveAuthorizedSafely()) {
                    finish(job, ManagedJobStatus.CANCELLED, REASON_REVOKED, withheld = true)
                    return
                }
                try {
                    withJobDeadline(deadlineAt) { delegate.approve(job.childCall.callId) }
                } catch (_: TimeoutCancellationException) {
                    finish(job, ManagedJobStatus.TIMED_OUT, ToolErrorCode.TIMEOUT.name)
                    return
                }
            } else {
                first
            }
            record(job, result)
        } catch (revoked: JobAuthorizationRevoked) {
            finish(job, ManagedJobStatus.CANCELLED, REASON_REVOKED, withheld = true)
            throw revoked
        } catch (cancelled: CancellationException) {
            finish(job, ManagedJobStatus.CANCELLED, REASON_CALLER, withheld = true)
            throw cancelled
        } catch (_: Exception) {
            // The delegate failed *after* it may have dispatched the call, so a tool
            // with possible side effects has an unknown outcome, never a definite
            // failure: the Run must be lifted to UNKNOWN_OUTCOME instead of
            // completing.  A side-effect-free read changed nothing, so it fails.
            val status = failedAfterDispatchStatus(job.dispatched, job.sideEffect)
            finish(job, status, ToolErrorCode.INTERNAL_ERROR.name, withheld = true)
        }
    }

    /** Fail-closed budget reservation: a throwing callback grants nothing. */
    private fun reserveChildCall(): Int = try {
        reserveCalls(1).coerceIn(0, 1)
    } catch (_: Exception) {
        0
    }

    /**
     * Polls the live gate while the child call runs.  A revocation cancels the
     * delegate call instead of waiting for the next `job_output`, so revoked
     * work stops promptly; the poll interval only bounds the reaction time.
     */
    private suspend fun watchAuthorization(job: ManagedJob) {
        val work = job.work ?: return
        while (work.isActive) {
            if (!liveAuthorizedSafely()) {
                work.cancel(JobAuthorizationRevoked())
                return
            }
            delay(budget.authorizationPollMs)
        }
    }

    /** The job deadline covers the whole nested request, not one step of it. */
    private suspend fun <T> withJobDeadline(deadlineAt: Long, block: suspend () -> T): T {
        val remaining = deadlineAt - nowMillis()
        return withTimeout(if (remaining > 0L) remaining else 1L) { block() }
    }

    // --------------------------------------------------------------- job_output

    private suspend fun jobOutput(entry: OuterCall): ToolResult {
        val args = when (val parsed = parseJobOutputArguments(entry.call.argumentsJson)) {
            is JobArguments.Rejected -> return ToolResult.Invalid(parsed.reason)
            is JobArguments.Accepted -> parsed.value
        }
        val job = synchronized(lock) { jobs[args.jobId] } ?: return ToolResult.Invalid("Unknown job id")
        val cursorArg = args.cursor
        val requestedOffset = when {
            cursorArg != null -> {
                val decoded = cursor.decode(cursorArg) ?: return ToolResult.Invalid("Cursor is invalid")
                if (decoded.first != args.jobId) return ToolResult.Invalid("Cursor does not belong to this job")
                decoded.second
            }
            args.offsetBytes != null -> args.offsetBytes ?: 0
            else -> 0
        }
        val maxBytes = (args.maxBytes ?: budget.defaultChunkBytes).coerceIn(1, budget.maxChunkBytes)
        val waitMs = args.waitMs.coerceIn(0, budget.maxWaitMs.toInt())
        if (waitMs > 0 && !job.status.isTerminal) {
            withTimeoutOrNull(waitMs.toLong()) { job.done.await() }
        }
        // Revocation withholds content and stops the job; the old buffer is
        // purged so a later call cannot resurrect it.
        if (!liveAuthorizedSafely()) {
            revoke(job)
            return ToolResult.Denied(WITHHELD_REASON)
        }
        val bytes = synchronized(lock) { job.buffered } ?: ByteArray(0)
        // An explicit offset may land inside a code point; snap down instead of
        // emitting a broken character.
        val from = utf8Boundary(bytes, requestedOffset.coerceIn(0, bytes.size))
        var to = if (from >= bytes.size) from else utf8Boundary(bytes, (from + maxBytes).coerceAtMost(bytes.size))
        // A cap smaller than the next code point must still advance, otherwise
        // the reader would be stuck on the same offset forever.
        if (to <= from && from < bytes.size) to = utf8NextBoundary(bytes, from)
        val chunk = if (to > from) String(bytes, from, to - from, StandardCharsets.UTF_8) else ""
        // Every page of this job's own result goes through the deferred channel,
        // not the strict cached-replay check.
        if (chunk.isNotEmpty() && !deferredResultAuthorizedSafely(job.childCall)) {
            revoke(job)
            return ToolResult.Denied(WITHHELD_REASON)
        }
        val next = if (to < bytes.size) to else null
        if (chunk.isNotEmpty()) {
            synchronized(lock) { entry.disclosedJobId = job.id }
        }
        val payload = envelope(job, chunk = chunk, offset = from, maxBytes = maxBytes, nextOffset = next)
        return ToolResult.Value(payload.toString())
    }

    // --------------------------------------------------------------- job_cancel

    private suspend fun jobCancel(entry: OuterCall): ToolResult {
        val args = when (val parsed = parseJobIdArguments(entry.call.argumentsJson)) {
            is JobArguments.Rejected -> return ToolResult.Invalid(parsed.reason)
            is JobArguments.Accepted -> parsed.value
        }
        val job = synchronized(lock) { jobs[args.jobId] } ?: return ToolResult.Invalid("Unknown job id")
        // Cancellation needs no authorization: it only removes privilege, and it
        // must stay available after a revocation so live work can be stopped.
        if (!job.status.isTerminal) {
            job.work?.cancel(JobCallerCancelled())
            job.watchdog?.cancel()
            job.work?.join()
            finish(job, ManagedJobStatus.CANCELLED, REASON_CALLER, withheld = true)
        }
        return ToolResult.Value(envelope(job).toString())
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun settlePending(
        callId: String,
        reason: String,
        fallback: suspend (String) -> ToolResult,
    ): ToolResult {
        val entry = synchronized(lock) { calls[callId] } ?: return fallback(callId)
        val pending = synchronized(lock) {
            val current = entry.pending ?: return@synchronized null
            entry.pending = null
            current
        } ?: return entry.result ?: ToolResult.Invalid("Tool call is already settled")
        if (pending.jobId.isBlank()) return ToolResult.Invalid("No pending job start for this call id")
        return settle(entry, ToolResult.Denied(reason))
    }

    private fun settle(entry: OuterCall, result: ToolResult): ToolResult {
        synchronized(lock) { entry.result = result }
        return result
    }

    private fun activeJobCountLocked(): Int = jobs.values.count { !it.status.isTerminal }

    private fun record(job: ManagedJob, result: ToolResult) {
        val completion = classifyJobResult(result)
        synchronized(lock) {
            if (job.status.isTerminal) return
            if (result is ToolResult.Value) {
                val bytes = result.json.toByteArray(StandardCharsets.UTF_8)
                val room = (budget.maxTotalResultBytes - totalBufferedBytes)
                    .coerceAtLeast(0L)
                    .coerceAtMost(budget.maxResultBytesPerJob.toLong())
                val kept = if (bytes.size.toLong() <= room) bytes.size else utf8Boundary(bytes, room.toInt())
                job.buffered = if (kept == bytes.size) bytes else bytes.copyOf(kept)
                job.bufferTruncated = kept < bytes.size
                totalBufferedBytes += kept
            }
            job.status = completion.status
            job.failureCode = completion.failureCode
            job.failureMessage = completion.failureMessage
            if (completion.unknownOutcome) job.unknownOutcome = true
            job.finishedAtMs = nowMillis()
        }
        // Completing outside the monitor keeps a resuming reader from running
        // delegate code while executor state is locked.
        job.done.complete(Unit)
    }

    private fun finish(job: ManagedJob, status: ManagedJobStatus, reason: String?, withheld: Boolean = false) {
        synchronized(lock) {
            if (job.status.isTerminal) return
            job.status = status
            job.statusDetail = reason
            terminalDecision(status, reason, job.sideEffect)?.let { terminal ->
                job.failureCode = terminal.code
                job.failureMessage = terminal.message
                if (terminal.unknownOutcome) job.unknownOutcome = true
            }
            if (withheld) dropContentLocked(job)
            job.finishedAtMs = nowMillis()
        }
        job.done.complete(Unit)
    }

    /**
     * Revocation path: stop the child (if any), then always purge the buffer,
     * including content that was already produced and authorized earlier.
     */
    private suspend fun revoke(job: ManagedJob) {
        job.work?.cancel(JobAuthorizationRevoked())
        job.watchdog?.cancel()
        job.work?.join()
        finish(job, ManagedJobStatus.CANCELLED, REASON_REVOKED, withheld = true)
        synchronized(lock) { dropContentLocked(job) }
    }

    private fun dropContentLocked(job: ManagedJob) {
        val held = job.buffered?.size ?: 0
        if (held > 0) totalBufferedBytes = (totalBufferedBytes - held).coerceAtLeast(0L)
        job.buffered = null
        job.contentWithheld = true
    }

    private fun purgeAndDenyReplay(entry: OuterCall) {
        synchronized(lock) {
            entry.replayDenied = true
            entry.result = null
        }
    }

    private fun envelope(
        job: ManagedJob,
        chunk: String? = null,
        offset: Int = 0,
        maxBytes: Int = 0,
        nextOffset: Int? = null,
    ): JsonObject {
        val view = synchronized(lock) { job.view() }
        return managedJobEnvelope(
            view = view,
            chunk = chunk,
            offset = offset,
            maxBytes = maxBytes,
            nextOffset = nextOffset,
            cursor = nextOffset?.let { cursor.forOffset(job.id, it) },
        )
    }

    private fun jobListEnvelope(): JsonObject = synchronized(lock) {
        managedJobListEnvelope(
            views = jobs.values.map { it.view() },
            jobsStarted = jobsStarted,
            totalResultBytes = totalBufferedBytes,
            budget = budget,
        )
    }

    private fun ManagedJob.view(): ManagedJobView = ManagedJobView(
        id = id,
        tool = toolName,
        status = status,
        resultBytes = buffered?.size ?: 0,
        bufferTruncated = bufferTruncated,
        contentWithheld = contentWithheld,
        unknownOutcome = unknownOutcome,
        statusDetail = statusDetail,
        failureCode = failureCode,
        failureMessage = failureMessage,
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs,
    )

    /** Fail-closed call into an integration gate: a throw means "denied". */
    private suspend fun <T> guardFailure(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }

    private suspend fun liveAuthorizedSafely(): Boolean = guardFailure(false) { liveAuthorized() }

    private suspend fun replayAuthorizedSafely(call: ToolCall): Boolean =
        guardFailure(false) { replayAuthorized(call) }

    private suspend fun deferredResultAuthorizedSafely(call: ToolCall): Boolean =
        guardFailure(false) { deferredResultAuthorized(call) }

    private class PendingStart(val jobId: String, val tool: String, val argumentsJson: String)

    private class OuterCall(
        val call: ToolCall,
        var pending: PendingStart? = null,
        var result: ToolResult? = null,
        var disclosedJobId: String? = null,
        var replayDenied: Boolean = false,
        var running: Boolean = false,
    )

    private class ManagedJob(
        val id: String,
        val toolName: String,
        val childCall: ToolCall,
        val sideEffect: Boolean,
    ) {
        val done = CompletableDeferred<Unit>()

        @Volatile var status: ManagedJobStatus = ManagedJobStatus.PENDING_APPROVAL

        @Volatile var statusDetail: String? = null

        @Volatile var dispatched: Boolean = false

        @Volatile var buffered: ByteArray? = null

        @Volatile var bufferTruncated: Boolean = false

        @Volatile var failureCode: String? = null

        @Volatile var failureMessage: String? = null

        @Volatile var unknownOutcome: Boolean = false

        @Volatile var contentWithheld: Boolean = false

        @Volatile var startedAtMs: Long = 0L

        @Volatile var finishedAtMs: Long? = null

        @Volatile var work: CoroutineJob? = null

        @Volatile var watchdog: CoroutineJob? = null
    }

    private class JobAuthorizationRevoked : CancellationException("job authorization was revoked")

    private class JobCallerCancelled : CancellationException("job was cancelled by the caller")

    private class JobClosed : CancellationException("managed job executor is closed")

    private companion object {
        const val REASON_CALLER = "CANCELLED"
        const val REASON_REVOKED = "AUTHORIZATION_REVOKED"
        const val REASON_CLOSED = "CLOSED"

        const val ALREADY_RUNNING = "Tool call is already executing; use a new call ID"
        const val JOB_TOOL_FAILED = "The job tool call failed; its outcome is unknown"
        const val CALL_ID_REUSED = "Tool call ID was already used for a different request"
        const val CLOSED_REASON = "Managed job executor is closed"
        const val CONCURRENCY_EXHAUSTED = "The Run already has the maximum number of running jobs"
        const val JOBS_EXHAUSTED = "The Run reached its background job limit"
        const val REPLAY_DENIED_REASON = "Tool authorization changed; cached tool output is unavailable"
        const val REVOKED_START_REASON = "Background job authorization was revoked before dispatch"
        const val WITHHELD_REASON =
            "Background job authorization changed; buffered output is withheld and the job was cancelled. " +
                "Do not retry automatically."
    }
}
