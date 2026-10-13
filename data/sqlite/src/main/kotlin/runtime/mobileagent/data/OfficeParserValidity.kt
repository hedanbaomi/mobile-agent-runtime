// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.OfficeParser

/** Historical versions remain inspectable; new retrieval must use repaired Office derivatives. */
internal fun staleOfficeDerivatives(db: SqlConnection, kbId: String, publishingDocumentId: String? = null): Boolean =
    db.query("""
        SELECT d.id FROM documents d JOIN document_versions v ON v.id=d.active_version_id AND v.document_id=d.id
        WHERE d.kb_id=? AND d.deleted_at IS NULL AND d.format='OFFICE_ARCHIVE'
        AND v.parser_fingerprint NOT IN (?,?) AND (? IS NULL OR d.id<>?) LIMIT 1
    """.trimIndent(), listOf(kbId, OfficeParser.DOCX_FINGERPRINT, OfficeParser.EPUB_FINGERPRINT,
        publishingDocumentId, publishingDocumentId)).isNotEmpty()

internal const val OFFICE_REPARSE_REQUIRED = "PARSER_REPARSE_REQUIRED: Office extraction changed; re-import the original file to repair local text. Vision and API embedding are never replayed automatically."

internal fun checkOfficeReadFingerprint(expectedVersion: String?, format: String, fingerprint: String) {
    if (expectedVersion == null && format == "OFFICE_ARCHIVE" &&
        fingerprint !in setOf(OfficeParser.DOCX_FINGERPRINT, OfficeParser.EPUB_FINGERPRINT)) error(OFFICE_REPARSE_REQUIRED)
}
