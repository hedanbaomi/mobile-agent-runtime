// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolOutcomeTest {
    @Test
    fun deniedIsDurableObjectWithTypedCode() {
        val json = ToolOutcome.denied(message = "The original tool authorization is no longer available")
        assertTrue(ToolOutcome.isDurableObject(json), "denied envelope must be a JSON object")
        assertEquals(ToolOutcomeStatus.DENIED, ToolOutcome.statusOf(json))
        assertEquals(ToolErrorCode.PERMISSION_DENIED, ToolOutcome.errorCodeOf(json))
        assertEquals(false, ToolOutcome.retryableOf(json))
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(false, root["ok"]!!.toString().toBoolean())
    }

    @Test
    fun invalidIsDurableObjectWithTypedCode() {
        val json = ToolOutcome.invalid(message = "Tool arguments are incomplete JSON")
        assertTrue(ToolOutcome.isDurableObject(json))
        assertEquals(ToolOutcomeStatus.INVALID, ToolOutcome.statusOf(json))
        assertEquals(ToolErrorCode.INVALID_REQUEST, ToolOutcome.errorCodeOf(json))
    }

    @Test
    fun failedEnvelopeCarriesStatus() {
        val json = ToolOutcome.failed(ToolError(ToolErrorCode.FILE_TOO_LARGE, message = "too large", retryable = false))
        assertTrue(ToolOutcome.isDurableObject(json))
        assertEquals(ToolOutcomeStatus.FAILED, ToolOutcome.statusOf(json))
        assertEquals(ToolErrorCode.FILE_TOO_LARGE, ToolOutcome.errorCodeOf(json))
    }

    @Test
    fun legacyFailureEnvelopeWithoutStatusStillReadsAsFailed() {
        val legacy = """{"ok":false,"error":{"code":"CONFLICT","message":"changed","retryable":true}}"""
        assertEquals(ToolOutcomeStatus.FAILED, ToolOutcome.statusOf(legacy))
        assertEquals(ToolErrorCode.CONFLICT, ToolOutcome.errorCodeOf(legacy))
    }

    @Test
    fun unknownEnvelopeNeverAllowsReplay() {
        val json = ToolOutcome.unknown("Tool dispatch may have started; do not automatically retry")
        assertTrue(ToolOutcome.isDurableObject(json))
        assertEquals(ToolOutcomeStatus.UNKNOWN_OUTCOME, ToolOutcome.statusOf(json))
        assertTrue(json.contains("\"automaticReplayAllowed\":false"))
    }

    @Test
    fun plainStringsAreNotDurableOutcomes() {
        assertFalse(ToolOutcome.isDurableObject("The original tool authorization is no longer available"))
        assertNull(ToolOutcome.statusOf("The original tool authorization is no longer available"))
        assertNull(ToolOutcome.errorCodeOf("not json"))
    }

    @Test
    fun deniedAndInvalidNeverEscalateToInternal() {
        val denied = ToolOutcome.denied(message = "revoked")
        val invalid = ToolOutcome.invalid(message = "bad args")
        assertFalse(denied.contains("INTERNAL"), "denied must not escalate to INTERNAL")
        assertFalse(invalid.contains("INTERNAL"), "invalid must not escalate to INTERNAL")
    }
}
