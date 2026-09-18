// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import runtime.mobileagent.knowledge.PipelineProgress
import runtime.mobileagent.knowledge.PipelineReuseSummary
import runtime.mobileagent.knowledge.PipelinePolicy

data class KnowledgeBaseUi(val id: String, val name: String, val documentCount: Int = 0, val status: String = "")

data class KnowledgeDocumentUi(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val status: String = "",
    val sizeLabel: String = "",
    val updatedAt: String = "",
)

data class KnowledgeImportJobUi(
    val id: String,
    val displayName: String,
    val stage: String,
    val error: String? = null,
    val updatedAt: String = "",
    val requiresVisionConsent: Boolean = false,
    val unknownOutcome: Boolean = false,
    val embeddingIsApi: Boolean = false,
    val requiresEmbeddingConsent: Boolean = false,
)

data class KnowledgeEmbeddingModelUi(val id: String, val label: String)

/**
 * A user-selectable Vision destination.  The fingerprint is the immutable
 * authorization identity; [label] is presentation only.
 */
data class KnowledgeVisionTargetUi(
    val fingerprint: String,
    val label: String,
    val providerId: String = "",
    val modelProfileId: String = "",
    val modelId: String = "",
)

/**
 * Import selection kept by the shell-scoped ViewModel while the user visits
 * Provider settings.  Uri values are persisted by the ViewModel before this
 * object is exposed, so leaving the Knowledge route does not discard the
 * staged selection.
 */
data class KnowledgePendingImportUi(
    val id: String,
    val uris: List<Uri>,
    val sourceKind: String,
    val selectedVisionTargetFingerprint: String? = null,
    val visionTargetSelectionInitialized: Boolean = false,
)

data class KnowledgeBatchVisionUi(
    val batchId: String,
    val selectedVisionTargetFingerprint: String? = null,
    val visionTargetSelectionInitialized: Boolean = false,
    val reusePreview: PipelineReuseSummary? = null,
)

data class KnowledgeQueryAttemptUi(
    val spaceId: String,
    val queryHash: String,
    val target: String,
    val retryAuthorized: Boolean = false,
)

data class KnowledgeBatchUi(
    val id: String,
    val displayName: String,
    val kind: String,
    val state: String,
    val totalItems: Int,
    val copied: Int,
    val processing: Int,
    val waiting: Int,
    val failed: Int,
    val error: String? = null,
    /** Published (searchable) items.  The only number that may be shown as finished. */
    val published: Int = 0,
    val pending: Int = 0,
    val unknown: Int = 0,
    val cancelled: Int = 0,
    val blockedReason: String? = null,
    /** Exact authorization identity of the confirmed destination, when one was confirmed. */
    val visionTarget: String? = null,
    /** Presentation-only label for [visionTarget]; it is never an authorization token. */
    val visionTargetLabel: String? = null,
    val paused: Boolean = false,
    val resumeStagingAvailable: Boolean = false,
    /** Per-item detail.  Rendered only when the user expands the overall progress card. */
    val items: List<KnowledgeBatchItemUi> = emptyList(),
    val pipeline: PipelineProgress = PipelineProgress(),
    val reuse: PipelineReuseSummary = PipelineReuseSummary(),
    val reuseByTarget: Map<String, PipelineReuseSummary> = emptyMap(),
    val policy: PipelinePolicy = PipelinePolicy(),
)

data class KnowledgeBatchItemUi(
    val jobId: String?,
    val displayName: String,
    val state: String,
    val error: String? = null,
)

data class KnowledgeWaitingUi(
    val jobId: String,
    val displayName: String,
    val reason: String,
    val authorizationTarget: String = "",
    val canConfigureVision: Boolean = true,
)

data class KnowledgeEvidenceUi(
    val documentId: String,
    val source: String,
    val chunkCount: Int? = null,
    val contentHash: String = "",
    val verified: Boolean? = null,
    val details: String = "",
)

data class KnowledgeUiState(
    val bases: List<KnowledgeBaseUi> = emptyList(),
    val selectedBaseId: String? = null,
    val documents: List<KnowledgeDocumentUi> = emptyList(),
    val jobs: List<KnowledgeImportJobUi> = emptyList(),
    val waiting: List<KnowledgeWaitingUi> = emptyList(),
    val evidence: KnowledgeEvidenceUi? = null,
    val status: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val rebuildEnabled: Boolean = true,
    val language: String = "zh-CN",
    val embeddingSpaceLabel: String = "",
    val embeddingModels: List<KnowledgeEmbeddingModelUi> = emptyList(),
    val apiQueryAttempts: List<KnowledgeQueryAttemptUi> = emptyList(),
    val batches: List<KnowledgeBatchUi> = emptyList(),
    /** Human-readable Vision destination shown on the create-and-import step. */
    val visionTargetLabel: String = "",
    /** True when an image-capable target exists, so visual work can be authorized at creation. */
    val visionConfigured: Boolean = false,
    /** Exact destination identity; display text must never be used as an authorization token. */
    val visionTargetFingerprint: String? = null,
    /** Every configured image-capable model, ordered deterministically for the default choice. */
    val visionTargets: List<KnowledgeVisionTargetUi> = emptyList(),
    /** True while the asynchronous profile refresh is resolving Vision targets. */
    val visionTargetsLoading: Boolean = false,
    /** Import selection survives route disposal and provider configuration navigation. */
    val pendingImport: KnowledgePendingImportUi? = null,
    /** Blocked batch target selection also survives provider configuration navigation. */
    val pendingBatchVision: KnowledgeBatchVisionUi? = null,
)

data class KnowledgeActions(
    val onStageImport: (List<Uri>, String) -> Unit = { _, _ -> },
    val onClearPendingImport: () -> Unit = {},
    val onSelectPendingVisionTarget: (String?) -> Unit = {},
    val onBeginBatchVision: (String) -> Unit = {},
    val onDismissBatchVision: () -> Unit = {},
    val onSelectBatchVisionTarget: (String?) -> Unit = {},
    val onImport: (List<Uri>, String?) -> Unit = { _, _ -> },
    val onImportZip: (Uri, String?) -> Unit = { _, _ -> },
    val onImportFolder: (Uri, String?) -> Unit = { _, _ -> },
    val onSelectBase: (String) -> Unit = {},
    val onOpenEvidence: (String) -> Unit = {},
    val onRebuild: () -> Unit = {},
    val onGrantVision: (String) -> Unit = {},
    val onRetryVision: (String) -> Unit = {},
    val onTextOnly: (String) -> Unit = {},
    val onConfigureVision: () -> Unit = {},
    val onKeepWaiting: () -> Unit = {},
    val onDeleteDocument: (String) -> Unit = {},
    val onCloseEvidence: () -> Unit = {},
    val onCreateBase: (String) -> Unit = {},
    val onDeleteBase: (String) -> Unit = {},
    val onCancelJob: (String) -> Unit = {},
    val onConfigureEmbedding: (String, Int) -> Unit = { _, _ -> },
    val onGrantEmbedding: (String) -> Unit = {},
    val onRetryEmbedding: (String) -> Unit = {},
    val onAuthorizeQueryRetry: (spaceId: String, queryHash: String) -> Unit = { _, _ -> },
    val onPauseBatch: (String) -> Unit = {},
    val onResumeBatch: (String) -> Unit = {},
    /** "Configure and continue": authorizes this batch against the current Vision destination. */
    val onAuthorizeBatchVision: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onConfigurePipeline: (String, PipelinePolicy) -> Unit = { _, _ -> },
    val onRebuildBatchLocalChunks: (String) -> Unit = {},
)

