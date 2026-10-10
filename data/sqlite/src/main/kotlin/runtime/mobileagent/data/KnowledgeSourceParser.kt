// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.*

/** Source decoding owns format selection; publication, consent and durable jobs stay in the repository. */
internal object KnowledgeSourceParser {
    fun parse(bytes: ByteArray, format: SourceFormat, name: String): ParsedPublication = when (format) {
        SourceFormat.PDF -> PdfParser.parse(bytes, deferImagePayloads = true)
        SourceFormat.IMAGE -> image(bytes, name)
        SourceFormat.OFFICE_ARCHIVE -> OfficeParser.parse(name, bytes)
        else -> String(bytes, Charsets.UTF_8).let { text ->
            ParsedPublication(SourceFormat.TEXT, text, listOf(ExtractedPage(1, text, false)), emptyList(), false, KnowledgeRepository.PARSER_FINGERPRINT)
        }
    }

    private fun image(bytes: ByteArray, name: String): ParsedPublication {
        val lower = name.lowercase()
        val mime = when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            else -> "image/*"
        }
        return ParsedPublication(SourceFormat.IMAGE, "", listOf(ExtractedPage(1, "", true)),
            listOf(ExtractedAsset("image-1", "IMAGE", 1, name, bytes, mime, "")), true, "image-v1")
    }
}
