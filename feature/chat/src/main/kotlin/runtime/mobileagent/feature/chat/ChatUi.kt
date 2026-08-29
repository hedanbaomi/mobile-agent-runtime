// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState

data class ChatSessionUi(
    val id: String,
    val title: String,
    val preview: String = "",
    val timeLabel: String = "",
    val unread: Boolean = false,
    val agentName: String = "",
)

data class ChatMessageUi(
    val id: String,
    val role: String,
    val text: String,
    val timeLabel: String = "",
    val citationIds: List<String> = emptyList(),
    val streaming: Boolean = false,
)

data class ChatCitationUi(
    val id: String,
    val title: String,
    val source: String,
    val excerpt: String,
    val location: String = "",
    val verified: Boolean = false,
    /** Validated evidence bytes supplied by the host; the UI never reads a path or secret. */
    val imageBytes: ByteArray? = null,
)

data class ChatToolApprovalUi(
    val id: String,
    val name: String,
    val summary: String,
    val confirmationRequired: Boolean = true,
    val externalEffect: Boolean = false,
)

data class ChatPromptLayerUi(val label: String, val text: String, val editable: Boolean = false)

data class ChatRequestPreviewUi(
    val method: String,
    val url: String,
    val headers: String = "",
    val body: String = "",
    val redacted: Boolean = true,
)

data class ChatAgentOptionUi(val id: String, val label: String)

enum class ToolApprovalChoice { APPROVE, REJECT }

data class ChatUiState(
    val sessions: List<ChatSessionUi> = emptyList(),
    val selectedSessionId: String? = null,
    val agents: List<ChatAgentOptionUi> = emptyList(),
    val selectedAgentId: String? = null,
    val messages: List<ChatMessageUi> = emptyList(),
    val input: String = "",
    val streaming: Boolean = false,
    val status: String = "",
    val statusKind: String = "",
    val textDegradation: Boolean = false,
    val citations: List<ChatCitationUi> = emptyList(),
    val selectedCitationId: String? = null,
    val pendingTool: ChatToolApprovalUi? = null,
    val promptLayers: List<ChatPromptLayerUi> = emptyList(),
    val requestPreview: ChatRequestPreviewUi? = null,
    val inspectorOpen: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** Optional host supplied empty-state copy; a blank value uses the session-aware default. */
    val emptyMessage: String = "",
    val language: String = "zh-CN",
)

data class ChatActions(
    val onInput: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onToggleDegradation: (Boolean) -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onSelectAgent: (String) -> Unit = {},
    val onOpenCitation: (String) -> Unit = {},
    val onCloseCitation: () -> Unit = {},
    val onToolApproval: (ToolApprovalChoice) -> Unit = {},
    val onOpenRequestInspector: () -> Unit = {},
    val onCloseRequestInspector: () -> Unit = {},
)

@Composable
fun ChatScreen(state: ChatUiState, actions: ChatActions = ChatActions(), modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                ChatSessionList(state, actions.onSelectSession, Modifier.width(240.dp))
                Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
                ChatConversationContent(state, actions, Modifier.weight(1f).fillMaxHeight().padding(16.dp).imePadding())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(12.dp).imePadding()) {
                SessionChooser(state, actions.onSelectSession)
                ChatConversationContent(state, actions, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    state.selectedCitationId?.let { id -> state.citations.firstOrNull { it.id == id } }?.let {
        CitationDialog(it, actions.onCloseCitation, state.language.equals("zh-CN", true))
    }
}

@Composable
private fun ChatConversationContent(state: ChatUiState, actions: ChatActions, modifier: Modifier) {
    Column(modifier) {
        ChatHeader(state, actions)
        if (state.status.isNotBlank()) StatusLine(state.status, state.statusKind)
        if (state.loading) {
            CenterState(if (state.language.equals("zh-CN", true)) "正在加载会话…" else "Loading conversations…", true, Modifier.weight(1f))
        } else if (state.error != null) {
            CenterState(state.error, false, Modifier.weight(1f))
        } else if (state.messages.isEmpty()) {
            CenterState(emptyConversationMessage(state, state.language.equals("zh-CN", true)), false, Modifier.weight(1f))
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(state.messages.size, state.streaming) {
                if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages, key = { it.id }) { MessageBubble(it, state.citations, actions.onOpenCitation, state.language.equals("zh-CN", true)) }
            }
        }
        state.pendingTool?.let { ApprovalCard(it, actions.onToolApproval, state.language.equals("zh-CN", true)) }
        Composer(state, actions)
    }
}

