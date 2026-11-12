// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.data.migrations.MigrationConversationBindings
import runtime.mobileagent.data.migrations.MigrationLegacyCollisions
import runtime.mobileagent.data.migrations.MigrationProfileBackfills
import runtime.mobileagent.data.migrations.MigrationSchemaColumns
import runtime.mobileagent.data.migrations.MigrationSchemaStatements
import runtime.mobileagent.data.migrations.MigrationSchemaValidation
import runtime.mobileagent.data.migrations.MigrationV2ControlPlane
import runtime.mobileagent.data.migrations.migrationInvalid
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

/**
 * SQLite schema owner for the application database.
 *
 * The previous implementation ran every ALTER statement and swallowed every exception. That
 * made a typo, a damaged schema, and an already-present column indistinguishable. v8 through v10 keep
 * existing data and only execute an ALTER after inspecting the live table. Any other DDL or
 * version error escapes the transaction, leaving schema_version at its previous value.
 */
object Migrations {
    // v13 adds durable grant owners/consumption markers and changes the canonical
    // uniqueness key. SQLite cannot alter a table-level UNIQUE constraint in place,
    // so old v12 tables are preserved under a legacy name and copied transactionally.
    // v14 persists workspace scope and the explicit high-risk full-device grant.
    // v15 adds encrypted privileged locators, immutable thread bindings, and
    // agent defaults. None of those rows contain a provider handle or a
    // plaintext path/URI.
    // v16 adds the explicit agent_snapshot_id conversation projection used by
    // the thread/workspace integration. snapshot_id remains for legacy data
    // and repository consumers; both values must describe the same snapshot.
    // v17 adds the frozen per-run manifest (versions/fingerprints only, never
    // secrets) to runs.  Existing rows keep the empty object: they predate
    // frozen facts and must not invent them.
    // v18 adds durable session-context compaction attempts: an explicit state
    // machine, a per-attempt source digest, at most one active attempt per
    // conversation, and a summary that only a
    // verified SUCCEEDED row may carry.  No transcript row is deleted or
    // rewritten by a summary, and no summary is ever re-sent from the database.
    // v25 stores bounded-read UTF-16 lengths; body and existing references are unchanged.
    // v26: transactional source revisions for linear API batch validation.
    // v27 replaces the single active dispatch index with three durable slots.
    // v28 permits six durable slots and freezes v27 batches at their old default of three.
    // v29 adds the scoped import-job display index without replaying legacy binding projection.
    // v33 derives an indexed instant key without rewriting message timestamps or hashes.
    // v34 converts chunks_fts from an external-content index into a contentless-delete
    // index rebuilt from chunks.text. Only derived postings change; every source,
    // embedding, generation, pin, permission and consent row stays in place.
    // v35 adds immutable world book revisions, conversation bindings and activation receipts.
    // v36 adds optional imported character metadata; old Agents and snapshots retain their values.
    // v38 fixes the Agent mode at creation and adds bounded per-conversation TRPG state/receipts.
    // It also repairs old API26 offset-timestamp ordering keys without rewriting messages.
    // v40 adds explicit Root consent and session epochs without inheriting prior trust.
    const val VERSION = 41

