// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

const val SOURCE_REPOSITORY_URL = "https://github.com/hedanbaomi/mobile-agent-runtime"

/** Version, licensing and source information reached from Settings. */
@Composable
fun AboutScreen(
    state: SettingsUiState,
    actions: SettingsActions = SettingsActions(),
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showVersion by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var agplText by remember(state.licenseText) { mutableStateOf(state.licenseText) }
    val licenseUnavailable = stringResource(R.string.about_license_unavailable)
    LaunchedEffect(showLicense, state.licenseText) {
        if (showLicense && agplText == null) {
            agplText = ThirdPartyNoticeAssets.loadAgplText(context).getOrElse { licenseUnavailable }
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("about.screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPageTitle) Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("mobileAgentRuntime", style = MaterialTheme.typography.titleMedium)
                Text("${state.versionName} (${state.gitRevision})", style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.about_build, state.schemaVersion, state.buildTimeUtc),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("AGPL-3.0-only", style = MaterialTheme.typography.bodySmall)
                Text(SOURCE_REPOSITORY_URL, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = actions.onOpenSource, modifier = Modifier.testTag("about.github")) {
                    Text(stringResource(R.string.about_open_source))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showVersion = true }) { Text(stringResource(R.string.about_version_details)) }
                    OutlinedButton(onClick = { showLicense = true }) { Text(stringResource(R.string.about_license)) }
                }
                OutlinedButton(onClick = actions.onOpenThirdPartyNotices) {
                    Text(stringResource(R.string.about_third_party))
                }
                if (state.noticeCount > 0) TextButton(onClick = actions.onOpenAnnouncements) {
                    Text(stringResource(R.string.about_news_count, state.noticeCount))
                }
                OutlinedButton(onClick = actions.onCheckUpdates) { Text(stringResource(R.string.about_check_updates)) }
                if (state.updateState.isNotBlank()) Text(state.updateState, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (showVersion) {
        AlertDialog(
            onDismissRequest = { showVersion = false },
            title = { Text("mobileAgentRuntime") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${state.versionName}\n${state.diagnosticText.trim()}\nAGPL-3.0-only")
                    Text(
                        stringResource(R.string.about_diagnostics_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { Button(onClick = { showVersion = false }) { Text(stringResource(R.string.about_close)) } },
            dismissButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.diagnosticText)) }) {
                    Text(stringResource(R.string.about_copy_diagnostics))
                }
            },
        )
    }
    if (showLicense) {
        val license = agplText ?: stringResource(R.string.about_license_loading)
        AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("AGPL-3.0-only") },
            text = { Text(license, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { Button(onClick = { showLicense = false }) { Text(stringResource(R.string.about_close)) } })
    }
    if (state.thirdPartyNotices.opened) {
        ThirdPartyNoticesDialog(state = state.thirdPartyNotices, chinese = context.resources.configuration.locales[0].language == "zh",
            onSelect = actions.onSelectThirdPartyNotice, onClose = actions.onCloseThirdPartyNotices)
    }
}
