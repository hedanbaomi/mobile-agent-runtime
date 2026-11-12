// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkspacePathTest {

    @Test
    fun validRelativePathsNormalizeCorrectly() {
        assertEquals("file.txt", WorkspacePath.normalize("file.txt"))
        assertEquals("docs/sub/readme.md", WorkspacePath.normalize("docs/sub/readme.md"))
        assertEquals("a/b/c/d/e", WorkspacePath.normalize("a/b/c/d/e"))
    }

    @Test
    fun backslashIsStrictlyRejected() {
        val ex1 = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("docs\\file.txt")
        }
        assertEquals("path contains backslash", ex1.message)

        val ex2 = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("file\\name")
        }
        assertEquals("path contains backslash", ex2.message)
    }

    @Test
    fun emptyAndNulPathsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("docs/\u0000file.txt")
        }
    }

    @Test
    fun absolutePathsAndDriveLettersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("/etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("C:/Windows/System32")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("c:/test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("D:")
        }
    }

    @Test
    fun unsafeSegmentsAndTraversalsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("docs/../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("./file.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("docs/./file.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("docs//file.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("colon:segment")
        }
    }

    @Test
    fun maxDepthEnforcement() {
        val depth32 = (1..32).joinToString("/") { "dir$it" }
        assertEquals(depth32, WorkspacePath.normalize(depth32))

        val depth33 = (1..33).joinToString("/") { "dir$it" }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize(depth33)
        }
        assertEquals("path is too deep", ex.message)

        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("a/b/c", maxDepth = 2)
        }
    }

    @Test
    fun unicodePathsNormalizeToNfcAndRejectControlCharacters() {
        val decomposed = "docs/caf\u0065\u0301.txt"
        val precomposed = "docs/caf\u00e9.txt"
        assertEquals(precomposed, WorkspacePath.normalize(decomposed))
        assertEquals(precomposed, WorkspacePath.normalize(precomposed))
        assertEquals("\uac00.txt", WorkspacePath.normalize("\u1100\u1161.txt"))

        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("docs/control\u0007char.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("\u212a:/system")
        }
    }
}
