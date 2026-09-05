// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Create the bounded, app-private workspace executor for one immutable Agent snapshot.
 *
 * The workspace is deliberately not a shell and never accepts a host path.  Its namespace
 * is derived from the Agent and snapshot identifiers, while all public results contain only
 * paths relative to that opaque namespace.  Reads are approval-gated as well as writes: a read
 * can disclose private app data to the remote model even though it does not mutate local state.
 */
fun workspaceAppTools(
    container: AppContainer,
    context: Context,
    snapshot: AgentSnapshot,
): ToolExecutor = WorkspaceAppTools(
    appPrivateRoot = context.filesDir,
    snapshot = snapshot,
    snapshotStillExists = {
        runCatching { container.agents.getSnapshot(snapshot.id) }
            .getOrNull()?.agentId == snapshot.agentId
    },
    agentStillExists = {
        runCatching { container.agents.get(snapshot.agentId) }.getOrNull() != null
    },
)

/**
 * Phase 1A structured text workspace.  This class intentionally has no ProcessBuilder,
 * Runtime.exec, shell, SAF, Termux, ADB, Device Owner, or host filesystem interface.
 */
internal class WorkspaceAppTools(
    private val appPrivateRoot: File,
    private val snapshot: AgentSnapshot,
    private val snapshotStillExists: () -> Boolean,
    private val agentStillExists: () -> Boolean,
) : ToolExecutor {
    private data class Pending(val call: ToolCall, val args: JsonObject)

    private data class Usage(
        val files: Int,
        val bytes: Long,
        val entries: Int,
    )

    private class InvalidWorkspacePath : Exception()
    private class InvalidWorkspaceContent : Exception()
    private class WorkspaceLimitExceeded : Exception()
    private class WorkspaceUnavailable : Exception()

    private val pending = linkedMapOf<String, Pending>()
    private val usedCallIds = linkedSetOf<String>()

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = LIST,
            description = "列出当前 Agent 快照的应用私有 workspace 子目录。只返回 workspace 相对路径；读取内容前需要逐次确认。",
            parametersJson = LIST_PARAMETERS,
            capability = "workspace.read",
            sideEffect = true,
        ),
        ToolSpec(
            name = READ,
            description = "读取当前 Agent 快照 workspace 中的 UTF-8 文本文件。结果只包含相对路径和有界文本；每次读取都需要逐次确认。",
            parametersJson = READ_PARAMETERS,
            capability = "workspace.read",
            sideEffect = true,
        ),
        ToolSpec(
            name = WRITE,
            description = "在当前 Agent 快照 workspace 中原子创建或覆盖 UTF-8 文本文件。不会访问宿主绝对路径；每次写入都需要逐次确认。",
            parametersJson = WRITE_PARAMETERS,
            capability = "workspace.write",
            sideEffect = true,
        ),
        ToolSpec(
            name = CREATE_DIRECTORY,
            description = "在当前 Agent 快照 workspace 中创建目录。不会访问宿主绝对路径；每次创建都需要逐次确认。",
            parametersJson = DIRECTORY_PARAMETERS,
            capability = "workspace.write",
            sideEffect = true,
        ),
        ToolSpec(
            name = DELETE,
            description = "在当前 Agent 快照 workspace 中删除一个普通文件或空目录。不会递归删除、不会删除 workspace 根目录；每次删除都需要逐次确认。",
            parametersJson = DIRECTORY_PARAMETERS,
            capability = "workspace.write",
            sideEffect = true,
        ),
    )

    override suspend fun invoke(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        synchronized(WORKSPACE_LOCK) {
            if (call.callId.isBlank()) return@synchronized ToolResult.Invalid("Workspace call ID is missing")
            if (!usedCallIds.add(call.callId)) {
                return@synchronized ToolResult.Invalid("Workspace call ID was already used")
            }
            val spec = specs.singleOrNull { it.name == call.name }
                ?: return@synchronized ToolResult.Invalid("Unknown workspace tool")
            if (call.argumentsJson.toByteArray(Charsets.UTF_8).size > MAX_ARGUMENT_BYTES) {
                return@synchronized ToolResult.Invalid("Workspace arguments exceed the limit")
            }
            val args = parseArguments(call.argumentsJson)
                ?: return@synchronized ToolResult.Invalid("Workspace arguments must be a JSON object")
            validateArguments(spec, args)?.let { return@synchronized ToolResult.Invalid(it) }
            if (!bindingIsCurrent()) {
                return@synchronized ToolResult.Denied("Agent or snapshot is unavailable; workspace access is disabled")
            }
            pending[call.callId] = Pending(call, args)
            // Reads are approval-gated because their result is subsequently supplied to the model.
            ToolResult.NeedsApproval
        }
    }

    override suspend fun approve(callId: String): ToolResult = withContext(Dispatchers.IO) {
        synchronized(WORKSPACE_LOCK) {
            val pendingCall = pending.remove(callId)
                ?: return@synchronized ToolResult.Invalid("No pending workspace approval")
            // A pending approval never grants a durable capability.  Re-check both sides of the
            // immutable binding after the user decision and before touching the workspace.
            if (!bindingIsCurrent()) {
                return@synchronized ToolResult.Denied("Agent or snapshot changed; workspace approval expired")
            }
            runCatching { execute(pendingCall.call.name, pendingCall.args) }.fold(
                onSuccess = { ToolResult.Value(it) },
                onFailure = { error -> mapFailure(error) },
            )
        }
    }

    private fun bindingIsCurrent(): Boolean =
        runCatching { snapshotStillExists() && agentStillExists() }.getOrDefault(false)

    /**
     * Approval-gated one-shots keep pending calls only, never a completed-call
     * authorization record, so a cached payload cannot be revalidated here.
     * Deny disclosure fail-closed (b07 follow-up finding A); the model must
     * issue a new call id through the approval path.
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean = false

    private fun execute(name: String, args: JsonObject): String = when (name) {
        LIST -> list(args)
        READ -> read(args)
        WRITE -> write(args)
        CREATE_DIRECTORY -> createDirectory(args)
        DELETE -> delete(args)
        else -> throw InvalidWorkspaceContent()
    }

    private fun list(args: JsonObject): String {
        val segments = pathSegments(args.stringValue("path") ?: "", allowRoot = true)
        val directory = resolve(segments)
        if (!directory.exists()) return output(buildJsonObject {
            put("path", relativePath(segments))
            put("entries", buildJsonArray { })
        })
        ensureDirectory(directory)
        val children = directory.listFiles()?.sortedBy { it.name } ?: throw WorkspaceUnavailable()
        if (children.size > MAX_DIRECTORY_ENTRIES) throw WorkspaceLimitExceeded()
        val result = buildJsonObject {
            put("path", relativePath(segments))
            put("entries", buildJsonArray {
                children.forEach { child ->
                    rejectSymbolicLink(child)
                    val childSegments = segments + child.name
                    pathSegments(relativePath(childSegments), allowRoot = false)
                    add(buildJsonObject {
                        put("path", relativePath(childSegments))
                        when {
                            Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) -> put("type", "directory")
                            Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS) -> {
                                put("type", "file")
                                put("bytes", child.length())
                            }
                            else -> throw WorkspaceUnavailable()
                        }
                    })
                }
            })
        }
        return output(result)
    }

    private fun read(args: JsonObject): String {
        val segments = pathSegments(args.stringValue("path") ?: throw InvalidWorkspacePath(), allowRoot = false)
        val requestedBytes = args.intValue("maxBytes") ?: MAX_READ_BYTES
        if (requestedBytes !in 1..MAX_READ_BYTES) throw WorkspaceLimitExceeded()
        val file = resolve(segments)
        rejectSymbolicLink(file)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw WorkspaceUnavailable()
        val size = file.length()
        if (size > MAX_FILE_BYTES || size > requestedBytes) throw WorkspaceLimitExceeded()
        val bytes = readBounded(file, requestedBytes)
        val text = decodeUtf8(bytes)
        return output(buildJsonObject {
            put("path", relativePath(segments))
            put("bytes", bytes.size)
            put("text", text)
        })
    }

    private fun write(args: JsonObject): String {
        val segments = pathSegments(args.stringValue("path") ?: throw InvalidWorkspacePath(), allowRoot = false)
        val text = args.stringValue("text") ?: throw InvalidWorkspaceContent()
        val bytes = encodeUtf8(text)
        if (bytes.size > MAX_FILE_BYTES) throw WorkspaceLimitExceeded()
        ensureWorkspaceDirectory()
        val target = resolve(segments)
        val parent = target.parentFile ?: throw WorkspaceUnavailable()
        ensureDirectory(parent)
        if (target.exists()) {
            rejectSymbolicLink(target)
            if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw WorkspaceUnavailable()
        }
        val usage = inspectWorkspace()
        val wasExisting = target.exists()
        val oldBytes = if (wasExisting) target.length() else 0L
        val newFiles = usage.files + if (wasExisting) 0 else 1
        val newBytes = usage.bytes - oldBytes + bytes.size
        if (newFiles > MAX_FILES || newBytes > MAX_TOTAL_BYTES) throw WorkspaceLimitExceeded()

        ensureWorkspaceDirectory()
        val temporary = File(parent, ".mar-write-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (temporary.length() != bytes.size.toLong()) throw WorkspaceUnavailable()
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // A non-atomic replacement would violate the workspace contract.  Fail closed.
                throw WorkspaceUnavailable()
            } catch (_: UnsupportedOperationException) {
                throw WorkspaceUnavailable()
            }
            if (target.length() != bytes.size.toLong()) throw WorkspaceUnavailable()
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return output(buildJsonObject {
            put("path", relativePath(segments))
            put("bytes", bytes.size)
            put("created", !wasExisting)
        })
    }

    private fun createDirectory(args: JsonObject): String {
        val segments = pathSegments(args.stringValue("path") ?: throw InvalidWorkspacePath(), allowRoot = false)
        ensureWorkspaceDirectory()
        val directory = resolve(segments)
        if (directory.exists()) {
            rejectSymbolicLink(directory)
            if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw WorkspaceUnavailable()
            return output(buildJsonObject {
                put("path", relativePath(segments))
                put("created", false)
            })
        }
        val parent = directory.parentFile ?: throw WorkspaceUnavailable()
        ensureDirectory(parent)
        val usage = inspectWorkspace()
        if (usage.entries + 1 > MAX_ENTRIES) throw WorkspaceLimitExceeded()
        if (!directory.mkdir()) throw WorkspaceUnavailable()
        return output(buildJsonObject {
            put("path", relativePath(segments))
            put("created", true)
        })
    }

    private fun delete(args: JsonObject): String {
        val segments = pathSegments(args.stringValue("path") ?: throw InvalidWorkspacePath(), allowRoot = false)
        val target = resolve(segments)
        rejectSymbolicLink(target)
        if (!target.exists()) throw WorkspaceUnavailable()

        val type = when {
            Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) -> "file"
            Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS) -> {
                val children = target.listFiles() ?: throw WorkspaceUnavailable()
                children.forEach(::rejectSymbolicLink)
                if (children.isNotEmpty()) throw WorkspaceUnavailable()
                "directory"
            }
            else -> throw WorkspaceUnavailable()
        }

        // Re-check immediately before deletion.  The app-private workspace is process-owned, but
        // refusing a changed link/type keeps the operation fail-closed if the entry races.
        rejectSymbolicLink(target)
        if (type == "file" && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw WorkspaceUnavailable()
        }
        if (type == "directory" && (!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                (target.listFiles() ?: throw WorkspaceUnavailable()).isNotEmpty())) {
            throw WorkspaceUnavailable()
        }
        if (!Files.deleteIfExists(target.toPath()) || Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw WorkspaceUnavailable()
        }
        return output(buildJsonObject {
            put("path", relativePath(segments))
            put("deleted", true)
            put("type", type)
        })
    }

    private fun inspectWorkspace(): Usage {
        val root = ensureWorkspaceDirectory()
        var files = 0
        var bytes = 0L
        var entries = 0
        fun visit(directory: File, depth: Int) {
            if (depth > MAX_PATH_DEPTH) throw WorkspaceLimitExceeded()
            val children = directory.listFiles() ?: throw WorkspaceUnavailable()
            if (children.size > MAX_DIRECTORY_ENTRIES) throw WorkspaceLimitExceeded()
            children.forEach { child ->
                rejectSymbolicLink(child)
                entries++
                if (entries > MAX_ENTRIES) throw WorkspaceLimitExceeded()
                when {
                    Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) -> visit(child, depth + 1)
                    Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS) -> {
                        val length = child.length()
                        if (length > MAX_FILE_BYTES) throw WorkspaceLimitExceeded()
                        files++
                        bytes += length
                        if (files > MAX_FILES || bytes > MAX_TOTAL_BYTES) throw WorkspaceLimitExceeded()
                    }
                    else -> throw WorkspaceUnavailable()
                }
            }
        }
        visit(root, 0)
        return Usage(files, bytes, entries)
    }

    private fun ensureWorkspaceDirectory(): File {
        val directory = workspaceDirectory()
        if (!directory.exists() && !directory.mkdirs()) throw WorkspaceUnavailable()
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw WorkspaceUnavailable()
        return directory
    }

    private fun ensureDirectory(directory: File) {
        rejectSymbolicLink(directory)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw WorkspaceUnavailable()
        val root = workspaceDirectory()
        val canonical = directory.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw InvalidWorkspacePath()
        }
    }

    private fun resolve(segments: List<String>): File {
        val root = workspaceDirectory()
        var current = root
        segments.forEach { segment ->
            current = File(current, segment)
            if (Files.isSymbolicLink(current.toPath())) throw InvalidWorkspacePath()
        }
        val canonical = current.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw InvalidWorkspacePath()
        }
        return canonical
    }

    private fun workspaceDirectory(): File {
        val privateRoot = appPrivateRoot.canonicalFile
        if (Files.isSymbolicLink(appPrivateRoot.toPath())) throw InvalidWorkspacePath()
        val container = File(privateRoot, WORKSPACE_CONTAINER)
        rejectSymbolicLink(container)
        val namespaceDirectory = File(container, workspaceNamespace(snapshot))
        rejectSymbolicLink(namespaceDirectory)
        val canonicalContainer = container.canonicalFile
        if (canonicalContainer != privateRoot && canonicalContainer.parentFile != privateRoot) {
            throw InvalidWorkspacePath()
        }
        val canonicalWorkspace = namespaceDirectory.canonicalFile
        if (canonicalWorkspace != canonicalContainer && canonicalWorkspace.parentFile != canonicalContainer) {
            throw InvalidWorkspacePath()
        }
        return canonicalWorkspace
    }

    private fun rejectSymbolicLink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw InvalidWorkspacePath()
    }

    private fun pathSegments(raw: String, allowRoot: Boolean): List<String> {
        if (raw.isEmpty()) {
            if (allowRoot) return emptyList()
            throw InvalidWorkspacePath()
        }
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES || raw.indexOf('\u0000') >= 0 || raw.indexOf('\\') >= 0) {
            throw InvalidWorkspacePath()
        }
        if (raw.startsWith('/') || raw.endsWith('/') || raw.contains("//")) throw InvalidWorkspacePath()
        val pieces = raw.split('/')
        if (pieces.size > MAX_PATH_DEPTH) throw WorkspaceLimitExceeded()
        if (pieces.any { piece ->
                piece.isBlank() || piece == "." || piece == ".." || piece.contains(':') ||
                    piece.any { character -> character.isISOControl() } ||
                    piece.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_BYTES
            }) throw InvalidWorkspacePath()
        return pieces
    }

    private fun relativePath(segments: List<String>): String = segments.joinToString("/")

    private fun parseArguments(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun validateArguments(spec: ToolSpec, args: JsonObject): String? {
        val properties = Json.parseToJsonElement(spec.parametersJson).jsonObject["properties"]?.jsonObject.orEmpty()
        val required = when (spec.name) {
            LIST -> emptySet()
            WRITE -> setOf("path", "text")
            else -> setOf("path")
        }
        if (required.any { it !in args }) return "Workspace parameter is missing"
        if (args.keys.any { it !in properties.keys }) return "Workspace parameter is unsupported"
        args["path"]?.let {
            val primitive = it as? JsonPrimitive ?: return "Workspace path must be text"
            if (!primitive.isString) return "Workspace path must be text"
            runCatching { pathSegments(primitive.content, allowRoot = spec.name == LIST) }
                .getOrElse { return "Workspace path is invalid" }
        }
        args["text"]?.let {
            val primitive = it as? JsonPrimitive ?: return "Workspace text must be UTF-8 text"
            if (!primitive.isString) return "Workspace text must be UTF-8 text"
            runCatching { encodeUtf8(primitive.content) }.getOrElse { return "Workspace text is invalid UTF-8" }
        }
        args["maxBytes"]?.let {
            val primitive = it as? JsonPrimitive ?: return "Workspace read limit must be an integer"
            if (primitive.isString || primitive.intOrNull == null) return "Workspace read limit must be an integer"
        }
        return null
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.let { value ->
            val primitive = value as? JsonPrimitive ?: return@let null
            if (primitive.isString) primitive.contentOrNull else null
        }

    private fun JsonObject.intValue(key: String): Int? =
        this[key]?.let { value ->
            val primitive = value as? JsonPrimitive ?: return@let null
            if (primitive.isString) null else primitive.intOrNull
        }

    private fun readBounded(file: File, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(file.length().toInt(), maximum) + 1)
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > maximum) throw WorkspaceLimitExceeded()
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun encodeUtf8(value: String): ByteArray {
        return try {
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val bytes = encoder.encode(CharBuffer.wrap(value)).let { buffer ->
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
            if (bytes.size > MAX_FILE_BYTES) throw WorkspaceLimitExceeded()
            bytes
        } catch (error: CharacterCodingException) {
            throw InvalidWorkspaceContent()
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw InvalidWorkspaceContent()
        }
    }

    private fun output(value: JsonObject): String {
        val result = value.toString()
        if (result.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES) throw WorkspaceLimitExceeded()
        return result
    }

    private fun mapFailure(error: Throwable): ToolResult = when (error) {
        is InvalidWorkspacePath -> ToolResult.Invalid("Workspace path is invalid or outside the app-private workspace")
        is InvalidWorkspaceContent -> ToolResult.Invalid("Workspace content must be bounded UTF-8 text")
        is WorkspaceLimitExceeded -> ToolResult.Invalid("Workspace resource limit exceeded")
        is WorkspaceUnavailable -> ToolResult.Invalid("Workspace operation is unavailable")
        else -> ToolResult.Invalid("Workspace operation failed")
    }

    companion object {
        private const val WORKSPACE_CONTAINER = "agent-workspaces"
        private const val MAX_ARGUMENT_BYTES = 384 * 1024
        private const val MAX_SEGMENT_BYTES = 120
        private const val MAX_PATH_BYTES = 512
        private const val MAX_PATH_DEPTH = 16
        private const val MAX_FILE_BYTES = 256 * 1024
        private const val MAX_READ_BYTES = 24 * 1024
        private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
        private const val MAX_FILES = 128
        private const val MAX_ENTRIES = 512
        private const val MAX_DIRECTORY_ENTRIES = 256
        private const val MAX_OUTPUT_BYTES = 32 * 1024
        private val WORKSPACE_LOCK = Any()

        private const val LIST = "workspace_list"
        private const val READ = "workspace_read"
        private const val WRITE = "workspace_write"
        private const val CREATE_DIRECTORY = "workspace_create_directory"
        private const val DELETE = "workspace_delete"

        private const val LIST_PARAMETERS =
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"path\":{\"type\":\"string\",\"maxLength\":512}}}"
        private const val READ_PARAMETERS =
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"maxBytes\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":24576}}}"
        private const val WRITE_PARAMETERS =
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\",\"text\"],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"text\":{\"type\":\"string\",\"maxLength\":262144}}}"
        private const val DIRECTORY_PARAMETERS =
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512}}}"

        /** A full SHA-256 namespace prevents Agent/snapshot identifiers becoming paths. */
        internal fun workspaceNamespace(snapshot: AgentSnapshot): String =
            sha256("mobile-agent-runtime/workspace-v1\n${snapshot.agentId}\n${snapshot.id}")

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charset.forName("UTF-8")))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
