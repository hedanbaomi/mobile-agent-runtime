// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.provider.mcp.McpCallResult
import runtime.mobileagent.provider.mcp.McpClientInfo
import runtime.mobileagent.provider.mcp.KtorMcpStreamableHttpTransport
import runtime.mobileagent.provider.mcp.RemoteMcpAdapter
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

/** Immutable MCP binding captured for one persisted Agent snapshot. */
data class McpSnapshot(
    val snapshotId: String,
    val agentId: String,
    internal val endpoint: String,
    internal val host: String,
    internal val namespace: String,
    internal val grantId: String,
    val discoveryRevision: Long,
    val discoveryFingerprint: String,
    internal val tools: List<McpSnapshotTool>,
)

data class McpSnapshotTool(
    internal val namespacedName: String,
    internal val description: String,
    internal val inputSchemaJson: String,
    internal val schemaHash: String,
)

/**
 * Capture the currently approved MCP tool list for a real Agent snapshot.
 * A missing endpoint, discovery, grant, or persisted snapshot binding returns
 * null and therefore cannot silently enable network tools.
 */
fun captureMcpSnapshot(container: AppContainer, snapshotId: String, agentId: String): McpSnapshot? {
    if (snapshotId.isBlank() || agentId.isBlank()) return null
    val agentSnapshot = runCatching { container.agents.getSnapshot(snapshotId) }.getOrNull() ?: return null
    if (agentSnapshot.agentId != agentId) return null
    val config = McpConfigStore.read(container).value ?: return null
    if (config.networkApprovedAt.isNullOrBlank() || config.discoveryRevision <= 0) return null
    val fingerprint = config.discoveryFingerprint ?: return null
    val grant = config.grants.singleOrNull { it.agentId == agentId } ?: return null
    val byName = config.tools.associateBy { it.namespacedName }
    if (grant.toolNames.isEmpty() || grant.toolNames.any { it !in byName }) return null
    if (grant.schemaHashes.keys != grant.toolNames.toSet()) return null
    if (grant.schemaHashes.any { (name, hash) -> byName[name]?.schemaHash != hash }) return null
    if (grant.revision != config.discoveryRevision) return null

    val existing = config.snapshots.singleOrNull { it.snapshotId == snapshotId && it.agentId == agentId }
    val stored = existing ?: McpStoredSnapshot(
        snapshotId = snapshotId,
        agentId = agentId,
        grantId = grant.grantId,
        revision = grant.revision,
        discoveryFingerprint = fingerprint,
        toolNames = grant.toolNames.sorted(),
        schemaHashes = grant.schemaHashes.toSortedMap(),
    ).also { created ->
        McpConfigStore.write(
            container,
            config.copy(
                snapshots = (config.snapshots.filterNot { it.snapshotId == snapshotId } + created).takeLast(MAX_SNAPSHOTS),
            ),
        )
    }
    if (stored.grantId != grant.grantId || stored.revision != grant.revision ||
        stored.discoveryFingerprint != fingerprint || stored.toolNames.toSet() != grant.toolNames.toSet() ||
        stored.schemaHashes != grant.schemaHashes
    ) return null
    return publicMcpSnapshot(config, stored)
}

/**
 * Load only a binding that was already persisted for this exact Agent snapshot.
 * This is intentionally read-only: unlike [captureMcpSnapshot], it never creates
 * a grant or snapshot entry for an older session.
 */
fun loadMcpSnapshot(container: AppContainer, snapshotId: String, agentId: String): McpSnapshot? {
    if (snapshotId.isBlank() || agentId.isBlank()) return null
    val agentSnapshot = runCatching { container.agents.getSnapshot(snapshotId) }.getOrNull() ?: return null
    if (agentSnapshot.agentId != agentId) return null
    val config = McpConfigStore.read(container).value ?: return null
    val stored = config.snapshots.singleOrNull { it.snapshotId == snapshotId && it.agentId == agentId }
        ?: return null
    val snapshot = publicMcpSnapshot(config, stored) ?: return null
    return snapshot.takeIf { snapshotBindingIsCurrent(config, it) }
}

