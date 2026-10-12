// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ChatImageUpload

class ChatImagePickerTest {
    private fun source(type: String?, bytes: ByteArray?, size: Long? = bytes?.size?.toLong()) =
        ChatImageSource(type, size) { bytes?.let(::ByteArrayInputStream) }

    @Test fun validFilesBecomeUploadsAndEachRejectionIsReportedInOrder() {
        var opened = 0
        val tooLargeByMetadata = ChatImageSource("image/png", ChatImageUpload.MAX_IMAGE_BYTES + 1L) { opened++; null }
        val result = ChatImagePicker.read(listOf(
            source("image/gif", "GIF89a".toByteArray()),
            source("image/heic", byteArrayOf(1, 2, 3)),
            tooLargeByMetadata,
            source("image/png", "GIF89a".toByteArray()),
            source("image/gif", ByteArray(ChatImageUpload.MAX_IMAGE_BYTES + 1).also { "GIF89a".toByteArray().copyInto(it) }, size = null),
            source("image/gif", null),
        )) { null }
        assertEquals(listOf("image/gif"), result.uploads.map { it.first.mediaType })
        assertEquals(0, opened, "a declared oversize file is rejected without opening its stream")
        assertEquals(5, result.rejected.size)
        assertTrue(result.rejected[0].startsWith("第 2 张") && "格式" in result.rejected[0])
        assertTrue("2 MiB" in result.rejected[1])
        assertTrue("格式不符" in result.rejected[2])
        assertTrue("2 MiB" in result.rejected[3])
        assertTrue("无法读取" in result.rejected[4])
    }

    @Test fun previewFailureDoesNotDropTheUpload() {
        val result = ChatImagePicker.read(listOf(source("IMAGE/GIF", "GIF89a".toByteArray()))) { error("decoder") }
        assertEquals(1, result.uploads.size)
        assertNull(result.uploads.single().second)
        assertTrue(result.rejected.isEmpty())
    }

    @Test fun fallbackSelectionIsBoundedBeforeOpeningFilesOrDecodingPreviews() {
        var opened = 0
        var previews = 0
        val sources = List(20) {
            ChatImageSource("image/gif", null) { opened++; ByteArrayInputStream("GIF89a".toByteArray()) }
        }
        val result = ChatImagePicker.read(sources) { previews++; null }
        assertEquals(8, result.uploads.size)
        assertEquals(8, opened)
        assertEquals(8, previews)
        assertEquals(1, result.rejected.size)
        assertTrue("12 张未读取" in result.rejected.single())
    }

    @Test fun metadataFailureDoesNotOpenThatFileOrDiscardOtherUploads() {
        var opened = false
        val inaccessible = ChatImageSource(null, null, metadataUnavailable = true) { opened = true; null }
        val result = ChatImagePicker.read(listOf(inaccessible, source("image/gif", "GIF89a".toByteArray()))) { null }
        assertFalse(opened)
        assertEquals(1, result.uploads.size)
        assertTrue("无法读取" in result.rejected.single())
    }
}
