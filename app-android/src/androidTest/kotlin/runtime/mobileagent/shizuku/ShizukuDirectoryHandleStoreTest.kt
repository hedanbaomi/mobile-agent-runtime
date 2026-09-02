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
    fun attachedWorkspaceUsesRelativePathsAndRejectsSymlinkEscape() {
        val rootResponse = JSONObject(store.openRoot(16))
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
}
