// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.provider.SecretRedactor

internal class RedactionBufferLimitExceeded : RuntimeException("Provider redaction buffer exceeds limit")

/**
 * Redacts credentials without exposing a prefix which may become a credential
 * when the next stream delta arrives. Only the suffix that may still form a
 * credential or credential pattern is retained in a mutable per-channel buffer.
 */
class StreamingSecretRedactor(secrets: List<String> = emptyList()) {
    /**
     * The content channel that owns the pending suffix. Reasoning, answer text
     * and refusals must not share one pending buffer: a suffix withheld from
     * reasoning would otherwise be re-emitted as answer text.
     */
    enum class Channel { TEXT, REASONING, REFUSAL }

    private class ChannelBuffer {
        val buf = StringBuilder()
        var quotedScanIndex: Int = -1
        var quotedQuoteChar: Char = '\u0000'

        fun clear() {
            buf.setLength(0)
            quotedScanIndex = -1
            quotedQuoteChar = '\u0000'
        }
    }

    private data class ScanOutcome(val cursor: Int, val requiresRedaction: Boolean)

    private val secrets = secrets
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .toMutableList()

    private var guardToken: String = ""
    private var cookieGuardToken: String = ""
    private var skGuardToken: String? = null

    // One mutable buffer per channel: reasoning, answer text and refusals must
    // not share a buffer, or a withheld suffix would be re-emitted on another
    // channel and an interleaved legitimate answer would lose characters.
    private val pending = linkedMapOf<Channel, ChannelBuffer>()

    init {
        refreshGuards()
    }

    private fun refreshGuards() {
        val guard = StreamingSecretRedactorPatterns.selectGuardChar(secrets)
        guardToken = guard.toString()
        cookieGuardToken = " $guard "
        skGuardToken = StreamingSecretRedactorPatterns.selectSkGuardChar(secrets)?.toString()
    }

    fun registerSecrets(newSecrets: List<String>) {
        var modified = false
        for (secret in newSecrets) {
            if (secret.isNotEmpty() && secret !in secrets) {
                secrets.add(secret)
                modified = true
            }
        }
        if (modified) {
            secrets.sortByDescending { it.length }
            refreshGuards()
            for (channelBuffer in pending.values) {
                channelBuffer.quotedScanIndex = -1
            }
        }
    }

    fun accept(input: String, channel: Channel = Channel.TEXT): String {
        if (input.isEmpty()) return ""
        val state = pending.getOrPut(channel) { ChannelBuffer() }
        if (input.length <= MAX_PENDING_CHARS - state.buf.length) return acceptChunk(input, state)

        // Unclosed quoted values may need their exact original text at EOF. Give the scanner one
        // character of lookahead to close or compact the candidate. If it still needs more, fail
        // the stream explicitly rather than expose a prefix or accumulate unlimited provider text.
        return buildString {
            var offset = 0
            while (offset < input.length) {
                val count = minOf(input.length - offset, MAX_PENDING_CHARS + 1 - state.buf.length)
                append(acceptChunk(input.substring(offset, offset + count), state))
                if (state.buf.length > MAX_PENDING_CHARS) {
                    discard()
                    throw RedactionBufferLimitExceeded()
                }
                offset += count
            }
        }
    }

    private fun acceptChunk(input: String, state: ChannelBuffer): String {
        if (state.buf.isEmpty()) {
            return acceptWithoutPending(input, state)
        }
        state.buf.append(input)
        if (tryContinueOpenQuote(state)) {
            return ""
        }
        return drainChannelBuffer(state)
    }

    private fun acceptWithoutPending(input: String, state: ChannelBuffer): String {
        val scan = scanSequence(input)
        if (scan.cursor == input.length) {
            return if (scan.requiresRedaction) SecretRedactor.redact(input, secrets) else input
        }
        state.buf.append(input, scan.cursor, input.length)
        updateOpenTailState(state)
        if (scan.cursor == 0) return ""
        val ready = input.substring(0, scan.cursor)
        return if (scan.requiresRedaction) SecretRedactor.redact(ready, secrets) else ready
    }

    private fun drainChannelBuffer(state: ChannelBuffer): String {
        val buf = state.buf
        val scan = scanSequence(buf)
        if (scan.cursor == buf.length) {
            val ready = buf.toString()
            state.clear()
            return if (scan.requiresRedaction) SecretRedactor.redact(ready, secrets) else ready
        }
        if (scan.cursor == 0) {
            updateOpenTailState(state)
            return ""
        }
        val ready = buf.substring(0, scan.cursor)
        buf.delete(0, scan.cursor)
        state.quotedScanIndex = -1
        updateOpenTailState(state)
        return if (scan.requiresRedaction) SecretRedactor.redact(ready, secrets) else ready
    }

