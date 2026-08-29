// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.domain.ThemePreference
import runtime.mobileagent.feature.settings.SettingsUiState
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.serialization.TransferOptions
import java.io.ByteArrayOutputStream

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val preferences = mutableStateOf(app.container.settings.get())
    val exportStatus = mutableStateOf("")
    val updateStatus = mutableStateOf("当前为 debug 验证版，正式 release 将由用户另行确认。")
    val inspectorEnabled = mutableStateOf(app.container.uiPreferences.getBoolean("request-inspector", true))
    val error = mutableStateOf<String?>(null)
    private var pendingExport: Pair<String, TransferOptions>? = null
    private var transferRunning = false

    fun uiState(statsEnabled: Boolean, noticeCount: Int): SettingsUiState = SettingsUiState(
        versionName = BuildConfig.VERSION_NAME + " debug", gitRevision = BuildConfig.GIT_REVISION,
        themeMode = when (preferences.value.theme) {
            ThemePreference.SYSTEM -> "system"; ThemePreference.LIGHT -> "light"
            ThemePreference.DARK -> "dark"; ThemePreference.COLOR_66CCFF -> "66ccff"
        },
        language = when (preferences.value.locale) {
            LocalePreference.SYSTEM -> "system"; LocalePreference.ZH_CN -> "zh-CN"; LocalePreference.EN_US -> "en-US"
        },
        statsEnabled = statsEnabled, requestInspectionEnabled = inspectorEnabled.value,
        exportState = exportStatus.value, updateState = updateStatus.value, noticeCount = noticeCount,
        licenseText = app.assets.open("AGPL-3.0-only.txt").bufferedReader().use { it.readText() },
        error = error.value,
    )

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

    fun checkUpdates() { updateStatus.value = "此版本使用 debug 签名；不自动下载或安装更新。正式 release 尚未发布。" }
}
