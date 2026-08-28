// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

data class EffectivePrompt(
    val runtimeContract: String,
    val userSystemPrompt: String,
    val skillInstructions: List<String>,
    val retrieved: List<String>,
    val history: List<Pair<String, String>>,
    val currentUser: String,
) {
    fun asMessages(): List<Map<String, String>> {
        val messages = mutableListOf<Map<String, String>>()
        val system = buildString {
            appendLine(runtimeContract)
            appendLine(userSystemPrompt)
            skillInstructions.forEach { appendLine(it) }
            retrieved.forEach { appendLine(it) }
        }.trim()
        if (system.isNotEmpty()) messages += mapOf("role" to "system", "content" to system)
        history.forEach { (role, content) -> messages += mapOf("role" to role, "content" to content) }
        messages += mapOf("role" to "user", "content" to currentUser)
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
