// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/** Bounded, namespace-aware DOCX and declared-spine EPUB extraction. */
object OfficeParser {
    const val DOCX_FINGERPRINT = "docx-xml-v3"
    const val EPUB_FINGERPRINT = "epub-xml-v5"
    fun parse(fileName: String, bytes: ByteArray): ParsedPublication {
        val inspection = ZipSafety.inspect(bytes)
        if (!inspection.ok) error(inspection.reason)
        val entries = readEntries(bytes)
        val names = entries.keys.map { it.lowercase() }
        return when {
            names.any { it == "word/document.xml" || it.endsWith("/word/document.xml") } -> DocxPublication.parse(entries)
            names.any { it == "meta-inf/container.xml" || it == "mimetype" } -> EpubPublication.parse(entries)
            fileName.lowercase().endsWith(".docx") -> DocxPublication.parse(entries)
            fileName.lowercase().endsWith(".epub") -> EpubPublication.parse(entries)
            else -> error("Archive is not a DOCX or EPUB package")
        }
    }

}
