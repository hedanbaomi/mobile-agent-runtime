// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.Authority

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

    /** Opens the manager UI and returns the user-facing error message, or null on success. */
    fun openRootManagerMessage(open: () -> Boolean): String? =
        if (open()) null else "无法打开 Root 管理器；请先安装并打开管理器后再授权。"

    /** The intent toggle only changes intent; it never selects the channel. */
    fun applyIntent(setIntent: () -> Unit) = setIntent()
}
