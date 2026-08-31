/*
 * SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package runtime.mobileagent.skills.tooling

/** Confirmation classification only; this is not an execution allowlist. */
enum class ShellRiskLevel { LOW, HIGH }

enum class ShellRiskReason {
    EMPTY_COMMAND,
    UNKNOWN_COMMAND,
    WRITE_OR_DELETE,
    REDIRECTION,
    PIPELINE_OR_COMPOSITION,
    VARIABLE_EXPANSION,
    SUBSHELL,
    GLOB,
    ENCODING_OR_TRANSFORM,
    SENSITIVE_READ,
    DATA_EXFILTRATION,
    PERMISSION_PROBE,
    BACKGROUND_RESIDENCY,
    COMPLEX_ARGUMENTS,
}

data class ShellRiskAssessment(
    val level: ShellRiskLevel,
    val reasons: Set<ShellRiskReason>,
) {
    val requiresConfirmation: Boolean
        get() = level == ShellRiskLevel.HIGH
}

/**
 * Conservative lexical classification for confirmation UX.  It deliberately
 * does not reject, rewrite, or execute a command and does not claim to be an
 * allowlist.  Unknown syntax is high risk and must be confirmed by policy.
 */
object HighRiskDetector {
    private val lowRiskCommands = setOf("pwd", "ls", "dir", "stat", "file", "head", "tail", "wc", "cat")
    private val writeCommands = setOf(
        "rm", "rmdir", "del", "erase", "mv", "move", "cp", "copy", "mkdir", "md", "touch", "tee",
        "dd", "truncate", "chmod", "chown", "chgrp", "setfacl", "sed", "perl", "python", "python3",
        "kill", "pkill", "killall", "reboot", "shutdown", "format", "write",
    )
    private val encodingCommands = setOf("base64", "xxd", "openssl", "gzip", "gunzip", "tar", "zip", "unzip", "printf")
    private val exfiltrationCommands = setOf("curl", "wget", "nc", "netcat", "scp", "sftp", "ftp", "ssh", "rsync", "telnet")
    private val permissionCommands = setOf(
        "id", "whoami", "groups", "sudo", "su", "runas", "getfacl", "lsattr", "mount", "adb",
        "getprop", "env", "printenv", "ps", "netstat", "ss", "ip", "ifconfig", "route",
    )
    private val backgroundCommands = setOf("nohup", "disown", "setsid", "systemctl", "service", "crond", "at", "daemon")
    private val sensitiveWords = Regex("(?i)(^|[/_.-])(secret|secrets|token|password|passwd|credential|credentials|private[_-]?key|api[_-]?key|access[_-]?key|\\.env|shadow|authorized_keys|known_hosts|id_rsa|keystore|keychain|cookies?|session|history|bash_history|proc|environ|dumpsys|logcat)([/_.-]|$)")

    fun assess(command: String): ShellRiskAssessment {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return high(ShellRiskReason.EMPTY_COMMAND)

        val reasons = linkedSetOf<ShellRiskReason>()
        when {
            trimmed.any { it == '>' || it == '<' } -> reasons += ShellRiskReason.REDIRECTION
            trimmed.contains('|') || trimmed.contains("&&") || trimmed.contains("||") || trimmed.contains(';') || trimmed.contains('\n') || trimmed.contains('\r') ->
                reasons += ShellRiskReason.PIPELINE_OR_COMPOSITION
        }
        if (trimmed.contains('$') || trimmed.contains('`') || trimmed.contains('~') || Regex("%[A-Za-z_][A-Za-z0-9_]*%").containsMatchIn(trimmed)) {
            reasons += ShellRiskReason.VARIABLE_EXPANSION
        }
        if (trimmed.contains("$(") || trimmed.contains("\${")) reasons += ShellRiskReason.SUBSHELL
        if (trimmed.any { it == '*' || it == '?' || it == '[' || it == ']' }) reasons += ShellRiskReason.GLOB
        if (trimmed.contains('&')) reasons += ShellRiskReason.BACKGROUND_RESIDENCY

        val tokens = tokenizeConservatively(trimmed)
        val commandName = tokens.firstOrNull()?.lowercase()?.substringAfterLast('/')
        when {
            commandName == null -> reasons += ShellRiskReason.UNKNOWN_COMMAND
            commandName in writeCommands -> reasons += ShellRiskReason.WRITE_OR_DELETE
            commandName in encodingCommands -> reasons += ShellRiskReason.ENCODING_OR_TRANSFORM
            commandName in exfiltrationCommands -> reasons += ShellRiskReason.DATA_EXFILTRATION
            commandName in permissionCommands -> reasons += ShellRiskReason.PERMISSION_PROBE
            commandName in backgroundCommands -> reasons += ShellRiskReason.BACKGROUND_RESIDENCY
            commandName !in lowRiskCommands -> reasons += ShellRiskReason.UNKNOWN_COMMAND
        }

        if (tokens.drop(1).any { token -> sensitiveWords.containsMatchIn(token) }) {
            reasons += ShellRiskReason.SENSITIVE_READ
        }
        // Quoting, escapes, command flags outside a tiny read-only shape, and
        // an argument count beyond a simple path make interpretation complex.
        if (tokens.any { it.contains('\\') || it.contains('"') || it.contains('\'') } || tokens.size > 4) {
            reasons += ShellRiskReason.COMPLEX_ARGUMENTS
        }

        return if (reasons.isEmpty()) ShellRiskAssessment(ShellRiskLevel.LOW, emptySet())
        else ShellRiskAssessment(ShellRiskLevel.HIGH, reasons)
    }

    fun isHighRisk(command: String): Boolean = assess(command).level == ShellRiskLevel.HIGH

    private fun high(reason: ShellRiskReason) = ShellRiskAssessment(ShellRiskLevel.HIGH, setOf(reason))

    /** Tokenization is intentionally incomplete; it only prevents false low-risk classifications. */
    private fun tokenizeConservatively(command: String): List<String> =
        command.split(Regex("\\s+")).filter(String::isNotEmpty)
}
