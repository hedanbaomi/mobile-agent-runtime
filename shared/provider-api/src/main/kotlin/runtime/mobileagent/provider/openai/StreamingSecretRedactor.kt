// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.provider.SecretRedactor

/**
 * Redacts credentials without exposing a prefix which may become a credential
 * when the next stream delta arrives.  Only the bounded suffix that is a
 * prefix of a known credential is retained between calls.
 */
internal class StreamingSecretRedactor(secrets: List<String>) {
    private val secrets = secrets
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
    private var pending = ""

    fun accept(input: String): String {
        if (input.isEmpty()) return ""
        val combined = pending + input
        pending = ""
        val output = StringBuilder(combined.length)
        var cursor = 0
        while (cursor < combined.length) {
            val secret = secrets.firstOrNull { combined.regionMatches(cursor, it, 0, it.length) }
            if (secret != null) {
                output.append("***")
                cursor += secret.length
                continue
            }
            val remaining = combined.length - cursor
            val isPossiblePrefix = secrets.any { secretValue ->
                remaining in 1 until secretValue.length &&
                    combined.regionMatches(cursor, secretValue, 0, remaining)
            }
            if (isPossiblePrefix) {
                pending = combined.substring(cursor)
                break
            }
            output.append(combined[cursor])
            cursor++
        }
        return SecretRedactor.redact(output.toString(), secrets)
    }

    /** Flush is only valid after a confirmed normal completion. */
    fun finish(): String {
        val tail = pending
        pending = ""
        return SecretRedactor.redact(tail, secrets)
    }

    /** Drop an unconfirmed suffix on error, cancellation, or an incomplete EOF. */
    fun discard() {
        pending = ""
    }
}

internal fun containsCredentialText(value: String, secrets: List<String>): Boolean =
    SecretRedactor.redact(value, secrets) != value

internal fun containsCredentialJson(value: JsonElement, secrets: List<String>): Boolean = when (value) {
    is JsonPrimitive -> containsCredentialText(value.content, secrets)
    is JsonObject -> value.values.any { containsCredentialJson(it, secrets) }
    is JsonArray -> value.any { containsCredentialJson(it, secrets) }
    else -> false
}
