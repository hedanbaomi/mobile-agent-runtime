// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.CapabilityVerification
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.InputModality
import runtime.mobileagent.domain.ModelOperation
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile

class MigrationsTest {
    @Test
    fun schemaAndProviderRoundTrip() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = ProfileRepository(db)
        repo.upsertProvider(
            ProviderProfile(
                id = "p1",
                name = "Local",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "ref-1",
                revision = 1,
            ),
        )
        assertEquals("Local", repo.listProviders().single().name)
        assertEquals(Migrations.VERSION, db.query("SELECT version FROM schema_version").single().long("version").toInt())
        assertEquals(1, db.query("PRAGMA table_info(model_profiles)").count { it.string("name") == "parameters_json" })
        assertEquals(1, db.query("PRAGMA table_info(agent_snapshots)").count { it.string("name") == "expanded_json" })
        assertEquals(1, db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", listOf("embedding_query_attempts")).size)
    }

    @Test
    fun embeddingQueryAttemptsSurviveV10Migration() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)

        db.execute(
            "INSERT INTO knowledge_bases(id, name, active_generation_id, embedding_space_id, created_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?)",
            listOf("kb-v8", "Existing KB", null, "space-v8", "2026-08-29T00:00:00Z", null),
        )
        db.execute(
            "INSERT INTO embedding_query_attempts(kb_id, space_id, query_hash, error, updated_at) VALUES (?, ?, ?, ?, ?)",
            listOf("kb-v8", "space-v8", "sha256-query", "UNKNOWN", "2026-08-29T00:01:00Z"),
        )
        // Simulate a valid v9 database: v9 data remains, while only the v10 tables are absent.
        db.execute("DROP TABLE embedding_operations")
        db.execute("DROP TABLE embedding_query_vectors")
        db.execute("UPDATE schema_version SET version = ?", listOf(9))

        Migrations.apply(db)

        assertEquals(Migrations.VERSION, db.query("SELECT version FROM schema_version").single().long("version").toInt())
        assertEquals("Existing KB", db.query("SELECT name FROM knowledge_bases WHERE id = ?", listOf("kb-v8")).single().string("name"))
        assertEquals(
            listOf("kb_id", "space_id", "query_hash", "retry_authorized", "error", "updated_at"),
            db.query("PRAGMA table_info(embedding_query_attempts)").map { it.string("name") },
        )
        assertEquals("knowledge_bases", db.query("PRAGMA foreign_key_list(embedding_query_attempts)").single().string("table"))
        assertEquals("0", db.query("PRAGMA table_info(embedding_query_attempts)").single { it.string("name") == "retry_authorized" }.string("dflt_value"))
        val createSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf("embedding_query_attempts"),
        ).single().string("sql")
        assertTrue(createSql.replace(" ", "").contains("CHECK(retry_authorizedIN(0,1))"))
        assertEquals("UNKNOWN", db.query("SELECT error FROM embedding_query_attempts").single().string("error"))

        // A second apply must not replace the v9 data or newly created v10 tables.
        Migrations.apply(db)
        assertEquals(1, db.query("SELECT * FROM embedding_query_attempts").size)
        assertEquals("UNKNOWN", db.query("SELECT error FROM embedding_query_attempts").single().string("error"))
    }

    @Test
    fun embeddingOperationAndQueryVectorSchemaIsStrictAndIdempotent() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)

        val operationInfo = db.query("PRAGMA table_info(embedding_operations)")
        assertEquals(
            listOf(
                "token", "kind", "kb_id", "job_id", "document_id", "document_version_id", "space_id",
                "input_manifest_hash", "binding_fingerprint", "consent_fingerprint", "state", "cancel_requested",
                "error", "created_at", "updated_at",
            ),
            operationInfo.map { it.string("name") },
        )
        assertEquals(0L, operationInfo.single { it.string("name") == "job_id" }.long("notnull"))
        assertEquals(0L, operationInfo.single { it.string("name") == "document_id" }.long("notnull"))
        assertEquals(0L, operationInfo.single { it.string("name") == "document_version_id" }.long("notnull"))
        assertEquals("0", operationInfo.single { it.string("name") == "cancel_requested" }.string("dflt_value"))

        val vectorInfo = db.query("PRAGMA table_info(embedding_query_vectors)")
        assertEquals(
            listOf("space_id", "query_hash", "vector_blob", "dimension", "created_at"),
            vectorInfo.map { it.string("name") },
        )
        assertEquals(1L, vectorInfo.single { it.string("name") == "dimension" }.long("notnull"))

        assertEquals(
            setOf("knowledge_bases", "import_jobs", "documents", "document_versions"),
            db.query("PRAGMA foreign_key_list(embedding_operations)").map { it.string("table") }.toSet(),
        )
        val operationIndexes = db.query("PRAGMA index_list(embedding_operations)")
        val activeIndex = operationIndexes.single { it.string("name") == "uq_embedding_operations_active_kb" }
        assertEquals(1L, activeIndex.long("unique"))
        assertEquals(1L, activeIndex.long("partial"))
        assertTrue(operationIndexes.any { it.string("name") == "idx_embedding_operations_job" })

        val operationSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf("embedding_operations"),
        ).single().string("sql").lowercase().replace(" ", "")
        assertTrue(operationSql.contains("check(kindin('import','rebuild','rebind'))"))
        assertTrue(operationSql.contains("check(statein('prepared','dispatched','cache_ready','published','failed','cancelled','aborted','unknown'))"))
        assertTrue(operationSql.contains("check(cancel_requestedin(0,1))"))
        val vectorSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf("embedding_query_vectors"),
        ).single().string("sql").lowercase().replace(" ", "")
        assertTrue(vectorSql.contains("check(dimension>0)"))

        db.execute(
            "INSERT INTO knowledge_bases(id, name, active_generation_id, embedding_space_id, created_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?)",
            listOf("kb-v10", "Embedding KB", null, "space-v10", "2026-08-29T00:00:00Z", null),
        )
        insertEmbeddingOperation(db, "op-1", "kb-v10", state = "PREPARED")
        assertThrows(Exception::class.java) {
            insertEmbeddingOperation(db, "op-2", "kb-v10", state = "DISPATCHED")
        }
        assertThrows(Exception::class.java) {
            insertEmbeddingOperation(db, "op-invalid", "missing-kb", state = "FAILED")
        }
        assertThrows(Exception::class.java) {
            insertEmbeddingOperation(db, "op-invalid-kind", "kb-v10", kind = "OTHER", state = "FAILED")
        }

        db.execute(
            "INSERT INTO embedding_query_vectors(space_id, query_hash, vector_blob, dimension, created_at) VALUES (?, ?, ?, ?, ?)",
            listOf("space-v10", "sha256-query", byteArrayOf(1, 2), 2, "2026-08-29T00:01:00Z"),
        )
        assertThrows(Exception::class.java) {
            db.execute(
                "INSERT INTO embedding_query_vectors(space_id, query_hash, vector_blob, dimension, created_at) VALUES (?, ?, ?, ?, ?)",
                listOf("space-v10", "sha256-query", byteArrayOf(3, 4), 2, "2026-08-29T00:02:00Z"),
            )
        }
        assertThrows(Exception::class.java) {
            db.execute(
                "INSERT INTO embedding_query_vectors(space_id, query_hash, vector_blob, dimension, created_at) VALUES (?, ?, ?, ?, ?)",
                listOf("space-v10", "sha256-invalid", byteArrayOf(1), 0, "2026-08-29T00:03:00Z"),
            )
        }

        // Re-running the migration must leave operation and vector records untouched.
        Migrations.apply(db)
        assertEquals(1, db.query("SELECT * FROM embedding_operations").size)
        assertEquals(1, db.query("SELECT * FROM embedding_query_vectors").size)
        assertEquals(Migrations.VERSION, db.query("SELECT version FROM schema_version").single().long("version").toInt())
    }

    private fun insertEmbeddingOperation(
        db: SqlConnection,
        token: String,
        kbId: String,
        kind: String = "IMPORT",
        state: String,
        cancelRequested: Int = 0,
    ) {
        db.execute(
            "INSERT INTO embedding_operations(token, kind, kb_id, job_id, document_id, document_version_id, space_id, input_manifest_hash, binding_fingerprint, consent_fingerprint, state, cancel_requested, error, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                token, kind, kbId, null, null, null, "space-v10", "manifest-hash", "binding-fingerprint",
                "consent-fingerprint", state, cancelRequested, "", "2026-08-29T00:00:00Z", "2026-08-29T00:00:00Z",
            ),
        )
    }

    @Test
    fun chatBindingUsesTheModelProviderNotNameAndModelSort() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = ProfileRepository(db)
        repo.upsertProvider(
            ProviderProfile(
                id = "alpha",
                name = "Alpha endpoint",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://alpha.example.invalid/v1",
                secretRef = "ref-alpha",
                revision = 1,
            ),
        )
        repo.upsertProvider(
            ProviderProfile(
                id = "zulu",
                name = "Zulu endpoint",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://zulu.example.invalid/v1",
                secretRef = "ref-zulu",
                revision = 1,
            ),
        )
        repo.upsertModel(
            runtime.mobileagent.domain.ModelProfile(
                id = "m-z",
                providerId = "alpha",
                role = runtime.mobileagent.domain.ModelRole.CHAT,
                modelId = "zeta-chat",
                capabilities = setOf("stream"),
                contextLimit = 8_000,
                outputLimit = 1_024,
                revision = 1,
            ),
        )
        repo.upsertModel(
            runtime.mobileagent.domain.ModelProfile(
                id = "m-a",
                providerId = "zulu",
                role = runtime.mobileagent.domain.ModelRole.CHAT,
                modelId = "alpha-chat",
                capabilities = setOf("stream"),
                contextLimit = 8_000,
                outputLimit = 1_024,
                revision = 1,
            ),
        )
        val (provider, model) = repo.chatBinding()!!
        assertEquals(provider.id, model.providerId)
        assertEquals("alpha", provider.id)
        assertEquals("zeta-chat", model.modelId)
    }

    @Test
    fun newerSchemaIsRejectedWithoutRewritingVersion() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        db.execute("DELETE FROM schema_version")
        db.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(Migrations.VERSION + 1))

        val error = assertThrows(AppException::class.java) { Migrations.apply(db) }
        assertEquals(ErrorCode.SCHEMA_UNSUPPORTED, error.error.code)
        assertEquals(Migrations.VERSION + 1, db.query("SELECT version FROM schema_version").single().long("version").toInt())
    }

    @Test
    fun nonIntegerSchemaVersionIsRejectedWithoutCoercion() {
        // INTEGER PRIMARY KEY is a rowid alias in SQLite and rejects a REAL before the
        // migration code can inspect it.  Use a deliberately damaged, non-rowid schema_version
        // fixture so the strict reader receives the REAL value and can reject it itself.
        JdbcSqlConnection().use { db ->
            db.execute("CREATE TABLE schema_version(version REAL NOT NULL)")
            db.execute("INSERT INTO schema_version(version) VALUES (?)", listOf(8.5))

            assertThrows(AppException::class.java) { Migrations.apply(db) }
            assertEquals(8.5, db.query("SELECT version FROM schema_version").single().columns["version"])
        }
    }

    @Test
    fun legacyChatVisionEmbeddingAgentAndSnapshotMigrateWithoutLoss() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            listOf("chat", "vision", "embedding", "reranker").forEach { suffix ->
                profiles.createProvider(
                    ProviderProfile(
                        id = "provider.legacy.$suffix",
                        name = "Legacy $suffix",
                        apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                        baseUrl = "https://$suffix.example.invalid/v1",
                        revision = 1,
                    ),
                )
            }
            insertLegacyModel(db, "model.legacy.chat", "provider.legacy.chat", "CHAT", "[\"stream\"]")
            insertLegacyModel(db, "model.legacy.vision", "provider.legacy.vision", "VISION", "[\"image\"]")
            insertLegacyModel(db, "model.legacy.embedding", "provider.legacy.embedding", "EMBEDDING", "[]")
            insertLegacyModel(db, "model.legacy.reranker", "provider.legacy.reranker", "RERANKER", "[]")

            val agents = AgentRepository(db)
            val agent = agents.saveWithPrompt(
                AgentProfile(
                    id = "agent.legacy",
                    name = "Legacy Agent",
                    promptRevisionId = "prompt.legacy",
                    chatProfileId = "model.legacy.chat",
                    visionProfileId = "model.legacy.vision",
                    embeddingProfileId = "model.legacy.embedding",
                    rerankerProfileId = "model.legacy.reranker",
                    revision = 0,
                ),
                template = "Legacy prompt",
            )
            val snapshot = agents.createSnapshot(agent.id, "snapshot.legacy", "2026-08-29T00:00:00Z")

            // Revert only the newly added endpoint column to the exact legacy representation.
            // The role/capability rows remain untouched and are the source of the v11 backfill.
            db.execute("UPDATE model_profiles SET endpoint_json = '{}' WHERE id LIKE 'model.legacy.%'")
            db.execute("UPDATE schema_version SET version = ?", listOf(10))
            Migrations.apply(db)

            assertEquals(Migrations.VERSION, db.query("SELECT version FROM schema_version").single().long("version").toInt())
            assertEquals(
                setOf(ModelRole.CHAT, ModelRole.VISION, ModelRole.EMBEDDING, ModelRole.RERANKER),
                profiles.listModels().map { it.role }.toSet(),
            )
            assertEquals(setOf(ModelOperation.CHAT), profiles.getModel("model.legacy.chat")!!.endpoint.operations)
            assertEquals(setOf(ModelOperation.CHAT), profiles.getModel("model.legacy.vision")!!.endpoint.operations)
            assertEquals(setOf(ModelOperation.EMBEDDING), profiles.getModel("model.legacy.embedding")!!.endpoint.operations)
            assertEquals(setOf(ModelOperation.RERANK), profiles.getModel("model.legacy.reranker")!!.endpoint.operations)
            assertEquals(setOf(InputModality.IMAGE, InputModality.TEXT), profiles.getModel("model.legacy.vision")!!.endpoint.inputModalities)

            val restored = agents.resolveSnapshot(snapshot.id)
            assertEquals(snapshot.id, restored.snapshot.id)
            assertEquals("Legacy prompt", restored.prompt.template)
            assertEquals("model.legacy.chat", restored.chatModel.id)
            assertEquals("model.legacy.vision", restored.visionModel!!.id)
            assertEquals("model.legacy.embedding", restored.embeddingModel!!.id)
            assertEquals("model.legacy.reranker", restored.rerankerModel!!.id)
        }
    }

    @Test
    fun malformedLegacyRoleCapabilitiesAndEndpointFailClosedAndKeepVersion() {
        val cases = listOf(
            Triple("NOT_A_ROLE", "[]", "{}"),
            Triple("CHAT", "{}", "{}"),
            Triple("CHAT", "[]", "{not-json"),
            Triple(
                "CHAT",
                "[]",
                "{\"operations\":[],\"inputModalities\":[\"TEXT\"],\"features\":[],\"verification\":\"USER_DECLARED\"}",
            ),
            Triple(
                "CHAT",
                "[]",
                "{\"operations\":[\"EMBEDDING\"],\"inputModalities\":[\"TEXT\"],\"features\":[],\"verification\":\"USER_DECLARED\"}",
            ),
        )
        cases.forEachIndexed { index, (role, capabilities, endpoint) ->
            JdbcSqlConnection().use { db ->
                Migrations.apply(db)
                db.execute(
                    "INSERT INTO provider_profiles(id,name,api_format,base_url,header_secret_refs,non_secret_headers,secret_ref,revision) VALUES(?,?,?,?,?,?,?,?)",
                    listOf("provider.bad.$index", "Bad $index", "OPENAI_COMPATIBLE", "https://bad.example.invalid", "{}", "{}", "", 1),
                )
                insertLegacyModel(
                    db,
                    id = "model.bad.$index",
                    providerId = "provider.bad.$index",
                    role = role,
                    capabilities = capabilities,
                    endpoint = endpoint,
                )
                db.execute("UPDATE schema_version SET version = ?", listOf(10))

                assertThrows(AppException::class.java) { Migrations.apply(db) }
                assertEquals(10, db.query("SELECT version FROM schema_version").single().long("version").toInt())
                assertEquals(
                    role,
                    db.query("SELECT role FROM model_profiles WHERE id = ?", listOf("model.bad.$index")).single().string("role"),
                )
                assertEquals(
                    capabilities,
                    db.query("SELECT capabilities FROM model_profiles WHERE id = ?", listOf("model.bad.$index")).single().string("capabilities"),
                )
                assertEquals(
                    endpoint,
                    db.query("SELECT endpoint_json FROM model_profiles WHERE id = ?", listOf("model.bad.$index")).single().string("endpoint_json"),
                )
            }
        }
    }

    @Test
    fun metadataOnlyProbeDoesNotPromoteEndpointAndPerCapabilityResultsStayAuditable() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(
                ProviderProfile(
                    id = "provider.probe",
                    name = "Probe",
                    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                    baseUrl = "https://probe.example.invalid/v1",
                    revision = 1,
                ),
            )
            profiles.createModel(
                ModelProfile(
                    id = "model.probe",
                    providerId = "provider.probe",
                    role = ModelRole.CHAT,
                    modelId = "probe-chat",
                    capabilities = setOf("stream"),
                    contextLimit = 4_096,
                    outputLimit = 1_024,
                    revision = 1,
                ),
            )

            profiles.recordProbe(
                modelId = "model.probe",
                providerRevision = 1,
                toolsSummary = "not-declared",
                imagesSummary = "not-declared",
                source = "metadata=verified;stream=not-declared;tools=not-declared;image=not-declared",
                probed = true,
            )
            assertEquals(CapabilityVerification.UNKNOWN, profiles.getModel("model.probe")!!.endpoint.verification)
            assertEquals("not-declared", db.query("SELECT tools_summary FROM capability_probes").single().string("tools_summary"))
            assertEquals("PROBED", db.query("SELECT verification FROM capability_probes").single().string("verification"))

            profiles.recordProbe(
                modelId = "model.probe",
                providerRevision = 1,
                toolsSummary = "not-declared",
                imagesSummary = "not-declared",
                source = "metadata=verified;stream=verified;tools=not-declared;image=not-declared",
                probed = true,
            )
            assertEquals(CapabilityVerification.PROBED, profiles.getModel("model.probe")!!.endpoint.verification)

            profiles.recordProbe(
                modelId = "model.probe",
                providerRevision = 1,
                toolsSummary = "http-400",
                imagesSummary = "not-declared",
                source = "metadata=verified;stream=verified;tools=http-400;image=not-declared",
                probed = false,
            )
            assertEquals(CapabilityVerification.UNKNOWN, profiles.getModel("model.probe")!!.endpoint.verification)
            val latest = db.query("SELECT tools_summary, images_summary, source FROM capability_probes ORDER BY probed_at DESC LIMIT 1").single()
            assertEquals("http-400", latest.string("tools_summary"))
            assertEquals("not-declared", latest.string("images_summary"))
            assertTrue(latest.string("source").contains("stream=verified"))
        }
    }

    @Test
    fun malformedImmutableSnapshotFragmentFailsClosedAndKeepsSchemaVersion() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val badManifest = """
                {"snapshotId":"snapshot.bad","chatModel":{
                  "id":"model.bad.snapshot","providerId":"provider.bad.snapshot","role":"CHAT",
                  "modelId":"chat","capabilities":[],"parameterSchemaJson":"{}",
                  "contextLimit":4096,"outputLimit":1024,"revision":1,"parametersJson":"{}",
                  "endpoint":{"operations":["EMBEDDING"],"inputModalities":["TEXT"],"features":[],"verification":"USER_DECLARED"}
                }}
            """.trimIndent()
            db.execute(
                "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,vision_model_id,vision_model_revision,embedding_model_id,embedding_model_revision,reranker_model_id,reranker_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(
                    "snapshot.bad", 10, "agent.bad", "prompt.bad", "model.bad.snapshot", 1, "[]", "[]",
                    "2026-08-29T00:00:00Z", "provider.bad.snapshot", 1, null, null, null, null, null, null,
                    "{}", "{}", "{}", badManifest, "{}",
                ),
            )
            db.execute("UPDATE schema_version SET version = ?", listOf(10))

            assertThrows(AppException::class.java) { Migrations.apply(db) }
            assertEquals(10, db.query("SELECT version FROM schema_version").single().long("version").toInt())
            assertEquals(
                badManifest,
                db.query("SELECT binding_manifest_json FROM agent_snapshots WHERE id = ?", listOf("snapshot.bad"))
                    .single().string("binding_manifest_json"),
            )
        }
    }

    private fun insertLegacyModel(
        db: SqlConnection,
        id: String,
        providerId: String,
        role: String,
        capabilities: String,
        endpoint: String = "{}",
    ) {
        db.execute(
            "INSERT INTO model_profiles(id,provider_id,role,model_id,capabilities,parameter_schema_json,parameters_json,context_limit,output_limit,revision,endpoint_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            listOf(id, providerId, role, id, capabilities, "{}", "{}", 4_096, 1_024, 1, endpoint),
        )
    }
}
