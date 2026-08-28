// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import runtime.mobileagent.feature.settings.R as SettR

@Composable
fun AboutScreen(
    versionName: String,
    gitRevision: String,
    statsEnabled: Boolean = false,
    onStats: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val agpl = runCatching {
        context.assets.open("AGPL-3.0-only.txt").bufferedReader().use(BufferedReader::readText)
    }.getOrDefault("AGPL-3.0-only text is bundled in the APK assets.")
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("mobileAgentRuntime", style = MaterialTheme.typography.headlineSmall)
        Text("Version $versionName ($gitRevision)")
        Text("License: AGPL-3.0-only")
        Text("Source: https://github.com/hedanbaomi/mobile-agent-runtime")
        Text(
            "This is free software under GNU Affero General Public License version 3.0 only. " +
                "API keys stay on this device. The announcement service is independent of your model provider.",
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(stringResource(SettR.string.sett_anonymous_metrics), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(SettR.string.sett_metrics_help), style = MaterialTheme.typography.bodySmall)
        Switch(checked = statsEnabled, onCheckedChange = onStats)
        Text(agpl, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
}
