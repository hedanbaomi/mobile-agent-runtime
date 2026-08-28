// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.feature.skills.SkillRow
import runtime.mobileagent.knowledge.MediaKind
import java.io.ByteArrayOutputStream

class SkillsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val rows = mutableStateListOf<SkillRow>()
    val status = mutableStateOf("Import a local zip or SKILL.md. Class E packages are refused. Python isolation is not in this build.")

    init {
        reload()
    }

    fun reload() {
        rows.clear()
        app.container.skills.list().forEach { skill ->
            rows += SkillRow(
                installId = skill.installId,
                name = skill.name,
                classification = skill.classification.name,
                enabled = skill.enabled,
                license = skill.license,
                reasons = skill.reasons.joinToString("; "),
                preview = skill.skillMarkdown.orEmpty(),
            )
        }
    }

    fun toggle(installId: String, enabled: Boolean) {
        app.container.skills.setEnabled(installId, enabled)
        reload()
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                val name = uri.lastPathSegment ?: "skill"
                try {
                    val bytes = withContext(Dispatchers.IO) { readLimited(uri) }
                    val result = withContext(Dispatchers.IO) { app.container.skills.importPackage(bytes) }
                    status.value = if (result.accepted) {
                        "$name classified ${result.inspection.classification} and stored. Scripts are not auto-run."
                    } else {
                        "$name refused (${result.inspection.classification}): ${result.inspection.reasons.joinToString()}"
                    }
                } catch (e: Exception) {
                    status.value = "$name failed: ${e.message ?: "import error"}"
                }
            }
            reload()
        }
    }

    private fun readLimited(uri: Uri): ByteArray {
        val input = app.contentResolver.openInputStream(uri) ?: error("Could not open the selected file")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > MediaKind.MAX_IMPORT_BYTES) error("RESOURCE_LIMIT")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
