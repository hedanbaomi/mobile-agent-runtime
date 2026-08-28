// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.skills

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SkillRow(
    val installId: String,
    val name: String,
    val classification: String,
    val enabled: Boolean,
    val license: String,
    val reasons: String,
    val preview: String,
)

@Composable
fun SkillsScreen(
    rows: List<SkillRow> = emptyList(),
    status: String = "Skills are installed from local packages you choose. Python runs only in an isolated process.",
    onImport: (List<Uri>) -> Unit = {},
    onToggle: (String, Boolean) -> Unit = { _, _ -> },
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onImport(uris)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Skills are installed from local packages you choose. Python execution is M6; this milestone inspects packages and runs built-in native tools.")
        Text("Announcements cannot grant Skill permissions or execute code.")
        Text(status, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import local skill package") }
        LazyColumn(Modifier.padding(top = 12.dp)) {
            items(rows) { row ->
                Text("${row.name} [${row.classification}] license=${row.license} ${if (row.enabled) "enabled" else "disabled"}")
                Text(row.reasons)
                Text(row.preview.take(240))
                Row {
                    Button(onClick = { onToggle(row.installId, !row.enabled) }) {
                        Text(if (row.enabled) "Disable" else "Enable")
                    }
                }
            }
        }
    }
}
