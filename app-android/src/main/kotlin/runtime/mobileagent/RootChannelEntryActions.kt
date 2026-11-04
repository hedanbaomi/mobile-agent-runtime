// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode

/**
 * Explicit foreground entry actions for the two Root channels.
 *
 * Extracted from [SettingsViewModel] for the conditional-token ratchet. A plain intent toggle never
 * selects a channel here: selection happens only from the explicit primary/enable action, before the
 * consent is bound to the fresh policy version. No permission is requested in the background.
 */
internal object RootChannelEntryActions {
    fun enableShizukuRoot(
        platformReady: () -> Boolean,
        requestPermission: () -> Unit,
        select: () -> Unit,
        setIntent: () -> Unit,
        clearError: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        // Two explicit steps: the first click requests the platform permission; after the user
        // authorizes, a second click enables and selects the channel (the foreground Root request).
        try {
            if (!platformReady()) {
                requestPermission()
                clearError()
                return
            }
            select()
            setIntent()
            clearError()
        } catch (failure: Exception) {
            onError(failure)
        }
    }

    /**
     * Opens the manager UI and returns the caller-localized failure message, or null on success.
     * The copy lives in the caller's resources so this action object owns no UI text.
     */
    fun openRootManagerMessage(open: () -> Boolean, failureMessage: String): String? =
        if (open()) null else failureMessage

    /**
     * One confirmed dangerous-mode change: publish the returned snapshot, then report the durable
     * reason. A thrown failure is reported through [onError] instead of escaping, and the reason
     * table stays with the caller that owns the localized copy.
     *
     * The explicit `set` and `disable` entries share this, so the order "apply, publish snapshot,
     * report reason" exists once instead of once per entry point.
     */
    fun applyDangerousMode(
        mode: DangerousMode,
        apply: (DangerousMode) -> SettingsAuthorityMutation,
        onSnapshot: (SettingsAuthoritySnapshot) -> Unit,
        onError: (String?) -> Unit,
        reasonMapper: (String?) -> String,
        errorMapper: (Throwable) -> String,
    ) {
        val result = runCatching { apply(mode) }.getOrElse { failure ->
            onError(errorMapper(failure))
            return
        }
        onSnapshot(result.snapshot)
        onError(if (result.accepted) null else reasonMapper(result.reason))
    }

    /** The intent toggle only changes intent; it never selects the channel. */
    fun applyIntent(setIntent: () -> Unit) = setIntent()
}