@Composable
private fun ChatSessionList(state: ChatUiState, onSelect: (String) -> Unit, modifier: Modifier) {
    val zh = state.language.equals("zh-CN", true)
    Column(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Text(if (zh) "会话" else "Conversations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (state.loading) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else if (state.sessions.isEmpty()) {
            Text(emptyConversationMessage(state, zh), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.sessions, key = { it.id }) { session ->
                    val selected = session.id == state.selectedSessionId
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(session.id) },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(session.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (session.unread) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
                            }
                            if (session.preview.isNotBlank()) Text(session.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (session.timeLabel.isNotBlank()) Text(session.timeLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionChooser(state: ChatUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val zh = state.language.equals("zh-CN", true)
    val selected = state.sessions.firstOrNull { it.id == state.selectedSessionId }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (zh) "会话" else "Conversations",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.widthIn(max = 220.dp),
            ) {
                Text(
                    selected?.title ?: if (zh) "选择会话" else "Choose conversation",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (state.sessions.isEmpty()) DropdownMenuItem(text = { Text(if (zh) "暂无会话" else "No conversations") }, onClick = { expanded = false }, enabled = false)
                else state.sessions.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                session.title,
                                modifier = Modifier.widthIn(max = 220.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = { expanded = false; onSelect(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ChatHeader(state: ChatUiState, actions: ChatActions) {
    val zh = state.language.equals("zh-CN", true)
    val session = state.sessions.firstOrNull { it.id == state.selectedSessionId }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                session?.title?.takeIf { it.isNotBlank() } ?: if (zh) "对话" else "Chat",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!session?.agentName.isNullOrBlank()) {
                Text(
                    session?.agentName.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!session?.preview.isNullOrBlank()) {
                Text(
                    session?.preview.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = actions.onNewSession,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text(if (zh) "新会话" else "New chat", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AgentChooser(state, actions.onSelectAgent)
            FilterChip(
                selected = state.textDegradation,
                onClick = { actions.onToggleDegradation(!state.textDegradation) },
                label = { Text(if (zh) "纯文本" else "Text only", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            if (state.requestPreview != null) TextButton(
                onClick = actions.onOpenRequestInspector,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text(if (zh) "查看请求" else "View request", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AgentChooser(state: ChatUiState, onSelect: (String) -> Unit) {
    val zh = state.language.equals("zh-CN", true)
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val selected = state.agents.firstOrNull { it.id == state.selectedAgentId }
    if (state.agents.isEmpty()) {
        Text(if (zh) "无智能体" else "No agent", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
    } else {
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.widthIn(max = 180.dp),
            ) {
                Text(
                    selected?.label ?: if (zh) "选择智能体" else "Choose agent",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.agents.forEach { agent ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                agent.label,
                                modifier = Modifier.widthIn(max = 180.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = { expanded = false; onSelect(agent.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: String, kind: String) {
    val color = when (kind.lowercase()) {
        "failed", "error" -> MaterialTheme.colorScheme.error
        "waiting" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(status, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MessageBubble(message: ChatMessageUi, citations: List<ChatCitationUi>, onCitation: (String) -> Unit, zh: Boolean) {
    val user = message.role.equals("user", ignoreCase = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(if (user) 0.86f else 0.94f),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(if (user) { if (zh) "你" else "You" } else message.role, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(message.text, Modifier.padding(top = 4.dp))
                if (message.streaming) Text(if (zh) "正在流式输出…" else "Streaming…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                if (message.timeLabel.isNotBlank()) Text(message.timeLabel, style = MaterialTheme.typography.labelSmall)
                val known = message.citationIds.mapNotNull { id -> citations.firstOrNull { it.id == id } }
                if (known.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    known.forEach { citation -> AssistChip(onClick = { onCitation(citation.id) }, label = { Text(if (zh) "来源：${citation.title}" else "Source: ${citation.title}") }) }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: ChatToolApprovalUi, onChoice: (ToolApprovalChoice) -> Unit, zh: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(if (zh) "需要确认" else "Confirmation required", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(approval.name, style = MaterialTheme.typography.labelLarge)
            Text(approval.summary, Modifier.padding(top = 4.dp))
            if (approval.externalEffect) Text(if (zh) "此请求可能离开设备。" else "This request may leave the device.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onChoice(ToolApprovalChoice.APPROVE) }) { Text(if (zh) "批准" else "Approve") }
                OutlinedButton(onClick = { onChoice(ToolApprovalChoice.REJECT) }) { Text(if (zh) "拒绝" else "Reject") }
            }
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, actions: ChatActions) {
    val zh = state.language.equals("zh-CN", true)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = state.input,
            onValueChange = actions.onInput,
            enabled = !state.streaming && state.pendingTool == null,
            label = { Text(if (zh) "消息" else "Message") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (state.streaming) OutlinedButton(onClick = actions.onCancel) { Text(if (zh) "取消" else "Cancel") }
        else Button(onClick = actions.onSend, enabled = state.input.isNotBlank() && state.pendingTool == null) { Text(if (zh) "发送" else "Send") }
    }
}

private fun emptyConversationMessage(state: ChatUiState, chinese: Boolean): String =
    state.emptyMessage.ifBlank {
        if (state.selectedSessionId != null) {
            if (chinese) "暂无消息，发送第一条消息。" else "No messages yet. Send the first message."
        } else {
            if (chinese) "新建会话以开始。" else "Create a conversation to start."
        }
    }

@Composable
private fun CenterState(label: String, loading: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun CitationDialog(citation: ChatCitationUi, onClose: () -> Unit, zh: Boolean) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(citation.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(citation.source, style = MaterialTheme.typography.labelLarge)
                if (citation.location.isNotBlank()) Text(citation.location, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                CitationImagePreview(citation, zh)
                Text(citation.excerpt, modifier = Modifier.padding(top = 12.dp))
                Text(if (citation.verified) { if (zh) "证据已验证" else "Verified evidence" } else { if (zh) "证据状态不可用" else "Evidence status unavailable" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") } },
    )
}

private const val MAX_CITATION_IMAGE_BYTES = 16 * 1024 * 1024
private const val MAX_CITATION_IMAGE_SOURCE_DIMENSION = 32_768
private const val MAX_CITATION_IMAGE_SOURCE_AREA = 268_435_456L
private const val MAX_CITATION_IMAGE_DIMENSION = 2_048

/**
 * Displays only bounded evidence supplied by the host. Bounds are inspected
 * before allocating pixels, and malformed or oversized data stays text-only.
 */
@Composable
private fun CitationImagePreview(citation: ChatCitationUi, zh: Boolean) {
    val bytes = citation.imageBytes ?: return
    if (bytes.isEmpty()) {
        Text(
            if (zh) "引用图片为空；仍显示元数据和摘录。" else "The citation image is empty; metadata and excerpt remain available.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        return
    }
    if (bytes.size > MAX_CITATION_IMAGE_BYTES) {
        Text(
            if (zh) "引用图片文件过大，已拒绝解码；仍显示元数据和摘录。" else "The citation image is too large and was not decoded; metadata and excerpt remain available.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        return
    }

    val bounds = remember(bytes) {
        runCatching {
            BitmapFactory.Options().also { options ->
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }.getOrNull()
    }
    val width = bounds?.outWidth ?: -1
    val height = bounds?.outHeight ?: -1
    val boundsAccepted = width > 0 && height > 0 &&
        width <= MAX_CITATION_IMAGE_SOURCE_DIMENSION &&
        height <= MAX_CITATION_IMAGE_SOURCE_DIMENSION &&
        width.toLong() * height.toLong() <= MAX_CITATION_IMAGE_SOURCE_AREA
    if (!boundsAccepted) {
        Text(
            if (zh) "引用图片尺寸无效或过大，已拒绝解码；仍显示元数据和摘录。" else "The citation image dimensions are invalid or too large and were not decoded; metadata and excerpt remain available.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        return
    }

    val sample = remember(width, height) { citationImageSample(width, height) }
    val bitmap = remember(bytes, sample) {
        runCatching {
            BitmapFactory.Options().also { options ->
                options.inSampleSize = sample
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
            }.let { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        }.getOrNull()?.takeIf {
            it.width in 1..MAX_CITATION_IMAGE_DIMENSION && it.height in 1..MAX_CITATION_IMAGE_DIMENSION
        }
    }
    if (bitmap == null) {
        Text(
            if (zh) "引用图片无法安全解码；仍显示元数据和摘录。" else "The citation image could not be decoded safely; metadata and excerpt remain available.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = if (zh) "引用图片：${citation.title}" else "Citation image: ${citation.title}",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .padding(top = 10.dp),
        )
    }
}

private fun citationImageSample(width: Int, height: Int): Int {
    var sample = 1
    while ((width + sample - 1) / sample > MAX_CITATION_IMAGE_DIMENSION ||
        (height + sample - 1) / sample > MAX_CITATION_IMAGE_DIMENSION
    ) {
        if (sample >= MAX_CITATION_IMAGE_DIMENSION) return MAX_CITATION_IMAGE_DIMENSION
        sample = (sample shl 1).coerceAtMost(MAX_CITATION_IMAGE_DIMENSION)
    }
    return sample
}

@Composable
fun RequestInspectorScreen(
    request: ChatRequestPreviewUi,
    layers: List<ChatPromptLayerUi>,
    onClose: () -> Unit,
    zh: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "请求检查器" else "Request inspector", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") }
        }
        Text("${request.method} ${request.url}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        if (request.redacted) {
            Text(
                if (zh) "API Key 和敏感请求头已遮盖；消息正文、提示词和知识内容仍会完整显示。"
                else "API keys and sensitive headers are redacted; message bodies, prompts, and knowledge still appear in full.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (request.headers.isNotBlank()) Text(request.headers, modifier = Modifier.padding(top = 10.dp))
        if (request.body.isNotBlank()) Text(request.body, modifier = Modifier.padding(top = 10.dp))
        if (layers.isNotEmpty()) {
            Text(if (zh) "提示词层" else "Prompt layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
            layers.forEach { layer ->
                Text(layer.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Text(layer.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RequestInspectorDialog(request: ChatRequestPreviewUi, layers: List<ChatPromptLayerUi>, onClose: () -> Unit, zh: Boolean) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (zh) "请求检查器" else "Request inspector") },
        text = {
            RequestInspectorScreen(request, layers, onClose, zh)
        },
        confirmButton = { Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") } },
    )
}
