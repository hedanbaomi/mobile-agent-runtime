// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.knowledge

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Provider settings. The ViewModel takes persistable URI grants on IO; leaving
 * the Knowledge route does not discard this staged selection.
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
    val storageUsedBytes: Long = 0,
    val storageQuotaBytes: Long = 0,
    val foreignKeyIssueCount: Int = 0,
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
    /** True while the confirmed selection is being resolved against SAF and the coordinator. */
    val importSubmitting: Boolean = false,
    /** Blocked batch target selection also survives provider configuration navigation. */
    val pendingBatchVision: KnowledgeBatchVisionUi? = null,
)

data class KnowledgeActions(
    val onCollectStorage: () -> Unit = {},
    val onConfigureStorageQuota: (Long) -> Unit = {},
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
                }, Modifier.weight(0.32f).fillMaxSize().verticalScroll(rememberScrollState()), showPageTitle)
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
                            Text(stringResource(R.string.ui_manage_libraries_81eba5d7))
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
            title = { Text(stringResource(R.string.ui_new_knowledge_base_b46c2c13)) },
            text = {
                OutlinedTextField(
                    value = newBaseName,
                    onValueChange = { newBaseName = it },
                    label = { Text(stringResource(R.string.ui_name_dd4dc4c5)) },
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
                ) { Text(stringResource(R.string.ui_create_7570cfc4)) }
            },
            dismissButton = { TextButton(onClick = { newBaseDialog = false }) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
        )
    }
    deleteBaseId?.let { baseId ->
        val baseName = state.bases.firstOrNull { it.id == baseId }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleteBaseId = null },
            title = { Text(stringResource(R.string.ui_delete_knowledge_base_96db098f)) },
            text = { Text(stringResource(R.string.ui_this_removes_s_and_its_document_d99c6d71, (baseName))) },
            confirmButton = {
                Button(onClick = { deleteBaseId = null; actions.onDeleteBase(baseId) }) { Text(stringResource(R.string.ui_delete_5b875326)) }
            },
            dismissButton = { TextButton(onClick = { deleteBaseId = null }) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
        )
    }
    deleteDocumentId?.let { documentId ->
        AlertDialog(
            onDismissRequest = { deleteDocumentId = null },
            title = { Text(stringResource(R.string.ui_delete_document_5c27de9e)) },
            text = { Text(stringResource(R.string.ui_this_removes_the_document_from_the_9b67d9e1)) },
            confirmButton = { Button(onClick = { deleteDocumentId = null; actions.onDeleteDocument(documentId) }) { Text(stringResource(R.string.ui_delete_5b875326)) } },
            dismissButton = { TextButton(onClick = { deleteDocumentId = null }) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
        )
    }
    if (rebuildRequested) {
        AlertDialog(
            onDismissRequest = { rebuildRequested = false },
            title = { Text(stringResource(R.string.ui_rebuild_index_5ad6867e)) },
            text = { Text(stringResource(R.string.ui_rebuild_the_index_from_local_documents_5c750e9a)) },
            confirmButton = { Button(onClick = { rebuildRequested = false; actions.onRebuild() }) { Text(stringResource(R.string.ui_rebuild_fae46c9d)) } },
            dismissButton = { TextButton(onClick = { rebuildRequested = false }) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
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
            title = { Text(stringResource(R.string.ui_configure_api_embedding_6fc9442f)) },
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
                            stringResource(R.string.ui_no_embedding_models_are_available_configure_5d20e7ef),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = embeddingDimension,
                        onValueChange = { embeddingDimension = it },
                        label = { Text(stringResource(R.string.ui_vector_dimension_0b32824c)) },
                        supportingText = { Text(stringResource(R.string.ui_use_the_dimension_documented_by_the_6a0fbcdc)) },
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
                ) { Text(stringResource(R.string.ui_continue_to_consent_61370b6a)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    embeddingDialog = false
                    embeddingModelMenu = false
                }) { Text(stringResource(R.string.ui_cancel_998b9c48)) }
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
            title = { Text(stringResource(R.string.ui_create_and_start_import_bb498dff)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.ui_s_selected_item_s_will_be_1958c56f, (uris.size), (selectedName)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.importSubmitting) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(
                                stringResource(R.string.ui_preparing_the_selected_items_d19b0aca),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    // A busy or rejected submission keeps this dialog open with the user's selection
                    // and the precise reason, so nothing is dropped without an explanation.
                    state.error?.let { reason ->
                        Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (zh) "视觉目标：${selectedTarget?.label ?: if (pending.visionTargetSelectionInitialized) "未选择" else "正在读取配置…" }"
                        else "Vision target: ${selectedTarget?.label ?: if (pending.visionTargetSelectionInitialized) "none selected" else "loading configuration…"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.ui_this_batch_uses_the_selected_vision_50936f49),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.visionTargetsLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.ui_refreshing_available_vision_models_c9976a12))
                        }
                    } else if (importTargets.isNotEmpty()) {
                        Box {
                            OutlinedButton(
                                onClick = { visionTargetMenu = true },
                                enabled = !state.importSubmitting,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(selectedTarget?.label ?: if (zh) "选择视觉目标" else "Choose Vision target")
                            }
                            DropdownMenu(
                                expanded = visionTargetMenu && !state.importSubmitting,
                                onDismissRequest = { visionTargetMenu = false },
                            ) {
                                importTargets.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.label) },
                                        enabled = !state.importSubmitting,
                                        onClick = {
                                            visionTargetMenu = false
                                            actions.onSelectPendingVisionTarget(target.fingerprint)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_no_vision_target_pause_if_images_7140120e)) },
                                    enabled = !state.importSubmitting,
                                    onClick = {
                                        visionTargetMenu = false
                                        actions.onSelectPendingVisionTarget(null)
                                    },
                                )
                            }
                        }
                        if (selectedTargetMissing) {
                            Text(
                                stringResource(R.string.ui_the_previously_selected_vision_target_is_bf0caeeb),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.ui_no_image_capable_vision_model_is_62cdaf25),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        stringResource(R.string.ui_what_may_leave_the_device_only_57aa8cb4),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.ui_cost_visual_processing_is_billed_by_1b99577e),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (pending.visionTargetSelectionInitialized && pending.selectedVisionTargetFingerprint == null) {
                        Text(
                            stringResource(R.string.ui_no_image_capable_vision_target_is_c46cf4cf),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosenTarget = pending.selectedVisionTargetFingerprint
                        // Only the in-composition fallback is dropped here.  A ViewModel-owned
                        // selection is cleared by the ViewModel once the coordinator actually
                        // accepted the batch, so a busy, rejected or failed submission keeps the
                        // user's selection visible together with its reason.
                        localPendingImport = null
                        localPendingTargets = emptyList()
                        when (sourceKind) {
                            "zip" -> actions.onImportZip(uris.first(), chosenTarget)
                            "folder" -> actions.onImportFolder(uris.first(), chosenTarget)
                            else -> actions.onImport(uris, chosenTarget)
                        }
                    },
                    enabled = !state.visionTargetsLoading && pending.visionTargetSelectionInitialized &&
                        !selectedTargetMissing && !state.importSubmitting,
                ) { Text(stringResource(R.string.ui_create_and_start_import_bb498dff)) }
            },
            dismissButton = { TextButton(onClick = clearStagedImport) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
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
            title = { Text(stringResource(R.string.ui_confirm_batch_vision_processing_8fec22de)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (zh) "目标：${selectedTarget?.label ?: if (pending.visionTargetSelectionInitialized) "未选择" else "正在读取配置…" }" else "Target: ${selectedTarget?.label ?: if (pending.visionTargetSelectionInitialized) "none selected" else "loading configuration…"}")
                if (state.visionTargetsLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.ui_refreshing_available_vision_models_c9976a12))
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
                                text = { Text(stringResource(R.string.ui_no_vision_target_pause_again_if_b635aed7)) },
                                onClick = {
                                    batchVisionTargetMenu = false
                                    actions.onSelectBatchVisionTarget(null)
                                },
                            )
                        }
                    }
                    if (selectedTargetMissing) {
                        Text(
                            stringResource(R.string.ui_the_previously_selected_vision_target_is_d5cebe48),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(stringResource(R.string.ui_no_image_capable_vision_model_is_8de44dfe))
                }
                Text(stringResource(R.string.ui_send_only_this_batch_s_required_048311f0))
                reviewedReuse?.let { ReuseSummaryText(it, zh) }
                if (pending.selectedVisionTargetFingerprint != null && reviewedReuse == null) {
                    Text(stringResource(R.string.ui_loading_the_local_processing_scope_before_67a9f94f))
                }
                if (needsUnknownConfirmation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledgeDuplicateCharge, onCheckedChange = { acknowledgeDuplicateCharge = it })
                        Text(stringResource(R.string.ui_i_approve_retrying_unknown_previous_requests_4beb1ab2))
                    }
                }
                if (pending.visionTargetSelectionInitialized && pending.selectedVisionTargetFingerprint == null) Text(stringResource(R.string.ui_choose_an_image_capable_model_before_c613cda6))
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
            dismissButton = { TextButton(onClick = actions.onDismissBatchVision) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
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
        StorageCard(state, actions, zh)
        if (showPageTitle) {
            Text(stringResource(R.string.ui_knowledge_b09e4bdc), style = MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreateBase) { Text(stringResource(R.string.ui_new_5a5d1d13)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val buttonModifier = Modifier.weight(1f).heightIn(min = 48.dp)
            val buttonPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            OutlinedButton(onClick = onImport, modifier = buttonModifier, contentPadding = buttonPadding) {
                Text(stringResource(R.string.ui_add_files_bcabb786), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
            OutlinedButton(onClick = onImportFolder, modifier = buttonModifier, contentPadding = buttonPadding) {
                Text(stringResource(R.string.ui_import_folder_d7d0aeb7), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
            OutlinedButton(onClick = onImportZip, modifier = buttonModifier, contentPadding = buttonPadding) {
                Text(stringResource(R.string.ui_import_zip_5773854e), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
        }
        Text(stringResource(R.string.ui_imported_files_stay_in_the_app_5b09be2a), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        if (state.embeddingSpaceLabel.isNotBlank()) {
            Text(
                stringResource(R.string.ui_current_embedding_space_s_c1bea4c9, (state.embeddingSpaceLabel)),
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
        else if (state.bases.isEmpty()) Text(stringResource(R.string.ui_no_knowledge_bases_available_af940d79), modifier = Modifier.padding(top = 16.dp))
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
            state.bases.forEach { base ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, if (base.id == state.selectedBaseId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable { actions.onSelectBase(base.id) },
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(base.name, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (base.status.isNotBlank()) Text(base.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                            Text(base.documentCount.toString(), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.ui_documents_2e4d6744), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp))
                        }
                    }
                }
            }
        }
        val selectedBase = state.selectedBaseId
        if (selectedBase != null) {
            OutlinedButton(onClick = { onDeleteBase(selectedBase) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.ui_delete_selected_base_b065bddc))
            }
            OutlinedButton(onClick = onConfigureEmbedding, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.ui_configure_api_embedding_6fc9442f))
            }
        }
    }
}

@Composable
private fun StorageCard(state: KnowledgeUiState, actions: KnowledgeActions, zh: Boolean) {
    if (state.storageQuotaBytes <= 0) return
    var editing by rememberSaveable { mutableStateOf(false) }
    var quotaGiB by rememberSaveable { mutableStateOf("2") }
    val overQuota = state.storageUsedBytes > state.storageQuotaBytes
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ui_local_storage_s_s_e1990a9a, (storageSize(state.storageUsedBytes)), (storageSize(state.storageQuotaBytes))))
            Text(stringResource(R.string.ui_knowledge_content_database_and_search_indexes_63fb4efb), style = MaterialTheme.typography.bodySmall)
            if (overQuota) Text(stringResource(R.string.ui_over_the_limit_reading_deletion_and_f364d9a1), color = MaterialTheme.colorScheme.error)
            if (state.foreignKeyIssueCount > 0) Text(stringResource(R.string.ui_s_legacy_database_relation_issues_detected_3fd8bd78, (state.foreignKeyIssueCount)), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.onCollectStorage, modifier = Modifier.weight(1f), enabled = !state.loading) { Text(stringResource(R.string.ui_reclaim_deleted_content_8ca77c69)) }
                TextButton(onClick = { quotaGiB = ((state.storageQuotaBytes + (1L shl 30) - 1) / (1L shl 30)).toString(); editing = true }, modifier = Modifier.weight(1f), enabled = !state.loading) { Text(stringResource(R.string.ui_storage_budget_3a90a935)) }
            }
        }
    }
    if (editing) {
        val amount = quotaGiB.toLongOrNull()?.takeIf { it in 1..64 }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.ui_local_storage_budget_dc31eacc)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = quotaGiB, onValueChange = { quotaGiB = it }, label = { Text("GiB (1–64)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = amount == null)
                Text(stringResource(R.string.ui_changing_the_limit_does_not_delete_f595fe9f))
            } },
            confirmButton = { TextButton(onClick = { amount?.let { actions.onConfigureStorageQuota(it * (1L shl 30)); editing = false } }, enabled = amount != null) { Text(stringResource(R.string.ui_save_ec8e6d58)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.ui_cancel_998b9c48)) } })
    }
}

