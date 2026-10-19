// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookRole
import runtime.mobileagent.domain.worldbook.WorldBookSettings

/**
 * 坏输入 / 歧义输入 / 越界输入的负向测试：一律失败（零写入），或降级并给出 notice。
 */
class WorldBookImportRejectionTest {

    private fun rejection(text: String, limits: WorldBookImportLimits = WorldBookImportLimits()): WorldBookImportException =
        assertThrows(WorldBookImportException::class.java) { WorldBookCodec.decode(text, "Test", limits) }

    @Test
    fun `malformed json is rejected with syntax code`() {
        assertEquals(WorldBookNoticeCodes.JSON_SYNTAX, rejection("""{"entries": }""").code)
        assertEquals(WorldBookNoticeCodes.JSON_SYNTAX, rejection("""{"entries": {"0": }}""").code)
        assertEquals(WorldBookNoticeCodes.JSON_SYNTAX, rejection("""{"entries": }""").code)
    }

    @Test
    fun `trailing content and unterminated string are rejected`() {
        assertEquals(WorldBookNoticeCodes.JSON_SYNTAX, rejection("""{"entries": {}} trailing""").code)
        assertEquals(WorldBookNoticeCodes.JSON_SYNTAX, rejection("""{"entries": {"0": {"content": "x}""").code)
    }

    @Test
    fun `duplicate keys are rejected on the top level and nested`() {
        assertEquals(WorldBookNoticeCodes.JSON_DUPLICATE_KEY, rejection("""{"entries": {}, "entries": {}}""").code)
        assertEquals(
            WorldBookNoticeCodes.JSON_DUPLICATE_KEY,
            rejection("""{"entries": {"0": {"key": [], "key": []}}}""").code,
        )
    }

