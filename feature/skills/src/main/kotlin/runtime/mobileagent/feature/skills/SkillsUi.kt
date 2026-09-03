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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.testTag
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

/**
 * UI-only summary of the current persistent grant.  IDs and grant records are deliberately not
 * copied into the feature model; the screen only needs to say whether the package binding is
 * still valid and which non-secret capability names are active.
 */
data class SkillBindingUi(
    val packageHashBound: Boolean = false,
    val grantRevision: Int? = null,
    val capabilities: List<String> = emptyList(),
)

/**
 * Safe, host-derived state for the current Skill memory binding.
 *
 * This is deliberately an enum instead of a free-form status string. The UI must not infer
 * availability from a normal Skill grant, and it must not expose an install id, package hash,
 * memory-space id, or filesystem path.
 */
enum class SkillMemoryAvailability {
    ENABLED,
    UNAVAILABLE,
    GRANT_LOST,
    EMPTY,
}

data class SkillMemoryUi(
    val availability: SkillMemoryAvailability = SkillMemoryAvailability.EMPTY,
    val capabilities: List<String> = emptyList(),
    val packageHashBound: Boolean = false,
    val grantRevision: Int? = null,
) {
    /** Compatibility/readability accessor; the enum remains the single source of truth. */
    val available: Boolean
        get() = availability == SkillMemoryAvailability.ENABLED
}

data class SkillDetailUi(
    val skill: SkillUi,
    val preview: String = "",
    val manifestJson: String = "",
    val permissions: List<SkillPermissionUi> = emptyList(),
    val files: List<SkillSourceFileUi> = emptyList(),
    val audit: List<SkillAuditUi> = emptyList(),
    val binding: SkillBindingUi = SkillBindingUi(),
    val memory: SkillMemoryUi = SkillMemoryUi(),
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
fun SkillsScreen(
    state: SkillsUiState,
    actions: SkillsActions = SkillsActions(),
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
) {
    val zh = state.language.equals("zh-CN", true)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) actions.onImport(uris)
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).testTag("skills.root")) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkillListPane(state, actions, zh, { picker.launch(arrayOf("*/*")) }, Modifier.weight(0.44f).fillMaxSize(), showPageTitle)
                SkillDetailPane(state, actions, zh, Modifier.weight(0.56f).fillMaxSize().verticalScroll(rememberScrollState()).testTag("skills.detail.scroll"))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("skills.narrow.scroll"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkillListPane(state, actions, zh, { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), showPageTitle)
                SkillDetailPane(state, actions, zh, Modifier.fillMaxWidth())
            }
        }
    }
    state.install?.let { InstallDialog(it, actions, zh) }
    state.sourcePath?.let { path -> SourceDialog(path, state.sourceText, actions.onCloseSource, zh) }
}

