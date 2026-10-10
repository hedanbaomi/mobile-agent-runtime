// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class MediaKindMarkdownTest {
    @Test
    fun markdownImageReferenceIsDetected() {
        val text = "See ![oven](photo.png) for the layout."
        assertTrue(MediaKind.markdownReferencesImages(text))
        assertEquals(SourceFormat.MARKDOWN, MediaKind.detect("recipe.md", "text/markdown", text.toByteArray()))
    }
}

class RetrievalBudgetTest {
    @Test
    fun clipsTotalCharacters() {
        val hits = listOf(
            SearchHit("c1", "d1", "a".repeat(4000), 1.0),
            SearchHit("c2", "d1", "b".repeat(4000), 0.5),
        )
        val clipped = RetrievalBudget.clip(hits, maxChars = 5000)
        assertEquals(2, clipped.size)
        assertEquals(4000, clipped[0].text.length)
        assertEquals(1000, clipped[1].text.length)
    }
}

class FileBlobSinkTest {
    @Test
    fun handlesShareIdempotentLeasesAndRejectInvalidHashes(@TempDir tmp: Path) {
        val first = FileBlobSink(tmp.toFile())
        val second = FileBlobSink(tmp.resolve(".").toFile())
        val blob = first.put("protected bytes".toByteArray(), "text/plain")
        val lease = first.protect(blob.sha256)
        assertTrue(blob.sha256 in second.protectedHashes())
        assertTrue(!second.remove(blob.sha256))
        lease.close()
        lease.close()
        assertTrue(second.remove(blob.sha256))
        assertEquals(0L, first.allocatedBytes())
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { first.get("../private") }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { first.protect("x") }
    }

    @Test
    fun temporaryCleanupOnlyRemovesItsOwnInterruptedWrites(@TempDir tmp: Path) {
        val sink = FileBlobSink(tmp.toFile())
        val dir = tmp.resolve("aa").toFile().also { it.mkdirs() }
        java.io.File(dir, "${"a".repeat(64)}-123.tmp").writeBytes(ByteArray(10))
        val unknown = java.io.File(dir, "keep.tmp").also { it.writeBytes(ByteArray(20)) }
        assertEquals(10L, sink.removeTemporaryFiles())
        assertTrue(unknown.exists())
        assertEquals(20L, sink.allocatedBytes())
    }
    @Test
    fun replacesCorruptExistingBlob(@TempDir tmp: Path) {
        val sink = FileBlobSink(tmp.toFile())
        val payload = "hello-cas".toByteArray()
        val first = sink.put(payload, "text/plain")
        val dest = tmp.resolve(first.sha256.take(2)).resolve(first.sha256)
        dest.writeBytes("CORRUPT".toByteArray())
        val second = sink.put(payload, "text/plain")
        assertEquals(first.sha256, second.sha256)
        assertTrue(dest.readBytes().contentEquals(payload))
    }
}
