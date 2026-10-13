// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent


/**
 * Shared test-side support for direct feature-screen Compose tests.
 *
 * A direct feature test renders a feature screen without the production shell. The production
 * shell aligns Android string resources with the UI-state language through
 * `runtime.mobileagent.ui.AppLocalizedResources(language)` (see `MainScreens.kt`); a direct test
 * that only passes `ChatUiState.language` leaves `stringResource(...)` resolved against the host
 * Activity locale. These helpers keep that contract identical to production without changing any
 * product code.
 */

/**
 * Turns a silent "timed out waiting for state" failure into an actionable signal.
 *
 * The condition is observed repeatedly. When the timeout expires, or when it throws, every
 * message supplied by [observation] is appended to the failure so the real reason (missing row,
 * stuck busy flag, recorded status/error text, unexpected value) is visible in the report instead
 * of being swallowed by a generic timeout.
 */
internal inline fun awaitUiState(
    timeoutMs: Long = 5_000,
    pollMs: Long = 25,
    observation: () -> List<String>,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    val started = System.currentTimeMillis()
    var lastFailure: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            if (condition()) return
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("interrupted while waiting for UI state", interrupted)
        } catch (failure: Throwable) {
            // Keep the failure so the timeout report names the real reason instead of hiding it.
            lastFailure = failure
        }
        try {
            Thread.sleep(pollMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("interrupted while waiting for UI state", interrupted)
        }
    }
    val elapsed = System.currentTimeMillis() - started
    val details = buildString {
        append("timed out after ").append(elapsed).append("ms (budget ").append(timeoutMs).append("ms)")
        observation().forEach { append("\n  ").append(it) }
        lastFailure?.let { append("\n  last condition failure: ").append(it) }
    }
    throw AssertionError(details)
}
