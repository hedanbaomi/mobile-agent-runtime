// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.*
import runtime.mobileagent.tooling.WorkspaceDiscovery

/**
 * F2: the *model-visible* Root surface must not claim an unconditional directory-entry CAS.
 *
 * Root uses the common workspace discovery protocol alongside its closed typed file catalog.
 * Backend-specific conditional support is reported by live discovery, not duplicated as prose
 * embedded in the shared scalar schema. Real Root discovery is covered by backend integration.
 */
class RootToolCapabilityProjectionTest {
    @Test
    fun typedFileCatalogUsesTheSeparateCommonDiscoveryProtocol() {
        val names = RootToolArguments.specs().map { it.name }
        assertFalse(
            "workspace_list" in names,
            "workspace_list is provided by the shared lifecycle, not a Root file wire operation",
        )
        assertTrue("file_write_text" in names)
        assertTrue("file_apply_patch" in names)
    }

    @Test
    fun rootCatalogKeepsReadOnlyFlagsAndWorkspaceRoutingSeparate() {
        assertEquals(
            setOf("file_list", "file_glob", "file_stat", "file_read_text", "file_grep", "file_get_metadata"),
            RootToolArguments.specs().filterNot { it.sideEffect }.map { it.name }.toSet(),
        )
        val reserved = RootToolArguments.workspaceToolNames
        assertTrue("workspace_list" in reserved && "file_move" in reserved)
        assertFalse("shell_exec" in reserved || "apply_patch" in reserved)
    }

    @Test
    fun conditionalToolsExposeOpaqueVersionsAndTheirActualPatchProtocol() {
        val write = RootToolArguments.specs().single { it.name == "file_write_text" }
        val patch = RootToolArguments.specs().single { it.name == "file_apply_patch" }
        val writeSchema = Json.parseToJsonElement(write.inputSchema).jsonObject
        val patchSchema = Json.parseToJsonElement(patch.inputSchema).jsonObject
        assertEquals("string", writeSchema.getValue("properties").jsonObject.getValue("expected_version").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("string", patchSchema.getValue("properties").jsonObject.getValue("expected_version").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse("expected_version" in writeSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content },
            "An ordinary unconditional authorized write remains expressible")
        assertTrue("expected_version" in patchSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("unified_diff", "search_replace"), patchSchema.getValue("properties").jsonObject
            .getValue("format").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(WorkspaceDiscovery.spec.schemaVersion, write.schemaVersion)
        assertEquals(write.schemaVersion, patch.schemaVersion)
    }
}
