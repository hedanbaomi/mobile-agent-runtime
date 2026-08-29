// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.RequestHeaderValue

/** Protocol revision pinned to the official MCP specification used by M7. */
const val MCP_PROTOCOL_VERSION_2025_06_18: String = "2025-06-18"

private const val DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024L * 1024L

sealed interface McpTransportResponse {
    data class Messages(
        val messages: List<JsonObject>,
        val sessionId: String? = null,
    ) : McpTransportResponse

    data class Accepted(val sessionId: String? = null) : McpTransportResponse
}

class McpTransportException(
    val httpStatus: Int?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A transport seam used by [RemoteMcpAdapter].  The production implementation
 * is [KtorMcpStreamableHttpTransport]; tests can provide an in-memory transport
 * without changing the protocol state machine.
 */
interface McpStreamableHttpTransport {
    suspend fun request(message: JsonObject): McpTransportResponse
    suspend fun notify(message: JsonObject): McpTransportResponse
    suspend fun cancel(requestId: String, reason: String): McpTransportResponse
}

/**
 * MCP Streamable HTTP transport for protocol revision 2025-06-18.
 *
 * It sends one JSON-RPC message per POST, advertises both response media types,
 * accepts either a single JSON response or an SSE response, and retains the
 * server session identifier.  It intentionally has no process/stdio path and
 * never retries a request after a transport failure.
 */
class KtorMcpStreamableHttpTransport(
    private val http: HttpClient,
    private val endpoint: String,
    private val defaultHeaders: Map<String, RequestHeaderValue> = emptyMap(),
    private val headerSecretResolver: HeaderSecretResolver? = null,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : McpStreamableHttpTransport {
    @Volatile
    private var sessionId: String? = null

    override suspend fun request(message: JsonObject): McpTransportResponse = post(message, expectNotification = false)

    override suspend fun notify(message: JsonObject): McpTransportResponse = post(message, expectNotification = true)

    override suspend fun cancel(requestId: String, reason: String): McpTransportResponse =
        notify(
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive("notifications/cancelled"))
                put(
                    "params",
                    buildJsonObject {
                        put("requestId", JsonPrimitive(requestId))
                        if (reason.isNotBlank()) put("reason", JsonPrimitive(reason.take(512)))
                    },
                )
            },
        )

    private suspend fun post(message: JsonObject, expectNotification: Boolean): McpTransportResponse {
        val resolved = resolveHeaders()
        return try {
            http.preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Accept, "application/json, text/event-stream")
                    append("MCP-Protocol-Version", MCP_PROTOCOL_VERSION_2025_06_18)
                    sessionId?.let { append("MCP-Session-Id", it) }
                    resolved.values.forEach { (name, value) -> append(name, value) }
                }
                setBody(message.toString())
            }.execute { response ->
                response.headers["MCP-Session-Id"]?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                val status = response.status.value
                val body = readBounded(response.bodyAsChannel())
                if (status == 202 && body.isBlank()) {
                    return@execute McpTransportResponse.Accepted(sessionId)
                }
                if (status !in 200..299) {
                    throw McpTransportException(status, "MCP HTTP $status")
                }
                if (body.isBlank()) {
                    if (expectNotification) McpTransportResponse.Accepted(sessionId)
                    else McpTransportResponse.Messages(emptyList(), sessionId)
                } else {
                    val contentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                    val messages = if (contentType.contains("text/event-stream")) {
                        parseSse(body)
                    } else {
                        parseJsonMessage(body)
                    }
                    McpTransportResponse.Messages(messages, sessionId)
                }
            }
        } catch (e: McpTransportException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw McpTransportException(null, "MCP transport failed", e)
        }
    }

    private suspend fun readBounded(channel: io.ktor.utils.io.ByteReadChannel): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (!channel.isClosedForRead) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxResponseBytes) {
                throw McpTransportException(null, "MCP response exceeds configured limit")
            }
            out.write(buffer, 0, count)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun parseJsonMessage(raw: String): List<JsonObject> {
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            ?: throw McpTransportException(null, "MCP response is not valid JSON")
        val objectValue = parsed as? JsonObject
            ?: throw McpTransportException(null, "MCP response must be a JSON object")
        return listOf(objectValue)
    }

    private fun parseSse(raw: String): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()
        val data = mutableListOf<String>()
        fun flush() {
            if (data.isEmpty()) return
            val payload = data.joinToString("\n").trim()
            data.clear()
            if (payload.isBlank()) return
            val parsed = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: throw McpTransportException(null, "MCP SSE event is not a JSON object")
            messages += parsed
        }
        raw.split('\n').forEach { line ->
            val clean = line.removeSuffix("\r")
            when {
                clean.isEmpty() -> flush()
                clean.startsWith(":") -> Unit
                clean.startsWith("data:") -> data += clean.removePrefix("data:").trimStart()
                else -> Unit
            }
        }
        flush()
        return messages
    }

    private suspend fun resolveHeaders(): ResolvedHeaders {
        val values = linkedMapOf<String, String>()
        val secrets = mutableListOf<String>()
        val host = URI(endpoint).host?.lowercase()?.trim('.')
            ?: throw McpTransportException(null, "MCP endpoint has no host")
        defaultHeaders.forEach { (name, header) ->
            validateHeaderName(name)
            when (header) {
                is RequestHeaderValue.Plain -> {
                    validateHeaderValue(header.value)
                    values[name] = header.value
                }
                is RequestHeaderValue.SecretRef -> {
                    if (header.ref.isBlank()) throw McpTransportException(null, "MCP secret reference is empty")
                    val resolver = headerSecretResolver
                        ?: throw McpTransportException(null, "MCP secret resolver is unavailable")
                    val chars = resolver.resolve(host, header.ref)
                    val text = chars.concatToString()
                    chars.fill('\u0000')
                    if (text.isEmpty()) throw McpTransportException(null, "MCP secret is unavailable")
                    validateHeaderValue(text)
                    values[name] = text
                    secrets += text
                }
            }
        }
        return ResolvedHeaders(values, secrets)
    }

    private fun validateHeaderName(name: String) {
        val lower = name.lowercase()
        if (name.isBlank() || name.any { it == '\r' || it == '\n' } || lower in FORBIDDEN_HEADERS) {
            throw McpTransportException(null, "MCP header name is invalid")
        }
    }

    private fun validateHeaderValue(value: String) {
        if (value.any { it == '\r' || it == '\n' }) {
            throw McpTransportException(null, "MCP header value is invalid")
        }
    }

    private data class ResolvedHeaders(
        val values: Map<String, String>,
        val secrets: List<String>,
    ) {
        override fun toString(): String = "ResolvedHeaders(values=${values.keys}, secrets=<redacted>)"
    }

    companion object {
        private val FORBIDDEN_HEADERS = setOf(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "upgrade",
            "proxy-authorization",
            "proxy-authenticate",
            "te",
            "trailer",
            "content-type",
            "accept",
            "mcp-session-id",
            "mcp-protocol-version",
        )
    }
}

