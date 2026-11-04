// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

/** Deterministic FD-boundary probes, not Android kernel or same-UID0 race acceptance. */
class RootMutationResumeRegressionTest {
    @Test
    fun cancellationAfterRenamePreservesUnknownOutcome() {
        Fixture().use { f ->
            f.ops.afterMove = { f.driver.close() }
            val result = f.executor.executeMove(
                RootTypedArguments.FileMove("ws", "source", "destination", replaceExisting = true),
            )
            assertNull(f.ops.base.readText("source"))
            assertEquals("old!", f.ops.base.readText("destination"))
            assertUnknown(result)
        }
    }

    @Test
    fun cancellationAfterAtomicNoReplaceRenamePreservesUnknownOutcome() {
        Fixture().use { f ->
            f.ops.afterMove = { f.driver.close() }
            val result = f.executor.executeMove(
                RootTypedArguments.FileMove("ws", "source", "destination"),
            )
            // A no-replace move is a single atomic rename: the source name leaves with the syscall,
            // so a cancel observed afterwards can only be UNKNOWN. There is no link/unlink step
            // that could delete a swapped source name.
            assertNull(f.ops.base.readText("source"))
            assertEquals("old!", f.ops.base.readText("destination"))
            assertUnknown(result)
        }
    }

    @Test
    fun deniedRenameWithoutSideEffectRemainsPermissionDenied() {
        Fixture().use { f ->
            f.ops.denyMove = true
            val result = f.executor.executeMove(
                RootTypedArguments.FileMove("ws", "source", "destination", replaceExisting = true),
            )
            assertTrue(result is RootFileOperationResult.PermissionDenied, "got $result")
            assertEquals("old!", f.ops.base.readText("source"))
            assertNull(f.ops.base.readText("destination"))
        }
    }

