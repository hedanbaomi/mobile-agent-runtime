// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun UnsavedChangesDialog(
    chinese: Boolean,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(if (chinese) "放弃未保存的修改？" else "Discard unsaved changes?") },
        text = {
            Text(
                if (chinese) "当前编辑尚未保存。离开后这些修改会丢失；已保存的数据不会被删除。"
                else "The current edit has not been saved. Leaving will discard these changes; saved data is unchanged.",
            )
        },
        confirmButton = { Button(onClick = onDiscard) { Text(if (chinese) "放弃并离开" else "Discard and leave") } },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text(if (chinese) "继续编辑" else "Keep editing") } },
    )
}

@Composable
internal fun UnknownOutcomeDialog(
    chinese: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (chinese) "运行结果未知" else "Run outcome is unknown") },
        text = {
            Text(
                if (chinese) {
                    "上一次运行可能已经产生服务商费用或外部操作。重试可能重复费用或操作，只有在你确认风险后才会继续。"
                } else {
                    "The previous run may already have incurred provider charges or caused an external effect. Retrying can repeat that cost or effect and requires your confirmation."
                },
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text(if (chinese) "确认风险并重试" else "Confirm risk and retry") } },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

@Composable
internal fun VisionConsentDialog(
    chinese: Boolean,
    displayName: String,
    target: String,
    retry: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (retry) { if (chinese) "确认重试视觉处理" else "Confirm Vision retry" } else { if (chinese) "确认视觉上传" else "Confirm Vision upload" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (displayName.isNotBlank()) Text(displayName)
                Text(if (chinese) "目标模型" else "Target model")
                Text(target.ifBlank { if (chinese) "未配置明确的 Vision 目标" else "No explicit Vision target is configured" })
                Text(
                    if (retry) {
                        if (chinese) "重试可能重复上传和服务商费用。只有在确认上一次结果未知后才继续。"
                        else "A retry may upload the image again and incur another provider charge. Continue only after confirming the previous outcome was unknown."
                    } else {
                        if (chinese) "所选文件的图像与临近文字将离开设备发送到此目标，服务商可能收费。"
                        else "The selected image and nearby text will leave this device for this target, and the provider may charge for it."
                    },
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(if (chinese) "确认并继续" else "Confirm and continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

@Composable
internal fun EmbeddingConsentDialog(
    chinese: Boolean,
    target: String,
    retry: Boolean,
    rebind: Boolean,
    documentCount: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    queryRetry: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                when {
                    chinese && queryRetry -> "确认允许查询重试"
                    chinese && retry -> "确认重试 API Embedding"
                    chinese -> "确认 API Embedding 外发"
                    queryRetry -> "Confirm query retry authorization"
                    retry -> "Confirm API Embedding retry"
                    else -> "Confirm API Embedding export"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (chinese) "目标模型" else "Target model")
                Text(target.ifBlank { if (chinese) "未配置明确的 Embedding 目标" else "No explicit Embedding target is configured" })
                Text(
                    if (chinese) {
                        "知识库文本块和检索查询将离开设备发送到此目标，服务商可能收费。"
                    } else {
                        "Knowledge chunks and retrieval queries will leave this device for this target, and the provider may charge."
                    },
                )
                if (rebind) {
                    Text(
                        if (chinese) {
                            "当前知识库已有 $documentCount 个文档；重新绑定会按新的 Embedding 空间重新索引。"
                        } else {
                            "The current knowledge base has $documentCount document(s); rebinding will reindex them in the new Embedding space."
                        },
                    )
                }
                if (retry && !queryRetry) {
                    Text(
                        if (chinese) {
                            "上一次 Embedding 结果未知。重试可能重复产生服务商费用，只有在你明确确认后才继续；应用不会自动重试。"
                        } else {
                            "The previous Embedding outcome is unknown. Retrying may incur a duplicate provider charge; it continues only after your explicit confirmation and is never automatic."
                        },
                    )
                }
                if (queryRetry) {
                    Text(
                        if (chinese) {
                            "这只允许同一知识库、同一模型与同一查询再次提交一次。上一次结果未知，再次提交可能重复收费；授权后不会自动发起请求，需你在会话中重新提交。"
                        } else {
                            "This authorizes one resubmission for the same knowledge base, model, and query. The previous result is unknown, so resubmitting may incur a duplicate charge. Authorization does not send a request automatically; resubmit it from the conversation."
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    when {
                        chinese && queryRetry -> "允许一次重试"
                        queryRetry -> "Allow one retry"
                        chinese -> "明确同意并继续"
                        else -> "Explicitly consent and continue"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

@Composable
internal fun SkillPermissionScopeDialog(
    chinese: Boolean,
    capability: String,
    declaredScope: String,
    knowledgeScope: Boolean,
    knowledgeBases: List<Pair<String, String>>,
    onConfirm: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedIds by remember(capability, declaredScope) { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (chinese) "确认技能权限范围" else "Confirm skill permission scope") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (chinese) "能力：$capability" else "Capability: $capability")
                Text(
                    if (declaredScope.isBlank()) {
                        if (chinese) "包未提供可显示的范围；宿主仍会严格按包声明校验。" else "The package did not provide a displayable scope; the host will still enforce its declaration."
                    } else {
                        if (chinese) "包声明范围：$declaredScope" else "Declared package scope: $declaredScope"
                    },
                )
                if (knowledgeScope) {
                    Text(if (chinese) "选择可访问的知识库（未选择则不授予知识范围）。" else "Select knowledge bases that this skill may access. No selection grants no knowledge scope.")
                    if (knowledgeBases.isEmpty()) {
                        Text(if (chinese) "当前没有可授权的知识库。" else "No knowledge bases are available for authorization.")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().size(220.dp)) {
                            items(knowledgeBases, key = { it.first }) { (id, name) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = id in selectedIds,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked) selectedIds + id else selectedIds - id
                                        },
                                    )
                                    Text(name, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    Text(if (chinese) "提交范围前请核对上面的域名与 HTTP 方法；宿主不会扩大包声明。" else "Review the domains and HTTP methods above before granting. The host will not expand the package declaration.")
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selectedIds) }) { Text(if (chinese) "确认授予" else "Confirm grant") } },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

@Composable
internal fun ExportAgentDialog(
    chinese: Boolean,
    agents: List<Pair<String, String>>,
    onConfirm: (String, Boolean, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedId by remember(agents) { mutableStateOf(agents.firstOrNull()?.first) }
    var includeSkillPackages by remember(agents) { mutableStateOf(false) }
    var includeKnowledgeContent by remember(agents) { mutableStateOf(false) }
    var includeConversations by remember(agents) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (chinese) "选择要导出的 Agent" else "Choose an Agent to export") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (agents.isEmpty()) {
                    Text(if (chinese) "暂无可导出的 Agent。" else "No Agent is available to export.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().size(220.dp)) {
                        items(agents, key = { it.first }) { (id, name) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = id == selectedId, onClick = { selectedId = id })
                                Text(name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeSkillPackages, onCheckedChange = { includeSkillPackages = it })
                        Text(
                            if (chinese) "包含已安装 Skill 包（仍不包含密钥）" else "Include installed Skill packages (secrets remain excluded)",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeKnowledgeContent, onCheckedChange = { includeKnowledgeContent = it })
                        Text(
                            if (chinese) "包含知识库原文/图片" else "Include knowledge source text/images",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeConversations, onCheckedChange = { includeConversations = it })
                        Text(
                            if (chinese) "包含会话与审计（导入后需本地重新配置，未知结果不重试）"
                            else "Include conversations and audit (reconfigure locally after import; do not retry unknown outcomes)",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        if (chinese) {
                            "扩展内容可能包含敏感信息，将写入你选择的位置；若选择云端文档提供方，该提供方可能上传或同步。应用不另行上传。不包含密钥或授权。总大小上限 512 MiB，单项上限 50 MiB。"
                        } else {
                            "Optional content may be sensitive and will be written to the location you choose. If you choose a cloud document provider, that provider may upload or sync it. The app does not upload it separately. Keys and authorizations are excluded. Total limit: 512 MiB; each item: 50 MiB."
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedId?.let {
                        onConfirm(it, includeSkillPackages, includeKnowledgeContent, includeConversations)
                    }
                },
                enabled = selectedId != null,
            ) { Text(if (chinese) "准备导出" else "Prepare export") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (chinese) "取消" else "Cancel") } },
    )
}
