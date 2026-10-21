// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import runtime.mobileagent.domain.trpg.TrpgActor
import runtime.mobileagent.domain.trpg.TrpgActorKind
import runtime.mobileagent.domain.trpg.TrpgCheckRequest
import runtime.mobileagent.domain.trpg.TrpgCommand
import runtime.mobileagent.domain.trpg.TrpgCondition
import runtime.mobileagent.domain.trpg.TrpgEncounter
import runtime.mobileagent.domain.trpg.TrpgInitiative
import runtime.mobileagent.domain.trpg.TrpgItem
import runtime.mobileagent.domain.trpg.TrpgOperation
import runtime.mobileagent.domain.trpg.TrpgParty
import runtime.mobileagent.domain.trpg.TrpgPartyCommand
import runtime.mobileagent.domain.trpg.TrpgPartyOperation
import runtime.mobileagent.domain.trpg.TrpgPartyValidation
import runtime.mobileagent.domain.trpg.TrpgRecovery
import runtime.mobileagent.domain.trpg.TrpgResource
import runtime.mobileagent.domain.trpg.TrpgResourceDelta
import runtime.mobileagent.domain.trpg.TrpgRest
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgSkill
import runtime.mobileagent.domain.trpg.TrpgViewRole
import runtime.mobileagent.domain.trpg.TrpgVisibility

