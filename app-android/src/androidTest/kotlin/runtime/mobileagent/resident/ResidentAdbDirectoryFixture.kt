// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.Context
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryBrowser
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceResult

/** Navigate through real opaque picker handles; never synthesize a provider path/token. */
internal object ResidentAdbDirectoryFixture {
    suspend fun downloads(context: Context, browser: WorkspaceDirectoryBrowser): WorkspaceDirectoryHandle {
        var page = value(browser.root(256))
        for (name in listOf("storage", "emulated", (context.applicationInfo.uid / 100_000).toString(), "Download")) {
            val handle = page.entries.firstOrNull { it.name == name }?.handle
                ?: error("Resident picker fixture directory is unavailable")
            page = value(browser.browse(WorkspaceBrowseRequest(handle, maxEntries = 256)))
        }
        return page.current
    }

    private fun value(result: WorkspaceResult<WorkspaceDirectoryPage>): WorkspaceDirectoryPage =
        (result as? WorkspaceResult.Success)?.value ?: error("Resident picker fixture browse failed")
}
