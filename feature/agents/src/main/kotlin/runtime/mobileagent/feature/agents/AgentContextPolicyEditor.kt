// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import runtime.mobileagent.domain.AgentContextPolicy

/**
 * Transient string draft for the Agent editor's "context & auto-compaction" section.
 *
 * Numbers stay as text so a user can clear a field completely and then type a new
 * value; the editor never snaps an empty string back to a default on each keystroke.
 * [toCanonicalJson] validates the text, writes it into the stored policy JSON, and
 * leaves every key this editor does not own (including unknown keys from another
 * build) exactly as it was.
 */
data class AgentContextPolicyDraftUi(
    val autoCompact: Boolean = true,
    /** Blank means "use the model's available window"; persisted by omitting the key. */
    val maxInputTokens: String = "",
    val maxHistoryMessages: String = "20",
    val maxHistoryTurns: String = "10",
    val maxModelRoundsPerSegment: String = "8",
    val maxModelRequestsPerRun: String = "32",
    /** Blank means "no Run fee authorization for Python model.invoke". */
    val pythonModelRunTokens: String = "",
    val keepRecentTurns: String = "2",
    val softLimitPercent: String = "85",
    val targetPercent: String = "60",
    val maxCompactionsPerRun: String = "8",
) {
    /**
     * Produces the JSON persisted in `AgentProfile.contextPolicyJson`.
     *
     * Only the ten editable keys are replaced; any other key already present in
     * [rawJson] is preserved. Non-integer or out-of-range input throws an
     * [IllegalArgumentException] with a field-specific message instead of silently
     * falling back to a default. The merged result is always re-validated through
     * the shared [AgentContextPolicy.fromJson] contract.
     */
    fun toCanonicalJson(rawJson: String): String {
        val parsed = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull()
        val base = when (parsed) {
            null -> throw IllegalArgumentException("上下文设置不是有效 JSON。")
            is JsonObject -> parsed.toMutableMap()
            else -> throw IllegalArgumentException("上下文设置必须是 JSON 对象。")
        }

        base["autoCompact"] = JsonPrimitive(autoCompact)
        val inputTokens = maxInputTokens.trim()
        if (inputTokens.isEmpty()) {
            base.remove("maxInputTokens")
        } else {
            base["maxInputTokens"] = JsonPrimitive(intText("输入预算", inputTokens))
        }
        base["maxHistoryMessages"] = JsonPrimitive(intText("最大历史消息数", maxHistoryMessages))
        base["maxHistoryTurns"] = JsonPrimitive(intText("最大历史轮数", maxHistoryTurns))
        base["maxModelRoundsPerSegment"] = JsonPrimitive(intText("每段模型轮上限", maxModelRoundsPerSegment))
        base["maxModelRequestsPerRun"] = JsonPrimitive(intText("每次运行模型请求上限", maxModelRequestsPerRun))
        val modelTokens = pythonModelRunTokens.trim()
        if (modelTokens.isEmpty()) {
            // Absent key = this Run is not authorized to spend on model.invoke.
            base.remove("pythonModelRunTokens")
        } else {
            base["pythonModelRunTokens"] = JsonPrimitive(intText("Python 模型调用上限", modelTokens))
        }
        base["keepRecentTurns"] = JsonPrimitive(intText("保留最近轮数", keepRecentTurns))
        base["softLimitPercent"] = JsonPrimitive(intText("软阈值百分比", softLimitPercent))
        base["targetPercent"] = JsonPrimitive(intText("目标压缩百分比", targetPercent))
        base["maxCompactionsPerRun"] = JsonPrimitive(intText("每次运行压缩次数上限", maxCompactionsPerRun))

        val merged = JsonObject(base).toString()
        try {
            AgentContextPolicy.fromJson(merged)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(localizedContextPolicyError(error.message))
        }
        return merged
    }

    /** Concise, read-only rendering used by the Agent summary (never used for persistence). */
    fun summary(zh: Boolean): String {
        val auto = when {
            autoCompact && zh -> "开启"
            autoCompact -> "on"
            zh -> "关闭"
            else -> "off"
        }
        val input = maxInputTokens.trim().ifEmpty { if (zh) "模型可用窗口" else "model window" }
        val messages = maxHistoryMessages.trim()
        val turns = maxHistoryTurns.trim()
        val rounds = maxModelRoundsPerSegment.trim()
        val requests = maxModelRequestsPerRun.trim()
        val modelTokens = pythonModelRunTokens.trim().ifEmpty { if (zh) "未授权" else "not authorized" }
        val keep = keepRecentTurns.trim()
        val soft = softLimitPercent.trim()
        val target = targetPercent.trim()
        val compactions = maxCompactionsPerRun.trim()
        return if (zh) {
            "自动压缩：$auto；输入预算（保守估算单位）：$input；历史消息 ≤$messages · 历史轮 ≤$turns；" +
                "每段模型轮 ≤$rounds；每次运行模型请求 ≤$requests（含摘要调用）；" +
                "Python 模型调用费用上限：$modelTokens token；保留最近 $keep 轮；" +
                "软阈值 $soft% → 目标 $target%；压缩 ≤$compactions 次。"
        } else {
            "Auto-compaction: $auto; input budget (conservative estimate): $input; history ≤$messages messages / ≤$turns turns; " +
                "≤$rounds model rounds per segment; ≤$requests model requests per run (includes summary calls); " +
                "Python model.invoke fee ceiling: $modelTokens tokens; keep $keep turns; " +
                "soft $soft% → target $target%; ≤$compactions compactions."
        }
    }

    companion object {
        /**
         * Reads stored JSON into editable text without validating it: persistence
         * still goes through [toCanonicalJson] and [AgentContextPolicy.fromJson].
         */
        fun fromJson(raw: String): AgentContextPolicyDraftUi {
            val obj = (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)
                ?: return AgentContextPolicyDraftUi()
            fun text(name: String, fallback: String): String {
                val element = obj[name] as? JsonPrimitive ?: return fallback
                if (element is JsonNull) return fallback
                if (element.isString) return element.content
                return element.intOrNull?.toString() ?: element.content
            }
            val defaults = AgentContextPolicy()
            val enabled = (obj["autoCompact"] as? JsonPrimitive)
                ?.takeIf { it !is JsonNull && !it.isString }
                ?.booleanOrNull
                ?: defaults.autoCompact
            return AgentContextPolicyDraftUi(
                autoCompact = enabled,
                maxInputTokens = text("maxInputTokens", ""),
                maxHistoryMessages = text("maxHistoryMessages", defaults.maxHistoryMessages.toString()),
                maxHistoryTurns = text("maxHistoryTurns", defaults.maxHistoryTurns.toString()),
                maxModelRoundsPerSegment = text("maxModelRoundsPerSegment", defaults.maxModelRoundsPerSegment.toString()),
                maxModelRequestsPerRun = text("maxModelRequestsPerRun", defaults.maxModelRequestsPerRun.toString()),
                pythonModelRunTokens = text("pythonModelRunTokens", ""),
                keepRecentTurns = text("keepRecentTurns", defaults.keepRecentTurns.toString()),
                softLimitPercent = text("softLimitPercent", defaults.softLimitPercent.toString()),
                targetPercent = text("targetPercent", defaults.targetPercent.toString()),
                maxCompactionsPerRun = text("maxCompactionsPerRun", defaults.maxCompactionsPerRun.toString()),
            )
        }
    }
}

