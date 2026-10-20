// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.InlineImage

enum class PromptTrust {
    TRUSTED_RUNTIME,
    USER_CONFIGURATION,
    UNTRUSTED_SKILL,
    UNTRUSTED_KNOWLEDGE,
    UNTRUSTED_WORLD_BOOK,
    USER_INPUT,
    CONVERSATION_HISTORY,
}

data class PromptBlock(
    val trust: PromptTrust,
    val text: String,
)

data class PromptAssembly(
    val blocks: List<PromptBlock>,
    val messages: List<ChatMessage>,
)

data class EffectivePrompt(
    val runtimeContract: String,
    val userSystemPrompt: String,
    val skillInstructions: List<String>,
    val retrieved: List<String>,
    val history: List<Pair<String, String>>,
    val currentUser: String,
    val currentImages: List<InlineImage> = emptyList(),
    /** Typed history preserves tool calls, tool results, and image metadata. */
    val typedHistory: List<ChatMessage>? = null,
    val globalRootPrompt: String = "",
    val worldBooks: runtime.mobileagent.agent.worldbook.WorldBookPromptPlan = runtime.mobileagent.agent.worldbook.WorldBookPromptPlan(),
    val trpgSession: runtime.mobileagent.domain.trpg.TrpgSession? = null,
) {
    /**
     * Assemble a model prompt with visible trust boundaries.  Skills and
     * retrieved knowledge are evidence/instructions supplied by external
     * content; they must not be allowed to redefine the runtime contract or
     * capability grants.  Tool definitions are attached separately by the
     * runtime and never come from these strings.
     */
    fun assemble(includeWorldBooks: Boolean = true, includeTrpg: Boolean = true): PromptAssembly {
        val blocks = buildList {
            add(PromptBlock(PromptTrust.TRUSTED_RUNTIME, runtimeContract))
            if (globalRootPrompt.isNotBlank()) {
                add(PromptBlock(PromptTrust.USER_CONFIGURATION, globalRootPrompt))
            }
            add(PromptBlock(PromptTrust.USER_CONFIGURATION, userSystemPrompt))
            skillInstructions.forEach { add(PromptBlock(PromptTrust.UNTRUSTED_SKILL, it)) }
            retrieved.forEach { add(PromptBlock(PromptTrust.UNTRUSTED_KNOWLEDGE, it)) }
            worldBooks.activated.forEach { add(PromptBlock(PromptTrust.UNTRUSTED_WORLD_BOOK, it.content)) }
            add(PromptBlock(PromptTrust.USER_INPUT, currentUser))
        }
        val messages = mutableListOf<ChatMessage>()
        val system = buildString {
            appendTagged("runtime-contract", runtimeContract)
            appendTagged("global-root-prompt", globalRootPrompt)
            appendTagged("user-system-prompt", userSystemPrompt)
            if (skillInstructions.isNotEmpty()) {
                appendLine("<untrusted-skill-instructions>")
                skillInstructions.forEach { appendLine(it) }
                appendLine("</untrusted-skill-instructions>")
            }
            if (retrieved.isNotEmpty()) {
                appendLine("<untrusted-knowledge-evidence>")
                retrieved.forEach { appendLine(it) }
                appendLine("</untrusted-knowledge-evidence>")
            }
        }.trim()
        if (system.isNotEmpty()) messages += ChatMessage(role = "system", text = system)
        if (typedHistory != null) {
            messages += typedHistory
        } else {
            history.forEach { (role, content) ->
                messages += ChatMessage(role = role, text = content)
            }
        }
        messages += ChatMessage(role = "user", text = currentUser, images = currentImages)
        val worldBookMessages = if (includeWorldBooks) runtime.mobileagent.agent.worldbook.WorldBookPromptComposer.apply(messages, worldBooks, outletConfiguration()) else messages
        return PromptAssembly(blocks = blocks, messages = if (includeTrpg) TrpgPromptComposer.apply(worldBookMessages, trpgSession) else worldBookMessages)
    }

    fun asMessages(): List<ChatMessage> = assemble().messages

    internal fun outletConfiguration(): Map<String, String> = linkedMapOf(
        "runtime-contract" to runtimeContract, "global-root-prompt" to globalRootPrompt, "user-system-prompt" to userSystemPrompt,
    )

    private fun StringBuilder.appendTagged(name: String, value: String) {
        if (value.isBlank()) return
        appendLine("<$name>")
        appendLine(value)
        appendLine("</$name>")
    }
}

object PromptTemplates {
    val allowed = setOf("date", "agent_name", "knowledge_bases", "character_user")
    private const val MAX_RENDERED_CHARS = 1_048_576
    private val placeholder = Regex("\\{\\{([^{}]*)\\}\\}")

    /** Render only the small, literal allow-list; no expression evaluation is performed. */
    fun render(template: String, values: Map<String, String>): String {
        val matches = placeholder.findAll(template).toList()
        require(template.count { it == '{' } == matches.size * 2 &&
            template.count { it == '}' } == matches.size * 2) {
            "Malformed template variable"
        }
        var cursor = 0
        matches.forEach { match ->
            require(!template.substring(cursor, match.range.first).contains("{{") &&
                !template.substring(cursor, match.range.first).contains("}}")) {
                "Malformed template variable"
            }
            cursor = match.range.last + 1
        }
        require(!template.substring(cursor).contains("{{") &&
            !template.substring(cursor).contains("}}")) {
            "Malformed template variable"
        }
        val illegal = matches.map { it.groupValues[1] }.filter { it !in allowed && !runtime.mobileagent.agent.worldbook.WorldBookPromptComposer.isOutletVariable(it) }.toSet()
        require(illegal.isEmpty()) { "Unsupported template variables: $illegal" }
        fun replacement(match: MatchResult): String =
            if (runtime.mobileagent.agent.worldbook.WorldBookPromptComposer.isOutletVariable(match.groupValues[1])) match.value
            else values[match.groupValues[1]].orEmpty()
        val renderedLength = matches.fold(template.length.toLong()) { size, match -> size - match.value.length + replacement(match).length }
        require(renderedLength <= MAX_RENDERED_CHARS) { "Prompt expansion exceeds the safe length limit" }
        return placeholder.replace(template, ::replacement)
    }
}
