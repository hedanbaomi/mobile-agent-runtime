// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.skills

import androidx.compose.ui.res.stringResource

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
    val pendingUninstallId: String? = null,
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
    val onRequestUninstall: (String) -> Unit = {},
    val onConfirmUninstall: () -> Unit = {},
    val onCancelUninstall: () -> Unit = {},
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
    state.pendingUninstallId?.let { id ->
        state.skills.firstOrNull { it.installId == id }?.let { UninstallDialog(it, actions, zh) }
    }
    state.sourcePath?.let { path -> SourceDialog(path, state.sourceText, actions.onCloseSource, zh) }
}

@Composable
private fun SkillListPane(state: SkillsUiState, actions: SkillsActions, zh: Boolean, onImport: () -> Unit, modifier: Modifier, showPageTitle: Boolean) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showPageTitle) {
                Text(stringResource(R.string.ui_skills_2a98e03d), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = onImport) { Text(stringResource(R.string.ui_import_package_0f33589c)) }
        }
        Text(
            stringResource(R.string.ui_import_a_skill_package_check_compatibility_9a6d5f72),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to if (zh) "全部" else "all", "enabled" to if (zh) "已启用" else "enabled", "disabled" to if (zh) "已停用" else "disabled").forEach { (key, label) -> FilterChip(selected = state.filter == key, onClick = { actions.onFilter(key) }, label = { Text(label) }) }
        }
        OutlinedTextField(state.query, actions.onQuery, label = { Text(stringResource(R.string.ui_filter_skills_c7db0d68)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
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
            if (visible.isEmpty()) Text(stringResource(R.string.ui_no_skills_available_65cf91a2), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
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
        state.detail?.let { SkillDetail(it, actions, zh) } ?: Text(stringResource(R.string.ui_select_a_skill_to_view_details_d6886104), modifier = Modifier.padding(24.dp))
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
                stringResource(R.string.ui_s_install_identity_verified_e3c3ceaf, (safeDisplay(skill.classification))),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Checkbox(skill.enabled, { actions.onToggle(skill.installId, it) })
    }
    OutlinedButton(
        onClick = { actions.onRequestUninstall(skill.installId) },
        modifier = Modifier.padding(top = 8.dp).testTag("skills.uninstall"),
    ) { Text(stringResource(R.string.ui_uninstall_skill_d11f9422)) }
    if (detail.preview.isNotBlank()) {
        var previewExpanded by remember(skill.installId) { mutableStateOf(false) }
        TextButton(onClick = { previewExpanded = !previewExpanded }, modifier = Modifier.testTag("skills.instructions.toggle")) {
            Text(if (previewExpanded) { if (zh) "收起使用说明" else "Hide instructions" } else { if (zh) "查看使用说明" else "View instructions" })
        }
        if (previewExpanded) Text(safeDisplay(detail.preview), modifier = Modifier.padding(top = 4.dp))
    }
    Text(stringResource(R.string.ui_grant_status_d79bc46f), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        when {
            detail.binding.packageHashBound && detail.binding.grantRevision != null -> {
                if (zh) "授权有效 · 修订 ${detail.binding.grantRevision}"
                else "Grant active · revision ${detail.binding.grantRevision}"
            }
            else -> {
                if (zh) "尚未授权"
                else "No active grant"
            }
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (detail.binding.capabilities.isNotEmpty()) {
        Text(if (zh) "当前能力：${safeDisplay(detail.binding.capabilities.joinToString("、"))}" else "Active capabilities: ${safeDisplay(detail.binding.capabilities.joinToString(", "))}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    Text(stringResource(R.string.ui_skill_memory_0ea065bc), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        memoryAvailabilityLabel(detail.memory.availability, zh),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp).testTag("skills.memory.status"),
    )
    if (detail.memory.available && detail.memory.capabilities.isNotEmpty()) {
        Text(if (zh) "memory 能力：${safeDisplay(detail.memory.capabilities.joinToString("、"))}" else "Memory capabilities: ${safeDisplay(detail.memory.capabilities.joinToString(", "))}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    Text(stringResource(R.string.ui_permissions_be9338af), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.permissions.isEmpty()) Text(stringResource(R.string.ui_no_permissions_declared_f900ab80), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.permissions.forEach { permission ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(safeDisplay(permission.capability), fontWeight = FontWeight.SemiBold)
                Text(safeDisplay(permission.scope), style = MaterialTheme.typography.bodySmall)
            }
            if (permission.granted) OutlinedButton(onClick = { actions.onRevokePermission(skill.installId, permission.capability) }) { Text(stringResource(R.string.ui_revoke_411a6c97)) }
            else Button(onClick = { actions.onGrantPermission(skill.installId, permission.capability) }) { Text(stringResource(R.string.ui_grant_a53c2c77)) }
        }
    }
    Text(stringResource(R.string.ui_source_files_dabedad9), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.files.isEmpty()) Text(stringResource(R.string.ui_source_listing_unavailable_4b7c7b16), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.files.forEach { file ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(safePath(file.path), safeDisplay(file.sizeLabel), safeDisplay(file.kind)).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onOpenSource(skill.installId, file.path) }) { Text(stringResource(R.string.ui_view_78059812)) }
        }
    }
    Text(stringResource(R.string.ui_audit_log_c7dfe2f3), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (detail.audit.isEmpty()) Text(stringResource(R.string.ui_no_audit_entries_available_ce05939a), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    detail.audit.forEach { event -> Text(listOf(event.timestamp, event.event, event.actor, event.detail).filter(String::isNotBlank).joinToString(" · ").let(::safeDisplay), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun InstallDialog(install: SkillInstallUi, actions: SkillsActions, zh: Boolean) {
    AlertDialog(
        onDismissRequest = actions.onCancelInstall,
        title = { Text(stringResource(R.string.ui_inspect_skill_package_857d34ad)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(safeDisplay(install.packageName))
                Text(
                    stringResource(R.string.ui_s_install_identity_verified_e3c3ceaf, (safeDisplay(install.classification))),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                install.reasons.forEach { Text(safeDisplay(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (install.permissions.isNotEmpty()) {
                    Text(stringResource(R.string.ui_requested_permissions_bbc6afc7), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    install.permissions.forEach { Text("${safeDisplay(it.capability)}: ${safeDisplay(it.scope)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
                if (install.status.isNotBlank()) Text(safeDisplay(install.status), modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = actions.onConfirmInstall, enabled = install.installable) { Text(stringResource(R.string.ui_install_fb022450)) } },
        dismissButton = { TextButton(onClick = actions.onCancelInstall) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
    )
}

@Composable
private fun UninstallDialog(skill: SkillUi, actions: SkillsActions, zh: Boolean) {
    AlertDialog(
        onDismissRequest = actions.onCancelUninstall,
        title = { Text(stringResource(R.string.ui_uninstall_skill_d11f9422)) },
        text = {
            Column {
                Text(safeDisplay(skill.name))
                Text(
                    stringResource(R.string.ui_this_removes_the_install_revokes_grants_24a153db),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = actions.onConfirmUninstall) { Text(stringResource(R.string.ui_uninstall_3e55535e)) } },
        dismissButton = { TextButton(onClick = actions.onCancelUninstall) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
    )
}

@Composable
private fun SourceDialog(path: String, text: String?, onClose: () -> Unit, zh: Boolean) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(safePath(path)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (text == null) Text(stringResource(R.string.ui_source_content_is_unavailable_929ce8ad)) else Text(safeDisplay(text), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(stringResource(R.string.ui_close_6cf4a777)) } },
    )
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
        "已启用"
    } else {
        "Enabled"
    }
    SkillMemoryAvailability.UNAVAILABLE -> if (zh) {
        "暂不可用"
    } else {
        "Unavailable"
    }
    SkillMemoryAvailability.GRANT_LOST -> if (zh) {
        "授权失效，请重新授权。"
    } else {
        "Grant lost; grant memory access again."
    }
    SkillMemoryAvailability.EMPTY -> if (zh) {
        "暂无记忆条目"
    } else {
        "No memory entries yet"
    }
}
