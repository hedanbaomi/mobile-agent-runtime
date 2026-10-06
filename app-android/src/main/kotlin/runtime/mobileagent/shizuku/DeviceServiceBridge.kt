// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.os.IBinder
import runtime.mobileagent.skills.tooling.ToolErrorCode

/** Typed device RPC, independent of its privilege bootstrap. */
interface DeviceServiceBridge {
    val unavailableErrorCode: ToolErrorCode get() = ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE
    fun dispatchList(relativePath: String): ShizukuDispatchResult
    fun dispatchListPaged(
        relativePath: String,
        maxEntries: Int,
        cursor: String?,
    ): ShizukuDispatchResult
    fun dispatchRead(relativePath: String, maxBytes: Int): ShizukuDispatchResult
    fun dispatchReadChunk(
        relativePath: String,
        offsetBytes: Long,
        maxBytes: Int,
    ): ShizukuWorkspaceReadDispatchResult
    fun dispatchApplyPatch(
        relativePath: String,
        patch: String,
        expectedVersion: String,
        format: String,
    ): ShizukuDispatchResult
    fun dispatchWrite(relativePath: String, content: ByteArray, replaceExisting: Boolean): ShizukuDispatchResult
    fun dispatchMkdir(relativePath: String): ShizukuDispatchResult
    fun dispatchDelete(relativePath: String): ShizukuDispatchResult
    fun dispatchStat(relativePath: String): ShizukuDispatchResult
    fun dispatchMove(
        sourcePath: String,
        destinationPath: String,
        replaceExisting: Boolean,
    ): ShizukuDispatchResult
    fun dispatchDirectoryRoot(maxEntries: Int, continuation: String? = null): ShizukuDispatchResult
    fun dispatchDirectoryBrowse(handle: String, maxEntries: Int, continuation: String? = null): ShizukuDispatchResult
    fun dispatchDirectoryAttach(handle: String): ShizukuDispatchResult
    fun dispatchDirectoryReattach(locator: ByteArray): ShizukuDispatchResult
    fun dispatchWorkspaceList(
        workspaceHandle: String,
        relativePath: String,
        maxEntries: Int,
    ): ShizukuDispatchResult
    fun dispatchWorkspaceListPaged(
        workspaceHandle: String,
        relativePath: String,
        maxEntries: Int,
        cursor: String?,
    ): ShizukuDispatchResult
    fun dispatchWorkspaceRead(
        workspaceHandle: String,
        relativePath: String,
        maxBytes: Int,
    ): ShizukuDispatchResult
    fun dispatchWorkspaceReadChunk(
        workspaceHandle: String,
        relativePath: String,
        offsetBytes: Long,
        maxBytes: Int,
    ): ShizukuWorkspaceReadDispatchResult
    fun dispatchWorkspaceApplyPatch(
        workspaceHandle: String,
        relativePath: String,
        patch: String,
        expectedVersion: String,
        format: String,
    ): ShizukuDispatchResult
    fun dispatchWorkspaceWrite(
        workspaceHandle: String,
        relativePath: String,
        content: ByteArray,
        replaceExisting: Boolean,
    ): ShizukuDispatchResult
    fun dispatchWorkspaceMkdir(
        workspaceHandle: String,
        relativePath: String,
    ): ShizukuDispatchResult
    fun dispatchWorkspaceDelete(
        workspaceHandle: String,
        relativePath: String,
    ): ShizukuDispatchResult
    fun dispatchWorkspaceStat(
        workspaceHandle: String,
        relativePath: String,
    ): ShizukuDispatchResult
    fun dispatchWorkspaceMove(
        workspaceHandle: String,
        sourcePath: String,
        destinationPath: String,
        replaceExisting: Boolean,
    ): ShizukuDispatchResult
    suspend fun execute(request: ShizukuShellRequest): ShizukuShellResult
    suspend fun cancel(callId: String): Boolean
}

/** The authenticated publisher owns connection consent and Binder lifetime. */
interface DeviceServiceConnection {
    fun binder(): IBinder?
    fun granted(): Boolean
}