@Composable
fun KnowledgeScreen(
    state: KnowledgeUiState,
    actions: KnowledgeActions = KnowledgeActions(),
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
) {
    val zh = state.language.equals("zh-CN", true)
    // Previews and lightweight harnesses that only wire onImport keep a local staging fallback.
    // The shell-scoped ViewModel (state.pendingImport) is the production owner and always wins,
    // so a route change, provider round trip or process restart can never drop the staged selection.
    var localPendingImport by remember { mutableStateOf<KnowledgePendingImportUi?>(null) }
    // The legacy in-composition path keeps the destination list it saw when the picker returned.
    var localPendingTargets by remember { mutableStateOf<List<KnowledgeVisionTargetUi>>(emptyList()) }
    val pendingImport = state.pendingImport ?: localPendingImport
    val pendingBatchVision = state.pendingBatchVision
    // A snapshot may carry only the legacy display pair (label + fingerprint).  Keep that exact
    // destination selectable instead of silently dropping it; the fingerprint stays the identity.
    val availableTargets = remember(state.visionTargets, state.visionTargetFingerprint, state.visionTargetLabel) {
        if (state.visionTargets.isNotEmpty()) state.visionTargets
        else listOfNotNull(
            state.visionTargetFingerprint?.takeIf { it.isNotBlank() }
                ?.let { KnowledgeVisionTargetUi(fingerprint = it, label = state.visionTargetLabel) },
        )
    }
    // The shell-scoped ViewModel resolves the staged selection against the live destination list.
    // Only the legacy in-composition fallback uses its own snapshot, so a late profile refresh can
    // never rewrite the destination the user already saw in the confirmation dialog.
    val importTargets = if (state.pendingImport != null) availableTargets else localPendingTargets
    val clearStagedImport: () -> Unit = {
        actions.onClearPendingImport()
        localPendingImport = null
        localPendingTargets = emptyList()
    }
    var visionTargetMenu by rememberSaveable { mutableStateOf(false) }
    var batchVisionTargetMenu by rememberSaveable { mutableStateOf(false) }
    val screenActions = actions.copy(onAuthorizeBatchVision = { id, _, _ -> actions.onBeginBatchVision(id) })
    var newBaseName by remember { mutableStateOf("") }
    var newBaseDialog by remember { mutableStateOf(false) }
    var deleteBaseId by remember { mutableStateOf<String?>(null) }
    var deleteDocumentId by remember { mutableStateOf<String?>(null) }
    var rebuildRequested by remember { mutableStateOf(false) }
    var embeddingDialog by remember { mutableStateOf(false) }
    var embeddingModelMenu by remember { mutableStateOf(false) }
    var embeddingModelId by remember { mutableStateOf("") }
    var embeddingDimension by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            actions.onStageImport(uris, "files")
            localPendingTargets = availableTargets
            localPendingImport = KnowledgePendingImportUi(
                id = "local:files:" + uris.joinToString("\n") { it.toString() },
                uris = uris,
                sourceKind = "files",
                selectedVisionTargetFingerprint = availableTargets.firstOrNull()?.fingerprint,
                visionTargetSelectionInitialized = true,
            )
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            actions.onStageImport(listOf(uri), "zip")
            localPendingTargets = availableTargets
            localPendingImport = KnowledgePendingImportUi(
                id = "local:zip:" + uri.toString(),
                uris = listOf(uri),
                sourceKind = "zip",
                selectedVisionTargetFingerprint = availableTargets.firstOrNull()?.fingerprint,
                visionTargetSelectionInitialized = true,
            )
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            actions.onStageImport(listOf(uri), "folder")
            localPendingTargets = availableTargets
            localPendingImport = KnowledgePendingImportUi(
                id = "local:folder:" + uri.toString(),
                uris = listOf(uri),
                sourceKind = "folder",
                selectedVisionTargetFingerprint = availableTargets.firstOrNull()?.fingerprint,
                visionTargetSelectionInitialized = true,
            )
        }
    }
    LaunchedEffect(pendingImport?.id, state.visionTargetsLoading, importTargets) {
        val pending = state.pendingImport ?: localPendingImport ?: return@LaunchedEffect
        if (!pending.visionTargetSelectionInitialized && !state.visionTargetsLoading) {
            // Only a deterministic initial default, and only before the user chose anything.
            val fingerprint = importTargets.firstOrNull()?.fingerprint
            if (state.pendingImport != null) {
                actions.onSelectPendingVisionTarget(fingerprint)
            } else {
                localPendingImport = pending.copy(
                    selectedVisionTargetFingerprint = fingerprint,
                    visionTargetSelectionInitialized = true,
                )
            }
        }
    }
    LaunchedEffect(pendingBatchVision?.batchId, state.visionTargetsLoading, availableTargets) {
        val pending = state.pendingBatchVision ?: return@LaunchedEffect
        if (!pending.visionTargetSelectionInitialized && !state.visionTargetsLoading) {
            actions.onSelectBatchVisionTarget(availableTargets.firstOrNull()?.fingerprint)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KnowledgeBasePane(
                    state, screenActions, zh,
                    { picker.launch(arrayOf("*/*")) },
                    { zipPicker.launch(arrayOf("application/zip", "*/*")) },
                    { folderPicker.launch(null) },
                    { newBaseDialog = true }, { deleteBaseId = it }, {
                    embeddingModelId = ""
                    embeddingDimension = ""
                    embeddingModelMenu = false
                    embeddingDialog = true
                }, Modifier.weight(0.32f).fillMaxSize(), showPageTitle)
                KnowledgeContentPane(state, screenActions, zh, { deleteDocumentId = it }, { rebuildRequested = true }, Modifier.weight(0.68f).fillMaxSize().verticalScroll(rememberScrollState()))
            }
        } else {
            var manageBases by rememberSaveable(state.selectedBaseId) { mutableStateOf(false) }
            val hasBatch = state.batches.isNotEmpty()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasBatch) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.bases.firstOrNull { it.id == state.selectedBaseId }?.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { manageBases = !manageBases }) {
                            Text(if (zh) "管理知识库" else "Manage libraries")
                        }
                    }
                }
                if (!hasBatch || manageBases) {
                KnowledgeBasePane(
                    state, screenActions, zh,
                    { picker.launch(arrayOf("*/*")) },
                    { zipPicker.launch(arrayOf("application/zip", "*/*")) },
                    { folderPicker.launch(null) },
                    { newBaseDialog = true }, { deleteBaseId = it }, {
                    embeddingModelId = ""
                    embeddingDimension = ""
                    embeddingModelMenu = false
                    embeddingDialog = true
                }, Modifier.fillMaxWidth(), showPageTitle)
                }
                KnowledgeContentPane(state, screenActions, zh, { deleteDocumentId = it }, { rebuildRequested = true }, Modifier.fillMaxWidth())
            }
        }
    }
    if (newBaseDialog) {
        AlertDialog(
            onDismissRequest = { newBaseDialog = false },
            title = { Text(if (zh) "新建知识库" else "New knowledge base") },
            text = {
                OutlinedTextField(
                    value = newBaseName,
                    onValueChange = { newBaseName = it },
                    label = { Text(if (zh) "名称" else "Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newBaseName.trim()
                        newBaseName = ""
                        newBaseDialog = false
                        actions.onCreateBase(name)
                    },
                    enabled = newBaseName.trim().isNotEmpty(),
                ) { Text(if (zh) "创建" else "Create") }
            },
            dismissButton = { TextButton(onClick = { newBaseDialog = false }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    deleteBaseId?.let { baseId ->
        val baseName = state.bases.firstOrNull { it.id == baseId }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleteBaseId = null },
            title = { Text(if (zh) "删除知识库？" else "Delete knowledge base?") },
            text = { Text(if (zh) "将删除“$baseName”及其文档索引；此操作不可撤销。" else "This removes $baseName and its document index. The operation cannot be undone.") },
            confirmButton = {
                Button(onClick = { deleteBaseId = null; actions.onDeleteBase(baseId) }) { Text(if (zh) "删除" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteBaseId = null }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    deleteDocumentId?.let { documentId ->
        AlertDialog(
            onDismissRequest = { deleteDocumentId = null },
            title = { Text(if (zh) "删除文档？" else "Delete document?") },
            text = { Text(if (zh) "将从当前知识库及索引中删除此文档；此操作不可撤销。" else "This removes the document from the selected knowledge base and its index. The operation is irreversible.") },
            confirmButton = { Button(onClick = { deleteDocumentId = null; actions.onDeleteDocument(documentId) }) { Text(if (zh) "删除" else "Delete") } },
            dismissButton = { TextButton(onClick = { deleteDocumentId = null }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    if (rebuildRequested) {
        AlertDialog(
            onDismissRequest = { rebuildRequested = false },
            title = { Text(if (zh) "重建索引？" else "Rebuild index?") },
            text = { Text(if (zh) "宿主将从本地持久化文档重建当前知识库索引，过程可能需要一些时间。" else "The host will rebuild the selected knowledge base from persisted local documents. This may take time.") },
            confirmButton = { Button(onClick = { rebuildRequested = false; actions.onRebuild() }) { Text(if (zh) "重建" else "Rebuild") } },
            dismissButton = { TextButton(onClick = { rebuildRequested = false }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    if (embeddingDialog) {
        val selectedBase = state.bases.firstOrNull { it.id == state.selectedBaseId }
        val selectedModel = state.embeddingModels.firstOrNull { it.id == embeddingModelId }
        val dimension = embeddingDimension.toIntOrNull()
        AlertDialog(
            onDismissRequest = {
                embeddingDialog = false
                embeddingModelMenu = false
            },
            title = { Text(if (zh) "配置 API Embedding" else "Configure API Embedding") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (zh) {
                            "配置只保存模型绑定与向量维度，随后还会显示单独的外发与费用确认。"
                        } else {
                            "Configuration only prepares the model binding and vector dimension. A separate export and cost consent is shown next."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (zh) {
                            "知识库文本块和检索查询将发送到所选目标，服务商可能收费。当前知识库文档数：${selectedBase?.documentCount ?: 0}；重新绑定会重新索引。"
                        } else {
                            "Knowledge chunks and retrieval queries will be sent to the selected target and the provider may charge. Current documents: ${selectedBase?.documentCount ?: 0}; rebinding reindexes them."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Box {
                        OutlinedButton(
                            onClick = { embeddingModelMenu = true },
                            enabled = state.embeddingModels.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedModel?.label ?: if (zh) "选择 Embedding 模型" else "Choose an Embedding model")
                        }
                        DropdownMenu(
                            expanded = embeddingModelMenu,
                            onDismissRequest = { embeddingModelMenu = false },
                        ) {
                            state.embeddingModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.label) },
                                    onClick = {
                                        embeddingModelId = model.id
                                        embeddingModelMenu = false
                                    },
                                )
                            }
                        }
                    }
                    if (state.embeddingModels.isEmpty()) {
                        Text(
                            if (zh) "没有可选择的 Embedding 模型，请先在服务商中配置模型。" else "No Embedding models are available. Configure a model under Providers first.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = embeddingDimension,
                        onValueChange = { embeddingDimension = it },
                        label = { Text(if (zh) "向量维度" else "Vector dimension") },
                        supportingText = { Text(if (zh) "必须由模型文档或服务商配置提供，不能猜测。" else "Use the dimension documented by the model or provider; do not guess.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val modelId = embeddingModelId
                        if (dimension != null && modelId.isNotBlank()) {
                            embeddingDialog = false
                            embeddingModelMenu = false
                            actions.onConfigureEmbedding(modelId, dimension)
                        }
                    },
                    enabled = selectedModel != null && embeddingModelId.isNotBlank() && dimension != null && dimension > 0,
                ) { Text(if (zh) "继续确认" else "Continue to consent") }
            },
            dismissButton = {
                TextButton(onClick = {
                    embeddingDialog = false
                    embeddingModelMenu = false
                }) { Text(if (zh) "取消" else "Cancel") }
            },
        )
    }
    pendingImport?.let { pending ->
        val uris = pending.uris
        val sourceKind = pending.sourceKind
        val selectedTarget = importTargets.firstOrNull { it.fingerprint == pending.selectedVisionTargetFingerprint }
        val selectedTargetMissing = pending.visionTargetSelectionInitialized &&
            pending.selectedVisionTargetFingerprint != null && selectedTarget == null
        val selectedBase = state.bases.firstOrNull { it.id == state.selectedBaseId }
        val selectedName = selectedBase?.name ?: if (zh) "默认知识库" else "the default knowledge base"
        AlertDialog(
            onDismissRequest = clearStagedImport,
            title = { Text(if (zh) "创建并开始导入" else "Create and start import") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (zh) "本批次选定 ${uris.size} 项资料，导入到「$selectedName」。" else "${uris.size} selected item(s) will be imported into $selectedName.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (zh) "视觉目标：${selectedTarget?.label ?: if (pending.visionTargetSelectionInitialized) "未选择" else "正在读取配置…" }"
                        else "Vision target: ${selectedTarget?.label ?: if (pending.visionTargetSelectionInitialized) "none selected" else "loading configuration…"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (zh) "创建前选择本批次的视觉服务商和模型。目标会按完整配置指纹固定，不会因为返回配置页或默认值变化而静默切换。"
                        else "Choose the Vision provider and model for this batch before it starts. The full configuration fingerprint is fixed for the batch and will not silently change after a settings round trip.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.visionTargetsLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(if (zh) "正在刷新可用视觉模型…" else "Refreshing available Vision models…")
                        }
                    } else if (importTargets.isNotEmpty()) {
                        Box {
                            OutlinedButton(
                                onClick = { visionTargetMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(selectedTarget?.label ?: if (zh) "选择视觉目标" else "Choose Vision target")
                            }
                            DropdownMenu(
                                expanded = visionTargetMenu,
                                onDismissRequest = { visionTargetMenu = false },
                            ) {
                                importTargets.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.label) },
                                        onClick = {
                                            visionTargetMenu = false
                                            actions.onSelectPendingVisionTarget(target.fingerprint)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (zh) "不选择视觉目标（遇到图片时暂停）" else "No Vision target (pause if images need processing)") },
                                    onClick = {
                                        visionTargetMenu = false
                                        actions.onSelectPendingVisionTarget(null)
                                    },
                                )
                            }
                        }
                        if (selectedTargetMissing) {
                            Text(
                                if (zh) "之前选择的视觉目标已不可用。请选择新的目标，或明确选择不使用视觉目标。"
                                else "The previously selected Vision target is no longer available. Choose another target or explicitly continue without one.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            if (zh) "当前没有可处理图片的视觉模型。纯文本资料可以继续；如果遇到图片，批次会暂停并显示配置入口。"
                            else "No image-capable Vision model is configured. Text-only material can continue; a batch pauses with a configuration action if it reaches an image.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (zh) "可能外发的内容：只有本批次中确实包含图片、且本机解析无法覆盖的页面或图片会发送到上面的视觉目标。纯文本资料只在本机处理。" else "What may leave the device: only pages or images in this batch that actually contain visual content the local parser cannot cover. Text-only material stays local.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (zh) "费用提示：视觉处理由服务商计费，金额取决于实际发送的图片数量与所选模型。本次授权只覆盖本批次已选资料，不会扩展到以后新增的文件。" else "Cost: visual processing is billed by the provider and depends on how many images are actually sent. This authorization covers only the material selected now and never widens to files added later.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (pending.visionTargetSelectionInitialized && pending.selectedVisionTargetFingerprint == null) {
                        Text(
                            if (zh) "当前没有可处理图片的视觉目标。纯文本资料不受影响；一旦真正遇到需要视觉的内容，本批次会整体暂停并提示你配置后继续。" else "No image-capable Vision target is configured. Text-only material is unaffected; if visual content is actually found the whole batch pauses and asks you to configure and continue.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosenTarget = pending.selectedVisionTargetFingerprint
                        clearStagedImport()
                        when (sourceKind) {
                            "zip" -> actions.onImportZip(uris.first(), chosenTarget)
                            "folder" -> actions.onImportFolder(uris.first(), chosenTarget)
                            else -> actions.onImport(uris, chosenTarget)
                        }
                    },
                    enabled = !state.visionTargetsLoading && pending.visionTargetSelectionInitialized && !selectedTargetMissing,
                ) { Text(if (zh) "创建并开始导入" else "Create and start import") }
            },
            dismissButton = { TextButton(onClick = clearStagedImport) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    pendingBatchVision?.let { pending ->
        val batchId = pending.batchId
        val selectedTarget = availableTargets.firstOrNull { it.fingerprint == pending.selectedVisionTargetFingerprint }
        val selectedTargetMissing = pending.visionTargetSelectionInitialized &&
            pending.selectedVisionTargetFingerprint != null && selectedTarget == null
        val reviewedReuse = pending.reusePreview
        var acknowledgeDuplicateCharge by remember(batchId, pending.selectedVisionTargetFingerprint, reviewedReuse) {
            mutableStateOf(false)
        }
        val needsUnknownConfirmation = (reviewedReuse?.unknown ?: 0) > 0
        AlertDialog(
            onDismissRequest = actions.onDismissBatchVision,
            title = { Text(if (zh) "确认本批次视觉处理" else "Confirm batch Vision processing") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (zh) "目标：${selectedTarget?.label ?: if (pending.visionTargetSelectionInitialized) "未选择" else "正在读取配置…" }" else "Target: ${selectedTarget?.label ?: if (pending.visionTargetSelectionInitialized) "none selected" else "loading configuration…"}")
                if (state.visionTargetsLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(if (zh) "正在刷新可用视觉模型…" else "Refreshing available Vision models…")
                    }
                } else if (availableTargets.isNotEmpty()) {
                    Box {
                        OutlinedButton(onClick = { batchVisionTargetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedTarget?.label ?: if (zh) "选择视觉目标" else "Choose Vision target")
                        }
                        DropdownMenu(
                            expanded = batchVisionTargetMenu,
                            onDismissRequest = { batchVisionTargetMenu = false },
                        ) {
                            availableTargets.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text(target.label) },
                                    onClick = {
                                        batchVisionTargetMenu = false
                                        actions.onSelectBatchVisionTarget(target.fingerprint)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (zh) "不选择视觉目标（继续后遇到图片会再次暂停）" else "No Vision target (pause again if images need processing)") },
                                onClick = {
                                    batchVisionTargetMenu = false
                                    actions.onSelectBatchVisionTarget(null)
                                },
                            )
                        }
                    }
                    if (selectedTargetMissing) {
                        Text(
                            if (zh) "之前选择的视觉目标已不可用，请重新选择。" else "The previously selected Vision target is no longer available; choose another target.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(if (zh) "当前没有可处理图片的视觉模型。请先配置后返回。" else "No image-capable Vision model is configured. Configure one and return.")
                }
                Text(if (zh) "仅将本批次需要视觉处理的页面或图片发送到此目标。服务商可能收费；后续新增资料不在授权范围内。" else "Send only this batch's required visual pages or images to this destination. Provider charges may apply. Files added later are excluded.")
                reviewedReuse?.let { ReuseSummaryText(it, zh) }
                if (pending.selectedVisionTargetFingerprint != null && reviewedReuse == null) {
                    Text(if (zh) "正在读取本地处理范围，完成后才能确认。" else "Loading the local processing scope before confirmation.")
                }
                if (needsUnknownConfirmation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledgeDuplicateCharge, onCheckedChange = { acknowledgeDuplicateCharge = it })
                        Text(if (zh) "我确认重试 UNKNOWN；旧请求可能已收费，新请求可能重复收费。" else "I approve retrying UNKNOWN: previous requests may have charged, and new requests may charge again.")
                    }
                }
                if (pending.visionTargetSelectionInitialized && pending.selectedVisionTargetFingerprint == null) Text(if (zh) "请先选择可处理图片的模型，然后返回继续。" else "Choose an image-capable model before continuing.")
            } },
            confirmButton = { Button(onClick = {
                if (pending.selectedVisionTargetFingerprint != null && !selectedTargetMissing) {
                    actions.onAuthorizeBatchVision(batchId, pending.selectedVisionTargetFingerprint, acknowledgeDuplicateCharge)
                    actions.onDismissBatchVision()
                } else actions.onConfigureVision()
            }, enabled = !state.visionTargetsLoading && pending.visionTargetSelectionInitialized && !selectedTargetMissing &&
                (pending.selectedVisionTargetFingerprint == null ||
                    (reviewedReuse != null && (!needsUnknownConfirmation || acknowledgeDuplicateCharge)))) {
                Text(if (pending.selectedVisionTargetFingerprint == null) (if (zh) "配置视觉模型" else "Configure Vision model") else (if (zh) "确认并继续" else "Confirm and continue"))
            } },
            dismissButton = { TextButton(onClick = actions.onDismissBatchVision) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    state.evidence?.let { EvidenceDialog(it, actions.onCloseEvidence) }
}

@Composable
private fun KnowledgeBasePane(
    state: KnowledgeUiState,
    actions: KnowledgeActions,
    zh: Boolean,
    onImport: () -> Unit,
    onImportZip: () -> Unit,
    onImportFolder: () -> Unit,
    onCreateBase: () -> Unit,
    onDeleteBase: (String) -> Unit,
    onConfigureEmbedding: () -> Unit,
    modifier: Modifier,
    showPageTitle: Boolean,
) {
    Column(modifier) {
        if (showPageTitle) {
            Text(if (zh) "知识" else "Knowledge", style = MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreateBase) { Text(if (zh) "新建" else "New") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport) { Text(if (zh) "添加文件" else "Add files") }
            OutlinedButton(onClick = onImportFolder) { Text(if (zh) "导入文件夹" else "Import folder") }
            OutlinedButton(onClick = onImportZip) { Text(if (zh) "导入 ZIP" else "Import ZIP") }
        }
        Text(if (zh) "文件、文件夹和知识库 ZIP 通过系统选择器进入应用管理存储；DOCX/EPUB 仍按办公文档解析。" else "Files, folders, and knowledge ZIP archives stay in app-managed storage. DOCX/EPUB remain office documents.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        if (state.embeddingSpaceLabel.isNotBlank()) {
            Text(
                if (zh) "当前 Embedding 空间：${state.embeddingSpaceLabel}" else "Current Embedding space: ${state.embeddingSpaceLabel}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (state.loading) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        else if (state.error != null) {
            Card(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) { Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(14.dp)) }
        }
        else if (state.bases.isEmpty()) Text(if (zh) "暂无知识库。" else "No knowledge bases available.", modifier = Modifier.padding(top = 16.dp))
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(280.dp).padding(top = 12.dp)) {
            items(state.bases, key = { it.id }) { base ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (base.id == state.selectedBaseId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { actions.onSelectBase(base.id) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(base.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (zh) "${base.documentCount} 个文档" else "${base.documentCount} documents", style = MaterialTheme.typography.bodySmall)
                        if (base.status.isNotBlank()) Text(base.status, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        val selectedBase = state.selectedBaseId
        if (selectedBase != null) {
            OutlinedButton(onClick = { onDeleteBase(selectedBase) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (zh) "删除当前知识库" else "Delete selected base")
            }
            OutlinedButton(onClick = onConfigureEmbedding, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (zh) "配置 API Embedding" else "Configure API Embedding")
            }
        }
    }
}

@Composable
private fun KnowledgeContentPane(state: KnowledgeUiState, actions: KnowledgeActions, zh: Boolean, onDelete: (String) -> Unit, onRebuild: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        if (state.status.isNotBlank()) StatusCard(state.status)
        // The default surface is exactly one overall progress card per durable batch.  Per-file
        // cards, technical fields and logs only appear behind the user-opened detail section.
        state.batches.forEach { batch -> BatchProgressCard(batch, state.jobs, actions, zh, state.loading) }
        if (state.batches.isEmpty()) {
            // Legacy single-file imports have no durable batch row and keep their per-item cards.
            state.waiting.forEach { WaitingCard(it, actions, zh) }
        }
        val selectedQueryAttempts = state.apiQueryAttempts
        if (selectedQueryAttempts.isNotEmpty()) {
            Text(
                if (zh) "未知查询" else "Queries with unknown results",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            selectedQueryAttempts.forEach { attempt -> QueryRetryCard(attempt, actions, zh) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "文档" else "Documents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRebuild, enabled = state.rebuildEnabled && !knowledgeImportActive(state)) {
                Text(if (zh) "重建索引" else "Rebuild index")
            }
        }
        if (state.batches.isEmpty() && (knowledgeImportActive(state) || state.jobs.isNotEmpty() || state.loading)) {
            Text(knowledgeImportSummary(state, zh), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.documents.isEmpty() && !knowledgeImportActive(state) && !state.loading && state.jobs.isEmpty()) {
            Text(if (zh) "此知识库没有文档。" else "No documents in this knowledge base.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        var showDocuments by rememberSaveable(state.selectedBaseId) { mutableStateOf(false) }
        if (state.batches.isNotEmpty()) {
            TextButton(onClick = { showDocuments = !showDocuments }) {
                Text(if (showDocuments) (if (zh) "收起文档" else "Hide documents") else (if (zh) "查看 ${state.documents.size} 个文档" else "Show ${state.documents.size} documents"))
            }
        }
        if (state.batches.isEmpty() || showDocuments) {
            state.documents.forEach { document -> DocumentCard(document, onDelete, actions, zh) }
        }
        val batchJobIds = state.batches.flatMap { it.items }.mapNotNull { it.jobId }.toSet()
        val legacyJobs = state.jobs.filter { it.id !in batchJobIds }
        if (legacyJobs.isNotEmpty()) {
            Text(if (zh) "导入任务" else "Import jobs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
            legacyJobs.forEach { job -> JobCard(job, actions, zh) }
        }
        Spacer(Modifier.height(16.dp))
        Text(if (zh) "未明确配置视觉模型的图片会保持等待，不会标记为已完成。" else "Images without an explicitly configured Vision model remain waiting and are not marked ready.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun QueryRetryCard(attempt: KnowledgeQueryAttemptUi, actions: KnowledgeActions, zh: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (zh) "查询结果未知" else "Query result is unknown",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (zh) "查询哈希（前 12 位）：${attempt.queryHash.take(12).ifBlank { "未提供" }}"
                else "Query hash (first 12): ${attempt.queryHash.take(12).ifBlank { "not provided" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (zh) "目标：${attempt.target.ifBlank { "未提供" }}"
                else "Target: ${attempt.target.ifBlank { "Not provided" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (attempt.retryAuthorized) {
                Text(
                    if (zh) "已授权：等待你重新提交此查询。授权不会自动发起请求。"
                    else "Authorized: waiting for you to resubmit this query. Authorization does not send a request automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    if (zh) "再次提交可能重复收费。授权仅限同一知识库、同一模型与同一查询一次；点击后不会自动请求。"
                    else "Resubmitting may incur a duplicate charge. Authorization is limited to this knowledge base, model, and query once; it will not send a request automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { actions.onAuthorizeQueryRetry(attempt.spaceId, attempt.queryHash) },
                    enabled = attempt.spaceId.isNotBlank() && attempt.queryHash.isNotBlank(),
                ) {
                    Text(if (zh) "允许再次提交此查询" else "Allow resubmitting this query")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The single overall progress card for one durable batch.
 *
 * It never presents copied bytes as finished work, never calls a queue an upload, and never keeps a
 * running animation while the batch is paused or blocked.  Everything technical lives behind the
 * user-opened detail section.
 */
@Composable
private fun BatchProgressCard(batch: KnowledgeBatchUi, jobs: List<KnowledgeImportJobUi>, actions: KnowledgeActions, zh: Boolean, busy: Boolean) {
    var expanded by rememberSaveable(batch.id) { mutableStateOf(false) }
    var confirmResume by rememberSaveable(batch.id) { mutableStateOf(false) }
    var editPolicy by rememberSaveable(batch.id) { mutableStateOf(false) }
    if (confirmResume) AlertDialog(
        onDismissRequest = { confirmResume = false },
        title = { Text(if (zh) "确认恢复范围" else "Review resume scope") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReuseSummaryText(batch.reuse, zh)
            Text(if (zh) "成功单元保留；UNKNOWN 不会自动重试。暂停只停止新增请求，在途请求仍可能完成并收费。取消后保留成果；重新启动仍受相同检查点约束。"
                else "Successful units are retained; UNKNOWN is never retried automatically. Pause stops new dispatch; in-flight requests may finish and incur charges. Cancel retains results; restarting uses the same checkpoints.")
        } },
        confirmButton = { Button(onClick = { confirmResume = false; actions.onResumeBatch(batch.id) }) {
            Text(if (zh) "确认继续" else "Confirm resume")
        } },
        dismissButton = { TextButton(onClick = { confirmResume = false }) { Text(if (zh) "返回" else "Back") } },
    )
    if (editPolicy) PipelinePolicyDialog(batch, zh, onDismiss = { editPolicy = false }) { policy ->
        editPolicy = false
        actions.onConfigurePipeline(batch.id, policy)
    }
    val blocked = batch.blockedReason != null || batch.state.equals("BLOCKED", true)
    val paused = batch.paused || batch.state.equals("PAUSED", true)
    val percent = if (batch.totalItems > 0) batch.published * 100 / batch.totalItems else null
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                blocked -> MaterialTheme.colorScheme.errorContainer
                paused -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(batch.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    batch.state.equals("COMPLETED", true) -> if (zh) "已完成" else "Completed"
                    blocked -> if (batch.blockedReason == "MISSING_VISUAL_SOURCE") {
                        if (zh) "已阻塞：资料只包含图片引用，请展开详情处理" else "Blocked: referenced images are missing; open details"
                    } else if (batch.blockedReason == "VISION_TARGET_CHANGED") {
                        if (zh) "已阻塞：视觉目标已变更，需要重新确认" else "Blocked: the Vision destination changed and needs re-confirmation"
                    } else if (zh) "已阻塞：需要视觉模型" else "Blocked: needs a Vision model"
                    paused -> if (zh) "已暂停" else "Paused"
                    batch.unknown > 0 -> if (zh) "请求结果未知：请展开详情确认是否重试" else "Request outcome unknown: open details to decide whether to retry"
                    batch.state.equals("FAILED", true) -> if (zh) "导入失败" else "Import failed"
                    batch.state.equals("CANCELLED", true) -> if (zh) "已取消" else "Cancelled"
                    batch.state.equals("WAITING", true) -> if (zh) "等待中" else "Waiting"
                    batch.resumeStagingAvailable -> if (zh) "复制已中断，可从已保存位置继续" else "Copy interrupted; resume from the saved checkpoint"
                    batch.state.equals("COPYING", true) -> if (zh) "正在复制本地资料" else "Copying local files"
                    else -> if (zh) "正在处理" else "Processing"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            // The unknown reason must be visible on the card itself, not only behind "details".
            val unknownReason = batch.items.asSequence().mapNotNull { it.error }
                .firstOrNull { it.contains("UNKNOWN_OUTCOME") }?.take(300)
            if (unknownReason != null) {
                Text(
                    if (zh) "未知原因：$unknownReason" else "Unknown reason: $unknownReason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Honest progress: finished means published and searchable, never merely copied.
            ProgressRow(batch, percent, zh)
            PipelineProgressText(batch.pipeline, zh)
            batch.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    blocked && batch.blockedReason == "MISSING_VISUAL_SOURCE" -> {
                        Text(if (zh) "请单独导入图片，或在详情中明确选择仅文本（保留视觉缺口）。" else "Import the images separately, or explicitly accept text only with visual gaps in details.")
                        TextButton(onClick = { expanded = true }) { Text(if (zh) "查看待处理资料" else "Review affected items") }
                    }
                    blocked -> {
                        Button(onClick = { actions.onAuthorizeBatchVision(batch.id, "", false) }, enabled = !busy) {
                            Text(if (zh) "配置并继续" else "Configure and continue")
                        }
                        OutlinedButton(onClick = { actions.onConfigureVision() }) {
                            Text(if (zh) "配置视觉模型" else "Configure Vision model")
                        }
                    }
                    paused || batch.resumeStagingAvailable -> Button(onClick = { confirmResume = true }, enabled = !busy) {
                        Text(if (zh) "继续导入" else "Resume import")
                    }
                    batch.state.uppercase() !in setOf("COMPLETED", "CANCELLED", "FAILED") ->
                        OutlinedButton(onClick = { actions.onPauseBatch(batch.id) }) {
                            Text(if (zh) "暂停" else "Pause")
                        }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) (if (zh) "收起详情" else "Hide details")
                        else (if (zh) "展开 ${batch.items.size} 项详情" else "Show ${batch.items.size} item details"),
                    )
                }
            }
            if (expanded) {
            ReuseSummaryText(batch.reuse, zh)
            if (batch.reuse.localRebuild > 0 && batch.reuse.newRequests == 0 && batch.reuse.unknown == 0 && batch.reuse.unplannedFiles == 0) {
                OutlinedButton(onClick = { actions.onRebuildBatchLocalChunks(batch.id) }, enabled = !busy) {
                    Text(if (zh) "仅本地重建检索片段" else "Rebuild retrieval chunks locally")
                }
            }
            Text(if (zh) "最大并发 1 · 连续失败停止阈值 ${batch.policy.consecutiveFailureLimit}" else "Concurrency 1 · Stop after ${batch.policy.consecutiveFailureLimit} consecutive failures")
            TextButton(onClick = { editPolicy = true }, enabled = !busy) {
                Text(if (zh) "处理限制" else "Processing limits")
            }
            TextButton(onClick = { actions.onAuthorizeBatchVision(batch.id, "", false) }, enabled = !busy) {
                Text(if (zh) "查看更换视觉配置的影响" else "Review Vision configuration change")
            }
            Text(
                if (zh) {
                    "已复制 ${batch.copied} / ${batch.totalItems} · 待复制 ${batch.pending} · 处理中 ${batch.processing} · 等待 ${batch.waiting} · 失败 ${batch.failed}" +
                        if (batch.unknown > 0) " · 待确认 ${batch.unknown}" else ""
                } else {
                    "Copied ${batch.copied} / ${batch.totalItems} · pending ${batch.pending} · processing ${batch.processing} · waiting ${batch.waiting} · failed ${batch.failed}" +
                        if (batch.unknown > 0) " · unknown ${batch.unknown}" else ""
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            batch.visionTarget?.let { fingerprint ->
                Text(
                    if (zh) "本批次视觉目标：${batch.visionTargetLabel ?: fingerprint}" else "Batch Vision target: ${batch.visionTargetLabel ?: fingerprint}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    batch.items.forEach { item ->
                        val job = jobs.firstOrNull { it.id == item.jobId }
                        if (job != null) {
                            JobCard(job.copy(requiresVisionConsent = false), actions, zh)
                            if (batch.blockedReason == "MISSING_VISUAL_SOURCE" && job.stage == "WAITING_FOR_VISION_MODEL") {
                                OutlinedButton(onClick = { actions.onTextOnly(job.id) }, enabled = !busy) {
                                    Text(if (zh) "仅文本继续（保留视觉缺口）" else "Continue with text only (visual gaps remain)")
                                }
                            }
                        }
                        else BatchItemRow(item, zh)
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineProgressText(progress: PipelineProgress, zh: Boolean) {
    Text(if (zh) "页面 ${progress.pages} · 处理单元 ${progress.units}" else "Pages ${progress.pages} · Processing units ${progress.units}", style = MaterialTheme.typography.bodySmall)
    Text(if (zh) "待处理 ${progress.pending} · 在途 ${progress.inFlight} · 成功 ${progress.succeeded} · 失败 ${progress.failed} · UNKNOWN ${progress.unknown} · 已索引 ${progress.published}"
        else "Pending ${progress.pending} · In flight ${progress.inFlight} · Succeeded ${progress.succeeded} · Failed ${progress.failed} · UNKNOWN ${progress.unknown} · Indexed ${progress.published}", style = MaterialTheme.typography.bodySmall)
    val usage = progress.usage
    Text(if (zh) "Provider token：输入 ${usage.inputTokens ?: "未知"} · 输出 ${usage.outputTokens ?: "未知"} · reasoning ${usage.reasoningTokens ?: "未知"}（包含在输出中）"
        else "Provider tokens: input ${usage.inputTokens ?: "unknown"} · output ${usage.outputTokens ?: "unknown"} · reasoning ${usage.reasoningTokens ?: "unknown"} (included in output)", style = MaterialTheme.typography.bodySmall)
    Text(if (zh) "usage 未知请求 ${usage.unknownUsageAttempts} · 安全预留 ${usage.reservedTokens}（不是实际收费）"
        else "Unknown usage attempts ${usage.unknownUsageAttempts} · Safety reservation ${usage.reservedTokens} (not actual charges)", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReuseSummaryText(summary: PipelineReuseSummary, zh: Boolean) {
    Text(if (zh) "可直接复用 ${summary.directReuse} · 仅本地重建 ${summary.localRebuild} · 新增 Provider 请求 ${summary.newRequests} · UNKNOWN 待确认 ${summary.unknown}"
        else "Reuse ${summary.directReuse} · Local rebuild ${summary.localRebuild} · New Provider requests ${summary.newRequests} · UNKNOWN needs confirmation ${summary.unknown}", style = MaterialTheme.typography.bodySmall)
    if (summary.unplannedFiles > 0) Text(if (zh) "尚未规划/旧文件 ${summary.unplannedFiles}：计划完成后才能确定请求数。" else "Unplanned/legacy files ${summary.unplannedFiles}: request count is not yet known.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PipelinePolicyDialog(batch: KnowledgeBatchUi, zh: Boolean, onDismiss: () -> Unit, onSave: (PipelinePolicy) -> Unit) {
    var failures by remember { mutableStateOf(batch.policy.consecutiveFailureLimit.toString()) }
    var ceiling by remember { mutableStateOf(batch.policy.tokenDispatchCeiling?.toString().orEmpty()) }
    var reservation by remember { mutableStateOf(batch.policy.reservationTokensPerRequest?.toString().orEmpty()) }
    val valid = failures.toIntOrNull()?.let { it > 0 } == true &&
        (ceiling.isBlank() || ceiling.toLongOrNull()?.let { it > 0 } == true) &&
        (reservation.isBlank() || reservation.toLongOrNull()?.let { it > 0 } == true) &&
        (ceiling.isBlank() || reservation.isNotBlank())
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (zh) "处理限制" else "Processing limits") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (zh) "最大并发：1。达到限制只停止新请求；UNKNOWN 保留安全预算。" else "Maximum concurrency: 1. Limits stop new dispatch only; UNKNOWN retains its safety reservation.")
            OutlinedTextField(failures, { failures = it }, label = { Text(if (zh) "连续失败阈值" else "Consecutive failure limit") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(ceiling, { ceiling = it }, label = { Text(if (zh) "批次 token 派发上限（可留空）" else "Token dispatch ceiling (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(reservation, { reservation = it }, label = { Text(if (zh) "每请求保守预留" else "Conservative reservation per request") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text(if (zh) "预留用于限制后续派发，不是 Provider 实际 token 或货币费用。" else "Reservations limit subsequent dispatch; they are not provider usage or currency charges.")
        } },
        confirmButton = { Button(enabled = valid, onClick = { onSave(PipelinePolicy(consecutiveFailureLimit = failures.toInt(), tokenDispatchCeiling = ceiling.toLongOrNull(), reservationTokensPerRequest = reservation.toLongOrNull())) }) { Text(if (zh) "保存" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (zh) "取消" else "Cancel") } })
}

@Composable
private fun ProgressRow(batch: KnowledgeBatchUi, percent: Int?, zh: Boolean) {
    if (batch.totalItems <= 0) {
        Text(
            if (zh) "本批次文件总量尚未确定。" else "The batch file count is not known yet.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        return
    }
    Column(Modifier.padding(top = 6.dp)) {
        LinearProgressIndicator(
            progress = { (percent ?: 0).toFloat() / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (zh) "已完成 ${batch.published} / ${batch.totalItems}（${percent ?: 0}%）"
            else "Finished ${batch.published} / ${batch.totalItems} (${percent ?: 0}%)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun BatchItemRow(item: KnowledgeBatchItemUi, zh: Boolean) {
    Column {
        Text("${item.displayName} · ${knowledgeStageLabel(item.state, zh)}", style = MaterialTheme.typography.labelMedium)
        item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}
@Composable
private fun WaitingCard(waiting: KnowledgeWaitingUi, actions: KnowledgeActions, zh: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(if (zh) "等待视觉模型" else "Waiting for Vision model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(waiting.displayName, modifier = Modifier.padding(top = 4.dp))
            Text(waiting.reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (waiting.authorizationTarget.isNotBlank()) Text(if (zh) "授权目标：${waiting.authorizationTarget}" else "Authorization target: ${waiting.authorizationTarget}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (waiting.canConfigureVision) OutlinedButton(onClick = actions.onConfigureVision) { Text(if (zh) "配置视觉" else "Configure Vision") }
                Button(onClick = actions.onKeepWaiting) { Text(if (zh) "继续等待" else "Keep waiting") }
                OutlinedButton(onClick = { actions.onTextOnly(waiting.jobId) }) { Text(if (zh) "仅使用文本" else "Use text only") }
            }
        }
    }
}

@Composable
private fun DocumentCard(document: KnowledgeDocumentUi, onDelete: (String) -> Unit, actions: KnowledgeActions, zh: Boolean) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(document.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = document.status.equals("READY", true), onClick = {}, enabled = false, label = { Text(knowledgeStageLabel(document.status.ifBlank { "UNKNOWN" }, zh)) })
            }
            if (document.mimeType.isNotBlank()) Text(document.mimeType, style = MaterialTheme.typography.bodySmall)
            if (document.sizeLabel.isNotBlank() || document.updatedAt.isNotBlank()) Text(listOf(document.sizeLabel, document.updatedAt).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onOpenEvidence(document.id) }) { Text(if (zh) "查看证据" else "View evidence") }
                OutlinedButton(onClick = { onDelete(document.id) }) { Text(if (zh) "删除" else "Delete") }
            }
        }
    }
}

@Composable
private fun JobCard(job: KnowledgeImportJobUi, actions: KnowledgeActions, zh: Boolean) {
    Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(job.displayName, style = MaterialTheme.typography.labelLarge)
            Text(knowledgeStageLabel(job.stage, zh), style = MaterialTheme.typography.bodySmall)
            job.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val embeddingConsentRequired = job.requiresEmbeddingConsent || job.stage.equals("AWAITING_EMBEDDING_CONSENT", true)
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (embeddingConsentRequired) {
                    Button(onClick = { actions.onGrantEmbedding(job.id) }) {
                        Text(if (zh) "同意 API Embedding" else "Consent to API Embedding")
                    }
                }
                if (job.requiresVisionConsent) {
                    Button(onClick = { actions.onGrantVision(job.id) }) { Text(if (zh) "批准视觉上传" else "Approve Vision upload") }
                }
                if (job.unknownOutcome && job.embeddingIsApi) {
                    OutlinedButton(onClick = { actions.onRetryEmbedding(job.id) }) {
                        Text(if (zh) "重试 Embedding（可能重复收费）" else "Retry Embedding (may charge twice)")
                    }
                } else if (job.unknownOutcome) {
                    OutlinedButton(onClick = { actions.onRetryVision(job.id) }) { Text(if (zh) "重试视觉（可能重复收费）" else "Retry Vision (may charge twice)") }
                }
                if (job.stage !in setOf("READY", "FAILED", "CANCELLED")) {
                    TextButton(onClick = { actions.onCancelJob(job.id) }) { Text(if (zh) "取消任务" else "Cancel job") }
                }
            }
        }
    }
}

private fun knowledgeStageLabel(stage: String, zh: Boolean): String = when (stage.uppercase()) {
    "UNKNOWN" -> if (zh) "未知" else "Unknown"
    "NOT_READY" -> if (zh) "未完成" else "Not ready"
    "READY" -> if (zh) "已完成" else "Ready"
    "READY_WITH_VISUAL_GAPS" -> if (zh) "仅文本（视觉未处理）" else "Text only (visual gaps)"
    "FAILED" -> if (zh) "失败" else "Failed"
    "CANCELLED" -> if (zh) "已取消" else "Cancelled"
    "COPYING" -> if (zh) "复制中" else "Copying"
    "HASHING" -> if (zh) "计算哈希中" else "Hashing"
    "PARSING" -> if (zh) "解析中" else "Parsing"
    "CHUNKING" -> if (zh) "文本分块中" else "Chunking"
    "EMBEDDING" -> if (zh) "生成向量中" else "Embedding"
    "INDEXING" -> if (zh) "建立索引中" else "Indexing"
    "RETRY_WAIT" -> if (zh) "等待重试" else "Waiting to retry"
    "WAITING_FOR_VISION_MODEL" -> if (zh) "等待视觉模型" else "Waiting for Vision model"
    "AWAITING_UPLOAD_CONSENT" -> if (zh) "等待视觉上传授权" else "Waiting for Vision upload consent"
    "AWAITING_EMBEDDING_CONSENT" -> if (zh) "等待 API Embedding 授权" else "Waiting for API Embedding consent"
    else -> stage
}

@Composable
private fun EvidenceDialog(evidence: KnowledgeEvidenceUi, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
            title = { Text("证据 / Evidence") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(evidence.source)
                evidence.chunkCount?.let { Text("分块 / Chunks: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (evidence.contentHash.isNotBlank()) Text("内容哈希 / Content hash: ${evidence.contentHash}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                evidence.verified?.let { Text(if (it) "证据已验证 / Evidence verified" else "证据未验证 / Evidence not verified", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (evidence.details.isNotBlank()) Text(evidence.details, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("关闭 / Close") } },
    )
}

private val KNOWLEDGE_TERMINAL_STAGES = setOf("READY", "READY_WITH_VISUAL_GAPS", "FAILED", "CANCELLED")

private fun knowledgeImportActive(state: KnowledgeUiState): Boolean =
    state.loading || state.jobs.any { it.stage.uppercase() !in KNOWLEDGE_TERMINAL_STAGES }

private fun knowledgeImportSummary(state: KnowledgeUiState, zh: Boolean): String {
    val jobs = state.jobs
    val processing = jobs.count { it.stage.uppercase() !in KNOWLEDGE_TERMINAL_STAGES && it.stage.uppercase() !in setOf("WAITING_FOR_VISION_MODEL", "AWAITING_UPLOAD_CONSENT", "AWAITING_EMBEDDING_CONSENT") }
    val waiting = jobs.count { it.stage.uppercase() in setOf("WAITING_FOR_VISION_MODEL", "AWAITING_UPLOAD_CONSENT", "AWAITING_EMBEDDING_CONSENT") }
    val failed = jobs.count { it.stage.equals("FAILED", true) }
    val ready = jobs.count { it.stage.equals("READY", true) || it.stage.equals("READY_WITH_VISUAL_GAPS", true) }
    return if (zh) {
        "导入进度：共 ${jobs.size} 项，处理中 $processing，等待 $waiting，完成 $ready，失败 $failed。"
    } else {
        "Import progress: ${jobs.size} item(s), $processing processing, $waiting waiting, $ready finished, $failed failed."
    }
}
