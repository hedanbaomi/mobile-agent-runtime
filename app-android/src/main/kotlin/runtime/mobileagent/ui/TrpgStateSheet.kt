// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import runtime.mobileagent.domain.trpg.TrpgRuleset
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgStateValidation

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrpgStateSheetDialog(
    session: TrpgSession,
    writable: Boolean,
    saving: Boolean,
    conflictMessage: String?,
    chinese: Boolean,
    onSave: (Long, TrpgState) -> Unit,
    onReloadLatest: suspend () -> TrpgSession,
    onDismiss: () -> Unit,
) {
    // A refresh or a rejected CAS must not silently replace the user's draft.
    // Only an explicit successful Reload replaces it while this dialog is open.
    var expectedRevision by remember(session.conversationId) { mutableLongStateOf(session.revision) }
    var draft by remember(session.conversationId) { mutableStateOf(session.state) }
    var reloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var newResName by remember { mutableStateOf("") }
    var newResCur by remember { mutableStateOf("10") }
    var newResMax by remember { mutableStateOf("10") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trpg.sheet.dialog"),
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (chinese) "📜 跑团角色卡与状态板" else "📜 TRPG Sheet & Campaign State",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Rev #$expectedRevision",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("trpg.sheet.revision"),
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                conflictMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().testTag("trpg.sheet.conflict"),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    reloading = true
                                    scope.launch {
                                        try {
                                            val latest = onReloadLatest()
                                            require(latest.conversationId == session.conversationId)
                                            expectedRevision = latest.revision
                                            draft = latest.state
                                            localError = null
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (error: Exception) {
                                            localError = error.message ?: "Unable to reload state"
                                        } finally {
                                            reloading = false
                                        }
                                    }
                                },
                                enabled = !saving && !reloading,
                                modifier = Modifier.testTag("trpg.sheet.reload"),
                            ) {
                                Text(if (chinese) "载入最新" else "Reload")
                            }
                        }
                    }
                }
                localError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("trpg.sheet.error"))
                }
                // Keep rejection feedback and Reload visible while long forms scroll.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            label = { Text(if (chinese) "概览与资源" else "Overview") },
                            modifier = Modifier.testTag("trpg.sheet.tab.overview"),
                        )
                        FilterChip(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            label = { Text(if (chinese) "角色卡" else "Sheet") },
                            modifier = Modifier.testTag("trpg.sheet.tab.sheet"),
                        )
                        FilterChip(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            label = { Text(if (chinese) "场景与笔记" else "Notes") },
                            modifier = Modifier.testTag("trpg.sheet.tab.notes"),
                        )
                    }

                    when (tab) {
                        0 -> TrpgOverviewTab(
                            draft = draft,
                            writable = writable && !saving && !reloading,
                            chinese = chinese,
                            newResName = newResName,
                            newResCur = newResCur,
                            newResMax = newResMax,
                            onDraftChange = { draft = it; localError = null },
                            onNewResChange = { n, c, m -> newResName = n; newResCur = c; newResMax = m },
                            onError = { localError = it },
                        )
                        1 -> TrpgCharacterSheetTab(
                            draft = draft,
                            writable = writable && !saving && !reloading,
                            chinese = chinese,
                            onDraftChange = { draft = it; localError = null },
                        )
                        else -> TrpgNotesTab(
                            draft = draft,
                            writable = writable && !saving && !reloading,
                            chinese = chinese,
                            onDraftChange = { draft = it; localError = null },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching { TrpgStateValidation.validate(draft) }
                        .onSuccess {
                            localError = null
                            onSave(expectedRevision, draft)
                        }
                        .onFailure { localError = it.message }
                },
                enabled = writable && !saving && !reloading,
                modifier = Modifier.testTag("trpg.sheet.save"),
            ) {
                Text(if (chinese) "保存状态" else "Save State")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("trpg.sheet.close")) {
                Text(if (chinese) "关闭" else "Close")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrpgOverviewTab(
    draft: TrpgState,
    writable: Boolean,
    chinese: Boolean,
    newResName: String,
    newResCur: String,
    newResMax: String,
    onDraftChange: (TrpgState) -> Unit,
    onNewResChange: (String, String, String) -> Unit,
    onError: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val resourceKeyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TrpgRuleset.entries.forEach { ruleset ->
            FilterChip(
                selected = draft.ruleset == ruleset,
                onClick = { onDraftChange(draft.copy(ruleset = ruleset)) },
                enabled = writable,
                label = { Text(trpgRulesetTitle(ruleset, chinese)) },
                modifier = Modifier.testTag("trpg.sheet.ruleset.${ruleset.name}"),
            )
        }
    }
    Text(
        text = if (chinese) {
            "具体版本与自设规则由智能体关联的世界书 (WorldBook) 决定。下方提供通用数值框架可选库（按需点击加入，不要求用满每一项），你与 LLM 工具也可随时自定义新数值栏目。"
        } else {
            "Edition or homebrew rules come from attached WorldBooks. Pick only the needed fields from the universal framework below, or let you/LLM add custom numeric columns."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft.playerName,
        onValueChange = { onDraftChange(draft.copy(playerName = it.take(128))) },
        enabled = writable,
        singleLine = true,
        label = { Text(if (chinese) "冒险者 / 调查员名称 (≤128)" else "Player / Character Name (≤128)") },
        modifier = Modifier.fillMaxWidth().testTag("trpg.sheet.player_name"),
    )
    OutlinedTextField(
        value = draft.scene,
        onValueChange = { onDraftChange(draft.copy(scene = it.take(4096))) },
        enabled = writable,
        label = { Text(if (chinese) "当前场景 / 地点" else "Active Scene / Location") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().testTag("trpg.sheet.scene"),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (chinese) "通用框架可选库与自定义数值 (${draft.resources.size}/32)" else "Universal Framework & Custom Stats (${draft.resources.size}/32)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(
            onClick = {
                runCatching { trpgStarterState(draft.ruleset, draft, chinese) }
                    .onSuccess(onDraftChange).onFailure { onError(it.message ?: "Unable to add core fields") }
            },
            enabled = writable,
            modifier = Modifier.testTag("trpg.sheet.apply_framework_core"),
        ) {
            Text(if (chinese) "补入缺失核心项" else "Add Missing Core Fields")
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        trpgFrameworkCatalog(draft.ruleset, chinese).forEach { preset ->
            FilterChip(
                selected = false,
                onClick = {
                    if (draft.resources.none { it.name == preset.name } && draft.resources.size < 32) {
                        onDraftChange(draft.copy(resources = draft.resources + preset))
                    }
                },
                enabled = writable && draft.resources.none { it.name == preset.name } && draft.resources.size < 32,
                label = { Text("+ ${preset.name}") },
                modifier = Modifier.testTag("trpg.sheet.quick_res.${preset.name}"),
            )
        }
    }
    draft.resources.forEachIndexed { idx, res ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(res.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val updated = draft.resources.toMutableList()
                        updated[idx] = adjustTrpgResource(res, -1)
                        onDraftChange(draft.copy(resources = updated))
                    },
                    enabled = writable,
                ) { Text("-1") }
                Text(
                    text = "${res.current}${res.maximum?.let { "/$it" }.orEmpty()}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = {
                        val updated = draft.resources.toMutableList()
                        updated[idx] = adjustTrpgResource(res, 1)
                        onDraftChange(draft.copy(resources = updated))
                    },
                    enabled = writable,
                ) { Text("+1") }
                TextButton(
                    onClick = {
                        onDraftChange(draft.copy(resources = draft.resources.filterIndexed { i, _ -> i != idx }))
                    },
                    enabled = writable,
                    modifier = Modifier.testTag("trpg.sheet.res.remove.${res.name}"),
                ) { Text("✕") }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = newResName,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = resourceKeyboardActions,
            onValueChange = { onNewResChange(it.take(64), newResCur, newResMax) },
            enabled = writable,
            singleLine = true,
            label = { Text(if (chinese) "自定义数值名" else "Custom Stat") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.sheet.res.new_name"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newResCur,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = resourceKeyboardActions,
                onValueChange = { onNewResChange(newResName, it, newResMax) },
                enabled = writable,
                singleLine = true,
                label = { Text(if (chinese) "当前" else "Cur") },
                modifier = Modifier.weight(1f).testTag("trpg.sheet.res.new_cur"),
            )
            OutlinedTextField(
                value = newResMax,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = resourceKeyboardActions,
                onValueChange = { onNewResChange(newResName, newResCur, it) },
                enabled = writable,
                singleLine = true,
                label = { Text(if (chinese) "上限(可空)" else "Max(opt)") },
                modifier = Modifier.weight(1f).testTag("trpg.sheet.res.new_max"),
            )
            OutlinedButton(
                onClick = {
                    runCatching { addTrpgResource(draft, newResName, newResCur, newResMax) }
                        .onSuccess { next ->
                            onDraftChange(next)
                            onNewResChange("", "10", "10")
                        }.onFailure {
                            onError(if (chinese) "请填写唯一的资源名与范围内整数；上限只能留空或填写有效整数。" else
                                "Use a unique name and in-range integers; maximum must be blank or a valid integer.")
                        }
                },
                enabled = writable && draft.resources.size < 32,
                modifier = Modifier.testTag("trpg.sheet.res.add"),
            ) { Text("+") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrpgCharacterSheetTab(
    draft: TrpgState,
    writable: Boolean,
    chinese: Boolean,
    onDraftChange: (TrpgState) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                val tpl = trpgSheetTemplate(TrpgRuleset.DND_5E, chinese)
                val joined = if (draft.characterSheet.isBlank()) tpl else "${draft.characterSheet}\n$tpl"
                onDraftChange(draft.copy(characterSheet = joined.take(8192)))
            },
            enabled = writable,
            modifier = Modifier.testTag("trpg.sheet.tpl.dnd"),
        ) { Text(if (chinese) "+ D&D 通用框架模板" else "+ D&D Universal Template") }
        OutlinedButton(
            onClick = {
                val tpl = trpgSheetTemplate(TrpgRuleset.COC_7, chinese)
                val joined = if (draft.characterSheet.isBlank()) tpl else "${draft.characterSheet}\n$tpl"
                onDraftChange(draft.copy(characterSheet = joined.take(8192)))
            },
            enabled = writable,
            modifier = Modifier.testTag("trpg.sheet.tpl.coc"),
        ) { Text(if (chinese) "+ CoC 通用框架模板" else "+ CoC Universal Template") }
        OutlinedButton(
            onClick = {
                val tpl = trpgSheetTemplate(TrpgRuleset.CUSTOM, chinese)
                val joined = if (draft.characterSheet.isBlank()) tpl else "${draft.characterSheet}\n$tpl"
                onDraftChange(draft.copy(characterSheet = joined.take(8192)))
            },
            enabled = writable,
            modifier = Modifier.testTag("trpg.sheet.tpl.custom"),
        ) { Text(if (chinese) "+ 世界书自定义模板" else "+ Lorebook Template") }
    }
    OutlinedTextField(
        value = draft.characterSheet,
        onValueChange = { onDraftChange(draft.copy(characterSheet = it.take(8192))) },
        enabled = writable,
        label = { Text(if (chinese) "角色卡详情 (${draft.characterSheet.length}/8192)" else "Character Sheet (${draft.characterSheet.length}/8192)") },
        minLines = 7,
        maxLines = 14,
        modifier = Modifier.fillMaxWidth().testTag("trpg.sheet.character_sheet"),
    )
}

@Composable
private fun TrpgNotesTab(
    draft: TrpgState,
    writable: Boolean,
    chinese: Boolean,
    onDraftChange: (TrpgState) -> Unit,
) {
    OutlinedTextField(
        value = draft.notes,
        onValueChange = { onDraftChange(draft.copy(notes = it.take(8192))) },
        enabled = writable,
        label = { Text(if (chinese) "线索、物品与跑团笔记 (${draft.notes.length}/8192)" else "Clues, Inventory & Notes (${draft.notes.length}/8192)") },
        minLines = 8,
        maxLines = 16,
        modifier = Modifier.fillMaxWidth().testTag("trpg.sheet.notes"),
    )
}
