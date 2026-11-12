// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShizukuWorkspacePathPolicyTest {

    @Test
    fun validPathsParseSuccessfully() {
        assertEquals(listOf("file.txt"), ShizukuWorkspacePathPolicy.parse("file.txt", allowRoot = false))
        assertEquals(listOf("docs", "sub", "readme.md"), ShizukuWorkspacePathPolicy.parse("docs/sub/readme.md", allowRoot = false))
        assertTrue(ShizukuWorkspacePathPolicy.isValid("docs/sub/readme.md", allowRoot = false))
    }

    @Test
    fun rootPathHandling() {
        assertEquals(emptyList<String>(), ShizukuWorkspacePathPolicy.parse("", allowRoot = true))
        assertEquals(emptyList<String>(), ShizukuWorkspacePathPolicy.parse(null, allowRoot = true))
        assertTrue(ShizukuWorkspacePathPolicy.isValid("", allowRoot = true))
        assertTrue(ShizukuWorkspacePathPolicy.isValid(null, allowRoot = true))

        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse(null, allowRoot = false)
        }
        assertFalse(ShizukuWorkspacePathPolicy.isValid("", allowRoot = false))
    }

    @Test
    fun backslashAndNulAreRejected() {
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("docs\\file.txt", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("docs/\u0000file.txt", allowRoot = false)
        }
        assertFalse(ShizukuWorkspacePathPolicy.isValid("docs\\file.txt", allowRoot = false))
    }

    @Test
    fun slashViolationsAreRejected() {
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("/leading/slash", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("trailing/slash/", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("double//slash", allowRoot = false)
        }
    }

    @Test
    fun traversalAndInvalidSegmentsAreRejected() {
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("../parent", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("sub/../parent", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("./current", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("sub/./current", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("drive:colon", allowRoot = false)
        }
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("control\u0007char", allowRoot = false)
        }
    }

    @Test
    fun depthAndSizeLimitsAreEnforced() {
        val depth16 = (1..16).joinToString("/") { "d$it" }
        assertEquals(16, ShizukuWorkspacePathPolicy.parse(depth16, allowRoot = false).size)

        val depth17 = (1..17).joinToString("/") { "d$it" }
        assertThrows(ShizukuPathLimit::class.java) {
            ShizukuWorkspacePathPolicy.parse(depth17, allowRoot = false)
        }

        val longSegment = "a".repeat(121)
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse(longSegment, allowRoot = false)
        }

        val longPath = (1..15).joinToString("/") { "a".repeat(35) } // > 512 bytes
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse(longPath, allowRoot = false)
        }
    }

    @Test
    fun unicodeNfcNormalization() {
        // Decomposed "e" + combining acute accent -> precomposed "é"
        val decomposed = "caf\u0065\u0301.txt"
        val expected = "caf\u00e9.txt"
        val parsed = ShizukuWorkspacePathPolicy.parse(decomposed, allowRoot = false)
        assertEquals(listOf(expected), parsed)

        // Kelvin sign U+212A normalizes to 'K' and must not smuggle a drive prefix
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("\u212a:/escape", allowRoot = false)
        }
    }

    @Test
    fun providerNameValidationRejectsNonNfcAliases() {
        val nfc = "caf\u00e9.txt"
        val nfd = "caf\u0065\u0301.txt"
        assertEquals(nfc, ShizukuWorkspacePathPolicy.validateProviderName(nfc))

        val aliasEx = assertThrows(ShizukuWorkspaceFileStore.WorkspaceFailure::class.java) {
            ShizukuWorkspacePathPolicy.validateProviderName(nfd)
        }
        assertEquals(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS, aliasEx.code)

        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.validateProviderName("nested/name.txt")
        }
    }
}