private fun publicMcpSnapshot(config: McpStoredConfig, stored: McpStoredSnapshot): McpSnapshot? {
    if (config.networkApprovedAt.isNullOrBlank() || config.discoveryRevision <= 0) return null
    val fingerprint = config.discoveryFingerprint ?: return null
    if (stored.revision != config.discoveryRevision || stored.discoveryFingerprint != fingerprint) return null
    val grant = config.grants.singleOrNull { it.agentId == stored.agentId } ?: return null
    if (grant.grantId != stored.grantId || grant.revision != stored.revision) return null
    if (grant.toolNames.toSet() != stored.toolNames.toSet() || grant.schemaHashes != stored.schemaHashes) return null
    val byName = config.tools.associateBy { it.namespacedName }
    val tools = stored.toolNames.mapNotNull { name ->
        byName[name]?.let { tool ->
            McpSnapshotTool(tool.namespacedName, tool.description, tool.inputSchemaJson, tool.schemaHash)
        }
    }
    if (tools.size != stored.toolNames.size || tools.isEmpty()) return null
    return McpSnapshot(
        snapshotId = stored.snapshotId,
        agentId = stored.agentId,
        endpoint = config.endpoint,
        host = config.host,
        namespace = config.namespace,
        grantId = stored.grantId,
        discoveryRevision = stored.revision,
        discoveryFingerprint = stored.discoveryFingerprint,
        tools = tools,
    )
}

/**
 * Return an app-level MCP executor for a previously captured binding.  The
 * returned empty executor is deliberate for old sessions, revoked grants, or
 * any metadata mismatch.  Construction never performs network I/O.
 */
fun mcpTools(container: AppContainer, snapshot: McpSnapshot): ToolExecutor {
    val config = McpConfigStore.read(container).value ?: return EmptyMcpToolExecutor
    if (!snapshotBindingIsCurrent(config, snapshot)) return EmptyMcpToolExecutor
    return AppMcpToolExecutor(container, snapshot)
}

/**
 * Resolve MCP for an existing AgentSnapshot without capturing a new binding.
 * Old sessions therefore remain MCP-disabled unless their binding was captured
 * at session creation and is still valid under the current grant.
 */
fun mcpTools(container: AppContainer, snapshot: AgentSnapshot): ToolExecutor =
    loadMcpSnapshot(container, snapshot.id, snapshot.agentId)?.let { mcpTools(container, it) }
        ?: EmptyMcpToolExecutor

/** Explicitly empty executor used when no valid MCP grant is available. */
private object EmptyMcpToolExecutor : ToolExecutor {
    override val specs: List<ToolSpec> = emptyList()

    override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Denied("MCP tool grant is not configured")

    override suspend fun approve(callId: String): ToolResult = ToolResult.Denied("MCP tool grant is not configured")
}

