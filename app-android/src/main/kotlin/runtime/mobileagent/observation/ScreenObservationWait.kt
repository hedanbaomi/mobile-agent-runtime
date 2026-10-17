// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one event-conditional wait behind `device_wait_for` and the `device_act`
 * `observe_after` option, so the two tools cannot drift apart.
 *
 * Events only wake the wait; every decision is made on a freshly captured
 * snapshot.  An OS-confirmed action or an unrelated accessibility event is
 * therefore never mistaken for a completed screen transition.
 */
internal object ScreenObservationWait {
    /** The shared reason for "the channel this read belonged to is gone". */
    const val ACCESS_CHANGED = "SCREEN_ACCESS_CHANGED"

    /** The model-facing result ceiling shared with the observation tool. */
    const val RESULT_LIMIT_BYTES = 96 * 1024

    /** The one accepted package-name shape. */
    val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_.]{0,199}")

    /** A frame-level miss: the wait retries it, the caller never sees it. */
    private val RETRYABLE = setOf("SCREEN_PACKAGE_MISMATCH", "SCREEN_WINDOW_UNAVAILABLE", "SCREEN_WINDOW_CHANGED")

    private suspend fun capture(port: ScreenObservationPort, expectedPackage: String?): ScreenSnapshot? = try {
        port.capture(expectedPackage)
    } catch (failure: ScreenObservationException) {
        if (failure.code in RETRYABLE) null else throw failure
    }

    /**
     * Wait for a stable snapshot that is newer than [afterSequence], sits in
     * [expectedPackage] when one is given, and contains [text] when one is
     * given.  Returns null only when [timeoutMs] elapses without such a
     * snapshot; a retryable frame miss keeps waiting instead of reporting a
     * timeout, and a revoked channel throws [ScreenObservationException].
     */
    suspend fun await(
        port: ScreenObservationPort,
        admitted: ScreenAccess?,
        authorized: suspend () -> Boolean,
        expectedPackage: String?,
        text: String?,
        afterSequence: Long?,
        timeoutMs: Long,
    ): ScreenSnapshot? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!authorized()) throw ScreenObservationException(ACCESS_CHANGED)
            // Capture the event counter before reading, so a concurrent event is never lost.
            val before = port.changes.value
            val snapshot = capture(port, expectedPackage)
            if (snapshot != null && snapshot.stable &&
                (afterSequence == null || snapshot.sequence > afterSequence) &&
                (text == null || snapshot.nodes.any { text in it.text || text in it.description })) {
                return@withTimeoutOrNull snapshot
            }
            combine(port.changes, port.access) { change, access -> change != before || access != admitted }.first { it }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }
}
