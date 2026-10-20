// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import runtime.mobileagent.serialization.charactercard.CharacterCardCodec

internal object CharacterCardFileIO {
    fun read(resolver: ContentResolver, uri: Uri): Pair<ByteArray, String> {
        require(uri.scheme == "content") { "character-card.content-uri-required" }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.substringBeforeLast('.')?.take(256).orEmpty().ifBlank { "Imported character" }
        val bytes = requireNotNull(resolver.openInputStream(uri)) { "character-card.unreadable" }.use(::readBytes)
        return bytes to name
    }

    internal fun readBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(count > 0) { "character-card.no-progress" }
            require(count <= CharacterCardCodec.MAX_FILE_BYTES - output.size()) { "character-card.file-too-large" }
            output.write(chunk, 0, count)
        }
        return output.toByteArray()
    }
}
