// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookActivation
import runtime.mobileagent.domain.worldbook.WorldBookContext
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookRole
import runtime.mobileagent.provider.ChatMessage

/** Frozen selection, reapplied to request projections; it never becomes transcript history. */
data class WorldBookPromptPlan(
    val activated: List<WorldBookActivation> = emptyList(),
    val context: WorldBookContext = WorldBookContext(),
)

object WorldBookPromptComposer {
    private const val MAX_EXPANDED_CHARS = 2_000_000
    private val outlet = Regex("\\{\\{outlet::([^{}]{1,128})\\}\\}")
    private val order = compareBy<WorldBookActivation> { it.order }.thenBy { it.index }.thenBy { it.bookId }.thenBy { it.entryUid }

    /** This allow-list is text substitution only; script and expression evaluation do not exist. */
    fun isOutletVariable(variable: String): Boolean = runtime.mobileagent.domain.worldbook.WorldBookTemplateRules.isOutletVariable(variable)

    fun wrap(content: String): String = "<untrusted-worldbook-context>\n$content\n</untrusted-worldbook-context>"

    fun outletNames(configuration: String): Set<String> = outlet.findAll(configuration).map { it.groupValues[1].trim() }.toSet()

    private fun expandOutlets(value: String, values: Map<String, String>): String = buildString {
        var cursor = 0
        for (match in outlet.findAll(value)) {
            appendBounded(value, cursor, match.range.first)
            val replacement = values[match.groupValues[1].trim()].orEmpty()
            appendBounded(replacement, 0, replacement.length)
            cursor = match.range.last + 1
        }
        appendBounded(value, cursor, value.length)
    }

    private fun StringBuilder.appendBounded(value: String, start: Int, end: Int) {
        require(end - start <= MAX_EXPANDED_CHARS - length) { "CONTEXT_OVERFLOW: world book expansion exceeds the safety limit" }
        append(value, start, end)
    }

