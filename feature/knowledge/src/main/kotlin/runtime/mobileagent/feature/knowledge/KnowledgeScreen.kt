// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class KnowledgeJobRow(
    val id: String,
    val displayName: String,
    val stage: String,
    val error: String?,
    val updatedAt: String,
)

@Composable
fun KnowledgeScreen(
    jobs: List<KnowledgeJobRow>,
    status: String,
    onImport: (List<Uri>) -> Unit,
    onRebuild: () -> Unit = {},
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onImport(uris)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Knowledge bases stay on this device. Images without a Vision model wait; they are never silently dropped.")
        Text("Import uses the system file picker. Python and Skills cannot see real filesystem paths.")
        Text(status, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import with system picker") }
        Button(onClick = onRebuild, modifier = Modifier.padding(top = 8.dp)) { Text("Rebuild index") }
        LazyColumn(Modifier.padding(top = 12.dp)) {
            items(jobs) { job ->
                val detail = job.error?.let { " — $it" }.orEmpty()
                Text("${job.displayName}: ${job.stage}$detail")
            }
        }
    }
}
