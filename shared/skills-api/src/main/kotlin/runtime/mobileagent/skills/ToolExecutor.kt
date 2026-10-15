// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import runtime.mobileagent.skills.tooling.ToolErrorCode

/**
 * Runtime-facing tool boundary.
 *
 * Implementations may be backed by built-in tools, the Python broker, or a
 * remote protocol adapter.  The runtime only depends on this suspendable
 * contract and the immutable specification snapshot exposed by [specs].
 */
interface ToolExecutor {
    val specs: List<ToolSpec>

    suspend fun invoke(call: ToolCall): ToolResult

    suspend fun approve(callId: String): ToolResult

    /**
     * Re-validate disclosure of an already-completed result *without*
     * re-executing the tool.  Runtimes call this before serving a cached
     * result for a repeated call id: `true` discloses the cache, `false`
     * denies without dispatch and without leaking the old payload.
     *
     * The default denies disclosure (fail-closed).  Only a pure,
     * resource-free computation with no authorization semantics may override
     * to allow; every resource-backed or external tool must override with an
     * explicit live revalidation, or inherit this denial.  A forgotten
     * override therefore denies instead of leaking (b07 follow-up finding A).
     */
    suspend fun authorizeReplay(call: ToolCall): Boolean = false

    /** Explicitly deny a pending approval; implementations may resolve callId to requestId. */
    suspend fun reject(callId: String): ToolResult = ToolResult.Denied(ToolErrorCode.APPROVAL_DENIED.name)

    /** Explicitly expire a pending approval; implementations may resolve callId to requestId. */
    suspend fun expire(callId: String): ToolResult = ToolResult.Denied(ToolErrorCode.TIMEOUT.name)

    /**
     * Re-validate disclosure of a result produced by the **same** execution,
     * including a deferred or paged continuation of it (a `job_output` page, or
     * the final envelope of an explicit batch).  Nothing is re-executed and no
     * tool is invoked: this is the first/continued disclosure of a result this
     * execution already produced, not a cached replay of an earlier answer.
     *
     * The default keeps the strict replay semantics, so an implementation that
     * adds no deferred channel behaves exactly as before.  An override must not
     * widen this into a general replay: repeating a call id for an
     * already-delivered answer still goes through [authorizeReplay].
     */
    suspend fun authorizeDeferredResult(call: ToolCall): Boolean = authorizeReplay(call)

    /**
     * Optional lifecycle hook for executors that own background work.
     *
     * The default is a no-op, so every existing implementation keeps compiling
     * and behaving exactly as before.  An executor that starts its own
     * coroutines, timers, or other run-owned resources overrides this to release
     * them when the Run ends.
     *
     * An override must be *terminal*: cancelling alone is not enough.  It must
     * not return before the children it started have actually stopped (cancel
     * **and** join), because a Run that has finished must not leave stray
     * coroutines, threads, sockets, or file handles behind.  It must never throw
     * for a partially-completed job; an interrupted child with possible side
     * effects settles as an unknown outcome, while a read-only child may settle
     * as a definite stop.
     */
    suspend fun close() {}
}

/** Which gate a settled result must pass before it is disclosed again. */
enum class ResultDisclosure {
    /** A repeated call id for an already-delivered answer: [ToolExecutor.authorizeReplay]. */
    REPLAY,

    /** A continuation of the same execution: [ToolExecutor.authorizeDeferredResult]. */
    DEFERRED,
}

/** Route one disclosure check to the matching gate, so wrappers never re-spell the switch. */
suspend fun ToolExecutor.authorizeDisclosure(call: ToolCall, disclosure: ResultDisclosure): Boolean =
    when (disclosure) {
        ResultDisclosure.REPLAY -> authorizeReplay(call)
        ResultDisclosure.DEFERRED -> authorizeDeferredResult(call)
    }

/**
 * Compatibility bridge for the existing synchronous [ToolBroker] API.
 *
 * The broker can block on host I/O.  [runInterruptible] is deliberate: a
 * coroutine timeout or cancellation must interrupt the worker thread so the
 * broker and its HTTP transport can close the active socket.
 */
class BlockingToolExecutor(
    private val broker: ToolBroker,
) : ToolExecutor {
    override val specs: List<ToolSpec> = BuiltinTools.all

    override suspend fun invoke(call: ToolCall): ToolResult =
        runInterruptible(Dispatchers.IO) { broker.invoke(call) }

    override suspend fun approve(callId: String): ToolResult =
        runInterruptible(Dispatchers.IO) { broker.approve(callId) }

    override suspend fun authorizeReplay(call: ToolCall): Boolean =
        runInterruptible(Dispatchers.IO) { broker.authorizeReplay(call) }
}

/** Source-compatible descriptive alias for callers that name the bridge. */
typealias ToolBrokerExecutor = BlockingToolExecutor

fun ToolBroker.asToolExecutor(): ToolExecutor = BlockingToolExecutor(this)