private fun storageSize(bytes: Long): String = java.lang.String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))

@Composable
private fun KnowledgeContentPane(state: KnowledgeUiState, actions: KnowledgeActions, zh: Boolean, onDelete: (String) -> Unit, onRebuild: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        if (state.status.isNotBlank()) StatusCard(state.status)
        if (state.waiting.isNotEmpty()) {
            val scheme = MaterialTheme.colorScheme
            val dark = scheme.background == Color(0xFF111827)
            val warningBackground = when {
                dark -> Color(0xFF45310A)
                scheme.background == Color(0xFFF2F9FD) -> Color(0xFFFEF3C7)
                else -> Color(0xFFFEF08A)
            }
            val warningInk = when {
                dark -> Color(0xFFFACA15)
                scheme.background == Color(0xFFF2F9FD) -> Color(0xFFB45309)
                else -> Color(0xFFA34808)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = warningBackground),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.ui_s_items_await_vision_processing_and_e80d8658, (state.waiting.size)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = warningInk,
                    )
                    OutlinedButton(onClick = actions.onConfigureVision) {
                        Text(stringResource(R.string.ui_configure_vision_model_53a303ac))
                    }
                }
            }
        }
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
                stringResource(R.string.ui_queries_with_unknown_results_3c16b575),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            selectedQueryAttempts.forEach { attempt -> QueryRetryCard(attempt, actions, zh) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ui_documents_5dac12b8), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRebuild, enabled = state.rebuildEnabled && !knowledgeImportActive(state)) {
                Text(stringResource(R.string.ui_rebuild_index_5df89300))
            }
        }
        if (state.batches.isEmpty() && (knowledgeImportActive(state) || state.jobs.isNotEmpty() || state.loading)) {
            Text(knowledgeImportSummary(state, zh), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.documents.isEmpty() && !knowledgeImportActive(state) && !state.loading && state.jobs.isEmpty()) {
            Text(stringResource(R.string.ui_no_documents_in_this_knowledge_base_cd58f8fa), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
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
            Text(stringResource(R.string.ui_import_jobs_bddacf29), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
            legacyJobs.forEach { job -> JobCard(job, actions, zh) }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.ui_images_without_an_explicitly_configured_visio_49eb4d07), style = MaterialTheme.typography.bodySmall)
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
                stringResource(R.string.ui_query_result_is_unknown_3f635e8b),
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
                    stringResource(R.string.ui_authorized_waiting_for_you_to_resubmit_98c15625),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    stringResource(R.string.ui_resubmitting_may_incur_a_duplicate_charge_b91a048e),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { actions.onAuthorizeQueryRetry(attempt.spaceId, attempt.queryHash) },
                    enabled = attempt.spaceId.isNotBlank() && attempt.queryHash.isNotBlank(),
                ) {
                    Text(stringResource(R.string.ui_allow_resubmitting_this_query_b31e35ec))
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
        title = { Text(stringResource(R.string.ui_review_resume_scope_0e07c6e4)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReuseSummaryText(batch.reuse, zh)
            Text(stringResource(R.string.ui_successful_units_are_retained_unknown_is_5c913918))
        } },
        confirmButton = { Button(onClick = { confirmResume = false; actions.onResumeBatch(batch.id) }) {
            Text(stringResource(R.string.ui_confirm_resume_2bdd89bf))
        } },
        dismissButton = { TextButton(onClick = { confirmResume = false }) { Text(stringResource(R.string.ui_back_5db5cac5)) } },
    )
    if (editPolicy) PipelinePolicyDialog(batch, zh, onDismiss = { editPolicy = false }) { policy ->
        editPolicy = false
        actions.onConfigurePipeline(batch.id, policy)
    }
    val blocked = batch.blockedReason != null || batch.state.equals("BLOCKED", true)
    val paused = batch.paused || batch.state.equals("PAUSED", true)
    val automaticRetryPending = batch.state.equals("PROCESSING", true) && batch.items.any { item ->
        item.state.uppercase() in setOf("QUEUED", "PROCESSING") &&
            jobs.any { job -> job.id == item.jobId && job.stage.equals("FAILED", true) }
    }
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
                    automaticRetryPending -> if (zh) "等待自动重试" else "Waiting for automatic retry"
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
            val unknownReason = if (automaticRetryPending) null else batch.items.asSequence().mapNotNull { it.error }
                .firstOrNull { it.contains("UNKNOWN_OUTCOME") }?.take(300)
            if (unknownReason != null) {
                Text(
                    stringResource(R.string.ui_unknown_reason_s_2b4c1b9d, (unknownReason)),
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
                        Text(stringResource(R.string.ui_import_the_images_separately_or_explicitly_74c7ec47))
                        TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.ui_review_affected_items_a6b05303)) }
                    }
                    blocked -> {
                        Button(onClick = { actions.onAuthorizeBatchVision(batch.id, "", false) }, enabled = !busy) {
                            Text(stringResource(R.string.ui_configure_and_continue_02fb6cd4))
                        }
                        OutlinedButton(onClick = { actions.onConfigureVision() }) {
                            Text(stringResource(R.string.ui_configure_vision_model_53a303ac))
                        }
                    }
                    paused || batch.resumeStagingAvailable -> Button(onClick = { confirmResume = true }, enabled = !busy) {
                        Text(stringResource(R.string.ui_resume_import_39aa59bf))
                    }
                    batch.state.uppercase() !in setOf("COMPLETED", "CANCELLED", "FAILED") ->
                        OutlinedButton(onClick = { actions.onPauseBatch(batch.id) }) {
                            Text(stringResource(R.string.ui_pause_c65f066c))
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
                    Text(stringResource(R.string.ui_rebuild_retrieval_chunks_locally_6fd08124))
                }
            }
            Text(stringResource(R.string.ui_concurrency_s_stop_after_s_consecutive_3727c191, (batch.policy.maxConcurrency), (batch.policy.consecutiveFailureLimit)))
            TextButton(onClick = { editPolicy = true }, enabled = !busy) {
                Text(stringResource(R.string.ui_processing_limits_2d068b3e))
            }
            TextButton(onClick = { actions.onAuthorizeBatchVision(batch.id, "", false) }, enabled = !busy) {
                Text(stringResource(R.string.ui_review_vision_configuration_change_b1fbb5dc))
            }
            Text(
                if (zh) {
                    "已复制 ${batch.copied} / ${batch.totalItems} · 待复制 ${batch.pending} · 处理中 ${batch.processing} · 等待 ${batch.waiting} · 失败 ${batch.failed}" +
                        if (batch.unknown > 0 && !automaticRetryPending) " · 待确认 ${batch.unknown}" else ""
                } else {
                    "Copied ${batch.copied} / ${batch.totalItems} · pending ${batch.pending} · processing ${batch.processing} · waiting ${batch.waiting} · failed ${batch.failed}" +
                        if (batch.unknown > 0 && !automaticRetryPending) " · unknown ${batch.unknown}" else ""
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            batch.visionTarget?.let { fingerprint ->
                Text(
                    stringResource(R.string.ui_batch_vision_target_s_19e3378a, (batch.visionTargetLabel ?: fingerprint)),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    batch.items.forEach { item ->
                        val job = jobs.firstOrNull { it.id == item.jobId }
                        if (job != null) {
                            val automaticRetry = job.stage.equals("FAILED", true) &&
                                batch.state.uppercase() in setOf("PROCESSING", "PAUSED") &&
                                item.state.uppercase() in setOf("QUEUED", "PROCESSING")
                            JobCard(job.copy(
                                stage = if (automaticRetry) "RETRY_WAIT" else job.stage,
                                error = if (automaticRetry) null else job.error,
                                unknownOutcome = job.unknownOutcome && !automaticRetry,
                                requiresVisionConsent = false,
                            ), actions, zh)
                            if (batch.blockedReason == "MISSING_VISUAL_SOURCE" && job.stage == "WAITING_FOR_VISION_MODEL") {
                                OutlinedButton(onClick = { actions.onTextOnly(job.id) }, enabled = !busy) {
                                    Text(stringResource(R.string.ui_continue_with_text_only_visual_gaps_f25cac9f))
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
    Text(stringResource(R.string.ui_pages_s_processing_units_s_89ee0550, (progress.pages), (progress.units)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.ui_pending_s_in_flight_s_succeeded_5b14923c, (progress.pending), (progress.inFlight), (progress.succeeded), (progress.failed), (progress.unknown), (progress.published)), style = MaterialTheme.typography.bodySmall)
    val usage = progress.usage
    Text(if (zh) "Provider token：输入 ${usage.inputTokens ?: "未知"} · 输出 ${usage.outputTokens ?: "未知"} · reasoning ${usage.reasoningTokens ?: "未知"}（包含在输出中）"
        else "Provider tokens: input ${usage.inputTokens ?: "unknown"} · output ${usage.outputTokens ?: "unknown"} · reasoning ${usage.reasoningTokens ?: "unknown"} (included in output)", style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.ui_unknown_usage_attempts_s_safety_reservation_cbab5631, (usage.unknownUsageAttempts), (usage.reservedTokens)), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReuseSummaryText(summary: PipelineReuseSummary, zh: Boolean) {
    Text(stringResource(R.string.ui_reuse_s_local_rebuild_s_new_361f961a, (summary.directReuse), (summary.localRebuild), (summary.newRequests), (summary.unknown)), style = MaterialTheme.typography.bodySmall)
    if (summary.unplannedFiles > 0) Text(stringResource(R.string.ui_unplanned_legacy_files_s_request_count_25814151, (summary.unplannedFiles)), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PipelinePolicyDialog(batch: KnowledgeBatchUi, zh: Boolean, onDismiss: () -> Unit, onSave: (PipelinePolicy) -> Unit) {
    var concurrency by remember { mutableStateOf(batch.policy.maxConcurrency.toString()) }
    var failures by remember { mutableStateOf(batch.policy.consecutiveFailureLimit.toString()) }
    var ceiling by remember { mutableStateOf(batch.policy.tokenDispatchCeiling?.toString().orEmpty()) }
    var reservation by remember { mutableStateOf(batch.policy.reservationTokensPerRequest?.toString().orEmpty()) }
    val valid = concurrency.toIntOrNull()?.let { it in 1..6 } == true &&
        failures.toIntOrNull()?.let { it > 0 } == true &&
        (ceiling.isBlank() || ceiling.toLongOrNull()?.let { it > 0 } == true) &&
        (reservation.isBlank() || reservation.toLongOrNull()?.let { it > 0 } == true) &&
        (ceiling.isBlank() || reservation.isNotBlank())
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_processing_limits_2d068b3e)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_limits_stop_new_dispatch_only_unknown_a341ab59))
            OutlinedTextField(concurrency, { concurrency = it }, label = { Text(stringResource(R.string.ui_maximum_concurrency_4fb6c774)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(failures, { failures = it }, label = { Text(stringResource(R.string.ui_consecutive_failure_limit_62eb3e74)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(ceiling, { ceiling = it }, label = { Text(stringResource(R.string.ui_token_dispatch_ceiling_optional_6672fd82)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(reservation, { reservation = it }, label = { Text(stringResource(R.string.ui_conservative_reservation_per_request_35cae466)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text(stringResource(R.string.ui_reservations_limit_subsequent_dispatch_they_a_1276c0e7))
        } },
        confirmButton = { Button(enabled = valid, onClick = { onSave(PipelinePolicy(maxConcurrency = concurrency.toInt(), consecutiveFailureLimit = failures.toInt(), tokenDispatchCeiling = ceiling.toLongOrNull(), reservationTokensPerRequest = reservation.toLongOrNull())) }) { Text(stringResource(R.string.ui_save_ec8e6d58)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel_998b9c48)) } })
}

@Composable
private fun ProgressRow(batch: KnowledgeBatchUi, percent: Int?, zh: Boolean) {
    if (batch.totalItems <= 0) {
        Text(
            stringResource(R.string.ui_the_batch_file_count_is_not_541e324f),
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
            stringResource(R.string.ui_finished_s_s_s_c204ae60, (batch.published), (batch.totalItems), (percent ?: 0)),
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
            Text(stringResource(R.string.ui_waiting_for_vision_model_0b90df35), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(waiting.displayName, modifier = Modifier.padding(top = 4.dp))
            Text(waiting.reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (waiting.authorizationTarget.isNotBlank()) Text(stringResource(R.string.ui_authorization_target_s_be9a7770, (waiting.authorizationTarget)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (waiting.canConfigureVision) OutlinedButton(onClick = actions.onConfigureVision) { Text(stringResource(R.string.ui_configure_vision_2d81db1a)) }
                Button(onClick = actions.onKeepWaiting) { Text(stringResource(R.string.ui_keep_waiting_6d98f03d)) }
                OutlinedButton(onClick = { actions.onTextOnly(waiting.jobId) }) { Text(stringResource(R.string.ui_use_text_only_61383e87)) }
            }
        }
    }
}

@Composable
private fun DocumentCard(document: KnowledgeDocumentUi, onDelete: (String) -> Unit, actions: KnowledgeActions, zh: Boolean) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(document.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                FilterChip(selected = document.status.equals("READY", true), onClick = {}, enabled = false, label = { Text(knowledgeStageLabel(document.status.ifBlank { "UNKNOWN" }, zh)) })
            }
            if (document.mimeType.isNotBlank()) Text(document.mimeType, style = MaterialTheme.typography.bodySmall)
            if (document.sizeLabel.isNotBlank() || document.updatedAt.isNotBlank()) Text(listOf(document.sizeLabel, document.updatedAt).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onOpenEvidence(document.id) }) { Text(stringResource(R.string.ui_view_evidence_e90fbed5)) }
                OutlinedButton(onClick = { onDelete(document.id) }) { Text(stringResource(R.string.ui_delete_5b875326)) }
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
                        Text(stringResource(R.string.ui_consent_to_api_embedding_53fe3597))
                    }
                }
                if (job.requiresVisionConsent) {
                    Button(onClick = { actions.onGrantVision(job.id) }) { Text(stringResource(R.string.ui_approve_vision_upload_99eb421f)) }
                }
                if (job.unknownOutcome && job.embeddingIsApi) {
                    OutlinedButton(onClick = { actions.onRetryEmbedding(job.id) }) {
                        Text(stringResource(R.string.ui_retry_embedding_may_charge_twice_3b32f4c3))
                    }
                } else if (job.unknownOutcome) {
                    OutlinedButton(onClick = { actions.onRetryVision(job.id) }) { Text(stringResource(R.string.ui_retry_vision_may_charge_twice_cca5f0a2)) }
                }
                if (job.stage !in setOf("READY", "FAILED", "CANCELLED")) {
                    TextButton(onClick = { actions.onCancelJob(job.id) }) { Text(stringResource(R.string.ui_cancel_job_7b34b54e)) }
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
        title = { Text(stringResource(R.string.knowledge_evidence_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(evidence.source)
                evidence.chunkCount?.let { Text(stringResource(R.string.knowledge_evidence_chunks, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (evidence.contentHash.isNotBlank()) Text(stringResource(R.string.knowledge_evidence_hash, evidence.contentHash), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                evidence.verified?.let { Text(stringResource(if (it) R.string.knowledge_evidence_verified else R.string.knowledge_evidence_unverified), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (evidence.details.isNotBlank()) Text(evidence.details, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(stringResource(R.string.knowledge_evidence_close)) } },
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
