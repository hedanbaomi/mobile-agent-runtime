// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.Context
import android.system.Os
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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
        check(File(root, "Download/MobileAgentRuntime-Shizuku").mkdirs())
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
    fun createOnlyWriteNeverOverwritesExternallyCreatedTarget() {
        assertTrue(store.mkdir("race").contains("\"ok\":true"))
        assertTrue(
            store.write("race/file.txt", "first".toByteArray(StandardCharsets.UTF_8), replaceExisting = false)
                .contains("\"created\":true"),
        )
        // Second create-only write fails without touching the original.
        assertTrue(
            store.write("race/file.txt", "second".toByteArray(StandardCharsets.UTF_8), replaceExisting = false)
                .contains("\"code\":\"TARGET_EXISTS\""),
        )
        assertTrue(store.read("race/file.txt", 1024).contains("first"))
        // External creation between the caller's check and commit is the same
        // violation: simulate it by creating the target out of band, then
        // attempting a create-only write over it.
        Files.write(
            root.toPath().resolve("Download/MobileAgentRuntime-Shizuku/race/external.txt"),
            "external".toByteArray(StandardCharsets.UTF_8),
        )
        assertTrue(
            store.write("race/external.txt", "candidate".toByteArray(StandardCharsets.UTF_8), replaceExisting = false)
                .contains("\"code\":\"TARGET_EXISTS\""),
        )
        assertTrue(store.read("race/external.txt", 1024).contains("external"))
    }

    @Test
    fun noReplaceMoveRaceNeverOverwritesExternallyCreatedTarget() {
        assertTrue(store.mkdir("race").contains("\"ok\":true"))
        assertTrue(
            store.write("race/src.txt", "agent".toByteArray(StandardCharsets.UTF_8), replaceExisting = false)
                .contains("\"ok\":true"),
        )
        // External destination created after the caller's pre-check: the
        // no-clobber commit must fail instead of overwriting (b07 finding C2).
        Files.write(
            root.toPath().resolve("Download/MobileAgentRuntime-Shizuku/race/dst.txt"),
            "external-owner-data".toByteArray(StandardCharsets.UTF_8),
        )
        assertTrue(store.move("race/src.txt", "race/dst.txt", false).contains("\"code\":\"TARGET_EXISTS\""))
        assertTrue(store.read("race/src.txt", 1024).contains("agent"))
        assertTrue(store.read("race/dst.txt", 1024).contains("external-owner-data"))
    }

    @Test
    fun noReplaceDirectoryMoveIsUnsupportedRatherThanOverwriting() {
        assertTrue(store.mkdir("dir").contains("\"ok\":true"))
        assertTrue(
            store.write("dir/note.txt", "x".toByteArray(StandardCharsets.UTF_8), replaceExisting = false)
                .contains("\"ok\":true"),
        )
        // No portable primitive proves a no-replace directory move; the store
        // fails closed instead of silently merging (b07 finding C2).
        assertTrue(store.move("dir", "dir2", false).contains("\"code\":\"ATOMIC_REPLACE_UNAVAILABLE\""))
        assertTrue(store.stat("dir/note.txt").contains("\"type\":\"file\""))
        assertTrue(store.stat("dir2").contains("\"code\":\"NOT_FOUND\""))
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
    fun missingDirectoryAndDirectoryReadReturnDistinctTypedErrors() {
        assertTrue(store.list("missing").contains("\"code\":\"NOT_FOUND\""))
        assertTrue(store.mkdir("directory").contains("\"ok\":true"))
        val read = store.readChunk("directory", 1024, 0L)
        assertEquals(
            ShizukuWorkspaceFileStore.UNSUPPORTED_ENTRY,
            (read as ShizukuWorkspaceFileStore.ReadChunkResult.Failure).code,
        )
    }

    @Test
    fun listAndStatExposeOversizedMetadataButReadRemainsBounded() {
        assertTrue(store.mkdir("oversize").contains("\"ok\":true"))
        val oversized = File(root, "Download/MobileAgentRuntime-Shizuku/oversize/large.bin")
        Files.newByteChannel(
            oversized.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.position(ShizukuWorkspaceFileStore.MAX_FILE_BYTES.toLong())
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        }

        val listing = JSONObject(store.list("oversize"))
        assertTrue(listing.getBoolean("ok"))
        assertEquals(1, listing.getJSONArray("entries").length())
        assertEquals(
            ShizukuWorkspaceFileStore.MAX_FILE_BYTES.toLong() + 1L,
            listing.getJSONArray("entries").getJSONObject(0).getLong("bytes"),
        )
        assertTrue(store.read("oversize/large.bin", ShizukuWorkspaceFileStore.MAX_READ_BYTES).contains("\"code\":\"FILE_TOO_LARGE\""))
        val stat = JSONObject(store.stat("oversize/large.bin"))
        assertTrue(stat.getBoolean("ok"))
        assertEquals(ShizukuWorkspaceFileStore.MAX_FILE_BYTES.toLong() + 1L, stat.getLong("bytes"))
    }

    @Test
    fun listingSkipsExternalSymlinkAndKeepsNormalEntries() {
        val normal = File(root, "Download/MobileAgentRuntime-Shizuku/normal.txt").apply { writeText("normal") }
        val outside = File(root.parentFile, "outside-${UUID.randomUUID()}.txt").apply { writeText("outside") }
        val link = File(root, "Download/MobileAgentRuntime-Shizuku/outside-link.txt")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)
        assumeTrue("The test filesystem does not support symlinks", created)
        try {
            val listing = JSONObject(store.list(null))
            assertTrue(listing.getBoolean("ok"))
            val paths = (0 until listing.getJSONArray("entries").length()).map {
                listing.getJSONArray("entries").getJSONObject(it).getString("path")
            }
            assertTrue(paths.contains("normal.txt"))
            assertFalse(paths.contains("outside-link.txt"))
            assertTrue(paths.none { it.contains(outside.absolutePath) })
            assertEquals(1, listing.getInt("skippedEntries"))
            assertEquals("SYMLINK_SKIPPED", listing.getJSONArray("warnings").getJSONObject(0).getString("code"))
            assertEquals(1, listing.getJSONArray("warnings").getJSONObject(0).getInt("count"))
        } finally {
            link.delete()
            outside.delete()
            assertTrue(normal.exists())
        }
    }

    @Test
    fun listingSkipsUnsupportedEntryAndKeepsNormalEntries() {
        val directory = File(root, "Download/MobileAgentRuntime-Shizuku")
        val normal = File(directory, "normal-with-fifo.txt").apply { writeText("normal") }
        val fifo = File(directory, "unsupported.fifo")
        val created = runCatching {
            Os.mkfifo(fifo.absolutePath, 0x1A4)
            true
        }.getOrDefault(false)
        assumeTrue("The test filesystem does not support FIFO entries", created)
        try {
            val listing = JSONObject(store.list(null))
            assertTrue(listing.getBoolean("ok"))
            val paths = (0 until listing.getJSONArray("entries").length()).map {
                listing.getJSONArray("entries").getJSONObject(it).getString("path")
            }
            assertTrue(paths.contains(normal.name))
            assertFalse(paths.contains(fifo.name))
            assertEquals(1, listing.getInt("skippedEntries"))
            assertEquals(
                "UNSUPPORTED_ENTRY_SKIPPED",
                listing.getJSONArray("warnings").getJSONObject(0).getString("code"),
            )
        } finally {
            fifo.delete()
        }
    }

    @Test
    fun listingFiltersUnsafeEntriesBeforePagingAndReportsSkip() {
        repeat(ShizukuWorkspaceFileStore.MAX_ENTRIES) { index ->
            File(root, "Download/MobileAgentRuntime-Shizuku/entry-${index.toString().padStart(3, '0')}.txt")
                .writeText("$index")
        }
        val outside = File(root.parentFile, "outside-${UUID.randomUUID()}.txt").apply { writeText("outside") }
        val link = File(root, "Download/MobileAgentRuntime-Shizuku/unsafe-link.txt")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)
        assumeTrue("The test filesystem does not support symlinks", created)
        try {
            // The store caps each page at MAX_DIRECTORY_ENTRIES so that the JSON envelope remains
            // bounded.  A continuation proves that the raw 513 children (512 safe + 1 link) page
            // through after unsafe filtering instead of failing up front.
            val listing = JSONObject(store.list(null, maxEntries = ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES))
            assertTrue(listing.getBoolean("ok"))
            assertEquals(ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES, listing.getJSONArray("entries").length())
            assertEquals(1, listing.getInt("skippedEntries"))
            assertTrue(listing.getJSONArray("warnings").toString().contains("SYMLINK_SKIPPED"))
            assertTrue(listing.getBoolean("truncated"))
            val continuation = listing.getString("nextCursor")
            val second = JSONObject(
                store.list(
                    null,
                    maxEntries = ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES,
                    cursor = continuation,
                ),
            )
            assertTrue(second.getBoolean("ok"))
            assertEquals(ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES, second.getJSONArray("entries").length())
            assertFalse(second.getBoolean("truncated"))
        } finally {
            link.delete()
            outside.delete()
        }
    }

    @Test
    fun listPagesThroughOneThousandImmediateEntries() {
        repeat(1000) { index ->
            File(root, "Download/MobileAgentRuntime-Shizuku/big-${index.toString().padStart(4, '0')}.txt")
                .writeText("$index")
        }
        val paths = readAllPages(null, 256)
        assertEquals(1000, paths.size)
        assertEquals(paths.sorted(), paths)
        assertEquals(paths.toSet().size, paths.size)
    }

    @Test
    fun listPagesThroughFiveThousandImmediateEntries() {
        repeat(5000) { index ->
            File(root, "Download/MobileAgentRuntime-Shizuku/huge-${index.toString().padStart(4, '0')}.txt")
                .writeText("$index")
        }
        val paths = readAllPages(null, 256)
        assertEquals(5000, paths.size)
        assertEquals(paths.sorted(), paths)
        assertEquals(paths.toSet().size, paths.size)
    }

    @Test
    fun parentListIgnoresHugeChildSubtree() {
        val base = File(root, "Download/MobileAgentRuntime-Shizuku")
        File(base, "keep-a.txt").writeText("a")
        File(base, "keep-b.txt").writeText("b")
        val child = File(base, "crowded").apply { check(mkdirs()) }
        // Beyond the historical 512 total-entry ceiling: the parent listing
        // must never descend into the child to discover this.
        repeat(700) { index ->
            File(child, "item-${index.toString().padStart(3, '0')}.txt").writeText("$index")
        }
        val parent = JSONObject(store.list(null))
        assertTrue(parent.toString(), parent.getBoolean("ok"))
        assertEquals(
            listOf("crowded", "keep-a.txt", "keep-b.txt"),
            (0 until parent.getJSONArray("entries").length()).map {
                parent.getJSONArray("entries").getJSONObject(it).getString("path")
            },
        )
        assertFalse(parent.getBoolean("truncated"))
        val crowded = readAllPages("crowded", 256)
        assertEquals(700, crowded.size)
        assertEquals(crowded.toSet().size, crowded.size)
    }

    @Test
    fun parentListIgnoresDeepChildBeyondMaxDepth() {
        val base = File(root, "Download/MobileAgentRuntime-Shizuku")
        File(base, "top.txt").writeText("top")
        var current = File(base, "level-00").apply { check(mkdirs()) }
        repeat(20) { depth ->
            current = File(current, "level-${(depth + 1).toString().padStart(2, '0')}").apply { check(mkdirs()) }
        }
        File(current, "bottom.txt").writeText("bottom")
        // The 21-deep chain exceeds MAX_PATH_DEPTH, but the parent listing is
        // shallow and must not descend into it.
        val parent = JSONObject(store.list(null))
        assertTrue(parent.toString(), parent.getBoolean("ok"))
        val paths = (0 until parent.getJSONArray("entries").length()).map {
            parent.getJSONArray("entries").getJSONObject(it).getString("path")
        }
        assertTrue(paths.contains("top.txt"))
        assertTrue(paths.contains("level-00"))
    }

    @Test
    fun directChildMutationInvalidatesCursor() {
        val base = File(root, "Download/MobileAgentRuntime-Shizuku")
        repeat(5) { index -> File(base, "mutable-$index.txt").writeText("$index") }

        var first = JSONObject(store.list(null, maxEntries = 2))
        assertTrue(first.getBoolean("ok"))
        File(base, "mutable-added.txt").writeText("added")
        assertTrue(
            store.list(null, maxEntries = 2, cursor = first.getString("nextCursor"))
                .contains("\"code\":\"INVALID_CURSOR\""),
        )

        first = JSONObject(store.list(null, maxEntries = 2))
        assertTrue(first.getBoolean("ok"))
        assertTrue(File(base, "mutable-0.txt").delete())
        assertTrue(
            store.list(null, maxEntries = 2, cursor = first.getString("nextCursor"))
                .contains("\"code\":\"INVALID_CURSOR\""),
        )

        first = JSONObject(store.list(null, maxEntries = 2))
        assertTrue(first.getBoolean("ok"))
        assertTrue(File(base, "mutable-1.txt").renameTo(File(base, "mutable-renamed.txt")))
        assertTrue(
            store.list(null, maxEntries = 2, cursor = first.getString("nextCursor"))
                .contains("\"code\":\"INVALID_CURSOR\""),
        )
    }

    @Test
    fun longFileNameKeepsBoundedResponse() {
        val name = "n".repeat(200) + ".txt"
        File(root, "Download/MobileAgentRuntime-Shizuku/$name").writeText("long")
        val raw = store.list(null)
        val listing = JSONObject(raw)
        assertTrue(raw, listing.getBoolean("ok"))
        val paths = (0 until listing.getJSONArray("entries").length()).map {
            listing.getJSONArray("entries").getJSONObject(it).getString("path")
        }
        assertTrue(paths.contains(name))
        assertTrue(
            "list response must stay within the Binder-safe envelope",
            raw.toByteArray(StandardCharsets.UTF_8).size <= ShizukuWorkspaceFileStore.MAX_OUTPUT_BYTES,
        )
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

    @Test
    fun listingUsesOpaqueContinuationForMoreThanOnePage() {
        repeat(300) { index ->
            File(root, "Download/MobileAgentRuntime-Shizuku/item-${index.toString().padStart(3, '0')}.txt")
                .writeText("$index")
        }
        val first = JSONObject(store.list(null, maxEntries = 128))
        assertTrue(first.toString(), first.getBoolean("ok"))
        assertEquals(128, first.getJSONArray("entries").length())
        assertTrue(first.getBoolean("truncated"))
        val cursor = first.getString("nextCursor")
        assertTrue(cursor.length >= 40)
        assertFalse(cursor.contains("item-"))

        assertTrue(store.list(null, maxEntries = 128, cursor = "$cursor-x").contains("\"code\":\"INVALID_CURSOR\""))

        val second = JSONObject(store.list(null, maxEntries = 128, cursor = cursor))
        assertTrue(second.getBoolean("ok"))
        assertEquals(128, second.getJSONArray("entries").length())
        val third = JSONObject(store.list(null, maxEntries = 128, cursor = second.getString("nextCursor")))
        assertTrue(third.getBoolean("ok"))
        assertEquals(44, third.getJSONArray("entries").length())
        assertFalse(third.getBoolean("truncated"))

        val stale = JSONObject(store.list(null, maxEntries = 128))
        File(root, "Download/MobileAgentRuntime-Shizuku/item-999.txt").writeText("changed")
        assertTrue(store.list(null, maxEntries = 128, cursor = stale.getString("nextCursor"))
            .contains("\"code\":\"INVALID_CURSOR\""))
    }

    @Test
    fun statAndChunkReadSupportFilesLargerThanOneChunk() {
        val file = File(root, "Download/MobileAgentRuntime-Shizuku/large.txt")
        file.writeText("a".repeat(300 * 1024), StandardCharsets.UTF_8)
        val stat = JSONObject(store.stat("large.txt"))
        assertTrue(stat.getBoolean("ok"))
        assertEquals(300L * 1024L, stat.getLong("bytes"))
        assertTrue(stat.getString("version").length == 64)

        val first = store.readChunk("large.txt", 24 * 1024, 0L) as ShizukuWorkspaceFileStore.ReadChunkResult.Success
        assertEquals(24 * 1024, first.bytes.size)
        assertEquals(300L * 1024L, first.totalBytes)
        assertFalse(first.eof)
        val second = store.readChunk("large.txt", 256 * 1024, first.offsetBytes + first.bytes.size) as ShizukuWorkspaceFileStore.ReadChunkResult.Success
        assertEquals(256 * 1024, second.bytes.size)
        assertFalse(second.eof)
        val final = store.readChunk("large.txt", 256 * 1024, second.offsetBytes + second.bytes.size) as ShizukuWorkspaceFileStore.ReadChunkResult.Success
        assertEquals(20 * 1024, final.bytes.size)
        assertTrue(final.eof)
    }

    @Test
    fun applyPatchRequiresCurrentVersionAndUsesAtomicReplacement() {
        assertTrue(store.write("patch.txt", "before".toByteArray(StandardCharsets.UTF_8), false).contains("\"ok\":true"))
        val version = JSONObject(store.stat("patch.txt")).getString("version")
        val applied = JSONObject(store.applyPatch("patch.txt", "after", version, "REPLACE"))
        assertTrue(applied.getBoolean("ok"))
        assertEquals("after", JSONObject(store.read("patch.txt", 1024)).getString("text"))
        assertTrue(store.applyPatch("patch.txt", "stale", version, "REPLACE").contains("\"code\":\"CONFLICT\""))
        assertTrue(root.walkTopDown().none { it.name.startsWith(".mar-shizuku-") })
    }

    /** Pages through [relativePath] until `truncated` is false and returns every entry path. */
    private fun readAllPages(relativePath: String?, maxEntries: Int): List<String> {
        val paths = ArrayList<String>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val raw = store.list(relativePath, maxEntries = maxEntries, cursor = cursor)
            val page = JSONObject(raw)
            assertTrue(raw, page.getBoolean("ok"))
            assertTrue(
                "list page must stay within the Binder-safe envelope",
                raw.toByteArray(StandardCharsets.UTF_8).size <= ShizukuWorkspaceFileStore.MAX_OUTPUT_BYTES,
            )
            (0 until page.getJSONArray("entries").length()).mapTo(paths) {
                page.getJSONArray("entries").getJSONObject(it).getString("path")
            }
            pages++
            assertTrue("pagination did not terminate", pages <= ShizukuWorkspaceFileStore.MAX_LISTED_ENTRIES)
            if (!page.getBoolean("truncated")) break
            cursor = page.getString("nextCursor")
            assertTrue(cursor.length >= 40)
        }
        return paths
    }
}
