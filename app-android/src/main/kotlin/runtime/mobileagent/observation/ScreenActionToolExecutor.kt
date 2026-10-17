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

/**
 * Optional companion to the selected ADB authority; one frozen instance per Run.
 *
 * [observation] defaults to the same object when it also serves the app-local
 * observation channel (the production registry does both), so the follow-up
 * read is offered exactly where it can actually be served.
 */
class ScreenActionToolExecutor(
    private val port: ScreenActionPort,
    private val authority: () -> ScreenActionAuthority?,
    private val liveContext: () -> Boolean,
    private val observation: ScreenObservationPort? = port as? ScreenObservationPort,
) : ToolExecutor {
    private val admitted = port.access.value.takeIf { it.actionsReady }
    private val admittedAuthority = authority()?.takeIf { it.authority != Authority.NONE }
    private val admittedObservation = observation?.access?.value?.takeIf { it.ready }
    /** The follow-up read collaborator, present exactly when the observation channel is. */
    private val followUp = observation?.let { ScreenFollowUpRead(it, admittedObservation) }
    private val calls = ConcurrentHashMap<String, Bound>()
    @Volatile private var closed = false
    override val specs = if (admitted == null || admittedAuthority == null) emptyList() else listOf(
        ToolSpec("device_act", DESCRIPTION, SCHEMA, "device.interact", true),
    )

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (specs.none { it.name == call.name } || call.callId.length !in 1..256) return ToolResult.Invalid("SCREEN_ACTION_UNAVAILABLE")
        val parsed = parse(call.argumentsJson) ?: return ToolResult.Invalid("SCREEN_ACTION_INVALID_REQUEST")
        if (parsed.observeAfter != null && !observationReady()) return ToolResult.Denied("SCREEN_ACTION_OBSERVE_UNAVAILABLE")
        if (!authorized()) return ToolResult.Denied("SCREEN_ACTION_ACCESS_CHANGED")
        if (calls.size >= 128 && !calls.containsKey(call.callId)) return ToolResult.Denied("SCREEN_CALL_LIMIT")
        val bound = calls.putIfAbsent(call.callId, Bound(call, parsed.request, parsed.observeAfter)) ?: calls.getValue(call.callId)
        if (bound.call != call) return ToolResult.Invalid("SCREEN_CALL_ID_REUSED")
        val cached = synchronized(bound) { bound.result }
        if (cached != null) {
            if (cached is ToolResult.UnknownOutcome) return ToolResult.Denied("SCREEN_ACTION_OUTCOME_UNKNOWN")
            // The action never repeats; the answer (including a bundled snapshot) is
            // disclosed again only while the live authorization still covers it.
            return if (authorizeReplay(call)) cached
            else ToolResult.Denied("SCREEN_RESULT_WITHHELD", ToolResult.Completion.COMPLETED_WITHHELD)
        }
        return synchronized(bound) {
            if (bound.started) ToolResult.Invalid("SCREEN_CALL_IN_PROGRESS") else ToolResult.NeedsApproval
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
            dispatch(bound)
        } catch (cancel: CancellationException) {
            // The OS already confirmed the action: a cancelled follow-up read settles as a
            // definite result instead of UNKNOWN, so the action is never replayed.
            if (bound.actionConfirmed) synchronized(bound) {
                bound.result = bound.result ?: ToolResult.Value(performed(CANCELLED_OBSERVATION, true))
            }
            throw cancel
        }
        synchronized(bound) { bound.result = result }
        return result
    }

    /** Exactly one OS dispatch per call id; the optional follow-up read follows it. */
    private suspend fun dispatch(bound: Bound): ToolResult {
        val baseline = if (bound.observeAfter?.conditional == true) (observation?.changes?.value ?: 0L) else 0L
        val performed = try {
            port.perform(bound.request, ::authorized) {
                synchronized(bound) {
                    if (closed) throw ScreenObservationException("SCREEN_ACTION_CLOSED")
                    bound.dispatched = true
                }
            }
        } catch (cancel: CancellationException) {
            if (bound.dispatched) return unknown() else throw cancel
        } catch (failure: Exception) {
            if (bound.dispatched) return unknown()
            else return ToolResult.Denied((failure as? ScreenObservationException)?.code ?: "SCREEN_ACTION_FAILED")
        }
        // false also covers an OS-side timeout: do not advertise it as safe to retry.
        if (!performed) return unknown()
        bound.actionConfirmed = true
        val plan = bound.observeAfter ?: return ToolResult.Value(PERFORMED)
        return ToolResult.Value(observeAfter(bound, plan, baseline))
    }

    /**
     * The follow-up read of an action the OS already confirmed. It can never turn the
     * action fact into a failure: a timeout, a failed read, a revoked channel or an
     * oversized payload is reported as an observation status on a performed envelope,
     * and a revoked channel discloses no fresh screen content at all.
     */
    private suspend fun observeAfter(bound: Bound, plan: ScreenObserveAfter, baseline: Long): String {
        val read = followUp ?: return performed(OBSERVE_UNAVAILABLE, true)
        val outcome = read.read(plan, baseline) { observationAuthorized() }
        outcome.sequence?.let { bound.sequence = it }
        return performed(outcome.json, observeAgain = !outcome.actionable)
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean {
        val bound = calls[call.callId] ?: return false
        val replayable = synchronized(bound) {
            bound.call == call && bound.result != null && bound.result !is ToolResult.UnknownOutcome &&
                (bound.sequence == null || bound.sequence == observation?.changes?.value)
        }
        return replayable && authorized()
    }

    /** Execution fact only: safe during cancellation/revocation and never discloses a captured screen. */
    internal fun isActionConfirmed(callId: String): Boolean = calls[callId]?.actionConfirmed == true
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

    private fun observationReady(): Boolean = observation != null && admittedObservation != null

    /** This action follow-up also requires the action's admitted authority and live Run to remain current. */
    private suspend fun observationAuthorized(): Boolean {
        val channel = observation ?: return false
        val admittedAccess = admittedObservation ?: return false
        if (closed || channel.access.value != admittedAccess) return false
        if (!authorized()) return false
        return !closed && channel.access.value == admittedAccess
    }

    private suspend fun authorized(): Boolean {
        currentCoroutineContext().ensureActive()
        if (closed || admitted == null || admittedAuthority == null || port.access.value != admitted) return false
        val allowed = withContext(Dispatchers.IO) {
            runCatching { authority() == admittedAuthority && liveContext() }.getOrDefault(false)
        }
        return allowed && !closed && port.access.value == admitted && authority() == admittedAuthority
    }

    private class Bound(val call: ToolCall, val request: ScreenActionRequest, val observeAfter: ScreenObserveAfter?) {
        var started = false
        @Volatile var dispatched = false
        @Volatile var actionConfirmed = false
        var result: ToolResult? = null
        @Volatile var sequence: Long? = null
    }

    private data class Parsed(val request: ScreenActionRequest, val observeAfter: ScreenObserveAfter?)

    private fun parse(arguments: String): Parsed? = runCatching {
        require(arguments.length <= 16_384)
        val obj = Json.parseToJsonElement(arguments).jsonObject
        require(obj.keys.all { it in ARGUMENTS })
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
        val observeAfter = ScreenObserveAfter.parse(obj["observe_after"])
        Parsed(ScreenActionRequest(id, action, node, text), observeAfter)
    }.getOrNull()

    /** The action fact is stated first; the observation is a separate, nested fact. */
    private fun performed(observationJson: String, observeAgain: Boolean): String = buildJsonObject {
        put("status", "performed"); put("action_confirmed", true); put("observe_again", observeAgain)
        put("observation", Json.parseToJsonElement(observationJson))
    }.toString()

    companion object {
        /** The unchanged answer for an action without a follow-up read. */
        private const val PERFORMED = "{\"status\":\"performed\",\"observe_again\":true}"
        /** Defensive payloads for the two states the follow-up collaborator cannot be asked for. */
        private const val CANCELLED_OBSERVATION = "{\"status\":\"cancelled\"}"
        private const val OBSERVE_UNAVAILABLE = "{\"status\":\"unavailable\",\"reason\":\"SCREEN_ACTION_OBSERVE_UNAVAILABLE\"}"
        private val ARGUMENTS = setOf("observation_id", "action", "node_index", "text", "observe_after")
        private fun unknown() = ToolResult.UnknownOutcome("SCREEN_ACTION_OUTCOME_UNKNOWN")
        private const val DESCRIPTION = "Act on the latest stable device_observe result: click, long-click, set text, scroll, or navigate back/home/recents. Requires optional action consent and the selected ADB authority. Use a listed node action. Each observation allows one action. Prefer the optional observe_after object over a separate device_observe/device_wait_for round: an empty object captures one fresh observation, while package_name and/or text waits (timeout_ms, default 5000) for a new stable screen that matches. An accepted action does not prove a later screen transition finished; matched is true only when the requested condition was actually observed, and a performed action with a timeout, unavailable or withheld observation is still performed. Never automatically retry an unknown outcome."
        private const val SCHEMA = """{"type":"object","additionalProperties":false,"required":["observation_id","action"],"properties":{"observation_id":{"type":"string"},"action":{"type":"string","enum":["click","long_click","set_text","scroll_forward","scroll_backward","back","home","recents"]},"node_index":{"type":"integer","minimum":0,"maximum":159},"text":{"type":"string","maxLength":2000},"observe_after":{"type":"object","additionalProperties":false,"properties":{"package_name":{"type":"string","minLength":1,"maxLength":200},"text":{"type":"string","minLength":1,"maxLength":200},"timeout_ms":{"type":"integer","minimum":1,"maximum":10000}}}}}"""
    }
}
