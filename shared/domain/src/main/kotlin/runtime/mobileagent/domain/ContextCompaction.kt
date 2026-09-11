// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Durable state of one session-context compaction attempt.
 *
 * This is conversation-continuity bookkeeping, not a model execution attempt: no HTTP request is
 * ever replayed from a record, and a terminal record is never rewritten.
 */
@Serializable
enum class ContextCompactionState {
    PREPARED,
    DISPATCHED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN_OUTCOME,
}

/**
 * One durable session-context compaction attempt.
 *
 * [sourceMessageIds] names the immutable transcript rows this summary covers; [sourceContentHash]
 * is derived from those rows (id/role/text/parts_json/status) and is re-verified before any
 * summary becomes reusable. [inputHash] is supplied by the Runtime and describes the actual
 * summarization model input; the two hashes answer different questions. [summaryJson] is
 * untrusted historical data, never instructions and never a
 * permission or execution channel.
 */
@Serializable
data class ContextCompactionRecord(
    val id: String,
    val conversationId: String,
    val snapshotId: String,
    val runId: String,
    val sourceMessageIds: List<String>,
    val inputHash: String,
    val modelId: String,
    val modelFingerprint: String,
    val authorizationFingerprint: String,
    val reason: String,
    val state: ContextCompactionState = ContextCompactionState.PREPARED,
    val summaryJson: String? = null,
    val parentId: String? = null,
    val version: Int = 1,
    val beforeUnits: Long = 0,
    val afterUnits: Long = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val createdAt: String = Utc.nowIso(),
    val updatedAt: String = createdAt,
    val sourceContentHash: String = "",
) {
    val isTerminal: Boolean
        get() = state in TERMINAL_STATES

    companion object {
        /** Terminal attempts are immutable; a later attempt requires a new record. */
        val TERMINAL_STATES: Set<ContextCompactionState> = setOf(
            ContextCompactionState.SUCCEEDED,
            ContextCompactionState.FAILED,
            ContextCompactionState.CANCELLED,
            ContextCompactionState.UNKNOWN_OUTCOME,
        )
    }
}

/** The durable facts of one transcript row that a source digest covers. */
data class ContextCompactionSource(
    val messageId: String,
    val createdAt: String,
    val ordinal: Long,
    val role: String,
    val text: String,
    val partsJson: String,
    val status: String,
)

object ContextCompactionLimits {
    /** Bounded summary payload; mirrors the durable message/part limits family. */
    const val MAX_SUMMARY_BYTES = 64 * 1024
    const val MAX_SUMMARY_SECTION_ITEMS = 128
    const val MAX_SUMMARY_TEXT_CHARS = 1_000_000
    const val MAX_FIELD_CHARS = 4_096
}

/**
 * Deterministic source digest for a compaction attempt.
 *
 * The digest is over the durable message facts only. Values are length-prefixed so no
 * concatenation of user text, ids or JSON can collide with a different field split, and the
 * scheme tag keeps a future change from being silently accepted as the old layout.
 */
object ContextCompactionDigest {
    private const val SCHEME = "mobileagent.context-compaction.source.v1"

    fun sourceContentHash(sources: List<ContextCompactionSource>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, SCHEME)
        update(digest, sources.size.toString())
        sources.forEach { source ->
            update(digest, source.messageId)
            update(digest, source.role)
            update(digest, source.text)
            update(digest, source.partsJson)
            update(digest, source.status)
        }
        return hex(digest.digest())
    }

    fun sha256Hex(value: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(value))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun update(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(bytes)
    }
}

/**
 * Strict shape of a durable conversation summary.
 *
 * A summary is data only: exactly the five named string-array sections, no extra keys, no numeric
 * or nested payloads, and no execution field. Keeping this validator in the domain layer means the
 * database, the Runtime and the UI cannot drift into different summary grammars.
 */
object ContextSummary {
    val REQUIRED_FIELDS: List<String> = listOf("goals", "constraints", "decisions", "pending", "results")

    private val expectedKeys: Set<String> = REQUIRED_FIELDS.toSet()

    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    /**
     * Validates and re-encodes a summary into its single canonical form (section order fixed,
     * compact encoding). Re-encoding rather than trusting the caller's whitespace keeps the
     * durable bytes and the source of a later replacement digest stable.
     */
    fun canonicalize(raw: String, maxBytes: Int = ContextCompactionLimits.MAX_SUMMARY_BYTES): String {
        if (raw.toByteArray(Charsets.UTF_8).size > maxBytes) {
            throw compactionFailure("summary exceeds $maxBytes bytes")
        }
        val element = runCatching { json.parseToJsonElement(raw) }.getOrElse {
            throw compactionFailure("summary must be a JSON object")
        }
        val obj = element as? JsonObject ?: throw compactionFailure("summary must be a JSON object")
        if (obj.keys != expectedKeys) {
            throw compactionFailure("summary must contain exactly ${REQUIRED_FIELDS.joinToString(",")}")
        }
        var hasText = false
        val canonical = LinkedHashMap<String, JsonArray>(REQUIRED_FIELDS.size)
        REQUIRED_FIELDS.forEach { field ->
            val values = obj[field] as? JsonArray
                ?: throw compactionFailure("summary section $field must be an array")
            if (values.size > ContextCompactionLimits.MAX_SUMMARY_SECTION_ITEMS) {
                throw compactionFailure("summary section $field exceeds its item limit")
            }
            val items = values.map { value ->
                if (value !is JsonPrimitive || !value.isString) {
                    throw compactionFailure("summary section $field must contain only strings")
                }
                if (value.content.length > ContextCompactionLimits.MAX_SUMMARY_TEXT_CHARS) {
                    throw compactionFailure("summary section $field contains an oversized entry")
                }
                if (value.content.isNotBlank()) hasText = true
                JsonPrimitive(value.content)
            }
            canonical[field] = JsonArray(items)
        }
        if (!hasText) throw compactionFailure("summary must contain at least one non-blank entry")
        val encoded = JsonObject(canonical).toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > maxBytes) {
            throw compactionFailure("summary exceeds $maxBytes bytes")
        }
        return encoded
    }

    fun validate(raw: String, maxBytes: Int = ContextCompactionLimits.MAX_SUMMARY_BYTES): String =
        canonicalize(raw, maxBytes)

    private fun compactionFailure(message: String): IllegalArgumentException =
        IllegalArgumentException("CONTEXT_COMPACTION_FAILED: $message")
}
