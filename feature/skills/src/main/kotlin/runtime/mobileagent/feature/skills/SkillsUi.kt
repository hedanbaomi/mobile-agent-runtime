// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.skills

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class SkillUi(
    val installId: String,
    val name: String,
    val version: String = "",
    val classification: String,
    val enabled: Boolean,
    val license: String,
    val reasons: List<String> = emptyList(),
    val packageHash: String = "",
    val installable: Boolean = false,
)

data class SkillPermissionUi(
    val capability: String,
    val scope: String,
    val granted: Boolean,
    val requiresConfirmation: Boolean = true,
)

data class SkillSourceFileUi(val path: String, val sizeLabel: String = "", val kind: String = "")

data class SkillAuditUi(val timestamp: String, val event: String, val actor: String = "", val detail: String = "")

data class SkillDetailUi(
    val skill: SkillUi,
    val preview: String = "",
    val manifestJson: String = "",
    val permissions: List<SkillPermissionUi> = emptyList(),
    val files: List<SkillSourceFileUi> = emptyList(),
    val audit: List<SkillAuditUi> = emptyList(),
)

data class SkillInstallUi(
    val packageName: String,
    val packageHash: String,
    val classification: String,
    val reasons: List<String> = emptyList(),
    val permissions: List<SkillPermissionUi> = emptyList(),
    val installable: Boolean = false,
    val status: String = "",
)

data class SkillsUiState(
    val skills: List<SkillUi> = emptyList(),
    val selectedInstallId: String? = null,
    val detail: SkillDetailUi? = null,
    val install: SkillInstallUi? = null,
    val sourcePath: String? = null,
    val sourceText: String? = null,
    val query: String = "",
    val filter: String = "all",
    val loading: Boolean = false,
    val error: String? = null,
    val status: String = "",
    val language: String = "zh-CN",
)

data class SkillsActions(
    val onImport: (List<Uri>) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onFilter: (String) -> Unit = {},
    val onOpenDetail: (String) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onGrantPermission: (String, String) -> Unit = { _, _ -> },
    val onRevokePermission: (String, String) -> Unit = { _, _ -> },
    val onConfirmInstall: () -> Unit = {},
    val onCancelInstall: () -> Unit = {},
    val onOpenSource: (String, String) -> Unit = { _, _ -> },
    val onCloseSource: () -> Unit = {},
)

@Composable
fun SkillsScreen(state: SkillsUiState, actions: SkillsActions = SkillsActions(), modifier: Modifier = Modifier) {
    val zh = state.language.equals("zh-CN", true)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) actions.onImport(uris)
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp)) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkillListPane(state, actions, zh, { picker.launch(arrayOf("*/*")) }, Modifier.weight(0.44f).fillMaxSize())
                SkillDetailPane(state, actions, zh, Modifier.weight(0.56f).fillMaxSize().verticalScroll(rememberScrollState()))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkillListPane(state, actions, zh, { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
                SkillDetailPane(state, actions, zh, Modifier.fillMaxWidth())
            }
        }
    }
    state.install?.let { InstallDialog(it, actions, zh) }
    state.sourcePath?.let { path -> SourceDialog(path, state.sourceText, actions.onCloseSource, zh) }
}

@Composable
private fun SkillListPane(state: SkillsUiState, actions: SkillsActions, zh: Boolean, onImport: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "技能" else "Skills", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = onImport) { Text(if (zh) "导入包" else "Import package") }
        }
        Text(
            if (zh) {
                "安装前会检查软件包。缺少 mobile-skill.json 的 Claude Skill 若包含仅使用受支持标准库、带 main 入口的 Python 程序，会生成本机兼容清单并归为 Class B；启用、授权并绑定到当前智能体后，模型可通过隔离的 py_* 工具传入参数和本次调用专用的虚拟 Markdown 文件。依赖 NumPy、PyTorch、PyMuPDF 等组件的脚本会明确保持不可直接执行；知识库检索由应用原生 PDF/ONNX 能力承接。应用不提供 PowerShell、宿主 Shell 或任意文件系统访问。"
            } else {
                "Packages are inspected before installation. A manifestless Claude Skill with a main-style Python program using only the supported standard library receives a local compatibility manifest and becomes Class B. Once enabled, granted, and bound to the current agent, the model can pass arguments and invocation-only virtual Markdown files to its isolated py_* tool. Programs requiring NumPy, PyTorch, PyMuPDF, or similar dependencies remain explicitly unavailable; native PDF/ONNX knowledge tools provide the Android retrieval path. PowerShell, a host shell, and arbitrary filesystem access remain unavailable."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to if (zh) "全部" else "all", "enabled" to if (zh) "已启用" else "enabled", "disabled" to if (zh) "已停用" else "disabled").forEach { (key, label) -> FilterChip(selected = state.filter == key, onClick = { actions.onFilter(key) }, label = { Text(label) }) }
        }
        OutlinedTextField(state.query, actions.onQuery, label = { Text(if (zh) "筛选技能" else "Filter skills") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        if (state.loading) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        else if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        else {
            val visible = state.skills.filter {
                (state.query.isBlank() || it.name.contains(state.query, true)) &&
                    (state.filter == "all" || (state.filter == "enabled" && it.enabled) || (state.filter == "disabled" && !it.enabled))
            }
            if (visible.isEmpty()) Text(if (zh) "暂无技能。" else "No skills available.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(280.dp).padding(top = 12.dp)) {
                items(visible, key = { it.installId }) { skill -> SkillCard(skill, skill.installId == state.selectedInstallId, actions, zh) }
            }
        }
    }
}

