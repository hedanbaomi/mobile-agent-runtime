// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.feature.skills.SkillRow
import runtime.mobileagent.feature.skills.*
import runtime.mobileagent.knowledge.MediaKind
import runtime.mobileagent.skills.SkillArchive
import runtime.mobileagent.skills.SkillInspection
import runtime.mobileagent.skills.CompatibilityClass
import runtime.mobileagent.provider.SecretRedactor
import java.io.ByteArrayOutputStream

class SkillsViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val rows = mutableStateListOf<SkillRow>()
    val status = mutableStateOf("Import a local zip or SKILL.md. Class E packages are refused. Isolated CPython runs imported Python skills.")
    val state = mutableStateOf(SkillsUiState())
    val permissionRequest = mutableStateOf<Pair<String, String>?>(null)
    private val pendingImports = ArrayDeque<Pair<String, SkillInspection>>()

    init {
        reload()
        state.value = state.value.copy(
            query = savedStateHandle.get<String>(QUERY_KEY).orEmpty(),
            filter = savedStateHandle.get<String>(FILTER_KEY) ?: "all",
        )
        savedStateHandle.get<String>(SELECTED_INSTALL_ID_KEY)?.let(::openDetail)
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
        state.value = state.value.copy(skills = app.container.skills.list().map { skill ->
            SkillUi(installId = skill.installId, name = skill.name, classification = skill.classification.name,
                enabled = skill.enabled, license = skill.license, reasons = skill.reasons, packageHash = skill.packageHash,
                installable = skill.classification != CompatibilityClass.E)
        }, status = status.value)
    }

    fun toggle(installId: String, enabled: Boolean) {
        try {
            if (enabled) {
                val inspection = app.container.skills.inspect(installId)
                if (inspection.classification == CompatibilityClass.B && inspection.manifest?.permissions.isNullOrEmpty()) {
                    app.container.skills.approvePermissions(installId, emptySet())
                }
            }
            app.container.skills.setEnabled(installId, enabled)
            message(if (enabled) "已启用。执行时仍会复核当前授权；导入或启用不会自动运行脚本。" else "已停用，后续能力请求会立即拒绝。")
            reload()
            if (state.value.selectedInstallId == installId) openDetail(installId)
        } catch (error: Exception) { message(error.message ?: "请先检查并确认该包权限。") }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            try {
                require(uris.size <= 10) { "一次最多检查 10 个 Skill 包。" }
                var total = 0L
                val inspected = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val bytes = readLimited(uri)
                        total += bytes.size
                        require(total <= 100L * 1024 * 1024) { "此次检查的包总量不能超过 100 MiB。" }
                        displayName(uri) to SkillArchive.inspect(bytes)
                    }
                }
                pendingImports.addAll(inspected)
                nextImport()
            } catch (error: Exception) {
                message(error.message ?: "读取或检查 Skill 失败。")
            } finally {
                state.value = state.value.copy(loading = false)
            }
        }
    }

    fun confirmInstall() {
        val pending = pendingImports.removeFirstOrNull() ?: return
        viewModelScope.launch {
            try {
                val inspection = pending.second
                require(inspection.installable) { inspection.reasons.joinToString() }
                val result = withContext(Dispatchers.IO) {
                    app.container.skills.importPackage(inspection.packageBytes ?: error("包内容不可用"), inspection.packageHash)
                }
                message(if (result.accepted) "已安装 ${pending.first}，尚未启用或授予权限。" else "包未安装：${result.inspection.reasons.joinToString()}")
                reload()
            } catch (error: Exception) { message(error.message ?: "安装失败。") }
            nextImport()
        }
    }

    fun cancelInstall() { pendingImports.removeFirstOrNull(); nextImport() }

    private fun nextImport() {
        val pending = pendingImports.firstOrNull()
        state.value = state.value.copy(install = pending?.let { (name, inspection) ->
            SkillInstallUi(name, inspection.packageHash, inspection.classification.name, inspection.reasons,
                permissions = inspection.manifest?.permissionSpecs.orEmpty().map { spec ->
                    SkillPermissionUi(spec.capability, scopeLabel(spec.knowledgeBaseIds, spec.hosts, spec.methods), false)
                }, installable = inspection.installable,
                status = "原包保持不变；安装只保存到本机。逐资源授权和启用需另行确认。")
        })
    }

    fun openDetail(installId: String) {
        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    val skill = app.container.skills.get(installId) ?: error("Skill 已移除。")
                    val inspection = app.container.skills.inspect(installId)
                    val grants = app.container.skills.grantsFor(installId).filter { !it.revoked && it.packageHash == skill.packageHash }
                    val caps = grants.flatMap { it.capabilities }.toSet()
                    SkillDetailUi(
                        skill = SkillUi(skill.installId, skill.name, inspection.manifest?.version.orEmpty(), skill.classification.name,
                            skill.enabled, skill.license, skill.reasons, skill.packageHash, inspection.installable),
                        preview = skill.skillMarkdown.orEmpty(), manifestJson = inspection.rawManifestJson.orEmpty(),
                        permissions = inspection.manifest?.permissionSpecs.orEmpty().map { spec ->
                            SkillPermissionUi(spec.capability, scopeLabel(spec.knowledgeBaseIds, spec.hosts, spec.methods), spec.capability in caps)
                        },
                        files = inspection.files.map { SkillSourceFileUi(it, kind = "纯文本预览，不执行") },
                    )
                }
                savedStateHandle[SELECTED_INSTALL_ID_KEY] = installId
                state.value = state.value.copy(selectedInstallId = installId, detail = detail, error = null)
            } catch (error: Exception) { message(error.message ?: "读取 Skill 失败。") }
        }
    }

    fun closeDetail() {
        savedStateHandle.remove<String>(SELECTED_INSTALL_ID_KEY)
        state.value = state.value.copy(selectedInstallId = null, detail = null)
    }
    fun query(value: String) {
        savedStateHandle[QUERY_KEY] = value
        state.value = state.value.copy(query = value)
    }
    fun filter(value: String) {
        savedStateHandle[FILTER_KEY] = value
        state.value = state.value.copy(filter = value)
    }

    fun openSource(installId: String, path: String) {
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) { app.container.skills.sourceText(installId, path) }
                savedStateHandle[SOURCE_PATH_KEY] = path
                state.value = state.value.copy(sourcePath = path, sourceText = content)
            } catch (error: Exception) { message(error.message ?: "无法预览文件。") }
        }
    }
    fun closeSource() {
        savedStateHandle.remove<String>(SOURCE_PATH_KEY)
        state.value = state.value.copy(sourcePath = null, sourceText = null)
    }

    fun beginGrant(installId: String, capability: String) { permissionRequest.value = installId to capability }
    fun cancelGrant() { permissionRequest.value = null }

    fun confirmGrant(selectedKnowledgeBaseIds: Set<String>) {
        val (id, capability) = permissionRequest.value ?: return
        try {
            val inspection = app.container.skills.inspect(id)
            val current = app.container.skills.grantsFor(id).singleOrNull { !it.revoked && it.packageHash == inspection.packageHash }
            val caps = current?.capabilities.orEmpty() + capability
            val spec = inspection.manifest?.permissionSpecs?.singleOrNull { it.capability == capability } ?: error("包未声明此权限。")
            val kbs = if (capability in setOf("knowledge.search", "knowledge.read", "document.read")) selectedKnowledgeBaseIds else current?.knowledgeBaseIds.orEmpty()
            val hosts = if (capability == "network.http") spec.hosts else current?.hosts.orEmpty()
            val methods = if (capability == "network.http") spec.methods.ifEmpty { setOf("GET") } else current?.methods.orEmpty()
            app.container.skills.approvePermissions(id, caps, kbs, hosts, methods)
            permissionRequest.value = null
            message("权限已保存到本机并绑定当前包哈希。执行时仍受 Agent 绑定范围限制。")
            openDetail(id)
        } catch (error: Exception) { message(error.message ?: "授权失败。") }
    }

    fun revokePermission(installId: String, capability: String) {
        try {
            val current = app.container.skills.grantsFor(installId).singleOrNull { !it.revoked } ?: return
            val caps = current.capabilities - capability
            app.container.skills.approvePermissions(installId, caps,
                if (caps.any { it in setOf("knowledge.search", "knowledge.read", "document.read") }) current.knowledgeBaseIds else emptySet(),
                if ("network.http" in caps) current.hosts else emptySet(),
                if ("network.http" in caps) current.methods else emptySet())
            message("已撤销 $capability；新的能力请求立即生效。")
            openDetail(installId)
        } catch (error: Exception) { message(error.message ?: "撤权失败。") }
    }

    fun availableKnowledgeBases(): List<Pair<String, String>> = app.container.knowledge.listKnowledgeBases()

    private fun message(value: String) {
        status.value = SecretRedactor.redact(value)
        state.value = state.value.copy(status = status.value)
    }

    private fun scopeLabel(kbs: Set<String>, hosts: Set<String>, methods: Set<String>): String =
        listOfNotNull(kbs.takeIf { it.isNotEmpty() }?.joinToString(prefix = "知识库："),
            hosts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "目的域名："),
            methods.takeIf { it.isNotEmpty() }?.joinToString(prefix = "HTTP 方法："))
            .joinToString("；").ifBlank { "需要用户选择资源，默认无权限" }

    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return "Skill 包"
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

    private companion object {
        const val SELECTED_INSTALL_ID_KEY = "skills.selectedInstallId"
        const val QUERY_KEY = "skills.query"
        const val FILTER_KEY = "skills.filter"
        const val SOURCE_PATH_KEY = "skills.sourcePath"
    }
}
