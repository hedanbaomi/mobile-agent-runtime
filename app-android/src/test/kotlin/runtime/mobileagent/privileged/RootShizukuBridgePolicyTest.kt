// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootAccessDenial
import runtime.mobileagent.domain.RootConfinementRejection
import runtime.mobileagent.skills.tooling.ToolErrorCode

class RootShizukuBridgePolicyTest {
    private val signing = "a".repeat(64)
    private val apkHash = "b".repeat(64)
    private val expectation = PinnedAppBootstrapExpectation(
        expectedAuthority = Authority.SHIZUKU_ROOT,
        expectedAppUid = 10148,
        expectedUserId = 0,
        expectedPackageName = "runtime.mobileagent",
        expectedSigningSha256 = signing,
        expectedApkSha256 = apkHash,
        expectedAppInstanceId = "app-instance-20261030-0001",
        expectedIdentityEpoch = 3L,
    )
    private val validObserved = ObservedOsAppIdentity(
        observedServiceUid = 0,
        callingUid = 10148,
        packagesForCallingUid = listOf("runtime.mobileagent"),
        resolvedPackageUidForUser = 10148,
        installedPackageUid = 10148,
        installedSigningSha256 = signing,
        archivePackageName = "runtime.mobileagent",
        archiveSigningSha256 = signing,
        canonicalLoadedCodePath = "/data/app/runtime.mobileagent-1/base.apk",
        canonicalInstalledSourceDir = "/data/app/runtime.mobileagent-1/base.apk",
        currentApkSha256 = apkHash,
    )

