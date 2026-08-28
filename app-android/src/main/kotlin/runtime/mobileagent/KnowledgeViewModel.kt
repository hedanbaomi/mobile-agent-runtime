// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.feature.knowledge.KnowledgeJobRow
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MediaKind
import java.io.ByteArrayOutputStream

class KnowledgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val jobs = mutableStateListOf<KnowledgeJobRow>()
    val status = mutableStateOf("Pick files with the system picker. Images wait if no Vision model is configured; they are never silently dropped.")

    init {
        reload()
    }

    fun reload() {
        jobs.clear()
        app.container.knowledge.listJobs().forEach { (job, name, updated) ->
            jobs += KnowledgeJobRow(job.id, name, job.stage.name, job.error, updated)
        }
        val waiting = app.container.knowledge.waitingForVisionCount()
        if (waiting > 0) {
            status.value = "$waiting file(s) waiting for a Vision model. They stay in local CAS and are not marked READY."
        }
    }

    fun rebuild() {
        viewModelScope.launch(Dispatchers.IO) {
            val kb = app.container.knowledge.ensureDefaultBase()
            app.container.knowledge.rebuildIndex(kb)
            withContext(Dispatchers.Main) {
                status.value = "Index rebuilt from SQLite. Old deleted documents stay excluded."
                reload()
            }
        }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val vision = app.container.profiles.visionConfigured()
            uris.forEach { uri ->
                val name = displayName(uri)
                try {
                    val bytes = withContext(Dispatchers.IO) { readLimited(uri) }
                    val mime = app.contentResolver.getType(uri).orEmpty()
                    val job = withContext(Dispatchers.IO) {
                        app.container.knowledge.importBytes(name, mime, bytes, vision)
                    }
                    if (job.stage == ImportStage.WAITING_FOR_VISION_MODEL) {
                        status.value = "$name copied. Waiting for a Vision model — not READY."
                    } else if (job.stage == ImportStage.READY) {
                        status.value = "$name is ready for on-device lexical and local-hash retrieval. ONNX model pack is still separate."
                    } else {
                        status.value = "$name: ${job.stage}${job.error?.let { " — $it" } ?: ""}"
                    }
                } catch (e: Exception) {
                    status.value = "$name failed: ${e.message ?: "import error"}"
                }
            }
            reload()
        }
    }

    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "file"
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
