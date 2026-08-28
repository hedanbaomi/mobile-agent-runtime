// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.InlineImage

data class EffectivePrompt(
    val runtimeContract: String,
    val userSystemPrompt: String,
    val skillInstructions: List<String>,
    val retrieved: List<String>,
    val history: List<Pair<String, String>>,
    val currentUser: String,
    val currentImages: List<InlineImage> = emptyList(),
) {
    fun asMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val system = buildString {
            appendLine(runtimeContract)
            appendLine(userSystemPrompt)
            skillInstructions.forEach { appendLine(it) }
            retrieved.forEach { appendLine(it) }
        }.trim()
        if (system.isNotEmpty()) messages += ChatMessage(role = "system", text = system)
        history.forEach { (role, content) -> messages += ChatMessage(role = role, text = content) }
        messages += ChatMessage(role = "user", text = currentUser, images = currentImages)
        return messages
    }
}

object PromptTemplates {
    val allowed = setOf("date", "agent_name", "knowledge_bases")

    fun render(template: String, values: Map<String, String>): String {
        val used = Regex("\\{\\{([a-z_]+)\\}\\}").findAll(template).map { it.groupValues[1] }.toSet()
        val illegal = used - allowed
        require(illegal.isEmpty()) { "Unsupported template variables: $illegal" }
        var result = template
        allowed.forEach { key ->
            result = result.replace("{{$key}}", values[key].orEmpty())
        }
        return result
    }
}
