// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * The optional `observe_after` object of `device_act`: its strict argument shape and
 * the follow-up read it asks for.  It lives beside the action executor so that the
 * executor states the action fact while this collaborator owns the observation fact.
 */
internal data class ScreenObserveAfter(val packageName: String?, val text: String?, val timeoutMs: Long?) {
    /** Any condition waits; an empty object only takes one fresh capture. */
    val conditional: Boolean get() = packageName != null || text != null || timeoutMs != null

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        private val KEYS = setOf("package_name", "text", "timeout_ms")

        /**
         * Strictly parses the optional object: null when the option is absent, and a
         * thrown failure for every malformed shape, so the caller rejects the whole
         * request before approval and never dispatches an action for it.
         */
        fun parse(element: JsonElement?): ScreenObserveAfter? {
            if (element == null) return null
            val plan = element as? JsonObject ?: error("Invalid observe_after")
            require(plan.keys.all { it in KEYS })
            fun optional(key: String, max: Int): String? = plan[key]?.let {
                val p = it as? JsonPrimitive ?: error("Invalid")
                require(p.isString && p.content.length in 1..max && '\u0000' !in p.content)
                p.content
            }
            val packageName = optional("package_name", 200)
            require(packageName == null || packageName.matches(ScreenObservationWait.PACKAGE_NAME))
            val waitText = optional("text", 200)
            val timeout = plan["timeout_ms"]?.let {
                val p = it as? JsonPrimitive ?: error("Invalid")
                require(!p.isString)
                p.longOrNull?.also { ms -> require(ms in 1..10_000) } ?: error("Invalid timeout")
            }
            return ScreenObserveAfter(packageName, waitText, timeout)
        }
    }
}

/**
 * What a followed-up read contributes to a `device_act` result: [json] is the
 * model-facing observation payload, and [sequence] is present only when a snapshot
 * was actually disclosed. [actionable] separately states whether it supplies a usable next target.
 */
internal data class ScreenFollowUpOutcome(val json: String, val sequence: Long?, val actionable: Boolean)

/** The one follow-up read: a single fresh capture, or the shared conditional wait. */
internal class ScreenFollowUpRead(
    private val channel: ScreenObservationPort,
    private val admitted: ScreenAccess?,
) {
    /**
     * Events only wake the wait, and every refusal is reported as an observation
     * status — never as a failure of the action that already happened.
     */
    suspend fun read(plan: ScreenObserveAfter, baseline: Long, authorized: suspend () -> Boolean): ScreenFollowUpOutcome {
        if (!authorized()) return refused("withheld", ScreenObservationWait.ACCESS_CHANGED)
        val snapshot = try {
            if (plan.conditional) {
                ScreenObservationWait.await(channel, admitted, authorized, plan.packageName, plan.text, baseline,
                    plan.timeoutMs ?: ScreenObserveAfter.DEFAULT_TIMEOUT_MS)
            } else {
                // An empty object asks for one fresh observation, not for a wait.
                channel.capture(plan.packageName)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: ScreenObservationException) {
            return if (failure.code == ScreenObservationWait.ACCESS_CHANGED)
                refused("withheld", failure.code) else refused("unavailable", failure.code)
        } catch (_: Exception) {
            return refused("unavailable", "SCREEN_OBSERVATION_FAILED")
        }
        if (snapshot == null) return refused("timeout")
        // Re-checked after the read: an authorization that lapsed during the wait withholds it.
        if (!authorized()) return refused("withheld", ScreenObservationWait.ACCESS_CHANGED)
        val json = ScreenObservationToolExecutor.render(snapshot, matched = if (plan.conditional) true else null)
        if (json.toByteArray(Charsets.UTF_8).size > ScreenObservationWait.RESULT_LIMIT_BYTES) {
            return refused("unavailable", "SCREEN_RESULT_LIMIT")
        }
        return ScreenFollowUpOutcome(json, snapshot.sequence, snapshot.stable && snapshot.observationId != null)
    }

    /** An observation payload that discloses nothing; [value] is one of timeout/withheld/unavailable. */
    private fun refused(value: String, reason: String? = null): ScreenFollowUpOutcome = ScreenFollowUpOutcome(
        buildJsonObject {
            put("status", value)
            if (value == "timeout") put("matched", false)
            reason?.let { put("reason", it) }
        }.toString(), null, false)
}
