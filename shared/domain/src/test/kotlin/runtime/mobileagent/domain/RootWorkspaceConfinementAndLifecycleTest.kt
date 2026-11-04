// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootWorkspaceConfinementAndLifecycleTest {
    private val signing = "a".repeat(64)
    private val identity = PrivilegedSessionIdentity(
        authority = Authority.ROOT_DIRECT,
        privilegeTier = PrivilegeTier.ROOT,
        serviceUid = 0,
        appUid = 10148,
        androidUserId = 0,
        appInstanceId = "app-instance-20261030-0001",
        packageName = "runtime.mobileagent",
        signingSha256 = signing,
        identityEpoch = 1L,
        serviceInstanceId = "svc-instance-20261030-0001",
        sessionId = "session-20261030-0001",
        protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
    )
    private val validCaller = RootCallerObservation(
        callingUid = 10148,
        observedServiceUid = 0,
        packageName = "runtime.mobileagent",
        androidUserId = 0,
        signingSha256 = signing,
        binderAlive = true,
    )
    private val origin = RootWorkspaceOrigin(
        workspaceId = "ws-root-1",
        authority = Authority.ROOT_DIRECT,
        appInstanceId = identity.appInstanceId,
        packageName = identity.packageName,
        appUid = identity.appUid,
        androidUserId = identity.androidUserId,
        signingSha256 = signing,
        generationId = "ws-gen-20261030-0001",
    )
    private val ceDirNode = RootFdNodeIdentity(
        deviceId = 64768L,
        inode = 9001L,
        mountId = 106L,
        isDirectory = true,
        isRegularFile = false,
        isSymlink = false,
        hardlinkCount = 2L,
        modeOctal = 448,
        ownerUid = 10148,
        ownerGid = 10148,
    )
    private val deDirNode = RootFdNodeIdentity(
        deviceId = 64768L,
        inode = 9002L,
        mountId = 106L,
        isDirectory = true,
        isRegularFile = false,
        isSymlink = false,
        hardlinkCount = 2L,
        modeOctal = 448,
        ownerUid = 10148,
        ownerGid = 10148,
    )
    private val dbSubtreeNode = RootFdNodeIdentity(
        deviceId = 64768L,
        inode = 9003L,
        mountId = 106L,
        isDirectory = true,
        isRegularFile = false,
        isSymlink = false,
        hardlinkCount = 2L,
        modeOctal = 448,
        ownerUid = 10148,
        ownerGid = 10148,
    )
    private val controlPlane = RootControlPlaneBoundary(
        credentialEncryptedDataDir = "/data/user/0/runtime.mobileagent",
        credentialEncryptedIdentity = ceDirNode,
        deviceProtectedDataDir = "/data/user_de/0/runtime.mobileagent",
        deviceProtectedIdentity = deDirNode,
        pinnedSubtreeIdentities = listOf(dbSubtreeNode),
    )
    private val targetDirNode = RootFdNodeIdentity(
        deviceId = 64768L,
        inode = 5001L,
        mountId = 106L,
        isDirectory = true,
        isRegularFile = false,
        isSymlink = false,
        hardlinkCount = 2L,
        modeOctal = 448,
        ownerUid = 10200,
        ownerGid = 10200,
        selinuxLabel = "u:object_r:app_data_file:s0:c200,c256,c512,c768",
    )

    @Test
    fun fuseZeroInodeRoundTripsAndStillBindsDeviceMountAndObject() {
        val root = targetDirNode.copy(deviceId = 23L, inode = 0L, mountId = 48L)
        assertTrue(root.hasValidKernelIdentity)
        assertFalse(root.copy(inode = -1L).hasValidKernelIdentity)
        assertFalse(root.copy(mountId = 0L).hasValidKernelIdentity)
        assertFalse(root.sameKernelObjectAs(root.copy(mountId = 49L)))
        val payload = RootRecoveryLocatorPayload(
            originSha256 = origin.canonicalSha256(), workspaceId = origin.workspaceId,
            workspaceGenerationId = origin.generationId, authority = origin.authority,
            rootKind = RootWorkspaceRootKind.DIRECTORY, absoluteRootPath = "/storage/emulated",
            rootDeviceId = root.deviceId, rootInode = root.inode, rootMountId = root.mountId,
        )
        val bytes = RootRecoveryLocatorCodec.encode(payload)
        assertEquals(payload, RootRecoveryLocatorCodec.decode(bytes))
        assertNull(RootFdConfinementPolicy.verifyReattachLocator(
            bytes, origin, origin.canonicalSha256(), payload.absoluteRootPath, root, controlPlane,
        ).second)
        assertEquals(RootConfinementRejection.IDENTITY_MISMATCH, RootFdConfinementPolicy.verifyReattachLocator(
            bytes, origin, origin.canonicalSha256(), payload.absoluteRootPath, root.copy(inode = 1L), controlPlane,
        ).second)
    }

    @Test
    fun recoveryLocatorRoundTripsAndRejectsLegacyShizukuOrTamperedBytes() {
        val payload = RootRecoveryLocatorPayload(
            originSha256 = origin.canonicalSha256(),
            workspaceId = origin.workspaceId,
            workspaceGenerationId = origin.generationId,
            authority = origin.authority,
            rootKind = RootWorkspaceRootKind.DIRECTORY,
            absoluteRootPath = "/data/user/0/com.example.target/files",
            rootDeviceId = targetDirNode.deviceId,
            rootInode = targetDirNode.inode,
            rootMountId = targetDirNode.mountId,
        )
        assertFalse(payload.toString().contains("/data/user/0/com.example.target"))
        val encoded = RootRecoveryLocatorCodec.encode(payload)
        assertTrue(encoded.size <= RootRecoveryLocatorCodec.MAX_LOCATOR_BYTES)
        val decoded = RootRecoveryLocatorCodec.decode(encoded)
        assertEquals(payload, decoded)

        // Tamper one byte in body -> digest mismatch returns null.
        val tampered = encoded.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()
        assertNull(RootRecoveryLocatorCodec.decode(tampered))

        // Legacy Shizuku locator magic must be rejected.
        val legacyMagic = "MAR-SHIZUKU-LOCATOR".encodeToByteArray() + ByteArray(64)
        assertNull(RootRecoveryLocatorCodec.decode(legacyMagic))

        // Invalid paths (NUL, relative, .., trailing slash) are rejected.
        assertFalse(RootRecoveryLocatorCodec.isValidAbsoluteRootPath("relative/path"))
        assertFalse(RootRecoveryLocatorCodec.isValidAbsoluteRootPath("/data/../etc"))
        assertFalse(RootRecoveryLocatorCodec.isValidAbsoluteRootPath("/data/user/0/"))
        assertFalse(RootRecoveryLocatorCodec.isValidAbsoluteRootPath("/data/user\u0000/0"))
    }

    @Test
    fun controlPlaneAndFdConfinementRejectAliasesCrossMountHardlinksAndRenameRaces() {
        // Direct path in control plane is rejected.
        assertEquals(
            RootConfinementRejection.CONTROL_PLANE_PROTECTED,
            RootFdConfinementPolicy.verifyWorkspaceRoot(
                rootPath = "/data/user/0/runtime.mobileagent/databases",
                rootKind = RootWorkspaceRootKind.DIRECTORY,
                rootIdentity = targetDirNode,
                controlPlane = controlPlane,
            ),
        )
        // Bind-mount or alias path pointing to control-plane inode is rejected even under another path.
        val aliasedNode = targetDirNode.copy(inode = dbSubtreeNode.inode)
        assertEquals(
            RootConfinementRejection.CONTROL_PLANE_PROTECTED,
            RootFdConfinementPolicy.verifyWorkspaceRoot(
                rootPath = "/mnt/alias/target",
                rootKind = RootWorkspaceRootKind.DIRECTORY,
                rootIdentity = aliasedNode,
                controlPlane = controlPlane,
            ),
        )
        // Cross-mount step is rejected unless mountId is explicitly in allowConfirmedMountIds.
        val childDifferentMount = targetDirNode.copy(inode = 5002L, mountId = 208L)
        assertEquals(
            RootConfinementRejection.CROSS_MOUNT_REJECTED,
            RootFdConfinementPolicy.verifyDirectoryStep(
                workspaceRoot = targetDirNode,
                stepIdentity = childDifferentMount,
                candidateAbsolutePath = "/data/user/0/com.example.target/files/submount",
                controlPlane = controlPlane,
            ),
        )
        assertNull(
            RootFdConfinementPolicy.verifyDirectoryStep(
                workspaceRoot = targetDirNode,
                stepIdentity = childDifferentMount,
                candidateAbsolutePath = "/data/user/0/com.example.target/files/submount",
                controlPlane = controlPlane,
                trustedMountRoots = RootTrustedMountRoots(mapOf(208L to childDifferentMount)),
            ),
        )
        assertNotNull(
            RootFdConfinementPolicy.verifyDirectoryStep(
                workspaceRoot = targetDirNode,
                stepIdentity = childDifferentMount,
                candidateAbsolutePath = "/data/user/0/com.example.target/files/submount",
                controlPlane = controlPlane,
            ),
            "Numeric mount IDs without a frozen root identity must never authorize a cross-mount step",
        )
        // Regular file with nlink > 1 or rename race is rejected.
        val regularFile = RootFdNodeIdentity(
            deviceId = 64768L,
            inode = 6001L,
            mountId = 106L,
            isDirectory = false,
            isRegularFile = true,
            isSymlink = false,
            hardlinkCount = 1L,
            modeOctal = 384,
            ownerUid = 10200,
            ownerGid = 10200,
        )
        assertEquals(
            RootConfinementRejection.HARDLINK_REJECTED,
            RootFdConfinementPolicy.verifyRegularFileTarget(
                workspaceRoot = targetDirNode,
                parentBefore = targetDirNode,
                targetBefore = regularFile,
                parentCurrent = targetDirNode,
                targetCurrent = regularFile.copy(hardlinkCount = 2L),
                candidateAbsolutePath = "/data/user/0/com.example.target/files/a.txt",
                controlPlane = controlPlane,
            ),
        )
        assertEquals(
            RootConfinementRejection.RENAME_RACE_DETECTED,
            RootFdConfinementPolicy.verifyRegularFileTarget(
                workspaceRoot = targetDirNode,
                parentBefore = targetDirNode,
                targetBefore = regularFile,
                parentCurrent = targetDirNode,
                targetCurrent = regularFile.copy(inode = 6002L),
                candidateAbsolutePath = "/data/user/0/com.example.target/files/a.txt",
                controlPlane = controlPlane,
            ),
        )
    }

    @Test
    fun provisionalStageRemainsUnusableUntilConfirmedAndFailedPersistenceClosesFd() {
        var fdClosedCount = 0
        val session = RootServiceSession(identity = identity, controlPlane = controlPlane)
        assertNull(
            session.stageProvisionalDirectory(
                caller = validCaller,
                targetServiceInstanceId = identity.serviceInstanceId,
                targetSessionId = identity.sessionId,
                targetIdentityEpoch = identity.identityEpoch,
                provisionalHandle = "prov-handle-0001-aaaa",
                rootPath = "/data/user/0/com.example.target/files",
                rootKind = RootWorkspaceRootKind.DIRECTORY,
                directoryIdentity = targetDirNode,
                onCloseFd = { fdClosedCount += 1 },
            ),
        )
        val originHash = origin.canonicalSha256()
        val staged = session.attachProvisionalWorkspace(
            caller = validCaller,
            targetServiceInstanceId = identity.serviceInstanceId,
            targetSessionId = identity.sessionId,
            targetIdentityEpoch = identity.identityEpoch,
            provisionalHandle = "prov-handle-0001-aaaa",
            workspaceSessionHandle = "ws-session-handle-0001",
            origin = origin,
            originSha256 = originHash,
        ) as RootWorkspaceAttachResult.Staged

        // One-time provisional handle cannot be attached again.
        val secondAttempt = session.attachProvisionalWorkspace(
            caller = validCaller,
            targetServiceInstanceId = identity.serviceInstanceId,
            targetSessionId = identity.sessionId,
            targetIdentityEpoch = identity.identityEpoch,
            provisionalHandle = "prov-handle-0001-aaaa",
            workspaceSessionHandle = "ws-session-handle-0002",
            origin = origin,
            originSha256 = originHash,
        )
        assertEquals(
            RootWorkspaceAttachResult.Rejected(RootConfinementRejection.PROVISIONAL_HANDLE_INVALID),
            secondAttempt,
        )

        // Release staged workspace (simulating persistence failure) closes FD immediately.
        assertTrue(
            session.releaseStagedWorkspace(
                caller = validCaller,
                targetServiceInstanceId = identity.serviceInstanceId,
                targetSessionId = identity.sessionId,
                targetIdentityEpoch = identity.identityEpoch,
                workspaceSessionHandle = staged.workspaceSessionHandle,
            ),
        )
        assertEquals(1, fdClosedCount)
        assertFalse(
            session.confirmStagedWorkspace(
                caller = validCaller,
                targetServiceInstanceId = identity.serviceInstanceId,
                targetSessionId = identity.sessionId,
                targetIdentityEpoch = identity.identityEpoch,
                workspaceId = origin.workspaceId,
                workspaceGenerationId = origin.generationId,
                workspaceSessionHandle = staged.workspaceSessionHandle,
                originSha256 = originHash,
            ),
        )
    }

    @Test
    fun backgroundIdle30sAndForcedShutdown5sWithSetsidEscapeBehaveDeterministically() {
        var serviceStoppedReason: String? = null
        val session = RootServiceSession(identity = identity, controlPlane = controlPlane)
        val lifecycle = RootServiceLifecycleCoordinator(
            session = session,
            onStopService = { reason -> serviceStoppedReason = reason },
        )
        assertTrue(session.bindWorkspaceHandle(validCaller, origin.workspaceId, origin.generationId, "ws-handle-active-0001"))

        val args = RootTypedArguments.FileList(
            workspaceId = origin.workspaceId,
            relativePath = "docs",
            maxEntries = 100,
        )
        val envelope = RootControlEnvelope(
            requestId = "req-life-1",
            runId = "run-1",
            invocationId = "inv-1",
            snapshotId = "snap-1",
            grantId = "grant-1",
            grantBeforeRevision = 1L,
            grantAfterRevision = 1L,
            once = false,
            grantConsumedAt = null,
            execution = RootExecutionStamp(
                identity = identity,
                consentRevision = 1L,
                shellConsentRevision = 0L,
                policyVersion = 1L,
            ),
            capability = CapabilityId("file.list"),
            toolName = "file_list",
            workspaceId = origin.workspaceId,
            workspaceGenerationId = origin.generationId,
            workspaceSessionHandle = "ws-handle-active-0001",
            relativePathScope = "docs",
            arguments = args,
            rawArgumentsSha256 = "b".repeat(64),
            normalizedArgumentsSha256 = args.canonicalSha256(),
            budget = RootExecutionBudget(),
        )
        val accepted = session.submit(
            caller = validCaller,
            targetServiceInstanceId = identity.serviceInstanceId,
            targetSessionId = identity.sessionId,
            targetIdentityEpoch = identity.identityEpoch,
            controlSequence = 1L,
            rawEnvelopeJson = envelope.canonicalJson(),
        ) as RootControlSubmitResult.Accepted
        assertTrue(session.ledger.beginExecution(accepted.ticket))

        // App enters background at t=1000ms while work is RUNNING -> even at t=40_000ms (39s later),
        // service MUST NOT idle-stop while active work exists.
        lifecycle.onAppBackgroundChanged(appInBackground = true, nowElapsedMs = 1_000L)
        assertFalse(lifecycle.pollBackgroundIdleStop(nowElapsedMs = 40_000L))
        assertFalse(lifecycle.isServiceStopped())

        // Complete work at t=40_000ms -> 30s idle timer starts at 40_000ms.
        assertEquals(
            RootServiceExecutionStage.TERMINAL,
            session.ledger.completeExecution(accepted.ticket, RootServiceTerminalOutcome.SUCCEEDED).stage,
        )
        lifecycle.onWorkStateChanged(nowElapsedMs = 40_000L)
        assertFalse(lifecycle.pollBackgroundIdleStop(nowElapsedMs = 69_999L))
        assertTrue(lifecycle.pollBackgroundIdleStop(nowElapsedMs = 70_000L))
        assertEquals("background_idle_30s", serviceStoppedReason)

        // Now verify 5s forced shutdown with an unconfirmed setsid escape child.
        var secondStoppedReason: String? = null
        val identity2 = identity.copy(
            serviceInstanceId = "svc-instance-20261030-0002",
            sessionId = "session-20261030-0002",
            identityEpoch = 2L,
        )
        val session2 = RootServiceSession(identity = identity2, controlPlane = controlPlane)
        val lifecycle2 = RootServiceLifecycleCoordinator(
            session = session2,
            onStopService = { reason -> secondStoppedReason = reason },
        )
        assertTrue(session2.bindWorkspaceHandle(validCaller, origin.workspaceId, origin.generationId, "ws-handle-active-0002"))
        val envelope2 = envelope.copy(
            requestId = "req-life-2",
            execution = envelope.execution.copy(identity = identity2),
            workspaceSessionHandle = "ws-handle-active-0002",
        )
        val accepted2 = session2.submit(
            caller = validCaller,
            targetServiceInstanceId = identity2.serviceInstanceId,
            targetSessionId = identity2.sessionId,
            targetIdentityEpoch = identity2.identityEpoch,
            controlSequence = 1L,
            rawEnvelopeJson = envelope2.canonicalJson(),
        ) as RootControlSubmitResult.Accepted
        assertTrue(session2.ledger.beginExecution(accepted2.ticket))

        val shutdown = lifecycle2.initiateForcedShutdown("controller_binder_died", nowElapsedMs = 10_000L)
        assertEquals(15_000L, shutdown.deadlineElapsedMs)
        // A child escaped via setsid -> watchdog pipe did not reach EOF -> descendantsConfirmedDead = false.
        val escapeEvidence = RootShellTerminationEvidence(
            directProcessExited = true,
            processGroupSignaledAndEmpty = true,
            watchdogPipeReachedEof = false,
            knownDescendantsAlive = 1,
            timedOut = false,
            cancelled = true,
            exitCode = 137,
        )
        assertFalse(escapeEvidence.descendantsConfirmedDead)
        assertEquals(
            RootServiceTerminalOutcome.UNKNOWN_OUTCOME to true,
            escapeEvidence.classifyOutcome(),
        )
        assertFalse(shutdown.confirmRequestTerminated("req-life-2", escapeEvidence.descendantsConfirmedDead))
        assertNull(shutdown.settle(nowElapsedMs = 14_999L))
        val report = shutdown.settle(nowElapsedMs = 15_000L)
        assertNotNull(report)
        assertTrue(report!!.serviceStopped)
        assertTrue(report.timedOut)
        assertTrue(report.requiresDegradedEpoch)
        assertEquals(listOf("req-life-2"), report.unconfirmedRequestIds)
        assertEquals("controller_binder_died", secondStoppedReason)
    }

    @Test
    fun fileExecutorEnforcesAttributePreservationMetadataMutationsAndPresentationInvariants() {
        val fileNode = RootFdNodeIdentity(
            deviceId = targetDirNode.deviceId,
            inode = 8101L,
            mountId = targetDirNode.mountId,
            isDirectory = false,
            isRegularFile = true,
            isSymlink = false,
            hardlinkCount = 1L,
            modeOctal = 416,
            ownerUid = 10200,
            ownerGid = 10200,
            selinuxLabel = "u:object_r:app_data_file:s0:c200,c256,c512,c768",
        )
        val openedRegular = RootOpenedFileTarget(
            parentIdentityBefore = targetDirNode,
            targetIdentityBefore = fileNode,
            parentIdentityCurrent = targetDirNode,
            targetIdentityCurrent = fileNode,
            resolvedAbsolutePath = "/data/user/0/com.example.target/files/config.json",
            sizeBytes = 42L,
            version = "v7",
            directoryChain = listOf(targetDirNode),
            writeDomain = RootWriteDomainEvidence.uid0Exclusive(1),
        )
        var simulateDriftToRootUid = false
        var simulatePostReplaceHardlink = false
        var simulateMetadataIgnoreRequestedMode = false
        var writeReplacedCalledCount = 0
        val driver = object : RootPosixCreateCapableDriver {
            override fun inspectOpenedTarget(
                workspaceId: String,
                relativePath: String,
                followFinalSymlink: Boolean,
            ): RootOpenedFileTarget? = when (relativePath) {
                "config.json" -> openedRegular
                "control_alias.db" -> openedRegular.copy(
                    targetIdentityBefore = fileNode.copy(inode = dbSubtreeNode.inode),
                    targetIdentityCurrent = fileNode.copy(inode = dbSubtreeNode.inode),
                )
                else -> null
            }

            override fun inspectParentForCreate(
                workspaceId: String,
                relativePath: String,
            ): RootOpenedParentTarget? = when (relativePath) {
                "created.json" -> RootOpenedParentTarget(
                    parentIdentityBefore = targetDirNode,
                    parentIdentityCurrent = targetDirNode,
                    resolvedParentAbsolutePath = "/data/user/0/com.example.target/files",
                    resolvedTargetAbsolutePath = "/data/user/0/com.example.target/files/created.json",
                    directoryChain = listOf(targetDirNode),
                    writeDomain = RootWriteDomainEvidence.uid0Exclusive(1),
                )
                else -> null
            }

            override fun applyMetadataOnOpenedFd(
                target: RootOpenedFileTarget,
                modeOctal: Int?,
                ownerUid: Int?,
                ownerGid: Int?,
                selinuxLabel: String?,
            ): DriverMutationStatus {
                val effectiveMode = if (simulateMetadataIgnoreRequestedMode) {
                    target.targetIdentityCurrent.modeOctal
                } else {
                    modeOctal ?: target.targetIdentityCurrent.modeOctal
                }
                val updatedNode = target.targetIdentityCurrent.copy(
                    modeOctal = effectiveMode,
                    ownerUid = ownerUid ?: target.targetIdentityCurrent.ownerUid,
                    ownerGid = ownerGid ?: target.targetIdentityCurrent.ownerGid,
                    selinuxLabel = selinuxLabel ?: target.targetIdentityCurrent.selinuxLabel,
                )
                return DriverMutationStatus.Applied(
                    target.copy(
                        targetIdentityCurrent = updatedNode,
                        version = if (simulateMetadataIgnoreRequestedMode) target.version else "v8",
                    ),
                )
            }

            override fun writeAtomicSiblingAndReplace(
                target: RootOpenedFileTarget,
                utf8Bytes: ByteArray,
                preserveModeOctal: Int,
                preserveOwnerUid: Int,
                preserveOwnerGid: Int,
                preserveSelinuxLabel: String?,
            ): DriverWriteStatus {
                writeReplacedCalledCount += 1
                val resultingOwner = if (simulateDriftToRootUid) 0 else preserveOwnerUid
                val updatedNode = target.targetIdentityCurrent.copy(
                    inode = 8102L,
                    hardlinkCount = if (simulatePostReplaceHardlink) 2L else 1L,
                    mountId = if (simulatePostReplaceHardlink) 999L else target.targetIdentityCurrent.mountId,
                    modeOctal = preserveModeOctal,
                    ownerUid = resultingOwner,
                    ownerGid = preserveOwnerGid,
                    selinuxLabel = preserveSelinuxLabel,
                )
                return DriverWriteStatus.Replaced(
                    target.copy(
                        targetIdentityBefore = updatedNode,
                        targetIdentityCurrent = updatedNode,
                        resolvedAbsolutePath = if (simulatePostReplaceHardlink) {
                            "/data/user/0/runtime.mobileagent/no_backup/payload"
                        } else {
                            target.resolvedAbsolutePath
                        },
                        sizeBytes = utf8Bytes.size.toLong(),
                        version = "v8",
                    ),
                )
            }

            override fun createNewRegularFile(
                parent: RootOpenedParentTarget,
                relativePath: String,
                utf8Bytes: ByteArray,
            ): DriverWriteStatus {
                val createdNode = fileNode.copy(
                    inode = 8201L,
                    modeOctal = 384,
                    ownerUid = parent.parentIdentityCurrent.ownerUid,
                    ownerGid = parent.parentIdentityCurrent.ownerGid,
                    selinuxLabel = parent.parentIdentityCurrent.selinuxLabel,
                )
                return DriverWriteStatus.Replaced(
                    RootOpenedFileTarget(
                        parentIdentityBefore = parent.parentIdentityBefore,
                        targetIdentityBefore = createdNode,
                        parentIdentityCurrent = parent.parentIdentityCurrent,
                        targetIdentityCurrent = createdNode,
                        resolvedAbsolutePath = parent.resolvedTargetAbsolutePath,
                        sizeBytes = utf8Bytes.size.toLong(),
                        version = "v1",
                        directoryChain = parent.directoryChain,
                        writeDomain = parent.writeDomain,
                    ),
                )
            }
        }

        val executor = RootWorkspaceFileExecutor(
            workspaceRootIdentity = targetDirNode,
            controlPlane = controlPlane,
            driver = driver,
        )

        // 1. file_get_metadata succeeds on confined regular file and rejects control-plane alias.
        val getOk = executor.executeGetMetadata(
            RootTypedArguments.FileGetMetadata("ws-root-1", "config.json"),
        ) as RootFileOperationResult.Succeeded
        assertEquals(10200, getOk.value.ownerUid)
        assertEquals(416, getOk.value.modeOctal)

        val getAlias = executor.executeGetMetadata(
            RootTypedArguments.FileGetMetadata("ws-root-1", "control_alias.db"),
        )
        assertEquals(
            RootFileOperationResult.Rejected(RootConfinementRejection.CONTROL_PLANE_PROTECTED, "confinement_rejected"),
            getAlias,
        )

        // 2. file_set_metadata updates mode/owner on FD and increments version; rejects postcondition mismatch after mutation.
        val setOk = executor.executeSetMetadata(
            RootTypedArguments.FileSetMetadata(
                workspaceId = "ws-root-1",
                relativePath = "config.json",
                expectedVersion = "v7",
                mode = 384,
                uid = 10200,
                gid = 10200,
                ownerTransferAuthorized = true,
            ),
        ) as RootFileOperationResult.Succeeded
        assertEquals(384, setOk.value.modeOctal)
        assertEquals("v8", setOk.value.version)

        simulateMetadataIgnoreRequestedMode = true
        val setMismatch = executor.executeSetMetadata(
            RootTypedArguments.FileSetMetadata(
                workspaceId = "ws-root-1",
                relativePath = "config.json",
                expectedVersion = "v7",
                mode = 420,
                uid = 10200,
                gid = 10200,
                ownerTransferAuthorized = true,
            ),
        )
        assertEquals(
            RootFileOperationResult.PostMutationFailure("metadata_postcondition_mismatch"),
            setMismatch,
        )
        simulateMetadataIgnoreRequestedMode = false

        // 3. Atomic write enforces replaceExisting, preserves owner/mode/SELinux context, and verifies post-replace confinement.
        val okPayload = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
        val noReplaceWithVersion = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws-root-1",
                relativePath = "config.json",
                contentSha256 = RootControlJson.sha256Hex("{\"ok\":true}"),
                contentByteLength = okPayload.size,
                replaceExisting = false,
                expectedVersion = "v7",
            ),
            okPayload,
        )
        assertEquals(
            RootFileOperationResult.Rejected(
                RootConfinementRejection.LOCATOR_INVALID,
                "expected_version_forbidden_when_replace_disabled",
            ),
            noReplaceWithVersion,
        )
        val noReplaceOnExisting = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws-root-1",
                relativePath = "config.json",
                contentSha256 = RootControlJson.sha256Hex("{\"ok\":true}"),
                contentByteLength = okPayload.size,
                replaceExisting = false,
                expectedVersion = null,
            ),
            okPayload,
        )
        assertEquals(
            RootFileOperationResult.Rejected(
                RootConfinementRejection.RENAME_RACE_DETECTED,
                "target_already_exists_replace_disabled",
            ),
            noReplaceOnExisting,
        )
        assertEquals(0, writeReplacedCalledCount)

        val createNewOk = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws-root-1",
                relativePath = "created.json",
                contentSha256 = RootControlJson.sha256Hex("{\"ok\":true}"),
                contentByteLength = okPayload.size,
                replaceExisting = false,
                expectedVersion = null,
            ),
            okPayload,
        ) as RootFileOperationResult.Succeeded
        assertEquals("created.json", createNewOk.value.relativePath)
        assertEquals("v1", createNewOk.value.version)

        val writeOk = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws-root-1",
                relativePath = "config.json",
                contentSha256 = RootControlJson.sha256Hex("{\"ok\":true}"),
                contentByteLength = okPayload.size,
                replaceExisting = true,
                expectedVersion = "v7",
            ),
            okPayload,
        ) as RootFileOperationResult.Succeeded
        assertEquals(10200, writeOk.value.ownerUid)
        assertEquals("v8", writeOk.value.version)
        assertEquals(1, writeReplacedCalledCount)

        simulatePostReplaceHardlink = true
        val writeUnsafePostReplace = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws-root-1",
                relativePath = "config.json",
                contentSha256 = RootControlJson.sha256Hex("{\"ok\":true}"),
                contentByteLength = okPayload.size,
                replaceExisting = true,
                expectedVersion = "v7",
            ),
            okPayload,
        )
        assertEquals(
            RootFileOperationResult.PostMutationFailure(
                reason = "post_replace_confinement_failed",
                rejection = RootConfinementRejection.HARDLINK_REJECTED,
            ),
            writeUnsafePostReplace,
        )
        simulatePostReplaceHardlink = false

        simulateDriftToRootUid = true
        val badPayload = "{\"ok\":false}".toByteArray(Charsets.UTF_8)
        val writeDrifted = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = "ws-root-1",
                relativePath = "config.json",
                contentSha256 = RootControlJson.sha256Hex("{\"ok\":false}"),
                contentByteLength = badPayload.size,
                replaceExisting = true,
                expectedVersion = "v7",
            ),
            badPayload,
        )
        assertEquals(
            RootFileOperationResult.PostMutationFailure(
                reason = "post_replace_attribute_drift",
                terminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
                requiresDegradedEpoch = true,
            ),
            writeDrifted,
        )

        // 4. Presentation model never shows READY_UID_0 without both consent and live UID 0 identity,
        // and requires explicit user recovery acknowledgment when degraded.
        val unconfiguredRecord = RootAuthorityRecord(RootAuthorityConsent(Authority.ROOT_DIRECT))
        val awaitingConsentUi = RootAuthorityPresentation.buildChannelModel(
            authority = Authority.ROOT_DIRECT,
            selectedAuthority = Authority.ROOT_DIRECT,
            record = unconfiguredRecord,
            dangerousMode = DangerousMode.DISABLED,
            platformGranted = true,
            liveIdentity = identity,
        )
        assertEquals(RootChannelUiStatus.AWAITING_ROOT_CONSENT, awaitingConsentUi.status)

        val degradedRecord = RootAuthorityRecord(
            consent = RootAuthorityConsent(Authority.ROOT_DIRECT, enabled = true, shellEnabled = true, revision = 1, shellRevision = 1, policyVersion = 1),
            revision = 2,
            identityEpoch = 2,
            degradedEpoch = 1,
            recoveryAckEpoch = 0,
        )
        val degradedUi = RootAuthorityPresentation.buildChannelModel(
            authority = Authority.ROOT_DIRECT,
            selectedAuthority = Authority.ROOT_DIRECT,
            record = degradedRecord,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
            platformGranted = true,
            liveIdentity = identity.copy(identityEpoch = 2),
        )
        assertEquals(RootChannelUiStatus.DEGRADED_UNCERTAIN_DESCENDANTS_REQUIRES_ACK, degradedUi.status)
        assertFalse(degradedUi.effectiveShellReady)
        assertTrue(RootChannelAction.ACKNOWLEDGE_DEGRADED_RECOVERY in degradedUi.availableActions)
    }

    @Test
    fun w4SingleAppPrivateWriteDomainAndControlPlaneAliasConfinementBehaveDeterministically() {
        val slashDir = targetDirNode.copy(inode = 1L, modeOctal = 493, ownerUid = 0, ownerGid = 0)
        val dataDir = targetDirNode.copy(inode = 2L, modeOctal = 505, ownerUid = 1000, ownerGid = 1000)
        val userDir = targetDirNode.copy(inode = 3L, modeOctal = 457, ownerUid = 1000, ownerGid = 1000)
        val user0Dir = targetDirNode.copy(inode = 4L, modeOctal = 505, ownerUid = 1000, ownerGid = 1000)
        val appRootDir = targetDirNode.copy(inode = 5000L, modeOctal = 448, ownerUid = 10200, ownerGid = 10200)
        val appFilesDir = targetDirNode.copy(inode = 5001L, modeOctal = 448, ownerUid = 10200, ownerGid = 10200)

        // 1. Single-app 0700 private directory under /data/user/0/<pkg>, /data/data/<pkg>, and /data/user_de/0/<pkg> is proven.
        val userChain = listOf(slashDir, dataDir, userDir, user0Dir, appRootDir, appFilesDir)
        for (path in listOf(
            "/data/user/0/com.example.fixture/files",
            "/data/user_de/0/com.example.fixture/files",
        )) {
            val assessed = RootWriteDomainPolicy.assess(userChain, resolvedTargetParentPath = path)
            assertEquals(RootWriteDomainKind.SINGLE_APP_PRIVATE, assessed.kind)
            assertTrue(assessed.isProvenForMutation)
            assertEquals(10200, assessed.ownerUid)
        }
        val dataDataChain = listOf(slashDir, dataDir, userDir, appRootDir, appFilesDir)
        val dataDataAssessed = RootWriteDomainPolicy.assess(
            dataDataChain,
            resolvedTargetParentPath = "/data/data/com.example.fixture/files",
        )
        assertEquals(RootWriteDomainKind.SINGLE_APP_PRIVATE, dataDataAssessed.kind)
        assertTrue(dataDataAssessed.isProvenForMutation)
        assertEquals(10200, dataDataAssessed.ownerUid)

        // Ownership is a conditional-replacement capability, not a blanket Root write gate.
        val sharedChains = listOf(
            listOf(appRootDir),
            listOf(slashDir.copy(modeOctal = 511, ownerUid = 2000)),
            userChain.dropLast(1) + appFilesDir.copy(ownerUid = 10201, ownerGid = 10201),
            userChain.dropLast(1) + appFilesDir.copy(modeOctal = 0b111_000_010),
            userChain.dropLast(1) + appFilesDir.copy(modeOctal = 0b111_010_000, ownerGid = 2000),
        )
        for (chain in sharedChains) {
            val domain = RootWriteDomainPolicy.assess(chain, resolvedTargetParentPath = "/storage/emulated/0")
            assertEquals(RootWriteDomainKind.SHARED_DIRECTORY, domain.kind)
            assertTrue(domain.isProvenForMutation)
            assertFalse(domain.isUid0Exclusive)
        }
        // A pinned root-owned 0700 workspace under shell's writable tmp directory supports CAS.
        val exclusive = RootWriteDomainPolicy.assess(
            listOf(slashDir, dataDir, userDir.copy(ownerUid = 2000, modeOctal = 511),
                appRootDir.copy(ownerUid = 0, ownerGid = 0)),
            resolvedTargetParentPath = "/data/local/tmp/root-owned",
            workspaceChainStart = 3,
        )
        assertTrue(exclusive.isUid0Exclusive)
        assertFalse(RootWriteDomainPolicy.assess(emptyList()).isProvenForMutation)
        assertFalse(RootWriteDomainPolicy.assess(listOf(appRootDir.copy(isSymlink = true))).isProvenForMutation)

        // 6. Control-plane boundary blocks /data/data/<host_pkg> alias even when initialized with /data/user/0/<host_pkg>.
        assertTrue(
            controlPlane.isProtected(
                "/data/data/runtime.mobileagent/files/secret.json",
                appFilesDir,
            ),
        )
        assertTrue(controlPlane.isChainProtected(listOf(slashDir, dbSubtreeNode, appFilesDir)))
    }
}
