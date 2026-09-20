// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/**
 * Transactional invalidation for a previously validated embedding manifest.
 * Only source identity/content and KB binding changes advance this counter.
 * Successful embedding cache writes do not. Every connection observes the same
 * committed revision, including writers outside KnowledgeRepository.
 *
 * A dispatch execution validates the legacy manifest once and captures its
 * revision in that transaction. Subsequent batches check this one indexed row,
 * in addition to the live operation/consent/deletion checks. Any source mutation
 * stops the operation rather than implicitly accepting a new manifest. On
 * restart there is no remembered validation: PREPARED work validates afresh;
 * UNKNOWN still requires the existing explicit retry authorization.
 */
internal object EmbeddingInputRevision {
    fun current(db: SqlConnection, kbId: String): Long = db.query(
        "SELECT revision FROM embedding_input_revisions WHERE kb_id = ?", listOf(kbId),
    ).singleOrNull()?.long("revision") ?: error("EMBEDDING_INPUT_REVISION_MISSING")

    /** Called inside Migrations.apply's transaction; no user content is rewritten. */
    fun install(db: SqlConnection) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS embedding_input_revisions (
                kb_id TEXT NOT NULL PRIMARY KEY,
                revision INTEGER NOT NULL CHECK(typeof(revision) = 'integer' AND revision >= 0)
            )
        """.trimIndent())
        db.execute("INSERT OR IGNORE INTO embedding_input_revisions(kb_id,revision) SELECT id,0 FROM knowledge_bases")
        for (source in sources) {
            val changed = source.columns.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
            for (event in listOf("INSERT", "UPDATE", "DELETE")) {
                val references = when (event) {
                    "INSERT" -> listOf("NEW")
                    "DELETE" -> listOf("OLD")
                    else -> listOf("OLD", "NEW")
                }
                val owners = references.map { source.knowledgeBases(it) } +
                    if (event == "DELETE") emptyList() else listOf(source.collisionOwners)
                val selection = owners.joinToString(" UNION ")
                // BEFORE preserves the owner of rows displaced by OR REPLACE,
                // even with recursive_triggers disabled. An ignored insertion
                // may conservatively invalidate; rollback restores the revision.
                // UPSERT preserves the counter under an outer OR REPLACE.
                val timing = "BEFORE"
                val action = if (event == "UPDATE") "UPDATE OF ${source.columns.joinToString(",")}" else event
                val condition = if (event == "UPDATE") "WHEN $changed" else ""
                db.execute("""
                    CREATE TRIGGER IF NOT EXISTS embedding_input_${source.table}_${event.lowercase()}
                    $timing $action ON ${source.table} FOR EACH ROW $condition
                    BEGIN
                        INSERT INTO embedding_input_revisions(kb_id, revision)
                        SELECT kb_id, 1 FROM ($selection) WHERE kb_id IS NOT NULL
                        ON CONFLICT(kb_id) DO UPDATE SET revision = embedding_input_revisions.revision + 1;
                    END
                """.trimIndent())
            }
        }
    }

    private data class Source(
        val table: String,
        val columns: List<String>,
        val collisionOwners: String,
        val knowledgeBases: (String) -> String,
    )

    private val sources = listOf(
        Source("knowledge_bases", listOf("id", "embedding_space_id", "active_generation_id", "deleted_at"),
            "SELECT id AS kb_id FROM knowledge_bases WHERE id = NEW.id") {
            "SELECT $it.id AS kb_id"
        },
        Source("documents", listOf("id", "kb_id", "blob_hash", "active_version_id", "deleted_at"),
            "SELECT kb_id FROM documents WHERE id = NEW.id OR (kb_id = NEW.kb_id AND blob_hash = NEW.blob_hash)") {
            "SELECT $it.kb_id AS kb_id"
        },
        Source("document_versions", listOf("id", "document_id", "content_hash", "status"),
            "SELECT d.kb_id FROM document_versions v JOIN documents d ON d.id = v.document_id WHERE v.id = NEW.id") {
            "SELECT kb_id FROM documents WHERE id = $it.document_id"
        },
        Source("chunks", listOf("id", "document_version_id", "ordinal", "text", "content_hash"),
            "SELECT d.kb_id FROM chunks c JOIN document_versions v ON v.id = c.document_version_id " +
                "JOIN documents d ON d.id = v.document_id " +
                "WHERE c.id = NEW.id OR (c.document_version_id = NEW.document_version_id AND c.ordinal = NEW.ordinal)") {
            "SELECT d.kb_id FROM document_versions v JOIN documents d ON d.id = v.document_id " +
                "WHERE v.id = $it.document_version_id"
        },
    )
}