data class McpClientInfo(val name: String, val version: String)

data class McpInitializeResult(
    val protocolVersion: String,
    val serverCapabilities: JsonObject,
    val serverInfo: JsonObject?,
    val instructions: String?,
)

data class McpToolDefinition(
    val namespace: String,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
) {
    val namespacedName: String get() = "$namespace/$name"
    val schemaHash: String get() = sha256Hex(inputSchema.toString())
}

enum class McpGrantState { ACTIVE, STALE }

data class McpToolGrant(
    val grantId: String,
    val revision: Long,
    val namespace: String,
    val tools: Map<String, McpToolDefinition>,
    val discoveryFingerprint: String,
    val state: McpGrantState = McpGrantState.ACTIVE,
)

sealed interface McpCallResult {
    val callId: String

    data class Success(override val callId: String, val result: JsonObject) : McpCallResult
    data class ToolError(override val callId: String, val result: JsonObject) : McpCallResult
    data class ProtocolError(override val callId: String, val code: Int, val message: String) : McpCallResult
    data class Denied(override val callId: String, val reason: String) : McpCallResult
    data class UnknownOutcome(override val callId: String, val reason: String) : McpCallResult
}

data class McpCancellationResult(
    val callId: String,
    val accepted: Boolean,
    val terminalState: String?,
)

class McpProtocolException(message: String) : RuntimeException(message)

/**
 * Protocol/state adapter for one user-configured MCP Streamable HTTP endpoint.
 * It freezes explicit grants against a discovered list and invalidates them on
 * any list/schema/ordering change. Calls are single-use by call id.
 */
