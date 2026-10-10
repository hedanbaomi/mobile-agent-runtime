// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

object SecretRedactor {
    private val bearer = Regex("""(?i)(authorization\s*:\s*)?(bearer\s+)\S+""")
    private val apiKey = Regex("""(?i)((?:x-)?api[_-]?key["']?)\s*[:=]\s*(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;}]+)""")
    private val cookie = Regex("""(?i)((?:set-)?cookie\s*:\s*)[^\r\n]+""")
    private val jsonCookie = Regex("""(?i)(["'](?:set-)?cookie["']\s*:\s*)(?:"[^"\r\n]*"|'[^'\r\n]*')""")
    private val sk = Regex("""(?i)sk-[A-Za-z0-9_-]{10,}""")

    fun redact(text: String, extraSecrets: List<String> = emptyList()): String {
        var result = text
        extraSecrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { secret ->
            result = result.replace(secret, "***")
        }
        result = bearer.replace(result, "$1$2***")
        result = apiKey.replace(result, "$1=***")
        result = cookie.replace(result, "$1***")
        result = jsonCookie.replace(result, "$1\"***\"")
        result = sk.replace(result, "***")
        return result
    }
}
