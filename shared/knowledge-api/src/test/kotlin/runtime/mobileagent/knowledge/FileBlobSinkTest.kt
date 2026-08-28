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

class TextChunkerTest {
    @Test
    fun splitsOversizedSingleParagraph() {
        val chunks = TextChunker.chunk("x".repeat(100_000), targetChars = 1800, overlapChars = 200)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.maxOf { it.length } <= 1800)
    }
}

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
