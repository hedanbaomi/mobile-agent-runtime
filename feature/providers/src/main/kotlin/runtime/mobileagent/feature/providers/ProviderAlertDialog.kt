// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.providers

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Dialog windows establish their own Android locals; retain the screen's app language in slots. */
@Composable
internal fun ProviderAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = title) },
        text = { CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = text) },
        confirmButton = { CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = confirmButton) },
        dismissButton = dismissButton?.let { content ->
            { CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = content) }
        },
    )
}
