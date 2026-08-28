// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaKindTest {
    @Test
    fun pngHeaderIsImage() {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        assertEquals(SourceFormat.IMAGE, MediaKind.detect("scan.bin", "application/octet-stream", header))
        assertTrue(MediaKind.isImage(SourceFormat.IMAGE))
    }

    @Test
    fun pdfMagicIsPdf() {
        assertEquals(SourceFormat.PDF, MediaKind.detect("doc.pdf", "application/pdf", "%PDF-1.4".toByteArray()))
    }

    @Test
    fun markdownNameIsMarkdown() {
        assertEquals(SourceFormat.MARKDOWN, MediaKind.detect("notes.md", "text/plain", "# hi".toByteArray()))
    }
}
