// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.RootForcedShutdownHandle

/**
 * Runs the bounded termination attempt that precedes the 5s settlement of a forced shutdown.
 * A request id is confirmed to the handle only when the runner observed the whole process
 * group (and any observed escapee) gone; everything else stays unconfirmed, so the session
 * reports `UNKNOWN_OUTCOME`/`DEGRADED` instead of a clean stop.
 */
internal object RootServiceShutdownCollector {
    fun terminateInflight(
        handle: RootForcedShutdownHandle,
        runners: List<RootShellCommandRunner>,
        aliasResolver: (String) -> Set<String> = { setOf(it) },
    ) {
        val budgetMs = (handle.deadlineElapsedMs - handle.startedAtElapsedMs).coerceAtLeast(0L)
        runners.asSequence()
            .flatMap { runner ->
                runCatching { runner.terminateAll(budgetMs) }.getOrDefault(emptySet()).asSequence()
            }
            .flatMap { requestId -> aliasResolver(requestId).asSequence() }
            .forEach { requestId -> handle.confirmRequestTerminated(requestId, descendantsConfirmedDead = true) }
    }
}
