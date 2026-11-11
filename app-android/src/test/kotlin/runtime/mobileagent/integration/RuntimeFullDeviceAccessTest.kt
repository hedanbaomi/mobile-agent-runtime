// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.AgentWorkspaceDefaultRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.ConversationWorkspaceBindingRepository
import runtime.mobileagent.data.FullDeviceFilesGrantRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.privileged.InMemoryRootSqlConnection
import runtime.mobileagent.security.PrivilegedWorkspaceBindingEnvelope
import runtime.mobileagent.security.PrivilegedWorkspaceBindingSealResult
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceAttachment
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryBrowser
import runtime.mobileagent.skills.tooling.WorkspaceReattachRequest
import runtime.mobileagent.skills.tooling.WorkspaceRecoveryLocator
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.tooling.WorkspaceRegistry

/** Real in-memory SQLite; verifies full-device locator hygiene, id consistency, and grant rollback. */
class RuntimeFullDeviceAccessTest {
    @Test
    fun mismatchedDescriptorIdClearsRecoveryLocatorAndRollsBackNewGrant() = fixture { f ->
        val locator = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(7, 8, 9))
        val descriptor = WorkspaceDescriptor("unexpected-id", "Device files", WorkspaceBackendType.PRIVILEGED)
        val backend = object : WorkspaceBackend { override val descriptor = descriptor }
        val result = runBlocking {
            openFullDeviceFiles(
                ports = f.ports { WorkspaceResult.Success(WorkspaceAttachment(descriptor, backend, locator)) },
                authority = Authority.SHIZUKU,
                request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                grant = null,
            )
        }

