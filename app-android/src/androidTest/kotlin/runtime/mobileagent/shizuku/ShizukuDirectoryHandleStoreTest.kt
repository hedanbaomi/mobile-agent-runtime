// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.io.File
import java.nio.file.Files
import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
