// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.WorkspaceBackendType

class WorkspaceUiPresentationTest {
    @Test
    fun appPrivateTitleIsFixedAndNeverAPath() {
        val label = persistedWorkspaceFolderLabel(
            backendType = WorkspaceBackendType.INTERNAL,
            requestedName = "/data/user/0/secret",
            ordinal = 1,
            fullDevice = false,
        )
        assertEquals(WorkspaceUiPresentation.APP_PRIVATE_TITLE_ZH, label)
        assertFalse(label.startsWith("/"))
    }

    @Test
    fun safTitleRejectsContentUri() {
        assertTrue(WorkspaceUiPresentation.containsSensitive("content://com.android.externalstorage.documents/tree/primary%3ADownload"))
        val label = persistedWorkspaceFolderLabel(
            backendType = WorkspaceBackendType.SAF_TREE,
            requestedName = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            ordinal = 2,
            fullDevice = false,
        )
        assertFalse(label.contains("content://"))
        assertEquals("Download", label)
    }

    @Test
    fun privilegedUiTitleAllowsDevicePathAndRejectsUri() {
        assertEquals(
            "/storage/emulated/0/Download",
            privilegedUiTitle("/storage/emulated/0/Download"),
        )
        assertNull(privilegedUiTitle("content://tree/primary"))
        val fromTrail = privilegedUiTitle("", listOf("根目录", "storage", "emulated", "0", "Download"))
        assertEquals("/storage/emulated/0/Download", fromTrail)
    }

    @Test
    fun presentationRejectsLocatorMarkers() {
        val invalid = runCatching {
            WorkspaceUiPresentation(
                workspaceId = "ws-1",
                kind = WorkspaceUiKind.SAF,
                title = "recovery_locator=abc",
            )
        }
        assertTrue(invalid.isFailure)
    }

    @Test
    fun fallbackPresentationDoesNotUseUriAndDoesNotNeedStore() {
        val saf = runtime.mobileagent.domain.Workspace(
            id = "ws-saf-fallback",
            displayName = "content://tree/primary",
            backendType = WorkspaceBackendType.SAF_TREE,
            rootReference = "opaque",
            readable = true,
            writable = true,
            quotaBytes = 1,
            maxFileBytes = 1,
            enabled = true,
        )
        val presentation = fallbackWorkspaceUiPresentation(saf, null, chinese = true)
        assertEquals(WorkspaceUiKind.SAF, presentation?.kind)
        assertFalse(presentation!!.title.contains("content://"))
        val privileged = runtime.mobileagent.domain.Workspace(
            id = "ws-priv-fallback",
            displayName = "/storage/emulated/0/Download",
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "authority:SHIZUKU",
            readable = true,
            writable = true,
            quotaBytes = 1,
            maxFileBytes = 1,
            enabled = true,
        )
        assertEquals(
            "/storage/emulated/0/Download",
            fallbackWorkspaceUiPresentation(
                privileged,
                runtime.mobileagent.domain.Authority.SHIZUKU,
                chinese = true,
            )?.title,
        )
    }
}
