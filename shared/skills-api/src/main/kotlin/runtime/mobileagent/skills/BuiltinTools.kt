// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import runtime.mobileagent.skills.tooling.AuthorizationDecision
import runtime.mobileagent.skills.tooling.AuthorizationEvaluator
import runtime.mobileagent.skills.tooling.ToolError

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
    /** A known, typed operational failure that is safe to project to the model and UI. */
    data class Failure(val error: ToolError) : ToolResult
    /** A request may have executed externally; only an acknowledged new invocation may retry. */
    data class UnknownOutcome(val reason: String) : ToolResult
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
    private val requests = linkedMapOf<String, ToolCall>()
    private val completionScopes = linkedMapOf<String, CompletionScope>()

    private data class CompletionScope(
        val capabilities: Set<String>,
        val knowledgeBaseIds: Set<String>,
        val hosts: Set<String>,
        val methods: Set<String>,
        val grant: PermissionGrant?,
    )

    private fun scope(caps: Set<String>, ctx: ToolContext, grant: PermissionGrant?) = CompletionScope(
        caps.toSet(), ctx.grantedKnowledgeBaseIds.toSet(), ctx.allowedHosts.toSet(), ctx.grantedMethods.toSet(), grant,
    )

    /**
     * Disclosure check for cached results: the completion scope must still
     * match live facts AND the grant must not have expired since completion.
     * Scope-equality alone cannot see time, so expiry is evaluated explicitly
     * at BEFORE_DISCLOSURE.  Never re-executes the tool.
     */
    @Synchronized
    fun authorizeReplay(call: ToolCall): Boolean {
        val grant = liveGrant?.invoke()
        if (AuthorizationEvaluator.isExpired(grant?.scopesJson)) return false
        // Fail closed: a call this broker never completed must never disclose.
        // (Pure-computation tools such as calculator still allow replay once
        // completed: their empty-capability scope is stable, so the equality
        // check below returns true without any grant semantics.)
        val remembered = completed[call.callId] ?: return false
        if (remembered is ToolResult.UnknownOutcome) return false
        return completionScopes[call.callId] == scope(activeCapabilities(grant), activeContext(grant), grant)
    }

    @Synchronized
    fun invoke(call: ToolCall): ToolResult {
        if (call.callId.isBlank()) return ToolResult.Invalid("Tool call ID is missing")
        if (requests[call.callId]?.let { it != call } == true) {
            return ToolResult.Invalid("Tool call ID was already used for a different request")
        }
        val grant = liveGrant?.invoke()
        // Expiry is time, not scope: it must fail both fresh dispatch and
        // cached disclosure even when the stored scope row is unchanged.
        if (AuthorizationEvaluator.isExpired(grant?.scopesJson)) {
            return ToolResult.Denied(AuthorizationEvaluator.deniedReason(AuthorizationDecision.EXPIRED))
        }
        val caps = activeCapabilities(grant)
        val ctx = activeContext(grant)
        completed[call.callId]?.let { remembered ->
            if (remembered is ToolResult.UnknownOutcome) {
                return ToolResult.Denied("Tool outcome is unknown; it cannot be replayed")
            }
            if (completionScopes[call.callId] != scope(caps, ctx, grant)) {
                return ToolResult.Denied("Current grant changed; cached tool output is unavailable")
            }
            return remembered
        }
        val spec = BuiltinTools.byName[call.name] ?: return ToolResult.Invalid("Unknown tool ${call.name}")
        val args = parseObject(call.argumentsJson) ?: return ToolResult.Invalid("Tool arguments are incomplete JSON")
        val missing = specRequired(spec).filter { key -> !args.containsKey(key) }
        if (missing.isNotEmpty()) {
            return ToolResult.Invalid("Missing parameters: ${missing.joinToString()}")
        }
        validateArguments(spec, args)?.let { return ToolResult.Invalid(it) }
        requests[call.callId] = call
        completionScopes[call.callId] = scope(caps, ctx, grant)
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
            onFailure = { toolFailure(it) },
        )
        return remember(call.callId, result)
    }

    @Synchronized
    fun approve(callId: String): ToolResult {
        val call = pending.remove(callId) ?: return ToolResult.Invalid("No pending side-effect call")
        completed.remove(callId)
        val grant = liveGrant?.invoke()
        if (AuthorizationEvaluator.isExpired(grant?.scopesJson)) {
            return ToolResult.Denied(AuthorizationEvaluator.deniedReason(AuthorizationDecision.EXPIRED))
        }
        val caps = activeCapabilities(grant)
        val ctx = activeContext(grant)
        completionScopes[call.callId] = scope(caps, ctx, grant)
        val spec = BuiltinTools.byName[call.name] ?: return ToolResult.Invalid("Unknown tool ${call.name}")
        val args = parseObject(call.argumentsJson) ?: return ToolResult.Invalid("Tool arguments are incomplete JSON")
        if (spec.capability.isNotEmpty() && spec.capability !in caps) {
            return remember(call.callId, ToolResult.Denied("Capability ${spec.capability} is not granted"))
        }
        val denied = authorize(spec, args, ctx)
        if (denied != null) return remember(call.callId, denied)
        val result = runCatching { execute(spec.name, args, ctx) }.fold(
            onSuccess = { ToolResult.Value(it) },
            onFailure = { toolFailure(it) },
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

    private fun toolFailure(error: Throwable): ToolResult.Invalid {
        // runInterruptible must see cancellation instead of a cached tool error.
        if (error is InterruptedException || error is CancellationException) throw error
        return ToolResult.Invalid(error.message ?: "tool failed")
    }

    private fun execute(name: String, args: JsonObject, ctx: ToolContext): String = when (name) {
        "knowledge_search" -> {
            val query = args.string("query")
            val topK = args["topK"]?.jsonPrimitive?.intOrNull ?: 8
            val requested = (args["knowledgeBaseIds"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val allowed = ctx.grantedKnowledgeBaseIds
            val ids = if (requested.isEmpty()) allowed.toList() else requested.filter { it in allowed }
            if (ids.isEmpty()) {
                """{"hits":[],"warning":"No authorized knowledge base in the current grant"}"""
            } else {
                capOutput(ctx.search(query, ids, topK))
            }
        }
        "read_document" -> {
            val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull ?: 4000
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

    private fun validateArguments(spec: ToolSpec, args: JsonObject): String? {
        val properties = Json.parseToJsonElement(spec.parametersJson).jsonObject.getValue("properties").jsonObject
        for ((key, value) in args) {
            val property = properties[key]?.jsonObject ?: return "Unknown parameter: $key"
            val primitive = value as? JsonPrimitive
            when (property.getValue("type").jsonPrimitive.content) {
                "string" -> if (primitive?.isString != true || primitive.content.isBlank()) return "$key must be a nonblank string"
                "integer" -> {
                    val number = primitive?.takeUnless { it.isString }?.intOrNull ?: return "$key must be an integer"
                    val minimum = property["minimum"]?.jsonPrimitive?.intOrNull
                    val maximum = property["maximum"]?.jsonPrimitive?.intOrNull
                    if ((minimum != null && number < minimum) || (maximum != null && number > maximum)) return "$key is outside its allowed range"
                }
                "array" -> if (value !is JsonArray || value.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() }) {
                    return "$key must be an array of nonblank strings"
                }
            }
        }
        return null
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
        parametersJson = """{"type":"object","additionalProperties":false,"required":["query"],"properties":{"query":{"type":"string","minLength":1},"knowledgeBaseIds":{"type":"array","items":{"type":"string","minLength":1}},"topK":{"type":"integer","minimum":1,"maximum":100}}}""",
        capability = "knowledge.search",
        sideEffect = false,
    )
    val readDocument = ToolSpec(
        name = "read_document",
        description = "Read a document that belongs to an authorized knowledge base",
        parametersJson = """{"type":"object","additionalProperties":false,"required":["documentId"],"properties":{"documentId":{"type":"string","minLength":1},"maxChars":{"type":"integer","minimum":1,"maximum":16384}}}""",
        capability = "knowledge.read",
        sideEffect = false,
    )
    val calculator = ToolSpec(
        name = "calculator",
        description = "Evaluate a numeric expression",
        parametersJson = """{"type":"object","additionalProperties":false,"required":["expression"],"properties":{"expression":{"type":"string","minLength":1}}}""",
        capability = "",
        sideEffect = false,
    )
    val httpRequest = ToolSpec(
        name = "http_request",
        description = "GET an allow-listed HTTPS URL",
        parametersJson = """{"type":"object","additionalProperties":false,"required":["url"],"properties":{"url":{"type":"string","minLength":1},"method":{"type":"string","minLength":1}}}""",
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
        if (uri.rawUserInfo != null) error("URL credentials are not allowed")
        val host = requestHost(uri) ?: error("URL host is missing")
        if (isIpLiteral(host)) error("IP literals are not allowed")
        if (host !in allowedHosts.map { it.lowercase().trim('.') }.toSet()) {
            error("Host $host is not in the HTTP allow-list")
        }
        if (isForbiddenHost(host)) error("Loopback or private HTTP is not allowed")
        if (uri.port != -1 && uri.port != 443) error("Only HTTPS port 443 is allowed")
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
    private const val MAX_TIMEOUT_MILLIS = 30_000L
    private val client by lazy { OkHttpClient() }

    /** Blocking boundary: callers in coroutines must use runInterruptible(Dispatchers.IO). */
    fun get(
        url: String,
        allowedHosts: Set<String>,
        resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    ): String = get(url, allowedHosts, resolve, client)

    /**
     * Send one fixed secret header only to the request's original host. Redirects to another
     * allow-listed host are revalidated but never receive the credential.
     */
    fun getWithSecretHeader(
        url: String,
        allowedHosts: Set<String>,
        headerName: String,
        headerValue: String,
        resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    ): String = getWithSecretHeader(url, allowedHosts, headerName, headerValue, resolve, client)

    internal fun getWithSecretHeader(
        url: String,
        allowedHosts: Set<String>,
        headerName: String,
        headerValue: String,
        resolve: (String) -> List<InetAddress>,
        client: OkHttpClient,
    ): String {
        require(headerName.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Secret header name is invalid" }
        require(headerName.lowercase() !in setOf("authorization", "cookie", "proxy-authorization")) { "Reserved secret header name" }
        require(headerValue.isNotEmpty() && headerValue.length <= 4096 && headerValue.none { it == '\r' || it == '\n' }) {
            "Secret header value is invalid"
        }
        return get(url, allowedHosts, resolve, client, secretHeader = headerName to headerValue)
    }

    // The injected client enables loopback TLS/socket tests; production always uses the private client.
    internal fun get(
        url: String,
        allowedHosts: Set<String>,
        resolve: (String) -> List<InetAddress>,
        client: OkHttpClient,
        timeoutMillis: Long = MAX_TIMEOUT_MILLIS,
        secretHeader: Pair<String, String>? = null,
    ): String {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var current = url
        val originalHost = HttpPolicy.requestHost(URI(url)) ?: error("URL host is missing")
        repeat(HttpPolicy.MAX_REDIRECTS + 1) { hop ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException("HTTP request interrupted")
            HttpPolicy.assertRequest(current, allowedHosts)
            val uri = URI(current)
            val host = HttpPolicy.requestHost(uri) ?: error("URL host is missing")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw InterruptedIOException("HTTP request timed out")
            // New pool for every hop prevents a previously connected/coalesced socket from
            // bypassing that hop's validated DNS. The URL host remains intact for TLS/SNI.
            val pool = ConnectionPool(0, 1, TimeUnit.NANOSECONDS)
            val hopClient = client.newBuilder()
                .dns(ValidatedDns(host, resolve))
                .proxy(Proxy.NO_PROXY)
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    // OkHttp 4.12 may retry 503 + Retry-After: 0 even when connection
                    // retries are disabled. Fail before its automatic follow-up layer.
                    if (response.code == 503) {
                        response.close()
                        throw IOException("HTTP 503")
                    }
                    response
                }
                .connectionPool(pool)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(remaining, TimeUnit.NANOSECONDS)
                .build()
            val request = Request.Builder().url(current).get().apply {
                if (host == originalHost) secretHeader?.let { (name, value) -> header(name, value) }
            }.build()
            // Fail closed if URI and OkHttp interpret the authority differently.
            if (request.url.host.lowercase().trimEnd('.') != host) error("URL host is ambiguous")
            val response = try {
                awaitResponse(hopClient.newCall(request), deadline)
            } finally {
                pool.evictAll()
            }
            if (response.code in 300..399) {
                if (hop == HttpPolicy.MAX_REDIRECTS) error("Too many HTTP redirects")
                val location = response.location ?: error("Redirect is missing Location")
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            return response.body
        }
        error("Too many HTTP redirects")
    }

    private class ValidatedDns(
        private val host: String,
        private val resolve: (String) -> List<InetAddress>,
    ) : Dns {
        private var pinned: List<InetAddress>? = null

        @Synchronized
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.lowercase().trimEnd('.') != host) throw UnknownHostException("Unexpected DNS host")
            pinned?.let { return it }
            // Validation happens inside OkHttp's actual DNS path, not in a separate lookup.
            // DNS is off the waiting thread, so a slow platform resolver cannot hold the caller
            // past the deadline. A cancelled call cannot connect when a late lookup returns.
            val addresses = try {
                resolve(host).toList()
            } catch (error: RuntimeException) {
                throw UnknownHostException("Host resolution failed").apply { initCause(error) }
            }
            if (addresses.isEmpty() || addresses.any(HttpPolicy::isForbiddenAddress)) {
                throw UnknownHostException("Resolved address is not allowed")
            }
            return addresses.also { pinned = it }
        }
    }

    private data class HopResponse(val code: Int, val location: String?, val body: String)

    private fun awaitResponse(call: Call, deadline: Long): HopResponse {
        val done = CountDownLatch(1)
        val outcome = AtomicReference<Result<HopResponse>>()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                outcome.set(Result.failure(e))
                done.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    outcome.set(runCatching {
                        response.use {
                            if (it.code in 300..399) return@runCatching HopResponse(it.code, it.header("Location"), "")
                            if (!it.isSuccessful) error("HTTP ${it.code}")
                            val body = it.body ?: error("HTTP response body is missing")
                            if (body.contentLength() > HttpPolicy.MAX_HTTP_RESPONSE_BYTES) error("HTTP response exceeds limit")
                            val out = java.io.ByteArrayOutputStream()
                            body.byteStream().use { stream ->
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > HttpPolicy.MAX_HTTP_RESPONSE_BYTES) error("HTTP response exceeds limit")
                                    out.write(buffer, 0, count)
                                }
                            }
                            HopResponse(it.code, null, out.toString(Charsets.UTF_8.name()))
                        }
                    })
                } finally {
                    done.countDown()
                }
            }
        })
        try {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0 || !done.await(remaining, TimeUnit.NANOSECONDS)) {
                throw InterruptedIOException("HTTP request timed out")
            }
            return outcome.get().getOrThrow()
        } finally {
            // Cancels even while a callback is reading a slow body; closes the active socket.
            call.cancel()
        }
    }
}
