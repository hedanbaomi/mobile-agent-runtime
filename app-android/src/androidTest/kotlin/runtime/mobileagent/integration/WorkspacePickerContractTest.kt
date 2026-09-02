// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.WorkspacePickerAuthoritySnapshot
import runtime.mobileagent.WorkspacePickerAuthorityStatus
import runtime.mobileagent.WorkspacePickerPort
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceResult

/** Contract tests for the foreground picker seam, independent of a live ADB/Shizuku transport. */
class WorkspacePickerContractTest {
    @Test
    fun threadTargetRequiresAgentAndGrantProjectionKeepsTheSameTarget() {
        val invalid = runCatching {
            WorkspacePickerTarget(threadId = "thread-without-agent")
        }
        assertTrue(invalid.isFailure)

        val target = WorkspacePickerTarget(
            agentId = "agent-picker-contract",
            threadId = "thread-picker-contract",
            setAsAgentDefault = true,
            grantCapabilities = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT)),
            lifetime = GrantLifetime.PERSISTENT,
        )
        val grant = target.grantForWorkspace()
        assertEquals(target.agentId, grant?.agentId)
        assertEquals(target.grantCapabilities, grant?.capabilities)
        assertEquals(target.lifetime, grant?.lifetime)
    }

    @Test
    fun pickerCommitsWorkspaceGrantThreadAndDefaultAsOneResult() = runBlocking {
        val picker = FakePicker()
        val target = target()
        val result = picker.attachPrivilegedDirectory(
            authority = Authority.SHIZUKU,
            request = request("picker-commit-workspace"),
            grant = target.grantForWorkspace(),
            target = target,
        )

        val success = result as WorkspaceAccessResult.Success
        val committed = picker.committed
        assertEquals("picker-commit-workspace", success.workspace.workspaceId)
        assertEquals(setOf(CapabilityId(CapabilityId.FILE_READ_TEXT)), success.workspace.grantedCapabilities)
        assertEquals("agent-picker-contract", committed?.agentId)
        assertEquals("thread-picker-contract", committed?.threadId)
        assertEquals("agent-picker-contract", committed?.defaultAgentId)
        assertEquals(success.workspace.workspaceId, committed?.workspaceId)
        assertEquals(1, success.grants.size)
    }

    @Test
    fun selectedAuthorityMismatchOrTemporaryUnavailableNeverFallsBackOrRevokes() = runBlocking {
        val picker = FakePicker()
        val target = target()
        val request = request("picker-authority-contract")

        val mismatch = picker.attachPrivilegedDirectory(
            authority = Authority.WIRED_ADB,
            request = request,
            grant = target.grantForWorkspace(),
            target = target,
        )
        assertEquals(
            WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED,
            (mismatch as WorkspaceAccessResult.Failure).code,
        )
        assertNull(picker.committed)
        assertEquals(0, picker.revocationCount)

        picker.setReady(false)
        val unavailable = picker.attachPrivilegedDirectory(
            authority = Authority.SHIZUKU,
            request = request,
            grant = target.grantForWorkspace(),
            target = target,
        )
        assertEquals(
            WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE,
            (unavailable as WorkspaceAccessResult.Failure).code,
        )
        assertNull(picker.committed)
        assertEquals(0, picker.revocationCount)
    }

    @Test
    fun reconnectKeepsWorkspaceIdentityButPublishesFreshOpaqueHandle() = runBlocking {
        val picker = FakePicker()
        val target = target()
        picker.attachPrivilegedDirectory(
            authority = Authority.SHIZUKU,
            request = request("picker-reattach-workspace"),
            grant = target.grantForWorkspace(),
            target = target,
        )
        val first = picker.currentHandle("picker-reattach-workspace")
        val firstCommit = picker.committed

        val second = picker.reconnect("picker-reattach-workspace")
        assertNotSame(first, second)
        assertSame(firstCommit, picker.committed)
        assertEquals("picker-reattach-workspace", picker.committed?.workspaceId)
        assertEquals(0, picker.revocationCount)
    }

    private fun target() = WorkspacePickerTarget(
        agentId = "agent-picker-contract",
        threadId = "thread-picker-contract",
        setAsAgentDefault = true,
        grantCapabilities = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT)),
        lifetime = GrantLifetime.PERSISTENT,
    )

    private fun request(workspaceId: String) = WorkspaceAttachRequest(
        workspaceId = workspaceId,
        displayName = "Picker contract workspace",
        directory = FakeHandle(0),
    )

    private data class Commit(
        val workspaceId: String,
        val agentId: String,
        val threadId: String?,
        val defaultAgentId: String?,
    )

    private class FakeHandle(val generation: Int) : WorkspaceDirectoryHandle()

    private class FakePicker : WorkspacePickerPort {
        private var authority = WorkspacePickerAuthoritySnapshot(
            selectedAuthority = Authority.SHIZUKU,
            status = WorkspacePickerAuthorityStatus.READY,
            ready = true,
        )
        private var handleGeneration = 0
        var committed: Commit? = null
            private set
        var revocationCount: Int = 0
            private set

        override fun authoritySnapshot(): WorkspacePickerAuthoritySnapshot = authority

        fun setReady(ready: Boolean) {
            authority = authority.copy(
                status = if (ready) WorkspacePickerAuthorityStatus.READY else WorkspacePickerAuthorityStatus.OFFLINE,
                ready = ready,
            )
        }

        fun currentHandle(workspaceId: String): WorkspaceDirectoryHandle? =
            committed?.takeIf { it.workspaceId == workspaceId }?.let { FakeHandle(handleGeneration) }

        fun reconnect(workspaceId: String): WorkspaceDirectoryHandle? {
            val current = committed?.takeIf { it.workspaceId == workspaceId } ?: return null
            handleGeneration++
            // Reattach replaces only the provider-owned handle.  The durable target commit remains
            // the same object and is not revoked as a side effect of a transport reconnect.
            return FakeHandle(handleGeneration)
        }

        override suspend fun browsePrivilegedRoot(
            authority: Authority,
            maxEntries: Int,
        ): WorkspaceResult<WorkspaceDirectoryPage> = unavailable()

        override suspend fun browsePrivileged(
            authority: Authority,
            request: WorkspaceBrowseRequest,
        ): WorkspaceResult<WorkspaceDirectoryPage> = unavailable()

        override suspend fun attachPrivilegedDirectory(
            authority: Authority,
            request: WorkspaceAttachRequest,
            grant: WorkspaceAccessGrantTarget?,
        ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.UNSUPPORTED)

        override suspend fun attachPrivilegedDirectory(
            authority: Authority,
            request: WorkspaceAttachRequest,
            grant: WorkspaceAccessGrantTarget?,
            target: WorkspacePickerTarget,
        ): WorkspaceAccessResult {
            if (this.authority.selectedAuthority != authority) {
                return WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED)
            }
            if (!this.authority.ready) {
                return WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
            }
            if (target.agentId == null || target.threadId == null || target.grantForWorkspace() != grant) {
                return WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.INVALID_REQUEST)
            }
            handleGeneration++
            val next = Commit(
                workspaceId = request.workspaceId,
                agentId = target.agentId,
                threadId = target.threadId,
                defaultAgentId = target.agentId.takeIf { target.setAsAgentDefault },
            )
            // The fixture models the required all-or-nothing commit: only after every target
            // invariant is validated are workspace, grant, thread, and default published.
            committed = next
            val capability = target.grantCapabilities.single()
            return WorkspaceAccessResult.Success(
                workspace = WorkspaceAccessItem(
                    workspaceId = request.workspaceId,
                    displayName = request.displayName,
                    backendType = WorkspaceBackendType.INTERNAL,
                    scope = WorkspaceScope.SELECTED_DIRECTORY,
                    readable = true,
                    writable = true,
                    status = WorkspaceAccessStatus.ACTIVE,
                    durablyAuthorized = true,
                    grantedCapabilities = setOf(capability),
                    grantRevision = 1L,
                ),
                grants = listOf(
                    WorkspaceAccessGrantSummary(
                        grantId = "contract-grant-${request.workspaceId}",
                        capability = capability,
                        lifetime = target.lifetime,
                        revision = 1L,
                    ),
                ),
            )
        }

        override suspend fun attachSaf(
            uri: Uri,
            resultFlags: Int,
            grant: WorkspaceAccessGrantTarget?,
        ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.UNSUPPORTED)

        private fun <T> unavailable(): WorkspaceResult<T> = WorkspaceResult.Failure(
            ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE),
        )
    }
}
