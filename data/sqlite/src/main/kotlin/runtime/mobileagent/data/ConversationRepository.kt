// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessagePart
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.domain.ToolCallPart
import runtime.mobileagent.domain.ToolResultPart
import runtime.mobileagent.domain.CitationPart
import runtime.mobileagent.domain.DiffPart
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.MessagePartLimits
import runtime.mobileagent.domain.ReasoningPart
import runtime.mobileagent.domain.Utc

/** Conversation and typed message persistence. Message content is append-only; status is mutable. */
class ConversationRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun create(
        snapshotId: String,
        title: String,
        conversationId: String = EntityId.random().value,
        at: String = clock(),
    ): Conversation {
        requireId(snapshotId, "snapshotId")
        requireText(title, "title")
        requireId(conversationId, "conversationId")
        if (db.query("SELECT id FROM agent_snapshots WHERE id=?", listOf(snapshotId)).isEmpty()) {
            throw invalid("Agent snapshot $snapshotId does not exist")
        }
        if (db.query("SELECT id FROM conversations WHERE id=?", listOf(conversationId)).isNotEmpty()) {
            throw invalid("Conversation $conversationId already exists")
        }
        val conversation = Conversation(conversationId, snapshotId, title.trim(), at, at)
        db.execute(
            "INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            listOf(conversation.id, conversation.snapshotId, conversation.snapshotId, conversation.title, conversation.createdAt, conversation.updatedAt),
        )
        return conversation
    }

    fun createConversation(
        snapshotId: String,
        title: String,
        conversationId: String = EntityId.random().value,
        at: String = clock(),
    ): Conversation = create(snapshotId, title, conversationId, at)

    /** Compatibility overload for callers that already assembled the durable Conversation value. */
    fun create(conversation: Conversation): Conversation =
        create(conversation.snapshotId, conversation.title, conversation.id, conversation.createdAt)

    fun createConversation(conversation: Conversation): Conversation = create(conversation)

    fun get(id: String): Conversation? =
        db.query("SELECT * FROM conversations WHERE id=?", listOf(id)).singleOrNull()?.toConversation()

    fun conversation(id: String): Conversation? = get(id)

    fun list(): List<Conversation> =
        db.query("SELECT * FROM conversations ORDER BY updated_at DESC,id").map { it.toConversation() }

    fun delete(id: String): Boolean {
        if (get(id) == null) return false
        if (db.query("SELECT run_id FROM runs WHERE conversation_id=? LIMIT 1", listOf(id)).isNotEmpty()) return false
        db.transaction {
            db.execute("DELETE FROM message_parts WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)", listOf(id))
            db.execute("DELETE FROM messages WHERE conversation_id=?", listOf(id))
            db.execute("DELETE FROM conversations WHERE id=?", listOf(id))
        }
        return true
    }

    fun appendMessage(message: Message): Message = db.transaction {
        validateMessage(message)
        val durable = if (message.parts.isEmpty() && message.text.isNotEmpty()) {
            message.copy(parts = listOf(TextPart(message.text)))
        } else {
            message
        }
        if (db.query("SELECT id FROM conversations WHERE id=?", listOf(durable.conversationId)).singleOrNull() == null) {
            throw invalid("Conversation ${durable.conversationId} does not exist")
        }
        if (durable.parentMessageId != null) {
            val parent = db.query("SELECT conversation_id FROM messages WHERE id=?", listOf(durable.parentMessageId)).singleOrNull()
                ?: throw invalid("Parent message ${durable.parentMessageId} does not exist")
            if (parent.string("conversation_id") != durable.conversationId) throw invalid("Message parent belongs to another conversation")
        }
        val existing = db.query("SELECT * FROM messages WHERE id=?", listOf(durable.id)).singleOrNull()
        if (existing != null) {
            val stored = existing.toMessage(json)
            if (stored != durable) throw invalid("Message ${durable.id} is immutable")
            return@transaction stored
        }
        val parts = durable.parts
        db.execute(
            "INSERT INTO messages(id,conversation_id,parent_message_id,role,text,status,created_at,parts_json,metadata_json) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf(
                durable.id,
                durable.conversationId,
                durable.parentMessageId,
                durable.role.name,
                durable.text,
                durable.status,
                durable.createdAt,
                json.encodeToString(parts),
                durable.metadataJson,
            ),
        )
        parts.forEachIndexed { index, part ->
            db.execute(
                "INSERT INTO message_parts(id,message_id,ordinal,part_type,payload_json) VALUES(?,?,?,?,?)",
                listOf(
                    "${durable.id}:$index",
                    durable.id,
                    index,
                    partType(part),
                    json.encodeToString(part),
                ),
            )
        }
        db.execute("UPDATE conversations SET updated_at=? WHERE id=?", listOf(durable.createdAt, durable.conversationId))
        durable
    }

    /**
     * Persist a partial assistant response without creating a second message row.  Only an
     * existing ASSISTANT message whose current status is STREAMING can be changed; once a caller
     * stores a terminal status (including UNKNOWN_OUTCOME), later checkpoints are ignored and the
     * terminal row is returned unchanged.
     */
    fun checkpointAssistant(
        messageId: String,
        text: String,
        parts: List<MessagePart>,
        metadataJson: String = "{}",
        status: String = "STREAMING",
    ): Message = db.transaction {
        val existing = db.query("SELECT * FROM messages WHERE id=?", listOf(messageId)).singleOrNull()
            ?: throw invalid("Message $messageId does not exist")
        val stored = existing.toMessage(json)
        if (stored.role != runtime.mobileagent.domain.MessageRole.ASSISTANT) {
            throw invalid("Only assistant messages may be checkpointed")
        }
        if (stored.status != STREAMING_STATUS) return@transaction stored
        val updated = stored.copy(text = text, parts = parts, metadataJson = metadataJson, status = status)
        validateMessage(updated)
        db.execute(
            "UPDATE messages SET text=?,status=?,parts_json=?,metadata_json=? WHERE id=?",
            listOf(updated.text, updated.status, json.encodeToString(updated.parts), updated.metadataJson, updated.id),
        )
        db.execute("DELETE FROM message_parts WHERE message_id=?", listOf(updated.id))
        updated.parts.forEachIndexed { index, part ->
            db.execute(
                "INSERT INTO message_parts(id,message_id,ordinal,part_type,payload_json) VALUES(?,?,?,?,?)",
                listOf("${updated.id}:$index", updated.id, index, partType(part), json.encodeToString(part)),
            )
        }
        db.execute("UPDATE conversations SET updated_at=? WHERE id=?", listOf(clock(), updated.conversationId))
        updated
    }

    fun checkpoint(
        messageId: String,
        text: String,
        parts: List<MessagePart>,
        metadataJson: String = "{}",
        status: String = STREAMING_STATUS,
    ): Message = checkpointAssistant(messageId, text, parts, metadataJson, status)

    fun append(
        conversationId: String,
        role: runtime.mobileagent.domain.MessageRole,
        text: String = "",
        status: String = "COMPLETE",
        parentMessageId: String? = null,
        parts: List<MessagePart> = if (text.isBlank()) emptyList() else listOf(TextPart(text)),
        messageId: String = EntityId.random().value,
        createdAt: String = clock(),
        metadataJson: String = "{}",
    ): Message = appendMessage(
        Message(messageId, conversationId, parentMessageId, role, text, status, createdAt, parts, metadataJson),
    )

    fun message(id: String): Message? =
        db.query("SELECT * FROM messages WHERE id=?", listOf(id)).singleOrNull()?.toMessage(json)

    fun messages(conversationId: String): List<Message> =
        // UUIDs are intentionally opaque and do not provide a conversation order.  Keep timestamp
        // ordering for normal history, then use SQLite's durable row insertion order to keep an
        // assistant/tool pair deterministic when one request writes both at the same instant.
        db.query("SELECT * FROM messages WHERE conversation_id=? ORDER BY created_at,rowid", listOf(conversationId))
            .map { it.toMessage(json) }

    fun listMessages(conversationId: String): List<Message> = messages(conversationId)

    fun updateMessageStatus(messageId: String, status: String): Boolean {
        requireText(status, "status")
        val changed = db.query("SELECT id FROM messages WHERE id=?", listOf(messageId)).isNotEmpty()
        if (!changed) return false
        db.execute("UPDATE messages SET status=? WHERE id=?", listOf(status, messageId))
        return true
    }

    private fun validateMessage(message: Message) {
        requireId(message.id, "message.id")
        requireId(message.conversationId, "message.conversationId")
        message.parentMessageId?.let { requireId(it, "message.parentMessageId") }
        requireText(message.status, "message.status")
        requireText(message.createdAt, "message.createdAt")
        if (message.text.length > MAX_TEXT) throw invalid("Message text is too long")
        parseObject(message.metadataJson, "message.metadataJson")
        if (message.parts.size > MessagePartLimits.MAX_PART_COUNT) {
            throw invalid("Message contains too many parts")
        }
        var encodedPartBytes = 0L
        message.parts.forEach { part ->
            val encoded = runCatching { json.encodeToString<MessagePart>(part) }
                .getOrElse { throw invalid("Message part cannot be serialized") }
            encodedPartBytes += encoded.toByteArray(Charsets.UTF_8).size
            if (encodedPartBytes > MessagePartLimits.MAX_TOTAL_ENCODED_BYTES) {
                throw invalid("Message parts exceed the durable size limit")
            }
            when (part) {
                is TextPart -> if (part.value.length > MAX_TEXT) throw invalid("Text message part is too long")
                is ReasoningPart -> Unit // Constructor enforces bounded, non-empty provider content.
                is ImagePart -> requireId(part.assetId, "image.assetId")
                is ToolCallPart -> {
                    requireId(part.callId, "tool.callId")
                    requireText(part.name, "tool.name")
                    parseObject(part.argumentsJson, "tool.argumentsJson")
                }
                is ToolResultPart -> {
                    requireId(part.callId, "toolResult.callId")
                    requireText(part.status, "toolResult.status")
                    if (part.resultJson.length > MAX_TEXT) throw invalid("Tool result is too long")
                    parseObject(part.resultJson, "toolResult.resultJson")
                }
                is DiffPart -> Unit // Constructor enforces bounded, path-safe display data.
                is ErrorPart -> Unit // Constructor and enum enforce a safe typed error surface.
                is CitationPart -> requireId(part.citationId, "citation.citationId")
            }
        }
    }

    private fun parseObject(raw: String, field: String) {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrElse { throw invalid("$field must be a JSON object") }
        if (element !is kotlinx.serialization.json.JsonObject) throw invalid("$field must be a JSON object")
    }

    private fun partType(part: MessagePart): String = when (part) {
        is TextPart -> "text"
        is ReasoningPart -> "reasoning"
        is ImagePart -> "image"
        is ToolCallPart -> "tool_call"
        is ToolResultPart -> "tool_result"
        is DiffPart -> "diff"
        is ErrorPart -> "error"
        is CitationPart -> "citation"
    }

    private fun SqlRow.toConversation(): Conversation = Conversation(
        id = string("id"),
        snapshotId = string("snapshot_id"),
        title = string("title"),
        createdAt = string("created_at"),
        updatedAt = string("updated_at"),
    )

    private fun SqlRow.toMessage(json: Json): Message {
        val rawParts = string("parts_json").ifBlank { "[]" }
        val decoded = runCatching { json.decodeFromString<List<MessagePart>>(rawParts) }.getOrElse {
            throw invalid("Persisted message parts are invalid")
        }
        val parts = if (decoded.isEmpty() && string("text").isNotBlank()) listOf(TextPart(string("text"))) else decoded
        val message = Message(
            id = string("id"),
            conversationId = string("conversation_id"),
            parentMessageId = string("parent_message_id").ifBlank { null },
            role = runCatching { runtime.mobileagent.domain.MessageRole.valueOf(string("role")) }
                .getOrElse { throw invalid("Persisted message role is invalid") },
            text = string("text"),
            status = string("status"),
            createdAt = string("created_at"),
            parts = parts,
            metadataJson = string("metadata_json").ifBlank { "{}" },
        )
        // Re-validate rows on read as well as on write.  This keeps hand-edited or partially
        // migrated parts fail-closed instead of allowing malformed JSON into the runtime.
        validateMessage(message)
        return message
    }

    private fun requireId(value: String, field: String) {
        requireText(value, field)
        if (!SAFE_ID.matches(value)) throw invalid("$field contains unsafe characters")
    }

    private fun requireText(value: String, field: String) {
        if (value.isBlank() || value.length > MAX_TEXT) throw invalid("$field is empty or too long")
    }

    private fun invalid(message: String): AppException = AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "conversation-persistence",
        operationId = "conversation-write",
        sanitizedDetails = message,
    ).asException()

    companion object {
        private const val MAX_TEXT = 1_000_000
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
        private const val STREAMING_STATUS = "STREAMING"
    }
}