@Composable
private fun SkillListPane(state: SkillsUiState, actions: SkillsActions, zh: Boolean, onImport: () -> Unit, modifier: Modifier, showPageTitle: Boolean) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showPageTitle) {
                Text(if (zh) "技能" else "Skills", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = onImport) { Text(if (zh) "导入包" else "Import package") }
        }
        Text(
            if (zh) {
                "安装前会检查软件包。缺少 mobile-skill.json 的 Claude Skill 若包含仅使用受支持标准库、带 main 入口的 Python 程序，会生成本机兼容清单并归为 Class B；启用、授权并绑定到当前智能体后，模型可通过隔离的 py_* 工具传入参数和本次调用专用的虚拟 Markdown 文件。依赖 NumPy、PyTorch、PyMuPDF 等组件的脚本会明确保持不可直接执行；知识库检索由应用原生 PDF/ONNX 能力承接。"
            } else {
                "Packages are inspected before installation. A manifestless Claude Skill with a main-style Python program using only the supported standard library receives a local compatibility manifest and becomes Class B. Once enabled, granted, and bound to the current agent, the model can pass arguments and invocation-only virtual Markdown files to its isolated py_* tool. Programs requiring NumPy, PyTorch, PyMuPDF, or similar dependencies remain explicitly unavailable; native PDF/ONNX knowledge tools provide the Android retrieval path."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        SecurityBoundarySummary(zh)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to if (zh) "全部" else "all", "enabled" to if (zh) "已启用" else "enabled", "disabled" to if (zh) "已停用" else "disabled").forEach { (key, label) -> FilterChip(selected = state.filter == key, onClick = { actions.onFilter(key) }, label = { Text(label) }) }
        }
        OutlinedTextField(state.query, actions.onQuery, label = { Text(if (zh) "筛选技能" else "Filter skills") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        if (state.loading) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        else if (state.error != null) {
            Card(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    safeDisplay(state.error.orEmpty()),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
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
        if (state.status.isNotBlank()) Text(safeDisplay(state.status), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        state.detail?.let { SkillDetail(it, actions, zh) } ?: Text(if (zh) "选择技能以查看源码、权限和审计日志。" else "Select a skill to inspect its source, permissions, and audit log.", modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun SkillCard(skill: SkillUi, selected: Boolean, actions: SkillsActions, zh: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { actions.onOpenDetail(skill.installId) },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(safeDisplay(skill.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = skill.enabled, onClick = { actions.onToggle(skill.installId, !skill.enabled) }, label = { Text(if (skill.enabled) { if (zh) "已启用" else "Enabled" } else { if (zh) "已停用" else "Disabled" }) })
            }
            Text("${safeDisplay(skill.classification)}${if (skill.version.isBlank()) "" else " · ${safeDisplay(skill.version)}"}", style = MaterialTheme.typography.bodySmall)
            Text(if (zh) "许可证：${safeDisplay(skill.license.ifBlank { "未知" })}" else "License: ${safeDisplay(skill.license.ifBlank { "unknown" })}", style = MaterialTheme.typography.labelSmall)
            skill.reasons.firstOrNull()?.let { Text(safeDisplay(it), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun SkillDetail(detail: SkillDetailUi, actions: SkillsActions, zh: Boolean) {
    val skill = detail.skill
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(safeDisplay(skill.name), style = MaterialTheme.typography.headlineSmall)
            Text(
                if (zh) "${safeDisplay(skill.classification)} · 安装包身份已校验"
                else "${safeDisplay(skill.classification)} · install identity verified",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Checkbox(skill.enabled, { actions.onToggle(skill.installId, it) })
    }
    if (detail.preview.isNotBlank()) Text(safeDisplay(detail.preview), modifier = Modifier.padding(top = 12.dp))
    Text(if (zh) "安全边界" else "Security boundary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        if (zh) "默认使用受限的类型化文件工具；应用私有工作区与用户在系统选择器选定并持久授权的 SAF 工作区相互独立。系统增强文件工具只使用用户明确选择的 Shizuku 或有线 ADB，选定通道不可用时不会自动切换。"
        else "Default access uses confined typed file tools. The app-private workspace is separate from a SAF workspace selected and persistently granted by the user. Elevated typed files use only the explicitly selected Shizuku or wired ADB authority; an unavailable selection never silently falls back.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        if (zh) "Dangerous Mode 开启、当前 Agent/Skill 具有 shell.execute 且选定 Authority 可用时，才提供一次性 shell_exec。它是 Android shell escape，不是安全沙箱，也不是 Root，可能修改设备状态。Root、无线 ADB、DPC/Device Owner/Profile Owner、Termux 不在产品路线。"
        else "Only when Dangerous Mode is enabled, the current Agent/Skill has shell.execute, and the selected authority is available is one-shot shell_exec exposed. It is an Android shell escape, not a security sandbox or Root, and may change device state. Root, wireless ADB, DPC/Device Owner/Profile Owner, and Termux are out of scope.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(if (zh) "授权与持久绑定" else "Grant and persistent binding", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        when {
            detail.binding.packageHashBound && detail.binding.grantRevision != null -> {
                if (zh) "当前授权已绑定本次安装的包哈希；授权修订 ${detail.binding.grantRevision}。"
                else "The current grant is bound to this install's package hash; grant revision ${detail.binding.grantRevision}."
            }
            else -> {
                if (zh) "当前安装没有可用的包哈希绑定授权。"
                else "No usable package-hash-bound grant exists for this install."
            }
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (detail.binding.capabilities.isNotEmpty()) {
        Text(if (zh) "当前能力：${safeDisplay(detail.binding.capabilities.joinToString("、"))}" else "Active capabilities: ${safeDisplay(detail.binding.capabilities.joinToString(", "))}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    Text(if (zh) "持久 Skill memory" else "Persistent Skill memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        memoryAvailabilityLabel(detail.memory.availability, zh),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp).testTag("skills.memory.status"),
    )
    if (detail.memory.available && detail.memory.capabilities.isNotEmpty()) {
        Text(if (zh) "memory 能力：${safeDisplay(detail.memory.capabilities.joinToString("、"))}" else "Memory capabilities: ${safeDisplay(detail.memory.capabilities.joinToString(", "))}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    Text(if (zh) "权限" else "Permissions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.permissions.isEmpty()) Text(if (zh) "未声明权限。" else "No permissions declared.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.permissions.forEach { permission ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(safeDisplay(permission.capability), fontWeight = FontWeight.SemiBold)
                Text(safeDisplay(permission.scope), style = MaterialTheme.typography.bodySmall)
            }
            if (permission.granted) OutlinedButton(onClick = { actions.onRevokePermission(skill.installId, permission.capability) }) { Text(if (zh) "撤销" else "Revoke") }
            else Button(onClick = { actions.onGrantPermission(skill.installId, permission.capability) }) { Text(if (zh) "授予" else "Grant") }
        }
    }
    Text(if (zh) "源码文件" else "Source files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.files.isEmpty()) Text(if (zh) "源码列表不可用。" else "Source listing unavailable.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.files.forEach { file ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(safePath(file.path), safeDisplay(file.sizeLabel), safeDisplay(file.kind)).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onOpenSource(skill.installId, file.path) }) { Text(if (zh) "查看" else "View") }
        }
    }
    Text(if (zh) "清单" else "Manifest", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(if (zh) "仅显示上面的权限、绑定与兼容性安全摘要；原始清单字段不会在此页面展开。" else "Only the security summary above is shown; raw manifest fields are not expanded on this screen.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Text(if (zh) "审计日志" else "Audit log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.audit.isEmpty()) Text(if (zh) "暂无审计记录。" else "No audit entries available.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.audit.forEach { event -> Text(listOf(event.timestamp, event.event, event.actor, event.detail).filter(String::isNotBlank).joinToString(" · ").let(::safeDisplay), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun InstallDialog(install: SkillInstallUi, actions: SkillsActions, zh: Boolean) {
    AlertDialog(
        onDismissRequest = actions.onCancelInstall,
        title = { Text(if (zh) "检查技能包" else "Inspect skill package") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(safeDisplay(install.packageName))
                Text(
                    if (zh) "${safeDisplay(install.classification)} · 安装包身份已校验"
                    else "${safeDisplay(install.classification)} · install identity verified",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                install.reasons.forEach { Text(safeDisplay(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (install.permissions.isNotEmpty()) {
                    Text(if (zh) "请求的权限" else "Requested permissions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    install.permissions.forEach { Text("${safeDisplay(it.capability)}: ${safeDisplay(it.scope)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
                if (install.status.isNotBlank()) Text(safeDisplay(install.status), modifier = Modifier.padding(top = 8.dp))
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
        title = { Text(safePath(path)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (text == null) Text(if (zh) "源码内容不可用。" else "Source content is unavailable.") else Text(safeDisplay(text), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") } },
    )
}

@Composable
private fun SecurityBoundarySummary(zh: Boolean) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(if (zh) "访问边界摘要" else "Access boundary summary", style = MaterialTheme.typography.titleMedium)
        Text(
            if (zh) "类型化文件工具：应用私有工作区、用户选定的 SAF 工作区，或用户明确选择的 Shizuku/有线 ADB 工作区；不会向 Agent 暴露 URI、真实路径、Binder 或 ADB serial。"
            else "Typed file tools use the app-private workspace, a user-selected SAF workspace, or the explicitly selected Shizuku/wired ADB workspace; URI, real paths, Binder details, and ADB serials are not exposed to the Agent.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            if (zh) "危险模式：仅在持久 Dangerous Mode、Agent/Skill 的 shell.execute 授权和选定 Authority 同时满足时提供一次性 shell_exec；这是 Android shell escape，不是宿主 Shell 或安全沙箱。"
            else "Dangerous Mode: one-shot shell_exec requires persistent Dangerous Mode, Agent/Skill shell.execute permission, and the selected authority; it is an Android shell escape, not a host shell or security sandbox.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            if (zh) "未实现/不在路线：Root、无线 ADB、DPC/Device Owner/Profile Owner、Termux；选定 Authority 失效时不会自动回退。"
            else "Out of scope: Root, wireless ADB, DPC/Device Owner/Profile Owner, and Termux; an unavailable selected authority never silently falls back.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val sensitiveAssignment = Regex(
    "(?i)\\b(api[_-]?key|authorization|bearer|token|password|secret|cookie|private[_-]?key)\\b\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+",
)
private val standaloneBearer = Regex("(?i)\\bbearer\\s+\\S+")
private val standaloneSecret = Regex("(?i)\\bsk-[A-Za-z0-9]{10,}\\b")

/** Redact only presentation text; secrets must never become part of a security summary. */
private fun safeDisplay(value: String, maxLength: Int = 8 * 1024): String {
    val redactedAssignments = sensitiveAssignment.replace(value.take(maxLength)) { match: MatchResult ->
        "${match.groupValues[1]}=[hidden]"
    }
    val redactedBearer = standaloneBearer.replace(redactedAssignments) { _: MatchResult -> "[hidden]" }
    return standaloneSecret.replace(redactedBearer) { _: MatchResult -> "[hidden]" }
}

private fun safePath(value: String): String {
    val normalized = value.replace('\\', '/')
    val absolute = normalized.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(normalized)
    return if (absolute) "受限包内文件" else safeDisplay(normalized, 512)
}

private fun memoryAvailabilityLabel(availability: SkillMemoryAvailability, zh: Boolean): String = when (availability) {
    SkillMemoryAvailability.ENABLED -> if (zh) {
        "已启用：持久记忆已绑定当前 Agent 快照和本次授权。"
    } else {
        "Enabled: persistent memory is bound to the current Agent snapshot and grant."
    }
    SkillMemoryAvailability.UNAVAILABLE -> if (zh) {
        "暂不可用：持久记忆绑定或后端当前不可用。"
    } else {
        "Unavailable: the persistent-memory binding or backend is currently unavailable."
    }
    SkillMemoryAvailability.GRANT_LOST -> if (zh) {
        "授权已丢失：请重新授予当前 Skill 的 memory 权限。"
    } else {
        "Grant lost: grant the Skill's memory permissions again."
    }
    SkillMemoryAvailability.EMPTY -> if (zh) {
        "暂无条目：持久记忆已绑定，但当前没有记忆条目。"
    } else {
        "Empty: persistent memory is bound, but it has no entries yet."
    }
}
