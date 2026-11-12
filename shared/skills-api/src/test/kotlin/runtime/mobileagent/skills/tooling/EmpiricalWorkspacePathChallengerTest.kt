// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EmpiricalWorkspacePathChallengerTest {

    @Test
    fun deepPathsAdversarialTest() {
        val depth16 = (1..16).joinToString("/") { "dir$it" }
        assertEquals(depth16, WorkspacePath.normalize(depth16))

        val depth17 = (1..17).joinToString("/") { "dir$it" }
        assertEquals(depth17, WorkspacePath.normalize(depth17))
        val exDepth17 = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize(depth17, maxDepth = 16)
        }
        assertEquals("path is too deep", exDepth17.message)

        val depth32 = (1..32).joinToString("/") { "dir$it" }
        assertEquals(depth32, WorkspacePath.normalize(depth32))

        val depth33 = (1..33).joinToString("/") { "dir$it" }
        val exDepth33 = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize(depth33)
        }
        assertEquals("path is too deep", exDepth33.message)
    }

    @Test
    fun rootAndEmptyPathsAdversarialTest() {
        val emptyEx = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("")
        }
        assertEquals("path is empty", emptyEx.message)

        val rootEx = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("/")
        }
        assertEquals("path must be relative", rootEx.message)

        val multiRootEx = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("///")
        }
        assertEquals("path must be relative", multiRootEx.message)

        val absEx = assertThrows(IllegalArgumentException::class.java) {
            WorkspacePath.normalize("/etc/shadow")
        }
        assertEquals("path must be relative", absEx.message)
    }

    @Test
    fun backslashAndMixedSlashAdversarialTest() {
        listOf(
            "a\\b",
            "\\",
            "\\\\",
            "\\a\\b",
            "a/b\\c",
            "a\\b/c",
            "foo/bar\\baz/file.txt",
            "docs\\",
            "\\docs"
        ).forEach { adversarialPath ->
            val ex = assertThrows(IllegalArgumentException::class.java, {
                WorkspacePath.normalize(adversarialPath)
            }, "Expected rejection for path with backslash: $adversarialPath")
            assertEquals("path contains backslash", ex.message)
        }
    }

    @Test
    fun nulByteAdversarialTest() {
        listOf(
            "\u0000",
            "a/\u0000/b",
            "a/b\u0000",
            "\u0000a/b",
            "foo\u0000bar.txt"
        ).forEach { adversarialPath ->
            val ex = assertThrows(IllegalArgumentException::class.java, {
                WorkspacePath.normalize(adversarialPath)
            }, "Expected rejection for path with NUL: $adversarialPath")
            assertEquals("path contains NUL", ex.message)
        }
    }

    @Test
    fun pathTraversalAdversarialTest() {
        listOf(
            "..",
            "../..",
            "a/../b",
            "a/../../b",
            ".",
            "./.",
            "a/./b",
            "a//b",
            "a///b"
        ).forEach { adversarialPath ->
            val ex = assertThrows(IllegalArgumentException::class.java, {
                WorkspacePath.normalize(adversarialPath)
            }, "Expected rejection for traversal path: $adversarialPath")
            assertEquals("path contains an unsafe segment", ex.message)
        }
    }

    @Test
    fun driveLetterAndColonAdversarialTest() {
        listOf(
            "C:/foo",
            "c:/foo",
            "D:",
            "d:",
            "Z:/system",
            "a:b",
            "foo:bar",
            "foo/bar:baz",
            "http://foo.bar",
            "file:///path"
        ).forEach { adversarialPath ->
            val ex = assertThrows(IllegalArgumentException::class.java, {
                WorkspacePath.normalize(adversarialPath)
            }, "Expected rejection for drive/colon path: $adversarialPath")
            val msg = ex.message
            org.junit.jupiter.api.Assertions.assertTrue(
                msg == "path must be relative" || msg == "path contains a drive separator" || msg == "path contains an unsafe segment",
                "Unexpected message: $msg for $adversarialPath"
            )
        }
    }

    @Test
    fun unicodeAndMultibyteCharactersNormalizeProperly() {
        assertEquals("café.txt", WorkspacePath.normalize("café.txt"))
        assertEquals("caf\u00e9.txt", WorkspacePath.normalize("caf\u0065\u0301.txt"))
        assertEquals("d\u00edr/caf\u00e9.txt", WorkspacePath.normalize("di\u0301r/cafe\u0301.txt"))
        assertEquals("文档/测试/文件.md", WorkspacePath.normalize("文档/测试/文件.md"))
        assertEquals("📁/子目录/📄.json", WorkspacePath.normalize("📁/子目录/📄.json"))

        listOf(
            "dir/bell\u0007.txt",
            "dir/newline\n.txt",
            "\u212a:/escape"
        ).forEach { adversarial ->
            assertThrows(IllegalArgumentException::class.java) {
                WorkspacePath.normalize(adversarial)
            }
        }
    }
}
