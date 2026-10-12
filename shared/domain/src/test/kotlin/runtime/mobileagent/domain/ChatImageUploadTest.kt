// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChatImageUploadTest {
    @Test fun readsSupportedFormatsAndCopiesCallerBytes() {
        val fixtures = listOf(
            "image/png" to byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
            "image/jpeg" to byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()),
            "image/gif" to "GIF89a".toByteArray(),
            "image/webp" to "RIFFxxxxWEBP".toByteArray(),
        )
        fixtures.forEach { (mime, bytes) ->
            val upload = ChatImageUpload.read(ByteArrayInputStream(bytes), mime)
            assertArrayEquals(bytes, upload.bytes())
            val expected = upload.bytes()
            bytes[0] = 0
            upload.bytes()[0] = 0
            assertArrayEquals(expected, upload.bytes())
        }
    }

    @Test fun rejectsEmptyUnsupportedMismatchedAndOversizedInputsWithBoundedReads() {
        assertThrows(IllegalArgumentException::class.java) { ChatImageUpload(byteArrayOf(), "image/png") }
        assertThrows(IllegalArgumentException::class.java) { ChatImageUpload("<svg/>".toByteArray(), "image/svg+xml") }
        assertThrows(IllegalArgumentException::class.java) { ChatImageUpload("GIF89a".toByteArray(), "image/png") }
        val oversized = ByteArray(ChatImageUpload.MAX_IMAGE_BYTES + 100) { 1 }
        "GIF89a".toByteArray().copyInto(oversized)
        val stream = ByteArrayInputStream(oversized)
        assertThrows(IllegalArgumentException::class.java) { ChatImageUpload.read(stream, "image/gif") }
        assertEquals(99, stream.available())
        assertEquals(ChatImageUpload.MAX_IMAGE_BYTES,
            ChatImageUpload(oversized.copyOf(ChatImageUpload.MAX_IMAGE_BYTES), "image/gif").byteLength)
    }
}
