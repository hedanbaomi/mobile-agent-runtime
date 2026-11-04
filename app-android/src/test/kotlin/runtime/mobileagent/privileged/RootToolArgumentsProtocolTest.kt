// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.RootTypedArguments

/** One strict argument contract for both model spellings and backend capabilities. */
class RootToolArgumentsProtocolTest {
    @Test
    fun workspaceIdAliasNormalizesToTheSameTypedRequest() {
        val snake = requireNotNull(RootToolArguments.normalize(
            "file_read_text", """{"workspace_id":"ws","relative_path":"a"}""",
        ))
        val camel = requireNotNull(RootToolArguments.normalize(
            "file_read_text", """{"workspaceId":"ws","relative_path":"a"}""",
        ))
        val both = requireNotNull(RootToolArguments.normalize(
            "file_read_text", """{"workspaceId":"ws","workspace_id":"ws","relative_path":"a"}""",
        ))
        assertEquals("ws", camel.workspaceId)
        assertEquals(snake.canonicalSha256, camel.canonicalSha256)
        assertEquals(snake.canonicalSha256, both.canonicalSha256)
    }

    @Test
    fun conflictingWorkspaceIdAliasesAreRejected() {
        assertNull(RootToolArguments.normalize(
            "file_read_text", """{"workspaceId":"a","workspace_id":"b","relative_path":"x"}""",
        ))
        assertEquals(
            RootArgumentNormalization.Invalid,
            RootToolArguments.normalizeResult(
                "file_read_text", """{"workspaceId":"a","workspace_id":"b","relative_path":"x"}""",
            ),
        )
    }

    @Test
    fun unsupportedConditionalFieldsAreExplicitNotIgnored() {
        for (tool in listOf("file_delete", "file_create_directory", "file_move", "file_read_text")) {
            val result = RootToolArguments.normalizeResult(
                tool, """{"workspace_id":"ws","relative_path":"a","expected_version":"v1"}""",
            )
            assertEquals(RootArgumentNormalization.UnsupportedVersionCondition, result, tool)
            assertNull(RootToolArguments.normalize(
                tool, """{"workspace_id":"ws","relative_path":"a","expected_version":"v1"}""",
            ), tool)
        }
    }

    @Test
    fun rootPatchFormatKeepsOnlyItsOwnWireFormats() {
        assertEquals(
            RootArgumentNormalization.UnsupportedPatchFormat,
            RootToolArguments.normalizeResult(
                "file_apply_patch",
                """{"workspace_id":"ws","relative_path":"a","patch":"x","expected_version":"v1","format":"replace"}""",
            ),
        )
        val searchReplace = requireNotNull(RootToolArguments.normalize(
            "file_apply_patch",
            """{"workspace_id":"ws","relative_path":"a","patch":"x","expected_version":"v1","format":"search_replace"}""",
        ))
        assertEquals("search_replace", (searchReplace.typed as RootTypedArguments.FileApplyPatch).format)
        val unified = requireNotNull(RootToolArguments.normalize(
            "file_apply_patch",
            """{"workspace_id":"ws","relative_path":"a","patch":"x","expected_version":"v1","format":"unified_diff"}""",
        ))
        assertEquals("unified", (unified.typed as RootTypedArguments.FileApplyPatch).format)
    }

    @Test
    fun supportedVersionConditionsStillNormalize() {
        val write = requireNotNull(RootToolArguments.normalize(
            "file_write_text",
            """{"workspace_id":"ws","relative_path":"a","text":"x","expected_version":"v1"}""",
        ))
        assertEquals("v1", (write.typed as RootTypedArguments.FileWriteText).expectedVersion)
        val patch = requireNotNull(RootToolArguments.normalize(
            "file_apply_patch",
            """{"workspace_id":"ws","relative_path":"a","patch":"x","expected_version":"v1","format":"search_replace"}""",
        ))
        assertEquals("v1", (patch.typed as RootTypedArguments.FileApplyPatch).expectedVersion)
    }

    @Test
    fun catalogSchemaMatchesEachBackendCapability() {
        fun schema(name: String) = Json.parseToJsonElement(requireNotNull(RootToolArguments.spec(name)).inputSchema).jsonObject
        assertFalse("expected_version" in schema("file_delete").getValue("properties").jsonObject)
        assertFalse("expected_version" in schema("file_create_directory").getValue("properties").jsonObject)
        assertTrue("expected_version" in schema("file_write_text").getValue("properties").jsonObject)
        val formats = schema("file_apply_patch").getValue("properties").jsonObject
            .getValue("format").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("unified_diff", "search_replace"), formats)
        assertFalse("replace" in formats)
    }
}
