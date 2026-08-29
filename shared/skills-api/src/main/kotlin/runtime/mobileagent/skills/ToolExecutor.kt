// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

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
}

/** Source-compatible descriptive alias for callers that name the bridge. */
typealias ToolBrokerExecutor = BlockingToolExecutor

fun ToolBroker.asToolExecutor(): ToolExecutor = BlockingToolExecutor(this)