private class AppMcpToolExecutor(
    private val container: AppContainer,
    private val snapshot: McpSnapshot,
) : ToolExecutor {
    private val usedCallIds = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, PendingMcpCall>()
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val modelTools = modelMcpTools(snapshot)
    private val modelToolsByName = modelTools.associateBy { it.modelName }

    /**
     * Every MCP call is treated as approval-required, including tools that the
     * remote server labels read-only.  Model-facing metadata is a deliberately
     * neutral projection: endpoint, host, namespace, grant and remote prose
     * never cross the model boundary.  The original values remain available
     * only to the internal snapshot/grant checks below.
     */
    override val specs: List<ToolSpec> = modelMcpToolSpecs(snapshot)

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (call.callId.isBlank()) return ToolResult.Invalid("MCP call ID is missing")
        if (usedCallIds.contains(call.callId)) return ToolResult.Invalid("MCP call ID was already used")
        val modelTool = modelToolsByName[call.name]
            ?: return ToolResult.Invalid("MCP tool is not in the snapshot grant")
        val args = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
            ?: return ToolResult.Invalid("MCP tool arguments must be a JSON object")
        if (!usedCallIds.add(call.callId)) return ToolResult.Invalid("MCP call ID was already used")
        pending[call.callId] = PendingMcpCall(
            modelCall = call.copy(argumentsJson = args.toString()),
            internalToolName = modelTool.internalName,
        )
        return ToolResult.NeedsApproval
    }

    override suspend fun approve(callId: String): ToolResult {
        val pendingCall = pending.remove(callId) ?: return ToolResult.Invalid("No pending MCP approval")
        return try {
            executeApproved(pendingCall)
        } catch (error: CancellationException) {
            // The call ID remains consumed; a caller must not replay after an
            // interrupted network operation whose remote outcome is unknown.
            throw error
        } catch (_: Exception) {
            // Throwable text may contain an endpoint, host, secret or remote
            // protocol detail.  The model receives only a stable outcome.
            ToolResult.UnknownOutcome(MCP_UNKNOWN_OUTCOME)
        }
    }

    private suspend fun executeApproved(pendingCall: PendingMcpCall): ToolResult {
        val call = pendingCall.modelCall
        val config = McpConfigStore.read(container).value
            ?: return ToolResult.Denied(MCP_CONFIGURATION_UNAVAILABLE)
        if (!snapshotBindingIsCurrent(config, snapshot)) {
            return ToolResult.Denied(MCP_SNAPSHOT_STALE)
        }
        val secrets = mutableListOf<String>()
        try {
            val adapter = createMcpAdapter(container, config) { value ->
                secrets += value.concatToString()
            }
            // Re-discover immediately before the approved call.  This one request
            // sequence has no retry or reconnect loop and never adds new tools.
            adapter.initialize()
            val discovered = adapter.discoverTools()
            if (mcpFingerprint(discovered) != snapshot.discoveryFingerprint) {
                return ToolResult.Denied(MCP_TOOL_LIST_STALE)
            }
            val byName = discovered.associateBy { it.namespacedName }
            if (snapshot.tools.any { byName[it.namespacedName]?.schemaHash != it.schemaHash }) {
                return ToolResult.Denied(MCP_TOOL_SCHEMA_STALE)
            }
            val grant = adapter.freezeGrant(
                grantId = snapshot.grantId,
                revision = snapshot.discoveryRevision,
                toolNames = snapshot.tools.map { it.namespacedName }.toSet(),
            )
            val result = adapter.callTool(
                grant = grant,
                callId = call.callId,
                toolName = pendingCall.internalToolName,
                arguments = json.parseToJsonElement(call.argumentsJson).jsonObject,
            )
            return mcpCallResultForModel(
                result = result,
                blockedValues = (listOf(
                    snapshot.endpoint,
                    snapshot.host,
                    snapshot.namespace,
                    snapshot.grantId,
                    config.endpoint,
                    config.host,
                    config.namespace,
                    config.secretRef,
                    config.secretHost,
                ) + secrets).filterNotNull(),
            )
        } finally {
            secrets.fill("")
            secrets.clear()
        }
    }
}

private data class PendingMcpCall(
    val modelCall: ToolCall,
    val internalToolName: String,
)

private data class ModelMcpTool(
    val modelName: String,
    val internalName: String,
    val modelSchemaJson: String,
)

/**
 * Model-facing MCP metadata is a deliberately lossy projection.  MCP server
 * names, transport coordinates and grant identifiers remain in the private
 * snapshot above, but are never useful to the model when selecting a tool.
 */
private fun modelMcpTools(snapshot: McpSnapshot): List<ModelMcpTool> =
    snapshot.tools.mapIndexedNotNull { index, tool ->
        sanitizeMcpInputSchema(tool.inputSchemaJson)?.let { schema ->
            ModelMcpTool(
                modelName = "external_operation_${index + 1}",
                internalName = tool.namespacedName,
                modelSchemaJson = schema,
            )
        }
    }

/** Exposed only to the app's focused privacy tests; transport metadata stays internal. */
internal fun modelMcpToolSpecs(snapshot: McpSnapshot): List<ToolSpec> =
    modelMcpTools(snapshot).map { tool ->
        ToolSpec(
            name = tool.modelName,
            description = MODEL_TOOL_DESCRIPTION,
            parametersJson = tool.modelSchemaJson,
            capability = MODEL_TOOL_CAPABILITY,
            sideEffect = true,
        )
    }

private fun sanitizeMcpInputSchema(raw: String): String? = runCatching {
    val root = MCP_JSON.parseToJsonElement(raw).jsonObject
    val safe = sanitizeMcpSchemaObject(root, depth = 0) ?: return@runCatching null
    if (safe["type"]?.jsonPrimitive?.contentOrNull != "object") return@runCatching null
    safe.toString().takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_MODEL_SCHEMA_BYTES }
}.getOrNull()