    fun apply(connection: SqlConnection) {
        // Structural corruption is not a legacy FK orphan: never migrate a damaged database.
        check(connection.query("PRAGMA quick_check").all { it.columns.values.singleOrNull() == "ok" }) {
            "SQLite integrity check failed; database was preserved"
        }
        connection.transaction {
            // A malformed pre-existing table must fail here; never replace it or clear data.
            connection.execute(MigrationSchemaStatements.statements.first())
            val current = readVersion(connection)
            if (current > VERSION) unsupported(current)
            // v2 gives several control-plane tables canonical names which were never present in
            // the v1 schema.  A few early prototypes used one of those names for a different
            // shape.  Preserve such a table under a deterministic legacy name before creating
            // the canonical table; never drop or overwrite it.  A table which already has the
            // canonical shape is left in place so a damaged v2 install still fails validation.
            if (current < 28L) MigrationLegacyCollisions.prepareLegacyNameCollisions(connection)
            // workspace_acl is derived state.  Recreate it so a v12 view cannot
            // silently hide the durable lifecycle columns introduced in v13.
            if (connection.query("SELECT type FROM sqlite_master WHERE name = 'workspace_acl'").singleOrNull()?.string("type") == "view") {
                connection.execute("DROP VIEW workspace_acl")
            }
            MigrationSchemaStatements.statements.drop(1).forEach { sql -> connection.execute(sql) }
            connection.execute("CREATE TABLE IF NOT EXISTS blob_gc_pending(hash TEXT PRIMARY KEY CHECK(length(hash)=64))")
            // v24 is additive and lazy: legacy jobs/cache rows are not guessed into unit plans.
            DocumentPipelineStore.schema.forEach { sql -> connection.execute(sql) }
            MigrationSchemaColumns.columns.forEach { column -> MigrationSchemaColumns.ensureColumn(connection, column) }
            RootAuthoritySchema.install(connection, current)
            WorldBookSchema.install(connection)
            CharacterCardStorage.install(connection)
            TrpgSchema.install(connection)
            WorldBookRevisionStorage.install(connection, current)
            MessageChronology.install(connection)
            MessageChronologyCompatibility.repair(connection, current)
            // v34: the historical external-content statement above may have just created the
            // wrong table, so the index is repaired from chunks.text in this same transaction.
            LexicalIndexMigration.ensure(connection, current)
            if (current in 1L..27L) {
                // The old default lived in code, not a policy row. Preserve it for existing
                // batches before changing the default for newly created batches.
                connection.execute("INSERT INTO pipeline_policies(batch_id,max_concurrency,failure_limit,token_ceiling,reservation_tokens) SELECT b.id,3,3,NULL,NULL FROM import_batches b WHERE NOT EXISTS (SELECT 1 FROM pipeline_policies p WHERE p.batch_id=b.id)")
                connection.execute("DROP TRIGGER IF EXISTS pipeline_dispatch_slot_required_insert")
                connection.execute("DROP TRIGGER IF EXISTS pipeline_dispatch_slot_required_update")
            }
            // The v24 unique constant-expression index allowed only one live
            // request. Existing databases can have at most one active row.
            connection.execute("DROP INDEX IF EXISTS pipeline_one_dispatch")
            connection.execute("UPDATE pipeline_attempts SET dispatch_slot = 1 WHERE state IN ('READY','DISPATCHED') AND dispatch_slot IS NULL")
            connection.execute("CREATE UNIQUE INDEX IF NOT EXISTS pipeline_active_dispatch_slot ON pipeline_attempts(dispatch_slot) WHERE state IN ('READY','DISPATCHED')")
            connection.execute("CREATE TRIGGER IF NOT EXISTS pipeline_dispatch_slot_required_insert BEFORE INSERT ON pipeline_attempts WHEN NEW.state IN ('READY','DISPATCHED') AND (NEW.dispatch_slot IS NULL OR NEW.dispatch_slot NOT BETWEEN 1 AND 6) BEGIN SELECT RAISE(ABORT,'active dispatch requires a bounded slot'); END")
            connection.execute("CREATE TRIGGER IF NOT EXISTS pipeline_dispatch_slot_required_update BEFORE UPDATE ON pipeline_attempts WHEN NEW.state IN ('READY','DISPATCHED') AND (NEW.dispatch_slot IS NULL OR NEW.dispatch_slot NOT BETWEEN 1 AND 6) BEGIN SELECT RAISE(ABORT,'active dispatch requires a bounded slot'); END")
            connection.execute("CREATE TRIGGER IF NOT EXISTS pipeline_dispatch_slot_immutable BEFORE UPDATE ON pipeline_attempts WHEN OLD.dispatch_slot IS NOT NEW.dispatch_slot BEGIN SELECT RAISE(ABORT,'pipeline dispatch slot is immutable'); END")
            connection.execute("CREATE INDEX IF NOT EXISTS chunk_read_lengths ON chunks(document_version_id,ordinal,text_utf16_length,id)")
            connection.execute("CREATE INDEX IF NOT EXISTS chunk_missing_lengths ON chunks(document_version_id,id) WHERE text_utf16_length < 0")
            ChunkTextMetadata.backfill(connection)
            EmbeddingInputRevision.install(connection)
            // Bounded result pages also need bounded SQL access paths.
            connection.execute("CREATE INDEX IF NOT EXISTS chunk_embedding_keyset ON chunks(document_version_id,id)")
            connection.execute("CREATE INDEX IF NOT EXISTS embedding_content_lookup ON embeddings(space_id,content_hash,chunk_id)")
            // Derived read paths are installed for current-version databases too; no durable
            // row or compatibility version is changed by these non-unique covering indexes.
            connection.execute("CREATE INDEX IF NOT EXISTS assets_document_vision_lookup ON assets(document_id,blob_hash,surrounding_text_hash,page)")
            connection.execute("CREATE INDEX IF NOT EXISTS vision_result_asset_lookup ON vision_results(asset_hash,context_hash,status,model_fingerprint)")
            MigrationProfileBackfills.backfillConversationSnapshotIds(connection)
            MigrationProfileBackfills.backfillV11(connection)
            MigrationProfileBackfills.backfillV12(connection)
            MigrationV2ControlPlane.backfillV2ControlPlane(connection)
            // Preserve the pre-v29 cutoff: an index upgrade must not bind an unbound thread.
            if (current < 28L) MigrationConversationBindings.migrateExistingConversationWorkspaceBindings(connection)
            MigrationSchemaValidation.ensureDefaultAuthorityRows(connection)
            if (current in 15L..30L) {
                // Legacy Agent rollback relied on a cascade that the Android
                // driver did not enable. A dead owner's default is only a
                // preference; discard it without changing workspaces/grants.
                connection.execute("DELETE FROM agent_workspace_defaults WHERE NOT EXISTS (SELECT 1 FROM agent_profiles WHERE agent_profiles.id = agent_workspace_defaults.agent_id)")
            }
            MigrationSchemaValidation.validateSnapshotManifests(connection)
            MigrationSchemaValidation.validateContextCompactions(connection)
            MigrationSchemaValidation.validateAuthoritySchema(connection)
            MigrationSchemaValidation.validateWorkspaceScopes(connection)
            MigrationSchemaValidation.validateWorkspaceBindings(connection)
            MigrationSchemaValidation.validateRequiredSchema(connection)
            connection.execute("DELETE FROM schema_version")
            connection.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(VERSION))
        }
        // Index repair is a data migration owned by KnowledgeRepository. It is deliberately not
        // wrapped in runCatching: callers must see a failed repair and can retry explicitly.
        KnowledgeRepository(connection, runtime.mobileagent.knowledge.MemoryBlobSink(), automaticStorageMaintenance = false).repairIndexes(onlyChanged = true)
    }

    /** Existing orphan user rows are preserved for explicit repair; new writes enforce FKs. */
    fun foreignKeyCompatibility(connection: SqlConnection): List<ForeignKeyCompatibilityIssue> =
        connection.query("PRAGMA foreign_key_check").map {
            ForeignKeyCompatibilityIssue(it.string("table"), it.longOrNull("rowid"), it.string("parent"), it.long("fkid"))
        }

    private fun readVersion(connection: SqlConnection): Long {
        val rows = connection.query("SELECT version FROM schema_version")
        if (rows.size > 1) migrationInvalid("schema_version has multiple rows")
        if (rows.isEmpty()) return 0L
        val raw = rows.single().columns["version"]
        // SQLite's INTEGER affinity still permits REAL/TEXT values.  Accept only the integer
        // runtime types returned by the adapters; coercing 8.5 or "8" would hide a damaged DB.
        val version = when (raw) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            else -> migrationInvalid("schema_version.version is not an integer")
        }
        if (version < 0L) migrationInvalid("schema_version.version must not be negative")
        return version
    }

    private fun unsupported(current: Long): Nothing = throw AppError(
        code = ErrorCode.SCHEMA_UNSUPPORTED,
        userMessage = "Database schema version $current is newer than supported version $VERSION",
        retryClass = RetryClass.USER_ACTION,
        stage = "migration",
        operationId = "database-migration",
        sanitizedDetails = "schemaVersion=$current",
    ).asException()
}
