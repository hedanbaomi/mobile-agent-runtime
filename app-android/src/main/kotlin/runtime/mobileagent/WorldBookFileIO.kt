// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object WorldBookFileIO {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun read(resolver: ContentResolver, uri: Uri): Pair<String, String> {
        require(uri.scheme == "content") { "Select a world book with the system file picker" }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.substringBeforeLast('.')?.take(256).orEmpty().ifBlank { "Imported world book" }
        val text = requireNotNull(resolver.openInputStream(uri)) { "World book could not be opened" }.use(::readUtf8)
        return text to name
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
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString().removePrefix("\uFEFF")
    }

    fun write(resolver: ContentResolver, uri: Uri, text: String) {
        require(uri.scheme == "content") { "Select an export location with the system file picker" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "World book export exceeds the 16 MiB limit" }
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "World book export could not be opened" }.use { it.write(bytes); it.flush() }
    }
}