private fun sanitizeMcpSchemaObject(value: JsonObject, depth: Int): JsonObject? {
    if (depth > MAX_SCHEMA_DEPTH) return null
    val type = (value["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (type !in ALLOWED_SCHEMA_TYPES) return null

    val output = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type))
    if (type == "object") {
        val properties = (value["properties"] as? JsonObject)?.let { source ->
            buildJsonObject {
                source.entries
                    .asSequence()
                    .filter { (name, _) -> isSafeMcpFieldName(name) }
                    .take(MAX_SCHEMA_FIELDS)
                    .forEach { (name, child) ->
                        val childObject = child as? JsonObject
                        val sanitized = childObject?.let { sanitizeMcpSchemaObject(it, depth + 1) }
                        if (sanitized != null) put(name.take(MAX_FIELD_NAME_LENGTH), sanitized)
                    }
            }
        }
        if (properties != null) output["properties"] = properties

        val retainedNames = (properties?.keys ?: emptySet())
        val required = (value["required"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf { it in retainedNames }
        }?.distinct()?.take(MAX_SCHEMA_FIELDS)
        if (!required.isNullOrEmpty()) output["required"] = buildJsonArray {
            required.forEach { add(JsonPrimitive(it)) }
        }
    } else if (type == "array") {
        val items = (value["items"] as? JsonObject)?.let { sanitizeMcpSchemaObject(it, depth + 1) }
        if (items != null) output["items"] = items
    }

    if (value["additionalProperties"] is JsonPrimitive) {
        val additional = value["additionalProperties"] as JsonPrimitive
        if (additional.content == "true" || additional.content == "false") {
            output["additionalProperties"] = additional
        }
    } else if (value["additionalProperties"] is JsonObject) {
        sanitizeMcpSchemaObject(value["additionalProperties"] as JsonObject, depth + 1)?.let {
            output["additionalProperties"] = it
        }
    }

    SCHEMA_NUMERIC_KEYS.forEach { key ->
        val primitive = value[key] as? JsonPrimitive ?: return@forEach
        if (!primitive.isString && primitive.content.toDoubleOrNull() != null) output[key] = primitive
    }
    (value["enum"] as? JsonArray)?.let { values ->
        val safe = values.filter { element ->
            val primitive = element as? JsonPrimitive
            primitive != null && (!primitive.isString || !isSensitiveMcpText(primitive.content))
        }.take(MAX_SCHEMA_ENUM_VALUES)
        if (safe.isNotEmpty()) output["enum"] = JsonArray(safe)
    }
    return JsonObject(output)
}

private fun isSafeMcpFieldName(name: String): Boolean =
    name.length in 1..MAX_FIELD_NAME_LENGTH &&
        name.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
        !isSensitiveMcpName(name)

private fun isSensitiveMcpName(name: String): Boolean {
    val normalized = name.filter { it.isLetterOrDigit() }.lowercase()
    return normalized in SENSITIVE_MCP_NAMES ||
        normalized.contains("endpoint") || normalized.contains("hostname") ||
        normalized.contains("grant") || normalized.contains("secret") ||
        normalized.contains("token") || normalized.contains("credential") ||
        normalized.contains("authorization") || normalized.contains("apikey")
}

private fun isSensitiveMcpText(value: String): Boolean =
    MCP_URL_PATTERN.containsMatchIn(value) || MCP_GRANT_REF_PATTERN.containsMatchIn(value) ||
        MCP_SECRET_REF_PATTERN.containsMatchIn(value) || MCP_HOSTNAME_PATTERN.containsMatchIn(value)

internal fun mcpCallResultForModel(result: McpCallResult, blockedValues: List<String>): ToolResult =
    when (result) {
        is McpCallResult.Success -> safeMcpJsonResult(result.result, blockedValues)?.let(ToolResult::Value)
            ?: ToolResult.Invalid(MCP_RESULT_TOO_LARGE)
        is McpCallResult.ToolError -> safeMcpJsonResult(result.result, blockedValues)?.let(ToolResult::Invalid)
            ?: ToolResult.Invalid(MCP_RESULT_TOO_LARGE)
        is McpCallResult.ProtocolError -> ToolResult.Invalid(MCP_PROTOCOL_ERROR)
        is McpCallResult.Denied -> ToolResult.Denied(MCP_DENIED)
        is McpCallResult.UnknownOutcome -> ToolResult.UnknownOutcome(MCP_UNKNOWN_OUTCOME)
    }

private fun safeMcpJsonResult(value: JsonObject, blockedValues: List<String>): String? = runCatching {
    val safe = sanitizeMcpResultElement(value, blockedValues, depth = 0) ?: return@runCatching null
    val encoded = safe.toString()
    encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_MODEL_RESULT_BYTES }
}.getOrNull()

