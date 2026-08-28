// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
    val capability: String,
    val sideEffect: Boolean,
)

data class ToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

sealed interface ToolResult {
    data class Value(val json: String) : ToolResult
    data class Denied(val reason: String) : ToolResult
    data class Invalid(val reason: String) : ToolResult
    data object NeedsApproval : ToolResult
}

data class ToolContext(
    val search: (query: String, knowledgeBaseIds: List<String>, topK: Int) -> String,
    val readDocument: (documentId: String, maxChars: Int) -> String,
    val httpGet: (url: String) -> String = { error("HTTP is not configured") },
    val allowedHosts: Set<String> = emptySet(),
    val grantedKnowledgeBaseIds: Set<String> = emptySet(),
    val grantedMethods: Set<String> = emptySet(),
    val documentKnowledgeBaseId: (documentId: String) -> String? = { null },
)

class ToolBroker(
    private val effectiveCapabilities: Set<String>,
    private val context: ToolContext,
    private val autoApproveSideEffects: Boolean = false,
    private val liveGrant: (() -> PermissionGrant)? = null,
) {
    private val completed = linkedMapOf<String, ToolResult>()
    private val pending = linkedMapOf<String, ToolCall>()

    fun invoke(call: ToolCall): ToolResult {
        val grant = liveGrant?.invoke()
        val caps = activeCapabilities(grant)
        val ctx = activeContext(grant)
        completed[call.callId]?.let { remembered ->
            if (caps.isEmpty() && BuiltinTools.byName[call.name]?.capability?.isNotEmpty() == true) {
                return ToolResult.Denied("Current grant is empty or revoked")
            }
            return remembered
        }
        val spec = BuiltinTools.byName[call.name] ?: return ToolResult.Invalid("Unknown tool ${call.name}")
        val args = parseObject(call.argumentsJson) ?: return ToolResult.Invalid("Tool arguments are incomplete JSON")
        val missing = specRequired(spec).filter { key -> !args.containsKey(key) }
        if (missing.isNotEmpty()) {
            return ToolResult.Invalid("Missing parameters: ${missing.joinToString()}")
        }
        if (spec.capability.isNotEmpty() && spec.capability !in caps) {
            return remember(call.callId, ToolResult.Denied("Capability ${spec.capability} is not granted"))
        }
        val denied = authorize(spec, args, ctx)
        if (denied != null) return remember(call.callId, denied)
        if (spec.sideEffect && !autoApproveSideEffects) {
            pending[call.callId] = call
            return ToolResult.NeedsApproval
        }
        val result = runCatching { execute(spec.name, args, ctx) }.fold(
            onSuccess = { ToolResult.Value(it) },
            onFailure = { ToolResult.Invalid(it.message ?: "tool failed") },
        )
        return remember(call.callId, result)
    }

    fun approve(callId: String): ToolResult {
        val call = pending.remove(callId) ?: return ToolResult.Invalid("No pending side-effect call")
        completed.remove(callId)
        val grant = liveGrant?.invoke()
        val caps = activeCapabilities(grant)
        val ctx = activeContext(grant)
        val spec = BuiltinTools.byName[call.name] ?: return ToolResult.Invalid("Unknown tool ${call.name}")
        val args = parseObject(call.argumentsJson) ?: return ToolResult.Invalid("Tool arguments are incomplete JSON")
        if (spec.capability.isNotEmpty() && spec.capability !in caps) {
            return remember(call.callId, ToolResult.Denied("Capability ${spec.capability} is not granted"))
        }
        val denied = authorize(spec, args, ctx)
        if (denied != null) return remember(call.callId, denied)
        val result = runCatching { execute(spec.name, args, ctx) }.fold(
            onSuccess = { ToolResult.Value(it) },
            onFailure = { ToolResult.Invalid(it.message ?: "tool failed") },
        )
        return remember(call.callId, result)
    }

    private fun activeCapabilities(grant: PermissionGrant?): Set<String> {
        if (grant == null) return effectiveCapabilities
        return if (grant.revoked) emptySet() else grant.capabilities
    }

    private fun activeContext(grant: PermissionGrant?): ToolContext {
        if (grant == null) return context
        if (grant.revoked) {
            return context.copy(
                grantedKnowledgeBaseIds = emptySet(),
                allowedHosts = emptySet(),
                grantedMethods = emptySet(),
            )
        }
        return context.copy(
            grantedKnowledgeBaseIds = grant.knowledgeBaseIds,
            allowedHosts = grant.hosts,
            grantedMethods = grant.methods,
        )
    }

    private fun authorize(spec: ToolSpec, args: JsonObject, ctx: ToolContext): ToolResult.Denied? {
        if (spec.name == "read_document") {
            if (ctx.grantedKnowledgeBaseIds.isEmpty()) {
                return ToolResult.Denied("No authorized knowledge base in the current grant")
            }
            val documentId = args.string("documentId")
            val kb = ctx.documentKnowledgeBaseId(documentId)
            if (kb == null || kb !in ctx.grantedKnowledgeBaseIds) {
                return ToolResult.Denied("Document is not in an authorized knowledge base")
            }
        }
        if (spec.name == "http_request") {
            val method = (args["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
            val allowed = ctx.grantedMethods.map { it.uppercase() }.toSet()
            if (allowed.isNotEmpty() && method !in allowed) {
                return ToolResult.Denied("HTTP method is not granted")
            }
        }
        return null
    }

    private fun remember(id: String, result: ToolResult): ToolResult {
        completed[id] = result
        return result
    }

    private fun execute(name: String, args: JsonObject, ctx: ToolContext): String = when (name) {
        "knowledge_search" -> {
            val query = args.string("query")
            val topK = args["topK"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8
            val requested = args["knowledgeBaseIds"]?.toString()?.let { raw ->
                Regex("\"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
            }.orEmpty()
            val allowed = ctx.grantedKnowledgeBaseIds
            val ids = if (requested.isEmpty()) allowed.toList() else requested.filter { it in allowed }
            if (ids.isEmpty()) {
                """{"hits":[],"warning":"No authorized knowledge base in the current grant"}"""
            } else {
                capOutput(ctx.search(query, ids, topK))
            }
        }
        "read_document" -> {
            val requested = args["maxChars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 4000
            val maxChars = requested.coerceIn(0, HttpPolicy.MAX_READ_DOCUMENT_CHARS)
            capOutput(ctx.readDocument(args.string("documentId"), maxChars))
        }
        "calculator" -> {
            val value = Calculator.eval(args.string("expression"))
            """{"value":$value}"""
        }
        "http_request" -> {
            val url = args.string("url")
            val method = args["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
            if (method.uppercase() != "GET") error("Only GET is allowed without extra confirmation")
            HttpPolicy.assertRequest(url, ctx.allowedHosts)
            capOutput(ctx.httpGet(url))
        }
        else -> error("Unknown tool")
    }

    private fun parseObject(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return element as? JsonObject
    }

    private fun specRequired(spec: ToolSpec): List<String> {
        val params = runCatching { Json.parseToJsonElement(spec.parametersJson).jsonObject }.getOrNull() ?: return emptyList()
        val required = params["required"]?.toString() ?: return emptyList()
        return Regex("\"([^\"]+)\"").findAll(required).map { it.groupValues[1] }.toList()
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: error("missing $key")

    private fun capOutput(value: String): String {
        if (value.length <= HttpPolicy.MAX_TOOL_OUTPUT_CHARS) return value
        error("Tool output exceeds ${HttpPolicy.MAX_TOOL_OUTPUT_CHARS} characters")
    }
}

object BuiltinTools {
    val knowledgeSearch = ToolSpec(
        name = "knowledge_search",
        description = "Search authorized local knowledge bases",
        parametersJson = """{"type":"object","required":["query"],"properties":{"query":{"type":"string"},"knowledgeBaseIds":{"type":"array"},"topK":{"type":"integer"}}}""",
        capability = "knowledge.search",
        sideEffect = false,
    )
    val readDocument = ToolSpec(
        name = "read_document",
        description = "Read a document that belongs to an authorized knowledge base",
        parametersJson = """{"type":"object","required":["documentId"],"properties":{"documentId":{"type":"string"},"maxChars":{"type":"integer"}}}""",
        capability = "knowledge.read",
        sideEffect = false,
    )
    val calculator = ToolSpec(
        name = "calculator",
        description = "Evaluate a numeric expression",
        parametersJson = """{"type":"object","required":["expression"],"properties":{"expression":{"type":"string"}}}""",
        capability = "",
        sideEffect = false,
    )
    val httpRequest = ToolSpec(
        name = "http_request",
        description = "GET an allow-listed HTTPS URL",
        parametersJson = """{"type":"object","required":["url"],"properties":{"url":{"type":"string"},"method":{"type":"string"}}}""",
        capability = "network.http",
        sideEffect = true,
    )

    val all = listOf(knowledgeSearch, readDocument, calculator, httpRequest)
    val byName = all.associateBy { it.name }
}

object Calculator {
    fun eval(expression: String): Double {
        val compact = expression.replace(" ", "")
        require(compact.matches(Regex("[0-9.+\\-*/()]+"))) { "Expression is not a numeric formula" }
        return Parser(compact).parse()
    }

    private class Parser(private val text: String) {
        private var i = 0
        fun parse(): Double {
            val value = expr()
            require(i >= text.length) { "Unexpected input" }
            return value
        }

        private fun expr(): Double {
            var v = term()
            while (i < text.length && (text[i] == '+' || text[i] == '-')) {
                val op = text[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = unary()
            while (i < text.length && (text[i] == '*' || text[i] == '/')) {
                val op = text[i++]
                val r = unary()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        private fun unary(): Double {
            if (i < text.length && text[i] == '-') {
                i++
                return -unary()
            }
            return primary()
        }

        private fun primary(): Double {
            if (i < text.length && text[i] == '(') {
                i++
                val v = expr()
                require(i < text.length && text[i] == ')') { "Missing )" }
                i++
                return v
            }
            val start = i
            while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
            require(i > start) { "Expected number" }
            return text.substring(start, i).toDouble()
        }
    }
}

fun toolSpecsAsMaps(): List<Map<String, String>> = BuiltinTools.all.map {
    mapOf("name" to it.name, "description" to it.description, "parameters" to it.parametersJson)
}

object HttpPolicy {
    const val MAX_TOOL_OUTPUT_CHARS = 32_768
    const val MAX_READ_DOCUMENT_CHARS = 16_384
    const val MAX_HTTP_RESPONSE_BYTES = 1 * 1024 * 1024
    const val MAX_REDIRECTS = 3

    fun assertRequest(url: String, allowedHosts: Set<String>) {
        val uri = runCatching { URI(url) }.getOrNull() ?: error("URL is not valid")
        val scheme = uri.scheme?.lowercase() ?: error("URL scheme is missing")
        if (scheme != "https") error("Only HTTPS URLs are allowed")
        val host = requestHost(uri) ?: error("URL host is missing")
        if (isIpLiteral(host)) error("IP literals are not allowed")
        if (host !in allowedHosts.map { it.lowercase().trim('.') }.toSet()) {
            error("Host $host is not in the HTTP allow-list")
        }
        if (isForbiddenHost(host)) error("Loopback or private HTTP is not allowed")
        uri.port.takeIf { it > 0 }?.let { port ->
            if (port != 443) error("Only HTTPS port 443 is allowed")
        }
    }

    fun assertDestination(
        url: String,
        allowedHosts: Set<String>,
        resolve: (String) -> List<java.net.InetAddress> = { host ->
            java.net.InetAddress.getAllByName(host).toList()
        },
    ) {
        assertRequest(url, allowedHosts)
        val host = requestHost(URI(url)) ?: error("URL host is missing")
        val addresses = resolve(host)
        if (addresses.isEmpty()) error("Host did not resolve")
        addresses.forEach { address ->
            if (isForbiddenAddress(address)) error("Resolved address is not allowed")
        }
    }

    fun requestHost(uri: URI): String? {
        val host = uri.host?.lowercase()?.trim('.')
        if (!host.isNullOrBlank()) return host
        val authority = uri.rawAuthority?.substringBefore('@')?.let { raw ->
            if (raw.contains('@')) uri.rawAuthority.substringAfter('@') else uri.rawAuthority
        } ?: uri.authority
        return authority?.substringBefore(']')?.trim('[', ']')?.substringBefore(':')?.lowercase()?.trim('.')?.ifBlank { null }
    }

    fun isIpLiteral(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h.contains(':')) return true
        if (h.startsWith("0x")) return true
        if (h.all { it.isDigit() } && h.isNotEmpty()) return true
        val parts = h.split('.')
        return parts.size in 1..4 && parts.all { part -> part.toIntOrNull() != null }
    }

    fun isForbiddenHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h == "::1" || h == "0.0.0.0") return true
        if (isIpLiteral(h) && isForbiddenAddress(runCatching { java.net.InetAddress.getByName(h) }.getOrNull())) return true
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return true
        if (h.startsWith("172.")) {
            val second = h.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")) return true
        return false
    }

    fun isForbiddenAddress(address: java.net.InetAddress?): Boolean {
        if (address == null) return true
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        if (address is java.net.Inet6Address) {
            val first = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: return true
            if (first in 0xfc..0xfd) return true
            if (first == 0xfe && (address.address.getOrNull(1)?.toInt()?.and(0xC0) == 0x80)) return true
        }
        return false
    }
}

object HostHttp {
    fun get(
        url: String,
        allowedHosts: Set<String>,
        resolve: (String) -> List<java.net.InetAddress> = { host ->
            java.net.InetAddress.getAllByName(host).toList()
        },
    ): String {
        var current = url
        repeat(HttpPolicy.MAX_REDIRECTS + 1) { hop ->
            HttpPolicy.assertDestination(current, allowedHosts, resolve)
            val uri = URI(current)
            val host = HttpPolicy.requestHost(uri) ?: error("URL host is missing")
            val pinned = resolve(host).firstOrNull() ?: error("Host did not resolve")
            if (HttpPolicy.isForbiddenAddress(pinned)) error("Resolved address is not allowed")
            val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Redirect is missing Location")
                connection.disconnect()
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                error("HTTP $code")
            }
            val body = connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > HttpPolicy.MAX_HTTP_RESPONSE_BYTES) error("HTTP response exceeds limit")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            connection.disconnect()
            return String(body, Charsets.UTF_8)
        }
        error("Too many HTTP redirects")
    }
}
