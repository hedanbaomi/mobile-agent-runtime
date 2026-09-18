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
    /**
     * The content channel that owns the pending suffix.  Reasoning, answer text
     * and refusals must not share one pending buffer: a suffix withheld from
     * reasoning would otherwise be re-emitted as answer text.
     */
    enum class Channel { TEXT, REASONING, REFUSAL }

    private val secrets = secrets
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
    // One pending suffix per channel: reasoning, answer text and refusals must
    // not share a buffer, or a withheld suffix would be re-emitted on another
    // channel and an interleaved legitimate answer would lose characters.
    private val pending = linkedMapOf<Channel, String>()

    fun accept(input: String, channel: Channel = Channel.TEXT): String {
        if (input.isEmpty()) return ""
        val combined = pending[channel].orEmpty() + input
        pending[channel] = ""
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
                pending[channel] = combined.substring(cursor)
                break
            }
            output.append(combined[cursor])
            cursor++
        }
        return SecretRedactor.redact(output.toString(), secrets)
    }

    /** Channel that owns the withheld suffix, if any. */
    fun pendingChannel(): Channel? = pending.entries.firstOrNull { it.value.isNotEmpty() }?.key

    /** Flush every channel's withheld suffix; only valid after normal completion. */
    fun finish(): List<Pair<Channel, String>> {
        val tails = pending.entries
            .filter { it.value.isNotEmpty() }
            .map { it.key to SecretRedactor.redact(it.value, secrets) }
        pending.clear()
        return tails
    }
    /** Drop an unconfirmed suffix on error, cancellation, or an incomplete EOF. */
    fun discard() {
        pending.clear()
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
