// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkspacePathPolicyTest {
    private val limits = InternalWorkspaceLimits()

    @Test
    fun validPathsParseSuccessfully() {
        assertEquals(listOf("file.txt"), WorkspacePathPolicy.parse("file.txt", allowRoot = false, limits))
        assertEquals(listOf("sub", "dir", "file.md"), WorkspacePathPolicy.parse("sub/dir/file.md", allowRoot = false, limits))
    }

    @Test
    fun rootPathHandling() {
        assertEquals(emptyList<String>(), WorkspacePathPolicy.parse("", allowRoot = true, limits))

        val ex1 = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("", allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex1.error.code)

        val ex2 = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse(null, allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex2.error.code)

        val ex3 = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse(null, allowRoot = true, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex3.error.code)
    }

    @Test
    fun backslashAndNulAreRejected() {
        val ex1 = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("docs\\file.txt", allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex1.error.code)

        val ex2 = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("docs/\u0000file.txt", allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex2.error.code)
    }

    @Test
    fun slashViolationsAreRejected() {
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("/leading/slash", allowRoot = false, limits)
        }
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("trailing/slash/", allowRoot = false, limits)
        }
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("double//slash", allowRoot = false, limits)
        }
    }

    @Test
    fun traversalAndInvalidSegmentsAreRejected() {
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("../parent", allowRoot = false, limits)
        }
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("sub/../parent", allowRoot = false, limits)
        }
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("./current", allowRoot = false, limits)
        }
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("sub/./current", allowRoot = false, limits)
        }
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("drive:colon", allowRoot = false, limits)
        }
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("control\u0007char", allowRoot = false, limits)
        }
    }

    @Test
    fun depthAndSizeLimitsAreEnforced() {
        val depth16 = (1..16).joinToString("/") { "d$it" }
        assertEquals(16, WorkspacePathPolicy.parse(depth16, allowRoot = false, limits).size)

        val depth17 = (1..17).joinToString("/") { "d$it" }
        val depthEx = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse(depth17, allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED, depthEx.error.code)

        val longSegment = "a".repeat(121)
        val segEx = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse(longSegment, allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, segEx.error.code)

        val longPath = (1..15).joinToString("/") { "a".repeat(35) } // > 512 bytes
        val pathEx = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse(longPath, allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, pathEx.error.code)
    }

    @Test
    fun unicodeNfcNormalization() {
        // Decomposed "e" + combining acute accent -> precomposed "é"
        val decomposed = "caf\u0065\u0301.txt"
        val expected = "caf\u00e9.txt"
        val parsed = WorkspacePathPolicy.parse(decomposed, allowRoot = false, limits)
        assertEquals(listOf(expected), parsed)
    }

    @Test
    fun providerNameValidation() {
        assertEquals("valid_name.txt", WorkspacePathPolicy.validateProviderName("valid_name.txt", limits))

        // Non-NFC name must be rejected as ambiguous alias
        val decomposed = "caf\u0065\u0301.txt"
        val aliasEx = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.validateProviderName(decomposed, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS, aliasEx.error.code)

        // Slashes in provider name are rejected
        assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.validateProviderName("nested/name.txt", limits)
        }
    }
}