    @Test
    fun shizukuRootAndShellChannelsEnforceStrictUidAndTagSeparationWithoutFallback() {
        assertNotEquals(
            RootShizukuBridgePolicy.ROOT_USER_SERVICE_TAG,
            RootShizukuBridgePolicy.SHELL_USER_SERVICE_TAG,
        )
        // SHIZUKU_ROOT accepts UID 0 and rejects UID 2000 with PRIVILEGE_MISMATCH
        assertEquals(
            RootBridgeGateDecision.Allowed,
            RootShizukuBridgePolicy.evaluateRootShizukuServer(
                selectedAuthority = Authority.SHIZUKU_ROOT,
                binderAlive = true,
                preV11 = false,
                permissionGranted = true,
                serverUid = 0,
                serverVersion = 13,
            ),
        )
        assertEquals(
            RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "shizuku_running_as_shell_uid_2000",
            ),
            RootShizukuBridgePolicy.evaluateRootShizukuServer(
                selectedAuthority = Authority.SHIZUKU_ROOT,
                binderAlive = true,
                preV11 = false,
                permissionGranted = true,
                serverUid = 2000,
                serverVersion = 13,
            ),
        )
        // Degraded session fails closed with ROOT_SERVICE_UNAVAILABLE (reason=root_session_degraded, retryable=false)
        assertEquals(
            RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.ROOT_SERVICE_UNAVAILABLE,
                "root_session_degraded",
                retryable = false,
            ),
            RootShizukuBridgePolicy.evaluateRootShizukuServer(
                selectedAuthority = Authority.SHIZUKU_ROOT,
                binderAlive = true,
                preV11 = false,
                permissionGranted = true,
                serverUid = 0,
                serverVersion = 13,
                sessionDegraded = true,
            ),
        )
        // SHIZUKU (ADB shell) rejects UID 0 and requires selecting SHIZUKU_ROOT explicitly
        assertEquals(
            RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "shizuku_running_as_root_select_shizuku_root",
            ),
            RootShizukuBridgePolicy.evaluateShellShizukuServerUid(
                selectedAuthority = Authority.SHIZUKU,
                serverUid = 0,
            ),
        )
        assertEquals(
            RootBridgeGateDecision.Allowed,
            RootShizukuBridgePolicy.evaluateShellShizukuServerUid(
                selectedAuthority = Authority.SHIZUKU,
                serverUid = 2000,
            ),
        )
    }

    @Test
    fun pinnedAppIdentityVerifierRejectsForeignCallersSharedUidsCrossUserAndApkDrift() {
        assertEquals(
            PrivilegedIdentityVerificationResult.Verified,
            PrivilegedIdentityVerifier.verify(expectation, validObserved),
        )
        // Service running as shell (2000) -> PRIVILEGE_MISMATCH
        assertEquals(
            PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "service_uid_not_root",
            ),
            PrivilegedIdentityVerifier.verify(expectation, validObserved.copy(observedServiceUid = 2000)),
        )
        // Foreign caller UID -> ROOT_REQUEST_DENIED
        assertEquals(
            PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.ROOT_REQUEST_DENIED,
                "caller_uid_mismatch",
            ),
            PrivilegedIdentityVerifier.verify(expectation, validObserved.copy(callingUid = 10149)),
        )
        // Shared UID with another package -> IDENTITY_CHANGED
        assertEquals(
            PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "package_uid_or_shared_uid_mismatch",
            ),
            PrivilegedIdentityVerifier.verify(
                expectation,
                validObserved.copy(packagesForCallingUid = listOf("runtime.mobileagent", "com.other.shared")),
            ),
        )
        // Signature rotation / mismatch -> IDENTITY_CHANGED
        assertEquals(
            PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "signing_certificate_mismatch",
            ),
            PrivilegedIdentityVerifier.verify(
                expectation,
                validObserved.copy(installedSigningSha256 = "c".repeat(64)),
            ),
        )
        // Hot-swapped APK sourceDir or hash -> IDENTITY_CHANGED
        assertEquals(
            PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "apk_source_or_digest_changed",
            ),
            PrivilegedIdentityVerifier.verify(
                expectation,
                validObserved.copy(canonicalInstalledSourceDir = "/data/app/runtime.mobileagent-2/base.apk"),
            ),
        )
    }

    @Test
    fun handshakeRequiresProtocol4AndBindGenerationGuardIgnoresStaleCallbacks() {
        val validHandshake = PrivilegedSessionIdentity(
            authority = Authority.SHIZUKU_ROOT,
            privilegeTier = PrivilegeTier.ROOT,
            serviceUid = 0,
            appUid = 10148,
            androidUserId = 0,
            appInstanceId = expectation.expectedAppInstanceId,
            packageName = expectation.expectedPackageName,
            signingSha256 = signing,
            identityEpoch = 3L,
            serviceInstanceId = "svc-instance-20261030-0001",
            sessionId = "session-20261030-0001",
            protocolVersion = 4,
        )
        assertEquals(
            RootBridgeGateDecision.Allowed,
            RootShizukuBridgePolicy.evaluateRootHandshake(expectation, validHandshake),
        )
        // Old Shizuku protocol 3 is rejected
        assertEquals(
            RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "protocol_version_mismatch",
            ),
            RootShizukuBridgePolicy.evaluateRootHandshake(
                expectation,
                validHandshake.copy(protocolVersion = 3),
            ),
        )
        // Epoch mismatch is rejected
        assertEquals(
            RootBridgeGateDecision.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "app_or_epoch_binding_mismatch",
            ),
            RootShizukuBridgePolicy.evaluateRootHandshake(
                expectation,
                validHandshake.copy(identityEpoch = 2L),
            ),
        )

        // Bind generation guard: old disconnect callback after rebind cannot clear new session
        val guard = RootBindGenerationGuard()
        val gen1 = guard.beginBind()
        assertTrue(guard.acceptConnected(gen1, "session-gen-1"))
        assertEquals("session-gen-1", guard.currentSessionId())
        val gen2 = guard.beginBind()
        assertTrue(guard.acceptConnected(gen2, "session-gen-2"))
        assertFalse(guard.acceptDisconnectOrDeath(gen1))
        assertEquals("session-gen-2", guard.currentSessionId())
        assertTrue(guard.acceptDisconnectOrDeath(gen2))
        assertNull(guard.currentSessionId())

        // Error mapping check
        val degradedErr = RootBridgeErrorCode.fromAccessDenial(RootAccessDenial.SESSION_DEGRADED)
        assertEquals(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, degradedErr.code)
        assertFalse(degradedErr.retryable)
        assertEquals("root_session_degraded", degradedErr.details["reason"])
        val cpErr = RootBridgeErrorCode.fromConfinementRejection(RootConfinementRejection.CONTROL_PLANE_PROTECTED)
        assertEquals(ToolErrorCode.ROOT_OPERATION_FORBIDDEN, cpErr.code)
    }

    @Test
    fun androidRootPosixDriverEnforcesRetainedFdConfinementFailClosedStatAndPostCommitUnknownOutcome() {
        val tempDir = java.nio.file.Files.createTempDirectory("mar-root-driver-test")
        try {
            val wsDir = java.nio.file.Files.createDirectories(tempDir.resolve("workspace"))
            // 1. Default AndroidOsPosixFdOperations fails closed on host JVM when kernel Os.open/fstat/mnt_id are unavailable,
            // never fabricating synthetic inodes, nlink=1, or owner=0:0.
            val unbackedDriver = AndroidRootPosixFileDriver(
                workspaceRootPath = wsDir,
                defaultMountId = 77L,
                selinuxLabelReader = { "u:object_r:app_data_file:s0" },
                selinuxLabelWriter = { _, _ -> true },
            )
            java.nio.file.Files.write(wsDir.resolve("linked"), "LINK".toByteArray(Charsets.UTF_8))
            assertNull(unbackedDriver.inspectOpenedTarget("ws-1", "linked", false))

            // 2. Test FD-confined driver with explicit in-memory FD table PosixFdOperations:
            val wsRootPosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(wsDir)
            val fakeOps = InMemoryTestPosixFdOperations(wsRootPosix)
            val driver = AndroidRootPosixFileDriver(workspaceRootPath = wsDir, fdOps = fakeOps)
            val wsNode = fakeOps.inspectDirectoryIdentityNoFollow(wsDir)!!
            val ceNode = runtime.mobileagent.domain.RootFdNodeIdentity(1L, 9001L, 77L, true, false, false, 2L, 448, 10100, 10100, "u:object_r:app_data_file:s0")
            val deNode = runtime.mobileagent.domain.RootFdNodeIdentity(1L, 9002L, 77L, true, false, false, 2L, 448, 10100, 10100, "u:object_r:app_data_file:s0")
            val controlPlane = runtime.mobileagent.domain.RootControlPlaneBoundary(
                credentialEncryptedDataDir = "/data/user/0/runtime.mobileagent",
                credentialEncryptedIdentity = ceNode,
                deviceProtectedDataDir = "/data/user_de/0/runtime.mobileagent",
                deviceProtectedIdentity = deNode,
            )
            val executor = runtime.mobileagent.domain.RootWorkspaceFileExecutor(
                workspaceRootIdentity = wsNode,
                controlPlane = controlPlane,
                driver = driver,
            )

            // Create new file via linkNoReplaceAt
            val content1 = "hello=1".toByteArray(Charsets.UTF_8)
            val sha1 = sha256Hex("hello=1")
            val created = executor.executeAtomicWriteText(
                runtime.mobileagent.domain.RootTypedArguments.FileWriteText(
                    workspaceId = "ws-1",
                    relativePath = "app.conf",
                    contentSha256 = sha1,
                    contentByteLength = content1.size,
                    replaceExisting = false,
                    expectedVersion = null,
                ),
                content1,
            ) as runtime.mobileagent.domain.RootFileOperationResult.Succeeded
            assertEquals("app.conf", created.value.relativePath)

            // Healthy atomic replacement succeeds without false RENAME_RACE_DETECTED or degradation
            val contentHealthy = "hello=healthy".toByteArray(Charsets.UTF_8)
            val shaHealthy = sha256Hex("hello=healthy")
            val healthyReplaced = executor.executeAtomicWriteText(
                runtime.mobileagent.domain.RootTypedArguments.FileWriteText(
                    workspaceId = "ws-1",
                    relativePath = "app.conf",
                    contentSha256 = shaHealthy,
                    contentByteLength = contentHealthy.size,
                    replaceExisting = true,
                    expectedVersion = created.value.version,
                ),
                contentHealthy,
            ) as runtime.mobileagent.domain.RootFileOperationResult.Succeeded
            assertEquals("hello=healthy", fakeOps.readFileText("app.conf"))
            assertNotEquals(created.value.version, healthyReplaced.value.version)

            // Same-inode, same-length content change still produces a new version via fstatVersionStamp
            val beforeInPlace = driver.inspectOpenedTarget("ws-1", "app.conf", false)!!
            fakeOps.mutateInPlaceSameLength("app.conf", "hello=changed".toByteArray(Charsets.UTF_8))
            val afterInPlace = driver.inspectOpenedTarget("ws-1", "app.conf", false)!!
            assertNotEquals(beforeInPlace.version, afterInPlace.version)

            // Concurrent creator race after inspectParentForCreate is rejected by linkNoReplaceAt (EEXIST) without overwriting!
            fakeOps.injectConcurrentCreateBeforeLink = "CONCURRENT".toByteArray(Charsets.UTF_8)
            val concurrentRace = executor.executeAtomicWriteText(
                runtime.mobileagent.domain.RootTypedArguments.FileWriteText(
                    workspaceId = "ws-1",
                    relativePath = "race.conf",
                    contentSha256 = sha1,
                    contentByteLength = content1.size,
                    replaceExisting = false,
                    expectedVersion = null,
                ),
                content1,
            )
            assertTrue(concurrentRace is runtime.mobileagent.domain.RootFileOperationResult.Rejected)
            assertEquals("CONCURRENT", fakeOps.readFileText("race.conf"))
            fakeOps.injectConcurrentCreateBeforeLink = null

            // Explicit metadata: fchmod succeeds then fchown fails; preserve UNKNOWN_OUTCOME.
            fakeOps.failNextFchownAfterFchmod = true
            val openedBeforeMeta = driver.inspectOpenedTarget("ws-1", "app.conf", false)!!
            assertTrue(openedBeforeMeta.retainedFdHandle.isNotBlank())
            val partialMeta = executor.executeSetMetadata(
                runtime.mobileagent.domain.RootTypedArguments.FileSetMetadata(
                    workspaceId = "ws-1",
                    relativePath = "app.conf",
                    expectedVersion = openedBeforeMeta.version,
                    mode = 420,
                    uid = 10200,
                    gid = 10200,
                    ownerTransferAuthorized = true,
                ),
            )
            assertTrue(partialMeta is runtime.mobileagent.domain.RootFileOperationResult.PostMutationFailure)
            fakeOps.failNextFchownAfterFchmod = false

            // Post-commit observation failure after renameReplaceAt produces PostMutationFailure (never Denied(EIO)).
            fakeOps.failPostCommitFstat = true
            val content2 = "hello=2".toByteArray(Charsets.UTF_8)
            val sha2 = sha256Hex("hello=2")
            val currentOpened = driver.inspectOpenedTarget("ws-1", "app.conf", false)!!
            val postCommitFail = executor.executeAtomicWriteText(
                runtime.mobileagent.domain.RootTypedArguments.FileWriteText(
                    workspaceId = "ws-1",
                    relativePath = "app.conf",
                    contentSha256 = sha2,
                    contentByteLength = content2.size,
                    replaceExisting = true,
                    expectedVersion = currentOpened.version,
                ),
                content2,
            )
            assertTrue(postCommitFail is runtime.mobileagent.domain.RootFileOperationResult.PostMutationFailure)
            assertEquals("hello=2", fakeOps.readFileText("app.conf"))
            fakeOps.failPostCommitFstat = false

            // SettingsAuthoritySnapshot.providerFor resolves ROOT_DIRECT and SHIZUKU_ROOT.
            val snap = runtime.mobileagent.SettingsAuthoritySnapshot(
                selectedAuthority = Authority.ROOT_DIRECT,
                rootDirect = runtime.mobileagent.SettingsAuthorityProviderState(
                    authority = Authority.ROOT_DIRECT,
                    configured = true,
                    platformGrant = runtime.mobileagent.skills.tooling.PlatformGrant.GRANTED,
                    availability = runtime.mobileagent.skills.tooling.Availability.READY,
                    connection = runtime.mobileagent.skills.tooling.Connection.CONNECTED,
                ),
            )
            assertEquals(Authority.ROOT_DIRECT, snap.providerFor(Authority.ROOT_DIRECT)?.authority)
            assertEquals(Authority.SHIZUKU_ROOT, snap.providerFor(Authority.SHIZUKU_ROOT)?.authority)
            assertNull(snap.providerFor(Authority.NONE))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun defaultSelinuxXattrServiceEngineReceiptedDispatchExpectedVersionAndBoundedFloodDrain() {
        // 1. Default AndroidOsPosixFdOperations reads & writes security.selinux via /proc/self/fd/<n> without custom lambdas
        val xattrStore = HashMap<String, ByteArray>()
        xattrStore["/proc/self/fd/42"] = "u:object_r:app_data_file:s0\u0000".toByteArray(Charsets.UTF_8)
        val kernelBridge = object : PosixKernelBridge {
            override fun open(path: String, flags: Int, mode: Int) = java.io.FileDescriptor() to 42
            override fun close(rawFd: java.io.FileDescriptor, fdNumber: Int) = Unit
            override fun fstat(rawFd: java.io.FileDescriptor, fdNumber: Int) =
                PosixRawStat(1L, 5001L, 0b0100_000_000_000_000 or 448, 2L, 10148, 10148, 4096L, 100L, 100L)
            override fun lstat(path: String) = fstat(java.io.FileDescriptor(), 42)
            override fun readlink(path: String) = "/data/user/0/runtime.mobileagent"
            override fun readMountId(fdNumber: Int) = 88L
            override fun getxattr(path: String, name: String) =
                if (name == AndroidOsPosixFdOperations.XATTR_SELINUX) xattrStore[path]!! else ByteArray(0)
            override fun setxattr(path: String, name: String, value: ByteArray, flags: Int) {
                if (name == AndroidOsPosixFdOperations.XATTR_SELINUX) xattrStore[path] = value
            }
            override fun preadSampleDigest(rawFd: java.io.FileDescriptor, fdNumber: Int, sizeBytes: Long) = "digest"
            override fun fchmod(rawFd: java.io.FileDescriptor, fdNumber: Int, mode: Int) = Unit
            override fun fchown(rawFd: java.io.FileDescriptor, fdNumber: Int, uid: Int, gid: Int) = Unit
            override fun writeAllAndFsync(rawFd: java.io.FileDescriptor, fdNumber: Int, bytes: ByteArray) = Unit
            override fun fsync(rawFd: java.io.FileDescriptor, fdNumber: Int) = Unit
            override fun rename(src: String, dst: String) = Unit
            override fun link(src: String, dst: String) = Unit
            override fun remove(path: String) = Unit
        }
        val defaultFdOps = AndroidOsPosixFdOperations(kernel = kernelBridge)
        val openedDir = defaultFdOps.openDirectoryAbsoluteNoFollow("/data/user/0/runtime.mobileagent")!!
        val observedId = defaultFdOps.fstatIdentity(openedDir)!!
        assertEquals("u:object_r:app_data_file:s0", observedId.selinuxLabel)
        defaultFdOps.fsetSelinuxLabel(openedDir, "u:object_r:system_data_file:s0")
        assertEquals("u:object_r:system_data_file:s0", defaultFdOps.fstatIdentity(openedDir)!!.selinuxLabel)
        openedDir.close()

        // The bounded runner drains an output flood without exceeding the capture limit.
        val javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val floodReq = runtime.mobileagent.skills.tooling.ShellExecRequest.fromRuntime(
            callId = "call-flood",
            command = "flood",
            cwd = null,
            limits = runtime.mobileagent.skills.tooling.ShellLimits(5000L, 1024L),
            agentId = "agent",
            snapshotId = "snap",
            selectedAuthority = Authority.ROOT_DIRECT,
            dangerousMode = runtime.mobileagent.domain.DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            policyVersion = 1L,
            configSnapshotHash = "hash",
            sessionIdentity = "ses",
            toolSchemaVersion = 1,
        )
        // Use inline java source runner or direct process stream flood to test BoundedProcessGroupRunner
        val floodHelperFile = java.nio.file.Files.createTempFile("FloodPrint", ".java")
        try {
            floodHelperFile.toFile().writeText(
                "public class FloodPrint { public static void main(String[] a) { System.out.print(\"x\".repeat(262144)); } }",
            )
            val pb = ProcessBuilder(javaBin, floodHelperFile.toAbsolutePath().toString())
            val map = java.util.concurrent.ConcurrentHashMap<String, java.lang.Process>()
            val floodOutcome = BoundedProcessGroupRunner.executeProcess(pb, floodReq, map)
            assertEquals(runtime.mobileagent.skills.tooling.ShellExecutionStatus.SUCCEEDED, floodOutcome.status)
            assertFalse(floodOutcome.timedOut)
            assertTrue(floodOutcome.stdoutTruncated)
            assertEquals(1024, floodOutcome.stdout.length)
        } finally {
            java.nio.file.Files.deleteIfExists(floodHelperFile)
        }
    }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class InMemoryTestPosixFdOperations(
    private val rootPath: String,
) : PosixFdOperations {
    data class Node(
        var inode: Long,
        val isDirectory: Boolean,
        var modeOctal: Int,
        var ownerUid: Int,
        var ownerGid: Int,
        var selinuxLabel: String,
        var hardlinkCount: Long = 1L,
        var bytes: ByteArray = ByteArray(0),
        var versionTick: Long = 1L,
    )

    private var nextInode = 2000L
    private var nextFdNum = 100
    private var nextTick = 10L
    private val rootNode = Node(1000L, true, 448, 0, 0, "u:object_r:app_data_file:s0", 2L)
    private val children = LinkedHashMap<String, Node>()

    var injectConcurrentCreateBeforeLink: ByteArray? = null
    var failNextFchownAfterFchmod: Boolean = false
    var failPostCommitFstat: Boolean = false
    private var postCommitReopenArmed: Boolean = false

    private inner class FakeFd(
        override val fdNumber: Int,
        val node: Node,
        val path: String,
    ) : PosixRetainedFd {
        override var isOpen: Boolean = true
        override fun close() {
            isOpen = false
        }
    }

    fun readFileText(name: String): String? = children[name]?.bytes?.toString(Charsets.UTF_8)

    fun mutateInPlaceSameLength(name: String, newBytes: ByteArray) {
        val node = children[name] ?: return
        node.bytes = newBytes.copyOf()
        node.versionTick = nextTick++
    }

    override fun openDirectoryAbsoluteNoFollow(absolutePath: String): PosixRetainedFd? {
        if (absolutePath != rootPath) return null
        return FakeFd(nextFdNum++, rootNode, rootPath)
    }

    override fun openChildDirectoryAtNoFollow(parentFd: PosixRetainedFd, childName: String): PosixRetainedFd? = null

    override fun openTargetAt(
        parentFd: PosixRetainedFd,
        childName: String,
        followSymlink: Boolean,
        writable: Boolean,
    ): PosixRetainedFd {
        val node = children[childName] ?: throw PosixOpException("ENOENT")
        return FakeFd(nextFdNum++, node, "$rootPath/$childName")
    }

    override fun statAtNoFollow(parentFd: PosixRetainedFd, childName: String): runtime.mobileagent.domain.RootFdNodeIdentity? {
        val node = children[childName] ?: return null
        return toIdentity(node)
    }

    override fun fstatIdentity(fd: PosixRetainedFd): runtime.mobileagent.domain.RootFdNodeIdentity? {
        val f = fd as? FakeFd ?: return null
        if (!f.isOpen) return null
        if (postCommitReopenArmed && !f.node.isDirectory) {
            return null
        }
        return toIdentity(f.node)
    }

    override fun fstatSizeBytes(fd: PosixRetainedFd): Long? = (fd as? FakeFd)?.node?.bytes?.size?.toLong()

    override fun fstatVersionStamp(fd: PosixRetainedFd): String {
        val f = fd as? FakeFd ?: return ""
        val contentDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(f.node.bytes)
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${f.node.versionTick}:$contentDigest"
    }

    override fun fstatContentDigest(fd: PosixRetainedFd): String? {
        val f = fd as? FakeFd ?: return null
        if (f.node.isDirectory) return null
        return java.security.MessageDigest.getInstance("SHA-256").digest(f.node.bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    override fun readFdResolvedPath(fd: PosixRetainedFd): String? = (fd as? FakeFd)?.path

    override fun fchmod(fd: PosixRetainedFd, modeOctal: Int) {
        val f = fd as? FakeFd ?: throw PosixOpException("EBADF")
        f.node.modeOctal = modeOctal and 0b111_111_111
        f.node.versionTick = nextTick++
    }

    override fun fchown(fd: PosixRetainedFd, ownerUid: Int, ownerGid: Int) {
        if (failNextFchownAfterFchmod) throw PosixOpException("EPERM")
        val f = fd as? FakeFd ?: throw PosixOpException("EBADF")
        f.node.ownerUid = ownerUid
        f.node.ownerGid = ownerGid
        f.node.versionTick = nextTick++
    }

    override fun fsetSelinuxLabel(fd: PosixRetainedFd, selinuxLabel: String) {
        val f = fd as? FakeFd ?: throw PosixOpException("EBADF")
        f.node.selinuxLabel = selinuxLabel
        f.node.versionTick = nextTick++
    }

    override fun createSiblingTempAt(parentFd: PosixRetainedFd, tempName: String, modeOctal: Int): PosixRetainedFd {
        if (children.containsKey(tempName)) throw PosixOpException("EEXIST")
        val node = Node(nextInode++, false, modeOctal and 0b111_111_111, 0, 0, "u:object_r:app_data_file:s0", 1L, ByteArray(0), nextTick++)
        children[tempName] = node
        return FakeFd(nextFdNum++, node, "$rootPath/$tempName")
    }

    override fun writeAllAndFsync(fd: PosixRetainedFd, bytes: ByteArray) {
        val f = fd as? FakeFd ?: throw PosixOpException("EBADF")
        f.node.bytes = bytes.copyOf()
        f.node.versionTick = nextTick++
    }

    override fun fsyncDirectory(dirFd: PosixRetainedFd) = Unit

    override fun renameReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        val temp = children.remove(tempName) ?: throw PosixOpException("ENOENT")
        children[targetName] = temp
        if (failPostCommitFstat) {
            postCommitReopenArmed = true
        }
    }

    override fun linkNoReplaceAt(parentFd: PosixRetainedFd, tempName: String, targetName: String) {
        injectConcurrentCreateBeforeLink?.let { concurrentBytes ->
            children[targetName] = Node(nextInode++, false, 384, 10100, 10100, "u:object_r:app_data_file:s0", 1L, concurrentBytes)
        }
        if (children.containsKey(targetName)) throw PosixOpException("EEXIST")
        val temp = children[tempName] ?: throw PosixOpException("ENOENT")
        children[targetName] = temp
    }

    override fun unlinkAt(parentFd: PosixRetainedFd, childName: String): Boolean =
        children.remove(childName) != null

    private fun toIdentity(node: Node) = runtime.mobileagent.domain.RootFdNodeIdentity(
        deviceId = 1L,
        inode = node.inode,
        mountId = 77L,
        isDirectory = node.isDirectory,
        isRegularFile = !node.isDirectory,
        isSymlink = false,
        hardlinkCount = node.hardlinkCount,
        modeOctal = node.modeOctal,
        ownerUid = node.ownerUid,
        ownerGid = node.ownerGid,
        selinuxLabel = node.selinuxLabel,
    )
}