@Composable
internal fun PartyActorEditor(
    initial: TrpgActor, chinese: Boolean, enabled: Boolean,
    revision: Long, reloadToken: Int, onReloadLatest: () -> Unit,
    onDismiss: () -> Unit, onSubmit: suspend (TrpgActor) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id, reloadToken) { mutableStateOf(false) }
    var actor by remember(initial.id, reloadToken) { mutableStateOf(initial) }
    var fields by remember(initial.id, reloadToken) { mutableStateOf(initial.fields.entries.map { it.key to it.value }) }
    var skills by remember(initial.id, reloadToken) { mutableStateOf(initial.skills) }
    var recoveries by remember(initial.id, reloadToken) {
        mutableStateOf(initial.resources.map { resource -> initial.recovery.find { it.resource == resource.name } ?: TrpgRecovery(resource.name) })
    }
    // Numeric edits stay as typed text until submit so "-", "" and partial input are not rewritten.
    var resCurrent by remember(initial.id, reloadToken) { mutableStateOf(initial.resources.map { it.current.toString() }) }
    var resMax by remember(initial.id, reloadToken) { mutableStateOf(initial.resources.map { it.maximum?.toString().orEmpty() }) }
    var recShort by remember(initial.id, reloadToken) {
        mutableStateOf(recoveries.map { it.shortAmount.toString() })
    }
    var recLong by remember(initial.id, reloadToken) {
        mutableStateOf(recoveries.map { it.longAmount.toString() })
    }
    var skillFormula by remember(initial.id, reloadToken) {
        mutableStateOf(initial.skills.map { it.cocSkill?.toString() ?: it.expression })
    }
    var skillDc by remember(initial.id, reloadToken) {
        mutableStateOf(initial.skills.map { it.dc?.toString().orEmpty() })
    }
    var localError by remember(initial.id, reloadToken) { mutableStateOf<String?>(null) }
    val canEdit = enabled && !submitting
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.testTag("trpg.party.actor.editor"),
        title = { Text(if (chinese) "角色编辑" else "Actor") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrpgActorKind.entries.forEach { kind ->
                        FilterChip(selected = actor.kind == kind, onClick = { actor = actor.copy(kind = kind) }, enabled = canEdit,
                            label = { Text(actorKindText(kind, chinese)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                OutlinedTextField(actor.name, { actor = actor.copy(name = it.take(128)) }, enabled = canEdit, singleLine = true,
                    label = { Text(if (chinese) "姓名 (≤128)" else "Name (≤128)") }, modifier = Modifier.fillMaxWidth().testTag("trpg.party.actor.name"))
                OutlinedTextField(actor.description, { actor = actor.copy(description = it.take(4096)) }, enabled = canEdit, minLines = 2, maxLines = 4,
                    label = { Text(if (chinese) "描述 (≤4096)" else "Description (≤4096)") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = actor.visibility == TrpgVisibility.PLAYER, onClick = { actor = actor.copy(visibility = TrpgVisibility.PLAYER) },
                        enabled = canEdit, label = { Text(if (chinese) "玩家可见" else "Player") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = actor.visibility == TrpgVisibility.GAME_MASTER, onClick = { actor = actor.copy(visibility = TrpgVisibility.GAME_MASTER) },
                        enabled = canEdit, label = { Text(if (chinese) "主持秘密" else "GM secret") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                Text(if (chinese) "自定义字段（key = value，不得重复）" else "Custom fields (key = value, unique)", style = MaterialTheme.typography.labelMedium)
                fields.forEachIndexed { index, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(pair.first, { value -> fields = fields.toMutableList().also { it[index] = value to pair.second } },
                            enabled = canEdit, singleLine = true, label = { Text("key") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(pair.second, { value -> fields = fields.toMutableList().also { it[index] = pair.first to value } },
                            enabled = canEdit, singleLine = true, label = { Text("value") }, modifier = Modifier.weight(1f))
                        TextButton(onClick = { fields = fields.filterIndexed { i, _ -> i != index } }, enabled = canEdit,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("✕") }
                    }
                }
                TextButton(onClick = { if (fields.size < 16) fields = fields + ("" to "") }, enabled = canEdit && fields.size < 16,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (chinese) "+ 字段" else "+ Field") }
                Text(if (chinese) "资源（名称/当前/上限可选）" else "Resources (name/current/optional max)", style = MaterialTheme.typography.labelMedium)
                actor.resources.forEachIndexed { index, resource ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(resource.name, { value ->
                            val renamed = resource.copy(name = value.take(64))
                            actor = actor.copy(resources = actor.resources.toMutableList().also { it[index] = renamed })
                        }, enabled = canEdit, singleLine = true, label = { Text(if (chinese) "名称" else "Name") }, modifier = Modifier.weight(1.3f).testTag("trpg.party.actor.resource.name.$index"))
                        OutlinedTextField(resCurrent.getOrElse(index) { "" }, { value ->
                            resCurrent = resCurrent.toMutableList().also { it[index] = value }
                        }, enabled = canEdit, singleLine = true, label = { Text(if (chinese) "当前" else "Cur") }, modifier = Modifier.weight(0.8f))
                        OutlinedTextField(resMax.getOrElse(index) { "" }, { value ->
                            resMax = resMax.toMutableList().also { it[index] = value }
                        }, enabled = canEdit, singleLine = true, label = { Text(if (chinese) "上限" else "Max") }, modifier = Modifier.weight(0.8f))
                        TextButton(onClick = {
                            actor = actor.copy(resources = actor.resources.filterIndexed { i, _ -> i != index })
                            resCurrent = resCurrent.filterIndexed { i, _ -> i != index }
                            resMax = resMax.filterIndexed { i, _ -> i != index }
                            recoveries = recoveries.filterIndexed { i, _ -> i != index }
                            recShort = recShort.filterIndexed { i, _ -> i != index }
                            recLong = recLong.filterIndexed { i, _ -> i != index }
                        }, enabled = canEdit,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("✕") }
                    }
                }
                TextButton(onClick = {
                    if (actor.resources.size < 32) {
                        actor = actor.copy(resources = actor.resources + TrpgResource("HP", 10, 10))
                        resCurrent = resCurrent + "10"
                        resMax = resMax + "10"
                        recoveries = recoveries + TrpgRecovery("HP")
                        recShort = recShort + "0"
                        recLong = recLong + "0"
                    }
                },
                    enabled = canEdit && actor.resources.size < 32, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (chinese) "+ 资源" else "+ Resource")
                }
                Text(if (chinese) "技能（名称/表达式或CoC%/DC）" else "Skills (name/expression or CoC%/DC)", style = MaterialTheme.typography.labelMedium)
                skills.forEachIndexed { index, skill ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(skill.name, { value -> skills = skills.toMutableList().also { it[index] = skill.copy(name = value.take(128)) } },
                            enabled = canEdit, singleLine = true, label = { Text(if (chinese) "名称" else "Name") }, modifier = Modifier.weight(1.2f).testTag("trpg.party.actor.skill.name.$index"))
                        OutlinedTextField(skillFormula.getOrElse(index) { "" },
                            { value -> skillFormula = skillFormula.toMutableList().also { it[index] = value } },
                            enabled = canEdit, singleLine = true, label = { Text(if (chinese) "表达式/CoC%" else "Expr/CoC%") }, modifier = Modifier.weight(1f).testTag("trpg.party.actor.skill.formula.$index"))
                        OutlinedTextField(skillDc.getOrElse(index) { "" }, { value ->
                            skillDc = skillDc.toMutableList().also { it[index] = value }
                        }, enabled = canEdit, singleLine = true, label = { Text("DC") }, modifier = Modifier.weight(0.6f).testTag("trpg.party.actor.skill.dc.$index"))
                        TextButton(onClick = {
                            skills = skills.filterIndexed { i, _ -> i != index }
                            skillFormula = skillFormula.filterIndexed { i, _ -> i != index }
                            skillDc = skillDc.filterIndexed { i, _ -> i != index }
                        }, enabled = canEdit,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.actor.skill.remove.$index")) { Text("✕") }
                    }
                }
                TextButton(onClick = {
                    if (skills.size < 32) {
                        skills = skills + TrpgSkill(UUID.randomUUID().toString(), "", expression = "1d20")
                        skillFormula = skillFormula + "1d20"
                        skillDc = skillDc + ""
                    }
                }, enabled = canEdit && skills.size < 32, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.actor.skill.add")) {
                    Text(if (chinese) "+ 技能" else "+ Skill")
                }
                Text(if (chinese) "休息恢复（每资源：短休/长休数量或全满）" else "Rest recovery (per resource: short/long amount or full)",
                    style = MaterialTheme.typography.labelMedium)
                actor.resources.forEachIndexed { index, resource ->
                    val policy = recoveries[index]
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(resource.name, modifier = Modifier.weight(0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedTextField(recShort[index], { value ->
                            recShort = recShort.toMutableList().also { it[index] = value }
                        }, enabled = canEdit, singleLine = true, label = { Text(if (chinese) "短" else "S") }, modifier = Modifier.weight(0.6f))
                        Switch(checked = policy.shortFull, onCheckedChange = { value -> recoveries = recoveries.toMutableList().also { it[index] = policy.copy(shortFull = value) } }, enabled = canEdit)
                        OutlinedTextField(recLong[index], { value ->
                            recLong = recLong.toMutableList().also { it[index] = value }
                        }, enabled = canEdit, singleLine = true, label = { Text(if (chinese) "长" else "L") }, modifier = Modifier.weight(0.6f))
                        Switch(checked = policy.longFull, onCheckedChange = { value -> recoveries = recoveries.toMutableList().also { it[index] = policy.copy(longFull = value) } }, enabled = canEdit)
                    }
                }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (localError != null) {
                    TextButton(onClick = onReloadLatest, enabled = !submitting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.actor.reload")) {
                        Text(if (chinese) "载入最新" else "Reload latest")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val custom = linkedMapOf<String, String>()
                for ((key, value) in fields) {
                    if (key.isBlank() && value.isBlank()) continue
                    if (key.isBlank() || key.length > 64 || value.length > 512 || custom.containsKey(key.trim())) {
                        localError = if (chinese) "字段 key 不能为空/重复，value ≤512" else "Field keys must be unique and non-empty; value ≤512"
                        return@Button
                    }
                    custom[key.trim()] = value
                }
                val parsedResources = actor.resources.mapIndexed { index, resource ->
                    val current = resCurrent.getOrNull(index).orEmpty().trim().toIntOrNull()
                    val maxText = resMax.getOrNull(index).orEmpty().trim()
                    val maximum = if (maxText.isEmpty()) null else maxText.toIntOrNull()
                    if (current == null || (maxText.isNotEmpty() && maximum == null)) {
                        localError = if (chinese) "资源当前/上限必须是整数（上限可空）" else "Resource current/max must be integers (max optional)"
                        return@Button
                    }
                    TrpgResource(resource.name, current, maximum)
                }
                val parsedSkills = skills.mapIndexed { index, skill ->
                    val formula = skillFormula.getOrNull(index).orEmpty().trim()
                    val dcText = skillDc.getOrNull(index).orEmpty().trim()
                    val dc = if (dcText.isEmpty()) null else dcText.toIntOrNull()
                    if (formula.isEmpty() || (dcText.isNotEmpty() && dc == null)) {
                        localError = if (chinese) "技能表达式/CoC%与 DC 必须有效" else "Skill formula and DC must be valid"
                        return@Button
                    }
                    val coc = formula.toIntOrNull()?.takeIf { it in 1..100 }
                    if (coc != null) skill.copy(cocSkill = coc, expression = "1d20", dc = dc)
                    else skill.copy(cocSkill = null, expression = formula.take(256), dc = dc)
                }
                val parsedRecovery = parsedResources.mapIndexedNotNull { index, resource ->
                    val shortText = recShort[index].trim()
                    val longText = recLong[index].trim()
                    val short = if (shortText.isEmpty()) 0 else shortText.toIntOrNull()
                    val long = if (longText.isEmpty()) 0 else longText.toIntOrNull()
                    if (short == null || long == null || short < 0 || long < 0) {
                        localError = if (chinese) "休息恢复数量必须是非负整数" else "Rest recovery must be non-negative integers"
                        return@Button
                    }
                    val policy = recoveries[index]
                    if (short > 0 || long > 0 || policy.shortFull || policy.longFull) {
                        policy.copy(resource = resource.name, shortAmount = short, longAmount = long)
                    } else null
                }
                val updated = actor.copy(resources = parsedResources, skills = parsedSkills, fields = custom, recovery = parsedRecovery)
                scope.launch {
                    submitting = true
                    try {
                        try {
                            TrpgPartyValidation.validate(TrpgParty(actors = listOf(updated)))
                        } catch (failure: Exception) {
                            localError = failure.message
                            return@launch
                        }
                        if (!onSubmit(updated)) {
                            localError = if (chinese) "提交失败，草稿已保留；可载入最新后重试。" else
                                "Submit failed; draft kept. Reload latest and retry."
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        localError = failure.message
                    } finally { submitting = false }
                }
            }, enabled = canEdit, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.actor.apply")) {
                Text(if (chinese) "应用" else "Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (chinese) "取消" else "Cancel") } },
    )
}
