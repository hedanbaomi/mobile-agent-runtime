// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.announcements.AnnouncementCategory
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.diagnostics.DiagnosticSanitizer
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.domain.SecretStatus
import runtime.mobileagent.domain.ThemePreference
import runtime.mobileagent.feature.settings.SettingsUiState
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.serialization.TransferOptions
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val preferences = mutableStateOf(app.container.settings.get())
    val exportStatus = mutableStateOf("")
    val updateStatus = mutableStateOf("当前为 debug 验证版，正式 release 将由用户另行确认。")
    val inspectorEnabled = mutableStateOf(app.container.uiPreferences.getBoolean("request-inspector", true))
    val diagnosticsStatus = mutableStateOf("")
    val webSearchStatus = mutableStateOf("")
    val error = mutableStateOf<String?>(null)
    private var pendingExport: Pair<String, TransferOptions>? = null
    private var transferRunning = false
    private var updateCheckRunning = false

    fun uiState(statsEnabled: Boolean, noticeCount: Int): SettingsUiState {
        val diagnosticFiles = app.diagnostics.status()
        val searchRef = app.container.settings.webSearchSecretRef()
        val searchConfigured = searchRef != null && app.container.secrets.inventory().status(searchRef) == SecretStatus.ACTIVE
        return SettingsUiState(
        versionName = BuildConfig.VERSION_NAME + " debug",
        gitRevision = BuildConfig.GIT_REVISION,
        gitDirty = BuildConfig.GIT_DIRTY,
        schemaVersion = BuildConfig.DB_SCHEMA_VERSION,
        buildTimeUtc = BuildConfig.BUILD_TIME_UTC,
        diagnosticText = buildString {
            appendLine("revision=${BuildConfig.GIT_REVISION}")
            appendLine("dirty=${BuildConfig.GIT_DIRTY}")
            appendLine("schema=${BuildConfig.DB_SCHEMA_VERSION}")
            appendLine("builtAt=${BuildConfig.BUILD_TIME_UTC}")
        },
        themeMode = when (preferences.value.theme) {
            ThemePreference.SYSTEM -> "system"; ThemePreference.LIGHT -> "light"
            ThemePreference.DARK -> "dark"; ThemePreference.COLOR_66CCFF -> "66ccff"
        },
        language = when (preferences.value.locale) {
            LocalePreference.SYSTEM -> "system"; LocalePreference.ZH_CN -> "zh-CN"; LocalePreference.EN_US -> "en-US"
        },
        statsEnabled = statsEnabled, requestInspectionEnabled = inspectorEnabled.value,
        diagnosticsEnabled = diagnosticFiles.enabled,
        diagnosticsSizeBytes = diagnosticFiles.sizeBytes,
        diagnosticsLimitBytes = diagnosticFiles.totalLimitBytes,
        diagnosticsState = diagnosticsStatus.value,
        exportState = exportStatus.value, updateState = updateStatus.value, noticeCount = noticeCount,
        licenseText = app.assets.open("AGPL-3.0-only.txt").bufferedReader().use { it.readText() },
        error = error.value,
        globalRootPrompt = app.container.settings.effectiveGlobalRootPrompt(),
        globalRootPromptOverride = preferences.value.globalRootPromptOverride,
        globalRootPromptUnlocked = preferences.value.globalRootPromptUnlocked,
        globalRootPromptRevision = preferences.value.globalRootPromptRevision,
        globalRootPromptUpdatedAt = preferences.value.globalRootPromptUpdatedAt,
        webSearchConfigured = searchConfigured,
        webSearchEnabled = searchConfigured && app.container.settings.webSearchEnabled(),
        webSearchState = webSearchStatus.value,
        )
    }

    fun saveWebSearch(apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized.isEmpty() || normalized.length > 4096 || normalized.any { it == '\r' || it == '\n' }) {
            error.value = "请输入有效的 Brave Search API Key。"
            return
        }
        val oldRef = app.container.settings.webSearchSecretRef()
        val newRef = "search:brave:${UUID.randomUUID()}"
        try {
            app.container.secrets.put(newRef, normalized.toCharArray())
            app.container.settings.setWebSearch(newRef, enabled = true)
            oldRef?.takeIf { it != newRef }?.let { old ->
                runCatching { app.container.secrets.inventory().retireIfUnreferenced(old) }
            }
            webSearchStatus.value = "联网搜索已配置并启用；每次查询仍需单独确认。"
            error.value = null
        } catch (_: Exception) {
            runCatching { app.container.secrets.inventory().retireIfUnreferenced(newRef) }
            webSearchStatus.value = "联网搜索配置未保存。"
            error.value = "无法安全保存联网搜索凭据。"
        }
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        val ref = app.container.settings.webSearchSecretRef()
        if (ref == null || app.container.secrets.inventory().status(ref) != SecretStatus.ACTIVE) {
            error.value = "请先保存有效的 Brave Search API Key。"
            return
        }
        app.container.settings.setWebSearch(ref, enabled)
        webSearchStatus.value = if (enabled) "联网搜索已启用；每次查询仍需单独确认。" else "联网搜索已停用。"
        error.value = null
    }

    fun clearWebSearch() {
        val oldRef = app.container.settings.webSearchSecretRef()
        try {
            app.container.settings.setWebSearch(null, enabled = false)
            oldRef?.let { app.container.secrets.inventory().retireIfUnreferenced(it) }
            webSearchStatus.value = "联网搜索凭据已移除。"
            error.value = null
        } catch (_: Exception) {
            webSearchStatus.value = "联网搜索凭据未能完整移除。"
            error.value = "无法安全移除联网搜索凭据。"
        }
    }

    fun theme(value: String) {
        val mode = when (value) {
            "light" -> ThemePreference.LIGHT; "dark" -> ThemePreference.DARK
            "66ccff" -> ThemePreference.COLOR_66CCFF; else -> ThemePreference.SYSTEM
        }
        app.container.settings.setTheme(mode)
        preferences.value = app.container.settings.get()
    }

    fun language(value: String) {
        app.container.settings.setLocale(when (value) {
            "zh-CN" -> LocalePreference.ZH_CN; "en-US" -> LocalePreference.EN_US; else -> LocalePreference.SYSTEM
        })
        preferences.value = app.container.settings.get()
    }

    fun inspector(value: Boolean) {
        app.container.uiPreferences.edit().putBoolean("request-inspector", value).apply()
        inspectorEnabled.value = value
    }

    fun setDiagnosticsEnabled(value: Boolean) {
        try {
            app.diagnostics.setEnabled(value)
            diagnosticsStatus.value = if (value) "诊断记录已开启。" else "诊断记录已关闭；已有记录仍可导出或清除。"
            error.value = null
        } catch (failure: Exception) {
            diagnosticsStatus.value = "无法保存诊断开关。"
            error.value = DiagnosticSanitizer.text(failure.message ?: "无法保存诊断开关。")
        }
    }

    fun exportDiagnosticsTo(uri: Uri?) {
        if (uri == null) {
            diagnosticsStatus.value = "已取消诊断导出。"
            return
        }
        viewModelScope.launch {
            try {
                diagnosticsStatus.value = "正在导出诊断 ZIP…"
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开导出位置。")
                    output.use { app.diagnostics.exportTo(it) }
                }
                diagnosticsStatus.value = "诊断 ZIP 已保存；原生崩溃或系统强杀仍可能需要 ADB Logcat。"
                error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Deliberately do not clear records here: a failed SAF write must leave the
                // evidence available for another destination or a later export attempt.
                diagnosticsStatus.value = "诊断导出失败；原记录未清除。"
                error.value = DiagnosticSanitizer.text(failure.message ?: "诊断导出失败。")
            }
        }
    }

    fun clearDiagnostics() {
        try {
            app.diagnostics.clear()
            diagnosticsStatus.value = "诊断记录已清除。"
            error.value = null
        } catch (failure: Exception) {
            error.value = DiagnosticSanitizer.text(failure.message ?: "清除诊断记录失败。")
        }
    }

    fun unlockRootPrompt() {
        app.container.settings.setGlobalRootPrompt(app.container.settings.get().globalRootPromptOverride, unlocked = true)
        preferences.value = app.container.settings.get()
    }

    fun saveRootPrompt(text: String) {
        app.container.settings.setGlobalRootPrompt(text, unlocked = true)
        preferences.value = app.container.settings.get()
    }

    fun restoreRootPrompt() {
        app.container.settings.restoreDefaultGlobalRootPrompt()
        preferences.value = app.container.settings.get()
    }

    fun exportAgents(): List<Pair<String, String>> = app.container.agents.list().map { it.id to it.name }

    fun prepareExport(agentId: String, includeSkillPackages: Boolean = false,
        includeKnowledgeContent: Boolean = false, includeConversations: Boolean = false, ready: () -> Unit) {
        if (transferRunning) { error.value = "另一项导入或导出尚未结束。"; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { require(app.container.agents.get(agentId) != null) { "Agent 已不存在。" } }
                pendingExport = agentId to TransferOptions(includeSkillPackages, includeKnowledgeContent, includeConversations)
                exportStatus.value = "请选择 ZIP 保存位置。所勾选内容将流式写入；不包含密钥或可继承的授权。"
                error.value = null
                ready()
            } catch (failure: Exception) { error.value = SecretRedactor.redact(failure.message ?: "导出准备失败。") }
        }
    }

    fun exportTo(uri: Uri?) {
        val selection = pendingExport
        pendingExport = null
        if (uri == null || selection == null) { exportStatus.value = "已取消导出。"; return }
        if (transferRunning) { error.value = "另一项导入或导出尚未结束。"; return }
        transferRunning = true
        viewModelScope.launch {
            try {
                exportStatus.value = "正在校验并导出 ZIP…"
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开导出位置。")
                    output.use { app.container.transfer.exportArchive(selection.first, selection.second, it) }
                }
                exportStatus.value = "ZIP 已保存。跨设备导入后需重新配置凭据与权限；历史会话保留但不自动继续执行。"
                error.value = null
            } catch (failure: Exception) {
                error.value = "导出未完成，所选文件可能不完整：" + SecretRedactor.redact(failure.message ?: "写入失败。")
            } finally { transferRunning = false }
        }
    }

    fun importFrom(uri: Uri?) {
        if (uri == null) return
        if (transferRunning) { error.value = "另一项导入或导出尚未结束。"; return }
        transferRunning = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri) ?: error("无法读取导入文件。")
                    input.buffered().use { stream ->
                        stream.mark(4)
                        val signature = ByteArray(4)
                        val signatureLength = stream.read(signature)
                        stream.reset()
                        if (signatureLength == 4 && signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                            app.container.transfer.importArchive(stream)
                        } else {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 16 * 1024 * 1024) { "旧 JSON 配置超过 16 MiB 上限；完整内容请使用流式 ZIP。" }
                            output.write(buffer, 0, count)
                        }
                        app.container.transfer.importBundle(output.toByteArray().toString(Charsets.UTF_8))
                        }
                    }
                }
                result.agentId?.let { app.container.uiPreferences.edit().putString("selected-agent", it).apply() }
                exportStatus.value = "配置导入成功。${result.warnings.joinToString("；")} 密钥需重新配置，Skill 权限不会自动恢复。"
                error.value = null
            } catch (failure: Exception) {
                error.value = SecretRedactor.redact(failure.message ?: "导入被拒绝；原有数据未清空。")
            } finally { transferRunning = false }
        }
    }

    /**
     * Check the signed announcement feed for an UPDATE item that applies to this installation.
     * The app deliberately does not download or install arbitrary announcement content; release
     * artifacts remain a separately authenticated, user-initiated step.
     */
    fun checkUpdates() {
        if (updateCheckRunning) return
        updateCheckRunning = true
        updateStatus.value = "正在检查签名更新公告…"
        viewModelScope.launch {
            try {
                val result = app.container.announcementRefreshCoordinator.refresh(force = true).await()
                val client = ClientContext(
                    platform = "android",
                    channel = "stable",
                    versionCode = BuildConfig.VERSION_CODE,
                    locale = Locale.getDefault().toLanguageTag(),
                    installId = app.container.announcements.installId(),
                )
                val update = app.container.announcements.records(client = client)
                    .asSequence()
                    .filter { !it.withdrawn && !it.signatureExpired }
                    .filter { it.item.category == AnnouncementCategory.UPDATE }
                    .maxWithOrNull(compareBy({ it.item.publishedAt.orEmpty() }, { it.item.revision }))
                updateStatus.value = when {
                    update != null && result is AnnouncementRefreshResult.Failed ->
                        "本次联网检查失败；仍保留此前已验证的更新公告《${update.item.title}》。请在公告中心查看详情。"
                    update != null && result is AnnouncementRefreshResult.Rejected ->
                        "新公告签名未通过验证；仍保留此前已验证的更新公告《${update.item.title}》。请在公告中心查看详情。"
                    update != null ->
                        "发现适用于当前设备的签名更新公告《${update.item.title}》。请在公告中心查看发布说明；应用不会自动下载或安装。"
                    result is AnnouncementRefreshResult.ConfigurationUnavailable ->
                        "更新公告服务尚未配置完整，无法执行签名检查。"
                    result is AnnouncementRefreshResult.Failed ->
                        "更新检查失败；未找到可安全使用的缓存更新公告。${result.message}"
                    result is AnnouncementRefreshResult.Rejected ->
                        "更新公告签名未通过验证，已保留原缓存且未执行更新。"
                    else -> "已完成签名检查，当前没有适用于此设备的更新公告。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateStatus.value = "更新检查暂时不可用；聊天与知识库功能不受影响。"
            } finally {
                updateCheckRunning = false
            }
        }
    }
}