private val contextPolicyFieldLabels = linkedMapOf(
    "autoCompact" to "自动压缩开关",
    "maxInputTokens" to "输入预算",
    "maxHistoryMessages" to "最大历史消息数",
    "maxHistoryTurns" to "最大历史轮数",
    "keepRecentTurns" to "保留最近轮数",
    "softLimitPercent" to "软阈值百分比",
    "targetPercent" to "目标压缩百分比",
    "maxModelRoundsPerSegment" to "每段模型轮上限",
    "maxModelRequestsPerRun" to "每次运行模型请求上限",
    "pythonModelRunTokens" to "Python 模型调用费用上限",
    "maxCompactionsPerRun" to "每次运行压缩次数上限",
    "summaryOutputTokens" to "摘要输出上限",
    "summaryMaxUnits" to "摘要最大单位",
    "reservedOutputTokens" to "预留输出",
    "knowledgeTokenBudget" to "知识预算",
    "imageBudget" to "图片预算",
)

/** Maps the shared policy API's English field error to a field-specific Chinese message. */
fun localizedContextPolicyError(message: String?): String {
    val text = message.orEmpty()
    if (text.contains("below the soft limit")) return "目标压缩百分比必须低于软阈值百分比。"
    if (text.contains("leave space after the output reservation")) return "该模型的输出预留超过了上下文窗口。"
    val label = contextPolicyFieldLabels.entries
        .firstOrNull { (field, _) -> text.startsWith(field) }
        ?.value
    return if (label != null) "$label 超出允许范围。" else "上下文设置无效。"
}

private fun intText(label: String, raw: String): Int {
    val text = raw.trim()
    val value = text.toLongOrNull()
    if (text.isEmpty() || text.any { it !in '0'..'9' } || value == null || value > Int.MAX_VALUE) {
        throw IllegalArgumentException("$label 必须是整数。")
    }
    return value.toInt()
}