private fun sanitizeMcpResultElement(
    value: JsonElement,
    blockedValues: List<String>,
    depth: Int,
): JsonElement? {
    if (depth > MAX_RESULT_DEPTH) return JsonPrimitive("[redacted-depth]")
    return when (value) {
        JsonNull -> JsonNull
        is JsonPrimitive -> if (value.isString) {
            JsonPrimitive(sanitizeMcpText(value.content, blockedValues))
        } else value
        is JsonArray -> JsonArray(
            value.take(MAX_RESULT_ARRAY_ITEMS).mapNotNull {
                sanitizeMcpResultElement(it, blockedValues, depth + 1)
            },
        )
        is JsonObject -> buildJsonObject {
            value.entries.asSequence()
                .filter { (key, _) -> isSafeMcpFieldName(key) }
                .take(MAX_RESULT_OBJECT_FIELDS)
                .forEach { (key, child) ->
                    sanitizeMcpResultElement(child, blockedValues, depth + 1)?.let { put(key, it) }
                }
        }
    }
}

private fun sanitizeMcpText(value: String, blockedValues: List<String>): String {
    var result = SecretRedactor.redact(value, blockedValues)
    result = MCP_URL_PATTERN.replace(result, "[redacted-url]")
    result = MCP_GRANT_REF_PATTERN.replace(result, "[redacted-grant]")
    result = MCP_SECRET_REF_PATTERN.replace(result, "[redacted-secret-ref]")
    result = MCP_ERROR_TEXT_PATTERN.replace(result, "[redacted-error]")
    result = MCP_HOSTNAME_PATTERN.replace(result, "[redacted-host]")
    result = MCP_CONTROL_PATTERN.replace(result, " ")
    return result.take(MAX_MODEL_TEXT_LENGTH)
}

private fun snapshotBindingIsCurrent(config: McpStoredConfig, snapshot: McpSnapshot): Boolean {
    if (config.endpoint != snapshot.endpoint || config.host != snapshot.host || config.namespace != snapshot.namespace) return false
    if (config.networkApprovedAt.isNullOrBlank() || config.discoveryRevision != snapshot.discoveryRevision) return false
    if (config.discoveryFingerprint != snapshot.discoveryFingerprint) return false
    val grant = config.grants.singleOrNull { it.agentId == snapshot.agentId } ?: return false
    if (grant.grantId != snapshot.grantId || grant.revision != snapshot.discoveryRevision) return false
    val storedSnapshot = config.snapshots.singleOrNull { it.snapshotId == snapshot.snapshotId && it.agentId == snapshot.agentId }
        ?: return false
    if (storedSnapshot.grantId != snapshot.grantId || storedSnapshot.revision != snapshot.discoveryRevision ||
        storedSnapshot.discoveryFingerprint != snapshot.discoveryFingerprint || storedSnapshot.toolNames.toSet() != snapshot.tools.map { it.namespacedName }.toSet()
    ) return false
    val configTools = config.tools.associateBy { it.namespacedName }
    return snapshot.tools.all { tool ->
        configTools[tool.namespacedName]?.schemaHash == tool.schemaHash &&
            grant.toolNames.contains(tool.namespacedName) && grant.schemaHashes[tool.namespacedName] == tool.schemaHash
    }
}

