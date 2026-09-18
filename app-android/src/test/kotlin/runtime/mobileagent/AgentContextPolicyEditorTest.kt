// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import runtime.mobileagent.domain.AgentContextPolicy

class AgentContextPolicyEditorTest {

    @Test
    fun visibleFieldsRoundTripThroughTheSharedPolicyContract() {
        val draft = AgentContextPolicyDraftUi(
            autoCompact = false,
            maxInputTokens = "4000",
            maxHistoryMessages = "12",
            maxHistoryTurns = "6",
            maxModelRoundsPerSegment = "4",
            maxModelRequestsPerRun = "16",
            keepRecentTurns = "3",
            softLimitPercent = "80",
            targetPercent = "50",
            maxCompactionsPerRun = "2",
        )

        val stored = draft.toCanonicalJson("{}")
        val policy = AgentContextPolicy.fromJson(stored)

        assertFalse(policy.autoCompact)
        assertEquals(4000, policy.maxInputTokens)
        assertEquals(12, policy.maxHistoryMessages)
        assertEquals(6, policy.maxHistoryTurns)
        assertEquals(4, policy.maxModelRoundsPerSegment)
        assertEquals(16, policy.maxModelRequestsPerRun)
        assertEquals(3, policy.keepRecentTurns)
        assertEquals(80, policy.softLimitPercent)
        assertEquals(50, policy.targetPercent)
        assertEquals(2, policy.maxCompactionsPerRun)
    }

    @Test
    fun defaultsMatchTheDocumentedVisibleSettings() {
        val policy = AgentContextPolicy.fromJson(AgentContextPolicyDraftUi().toCanonicalJson("{}"))
        assertTrue(policy.autoCompact)
        assertNull(policy.maxInputTokens)
        assertEquals(20, policy.maxHistoryMessages)
        assertEquals(10, policy.maxHistoryTurns)
        assertEquals(8, policy.maxModelRoundsPerSegment)
        assertEquals(32, policy.maxModelRequestsPerRun)
        assertEquals(2, policy.keepRecentTurns)
        assertEquals(85, policy.softLimitPercent)
        assertEquals(60, policy.targetPercent)
        assertEquals(8, policy.maxCompactionsPerRun)
    }

    @Test
    fun clearingTheInputBudgetKeepsAnEmptyTextDraftAndOmitsTheKey() {
        val draft = AgentContextPolicyDraftUi(maxInputTokens = "4000")
            .copy(maxInputTokens = "")

        // The transient draft must not snap an empty field back to a default.
        assertEquals("", draft.maxInputTokens)

        val stored = draft.toCanonicalJson("""{"maxInputTokens":4000}""")
        val parsed = Json.parseToJsonElement(stored).jsonObject
        assertFalse(parsed.containsKey("maxInputTokens"))
        // Blank input means "use the model's available window".
        assertNull(AgentContextPolicy.fromJson(stored).maxInputTokens)
    }

