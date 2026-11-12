// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.skills.tooling.ToolErrorCode

/**
 * Regression suite for [P0-B]: Shizuku workspace NFC path normalization and
 * fail-closed non-NFC / sibling-collision alias guards.
 */
class ShizukuWorkspaceFileStoreAliasTest {

    private val nfdName = "cafe\u0301.txt"
    private val nfcName = "caf\u00e9.txt"

    private fun writeUtf8(path: Path, content: String) {
        Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readUtf8(path: Path): String =
        String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    @Test
    fun nonNfcEntryAloneFailsClosedAcrossAllOperations(@TempDir tempDir: Path) {
        val root = Files.createDirectory(tempDir.resolve("workspace"))
        val nfdFile = root.resolve(nfdName)
        writeUtf8(nfdFile, "nfd-only")

        listOf(true, false).forEach { enforceQuota ->
            val store = ShizukuWorkspaceFileStore(root.toFile(), enforceQuota = enforceQuota)

            assertAliasFailure(store.list(null), "list")
            assertAliasFailure(store.stat(null), "stat")
            assertAliasFailure(store.stat(nfdName), "stat")
            assertAliasFailure(store.stat(nfcName), "stat")
            assertAliasFailure(store.read(nfdName, 1024), "read")
            assertAliasFailure(store.read(nfcName, 1024), "read")

            val chunkNfd = store.readChunk(nfdName, 1024, 0L)
            assertEquals(
                ShizukuWorkspaceFileStore.ReadChunkResult.Failure(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS),
                chunkNfd,
            )
            val chunkNfc = store.readChunk(nfcName, 1024, 0L)
            assertEquals(
                ShizukuWorkspaceFileStore.ReadChunkResult.Failure(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS),
                chunkNfc,
            )

            assertAliasFailure(
                store.write(nfdName, "new".toByteArray(StandardCharsets.UTF_8), replaceExisting = false),
                "write",
            )
            assertAliasFailure(
                store.write(nfdName, "new".toByteArray(StandardCharsets.UTF_8), replaceExisting = true),
                "write",
            )
            assertAliasFailure(store.delete(nfdName), "delete")
            assertAliasFailure(store.applyPatch(nfdName, "patched", "v1", "REPLACE"), "apply_patch")

            assertEquals("nfd-only", readUtf8(nfdFile))
            assertFalse(Files.exists(root.resolve(nfcName)))
        }
    }

    @Test
    fun coexistingNfdAndNfcSiblingsNeverOverwriteOrDeleteNfcTarget(@TempDir tempDir: Path) {
        val root = Files.createDirectory(tempDir.resolve("workspace"))
        val nfcFile = root.resolve(nfcName)
        val nfdFile = root.resolve(nfdName)
        writeUtf8(nfcFile, "nfc-protected")
        writeUtf8(nfdFile, "nfd-shadow")
        writeUtf8(root.resolve("other.txt"), "other")

        listOf(true, false).forEach { enforceQuota ->
            val store = ShizukuWorkspaceFileStore(root.toFile(), enforceQuota = enforceQuota)

            assertAliasFailure(store.list(null), "list")
            assertAliasFailure(store.stat(nfdName), "stat")
            assertAliasFailure(store.stat(nfcName), "stat")
            assertAliasFailure(store.read(nfdName, 1024), "read")
            assertAliasFailure(store.read(nfcName, 1024), "read")

            assertAliasFailure(
                store.write(nfdName, "clobber".toByteArray(StandardCharsets.UTF_8), replaceExisting = true),
                "write",
            )
            assertAliasFailure(
                store.write(nfcName, "clobber".toByteArray(StandardCharsets.UTF_8), replaceExisting = true),
                "write",
            )
            assertAliasFailure(store.delete(nfdName), "delete")
            assertAliasFailure(store.delete(nfcName), "delete")
            assertAliasFailure(
                store.applyPatch(nfdName, "clobber", "deadbeef".repeat(8), "REPLACE"),
                "apply_patch",
            )
            assertAliasFailure(
                store.move(nfdName, "moved.txt", replaceExisting = true),
                "move",
            )
            assertAliasFailure(
                store.move("other.txt", nfdName, replaceExisting = true),
                "move",
            )

            // Both files must remain completely intact and unmodified.
            assertTrue(Files.exists(nfcFile))
            assertTrue(Files.exists(nfdFile))
            assertEquals("nfc-protected", readUtf8(nfcFile))
            assertEquals("nfd-shadow", readUtf8(nfdFile))
            assertEquals("other", readUtf8(root.resolve("other.txt")))
        }
    }

    @Test
    fun nestedNonNfcDirectorySegmentFailsClosedWithoutQuotaWalk(@TempDir tempDir: Path) {
        val root = Files.createDirectory(tempDir.resolve("attached"))
        val sub = Files.createDirectory(root.resolve("sub"))
        val nfdDir = Files.createDirectory(sub.resolve("di\u0301r"))
        val child = nfdDir.resolve("note.txt")
        writeUtf8(child, "keep")

        val store = ShizukuWorkspaceFileStore(root.toFile(), enforceQuota = false)
        assertAliasFailure(store.list("sub"), "list")
        assertAliasFailure(store.read("sub/di\u0301r/note.txt", 1024), "read")
        assertAliasFailure(
            store.write("sub/di\u0301r/note.txt", "overwrite".toByteArray(StandardCharsets.UTF_8), replaceExisting = true),
            "write",
        )
        assertAliasFailure(store.delete("sub/di\u0301r/note.txt"), "delete")
        assertEquals("keep", readUtf8(child))
    }

    @Test
    fun canonicalNfcRoundTripAndLongNfcListingRemainFunctional(@TempDir tempDir: Path) {
        val root = Files.createDirectory(tempDir.resolve("clean"))
        val store = ShizukuWorkspaceFileStore(root.toFile(), enforceQuota = true)

        val writeJson = Json.parseToJsonElement(
            store.write(nfdName, "hello".toByteArray(StandardCharsets.UTF_8), replaceExisting = false),
        ).jsonObject
        assertTrue(writeJson.getValue("ok").jsonPrimitive.boolean)
        assertEquals(nfcName, writeJson.getValue("path").jsonPrimitive.content)
        assertTrue(Files.exists(root.resolve(nfcName)))

        val longName = "n".repeat(200) + ".txt"
        writeUtf8(root.resolve(longName), "long")

        val listing = Json.parseToJsonElement(store.list(null)).jsonObject
        assertTrue(listing.getValue("ok").jsonPrimitive.boolean)
        val listedPaths = listing.getValue("entries").jsonArray.map {
            it.jsonObject.getValue("path").jsonPrimitive.content
        }
        assertTrue(nfcName in listedPaths)
        assertTrue(longName in listedPaths)

        val statJson = Json.parseToJsonElement(store.stat(nfdName)).jsonObject
        val version = statJson.getValue("version").jsonPrimitive.content
        val patched = Json.parseToJsonElement(
            store.applyPatch(nfdName, "updated", version, "REPLACE"),
        ).jsonObject
        assertTrue(patched.getValue("ok").jsonPrimitive.boolean)

        val readJson = Json.parseToJsonElement(store.read(nfdName, 1024)).jsonObject
        assertEquals("updated", readJson.getValue("text").jsonPrimitive.content)

        val deleted = Json.parseToJsonElement(store.delete(nfdName)).jsonObject
        assertTrue(deleted.getValue("ok").jsonPrimitive.boolean)
        assertFalse(Files.exists(root.resolve(nfcName)))
    }

    @Test
    fun adapterErrorMappersTranslateAliasAmbiguityToIoErrorNotUnknownOutcome() {
        listOf("list", "stat", "read", "write", "delete", "apply_patch", "mkdir", "move").forEach { operation ->
            val fallback = shizukuMalformedResponseCode(operation)
            assertEquals(
                ToolErrorCode.IO_ERROR,
                mapShizukuWorkspaceError(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS, operation, fallback),
                "ShizukuBackendAdapters must map PROVIDER_ALIAS_AMBIGUOUS to IO_ERROR for $operation",
            )
            assertEquals(
                ToolErrorCode.IO_ERROR,
                mapShizukuTokenWorkspaceError(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS, fallback),
                "ShizukuTokenWorkspaceBackend must map PROVIDER_ALIAS_AMBIGUOUS to IO_ERROR for $operation",
            )
        }
        assertEquals(
            ToolErrorCode.IO_ERROR,
            mapShizukuDeviceBrowserError(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS),
        )
    }

    private fun assertAliasFailure(rawJson: String, expectedOperation: String) {
        val obj = Json.parseToJsonElement(rawJson).jsonObject
        assertFalse(obj.getValue("ok").jsonPrimitive.boolean, "Expected failure JSON: $rawJson")
        assertEquals(expectedOperation, obj.getValue("operation").jsonPrimitive.content)
        assertEquals(
            ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS,
            obj.getValue("code").jsonPrimitive.content,
            "Expected PROVIDER_ALIAS_AMBIGUOUS for $expectedOperation but got: $rawJson",
        )
    }
}