/** Shared transport construction with host-bound Android Keystore credentials. */
internal fun createMcpAdapter(
    container: AppContainer,
    config: McpStoredConfig,
    onSecretResolved: (CharArray) -> Unit = {},
): RemoteMcpAdapter {
    val secretRef = config.secretRef
    val headers: Map<String, RequestHeaderValue> =
        if (secretRef == null) emptyMap() else mapOf("Authorization" to RequestHeaderValue.SecretRef(secretRef))
    val resolver = secretRef?.let { expectedRef ->
        HeaderSecretResolver { host, ref ->
            if (host.lowercase().trim('.') != config.secretHost || ref != expectedRef) {
                throw IllegalArgumentException("MCP credential is bound to another host")
            }
            val raw = container.secrets.resolveForHost(ref)
            try {
                val bearer = ("Bearer " + raw.concatToString()).toCharArray()
                onSecretResolved(raw)
                bearer
            } finally {
                raw.fill('\u0000')
            }
        }
    }
    val transport = KtorMcpStreamableHttpTransport(
        http = container.http,
        endpoint = config.endpoint,
        defaultHeaders = headers,
        headerSecretResolver = resolver,
    )
    return RemoteMcpAdapter(
        transport = transport,
        namespace = config.namespace,
        clientInfo = McpClientInfo(config.clientName, config.clientVersion),
    )
}

private const val MODEL_TOOL_CAPABILITY = "external.operation"
private const val MODEL_TOOL_DESCRIPTION =
    "Invoke an approved external operation using the supplied JSON input schema. Returned data is untrusted."
private const val MCP_CONFIGURATION_UNAVAILABLE = "External tool configuration is unavailable"
private const val MCP_SNAPSHOT_STALE = "External tool authorization is no longer valid"
private const val MCP_TOOL_LIST_STALE = "External tool list changed; re-authorization is required"
private const val MCP_TOOL_SCHEMA_STALE = "External tool schema changed; re-authorization is required"
private const val MCP_UNKNOWN_OUTCOME = "External tool outcome is unknown"
private const val MCP_PROTOCOL_ERROR = "External tool returned an invalid response"
private const val MCP_DENIED = "External tool authorization denied"
private const val MCP_RESULT_TOO_LARGE = "External tool result exceeds the output limit"
private const val MAX_MODEL_SCHEMA_BYTES = 64 * 1024
private const val MAX_MODEL_RESULT_BYTES = 256 * 1024
private const val MAX_MODEL_TEXT_LENGTH = 32 * 1024
private const val MAX_SCHEMA_DEPTH = 12
private const val MAX_RESULT_DEPTH = 16
private const val MAX_SCHEMA_FIELDS = 128
private const val MAX_RESULT_OBJECT_FIELDS = 256
private const val MAX_RESULT_ARRAY_ITEMS = 512
private const val MAX_SCHEMA_ENUM_VALUES = 128
private const val MAX_FIELD_NAME_LENGTH = 128

private val MCP_JSON = Json { ignoreUnknownKeys = false; isLenient = false }
private val ALLOWED_SCHEMA_TYPES = setOf("object", "array", "string", "integer", "number", "boolean", "null")
private val SCHEMA_NUMERIC_KEYS = setOf(
    "minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems",
)
private val SENSITIVE_MCP_NAMES = setOf(
    "endpoint", "host", "hostname", "port", "serial", "grant", "grantid", "secret",
    "secretref", "token", "authorization", "cookie", "header", "credential", "password",
    "apikey", "uri", "url", "adb", "binder", "desktop", "transport", "session", "protocol",
    "namespace", "server", "serverid", "toolname", "revision", "fingerprint", "path",
    "filepath", "filename", "directory", "cwd", "message", "error", "reason", "detail",
    "details", "stack", "stacktrace", "trace", "exception",
)
private val MCP_URL_PATTERN = Regex("""(?i)\b(?:https?|wss?)://[^\s\"'<>]+""")
private val MCP_GRANT_REF_PATTERN = Regex("""(?i)\bmcp-grant:[a-z0-9-]{1,128}\b""")
private val MCP_SECRET_REF_PATTERN = Regex("""(?i)\bmcp:[a-z0-9-]{16,128}\b""")
private val MCP_ERROR_TEXT_PATTERN = Regex(
    """(?i)\b(?:exception|stack\s*trace|traceback|remote\s+error|error\s+message)\b[^\n]{0,512}""",
)
private val MCP_HOSTNAME_PATTERN = Regex(
    """(?i)(?<![a-z0-9@])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?::\d{1,5})?(?![a-z0-9])""",
)
private val MCP_CONTROL_PATTERN = Regex("[\\u0000-\\u001f\\u007f]")

private const val MAX_SNAPSHOTS = 128
