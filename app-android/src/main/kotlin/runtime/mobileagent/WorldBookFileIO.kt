// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object WorldBookFileIO {
    const val MAX_BYTES = 16 * 1024 * 1024

    /** Re-reads a staged candidate and proves it is the exact file the preview described. */
    fun readStaged(file: File, sha256: String): String {
        require(file.isFile && file.length() <= MAX_BYTES) { "The import preview expired; choose the files again" }
        val bytes = file.readBytes()
        check(WorldBookBatchStager.sha256(bytes) == sha256) { "Staged world book changed after preview; choose the files again" }
        return decodeUtf8(bytes)
    }

    fun readUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (count == 0) throw IllegalArgumentException("World book stream did not make progress")
            require(output.size() <= MAX_BYTES - count) { "World book exceeds the 16 MiB import limit" }
            output.write(chunk, 0, count)
        }
        return decodeUtf8(output.toByteArray())
    }

    /** Strict UTF-8; a malformed byte rejects the file instead of importing replacement characters. */
    fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

    fun write(resolver: ContentResolver, uri: Uri, text: String) {
        require(uri.scheme == "content") { "Select an export location with the system file picker" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "World book export exceeds the 16 MiB limit" }
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "World book export could not be opened" }.use { it.write(bytes); it.flush() }
    }
}
