// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import runtime.mobileagent.skills.ToolExecutor

/** One execution's resources, deadline watcher and teardown; never owns page selection. */
internal class ChatRunSession(val owner: ChatRunOwner) {
    var job: Job? = null
    var approval: CompletableDeferred<Boolean>? = null
    var approvalCallId: String? = null
    var executor: ToolExecutor? = null
    var productivity: RunProductivityTools? = null
    var confirmedDeviceAction: (String) -> ConfirmedDeviceActionReceipt? = { null }

    fun watch(
        scope: CoroutineScope,
        execution: ChatRunExecution,
        idleLimitMs: Long,
        progress: AtomicLong,
        terminal: () -> Boolean,
        onTimeout: () -> Unit,
        diagnostic: () -> Unit,
    ): Job = scope.launch {
        execution.awaitWatchdog(idleLimitMs) { progress.get() }
        if (terminal()) return@launch
        onTimeout()
        approval?.complete(false)
        approval = null
        approvalCallId = null
        job?.cancel(CancellationException("Run watchdog deadline exceeded"))
        runCatching(diagnostic)
    }

    /** Called after durable approval settlement, even when final persistence failed. */
    suspend fun closeBindings(secret: CharArray?) {
        runCatching { executor?.close() }
        secret?.fill('\u0000')
        approval?.complete(false)
        approval = null
        approvalCallId = null
        executor = null
        productivity = null
        confirmedDeviceAction = { null }
    }
}