    fun apply(messages: List<ChatMessage>, plan: WorldBookPromptPlan, configuration: Map<String, String> = emptyMap()): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val result = messages.toMutableList()
        val systemIndex = result.indexOfFirst { it.role == "system" }
        if (systemIndex >= 0) result[systemIndex] = composeSystem(result[systemIndex], plan, configuration)
        else if (plan.activated.any { it.position == WorldBookPosition.BEFORE_CHAR || it.position == WorldBookPosition.AFTER_CHAR }) {
            result.add(0, ChatMessage("system", joined(plan.activated.filter { it.position == WorldBookPosition.BEFORE_CHAR || it.position == WorldBookPosition.AFTER_CHAR })))
        }
        addAnchoredContent(result, plan)
        addDepthContent(result, plan)
        return result
    }

    private fun composeSystem(message: ChatMessage, plan: WorldBookPromptPlan, configuration: Map<String, String>): ChatMessage {
        // Expand outlets in trusted user configuration before adding untrusted entry bodies.
        val outlets = plan.activated.filter { it.position == WorldBookPosition.OUTLET }
            .groupBy { it.outletName }.mapValues { joined(it.value) }
        val before = joined(plan.activated.filter { it.position == WorldBookPosition.BEFORE_CHAR })
        val after = joined(plan.activated.filter { it.position == WorldBookPosition.AFTER_CHAR })
        // Rebuild the known structured prefix. Never search tags inside any field or expanded body.
        val fields = configuration.filterValues { it.isNotBlank() }
        val original = fields.entries.joinToString("\n") { (tag, value) -> "<$tag>\n$value\n</$tag>" }
        if (original.isEmpty() || !message.text.startsWith(original)) {
            return message.copy(text = listOf(message.text, before, after).filter { it.isNotEmpty() }.joinToString("\n"))
        }
        val replacement = fields.entries.joinToString("\n") { (tag, value) ->
            val expanded = if (tag == "runtime-contract") value else expandOutlets(value, outlets)
            val block = "<$tag>\n$expanded\n</$tag>"
            if (tag == "user-system-prompt") listOf(before, block, after).filter { it.isNotEmpty() }.joinToString("\n") else block
        }
        val absentAnchor = if ("user-system-prompt" !in fields) listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n") else ""
        return message.copy(text = replacement + (if (absentAnchor.isEmpty()) "" else "\n$absentAnchor") + message.text.substring(original.length))
    }

    private fun addAnchoredContent(messages: MutableList<ChatMessage>, plan: WorldBookPromptPlan) {
        val context = plan.context
        if (context.exampleMessages.isNotEmpty()) {
            val text = listOf(joined(plan.activated.filter { it.position == WorldBookPosition.EM_TOP }),
                wrap(context.exampleMessages.joinToString("\n")),
                joined(plan.activated.filter { it.position == WorldBookPosition.EM_BOTTOM }))
                .filter { it.isNotEmpty() }.joinToString("\n")
            val index = messages.indexOfFirst { it.role != "system" }.takeIf { it >= 0 } ?: messages.size
            messages.add(safeBoundary(messages, index), ChatMessage("system", text))
        }
        if (context.authorNote.isNotBlank()) {
            val text = listOf(joined(plan.activated.filter { it.position == WorldBookPosition.AN_TOP }),
                wrap(context.authorNote), joined(plan.activated.filter { it.position == WorldBookPosition.AN_BOTTOM }))
                .filter { it.isNotEmpty() }.joinToString("\n")
            // Author's note is a separate background anchor immediately before the current user.
            val toolMembers = toolExchangeRanges(messages).flatMap { it.toList() }.toSet()
            val index = messages.indices.lastOrNull { messages[it].role == "user" && it !in toolMembers } ?: messages.size
            messages.add(safeBoundary(messages, index), ChatMessage("system", text))
        }
    }

    private fun addDepthContent(messages: MutableList<ChatMessage>, plan: WorldBookPromptPlan) {
        val original = messages.toList()
        val toolMembers = toolExchangeRanges(original).flatMap { it.toList() }.toSet()
        val visible = original.indices.filter { it !in toolMembers && original[it].role in setOf("user", "assistant") }
        val insertions = plan.activated.filter { it.position == WorldBookPosition.AT_DEPTH }
            .groupBy { it.depth.coerceAtLeast(0) to it.role }
            .map { (key, entries) ->
                val (depth, role) = key
                val index = when {
                    depth == 0 -> original.size
                    visible.isEmpty() -> original.size
                    depth >= visible.size -> visible.first()
                    else -> visible[visible.size - depth]
                }
                Triple(safeBoundary(original, index), role, joined(entries))
            }.sortedWith(compareByDescending<Triple<Int, WorldBookRole, String>> { it.first }.thenByDescending { it.second.code })
        insertions.forEach { (index, role, content) ->
            messages.add(index, ChatMessage(when (role) {
                WorldBookRole.SYSTEM -> "system"
                WorldBookRole.USER -> "user"
                WorldBookRole.ASSISTANT -> "assistant"
            }, content))
        }
    }

    /** Never place a synthetic context message inside an assistant/tool-result exchange. */
    private fun safeBoundary(messages: List<ChatMessage>, proposed: Int): Int {
        val index = proposed.coerceIn(0, messages.size)
        return toolExchangeRanges(messages).firstOrNull { index > it.first && index <= it.last }?.first ?: index
    }

    private fun toolExchangeRanges(messages: List<ChatMessage>): List<IntRange> = buildList {
        var index = 0
        while (index < messages.size) {
            val start = index++
            val pending = messages[start].toolCalls.map { it.id }.toMutableSet()
            if (pending.isEmpty()) continue
            while (index < messages.size && pending.isNotEmpty()) {
                val next = messages[index]
                if (next.role == "tool") pending.remove(next.toolCallId)
                else if (next.images.isEmpty()) break
                index++
            }
            while (index < messages.size && messages[index].images.isNotEmpty() && messages[index].toolCalls.isEmpty()) index++
            add(start until index)
        }
    }

    private fun joined(entries: List<WorldBookActivation>): String = buildString {
        entries.sortedWith(order).forEachIndexed { index, entry ->
            val wrappers = "<untrusted-worldbook-context>\n" to "\n</untrusted-worldbook-context>"
            if (index > 0) appendBounded("\n", 0, 1)
            appendBounded(wrappers.first, 0, wrappers.first.length)
            appendBounded(entry.content, 0, entry.content.length)
            appendBounded(wrappers.second, 0, wrappers.second.length)
        }
    }
}