@Composable
private fun SkillDetailPane(state: SkillsUiState, actions: SkillsActions, zh: Boolean, modifier: Modifier) {
    Column(modifier) {
        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        state.detail?.let { SkillDetail(it, actions, zh) } ?: Text(if (zh) "选择技能以查看源码、权限和审计日志。" else "Select a skill to inspect its source, permissions, and audit log.", modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun SkillCard(skill: SkillUi, selected: Boolean, actions: SkillsActions, zh: Boolean) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { actions.onOpenDetail(skill.installId) }) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(skill.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = skill.enabled, onClick = { actions.onToggle(skill.installId, !skill.enabled) }, label = { Text(if (skill.enabled) { if (zh) "已启用" else "Enabled" } else { if (zh) "已停用" else "Disabled" }) })
            }
            Text("${skill.classification}${if (skill.version.isBlank()) "" else " · ${skill.version}"}", style = MaterialTheme.typography.bodySmall)
            Text(if (zh) "许可证：${skill.license.ifBlank { "未知" }}" else "License: ${skill.license.ifBlank { "unknown" }}", style = MaterialTheme.typography.labelSmall)
            skill.reasons.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun SkillDetail(detail: SkillDetailUi, actions: SkillsActions, zh: Boolean) {
    val skill = detail.skill
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(skill.name, style = MaterialTheme.typography.headlineSmall)
            Text(if (zh) "${skill.classification} · ${skill.packageHash.ifBlank { "哈希不可用" }}" else "${skill.classification} · ${skill.packageHash.ifBlank { "hash unavailable" }}", style = MaterialTheme.typography.bodySmall)
        }
        Checkbox(skill.enabled, { actions.onToggle(skill.installId, it) })
    }
    if (detail.preview.isNotBlank()) Text(detail.preview, modifier = Modifier.padding(top = 12.dp))
    Text(if (zh) "权限" else "Permissions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.permissions.isEmpty()) Text(if (zh) "未声明权限。" else "No permissions declared.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.permissions.forEach { permission ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(permission.capability, fontWeight = FontWeight.SemiBold)
                Text(permission.scope, style = MaterialTheme.typography.bodySmall)
            }
            if (permission.granted) OutlinedButton(onClick = { actions.onRevokePermission(skill.installId, permission.capability) }) { Text(if (zh) "撤销" else "Revoke") }
            else Button(onClick = { actions.onGrantPermission(skill.installId, permission.capability) }) { Text(if (zh) "授予" else "Grant") }
        }
    }
    Text(if (zh) "源码文件" else "Source files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.files.isEmpty()) Text(if (zh) "源码列表不可用。" else "Source listing unavailable.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.files.forEach { file ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(file.path, file.sizeLabel, file.kind).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onOpenSource(skill.installId, file.path) }) { Text(if (zh) "查看" else "View") }
        }
    }
    if (detail.manifestJson.isNotBlank()) {
        Text(if (zh) "清单" else "Manifest", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(detail.manifestJson, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    Text(if (zh) "审计日志" else "Audit log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.audit.isEmpty()) Text(if (zh) "暂无审计记录。" else "No audit entries available.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.audit.forEach { event -> Text(listOf(event.timestamp, event.event, event.actor, event.detail).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun InstallDialog(install: SkillInstallUi, actions: SkillsActions, zh: Boolean) {
    AlertDialog(
        onDismissRequest = actions.onCancelInstall,
        title = { Text(if (zh) "检查技能包" else "Inspect skill package") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(install.packageName)
                Text("${install.classification} · ${install.packageHash}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                install.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (install.permissions.isNotEmpty()) {
                    Text(if (zh) "请求的权限" else "Requested permissions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    install.permissions.forEach { Text("${it.capability}: ${it.scope}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
                if (install.status.isNotBlank()) Text(install.status, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = actions.onConfirmInstall, enabled = install.installable) { Text(if (zh) "安装" else "Install") } },
        dismissButton = { TextButton(onClick = actions.onCancelInstall) { Text(if (zh) "取消" else "Cancel") } },
    )
}

@Composable
private fun SourceDialog(path: String, text: String?, onClose: () -> Unit, zh: Boolean) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(path) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (text == null) Text(if (zh) "源码内容不可用。" else "Source content is unavailable.") else Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") } },
    )
}
