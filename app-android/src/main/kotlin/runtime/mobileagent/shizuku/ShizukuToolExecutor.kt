// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Agent-bound typed tools for the optional Shizuku workspace.
 *
 * All five operations are approval-gated, including reads because their output
 * is subsequently supplied to the model.  The executor owns one-time call IDs
 * and re-checks the Agent/snapshot callbacks at approval time.
 */
internal class ShizukuToolExecutor(
    private val bridge: ShizukuAuthorityBridge,
    private val snapshot: AgentSnapshot,
    private val snapshotStillExists: () -> Boolean,
    private val agentStillExists: () -> Boolean,
) : ToolExecutor {
    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val usedCallIds = linkedSetOf<String>()

    /** Not-ready bridges expose no executable tools to the model. */
    override val specs: List<ToolSpec>
        get() = if (bridge.refresh().ready) TOOL_SPECS else emptyList()

    override suspend fun invoke(call: ToolCall): ToolResult {
        synchronized(lock) {
            if (call.callId.isBlank()) return ToolResult.Invalid("Shizuku call ID is missing")
            if (call.callId.toByteArray(StandardCharsets.UTF_8).size > MAX_CALL_ID_BYTES) {
                return ToolResult.Invalid("Shizuku call ID is too long")
            }
            if (call.callId in usedCallIds) return ToolResult.Invalid("Shizuku call ID was already used")
            if (usedCallIds.size >= MAX_TRACKED_CALLS) return ToolResult.Invalid("Shizuku call limit exceeded")
            if (!bindingIsCurrent()) return ToolResult.Denied("Agent or snapshot is unavailable")
            if (!bridge.refresh().ready) return ToolResult.Denied("Shizuku bridge is not ready")
            if (call.argumentsJson.toByteArray(StandardCharsets.UTF_8).size > MAX_ARGUMENT_BYTES) {
                return ToolResult.Invalid("Shizuku arguments are too large")
            }

            val parsed = parse(call) ?: return ToolResult.Invalid("Invalid Shizuku tool arguments")
            usedCallIds += call.callId
            pending[call.callId] = parsed
            return ToolResult.NeedsApproval
        }
    }

    override suspend fun approve(callId: String): ToolResult {
        val operation = synchronized(lock) {
            pending.remove(callId)
        } ?: return ToolResult.Invalid("No pending Shizuku approval")

        if (!bindingIsCurrent()) return ToolResult.Denied("Agent or snapshot changed; approval expired")

        val result = runInterruptible(Dispatchers.IO) {
            when (operation.name) {
                LIST -> bridge.dispatchList(operation.path)
                READ -> bridge.dispatchRead(operation.path, operation.maxBytes)
                WRITE -> bridge.dispatchWrite(operation.path, operation.content!!, operation.replaceExisting)
                MKDIR -> bridge.dispatchMkdir(operation.path)
                DELETE -> bridge.dispatchDelete(operation.path)
                else -> ShizukuDispatchResult.Denied("Unknown Shizuku operation")
            }
        }
        return when (result) {
            is ShizukuDispatchResult.Success -> ToolResult.Value(result.payload)
            is ShizukuDispatchResult.Denied -> ToolResult.Denied(result.reason)
            is ShizukuDispatchResult.Failed -> if (result.unknownOutcome) {
                ToolResult.UnknownOutcome(result.reason)
            } else {
                ToolResult.Invalid(result.reason)
            }
        }
    }

    private fun bindingIsCurrent(): Boolean =
        runCatching { snapshotStillExists() && agentStillExists() }.getOrDefault(false)

    private fun parse(call: ToolCall): Pending? {
        val args = runCatching { Json.parseToJsonElement(call.argumentsJson) as? JsonObject }.getOrNull()
            ?: return null
        val allowed = when (call.name) {
            LIST, READ, MKDIR, DELETE -> setOf("path", "maxBytes").let { if (call.name == LIST || call.name == MKDIR || call.name == DELETE) it - "maxBytes" else it }
            WRITE -> setOf("path", "text", "replaceExisting")
            else -> return null
        }
        if (args.keys.any { it !in allowed }) return null

        val path = args.string("path") ?: if (call.name == LIST && "path" !in args) "" else return null
        if (path.isEmpty() && call.name != LIST) return null
        if (!ShizukuWorkspacePathPolicy.isValid(path, allowRoot = call.name == LIST)) return null

        return when (call.name) {
            LIST -> Pending(call.name, path, maxBytes = 0)
            READ -> {
                val maxBytes = args.int("maxBytes") ?: ShizukuWorkspaceFileStore.MAX_READ_BYTES
                if (!ShizukuBridgePolicy.validReadLimit(maxBytes)) null else Pending(call.name, path, maxBytes = maxBytes)
            }
            WRITE -> {
                val text = args.string("text") ?: return null
                val replaceExisting = args.boolean("replaceExisting") ?: return null
                val content = encodeUtf8(text) ?: return null
                if (content.size > ShizukuWorkspaceFileStore.MAX_FILE_BYTES) null
                else Pending(call.name, path, content = content, replaceExisting = replaceExisting)
            }
            MKDIR, DELETE -> Pending(call.name, path, maxBytes = 0)
            else -> null
        }
    }

    private fun encodeUtf8(value: String): ByteArray? = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }

    private data class Pending(
        val name: String,
        val path: String,
        val maxBytes: Int = 0,
        val content: ByteArray? = null,
        val replaceExisting: Boolean = false,
    )

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return if (value.isString) value.contentOrNull else null
    }

    private fun JsonObject.int(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        return if (value.isString) null else value.intOrNull
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        val value = this[key] as? JsonPrimitive ?: return null
        return if (value.isString) null else value.booleanOrNull
    }

    private companion object {
        const val MAX_ARGUMENT_BYTES = 384 * 1024
        const val MAX_CALL_ID_BYTES = 256
        const val MAX_TRACKED_CALLS = 512
        const val LIST = "shizuku_workspace_list"
        const val READ = "shizuku_workspace_read"
        const val WRITE = "shizuku_workspace_write"
        const val MKDIR = "shizuku_workspace_mkdir"
        const val DELETE = "shizuku_workspace_delete"

        val TOOL_SPECS = listOf(
            ToolSpec(
                name = LIST,
                description = "List the fixed Shizuku Download workspace using a relative path.",
                parametersJson = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"path\":{\"type\":\"string\",\"maxLength\":512}}}",
                capability = "shizuku.workspace.read",
                sideEffect = true,
            ),
            ToolSpec(
                name = READ,
                description = "Read bounded UTF-8 text from the fixed Shizuku Download workspace.",
                parametersJson = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"maxBytes\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":24576}}}",
                capability = "shizuku.workspace.read",
                sideEffect = true,
            ),
            ToolSpec(
                name = WRITE,
                description = "Create or replace bounded UTF-8 text in the fixed Shizuku Download workspace.",
                parametersJson = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\",\"text\",\"replaceExisting\"],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"text\":{\"type\":\"string\",\"maxLength\":262144},\"replaceExisting\":{\"type\":\"boolean\"}}}",
                capability = "shizuku.workspace.write",
                sideEffect = true,
            ),
            ToolSpec(
                name = MKDIR,
                description = "Create one directory in the fixed Shizuku Download workspace.",
                parametersJson = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512}}}",
                capability = "shizuku.workspace.write",
                sideEffect = true,
            ),
            ToolSpec(
                name = DELETE,
                description = "Delete one file or an empty directory in the fixed Shizuku Download workspace.",
                parametersJson = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512}}}",
                capability = "shizuku.workspace.write",
                sideEffect = true,
            ),
        )
    }
}
