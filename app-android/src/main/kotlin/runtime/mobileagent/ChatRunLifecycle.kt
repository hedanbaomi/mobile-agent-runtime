// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.domain.RunRecord

/**
 * Starts the run coroutine undispatched from the caller's session context so `resources.job` and
 * `runJob` are bound before outer preflight `finally` inspects admission, even on queued dispatchers.
 */
internal fun launchChatSessionLifecycle(
    scope: CoroutineScope,
    ownership: ChatRunOwnership,
    resources: ChatRunSession,
    onStarted: (Job) -> Unit,
    onFailure: (Exception) -> Unit,
    onFinished: () -> Unit,
    block: suspend () -> Unit,
): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    val job = checkNotNull(currentCoroutineContext()[Job])
    resources.job = job
    onStarted(job)
    runChatSessionLifecycle(ownership, resources, onFailure, onFinished, block)
}

/** Stamps the run manifest fail-closed while letting coroutine cancellation propagate cleanly. */
internal suspend fun tryStampChatRunManifest(stamp: suspend () -> RunRecord): RunRecord? = try {
    stamp()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** Covers bootstrap as well as execution; a failed initial repository read cannot retain admission. */
internal suspend fun runChatSessionLifecycle(
    ownership: ChatRunOwnership,
    resources: ChatRunSession,
    onFailure: (Exception) -> Unit,
    onFinished: () -> Unit,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    } finally {
        withContext(NonCancellable) {
            try {
                onFinished()
            } finally {
                ownership.finish(resources.owner)
            }
        }
    }
}
