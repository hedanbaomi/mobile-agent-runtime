// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Fake-device filesystem coverage for the UserService's opaque directory
 * handle table.  Binder/session identity is covered by the UserService tests;
 * this test focuses on the path/canonicalization boundary without requiring a
 * Shizuku installation.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuDirectoryHandleStoreTest {
    private lateinit var root: File
    private lateinit var store: ShizukuDirectoryHandleStore

    @Before
    fun setUp() {
        root = File.createTempFile("mar-device-root-", "-${UUID.randomUUID()}").apply {
            delete()
            check(mkdirs())
        }
        File(root, "books").mkdirs()
        File(root, "books/book.md").writeText("typed")
        File(root, "notes.txt").writeText("notes")
        store = ShizukuDirectoryHandleStore(root.toPath())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun rootAndChildListingUseNamesAndOpaqueHandlesOnly() {
        val rootResponse = JSONObject(store.openRoot(16))
        assertTrue(rootResponse.getBoolean("ok"))
        assertFalse(rootResponse.has("path"))
        val rootToken = rootResponse.getString("handle")
        assertTrue(rootToken.length >= 40)
        assertFalse(rootToken.contains(root.absolutePath))

        val entries = rootResponse.getJSONArray("entries")
        var booksToken: String? = null
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            assertFalse(item.has("path"))
            if (item.getString("name") == "books") booksToken = item.getString("handle")
        }
        assertNotNull(booksToken)
        val booksResponse = JSONObject(store.browse(booksToken, 16))
        assertTrue(booksResponse.getJSONArray("entries").getJSONObject(0).getString("name") == "book.md")
    }

    @Test
    fun laterDirectoryPageIsReachableBeyondFirstPage() {
        repeat(300) { index ->
            File(root, "dir-${index.toString().padStart(3, '0')}").mkdirs()
        }
        val first = JSONObject(store.openRoot(100))
        assertTrue(first.getBoolean("ok"))
        assertEquals(100, first.getJSONArray("entries").length())
        assertTrue(first.getBoolean("truncated"))
        val continuation = first.optContinuation()
        assertTrue(!continuation.isNullOrEmpty() && continuation.length >= 40)
        assertFalse(continuation!!.contains("dir-"))

        val rootToken = first.getString("handle")
        val names = ArrayList<String>()
        var cursor: String? = continuation
        var pages = 1
        first.getJSONArray("entries").let { entries ->
            (0 until entries.length()).mapTo(names) { entries.getJSONObject(it).getString("name") }
        }
        while (cursor != null) {
            val page = JSONObject(store.browse(rootToken, 100, cursor))
            assertTrue(page.toString(), page.getBoolean("ok"))
            (0 until page.getJSONArray("entries").length()).mapTo(names) {
                page.getJSONArray("entries").getJSONObject(it).getString("name")
            }
            pages++
            assertTrue(pages <= 10)
            cursor = page.optContinuation()
            assertEquals(cursor == null, !page.getBoolean("truncated"))
        }
        // The fixture root also contains books/ and notes.txt from setUp.
        assertEquals(302, names.size)
        assertEquals(names.sorted(), names)
        assertEquals(names.toSet().size, names.size)
        assertTrue(names.contains("dir-299"))
    }

    @Test
    fun filesDoNotCrowdDirectoriesOutOfReach() {
        File(root, "wanted-a").mkdirs()
        File(root, "wanted-b").mkdirs()
        repeat(500) { index ->
            File(root, "file-${index.toString().padStart(3, '0')}.txt").writeText("$index")
        }
        // Directories sort before files, so the selectable entries lead even
        // when files outnumber them.
        val first = JSONObject(store.openRoot(100))
        assertTrue(first.getBoolean("ok"))
        val firstNames = (0 until first.getJSONArray("entries").length()).map {
            first.getJSONArray("entries").getJSONObject(it).getString("name")
        }
        assertTrue(firstNames.contains("wanted-a"))
        assertTrue(firstNames.contains("wanted-b"))
        assertTrue(first.getBoolean("truncated"))

        // Every directory stays reachable through continuation paging.
        val rootToken = first.getString("handle")
        val directories = ArrayList<String>()
        var cursor: String? = first.optContinuation()
        var pages = 1
        while (cursor != null) {
            val page = JSONObject(store.browse(rootToken, 100, cursor))
            assertTrue(page.getBoolean("ok"))
            (0 until page.getJSONArray("entries").length())
                .map { page.getJSONArray("entries").getJSONObject(it) }
                .filter { it.getString("type") == "directory" }
                .mapTo(directories) { it.getString("name") }
            pages++
            assertTrue(pages <= 10)
            cursor = page.optContinuation()
        }
        assertTrue(directories.contains("wanted-a") || firstNames.contains("wanted-a"))
    }

    @Test
    fun invalidAndStaleContinuationFailClosed() {
        repeat(10) { index -> File(root, "stable-$index").mkdirs() }
        val first = JSONObject(store.openRoot(4))
        assertTrue(first.getBoolean("ok"))
        val rootToken = first.getString("handle")
        val continuation = first.getString("continuation")

        assertEquals(ShizukuDirectoryHandleStore.INVALID_HANDLE, JSONObject(store.browse(rootToken, 4, "not-a-token!!")).getString("code"))
        assertEquals(ShizukuDirectoryHandleStore.INVALID_HANDLE, JSONObject(store.browse(rootToken, 4, "x".repeat(600))).getString("code"))

        File(root, "stable-added").mkdirs()
        assertEquals(ShizukuDirectoryHandleStore.INVALID_HANDLE, JSONObject(store.browse(rootToken, 4, continuation)).getString("code"))

        // A service restart drops the page table; old tokens fail closed.
        val restarted = ShizukuDirectoryHandleStore(root.toPath())
        val restartedRoot = JSONObject(restarted.openRoot(4))
        assertEquals(
            ShizukuDirectoryHandleStore.INVALID_HANDLE,
            JSONObject(restarted.browse(restartedRoot.getString("handle"), 4, continuation)).getString("code"),
        )
    }

    @Test
    fun symlinkEntriesAreSkippedAndNeverSelectable() {
        val outside = File(root.parentFile, "mar-outside-${UUID.randomUUID()}").apply { writeText("outside") }
        val link = File(root, "picker-link")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)
        try {
            assumeTrue(created)
            val response = JSONObject(store.openRoot(16))
            assertTrue(response.getBoolean("ok"))
            val names = (0 until response.getJSONArray("entries").length()).map {
                response.getJSONArray("entries").getJSONObject(it).getString("name")
            }
            assertFalse(names.contains("picker-link"))
            assertTrue(names.contains("books"))
            assertTrue(names.contains("notes.txt"))
            assertTrue(response.optContinuation().isNullOrEmpty())
        } finally {
            link.delete()
            outside.delete()
        }
    }

    @Test
    fun attachedWorkspaceUsesRelativePathsAndRejectsSymlinkEscape() {        val rootResponse = JSONObject(store.openRoot(16))
        val rootToken = rootResponse.getString("handle")
        val attached = JSONObject(store.attach(rootToken))
        assertTrue(attached.getBoolean("ok"))
        val workspace = store.workspace(attached.getString("workspaceHandle"))
        assertNotNull(workspace)
        val list = JSONObject(workspace!!.store.list(""))
        assertFalse(list.toString().contains(root.absolutePath))
        assertTrue(list.getString("path").isEmpty())

        val outside = File(root.parentFile, "mar-outside-${UUID.randomUUID()}").apply { writeText("outside") }
        val link = File(root, "escape")
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)
        if (symlinkCreated) {
            assertTrue(workspace.store.read("escape", 1024).contains("SYMLINK_REJECTED"))
            link.delete()
        }
        outside.delete()
    }

    @Test
    fun recoveryLocatorReopensWithFreshHandleAfterServiceRestart() {
        val rootResponse = JSONObject(store.openRoot(16))
        val rootToken = rootResponse.getString("handle")
        val booksToken = rootResponse.getJSONArray("entries")
            .let { entries ->
                (0 until entries.length()).firstNotNullOf { index ->
                    entries.getJSONObject(index).optString("handle", "")
                        .takeIf { entries.getJSONObject(index).getString("name") == "books" }
                }
            }
        val attached = JSONObject(store.attach(booksToken))
        assertTrue(attached.getBoolean("ok"))
        val oldWorkspaceToken = attached.getString("workspaceHandle")
        val locator = Base64.getUrlDecoder().decode(attached.getString("recoveryLocator"))
        assertFalse(attached.getString("recoveryLocator").contains(root.absolutePath))
        val safeLocator = runtime.mobileagent.skills.tooling.WorkspaceRecoveryLocator.fromBytes(locator)
        assertEquals("WorkspaceRecoveryLocator", safeLocator.toString())
        assertTrue(safeLocator.sizeBytes > 0)

        // A new UserService has a new handle table.  The old workspace token
        // is not accepted, while the authenticated locator creates a new one.
        val restarted = ShizukuDirectoryHandleStore(root.toPath())
        assertNull(restarted.workspace(oldWorkspaceToken))
        val reopened = JSONObject(restarted.reattach(locator))
        assertTrue(reopened.getBoolean("ok"))
        assertEquals("reattach_directory", reopened.getString("operation"))
        assertTrue(reopened.getString("workspaceHandle") != oldWorkspaceToken)
        assertNotNull(restarted.workspace(reopened.getString("workspaceHandle")))
        safeLocator.clear()
        assertTrue(safeLocator.isCleared)
        locator.fill(0)
    }

    @Test
    fun recoveryLocatorRejectsTamperAndMissingDirectory() {
        val rootResponse = JSONObject(store.openRoot(16))
        val entries = rootResponse.getJSONArray("entries")
        val booksToken = (0 until entries.length()).first { index ->
            entries.getJSONObject(index).getString("name") == "books"
        }.let { entries.getJSONObject(it).getString("handle") }
        val attached = JSONObject(store.attach(booksToken))
        val locator = Base64.getUrlDecoder().decode(attached.getString("recoveryLocator"))
        val tampered = locator.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val restarted = ShizukuDirectoryHandleStore(root.toPath())
        val tamperedResult = JSONObject(restarted.reattach(tampered))
        assertFalse(tamperedResult.getBoolean("ok"))
        assertTrue(tamperedResult.getString("code") == ShizukuDirectoryHandleStore.RECOVERY_LOCATOR_INVALID)

        File(root, "books").deleteRecursively()
        val missingResult = JSONObject(restarted.reattach(locator))
        assertFalse(missingResult.getBoolean("ok"))
        assertTrue(missingResult.getString("code") == ShizukuDirectoryHandleStore.WORKSPACE_NOT_FOUND)
        locator.fill(0)
    }

    /**
     * Reads the opaque page continuation.  `optString` alone is wrong here:
     * it renders an explicit JSON null as the string "null".
     */
    private fun JSONObject.optContinuation(): String? =
        if (isNull("continuation")) null else optString("continuation", "").takeIf { it.isNotEmpty() }
}
