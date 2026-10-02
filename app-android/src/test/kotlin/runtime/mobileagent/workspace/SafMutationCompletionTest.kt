// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Fault injection at the same post-create boundary used by the SAF write backend. */
class SafMutationCompletionTest {
    @TempDir lateinit var directory: Path

    @Test fun failedOpenLeavesCreatedDocumentAndReportsUnknown() {
        val failures: List<() -> OutputStream?> = listOf(
            { null },
            { throw SecurityException("synthetic provider denial") },
            { throw IOException("synthetic provider open failure") },
        )
        failures.forEachIndexed { index, open ->
            val created = Files.createFile(directory.resolve("open-$index"))
            val failure = assertThrows(InternalWorkspaceFailure::class.java) {
                completeSafCreatedDocument {
                    val output = open() ?: InternalWorkspaceErrorCode.UNSUPPORTED.error()
                    output.use { it.write(1) }
                }
            }
            assertEquals(InternalWorkspaceErrorCode.UNKNOWN_OUTCOME, failure.error.code)
            assertTrue(Files.exists(created), "Failed open did not undo document creation")
        }
    }

    @Test fun partialWriteAndCloseFailuresNeverBecomeRetryableFailure() {
        for (failOnClose in listOf(false, true)) {
            val created = Files.createFile(directory.resolve("write-$failOnClose"))
            val delegate = Files.newOutputStream(created)
            var closed = false
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    delegate.write(value)
                    if (!failOnClose) throw IOException("synthetic partial write")
                }
                override fun close() {
                    delegate.close()
                    closed = true
                    if (failOnClose) throw IOException("synthetic close failure")
                }
            }
            val failure = assertThrows(InternalWorkspaceFailure::class.java) {
                completeSafCreatedDocument { output.use { it.write(65) } }
            }
            assertEquals(InternalWorkspaceErrorCode.UNKNOWN_OUTCOME, failure.error.code)
            assertEquals(listOf(65.toByte()), Files.readAllBytes(created).toList())
            assertTrue(closed)
        }
    }

    @Test fun failedVerificationAfterWritingKeepsUnknownAndSuccessfulVerificationReturnsValue() {
        for (code in listOf(InternalWorkspaceErrorCode.PERMISSION_DENIED, InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS)) {
            val created = Files.createFile(directory.resolve(code.name))
            val failure = assertThrows(InternalWorkspaceFailure::class.java) {
                completeSafCreatedDocument {
                    Files.newOutputStream(created).use { it.write(65) }
                    code.error()
                }
            }
            assertEquals(InternalWorkspaceErrorCode.UNKNOWN_OUTCOME, failure.error.code)
            assertEquals(1L, Files.size(created))
        }
        assertEquals("verified-version", completeSafCreatedDocument { "verified-version" })
    }
}
