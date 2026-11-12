// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.shizuku.InvalidShizukuPath
import runtime.mobileagent.shizuku.ShizukuPathLimit
import runtime.mobileagent.shizuku.ShizukuWorkspaceFileStore
import runtime.mobileagent.shizuku.ShizukuWorkspacePathPolicy

class EmpiricalWorkspacePathPolicyChallengerTest {
    private val limits = InternalWorkspaceLimits()

    @Test
    fun deepPathsDepth16Vs17Vs32() {
        val depth16 = (1..16).joinToString("/") { "dir$it" }
        assertEquals(16, WorkspacePathPolicy.parse(depth16, allowRoot = false, limits).size)
        assertEquals(16, ShizukuWorkspacePathPolicy.parse(depth16, allowRoot = false).size)

        val depth17 = (1..17).joinToString("/") { "dir$it" }
        val ex17 = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse(depth17, allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED, ex17.error.code)
        assertThrows(ShizukuPathLimit::class.java) {
            ShizukuWorkspacePathPolicy.parse(depth17, allowRoot = false)
        }

        val depth32 = (1..32).joinToString("/") { "dir$it" }
        val ex32 = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse(depth32, allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED, ex32.error.code)
        assertThrows(ShizukuPathLimit::class.java) {
            ShizukuWorkspacePathPolicy.parse(depth32, allowRoot = false)
        }
    }

    @Test
    fun rootAndEmptyPathHandling() {
        // Root path "/"
        listOf("/", "///", "/root").forEach { rootPath ->
            val ex1 = assertThrows(InternalWorkspaceFailure::class.java) {
                WorkspacePathPolicy.parse(rootPath, allowRoot = true, limits)
            }
            assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex1.error.code)

            val ex2 = assertThrows(InternalWorkspaceFailure::class.java) {
                WorkspacePathPolicy.parse(rootPath, allowRoot = false, limits)
            }
            assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex2.error.code)

            assertThrows(InvalidShizukuPath::class.java) {
                ShizukuWorkspacePathPolicy.parse(rootPath, allowRoot = true)
            }
            assertThrows(InvalidShizukuPath::class.java) {
                ShizukuWorkspacePathPolicy.parse(rootPath, allowRoot = false)
            }
        }

        // Empty path ""
        assertEquals(emptyList<String>(), WorkspacePathPolicy.parse("", allowRoot = true, limits))
        val emptyEx = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.parse("", allowRoot = false, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, emptyEx.error.code)

        assertEquals(emptyList<String>(), ShizukuWorkspacePathPolicy.parse("", allowRoot = true))
        assertThrows(InvalidShizukuPath::class.java) {
            ShizukuWorkspacePathPolicy.parse("", allowRoot = false)
        }
    }

    @Test
    fun backslashAndMixedSlashesRejected() {
        listOf(
            "a\\b",
            "\\a",
            "a\\",
            "\\",
            "a/b\\c",
            "a\\b/c",
            "sub\\dir/file.txt"
        ).forEach { path ->
            val ex = assertThrows(InternalWorkspaceFailure::class.java) {
                WorkspacePathPolicy.parse(path, allowRoot = false, limits)
            }
            assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex.error.code)

            assertThrows(InvalidShizukuPath::class.java) {
                ShizukuWorkspacePathPolicy.parse(path, allowRoot = false)
            }
        }
    }

    @Test
    fun nulAndControlCharactersRejected() {
        listOf(
            "file\u0000.txt",
            "\u0000",
            "dir/\u0000/file.txt",
            "dir/tab\tfile.txt",
            "dir/newline\nfile.txt",
            "dir/cr\rfile.txt",
            "dir/bell\u0007.txt",
            "dir/esc\u001B.txt"
        ).forEach { path ->
            val ex = assertThrows(InternalWorkspaceFailure::class.java) {
                WorkspacePathPolicy.parse(path, allowRoot = false, limits)
            }
            assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex.error.code)

            assertThrows(InvalidShizukuPath::class.java) {
                ShizukuWorkspacePathPolicy.parse(path, allowRoot = false)
            }
        }
    }

    @Test
    fun pathTraversalAttemptsRejected() {
        listOf(
            "..",
            "../..",
            "a/../b",
            ".",
            "a/./b",
            "a//b",
            "a/b/",
            "a/b/../..",
            "foo/bar/../../baz"
        ).forEach { path ->
            val ex = assertThrows(InternalWorkspaceFailure::class.java) {
                WorkspacePathPolicy.parse(path, allowRoot = false, limits)
            }
            assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex.error.code)

            assertThrows(InvalidShizukuPath::class.java) {
                ShizukuWorkspacePathPolicy.parse(path, allowRoot = false)
            }
        }
    }

    @Test
    fun windowsDriveLettersAndColonsRejected() {
        listOf(
            "C:/Windows",
            "c:/test",
            "D:",
            "d:",
            "C:\\foo",
            "drive:name",
            "folder/drive:name",
            "http://example.com"
        ).forEach { path ->
            val ex = assertThrows(InternalWorkspaceFailure::class.java) {
                WorkspacePathPolicy.parse(path, allowRoot = false, limits)
            }
            assertEquals(InternalWorkspaceErrorCode.INVALID_PATH, ex.error.code)

            assertThrows(InvalidShizukuPath::class.java) {
                ShizukuWorkspacePathPolicy.parse(path, allowRoot = false)
            }
        }
    }

    @Test
    fun unicodeNfcVsNfdEquivalenceAndProviderAmbiguity() {
        val nfd = "caf\u0065\u0301.txt" // decomposed 'e' + combining acute
        val nfc = "caf\u00e9.txt"         // precomposed 'é'

        // Both policies normalize NFD to NFC
        val parsedNfd = WorkspacePathPolicy.parse(nfd, allowRoot = false, limits)
        val parsedNfc = WorkspacePathPolicy.parse(nfc, allowRoot = false, limits)
        assertEquals(listOf(nfc), parsedNfd)
        assertEquals(listOf(nfc), parsedNfc)

        val shizukuNfd = ShizukuWorkspacePathPolicy.parse(nfd, allowRoot = false)
        val shizukuNfc = ShizukuWorkspacePathPolicy.parse(nfc, allowRoot = false)
        assertEquals(listOf(nfc), shizukuNfd)
        assertEquals(listOf(nfc), shizukuNfc)

        // Provider name validation must strictly reject NFD to prevent alias attacks
        val aliasEx = assertThrows(InternalWorkspaceFailure::class.java) {
            WorkspacePathPolicy.validateProviderName(nfd, limits)
        }
        assertEquals(InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS, aliasEx.error.code)

        val shizukuAliasEx = assertThrows(ShizukuWorkspaceFileStore.WorkspaceFailure::class.java) {
            ShizukuWorkspacePathPolicy.validateProviderName(nfd)
        }
        assertEquals(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS, shizukuAliasEx.code)

        // Provider name validation accepts NFC
        assertEquals(nfc, WorkspacePathPolicy.validateProviderName(nfc, limits))
        assertEquals(nfc, ShizukuWorkspacePathPolicy.validateProviderName(nfc))
    }
}
