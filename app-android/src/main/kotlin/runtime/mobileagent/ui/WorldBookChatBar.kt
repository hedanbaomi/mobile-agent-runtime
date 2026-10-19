// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.AppContainer
import runtime.mobileagent.domain.worldbook.WorldBookConversationConfig
import runtime.mobileagent.domain.worldbook.WorldBookEvaluation
import runtime.mobileagent.feature.worldbooks.*
import runtime.mobileagent.worldBookCardUi
import runtime.mobileagent.worldBookPreviewUi

@Composable
internal fun WorldBookChatBar(container: AppContainer, conversationId: String?, streaming: Boolean,
    refreshTick: Int, chinese: Boolean, onManage: () -> Unit) {
    if (conversationId == null) return
    var config by remember(conversationId) { mutableStateOf<WorldBookConversationConfig?>(null) }
    var books by remember(conversationId) { mutableStateOf<List<WorldBookCardUi>>(emptyList()) }
    var preview by remember(conversationId) { mutableStateOf<WorldBookPreviewUi?>(null) }
    var bindingOpen by remember(conversationId) { mutableStateOf(false) }
    var receiptOpen by remember(conversationId) { mutableStateOf(false) }
    var error by remember(conversationId) { mutableStateOf<String?>(null) }
    var refresh by remember(conversationId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(conversationId, streaming, refreshTick, refresh, chinese) {
        try {
          val loaded = withContext(Dispatchers.IO) {
            val selected = container.worldBooks.conversationConfig(conversationId)
            val inventory = container.worldBooks.list().map(::worldBookCardUi)
            val receipt = container.worldBooks.latestReceipt(conversationId)?.let {
                worldBookPreviewUi(Json.decodeFromString<WorldBookEvaluation>(it.evaluationJson), chinese)
            }
            Triple(selected, inventory, receipt)
        }
          config = loaded.first; books = loaded.second; preview = loaded.third
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            config = null; preview = null
            error = if (chinese) "世界书记录暂时无法读取，请重新打开对话。" else "World book records could not be loaded. Reopen the chat."
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { bindingOpen = true }, enabled = config != null && !streaming,
            modifier = Modifier.testTag("worldbook-chat-bind")) {
            Text(if (chinese) "世界书 ${config?.bookIds?.size ?: 0}" else "World books ${config?.bookIds?.size ?: 0}")
        }
        TextButton(onClick = { receiptOpen = true }, enabled = preview != null, modifier = Modifier.testTag("worldbook-chat-receipt")) {
            Text(if (chinese) "本轮使用情况" else "Run details")
        }
        TextButton(onClick = onManage, modifier = Modifier.testTag("worldbook-chat-manage")) {
            Text(if (chinese) "管理" else "Manage")
        }
    }
    if (bindingOpen) config?.let { current ->
        val missing = current.bookIds.filter { id -> books.none { it.id == id } }.map {
            WorldBookCardUi(it, if (chinese) "已移除的世界书" else "Removed world book", 0, false, 0)
        }
        WorldBookBindingDialog(books + missing, current.bookIds.toSet(), onConfirm = { ids ->
            bindingOpen = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { container.worldBooks.saveConversationConfig(current.copy(bookIds = ids.toList()), current.revision) }
                    refresh++
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = if (chinese) "绑定未保存。请移除已删除的世界书并重新选择。" else "Bindings were not saved. Remove unavailable books and try again." }
            }
        }, onDismiss = { bindingOpen = false }, language = if (chinese) "zh-CN" else "en-US")
    }
    if (receiptOpen) preview?.let { receipt ->
        AlertDialog(onDismissRequest = { receiptOpen = false }, title = { Text(if (chinese) "世界书使用记录" else "World book run details") },
            text = { Box(Modifier.heightIn(max = 440.dp)) { WorldBookRunPanel(receipt, if (chinese) "zh-CN" else "en-US") } },
            confirmButton = { TextButton(onClick = { receiptOpen = false }) { Text(if (chinese) "关闭" else "Close") } })
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text(if (chinese) "关闭" else "Close") } }) }
}
