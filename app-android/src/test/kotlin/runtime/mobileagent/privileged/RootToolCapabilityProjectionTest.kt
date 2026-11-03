// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * F2: the *model-visible* Root surface must not claim an unconditional directory-entry CAS.
 *
 * The production Root surface is the typed tool catalog ([RootToolArguments.specs]); it has no
 * `workspace_list` entry, so `expected_version_operations` (produced only by the ordinary
 * workspace JSON in `UnifiedWorkspaceToolExecutor`) is never emitted for Root. The remaining
 * model-visible contract is the catalog text, which must carry the UID0-exclusive /
 * path-dependent limit instead of a blanket CAS claim.
 */
class RootToolCapabilityProjectionTest {
    @Test
    fun rootCatalogDoesNotExposeAWorkspaceListEntry() {
        val names = RootToolArguments.specs().map { it.name }
        assertFalse(
            "workspace_list" in names,
            "Root typed tools do not project workspace_list; adding one would invent a second capability surface",
        )
        assertTrue("file_write_text" in names)
        assertTrue("file_apply_patch" in names)
    }

    @Test
    fun conditionalCapabilityIsDescribedAsUid0ExclusiveAndPathDependent() {
        val write = RootToolArguments.specs().single { it.name == "file_write_text" }
        val patch = RootToolArguments.specs().single { it.name == "file_apply_patch" }
        assertTrue(
            write.description.contains("UID0-exclusive"),
            "file_write_text must say expected_version is path-dependent: ${write.description}",
        )
        assertTrue(
            write.description.contains("unconditional writes only"),
            "file_write_text must keep the ordinary private write available: ${write.description}",
        )
        assertTrue(
            patch.description.contains("UID0-exclusive") &&
                patch.description.contains("WORKSPACE_VERSION_UNSUPPORTED"),
            "file_apply_patch must state where it is refused: ${patch.description}",
        )
        assertTrue(
            write.inputSchema.contains("WORKSPACE_VERSION_UNSUPPORTED"),
            "the expected_version parameter must document the refusal",
        )
        assertTrue(
            patch.inputSchema.contains("UID0-exclusive"),
            "the patch expected_version parameter must document the refusal",
        )
    }
}
