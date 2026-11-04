// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.feature.agents.AgentWorkspaceUi
import runtime.mobileagent.integration.RootDirectoryBrowseUiEntry
import runtime.mobileagent.integration.RootDirectoryBrowseUiPage
import runtime.mobileagent.integration.RootWorkspacePermissionsUi
import runtime.mobileagent.integration.RootWorkspaceUiPort

/**
 * Regressions for the single Root dialog state owner.
 *
 * Every host read is held open by a [CompletableDeferred], so each test drives the interleaving it
 * claims: a late page or permission read is completed only after the dialog was closed, or after the
 * editor session/channel changed. JUnit 5 plus the coroutines core are enough; no test framework and
 * no device, dispatcher or Compose host is involved.
 */
class RootWorkspaceDialogStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var editorToken = 7L

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun newState(port: FakePort) = RootWorkspaceDialogState(scope, port, { editorToken })

    // --- real UI regressions -------------------------------------------------------------------

    /** A read that failed must never authorize Apply: the intent on screen is not the live one. */
    @Test
    fun failedPermissionReadCannotApplyAndWritesNothing() {
        val port = FakePort()
        val dialogs = newState(port)
        dialogs.editPermissions(Authority.ROOT_DIRECT, "ws", listOf(rootWorkspace("ws")), "agent", "read failed")
        val read = awaitRead(port)
        assertEquals("ws", read.workspaceId)
        assertEquals("agent", read.agentId)
        assertTrue(dialogs.permissions!!.loading)
        assertFalse(dialogs.permissions!!.canApply, "a still-loading intent must not be applied")

        read.result.complete(null)
        awaitUntil("the failed read is reflected") { dialogs.permissions?.loadFailed == true }
        assertFalse(dialogs.permissions!!.canApply)
        dialogs.apply(Authority.ROOT_DIRECT, "agent", "apply failed", "applied") {
            error("a failed read must never report a successful save")
        }
        awaitSettled()
        assertTrue(port.updates.isEmpty(), "a failed read must not write permissions")
        assertTrue(dialogs.permissionsOpen, "the dialog stays open with the reason")
        assertEquals("read failed", dialogs.permissions!!.error)
    }

    /** Switching the edited Agent invalidates a read that is still running for the old session. */
    @Test
    fun latePermissionReadAfterEditorSwitchCannotReviveTheDialog() {
        val port = FakePort()
        val dialogs = newState(port)
        dialogs.editPermissions(Authority.ROOT_DIRECT, "ws", listOf(rootWorkspace("ws")), "agent", "read failed")
        val read = awaitRead(port)
        editorToken = 8L
        dialogs.invalidate()
        read.result.complete(RootWorkspacePermissionsUi("ws", writable = true, metadata = true, shell = true))
        awaitSettled()
        assertNull(dialogs.permissions, "the previous session's read must not repopulate the dialog")
        assertFalse(dialogs.permissionsOpen)
    }

    /** A cancelled attach dialog drops the page it asked for instead of reviving it later. */
    @Test
    fun lateBrowsePageAfterCloseCannotOverwriteTheClosedDialog() {
        val port = FakePort()
        val dialogs = newState(port)
        dialogs.open(Authority.ROOT_DIRECT, "browse failed")
        val first = awaitBrowse(port)
        assertEquals("/", first.path)
        dialogs.closeBrowse()
        first.result.complete(page("/old", "app"))
        awaitSettled()
        assertTrue(dialogs.browse.entries.isEmpty())
        assertEquals("/", dialogs.browse.path, "the cancelled request's path stays, not the late page's")
        assertFalse(dialogs.browseOpen)
    }

    /**
     * Opening a different directory must clear the previous listing before the read starts, or a
     * failed read leaves the old rows clickable under the new path title.
     */
    @Test
    fun newPathFailureDoesNotKeepThePreviousListing() {
        val port = FakePort()
        val dialogs = newState(port)
        dialogs.open(Authority.ROOT_DIRECT, "browse failed")
        awaitBrowse(port).result.complete(page("/data", "app", cursor = "more"))
        awaitUntil("the first page is shown") { dialogs.browse.entries.size == 1 }
        assertEquals("more", dialogs.browse.nextCursor)

        dialogs.openPath(Authority.ROOT_DIRECT, "/sdcard", "browse failed")
        assertEquals("/sdcard", dialogs.browse.path)
        assertTrue(dialogs.browse.entries.isEmpty(), "the previous directory must not render under the new path")
        assertNull(dialogs.browse.nextCursor)

        awaitBrowse(port, 1).result.complete(null)
        awaitUntil("the failed read settles") { !dialogs.browse.loading }
        assertTrue(dialogs.browse.entries.isEmpty(), "a failed read must not resurrect the old listing")
        assertNull(dialogs.browse.nextCursor)
        assertEquals("browse failed", dialogs.browse.error)
    }

    /** Apply sends exactly the three checkbox values the dialog renders, for this channel and Agent. */
    @Test
    fun applySubmitsExactlyTheIntentTheDialogShows() {
        val port = FakePort()
        val dialogs = newState(port)
        dialogs.editPermissions(Authority.ROOT_DIRECT, "ws", listOf(rootWorkspace("ws")), "agent", "read failed")
        awaitRead(port).result.complete(RootWorkspacePermissionsUi("ws", writable = false, metadata = true, shell = false))
        awaitUntil("the live intent is shown") { dialogs.permissions?.canApply == true }

        var reported: String? = null
        dialogs.apply(Authority.ROOT_DIRECT, "agent", "apply failed", "applied") { reported = it }
        val update = awaitUpdate(port)
        assertEquals(Authority.ROOT_DIRECT, update.authority)
        assertEquals("ws", update.workspaceId)
        assertEquals("agent", update.agentId)
        assertFalse(update.writable, "the unchecked write box must be submitted as false")
        assertTrue(update.metadata, "the checked metadata box must be submitted as true")
        assertFalse(update.shell, "the unchecked shell box must be submitted as false")

        update.result.complete(true)
        awaitUntil("success closes the dialog") { dialogs.permissions == null }
        assertEquals("applied", reported)
    }

    /** A write answered after the dialog closed must not close or re-report anything. */
    @Test
    fun latePermissionWriteAfterCloseCannotReportSuccess() {
        val port = FakePort()
        val dialogs = newState(port)
        dialogs.editPermissions(Authority.ROOT_DIRECT, "ws", listOf(rootWorkspace("ws")), "agent", "read failed")
        awaitRead(port).result.complete(RootWorkspacePermissionsUi("ws", writable = true, metadata = false, shell = false))
        awaitUntil("the live intent is shown") { dialogs.permissions?.canApply == true }

        var reported: String? = null
        dialogs.apply(Authority.ROOT_DIRECT, "agent", "apply failed", "applied") { reported = it }
        val update = awaitUpdate(port)
        dialogs.closePermissions()
        update.result.complete(true)
        awaitSettled()
        assertNull(reported, "a write answered after close must not report success")
        assertFalse(dialogs.permissionsOpen)
        assertTrue(dialogs.permissions?.busy == true, "the closed dialog's state is not rewritten by the late write")
    }

    // --- fixtures ------------------------------------------------------------------------------

    private fun rootWorkspace(id: String, writable: Boolean = false) = AgentWorkspaceUi(
        id = id,
        displayName = "Root folder",
        backendType = WorkspaceBackendType.PRIVILEGED,
        readable = true,
        writable = writable,
        quotaBytes = null,
        maxFileBytes = 0L,
        enabled = true,
        revision = 1,
        isRoot = true,
    )

    private fun page(path: String, name: String, cursor: String? = null) = RootDirectoryBrowseUiPage(
        path = path,
        entries = listOf(RootDirectoryBrowseUiEntry(name = name, path = "$path/$name", directory = true)),
        nextCursor = cursor,
    )

    private fun awaitUntil(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000L) {
                while (!condition()) delay(5)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("timed out waiting until $what", timeout)
        }
    }

    /** Waits until every coroutine the state owner launched has finished. */
    private fun awaitSettled() = awaitUntil("in-flight Root dialog work settles") {
        scope.coroutineContext[Job]!!.children.none()
    }

    private fun awaitBrowse(port: FakePort, index: Int = 0): BrowseCall = runBlocking {
        withTimeout(5_000L) {
            while (port.browseCalls.size <= index) delay(5)
            port.browseCalls[index]
        }
    }

    private fun awaitRead(port: FakePort): PermissionRead = runBlocking {
        withTimeout(5_000L) {
            while (port.permissionReads.isEmpty()) delay(5)
            port.permissionReads.first()
        }
    }

    private fun awaitUpdate(port: FakePort): PermissionUpdate = runBlocking {
        withTimeout(5_000L) {
            while (port.updates.isEmpty()) delay(5)
            port.updates.first()
        }
    }

    private class BrowseCall(val path: String, val cursor: String?) {
        val result = CompletableDeferred<RootDirectoryBrowseUiPage?>()
    }

    private class PermissionRead(val workspaceId: String, val agentId: String) {
        val result = CompletableDeferred<RootWorkspacePermissionsUi?>()
    }

    private class PermissionUpdate(
        val authority: Authority,
        val workspaceId: String,
        val agentId: String,
        val writable: Boolean,
        val metadata: Boolean,
        val shell: Boolean,
    ) {
        val result = CompletableDeferred<Boolean>()
    }

    /**
     * Host port whose reads only answer when the test completes them. The non-suspend permission read
     * blocks its IO thread exactly like the real Binder-backed port does.
     */
    private class FakePort : RootWorkspaceUiPort {
        val browseCalls = CopyOnWriteArrayList<BrowseCall>()
        val permissionReads = CopyOnWriteArrayList<PermissionRead>()
        val updates = CopyOnWriteArrayList<PermissionUpdate>()

        override suspend fun browseRootDirectory(
            authority: Authority,
            path: String,
            cursor: String?,
        ): RootDirectoryBrowseUiPage? = BrowseCall(path, cursor).also { browseCalls += it }.result.await()

        override fun rootWorkspacePermissions(workspaceId: String, agentId: String): RootWorkspacePermissionsUi? {
            val read = PermissionRead(workspaceId, agentId)
            permissionReads += read
            return runBlocking { read.result.await() }
        }

        override suspend fun updateRootWorkspacePermissions(
            authority: Authority,
            workspaceId: String,
            agentId: String,
            writable: Boolean,
            metadata: Boolean,
            shell: Boolean,
        ): Boolean = PermissionUpdate(authority, workspaceId, agentId, writable, metadata, shell)
            .also { updates += it }
            .result.await()
    }
}
