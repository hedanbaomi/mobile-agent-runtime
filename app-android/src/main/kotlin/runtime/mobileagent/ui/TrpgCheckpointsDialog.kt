// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import runtime.mobileagent.domain.trpg.TrpgCheckpoint
import runtime.mobileagent.domain.trpg.TrpgSession

@Composable
internal fun TrpgCheckpointsDialog(
    session: TrpgSession, checkpoints: List<TrpgCheckpoint>, writable: Boolean, canBranch: Boolean,
    saving: Boolean, error: String?, chinese: Boolean,
    onCreate: suspend (Long, String, String) -> TrpgCheckpoint,
    onBranch: suspend (String, String, String) -> String,
    onOpenConversation: (String) -> Unit, onDismiss: () -> Unit,
) {
    var busy by remember(session.conversationId) { mutableStateOf(false) }
    var localError by remember(session.conversationId) { mutableStateOf<String?>(null) }
    var create by remember(session.conversationId) { mutableStateOf<Pair<String, Long>?>(null) }
    var branch by remember(session.conversationId) { mutableStateOf<Pair<TrpgCheckpoint, String>?>(null) }
    val blocked = busy || saving
    AlertDialog(onDismissRequest = { if (!blocked) onDismiss() },
        title = { Text(if (chinese) "检查点与分支" else "Checkpoints and branches") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (chinese) "检查点保存当前状态与消息边界。从检查点继续会新建对话，保留原对话；旧工具不会重新执行。"
                else "A checkpoint captures state and the transcript boundary. Continue in a new conversation while keeping the original. Historical tools are never executed again.")
            (error ?: localError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { create = UUID.randomUUID().toString() to session.revision },
                enabled = writable && !blocked && checkpoints.size < 32,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.checkpoint.create")) {
                Text(if (chinese) "保存检查点" else "Save checkpoint")
            }
            if (checkpoints.isEmpty()) Text(if (chinese) "尚无检查点" else "No checkpoints yet")
            checkpoints.asReversed().forEach { point ->
                Text(point.name, style = MaterialTheme.typography.titleSmall)
                Text("#${point.revision} · ${point.createdAt}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { branch = point to UUID.randomUUID().toString() }, enabled = canBranch && !blocked,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.checkpoint.branch.${point.checkpointId}")) {
                    Text(if (chinese) "从这里继续（新分支）" else "Continue from here (new branch)")
                }
                HorizontalDivider()
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !blocked, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.checkpoint.close")) {
            Text(if (chinese) "关闭" else "Close")
        } })

    create?.let { (id, revision) ->
        CheckpointNameEditor(id, "", chinese, blocked, if (chinese) "检查点名称" else "Checkpoint name",
            stale = revision != session.revision, onReload = { create = id to session.revision }, onDismiss = { create = null },
            onSubmit = { name ->
                busy = true
                try {
                    onCreate(revision, name, id)
                    create = null; localError = null
                    true
                } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {
                    localError = if (chinese) "检查点未保存，请核对最新状态后重试。" else "Checkpoint failed. Review the current state and retry."
                    false
                } finally { busy = false }
            })
    }
    branch?.let { (point, id) ->
        CheckpointNameEditor(id, point.name, chinese, blocked, if (chinese) "新分支名称" else "New branch title",
            stale = false, onReload = {}, onDismiss = { branch = null }, onSubmit = { title ->
                busy = true
                try {
                    val conversationId = onBranch(point.checkpointId, title, id)
                    branch = null; localError = null
                    onDismiss(); onOpenConversation(conversationId)
                    true
                } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {
                    localError = if (chinese) "分支未创建；请核对检查点、图片和会话状态后重试。" else "Branch failed. Check the checkpoint, images and conversation state."
                    false
                } finally { busy = false }
            })
    }
}

@Composable
private fun CheckpointNameEditor(id: String, initial: String, chinese: Boolean, blocked: Boolean, title: String,
    stale: Boolean, onReload: () -> Unit, onDismiss: () -> Unit, onSubmit: suspend (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    var name by remember(id) { mutableStateOf(initial) }
    var failed by remember(id) { mutableStateOf(false) }
    var submitting by remember(id) { mutableStateOf(false) }
    val busy = blocked || submitting
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text(title) },
                modifier = Modifier.fillMaxWidth().testTag("trpg.checkpoint.name"))
            if (failed) Text(if (chinese) "操作未完成，名称已保留。" else "Action failed; the title was kept.", color = MaterialTheme.colorScheme.error)
            if (stale) {
                Text(if (chinese) "状态已更新。核对后可改用最新状态保存检查点。" else "State changed. Review it before capturing the new state.")
                TextButton(onClick = onReload, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (chinese) "采用最新状态" else "Use current state")
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { scope.launch {
            submitting = true
            try { failed = !onSubmit(name.trim()) } finally { submitting = false }
        } }, enabled = !busy && !stale && name.isNotBlank() && name.trim().length <= 128,
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.checkpoint.apply")) {
            Text(if (chinese) "保存" else "Save")
        }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (chinese) "取消" else "Cancel")
    } })
}
