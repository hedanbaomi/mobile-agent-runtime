// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.feature.agents.WorkspacePickerAttachPhaseUi
import runtime.mobileagent.feature.agents.WorkspacePickerUiState
import runtime.mobileagent.integration.WorkspaceAccessErrorCode
import runtime.mobileagent.integration.WorkspaceAccessResult

internal object RootWorkspacePickerRequest {
    fun canConfirm(state: WorkspacePickerUiState, ready: Boolean): Boolean =
        state.rootDirectorySelectionAvailable && ready && state.attachPhase != WorkspacePickerAttachPhaseUi.ATTACHING

    suspend fun attach(port: WorkspacePickerPort, authority: Authority, path: String, writable: Boolean,
        metadata: Boolean, shell: Boolean, target: WorkspacePickerTarget): WorkspaceAccessResult = try {
        withContext(Dispatchers.IO) { port.attachRootDirectory(authority, path, writable, metadata, shell, target) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: RuntimeException) { WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME) }
}