    private fun tryContinueOpenQuote(state: ChannelBuffer): Boolean {
        val start = state.quotedScanIndex
        if (start < 0) return false
        val quote = state.quotedQuoteChar
        val buf = state.buf
        for (idx in start until buf.length) {
            val ch = buf[idx]
            if (ch == quote || ch == '\r' || ch == '\n') {
                state.quotedScanIndex = -1
                return false
            }
        }
        state.quotedScanIndex = buf.length
        return true
    }

    private fun updateOpenTailState(state: ChannelBuffer) {
        val buf = state.buf
        StreamingSecretRedactorPatterns.compactOpenTailInPlace(
            buf = buf,
            secrets = secrets,
            guardToken = guardToken,
            cookieGuardToken = cookieGuardToken,
            skGuardToken = skGuardToken,
        )
        val maxSecretLen = secrets.firstOrNull()?.length ?: 0
        val quote = if (buf.length >= maxSecretLen) {
            StreamingSecretRedactorPatterns.detectOpenQuoteChar(buf)
        } else {
            '\u0000'
        }
        if (quote != '\u0000') {
            state.quotedQuoteChar = quote
            state.quotedScanIndex = buf.length
        } else {
            state.quotedScanIndex = -1
            state.quotedQuoteChar = '\u0000'
        }
    }

    private fun scanSequence(text: CharSequence): ScanOutcome {
        var cursor = 0
        var requiresRedaction = false
        while (cursor < text.length) {
            val secret = matchSecretAt(text, cursor)
            if (secret != null) {
                if (hasHigherPrecedencePrefix(text, cursor, cursor + secret.length, 0, secret.length + 1)) {
                    break
                }
                requiresRedaction = true
                cursor += secret.length
                continue
            }
            if (isSecretProperPrefixAt(text, cursor, 2) ||
                StreamingSecretRedactorPatterns.isOpenPatternPrefix(text, cursor)
            ) {
                break
            }
            val closed = StreamingSecretRedactorPatterns.closedPatternMatch(text, cursor)
            if (closed != null) {
                if (hasHigherPrecedencePrefix(text, cursor, closed.end, closed.rank - 1)) {
                    break
                }
                requiresRedaction = true
                cursor = closed.end
                continue
            }
            cursor++
        }
        return ScanOutcome(cursor, requiresRedaction)
    }

    private fun matchSecretAt(text: CharSequence, cursor: Int): String? {
        if (secrets.isEmpty()) return null
        val first = text[cursor]
        val remaining = text.length - cursor
        for (secret in secrets) {
            if (secret.length <= remaining && secret[0] == first && text.regionMatches(cursor, secret, 0, secret.length)) {
                return secret
            }
        }
        return null
    }

    private fun isSecretProperPrefixAt(text: CharSequence, index: Int, minSecretLength: Int): Boolean {
        if (secrets.isEmpty()) return false
        val remaining = text.length - index
        if (remaining <= 0 || remaining >= secrets[0].length) return false
        val first = text[index]
        for (secret in secrets) {
            if (secret.length < minSecretLength || remaining >= secret.length) break
            if (secret[0] == first && text.regionMatches(index, secret, 0, remaining)) return true
        }
        return false
    }

    private fun hasHigherPrecedencePrefix(
        text: CharSequence,
        start: Int,
        endExclusive: Int,
        maxPatternRank: Int,
        minSecretLength: Int = 2,
    ): Boolean {
        for (idx in start until endExclusive) {
            if (isSecretProperPrefixAt(text, idx, minSecretLength)) return true
            if (idx > start && maxPatternRank >= 1 &&
                StreamingSecretRedactorPatterns.isOpenPatternPrefix(text, idx, maxPatternRank)
            ) {
                return true
            }
        }
        return false
    }

    /** Channel that owns the withheld suffix, if any. */
    fun pendingChannel(): Channel? = pending.entries.firstOrNull { it.value.buf.isNotEmpty() }?.key

    fun hasPending(): Boolean = pending.values.any { it.buf.isNotEmpty() }

    /** Total or per-channel withheld character count currently buffered. */
    fun pendingLength(channel: Channel? = null): Int =
        if (channel != null) pending[channel]?.buf?.length ?: 0
        else pending.values.sumOf { it.buf.length }

    /** Flush every channel's withheld suffix; only valid after normal completion. */
    fun finish(): List<Pair<Channel, String>> {
        val tails = pending.entries
            .filter { it.value.buf.isNotEmpty() }
            .map { it.key to SecretRedactor.redact(it.value.buf.toString(), secrets) }
        pending.clear()
        return tails
    }

    /** Drop an unconfirmed suffix on error, cancellation, or an incomplete EOF. */
    fun discard() {
        pending.clear()
    }

    private companion object {
        const val MAX_PENDING_CHARS = 32 * 1024
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
