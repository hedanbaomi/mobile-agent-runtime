// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import java.util.concurrent.ConcurrentHashMap

/** One instance per frozen Run; observations use the existing Agent tool confirmation policy. */
class ScreenObservationToolExecutor(
    private val port: ScreenObservationPort,
    private val liveContext: () -> Boolean = { false },
) : ToolExecutor {
    private val admitted = port.access.value.takeIf { it.ready }
    private val calls = ConcurrentHashMap<String, Bound>()
    override val specs: List<ToolSpec> = if (admitted == null) emptyList() else listOf(
        ToolSpec("device_observe", "Read visible text and controls on the phone using the configured tool confirmation policy. This is not a screenshot. Password subtrees are omitted. Results can be incomplete or unstable; do not infer missing content is absent.", OBSERVE_SCHEMA, "device.observe", false),
        ToolSpec("device_wait_for", "Using the configured tool confirmation policy, wait for a matching phone window/text and return a fresh bounded observation. Events only wake the wait; the condition is checked against a new snapshot. Timeout does not prove an earlier ADB action failed.", WAIT_SCHEMA, "device.observe", false),
    )

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (specs.none { it.name == call.name } || call.callId.length !in 1..256) return ToolResult.Invalid("SCREEN_TOOL_UNAVAILABLE")
        val request = parse(call) ?: return ToolResult.Invalid("SCREEN_INVALID_REQUEST")
        if (!authorized()) return ToolResult.Denied("SCREEN_ACCESS_CHANGED")
        if (calls.size >= 128 && !calls.containsKey(call.callId)) return ToolResult.Denied("SCREEN_CALL_LIMIT")
        val bound = calls.putIfAbsent(call.callId, Bound(call, request)) ?: calls.getValue(call.callId)
        if (bound.call != call) return ToolResult.Invalid("SCREEN_CALL_ID_REUSED")
        val result = synchronized(bound) { bound.result }
        return if (result == null) {
            if (synchronized(bound) { bound.started }) ToolResult.Invalid("SCREEN_CALL_IN_PROGRESS") else ToolResult.NeedsApproval
        } else if (authorizeReplay(call)) result else ToolResult.Denied("SCREEN_RESULT_WITHHELD", ToolResult.Completion.COMPLETED_WITHHELD)
    }

    override suspend fun approve(callId: String): ToolResult {
        val bound = calls[callId] ?: return ToolResult.Invalid("SCREEN_APPROVAL_MISSING")
        if (!authorized()) return ToolResult.Denied("SCREEN_ACCESS_CHANGED")
        val begin = synchronized(bound) {
            if (bound.started || bound.result != null) false else { bound.started = true; true }
        }
        if (!begin) return ToolResult.Invalid("SCREEN_APPROVAL_CONSUMED")
        val result = try {
            val request = bound.request
            val observed = if (bound.call.name == "device_observe") port.capture(request.packageName) else {
                withTimeoutOrNull(request.timeoutMs) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (!authorized()) throw ScreenObservationException("SCREEN_ACCESS_CHANGED")
                        // Capture the event counter before reading, so a concurrent event is never lost.
                        val before = port.changes.value
                        val snapshot = try { port.capture(request.packageName) } catch (e: ScreenObservationException) {
                            if (e.code in setOf("SCREEN_PACKAGE_MISMATCH", "SCREEN_WINDOW_UNAVAILABLE", "SCREEN_WINDOW_CHANGED")) null else throw e
                        }
                        if (snapshot != null && snapshot.stable &&
                            (request.afterSequence == null || snapshot.sequence > request.afterSequence) &&
                            (request.text == null || snapshot.nodes.any { request.text in it.text || request.text in it.description })) {
                            return@withTimeoutOrNull snapshot
                        }
                        combine(port.changes, port.access) { change, access -> change != before || access != admitted }
                            .first { it }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            }
            if (!authorized() || synchronized(bound) { bound.result != null }) ToolResult.Denied("SCREEN_RESULT_WITHHELD", ToolResult.Completion.COMPLETED_WITHHELD)
            else if (observed == null) ToolResult.Value("{\"status\":\"timeout\",\"matched\":false}")
            else {
                bound.sequence = observed.sequence
                val json = render(observed)
                if (json.toByteArray(Charsets.UTF_8).size > 96 * 1024) ToolResult.Invalid("SCREEN_RESULT_LIMIT")
                else ToolResult.Value(json)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: ScreenObservationException) { ToolResult.Denied(failure.code) }
        catch (_: Exception) { ToolResult.Denied("SCREEN_OBSERVATION_FAILED") }
        synchronized(bound) { bound.result = result }
        return result
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean {
        val bound = calls[call.callId] ?: return false
        return bound.call == call && authorized() && (bound.sequence == null || bound.sequence == port.changes.value)
    }

    override suspend fun reject(callId: String): ToolResult = settle(callId, "SCREEN_APPROVAL_DENIED")
    override suspend fun expire(callId: String): ToolResult = settle(callId, "SCREEN_APPROVAL_EXPIRED")
    override suspend fun close() { calls.clear() }
    private fun settle(id: String, reason: String): ToolResult {
        val bound = calls[id] ?: return ToolResult.Invalid("SCREEN_APPROVAL_MISSING")
        return synchronized(bound) { bound.result ?: ToolResult.Denied(reason).also { bound.result = it } }
    }
    private suspend fun authorized(): Boolean {
        val allowed = admitted != null && port.access.value == admitted &&
            withContext(Dispatchers.IO) { runCatching(liveContext).getOrDefault(false) } &&
            port.access.value == admitted
        currentCoroutineContext().ensureActive()
        // Revocation settles only calls without a result yet; an already approved
        // observation keeps its own result and its replay stays gated by authorizeReplay.
        if (!allowed) calls.values.forEach { bound -> synchronized(bound) {
            if (bound.result == null) bound.result = ToolResult.Denied("SCREEN_ACCESS_CHANGED")
        } }
        return allowed
    }

    private data class Request(val packageName: String?, val text: String?, val afterSequence: Long?, val timeoutMs: Long)
    private class Bound(val call: ToolCall, val request: Request) {
        var started = false
        var result: ToolResult? = null
        @Volatile var sequence: Long? = null
    }

    private fun parse(call: ToolCall): Request? = runCatching {
        require(call.argumentsJson.length <= 4096)
        val obj = Json.parseToJsonElement(call.argumentsJson).jsonObject
        val keys = if (call.name == "device_observe") setOf("package_name") else setOf("package_name", "text", "after_sequence", "timeout_ms")
        require(obj.keys.all { it in keys })
        fun string(name: String, max: Int): String? = obj[name]?.let {
            val p = it as? JsonPrimitive ?: error("Invalid")
            require(p.isString && p.content.length in 1..max && '\u0000' !in p.content)
            p.content
        }
        fun number(name: String): Long? = obj[name]?.let {
            val p = it as? JsonPrimitive ?: error("Invalid")
            require(!p.isString)
            p.longOrNull ?: error("Invalid")
        }
        val packageName = string("package_name", 200)
        require(packageName == null || packageName.matches(Regex("[A-Za-z][A-Za-z0-9_.]{0,199}")))
        val after = number("after_sequence")?.also { require(it >= 0) }
        val text = string("text", 200)
        if (call.name == "device_wait_for") require(packageName != null || text != null || after != null)
        val timeout = (number("timeout_ms") ?: 5000).also { require(it in 1..10_000) }
        Request(packageName, text, after, timeout)
    }.getOrNull()

    companion object {
        private const val OBSERVE_SCHEMA = """{"type":"object","additionalProperties":false,"properties":{"package_name":{"type":"string","minLength":1,"maxLength":200}}}"""
        private const val WAIT_SCHEMA = """{"type":"object","additionalProperties":false,"properties":{"package_name":{"type":"string","minLength":1,"maxLength":200},"text":{"type":"string","minLength":1,"maxLength":200},"after_sequence":{"type":"integer","minimum":0},"timeout_ms":{"type":"integer","minimum":1,"maximum":10000}}}"""
        internal fun render(snapshot: ScreenSnapshot): String = buildJsonObject {
            put("status", "observed"); put("package_name", snapshot.packageName); put("window_id", snapshot.windowId)
            put("sequence", snapshot.sequence); put("captured_at_elapsed_ms", snapshot.capturedAtElapsedMs)
            put("truncated", snapshot.truncated); put("stable", snapshot.stable)
            snapshot.observationId?.let { put("observation_id", it) }
            putJsonArray("nodes") { snapshot.nodes.forEach { node -> add(buildJsonObject {
                put("index", node.index); node.parent?.let { put("parent", it) }
                put("text", node.text); put("description", node.description); put("role", node.role)
                putJsonArray("bounds") { node.bounds.forEach { add(JsonPrimitive(it)) } }
                put("clickable", node.clickable); put("editable", node.editable)
                put("scrollable", node.scrollable); put("enabled", node.enabled)
                if (node.actions.isNotEmpty()) putJsonArray("actions") { node.actions.forEach { add(JsonPrimitive(it)) } }
            }) } }
        }.toString()
    }
}