    @Test
    fun metadataContentDriftIsUnknownEvenWhenLengthIsUnchanged() {
        Fixture().use { f ->
            f.ops.afterChmod = { fd -> f.ops.base.writeAllAndFsync(fd, "evil".toByteArray()) }
            val before = f.executor.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "source"))
                as RootFileOperationResult.Succeeded
            val result = f.executor.executeSetMetadata(
                RootTypedArguments.FileSetMetadata("ws", "source", before.value.version, mode = 420),
            )
            assertEquals("evil", f.ops.base.readText("source"))
            assertUnknown(result)
        }
    }

    @Test
    fun driverDoesNotReportAppliedAfterMetadataContentDrift() {
        Fixture().use { f ->
            f.ops.afterChmod = { fd -> f.ops.base.writeAllAndFsync(fd, "longer content".toByteArray()) }
            val opened = f.driver.inspectOpenedTarget("ws", "source", false)!!
            val result = f.driver.applyMetadataOnOpenedFd(opened, 420, null, null, null)
            assertTrue(result is DriverMutationStatus.PostMutationObservationOrPartialFailure, "got $result")
        }
    }

    @Test
    fun executorRejectsMetadataReceiptWithChangedContentEvidence() {
        Fixture { driver ->
            object : RootPosixFileDriver by driver {
                override fun applyMetadataOnOpenedFd(
                    target: RootOpenedFileTarget,
                    modeOctal: Int?,
                    ownerUid: Int?,
                    ownerGid: Int?,
                    selinuxLabel: String?,
                ): DriverMutationStatus {
                    val applied = driver.applyMetadataOnOpenedFd(target, modeOctal, ownerUid, ownerGid, selinuxLabel)
                        as DriverMutationStatus.Applied
                    return DriverMutationStatus.Applied(applied.updatedTarget.copy(contentSha256 = "a".repeat(64)))
                }
            }
        }.use { f ->
            val before = f.executor.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "source"))
                as RootFileOperationResult.Succeeded
            assertUnknown(f.executor.executeSetMetadata(
                RootTypedArguments.FileSetMetadata("ws", "source", before.value.version, mode = 420),
            ))
        }
    }

    @Test
    fun unchangedMetadataSucceedsWithItsUnchangedVersion() {
        Fixture().use { f ->
            val before = f.executor.executeGetMetadata(RootTypedArguments.FileGetMetadata("ws", "source"))
                as RootFileOperationResult.Succeeded
            val result = f.executor.executeSetMetadata(
                RootTypedArguments.FileSetMetadata("ws", "source", before.value.version, mode = before.value.modeOctal),
            )
            assertTrue(result is RootFileOperationResult.Succeeded, "got $result")
            result as RootFileOperationResult.Succeeded
            assertEquals(before.value.version, result.value.version)
            assertEquals("old!", f.ops.base.readText("source"))
        }
    }

    private fun assertUnknown(result: RootFileOperationResult<*>) {
        assertTrue(result is RootFileOperationResult.PostMutationFailure, "got $result")
        result as RootFileOperationResult.PostMutationFailure
        assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, result.terminalOutcome)
        assertTrue(result.requiresDegradedEpoch)
    }

    private class Fixture(
        wrapDriver: (AndroidRootPosixFileDriver) -> RootPosixFileDriver = { it },
    ) : AutoCloseable {
        val ops = MutationOps()
        val driver = AndroidRootPosixFileDriver(Paths.get("/workspace"), ops)
        val executor: RootWorkspaceFileExecutor

        init {
            val root = ops.openDirectoryAbsoluteNoFollow("/workspace")!!
            val rootIdentity = root.use { fd ->
                ops.createSiblingTempAt(fd, "source", 384).use { ops.writeAllAndFsync(it, "old!".toByteArray()) }
                ops.fstatIdentity(fd)!!
            }
            val control = RootControlPlaneBoundary(
                "/data/user/0/app",
                RootFdNodeIdentity(1, 9001, 77, true, false, false, 2, 448, 10100, 10100),
                "/data/user_de/0/app",
                RootFdNodeIdentity(1, 9002, 77, true, false, false, 2, 448, 10100, 10100),
                listOf(RootFdNodeIdentity(1, 9003, 77, true, false, false, 2, 448, 10100, 10100)),
            )
            executor = RootWorkspaceFileExecutor(rootIdentity, control, wrapDriver(driver))
        }

        override fun close() = driver.close()
    }

    private class MutationOps(
        val base: InMemoryServiceFdOps = InMemoryServiceFdOps("/workspace"),
    ) : PosixFdOperations by base {
        var afterMove: (() -> Unit)? = null
        var denyMove = false
        var afterChmod: ((PosixRetainedFd) -> Unit)? = null

        override fun fchmod(fd: PosixRetainedFd, modeOctal: Int) {
            base.fchmod(fd, modeOctal)
            afterChmod?.invoke(fd)
        }

        override fun renameAt(
            srcParentFd: PosixRetainedFd,
            srcName: String,
            dstParentFd: PosixRetainedFd,
            dstName: String,
        ) {
            if (denyMove) throw PosixOpException("EACCES")
            base.renameReplaceAt(srcParentFd, srcName, dstName)
            afterMove?.invoke()
        }

        override fun linkAtNoReplace(
            srcParentFd: PosixRetainedFd,
            srcName: String,
            dstParentFd: PosixRetainedFd,
            dstName: String,
        ) {
            if (denyMove) throw PosixOpException("EACCES")
            base.linkNoReplaceAt(srcParentFd, srcName, dstName)
            afterMove?.invoke()
        }

        /** The no-replace move commit is one atomic `renameat2(RENAME_NOREPLACE)`. */
        override fun renameAtNoReplace(
            srcParentFd: PosixRetainedFd,
            srcName: String,
            dstParentFd: PosixRetainedFd,
            dstName: String,
        ) {
            if (denyMove) throw PosixOpException("EACCES")
            base.renameReplaceAt(srcParentFd, srcName, dstName)
            afterMove?.invoke()
        }
    }
}
