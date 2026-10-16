// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Optional companion to the selected ADB authority; one frozen instance per Run. */
class ScreenActionToolExecutor(
    private val port: ScreenActionPort,
    private val authority: () -> ScreenActionAuthority?,
    private val liveContext: () -> Boolean,
) : ToolExecutor {
    private val admitted = port.access.value.takeIf { it.actionsReady }
    private val admittedAuthority = authority()?.takeIf { it.authority != Authority.NONE }
    private val calls = ConcurrentHashMap<String, Bound>()
    @Volatile private var closed = false
    override val specs = if (admitted == null || admittedAuthority == null) emptyList() else listOf(
        ToolSpec("device_act", "Act on the latest stable device_observe result: click, long-click, set text, scroll, or navigate back/home/recents. Requires optional action consent and the selected ADB authority. Use a listed node action. Each observation allows one action; observe again afterward. An accepted action does not prove a later screen transition finished. Never automatically retry an unknown outcome.", SCHEMA, "device.interact", true),
    )

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (specs.none { it.name == call.name } || call.callId.length !in 1..256) return ToolResult.Invalid("SCREEN_ACTION_UNAVAILABLE")
        val request = parse(call.argumentsJson) ?: return ToolResult.Invalid("SCREEN_ACTION_INVALID_REQUEST")
        if (!authorized()) return ToolResult.Denied("SCREEN_ACTION_ACCESS_CHANGED")
        if (calls.size >= 128 && !calls.containsKey(call.callId)) return ToolResult.Denied("SCREEN_CALL_LIMIT")
        val bound = calls.putIfAbsent(call.callId, Bound(call, request)) ?: calls.getValue(call.callId)
        if (bound.call != call) return ToolResult.Invalid("SCREEN_CALL_ID_REUSED")
        return synchronized(bound) {
            if (bound.result is ToolResult.UnknownOutcome) ToolResult.Denied("SCREEN_ACTION_OUTCOME_UNKNOWN")
            else bound.result ?: if (bound.started) ToolResult.Invalid("SCREEN_CALL_IN_PROGRESS") else ToolResult.NeedsApproval
        }
    }

    override suspend fun approve(callId: String): ToolResult {
        val bound = calls[callId] ?: return ToolResult.Invalid("SCREEN_APPROVAL_MISSING")
        if (!authorized()) return ToolResult.Denied("SCREEN_ACTION_ACCESS_CHANGED")
        val begin = synchronized(bound) {
            if (bound.started || bound.result != null) false else { bound.started = true; true }
        }
        if (!begin) return ToolResult.Invalid("SCREEN_APPROVAL_CONSUMED")
        val result = try {
            val performed = port.perform(bound.request, ::authorized) {
                synchronized(bound) {
                    if (closed) throw ScreenObservationException("SCREEN_ACTION_CLOSED")
                    bound.dispatched = true
                }
            }
            // false also covers an OS-side timeout: do not advertise it as safe to retry.
            if (performed) ToolResult.Value("{\"status\":\"performed\",\"observe_again\":true}")
            else unknown()
        } catch (cancel: CancellationException) {
            if (bound.dispatched) unknown() else throw cancel
        } catch (failure: Exception) {
            if (bound.dispatched) unknown()
            else ToolResult.Denied((failure as? ScreenObservationException)?.code ?: "SCREEN_ACTION_FAILED")
        }
        synchronized(bound) { bound.result = result }
        return result
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean {
        val bound = calls[call.callId] ?: return false
        val replayable = synchronized(bound) { bound.call == call && bound.result != null && bound.result !is ToolResult.UnknownOutcome }
        return replayable && authorized()
    }
    override suspend fun reject(callId: String) = settle(callId, "SCREEN_APPROVAL_DENIED")
    override suspend fun expire(callId: String) = settle(callId, "SCREEN_APPROVAL_EXPIRED")
    override suspend fun close() { closed = true; calls.clear() }

    private fun settle(id: String, code: String): ToolResult {
        val bound = calls[id] ?: return ToolResult.Invalid("SCREEN_APPROVAL_MISSING")
        return synchronized(bound) {
            bound.result ?: if (bound.started) ToolResult.Invalid("SCREEN_CALL_IN_PROGRESS")
            else ToolResult.Denied(code).also { bound.result = it }
        }
    }

    private suspend fun authorized(): Boolean {
        currentCoroutineContext().ensureActive()
        if (closed || admitted == null || admittedAuthority == null || port.access.value != admitted) return false
        val allowed = withContext(Dispatchers.IO) {
            runCatching { authority() == admittedAuthority && liveContext() }.getOrDefault(false)
        }
        return allowed && !closed && port.access.value == admitted && authority() == admittedAuthority
    }

    private class Bound(val call: ToolCall, val request: ScreenActionRequest) {
        var started = false
        @Volatile var dispatched = false
        var result: ToolResult? = null
    }

    private fun parse(arguments: String): ScreenActionRequest? = runCatching {
        require(arguments.length <= 16_384)
        val obj = Json.parseToJsonElement(arguments).jsonObject
        require(obj.keys.all { it in setOf("observation_id", "action", "node_index", "text") })
        fun string(key: String): String? = obj[key]?.let {
            val p = it as? JsonPrimitive ?: error("Invalid")
            require(p.isString && '\u0000' !in p.content)
            p.content
        }
        val id = string("observation_id") ?: error("Missing observation")
        require(UUID.fromString(id).toString() == id)
        val action = ScreenAction.entries.firstOrNull { it.wireName == string("action") } ?: error("Invalid action")
        val node = obj["node_index"]?.let {
            val p = it as? JsonPrimitive ?: error("Invalid")
            require(!p.isString)
            p.intOrNull?.also { n -> require(n in 0..159) } ?: error("Invalid node")
        }
        require((node != null) == action.needsNode)
        val text = string("text")
        require((text != null) == (action == ScreenAction.SET_TEXT))
        require(text == null || text.length <= 2000)
        ScreenActionRequest(id, action, node, text)
    }.getOrNull()

    companion object {
        private fun unknown() = ToolResult.UnknownOutcome("SCREEN_ACTION_OUTCOME_UNKNOWN")
        private const val SCHEMA = """{"type":"object","additionalProperties":false,"required":["observation_id","action"],"properties":{"observation_id":{"type":"string"},"action":{"type":"string","enum":["click","long_click","set_text","scroll_forward","scroll_backward","back","home","recents"]},"node_index":{"type":"integer","minimum":0,"maximum":159},"text":{"type":"string","maxLength":2000}}}"""
    }
}