        assertEquals(WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.CONFLICT), result)
        assertTrue(locator.isCleared)
        assertNull(f.fullDeviceGrants.load("full-device"))
        assertEquals(2L, f.fullDeviceGrants.currentRevision("full-device"))
        assertNull(f.workspaces.get("full-device"))
        assertNull(f.workspaces.get("unexpected-id"))
    }

    @Test
    fun concurrentConflictingWorkspaceAfterAttachClearsRecoveryLocatorAndRollsBackNewGrant() = fixture { f ->
        val locator = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(4, 5, 6))
        val descriptor = WorkspaceDescriptor("full-device", "Device files", WorkspaceBackendType.PRIVILEGED)
        val backend = object : WorkspaceBackend { override val descriptor = descriptor }
        val result = runBlocking {
            openFullDeviceFiles(
                ports = f.ports {
                    // Simulate a competing ordinary directory row committed while provider open was suspended.
                    f.workspaces.save(
                        Workspace(
                            id = "full-device",
                            displayName = "Selected folder",
                            backendType = WorkspaceBackendType.PRIVILEGED,
                            rootReference = "authority:SHIZUKU",
                            scope = WorkspaceScope.SELECTED_DIRECTORY,
                        ),
                    )
                    WorkspaceResult.Success(WorkspaceAttachment(descriptor, backend, locator))
                },
                authority = Authority.SHIZUKU,
                request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                grant = null,
            )
        }

        assertEquals(WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.CONFLICT), result)
        assertTrue(locator.isCleared)
        assertNull(f.fullDeviceGrants.load("full-device"))
        assertEquals(WorkspaceScope.SELECTED_DIRECTORY, f.workspaces.get("full-device")?.scope)
    }

    @Test
    fun cancellationDuringProviderOpenRollsBackNewlySavedFullDeviceGrant() = fixture { f ->
        val cancellation = CancellationException("Synthetic cancellation during openFullDeviceFiles")
        val actual = assertThrows(CancellationException::class.java) {
            runBlocking {
                openFullDeviceFiles(
                    ports = f.ports {
                        assertEquals(1L, f.fullDeviceGrants.load("full-device")?.revision)
                        throw cancellation
                    },
                    authority = Authority.SHIZUKU,
                    request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                    grant = null,
                )
            }
        }

        assertSame(cancellation, actual)
        assertNull(f.fullDeviceGrants.load("full-device"))
        assertEquals(2L, f.fullDeviceGrants.currentRevision("full-device"))
        assertNull(f.workspaces.get("full-device"))
    }

    @Test
    fun validAttachmentSealsBindingClearsLocatorAndRegistersWorkspace() = fixture { f ->
        val locator = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(11, 22, 33))
        val descriptor = WorkspaceDescriptor("full-device", "Device files", WorkspaceBackendType.PRIVILEGED)
        val backend = object : WorkspaceBackend { override val descriptor = descriptor }
        val result = runBlocking {
            openFullDeviceFiles(
                ports = f.ports { WorkspaceResult.Success(WorkspaceAttachment(descriptor, backend, locator)) },
                authority = Authority.SHIZUKU,
                request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                grant = null,
            )
        }

        assertTrue(result is WorkspaceAccessResult.Success)
        assertTrue(locator.isCleared)
        assertEquals(1L, f.fullDeviceGrants.load("full-device")?.revision)
        assertEquals(WorkspaceScope.FULL_DEVICE_FILES, f.workspaces.get("full-device")?.scope)
        assertEquals(PrivilegedWorkspaceBindingStatusActive, f.bindings.get("full-device")?.status?.name)
        assertEquals("full-device", f.registry.descriptor("full-device")?.id)
    }

    @Test
    fun invalidWorkspaceIdIsRejectedBeforePersistingGrantOrOpeningProvider() = fixture { f ->
        var providerInvoked = false
        val result = runBlocking {
            openFullDeviceFiles(
                ports = f.ports {
                    providerInvoked = true
                    error("Provider must not be invoked for an unpersistable workspaceId")
                },
                authority = Authority.SHIZUKU,
                request = FullDeviceFilesRequest(workspaceId = "invalid~aad", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                grant = null,
            )
        }

        assertEquals(WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.INVALID_REQUEST), result)
        assertEquals(false, providerInvoked)
        assertNull(f.fullDeviceGrants.load("invalid~aad"))
        assertNull(f.fullDeviceGrants.currentRevision("invalid~aad"))
        assertNull(f.workspaces.get("invalid~aad"))
    }

    @Test
    fun invalidBindingAadOrDescriptorClearsRecoveryLocatorAndRollsBackNewGrant() = fixture { f ->
        val aadLocator = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(13, 24, 35))
        val validDescriptor = WorkspaceDescriptor("full-device", "Device files", WorkspaceBackendType.PRIVILEGED)
        val validBackend = object : WorkspaceBackend { override val descriptor = validDescriptor }
        val aadResult = runBlocking {
            openFullDeviceFiles(
                ports = f.ports(appInstanceId = "invalid~app") {
                    WorkspaceResult.Success(WorkspaceAttachment(validDescriptor, validBackend, aadLocator))
                },
                authority = Authority.SHIZUKU,
                request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                grant = null,
            )
        }

        assertEquals(WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.INVALID_REQUEST), aadResult)
        assertTrue(aadLocator.isCleared)
        assertNull(f.fullDeviceGrants.load("full-device"))
        assertEquals(2L, f.fullDeviceGrants.currentRevision("full-device"))
        assertNull(f.workspaces.get("full-device"))
        assertNull(f.bindings.get("full-device"))

        val badQuotaLocator = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(46, 57, 68))
        val badQuotaDescriptor = WorkspaceDescriptor("full-device", "Device files", WorkspaceBackendType.PRIVILEGED, quotaBytes = 0L)
        val badQuotaBackend = object : WorkspaceBackend { override val descriptor = badQuotaDescriptor }
        val descriptorResult = runBlocking {
            openFullDeviceFiles(
                ports = f.ports {
                    WorkspaceResult.Success(WorkspaceAttachment(badQuotaDescriptor, badQuotaBackend, badQuotaLocator))
                },
                authority = Authority.SHIZUKU,
                request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 3L, confirmedByUser = true),
                grant = null,
            )
        }

        assertEquals(WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.INVALID_REQUEST), descriptorResult)
        assertTrue(badQuotaLocator.isCleared)
        assertNull(f.fullDeviceGrants.load("full-device"))
        assertEquals(4L, f.fullDeviceGrants.currentRevision("full-device"))
        assertNull(f.workspaces.get("full-device"))
    }

    @Test
    fun crossAuthorityReopenIsRejectedWithoutMutatingRootReferenceOrStrandingBinding() = fixture { f ->
        val initialLocator = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(21, 22, 23))
        val descriptor = WorkspaceDescriptor("full-device", "Device files", WorkspaceBackendType.PRIVILEGED)
        val backend = object : WorkspaceBackend { override val descriptor = descriptor }
        val initial = runBlocking {
            openFullDeviceFiles(
                ports = f.ports { WorkspaceResult.Success(WorkspaceAttachment(descriptor, backend, initialLocator)) },
                authority = Authority.SHIZUKU,
                request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                grant = null,
            )
        }
        assertTrue(initial is WorkspaceAccessResult.Success)

        var providerInvoked = false
        val conflict = runBlocking {
            openFullDeviceFiles(
                ports = f.ports(requiresRecoveryLocator = false) {
                    providerInvoked = true
                    WorkspaceResult.Success(WorkspaceAttachment(descriptor, backend, null))
                },
                authority = Authority.WIRED_ADB,
                request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                grant = null,
            )
        }

        assertEquals(WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.CONFLICT), conflict)
        assertEquals(false, providerInvoked)
        assertEquals("authority:SHIZUKU", f.workspaces.get("full-device")?.rootReference)
        assertEquals(Authority.SHIZUKU, f.bindings.get("full-device")?.authority)
        assertEquals(1L, f.fullDeviceGrants.load("full-device")?.revision)
    }

    @Test
    fun cancellationWhenGrantAlreadyActivePreservesExistingFullDeviceGrant() = fixture { f ->
        val initialLocator = WorkspaceRecoveryLocator.fromBytes(byteArrayOf(31, 32, 33))
        val descriptor = WorkspaceDescriptor("full-device", "Device files", WorkspaceBackendType.PRIVILEGED)
        val backend = object : WorkspaceBackend { override val descriptor = descriptor }
        val initial = runBlocking {
            openFullDeviceFiles(
                ports = f.ports { WorkspaceResult.Success(WorkspaceAttachment(descriptor, backend, initialLocator)) },
                authority = Authority.SHIZUKU,
                request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                grant = null,
            )
        }
        assertTrue(initial is WorkspaceAccessResult.Success)

        val cancellation = CancellationException("Synthetic cancellation on re-open")
        val actual = assertThrows(CancellationException::class.java) {
            runBlocking {
                openFullDeviceFiles(
                    ports = f.ports { throw cancellation },
                    authority = Authority.SHIZUKU,
                    request = FullDeviceFilesRequest(workspaceId = "full-device", displayName = "Device files", grantRevision = 1L, confirmedByUser = true),
                    grant = null,
                )
            }
        }

        assertSame(cancellation, actual)
        assertEquals(1L, f.fullDeviceGrants.load("full-device")?.revision)
        assertEquals("authority:SHIZUKU", f.workspaces.get("full-device")?.rootReference)
    }

    private fun fixture(block: (Fixture) -> Unit) = InMemoryRootSqlConnection().use { db ->
        Migrations.apply(db)
        block(Fixture(db))
    }

    private class Fixture(val db: InMemoryRootSqlConnection) {
        val workspaces = WorkspaceRepository(db)
        val bindings = PrivilegedWorkspaceBindingRepository(db)
        val fullDeviceGrants = FullDeviceFilesGrantRepository(db)
        val registry = WorkspaceRegistry()
        private val store = RuntimeWorkspaceGrantStore(
            db = db,
            workspaceRepository = workspaces,
            capabilityGrantRepository = CapabilityGrantRepository(db),
            authorityPolicyRepository = AuthorityPolicyRepository(db),
            conversationWorkspaceBindingRepository = ConversationWorkspaceBindingRepository(db),
            agentWorkspaceDefaultRepository = AgentWorkspaceDefaultRepository(db),
        )

        fun ports(
            requiresRecoveryLocator: Boolean = true,
            appInstanceId: String = "app-instance-fixture",
            open: suspend (FullDeviceFilesRequest) -> WorkspaceResult<WorkspaceAttachment>,
        ) = FullDeviceAccessPorts(
            db = db,
            workspaceRepository = workspaces,
            privilegedWorkspaceBindingRepository = bindings,
            fullDeviceFilesGrantRepository = fullDeviceGrants,
            workspaceRegistry = registry,
            grantStore = store,
            appInstanceId = appInstanceId,
            permitsDangerousMode = true,
            requiresRecoveryLocator = requiresRecoveryLocator,
            authorityProviderFor = { requestedAuthority ->
                object : PrivilegedWorkspaceProvider {
                    override val authority = requestedAuthority
                    override val supportsFullDeviceFiles = true
                    override val directoryBrowser: WorkspaceDirectoryBrowser get() = error("Unused")
                    override suspend fun attachDirectory(request: WorkspaceAttachRequest): WorkspaceResult<WorkspaceAttachment> = error("Unused")
                    override suspend fun reopenDirectory(request: WorkspaceReattachRequest): WorkspaceResult<WorkspaceAttachment> = error("Unused")
                    override suspend fun openFullDeviceFiles(request: FullDeviceFilesRequest): WorkspaceResult<WorkspaceAttachment> = open(request)
                    override suspend fun revokeFullDeviceFiles(workspaceId: String, expectedRevision: Long): WorkspaceResult<Unit> = error("Unused")
                    override fun close() = Unit
                }
            },
            privilegedFailure = { ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE },
            sealLocator = { _, _ ->
                PrivilegedWorkspaceBindingSealResult.Success(
                    PrivilegedWorkspaceBindingEnvelope.create(
                        nonce = ByteArray(12) { 1 },
                        ciphertext = ByteArray(32) { 2 },
                        aadDigest = ByteArray(32) { 3 },
                    ),
                )
            },
            present = { workspace, _ ->
                WorkspaceAccessItem(
                    workspaceId = workspace.id,
                    displayName = workspace.displayName,
                    backendType = workspace.backendType,
                    scope = workspace.scope,
                    readable = workspace.readable,
                    writable = workspace.writable,
                    status = WorkspaceAccessStatus.ACTIVE,
                    authority = Authority.SHIZUKU,
                )
            },
        )
    }

    private companion object {
        const val PrivilegedWorkspaceBindingStatusActive = "ACTIVE"
    }
}
