// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

object SecretRedactor {
    private val bearer = Regex("""(?i)(authorization\s*:\s*)?(bearer\s+)\S+""")
    private val apiKey = Regex("""(?i)(api[_-]?key)\s*[:=]\s*\S+""")
    private val sk = Regex("""(?i)sk-[A-Za-z0-9]{10,}""")

    fun redact(text: String, extraSecrets: List<String> = emptyList()): String {
        var result = text
        extraSecrets.filter { it.isNotEmpty() }.forEach { secret ->
            result = result.replace(secret, "***")
        }
        result = bearer.replace(result, "$1$2***")
        result = apiKey.replace(result, "$1=***")
        result = sk.replace(result, "***")
        return result
    }
}