class RemoteMcpAdapter(
    private val transport: McpStreamableHttpTransport,
    private val namespace: String,
    private val clientInfo: McpClientInfo = McpClientInfo("mobileAgentRuntime", "1"),
    private val protocolVersion: String = MCP_PROTOCOL_VERSION_2025_06_18,
) {
    private val ids = AtomicLong(0)
    private val usedCallIds = linkedSetOf<String>()
    private val inFlight = linkedMapOf<String, Long>()
    private val grants = linkedMapOf<String, McpToolGrant>()
    private var initialized = false
    private var discovered: List<McpToolDefinition> = emptyList()
    private var discoveryFingerprint: String = ""

    init {
        require(namespace.isNotBlank() && !namespace.contains('/')) { "MCP namespace is invalid" }
        require(protocolVersion == MCP_PROTOCOL_VERSION_2025_06_18) { "Unsupported MCP protocol revision" }
    }

    suspend fun initialize(capabilities: JsonObject = JsonObject(emptyMap())): McpInitializeResult {
        check(!initialized) { "MCP client is already initialized" }
        val id = ids.incrementAndGet()
        val request = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive("initialize"))
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", JsonPrimitive(protocolVersion))
                    put("capabilities", capabilities)
                    put("clientInfo", buildJsonObject {
                        put("name", JsonPrimitive(clientInfo.name))
                        put("version", JsonPrimitive(clientInfo.version))
                    })
                },
            )
        }
        val response = transport.request(request)
        val root = response.responseFor(id, this)
        val result = root["result"]?.jsonObject
            ?: throw McpProtocolException("MCP initialize did not return a result")
        val negotiated = result["protocolVersion"]?.jsonPrimitive?.contentOrNull
            ?: throw McpProtocolException("MCP initialize omitted protocolVersion")
        if (negotiated != protocolVersion) throw McpProtocolException("Unsupported MCP protocol revision: $negotiated")
        val serverCapabilities = result["capabilities"]?.jsonObject ?: JsonObject(emptyMap())
        val initializedNotification = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive("notifications/initialized"))
        }
        transport.notify(initializedNotification)
        initialized = true
        return McpInitializeResult(
            protocolVersion = negotiated,
            serverCapabilities = serverCapabilities,
            serverInfo = result["serverInfo"]?.jsonObject,
            instructions = result["instructions"]?.jsonPrimitive?.contentOrNull?.take(4096),
        )
    }

    suspend fun discoverTools(): List<McpToolDefinition> {
        check(initialized) { "MCP client is not initialized" }
        val all = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val id = ids.incrementAndGet()
            val params = cursor?.let { buildJsonObject { put("cursor", JsonPrimitive(it)) } }
            val request = rpcRequest(id, "tools/list", params)
            val root = transport.request(request).responseFor(id, this)
            val result = root["result"]?.jsonObject
                ?: throw McpProtocolException("MCP tools/list did not return a result")
            val tools = result["tools"]?.jsonArray
                ?: throw McpProtocolException("MCP tools/list omitted tools")
            tools.forEach { element ->
                val tool = element.jsonObject
                val name = tool["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (name.isBlank() || name.contains('/')) throw McpProtocolException("MCP tool name is invalid")
                val schema = tool["inputSchema"]?.jsonObject
                    ?: throw McpProtocolException("MCP tool $name omitted inputSchema")
                validateSchema(schema)?.let { throw McpProtocolException("MCP tool $name schema invalid: $it") }
                all += McpToolDefinition(
                    namespace = namespace,
                    serverName = tool["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = name,
                    description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(4096),
                    inputSchema = schema,
                    outputSchema = tool["outputSchema"]?.jsonObject,
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (cursor != null && !seenCursors.add(cursor!!)) throw McpProtocolException("MCP tools/list cursor repeated")
        } while (cursor != null)

        val fingerprint = fingerprint(all)
        if (discoveryFingerprint.isNotBlank() && fingerprint != discoveryFingerprint) {
            grants.keys.toList().forEach { id -> grants[id] = grants.getValue(id).copy(state = McpGrantState.STALE) }
        }
        discovered = all.toList()
        discoveryFingerprint = fingerprint
        return discovered
    }

    /** Freeze exactly the explicitly named, currently discovered tools. */
    fun freezeGrant(grantId: String, revision: Long, toolNames: Set<String>): McpToolGrant {
        require(grantId.isNotBlank()) { "MCP grant id is empty" }
        require(revision > 0) { "MCP grant revision must be positive" }
        val byName = discovered.associateBy { it.namespacedName }
        val selected = linkedMapOf<String, McpToolDefinition>()
        toolNames.forEach { name ->
            val tool = byName[name] ?: throw McpProtocolException("MCP tool is not discovered: $name")
            selected[name] = tool
        }
        val grant = McpToolGrant(grantId, revision, namespace, selected.toMap(), discoveryFingerprint)
        grants[grantId] = grant
        return grant
    }

    suspend fun callTool(
        grant: McpToolGrant,
        callId: String,
        toolName: String,
        arguments: JsonObject,
    ): McpCallResult {
        if (callId.isBlank()) return McpCallResult.Denied(callId, "MCP call id is empty")
        if (!usedCallIds.add(callId)) return McpCallResult.Denied(callId, "MCP call id was already used")
        val activeGrant = grants[grant.grantId]
        if (grant.state != McpGrantState.ACTIVE || grant.namespace != namespace ||
            activeGrant == null || activeGrant.state != McpGrantState.ACTIVE ||
            activeGrant.discoveryFingerprint != discoveryFingerprint ||
            grant.discoveryFingerprint != discoveryFingerprint
        ) {
            return McpCallResult.Denied(callId, "MCP tool grant is stale")
        }
        val tool = activeGrant.tools[toolName]
            ?: return McpCallResult.Denied(callId, "MCP tool is not explicitly granted")
        val schemaError = validateArguments(tool.inputSchema, arguments)
        if (schemaError != null) return McpCallResult.Denied(callId, schemaError)

        val requestId = ids.incrementAndGet()
        inFlight[callId] = requestId
        val request = rpcRequest(
            requestId,
            "tools/call",
            buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("arguments", arguments)
            },
        )
        val result = try {
            val root = transport.request(request).responseFor(requestId, this)
            val error = root["error"]?.jsonObject
            if (error != null) {
                McpCallResult.ProtocolError(
                    callId,
                    error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -32000,
                    sanitized(error["message"]?.jsonPrimitive?.contentOrNull ?: "MCP protocol error"),
                )
            } else {
                val rpcResult = root["result"]?.jsonObject
                if (rpcResult == null) {
                    McpCallResult.UnknownOutcome(callId, "MCP response has neither result nor error")
                } else {
                    val isError = rpcResult["isError"]?.jsonPrimitive?.booleanOrNull() == true
                    if (isError) McpCallResult.ToolError(callId, rpcResult)
                    else McpCallResult.Success(callId, rpcResult)
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException("MCP call cancelled")
        } catch (e: Exception) {
            McpCallResult.UnknownOutcome(callId, sanitized(e.message ?: "MCP response is unknown"))
        } finally {
            inFlight.remove(callId)
        }
        return result
    }

    suspend fun cancel(callId: String, reason: String = "user-cancelled"): McpCancellationResult {
        val requestId = inFlight[callId]
            ?: return McpCancellationResult(callId, accepted = false, terminalState = null)
        return try {
            when (val response = transport.cancel(requestId.toString(), reason)) {
                is McpTransportResponse.Accepted -> {
                    // MCP cancellation is a notification. HTTP 202 confirms
                    // receipt of the notification, not that remote work stopped.
                    inFlight.remove(callId)
                    McpCancellationResult(callId, accepted = true, terminalState = "UNKNOWN_OUTCOME")
                }
                is McpTransportResponse.Messages -> {
                    val error = response.messages.firstOrNull { it["error"] != null }
                    if (error != null) {
                        McpCancellationResult(callId, accepted = false, terminalState = "UNKNOWN_OUTCOME")
                    } else {
                        inFlight.remove(callId)
                        McpCancellationResult(callId, accepted = true, terminalState = "UNKNOWN_OUTCOME")
                    }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException("MCP cancellation cancelled")
        } catch (_: Exception) {
            McpCancellationResult(callId, accepted = false, terminalState = "UNKNOWN_OUTCOME")
        }
    }

    fun currentTools(): List<McpToolDefinition> = discovered

    fun isGrantStale(grant: McpToolGrant): Boolean =
        grants[grant.grantId]?.let { it.state != McpGrantState.ACTIVE || it.discoveryFingerprint != discoveryFingerprint } != false

    private fun rpcRequest(id: Long, method: String, params: JsonObject?): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", JsonPrimitive(id))
        put("method", JsonPrimitive(method))
        if (params != null) put("params", params)
    }

    private fun validateArguments(schema: JsonObject, arguments: JsonObject): String? {
        val required = schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val missing = required.filterNot { arguments.containsKey(it) }
        if (missing.isNotEmpty()) return "MCP tool arguments missing: ${missing.joinToString(",")}"
        return validateObject(schema, arguments, "arguments")
    }

    private fun validateSchema(schema: JsonObject): String? {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
            ?: return "schema type is missing"
        if (type != "object") return "inputSchema type must be object"
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required = schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (required.any { it !in properties }) return "required contains an unknown property"
        if (properties.keys.any { it.isBlank() || it.contains('\u0000') }) return "property name is invalid"
        return properties.entries.firstNotNullOfOrNull { (name, value) ->
            if (value !is JsonObject) "$name schema must be an object" else validateValueSchema(value, name)
        }
    }

    private fun validateValueSchema(schema: JsonObject, path: String): String? {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
            ?: return "$path type is missing"
        if (type !in setOf("object", "array", "string", "integer", "number", "boolean", "null")) {
            return "$path type is unsupported"
        }
        if (type == "object") {
            validateSchema(schema)?.let { return it }
        }
        if (type == "array") {
            val items = schema["items"]
            if (items !is JsonObject) return "$path array items schema is missing"
            validateValueSchema(items, "$path[]")?.let { return it }
        }
        return null
    }

    private fun validateObject(schema: JsonObject, value: JsonObject, path: String): String? {
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val additional = schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull() ?: true
        if (!additional && value.keys.any { it !in properties }) {
            return "$path contains an unknown property"
        }
        for ((name, item) in value) {
            val property = properties[name] ?: continue
            val error = validateValue(property, item, "$path.$name")
            if (error != null) return error
        }
        return null
    }

    private fun validateValue(schema: JsonElement, value: JsonElement, path: String): String? {
        val obj = schema as? JsonObject ?: return "$path schema is invalid"
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return "$path type is missing"
        when (type) {
            "object" -> {
                val objectValue = value as? JsonObject ?: return "$path must be an object"
                validateObject(obj, objectValue, path)?.let { return it }
                val required = obj["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val missing = required.filterNot { objectValue.containsKey(it) }
                if (missing.isNotEmpty()) return "$path missing: ${missing.joinToString(",")}"
            }
            "array" -> {
                val array = value as? JsonArray ?: return "$path must be an array"
                val items = obj["items"] ?: return "$path array items schema is missing"
                array.forEachIndexed { index, item ->
                    validateValue(items, item, "$path[$index]")?.let { return it }
                }
            }
            "string" -> {
                val primitive = value as? JsonPrimitive ?: return "$path must be a string"
                if (!primitive.isString) return "$path must be a string"
                obj["minLength"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { if (primitive.content.length < it) return "$path is too short" }
            }
            "integer" -> {
                val number = value as? JsonPrimitive ?: return "$path must be an integer"
                if (number.isString || number.content.toLongOrNull() == null) return "$path must be an integer"
            }
            "number" -> {
                val number = value as? JsonPrimitive ?: return "$path must be a number"
                if (number.isString || number.content.toDoubleOrNull() == null) return "$path must be a number"
            }
            "boolean" -> {
                val primitive = value as? JsonPrimitive ?: return "$path must be boolean"
                if (primitive.isString || primitive.booleanOrNull() == null) return "$path must be boolean"
            }
            "null" -> if (value !is JsonNull) return "$path must be null"
            else -> return "$path type is unsupported"
        }
        obj["enum"]?.jsonArray?.let { allowed ->
            if (allowed.none { it == value }) return "$path is not an allowed value"
        }
        return null
    }

    private fun fingerprint(tools: List<McpToolDefinition>): String =
        sha256Hex(tools.joinToString("\n") { "${it.namespacedName}\u0000${it.description}\u0000${it.inputSchema}\u0000${it.outputSchema ?: JsonNull}" })

    private fun sanitized(value: String): String = value.replace(Regex("[\\r\\n\\t]+"), " ").take(1024)

    private fun McpTransportResponse.responseFor(id: Long, adapter: RemoteMcpAdapter): JsonObject {
        val messages = when (this) {
            is McpTransportResponse.Accepted -> emptyList()
            is McpTransportResponse.Messages -> messages
        }
        return messages.singleResponse(id, adapter)
    }

    private fun List<JsonObject>.singleResponse(id: Long, adapter: RemoteMcpAdapter): JsonObject {
        for (message in this) {
            adapter.observe(message)
        }
        val response = firstOrNull { it["id"]?.jsonPrimitive?.longOrNull() == id }
            ?: throw McpProtocolException("MCP response id is missing or does not match")
        return response
    }

    private fun observe(message: JsonObject) {
        if (message["method"]?.jsonPrimitive?.contentOrNull == "notifications/tools/list_changed") {
            grants.keys.toList().forEach { id -> grants[id] = grants.getValue(id).copy(state = McpGrantState.STALE) }
        }
    }

    private fun JsonPrimitive.booleanOrNull(): Boolean? = contentOrNull?.toBooleanStrictOrNull()
    private fun JsonPrimitive.longOrNull(): Long? = contentOrNull?.toLongOrNull()

}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
