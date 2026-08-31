// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShizukuWorkspaceFileStoreTest {
    private lateinit var root: File
    private lateinit var store: ShizukuWorkspaceFileStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "shizuku-store-${UUID.randomUUID()}").apply { check(mkdirs()) }
        check(File(root, "Download").mkdirs())
        store = ShizukuWorkspaceFileStore(File(root, "Download/MobileAgentRuntime-Shizuku"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun createsReadsReplacesAndDeletesOnlyRelativeUtf8Files() {
        assertTrue(store.mkdir("notes").contains("\"ok\":true"))
        assertTrue(
            store.write("notes/today.txt", "第一版".toByteArray(StandardCharsets.UTF_8), replaceExisting = false)
                .contains("\"created\":true"),
        )
        assertTrue(store.read("notes/today.txt", 1024).contains("第一版"))
        assertTrue(
            store.write("notes/today.txt", "第二版".toByteArray(StandardCharsets.UTF_8), replaceExisting = true)
                .contains("\"created\":false"),
        )
        assertTrue(store.read("notes/today.txt", 1024).contains("第二版"))
        assertTrue(store.delete("notes/today.txt").contains("\"deleted\":true"))
    }

    @Test
    fun typedStatAndAtomicMoveReportTheDestinationAndRejectReplayLikeTargets() {
        assertTrue(store.mkdir("source").contains("\"ok\":true"))
        assertTrue(store.write("source/item.txt", "payload".toByteArray(StandardCharsets.UTF_8), false).contains("\"ok\":true"))

        val stat = store.stat("source/item.txt")
        assertTrue(stat.contains("\"operation\":\"stat\""))
        assertTrue(stat.contains("\"type\":\"file\""))
        assertTrue(stat.contains("\"bytes\":7"))

        assertTrue(store.mkdir("moved").contains("\"ok\":true"))
        val moved = store.move("source/item.txt", "moved/item.txt", false)
        val movedJson = JSONObject(moved)
        assertTrue(movedJson.getBoolean("moved"))
        assertEquals("source/item.txt", movedJson.getString("sourcePath"))
        assertEquals("moved/item.txt", movedJson.getString("destinationPath"))
        assertTrue(store.stat("source/item.txt").contains("\"code\":\"NOT_FOUND\""))
        assertTrue(store.stat("moved/item.txt").contains("\"type\":\"file\""))

        assertTrue(store.write("existing.txt", "existing".toByteArray(StandardCharsets.UTF_8), false).contains("\"ok\":true"))
        assertTrue(store.move("moved/item.txt", "existing.txt", false).contains("\"code\":\"TARGET_EXISTS\""))
        assertTrue(store.move("moved/item.txt", "existing.txt", true).contains("\"moved\":true"))
        assertTrue(store.read("existing.txt", 1024).contains("payload"))
        assertTrue(store.mkdir("directory").contains("\"ok\":true"))
        assertTrue(store.move("directory", "directory/child", false).contains("\"code\":\"MOVE_INTO_SELF\""))
    }

    @Test
    fun listReadAndStatRejectOversizedExistingMetadataConsistently() {
        assertTrue(store.mkdir("oversize").contains("\"ok\":true"))
        val oversized = File(root, "Download/MobileAgentRuntime-Shizuku/oversize/large.bin")
        oversized.writeBytes(ByteArray(ShizukuWorkspaceFileStore.MAX_FILE_BYTES + 1))

        assertTrue(store.list("oversize").contains("\"code\":\"FILE_TOO_LARGE\""))
        assertTrue(store.read("oversize/large.bin", ShizukuWorkspaceFileStore.MAX_READ_BYTES).contains("\"code\":\"FILE_TOO_LARGE\""))
        assertTrue(store.stat("oversize/large.bin").contains("\"code\":\"FILE_TOO_LARGE\""))
    }

    @Test
    fun rejectsInvalidPathsBeforeLeavingTheFixedRoot() {
        val outside = File(root.parentFile, "escape-${UUID.randomUUID()}.txt")
        listOf("../escape.txt", "/escape.txt", "C:/escape.txt", "a\\escape.txt", "a\u0000b").forEach { path ->
            val result = store.write(path, "x".toByteArray(StandardCharsets.UTF_8), replaceExisting = false)
            assertTrue("Expected invalid path result for $path", result.contains("\"code\":\"INVALID_PATH\""))
        }
        assertFalse(outside.exists())
    }

    @Test
    fun rejectsMalformedUtf8OversizedTextSymlinkAndNonEmptyDirectoryDeletion() {
        assertTrue(
            store.write("bad.txt", byteArrayOf(0xC3.toByte(), 0x28), replaceExisting = false)
                .contains("\"code\":\"INVALID_CONTENT\""),
        )
        assertTrue(
            store.write("large.txt", ByteArray(ShizukuWorkspaceFileStore.MAX_FILE_BYTES + 1), replaceExisting = false)
                .contains("\"code\":\"LIMIT\""),
        )
        assertTrue(store.mkdir("nonempty").contains("\"ok\":true"))
        assertTrue(
            store.write("nonempty/child.txt", "keep".toByteArray(StandardCharsets.UTF_8), replaceExisting = false)
                .contains("\"ok\":true"),
        )
        assertTrue(store.delete("nonempty").contains("\"code\":\"NON_EMPTY_DIRECTORY\""))

        val outside = File(root.parentFile, "outside-${UUID.randomUUID()}.txt").apply { writeText("outside") }
        val link = File(root, "Download/MobileAgentRuntime-Shizuku/link")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)
        assumeTrue("The test filesystem does not support symlinks", created)
        try {
            val result = store.read("link", 1024)
            assertTrue(result.contains("\"code\":\"SYMLINK_REJECTED\""))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun atomicReplacementLeavesNoTemporaryFilesAndOutputsNoAbsolutePath() {
        assertTrue(store.write("one.txt", "one".toByteArray(StandardCharsets.UTF_8), false).contains("\"ok\":true"))
        assertTrue(store.write("one.txt", "two".toByteArray(StandardCharsets.UTF_8), true).contains("\"ok\":true"))
        assertTrue(root.walkTopDown().none { it.name.startsWith(".mar-shizuku-") })
        assertFalse(store.read("missing.txt", 100).contains(root.absolutePath))
    }
}