    @Test
    fun `non object root is rejected`() {
        assertEquals(WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT, rejection("""[1, 2, 3]""").code)
        assertEquals(WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT, rejection(""""just a string"""").code)
    }

    @Test
    fun `input size limit rejects oversized payload`() {
        val text = """{"entries": {}, "pad": "${"x".repeat(200)}"}"""
        val error = rejection(text, WorldBookImportLimits(maxChars = 64))
        assertEquals(WorldBookNoticeCodes.JSON_SIZE_LIMIT, error.code)
    }

    @Test
    fun `nesting depth limit rejects deeply nested payload`() {
        val text = "[".repeat(40) + "1" + "]".repeat(40)
        assertEquals(WorldBookNoticeCodes.JSON_DEPTH_LIMIT, rejection(text).code)
    }

    @Test
    fun `string length limit rejects oversized single field`() {
        val text = """{"entries": {"0": {"key": ["a"], "content": "0123456789012345678901234567890"}}}"""
        val error = rejection(text, WorldBookImportLimits(maxStringChars = 16))
        assertEquals(WorldBookNoticeCodes.JSON_STRING_LIMIT, error.code)
    }

    @Test
    fun `entry count limit rejects oversized book`() {
        val entries = (0 until 3).joinToString(",") { """{"keys": ["k$it"], "content": "c$it", "id": $it}""" }
        val error = rejection("""{"entries": [$entries]}""", WorldBookImportLimits(maxEntries = 2))
        assertEquals(WorldBookNoticeCodes.ENTRY_COUNT_LIMIT, error.code)
    }

    @Test
    fun `key count limit rejects oversized key list`() {
        val error = rejection(
            """{"entries": {"0": {"key": ["a", "b"], "content": "x"}}}""",
            WorldBookImportLimits(maxKeysPerEntry = 1),
        )
        assertEquals(WorldBookNoticeCodes.ENTRY_KEY_COUNT_LIMIT, error.code)
    }

    @Test
    fun `duplicate uid is rejected`() {
        val error = rejection("""{"entries": [{"keys": ["a"], "content": "1", "id": 3}, {"keys": ["b"], "content": "2", "id": 3}]}""")
        assertEquals(WorldBookNoticeCodes.ENTRY_DUPLICATE_UID, error.code)
    }

    @Test
    fun `non object entry is rejected`() {
        assertEquals(
            WorldBookNoticeCodes.ENTRY_NOT_OBJECT,
            rejection("""{"entries": {"0": 5}}""").code,
        )
        assertEquals(
            WorldBookNoticeCodes.ENTRY_NOT_OBJECT,
            rejection("""{"entries": [5]}""").code,
        )
    }

    @Test
    fun `entries container of wrong type is rejected`() {
        assertEquals(
            WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT,
            rejection("""{"entries": 5}""").code,
        )
    }

    @Test
    fun `out of range position and role fall back with notices`() {
        val result = WorldBookCodec.decode(
            """{"entries": {"0": {"key": ["a"], "content": "x", "position": 99, "role": 9}}}""",
        )
        val entry = result.document.entries.single()
        assertEquals(WorldBookPosition.BEFORE_CHAR, entry.position)
        assertEquals(WorldBookRole.SYSTEM, entry.role)
        assertTrue(result.notices.any { it.code == WorldBookNoticeCodes.FIELD_POSITION_OUT_OF_RANGE })
        assertTrue(result.notices.any { it.code == WorldBookNoticeCodes.FIELD_ROLE_OUT_OF_RANGE })
    }

    @Test
    fun `probability and counters are clamped with notices`() {
        val result = WorldBookCodec.decode(
            """{"entries": {"0": {"key": ["a"], "content": "x", "probability": 500,
                 "groupWeight": -3, "depth": -1, "delayUntilRecursion": -4}}}""".trimIndent(),
        )
        val entry = result.document.entries.single()
        assertEquals(100, entry.probability)
        assertEquals(0, entry.groupWeight)
        assertEquals(0, entry.depth)
        assertEquals(0, entry.delayUntilRecursion)
        val codes = result.notices.map { it.code }.toSet()
        assertTrue(codes.contains(WorldBookNoticeCodes.FIELD_PROBABILITY_OUT_OF_RANGE))
        assertTrue(codes.contains(WorldBookNoticeCodes.FIELD_GROUP_WEIGHT_OUT_OF_RANGE))
        assertTrue(codes.contains(WorldBookNoticeCodes.FIELD_DEPTH_OUT_OF_RANGE))
        assertTrue(codes.contains(WorldBookNoticeCodes.FIELD_NEGATIVE_COUNTER))
    }

    @Test
    fun `non array key field is coerced and reported`() {
        val result = WorldBookCodec.decode("""{"entries": {"0": {"key": "single", "content": "x"}}}""")
        assertEquals(listOf("single"), result.document.entries.single().keys)
        assertTrue(result.notices.any { it.code == WorldBookNoticeCodes.ENTRY_FIELD_COERCED })
    }

    @Test
    fun `unknown spec falls back to structural detection with notice`() {
        val result = WorldBookCodec.decode("""{"spec": "unknown_spec", "entries": {"0": {"key": ["a"]}}}""")
        assertEquals("0", result.document.entries.single().uid)
        assertTrue(result.notices.any { it.code == WorldBookNoticeCodes.FORMAT_ASSUMED })
    }

    @Test
    fun `empty or missing entries yields empty book with notice`() {
        val missing = WorldBookCodec.decode("""{"name": "empty"}""")
        assertTrue(missing.document.entries.isEmpty())
        assertTrue(missing.notices.any { it.code == WorldBookNoticeCodes.FORMAT_EMPTY_ENTRIES })
        val empty = WorldBookCodec.decode("""{"entries": []}""")
        assertTrue(empty.document.entries.isEmpty())
    }

    @Test
    fun `notice list is bounded`() {
        val entries = (0 until 20).joinToString(",") {
            """{"keys": "not-an-array", "content": "c$it", "id": $it}"""
        }
        val result = WorldBookCodec.decode(
            """{"entries": [$entries]}""",
            limits = WorldBookImportLimits(maxNotices = 3),
        )
        assertTrue(result.notices.size >= 3)
        assertTrue(result.notices.any { it.code == WorldBookNoticeCodes.NOTICES_TRUNCATED })
    }

    @Test
    fun `bom is stripped with notice`() {
        val result = WorldBookCodec.decode("\uFEFF" + """{"entries": {"0": {"key": ["a"]}}}""")
        assertEquals("0", result.document.entries.single().uid)
        assertTrue(result.notices.any { it.code == WorldBookNoticeCodes.JSON_BOM })
    }

    @Test
    fun `native import clamps out of range settings and reports it`() {
        val native = """
            {"spec": "mobileagent.worldbook", "version": 1, "format": "SILLY_TAVERN",
             "name": "N", "description": "",
             "settings": {"scanDepth": -3, "minActivations": -1, "budgetPercent": 900,
               "maxScanDepth": 99999, "maxRegexPatternLength": 100000,
               "regexStepBudget": 9223372036854775807, "maxMessageScanChars": -5,
               "maxRunExpansionChars": 2147483647},
             "originalJson": {}, "entries": []}
        """.trimIndent()

        val result = WorldBookCodec.decode(native)
        val settings = result.document.settings
        assertEquals(0, settings.scanDepth)
        assertEquals(0, settings.minActivations)
        assertEquals(100, settings.budgetPercent)
        assertEquals(WorldBookSettings.MAX_SCAN_DEPTH, settings.maxScanDepth)
        assertEquals(WorldBookSettings.HARD_MAX_REGEX_PATTERN_LENGTH, settings.maxRegexPatternLength)
        assertEquals(WorldBookSettings.HARD_MAX_REGEX_STEP_BUDGET, settings.regexStepBudget)
        assertEquals(WorldBookSettings.HARD_MAX_MESSAGE_SCAN_CHARS, settings.maxMessageScanChars)
        assertEquals(WorldBookSettings.HARD_MAX_RUN_EXPANSION_CHARS, settings.maxRunExpansionChars)
        assertTrue(result.notices.any { it.code == WorldBookNoticeCodes.SETTINGS_CLAMPED })
    }

    @Test
    fun `native import keeps valid settings untouched`() {
        val native = """
            {"spec": "mobileagent.worldbook", "version": 1, "format": "NATIVE", "name": "N",
             "settings": {"scanDepth": 7, "regexStepBudget": 1234, "maxMessageScanChars": 4096},
             "originalJson": {}, "entries": []}
        """.trimIndent()
        val result = WorldBookCodec.decode(native)
        assertEquals(7, result.document.settings.scanDepth)
        assertEquals(1234L, result.document.settings.regexStepBudget)
        assertEquals(4096, result.document.settings.maxMessageScanChars)
        assertTrue(result.notices.none { it.code == WorldBookNoticeCodes.SETTINGS_CLAMPED })
    }
}