    @Test
    fun invalidTextFailsClosedWithAFieldSpecificMessage() {
        val notANumber = assertThrows<IllegalArgumentException> {
            AgentContextPolicyDraftUi(maxHistoryMessages = "abc").toCanonicalJson("{}")
        }
        assertTrue(notANumber.message.orEmpty().contains("最大历史消息数"))

        val outOfRange = assertThrows<IllegalArgumentException> {
            AgentContextPolicyDraftUi(softLimitPercent = "99").toCanonicalJson("{}")
        }
        assertTrue(outOfRange.message.orEmpty().contains("软阈值百分比"))

        val inverted = assertThrows<IllegalArgumentException> {
            AgentContextPolicyDraftUi(softLimitPercent = "70", targetPercent = "80").toCanonicalJson("{}")
        }
        assertTrue(inverted.message.orEmpty().contains("目标压缩百分比"))

        // No partial result is produced: the stored JSON is untouched on failure.
        val previous = """{"summaryOutputTokens":2048}"""
        assertThrows<IllegalArgumentException> {
            AgentContextPolicyDraftUi(maxHistoryTurns = "-1").toCanonicalJson(previous)
        }
        assertEquals(2048, Json.parseToJsonElement(previous).jsonObject["summaryOutputTokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun unknownLegacyKeysArePreservedInsteadOfBeingDropped() {
        val legacy = """{"summaryOutputTokens":2048,"summaryMaxUnits":4096,"futureKnob":true,"nested":{"a":1}}"""

        val stored = AgentContextPolicyDraftUi(maxHistoryMessages = "7").toCanonicalJson(legacy)
        val parsed = Json.parseToJsonElement(stored).jsonObject

        assertEquals(2048, parsed["summaryOutputTokens"]!!.jsonPrimitive.int)
        assertEquals(4096, parsed["summaryMaxUnits"]!!.jsonPrimitive.int)
        assertTrue(parsed["futureKnob"]!!.jsonPrimitive.boolean)
        assertEquals(1, parsed["nested"]!!.jsonObject["a"]!!.jsonPrimitive.int)
        assertEquals(7, AgentContextPolicy.fromJson(stored).maxHistoryMessages)

        // A load/edit/save cycle through the draft keeps the same unknown keys.
        val reloaded = AgentContextPolicyDraftUi.fromJson(stored)
        val resaved = Json.parseToJsonElement(reloaded.toCanonicalJson(stored)).jsonObject
        assertEquals(2048, resaved["summaryOutputTokens"]!!.jsonPrimitive.int)
        assertTrue(resaved["futureKnob"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun contextPolicyJsonNeverCarriesModelParameterOverrides() {
        val parameterLikeKeys = setOf("temperature", "max_tokens", "top_p")

        // A model-parameter-shaped key already in the stored JSON is only an unknown key:
        // it is preserved verbatim, never re-interpreted as a context policy field.
        val stored = AgentContextPolicyDraftUi(autoCompact = false)
            .toCanonicalJson("""{"temperature":0.2,"top_p":0.9}""")
        val parsed = Json.parseToJsonElement(stored).jsonObject

        assertEquals("0.2", parsed["temperature"]!!.jsonPrimitive.content)
        assertEquals("0.9", parsed["top_p"]!!.jsonPrimitive.content)
        assertTrue(parameterLikeKeys.none { it in parsed.keys && it in visiblePolicyKeys })
        // The merge only writes the context-policy keys it owns.
        assertTrue(parsed.keys.all { it in visiblePolicyKeys || it in parameterLikeKeys })
        assertTrue((visiblePolicyKeys - setOf("maxInputTokens", "pythonModelRunTokens")).all { it in parsed.keys })
        assertFalse("maxInputTokens" in parsed.keys, "blank optional budget is omitted")
    }

    @Test
    fun summaryIsReadOnlyTextThatMarksTheInputBudgetAsAnEstimate() {
        val draft = AgentContextPolicyDraftUi(maxInputTokens = "")
        val zh = draft.summary(zh = true)
        assertTrue(zh.contains("自动压缩"))
        assertTrue(zh.contains("输入预算（保守估算单位）"))
        assertTrue(zh.contains("模型可用窗口"))
        assertTrue(draft.copy(maxInputTokens = "4000").summary(zh = true).contains("4000"))
    }

    /**
     * The Run fee authorization is an editable policy field: a blank value keeps
     * the key out entirely (no authorization), a number is persisted and read
     * back, and a malformed value is rejected instead of being guessed.
     */
    @Test
    fun theRunFeeAuthorizationIsEditableAndBlankMeansNotAuthorized() {
        val omitted = AgentContextPolicyDraftUi().toCanonicalJson("{}")
        assertFalse("pythonModelRunTokens" in Json.parseToJsonElement(omitted).jsonObject.keys)
        assertNull(AgentContextPolicy.fromJson(omitted).pythonModelRunTokens)

        val authorized = AgentContextPolicyDraftUi(pythonModelRunTokens = "65536")
        val stored = authorized.toCanonicalJson("{}")
        assertEquals(65536, Json.parseToJsonElement(stored).jsonObject["pythonModelRunTokens"]!!.jsonPrimitive.int)
        assertEquals(65536, AgentContextPolicy.fromJson(stored).pythonModelRunTokens)
        assertEquals("65536", AgentContextPolicyDraftUi.fromJson(stored).pythonModelRunTokens)

        assertThrows<IllegalArgumentException> { AgentContextPolicyDraftUi(pythonModelRunTokens = "abc").toCanonicalJson("{}") }
        assertThrows<IllegalArgumentException> { AgentContextPolicyDraftUi(pythonModelRunTokens = "0").toCanonicalJson("{}") }
        // A blank field must remove a previously stored authorization.
        val cleared = AgentContextPolicyDraftUi().toCanonicalJson(stored)
        assertFalse("pythonModelRunTokens" in Json.parseToJsonElement(cleared).jsonObject.keys)
        assertTrue(authorized.summary(zh = true).contains("65536"))
    }

    private companion object {
        val visiblePolicyKeys = setOf(
            "autoCompact",
            "maxInputTokens",
            "maxHistoryMessages",
            "maxHistoryTurns",
            "maxModelRoundsPerSegment",
            "maxModelRequestsPerRun",
            "pythonModelRunTokens",
            "keepRecentTurns",
            "softLimitPercent",
            "targetPercent",
            "maxCompactionsPerRun",
        )
    }
}
