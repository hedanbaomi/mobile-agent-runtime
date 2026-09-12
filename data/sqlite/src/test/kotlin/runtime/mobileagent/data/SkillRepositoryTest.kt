// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.skills.CompatibilityClass
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.serialization.TransferBundle
import runtime.mobileagent.serialization.TransferCodec
import runtime.mobileagent.serialization.TransferConflictPolicy
import runtime.mobileagent.serialization.TransferOptions

class SkillRepositoryTest {
    @Test
    fun importDoesNotAuthorizeOrExecutePackage() = database { db ->
        val repository = SkillRepository(db)
        assertTrue(repository.importPackage(packageBytes()).accepted)
        val skill = repository.list().single()
        assertFalse(skill.enabled)
        assertTrue(repository.effectiveGrant().revoked)
        assertThrows(IllegalArgumentException::class.java) { repository.setEnabled(skill.installId, true) }
    }

    @Test
    fun explicitGrantRestoresActualScopesAndRevocationWins() = database { db ->
        db.execute("INSERT INTO knowledge_bases(id,name,created_at) VALUES (?,?,?)", listOf("kb-a", "A", "2026-08-29T00:00:00Z"))
        val repository = SkillRepository(db)
        repository.importPackage(packageBytes())
        val id = repository.list().single().installId
        repository.approvePermissions(id, setOf("knowledge.search", "network.http"), setOf("kb-a"), setOf("example.com"), setOf("GET"))
        repository.setEnabled(id, true)
        val grant = repository.grantForInvocation(id, setOf(id), setOf("kb-a"))
        assertEquals(setOf("kb-a"), grant.knowledgeBaseIds)
        assertEquals(setOf("example.com"), grant.hosts)
        assertEquals(setOf("GET"), grant.methods)
        repository.revoke(id)
        assertTrue(repository.grantForInvocation(id, setOf(id), setOf("kb-a")).revoked)
        val revoked = db.query("SELECT lifetime, created_at, revoked_at FROM permission_grants WHERE install_id = ?", listOf(id)).single()
        assertEquals("PERSISTENT", revoked.string("lifetime"))
        assertTrue(revoked.string("created_at").isNotBlank())
        assertTrue(revoked.string("revoked_at").isNotBlank())
        assertThrows(IllegalArgumentException::class.java) { repository.setEnabled(id, true) }
        repository.importPackage(packageBytes())
        assertTrue(repository.grantForInvocation(id, setOf(id), setOf("kb-a")).revoked)
    }

    @Test
    fun legacySkillGrantNeverTreatsScopedLifetimeAsCapabilityAuthority() = database { db ->
        val repository = SkillRepository(db)
        repository.importPackage(packageBytes())
        val id = repository.list().single().installId
        repository.approvePermissions(id, emptySet())
        repository.setEnabled(id, true)
        db.execute(
            "UPDATE permission_grants SET lifetime = 'TASK', revoked = 0, revoked_at = NULL WHERE install_id = ?",
            listOf(id),
        )

        // Legacy PermissionGrant has no task/session identity.  A malformed
        // scoped row is therefore ignored, never widened into a persistent grant.
        assertTrue(repository.effectiveGrant().revoked)
        assertTrue(repository.grantsFor(id).isEmpty())
    }

    @Test
    fun scopesCannotExceedPackageOrAgentBinding() = database { db ->
        db.execute("INSERT INTO knowledge_bases(id,name,created_at) VALUES (?,?,?)", listOf("kb-a", "A", "2026-08-29T00:00:00Z"))
        val repository = SkillRepository(db)
        repository.importPackage(packageBytes())
        val id = repository.list().single().installId
        assertThrows(IllegalArgumentException::class.java) {
            repository.approvePermissions(id, setOf("model.invoke"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.approvePermissions(id, setOf("network.http"), hosts = setOf("not-declared.example"), methods = setOf("GET"))
        }
        repository.approvePermissions(id, setOf("knowledge.search"), setOf("kb-a"))
        repository.setEnabled(id, true)
        assertTrue(repository.grantForInvocation(id, emptySet(), setOf("kb-a")).revoked)
        assertTrue(repository.grantForInvocation(id, setOf(id), emptySet()).knowledgeBaseIds.isEmpty())
    }

    @Test
    fun sourceViewerIsPackageBoundAndChecksStoredHash() = database { db ->
        val repository = SkillRepository(db)
        repository.importPackage(packageBytes())
        val skill = repository.list().single()
        assertTrue(repository.sourceFiles(skill.installId).contains("scripts/main.py"))
        assertTrue(repository.sourceText(skill.installId, "scripts/main.py").contains("def run"))
        assertThrows(IllegalArgumentException::class.java) { repository.sourceText(skill.installId, "../private") }
        db.execute("UPDATE skill_packages SET package_bytes = ? WHERE package_hash = ?", listOf("changed".toByteArray(), skill.packageHash))
        assertThrows(IllegalArgumentException::class.java) { repository.inspect(skill.installId) }
    }

    @Test
    fun sourceViewerMatchesUnicodeEntryNamesUsingArchiveNormalization() = database { db ->
        val repository = SkillRepository(db)
        val nfdName = "scripts/" + Normalizer.normalize("résumé.py", Normalizer.Form.NFD)
        repository.importPackage(packageBytes(mapOf(nfdName to "def run(ctx, arguments):\n    return {'ok': True}\n")))
        val skill = repository.list().single()
        val normalizedName = Normalizer.normalize(nfdName, Normalizer.Form.NFC)

        assertTrue(repository.sourceFiles(skill.installId).contains(normalizedName))
        assertTrue(repository.sourceText(skill.installId, normalizedName).contains("def run"))
    }

    @Test
    fun enabledSkillCanBeBoundToAgentByInstallId() = database { db ->
        val skills = SkillRepository(db)
        assertTrue(skills.importPackage(packageBytes()).accepted)
        val installId = skills.list().single().installId
        skills.approvePermissions(installId, emptySet())
        skills.setEnabled(installId, true)

        val profiles = ProfileRepository(db)
        profiles.createProvider(
            ProviderProfile(
                id = "provider.skills",
                name = "Skill binding fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-secret",
                revision = 1,
            ),
        )
        profiles.createModel(
            ModelProfile(
                id = "model.skills.chat",
                providerId = "provider.skills",
                role = ModelRole.CHAT,
                modelId = "fixture-chat",
                capabilities = setOf("stream"),
                contextLimit = 8_192,
                outputLimit = 1_024,
                revision = 1,
            ),
        )

        val saved = AgentRepository(db).saveWithPrompt(
            AgentProfile(
                id = "agent.skills",
                name = "Skill binding agent",
                promptRevisionId = "created-atomically",
                chatProfileId = "model.skills.chat",
                skillIds = listOf(installId),
                revision = 0,
            ),
            "Use only the explicitly bound skill.",
        )

        assertEquals(listOf(installId), saved.skillIds)
        assertEquals(listOf(installId), AgentRepository(db).createSnapshot(saved.id).skillIds)
    }

    @Test
    fun disabledSkillInstallIdCannotBeBoundToAgent() = database { db ->
        val skills = SkillRepository(db)
        assertTrue(skills.importPackage(packageBytes()).accepted)
        val installId = skills.list().single().installId
        assertFalse(skills.get(installId)!!.enabled)

        createChatProfile(db)
        val error = assertThrows(AppException::class.java) {
            AgentRepository(db).saveWithPrompt(
                agentProfile("agent.disabled-skill", "model.skills.chat", installId),
                "Disabled skills must not be bound.",
            )
        }

        assertTrue(error.message.orEmpty().contains("missing or disabled"))
        assertEquals(0, db.query("SELECT COUNT(*) AS count FROM agent_profiles").single().long("count"))
        assertEquals(0, db.query("SELECT COUNT(*) AS count FROM prompt_revisions").single().long("count"))
    }

    @Test
    fun instructionOnlySkillCanBeBoundByEnabledInstallId() = database { db ->
        val skills = SkillRepository(db)
        assertTrue(skills.importPackage(instructionOnlyPackageBytes()).accepted)
        val installed = skills.list().single()
        assertEquals(CompatibilityClass.A, installed.classification)
        skills.setEnabled(installed.installId, true)

        createChatProfile(db)
        val saved = AgentRepository(db).saveWithPrompt(
            agentProfile("agent.instruction-only", "model.skills.chat", installed.installId),
            "Use the instruction-only skill.",
        )

        assertEquals(listOf(installed.installId), saved.skillIds)
    }

    @Test
    fun exportResolvesBoundSkillByInstallId() = database { db ->
        val skills = SkillRepository(db)
        assertTrue(skills.importPackage(instructionOnlyPackageBytes()).accepted)
        val installed = skills.list().single()
        skills.setEnabled(installed.installId, true)
        createChatProfile(db)
        val saved = AgentRepository(db).saveWithPrompt(
            agentProfile("agent.export-skill", "model.skills.chat", installed.installId),
            "Export the bound skill.",
        )
        val raw = TransferRepository(db).exportAgent(saved.id)
        assertTrue(raw.contains(installed.packageHash))
        assertFalse(raw.contains("missing skill"))
    }

    @Test
    fun agentSkillBackupRemapsInstallIdentityOnEmptyTarget() = database { source ->
        val skills = SkillRepository(source)
        assertTrue(skills.importPackage(instructionOnlyPackageBytes()).accepted)
        val installed = skills.list().single()
        skills.setEnabled(installed.installId, true)
        val packageId = source.query("SELECT id FROM skill_packages WHERE package_hash=?", listOf(installed.packageHash)).single().string("id")
        createChatProfile(source)
        val saved = AgentRepository(source).saveWithPrompt(
            agentProfile("agent.restore-skill", "model.skills.chat", installed.installId),
            "Export the bound skill.",
        )
        val raw = TransferRepository(source).exportAgent(saved.id, includeSkillPackageBytes = true)
        val exported = TransferCodec.decode(raw)
        assertEquals(installed.installId, exported.skills.single().sourceInstallId)
        assertEquals(packageId, exported.skills.single().id)
        assertEquals(listOf(installed.installId), exported.agent!!.profile.skillIds)

        JdbcSqlConnection("jdbc:sqlite::memory:").use { target ->
            Migrations.apply(target)
            val result = TransferRepository(target).importBundle(raw, TransferConflictPolicy.REJECT)
            val restored = AgentRepository(target).get(saved.id)!!
            val local = target.query("SELECT install_id, enabled FROM skill_installs").single()
            val localInstallId = local.string("install_id")
            assertEquals(listOf(localInstallId), restored.skillIds)
            assertTrue(localInstallId != installed.installId)
            assertTrue(localInstallId != packageId)
            assertEquals(0L, local.long("enabled"))
            assertEquals(0L, target.query("SELECT COUNT(*) AS n FROM permission_grants WHERE revoked=0").single().long("n"))
            assertTrue(result.warnings.any { it.contains("enable") || it.contains("grant") })
        }
    }

    @Test
    fun historicalUnboundSkillIsExportedAndRestoredWithoutRebindingTheAgent() = database { source ->
        val skills = SkillRepository(source)
        assertTrue(skills.importPackage(instructionOnlyPackageBytes("Skill A")).accepted)
        val skillA = skills.list().single()
        assertTrue(skills.importPackage(instructionOnlyPackageBytes("Skill B")).accepted)
        val skillB = skills.list().single { it.installId != skillA.installId }
        skills.setEnabled(skillA.installId, true)
        skills.setEnabled(skillB.installId, true)
        createChatProfile(source)
        val agents = AgentRepository(source)
        val boundA = agents.saveWithPrompt(
            agentProfile("agent.history-skill", "model.skills.chat", skillA.installId),
            "Use skill A.",
        )
        val snapshot = agents.createSnapshot(boundA.id, "snapshot.history-a", "2026-08-29T00:00:00Z")
        val conversation = ConversationRepository(source).create(
            snapshot.id,
            "Historical A thread",
            "conversation.history-a",
            "2026-08-29T00:00:01Z",
        )
        ConversationRepository(source).append(
            conversation.id,
            MessageRole.USER,
            "hello from A",
            messageId = "message.history-a",
            createdAt = "2026-08-29T00:00:02Z",
        )
        val boundB = agents.saveWithPrompt(
            boundA.copy(skillIds = listOf(skillB.installId), revision = boundA.revision),
            "Use skill B instead.",
        )
        assertEquals(listOf(skillB.installId), boundB.skillIds)

        val output = ByteArrayOutputStream()
        TransferRepository(source).exportArchive(
            boundB.id,
            TransferOptions(includeSkillPackageBytes = true, includeConversations = true),
            output,
        )
        val exported = TransferCodec.decode(readArchiveManifest(output.toByteArray()))
        assertEquals(listOf(skillB.installId), exported.agent!!.profile.skillIds)
        assertEquals(2, exported.skills.size)
        assertTrue(exported.skills.any { it.sourceInstallId == skillA.installId || skillA.installId in it.sourceInstallIds })
        assertTrue(exported.skills.any { it.sourceInstallId == skillB.installId || skillB.installId in it.sourceInstallIds })

        JdbcSqlConnection("jdbc:sqlite::memory:").use { target ->
            Migrations.apply(target)
            val result = TransferRepository(target).importArchive(output.toByteArray(), TransferConflictPolicy.REJECT)
            val restored = AgentRepository(target).get(boundB.id)!!
            val restoredSnapshot = AgentRepository(target).getSnapshot(snapshot.id)!!
            val installs = target.query("SELECT install_id, package_hash, enabled FROM skill_installs")
            assertEquals(2, installs.size)
            assertTrue(installs.all { it.long("enabled") == 0L })
            val localByHash = installs.associate { it.string("package_hash") to it.string("install_id") }
            assertEquals(listOf(localByHash.getValue(skillB.packageHash)), restored.skillIds)
            assertEquals(listOf(localByHash.getValue(skillA.packageHash)), restoredSnapshot.skillIds)
            assertEquals("hello from A", ConversationRepository(target).messages(conversation.id).single().text)
            assertEquals(0L, target.query("SELECT COUNT(*) AS n FROM permission_grants WHERE revoked=0").single().long("n"))
            assertTrue(result.warnings.any { it.contains("enable") || it.contains("grant") })
        }
    }

    @Test
    fun distinctSkillVersionsWithTheSamePackageIdRestoreByInstallIdentity() = database { source ->
        val archive = exportSharedPackageVersionHistory(source)
        assertSharedPackageVersionHistoryRestored(archive)
    }

    @Test
    fun distinctSkillVersionsRestoreWhenArchiveSkillOrderIsReversed() = database { source ->
        val archive = exportSharedPackageVersionHistory(source)
        val reversedBytes = rewriteArchive(archive.bytes) { it.copy(skills = it.skills.reversed()) }
        val reversedManifest = TransferCodec.decode(readArchiveManifest(reversedBytes))
        assertEquals(archive.exported.skills.map { it.packageHash }.reversed(), reversedManifest.skills.map { it.packageHash })
        assertSharedPackageVersionHistoryRestored(archive.copy(bytes = reversedBytes, exported = reversedManifest))
    }

    @Test
    fun ambiguousPackageIdOnlyAgentBindingIsRejectedWhenVersionsShareAnId() = database { source ->
        val archive = exportSharedPackageVersionHistory(source)
        val poisoned = rewriteArchive(archive.bytes) { bundle ->
            val agent = checkNotNull(bundle.agent)
            bundle.copy(
                agent = agent.copy(
                    profile = agent.profile.copy(skillIds = listOf(SHARED_SKILL_PACKAGE_ID)),
                ),
            )
        }
        JdbcSqlConnection("jdbc:sqlite::memory:").use { target ->
            Migrations.apply(target)
            val error = assertThrows(AppException::class.java) {
                TransferRepository(target).importArchive(poisoned, TransferConflictPolicy.REJECT)
            }
            assertEquals(ErrorCode.TRANSFER_INVALID, error.error.code)
            assertTrue(error.message.orEmpty().contains("ambiguous"))
            assertEquals(0L, target.query("SELECT COUNT(*) AS n FROM agent_profiles").single().long("n"))
            assertEquals(0L, target.query("SELECT COUNT(*) AS n FROM skill_installs").single().long("n"))
            assertEquals(0L, target.query("SELECT COUNT(*) AS n FROM conversations").single().long("n"))
        }
    }

    @Test
    fun rawInstructionSkillUsesFrontmatterName() = database { db ->
        val skills = SkillRepository(db)
        val raw = """
            ---
            name: josephine-mccarthy-perspective
            description: Local instruction fixture
            ---
            # Perspective
            Keep this instruction available to the selected agent.
        """.trimIndent().toByteArray()

        assertTrue(skills.importPackage(raw).accepted)
        val installed = skills.list().single()
        assertEquals(CompatibilityClass.A, installed.classification)
        assertEquals("josephine-mccarthy-perspective", installed.name)
    }

    @Test
    fun enablingRawInstructionSkillRestoresEmptyPersistentGrant() = database { db ->
        val skills = SkillRepository(db)
        val raw = """
            ---
            name: raw-instruction-fixture
            ---
            # Perspective
            Keep this instruction available to the selected agent.
        """.trimIndent().toByteArray()

        assertTrue(skills.importPackage(raw).accepted)
        val installed = skills.list().single()
        assertEquals(CompatibilityClass.A, installed.classification)
        assertTrue(skills.grantsFor(installed.installId).single().revoked)

        skills.setEnabled(installed.installId, true)

        val grant = skills.grantsFor(installed.installId).single { !it.revoked && it.packageHash == installed.packageHash }
        assertFalse(grant.revoked)
        assertEquals(installed.packageHash, grant.packageHash)
        assertTrue(grant.capabilities.isEmpty())
        assertFalse(skills.grantForInvocation(installed.installId, setOf(installed.installId), emptySet()).revoked)
    }

    @Test
    fun enablingInstructionSkillRevokesDuplicateCurrentPackageGrants() = database { db ->
        val skills = SkillRepository(db)
        assertTrue(skills.importPackage(instructionOnlyPackageBytes()).accepted)
        val installed = skills.list().single()
        val now = "2026-09-09T00:00:00Z"
        db.execute(
            "DELETE FROM permission_grants WHERE install_id = ? AND package_hash = ?",
            listOf(installed.installId, installed.packageHash),
        )
        listOf(
            "duplicate-a" to "network.http",
            "duplicate-b" to "knowledge.search",
        ).forEach { (grantId, capability) ->
            db.execute(
                "INSERT INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked,scopes_json,lifetime,policy_version,created_at,expires_at,revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(
                    grantId, installed.installId, installed.packageHash, capability, 1, 0,
                    "{\"capabilities\":[\"$capability\"],\"knowledgeBaseIds\":[],\"hosts\":[],\"methods\":[]}",
                    "PERSISTENT", 0, now, null, null,
                ),
            )
        }

        skills.setEnabled(installed.installId, true)

        val active = db.query(
            "SELECT * FROM permission_grants WHERE install_id = ? AND package_hash = ? AND lifetime = 'PERSISTENT' AND revoked = 0",
            listOf(installed.installId, installed.packageHash),
        )
        assertEquals(1, active.size)
        assertEquals("", active.single().string("capabilities"))
        assertTrue(skills.grantForInvocation(installed.installId, setOf(installed.installId), emptySet()).capabilities.isEmpty())
    }

    @Test
    fun enablingInstructionSkillReplacesMalformedCurrentPackageScope() = database { db ->
        val skills = SkillRepository(db)
        assertTrue(skills.importPackage(instructionOnlyPackageBytes()).accepted)
        val installed = skills.list().single()
        val now = "2026-09-09T00:00:00Z"
        db.execute(
            "DELETE FROM permission_grants WHERE install_id = ? AND package_hash = ?",
            listOf(installed.installId, installed.packageHash),
        )
        db.execute(
            "INSERT INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked,scopes_json,lifetime,policy_version,created_at,expires_at,revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                "malformed-scope", installed.installId, installed.packageHash, "", 3, 1,
                "{not-json", "PERSISTENT", 0, now, null, now,
            ),
        )

        skills.setEnabled(installed.installId, true)

        val active = db.query(
            "SELECT * FROM permission_grants WHERE install_id = ? AND package_hash = ? AND lifetime = 'PERSISTENT' AND revoked = 0",
            listOf(installed.installId, installed.packageHash),
        )
        assertEquals(1, active.size)
        assertEquals("{\"capabilities\":[],\"knowledgeBaseIds\":[],\"hosts\":[],\"methods\":[]}", active.single().string("scopes_json"))
    }

    @Test
    fun enablingInstructionSkillReplacesExpiredEmptyGrant() = database { db ->
        val skills = SkillRepository(db)
        assertTrue(skills.importPackage(instructionOnlyPackageBytes()).accepted)
        val installed = skills.list().single()
        db.execute(
            "DELETE FROM permission_grants WHERE install_id = ? AND package_hash = ?",
            listOf(installed.installId, installed.packageHash),
        )
        db.execute(
            "INSERT INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked,scopes_json,lifetime,policy_version,created_at,expires_at,revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                "expired-empty", installed.installId, installed.packageHash, "", 2, 0,
                "{\"capabilities\":[],\"knowledgeBaseIds\":[],\"hosts\":[],\"methods\":[]}",
                "PERSISTENT", 0, "2026-09-01T00:00:00Z", "2000-01-01T00:00:00Z", null,
            ),
        )

        skills.setEnabled(installed.installId, true)

        val active = db.query(
            "SELECT * FROM permission_grants WHERE install_id = ? AND package_hash = ? AND lifetime = 'PERSISTENT' AND revoked = 0",
            listOf(installed.installId, installed.packageHash),
        )
        assertEquals(1, active.size)
        assertTrue(active.single().string("expires_at").isBlank())
        assertEquals("{\"capabilities\":[],\"knowledgeBaseIds\":[],\"hosts\":[],\"methods\":[]}", active.single().string("scopes_json"))
    }

    private data class SharedPackageVersionArchive(
        val bytes: ByteArray,
        val exported: TransferBundle,
        val agentId: String,
        val snapshotId: String,
        val conversationId: String,
        val v1Hash: String,
        val v2Hash: String,
    )

    private fun exportSharedPackageVersionHistory(source: SqlConnection): SharedPackageVersionArchive {
        val skills = SkillRepository(source)
        assertTrue(skills.importPackage(versionedInstructionPackageBytes(SHARED_SKILL_PACKAGE_ID, "1.0.0", "Shared v1")).accepted)
        val skillV1 = skills.list().single()
        skills.setEnabled(skillV1.installId, true)
        createChatProfile(source)
        val agents = AgentRepository(source)
        val boundV1 = agents.saveWithPrompt(
            agentProfile("agent.shared-skill", "model.skills.chat", skillV1.installId),
            "Use shared skill v1.",
        )
        val snapshot = agents.createSnapshot(boundV1.id, "snapshot.shared-v1", "2026-08-29T00:00:00Z")
        val conversation = ConversationRepository(source).create(
            snapshot.id,
            "Historical shared v1 thread",
            "conversation.shared-v1",
            "2026-08-29T00:00:01Z",
        )
        ConversationRepository(source).append(
            conversation.id,
            MessageRole.USER,
            "hello from shared v1",
            messageId = "message.shared-v1",
            createdAt = "2026-08-29T00:00:02Z",
        )
        assertTrue(skills.importPackage(versionedInstructionPackageBytes(SHARED_SKILL_PACKAGE_ID, "2.0.0", "Shared v2")).accepted)
        val skillV2 = skills.list().single { it.installId != skillV1.installId }
        skills.setEnabled(skillV2.installId, true)
        val boundV2 = agents.saveWithPrompt(
            boundV1.copy(skillIds = listOf(skillV2.installId), revision = boundV1.revision),
            "Use shared skill v2.",
        )
        assertEquals(listOf(skillV2.installId), boundV2.skillIds)
        val packageIds = source.query("SELECT package_hash, id FROM skill_packages").associate { it.string("package_hash") to it.string("id") }
        assertEquals(SHARED_SKILL_PACKAGE_ID, packageIds.getValue(skillV1.packageHash))
        assertEquals(SHARED_SKILL_PACKAGE_ID, packageIds.getValue(skillV2.packageHash))
        assertTrue(skillV1.packageHash != skillV2.packageHash)

        val output = ByteArrayOutputStream()
        TransferRepository(source).exportArchive(
            boundV2.id,
            TransferOptions(includeSkillPackageBytes = true, includeConversations = true),
            output,
        )
        val bytes = output.toByteArray()
        val exported = TransferCodec.decode(readArchiveManifest(bytes))
        assertEquals(listOf(skillV2.installId), exported.agent!!.profile.skillIds)
        assertEquals(2, exported.skills.size)
        assertTrue(exported.skills.all { it.id == SHARED_SKILL_PACKAGE_ID })
        assertTrue(exported.skills.any { it.sourceInstallId == skillV1.installId || skillV1.installId in it.sourceInstallIds })
        assertTrue(exported.skills.any { it.sourceInstallId == skillV2.installId || skillV2.installId in it.sourceInstallIds })
        return SharedPackageVersionArchive(
            bytes = bytes,
            exported = exported,
            agentId = boundV2.id,
            snapshotId = snapshot.id,
            conversationId = conversation.id,
            v1Hash = skillV1.packageHash,
            v2Hash = skillV2.packageHash,
        )
    }

    private fun assertSharedPackageVersionHistoryRestored(archive: SharedPackageVersionArchive) {
        JdbcSqlConnection("jdbc:sqlite::memory:").use { target ->
            Migrations.apply(target)
            val result = TransferRepository(target).importArchive(archive.bytes, TransferConflictPolicy.REJECT)
            val restored = AgentRepository(target).get(archive.agentId)!!
            val restoredSnapshot = AgentRepository(target).getSnapshot(archive.snapshotId)!!
            val installs = target.query("SELECT install_id, package_hash, enabled FROM skill_installs")
            assertEquals(2, installs.size)
            assertTrue(installs.all { it.long("enabled") == 0L })
            val localByHash = installs.associate { it.string("package_hash") to it.string("install_id") }
            assertEquals(listOf(localByHash.getValue(archive.v2Hash)), restored.skillIds)
            assertEquals(listOf(localByHash.getValue(archive.v1Hash)), restoredSnapshot.skillIds)
            assertTrue(restored.skillIds.single() != SHARED_SKILL_PACKAGE_ID)
            assertTrue(restoredSnapshot.skillIds.single() != SHARED_SKILL_PACKAGE_ID)
            assertEquals("hello from shared v1", ConversationRepository(target).messages(archive.conversationId).single().text)
            assertEquals(0L, target.query("SELECT COUNT(*) AS n FROM permission_grants WHERE revoked=0").single().long("n"))
            assertTrue(result.warnings.any { it.contains("enable") || it.contains("grant") })
        }
    }

    private fun rewriteArchive(bytes: ByteArray, transform: (TransferBundle) -> TransferBundle): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val original = TransferCodec.decode(entries.getValue("manifest.json").decodeToString())
        entries["manifest.json"] = TransferCodec.encode(transform(original)).toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun createChatProfile(db: SqlConnection) {
        val profiles = ProfileRepository(db)
        profiles.createProvider(
            ProviderProfile(
                id = "provider.skills",
                name = "Skill binding fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-secret",
                revision = 1,
            ),
        )
        profiles.createModel(
            ModelProfile(
                id = "model.skills.chat",
                providerId = "provider.skills",
                role = ModelRole.CHAT,
                modelId = "fixture-chat",
                capabilities = setOf("stream"),
                contextLimit = 8_192,
                outputLimit = 1_024,
                revision = 1,
            ),
        )
    }

    private fun agentProfile(id: String, chatProfileId: String, skillId: String) = AgentProfile(
        id = id,
        name = "Skill binding agent",
        promptRevisionId = "created-atomically",
        chatProfileId = chatProfileId,
        skillIds = listOf(skillId),
        revision = 0,
    )

    private fun database(block: (SqlConnection) -> Unit) {
        JdbcSqlConnection("jdbc:sqlite::memory:").use { db ->
            Migrations.apply(db)
            block(db)
        }
    }

    private fun packageBytes(extraFiles: Map<String, String> = emptyMap()): ByteArray {
        val files = mapOf(
            "SKILL.md" to "# Test skill\nUser-selected local test package.",
            "scripts/main.py" to "def run(ctx, arguments):\n    return {\"ok\": True}\n",
            "mobile-skill.json" to """{
              "schemaVersion":1,"id":"test.scoped","name":"Scoped test","version":"1.0.0","license":"AGPL-3.0-only",
              "runtime":{"kind":"python","python":"3.14","entrypoint":"scripts.main:run","mode":"pure-python"},
              "permissions":{"knowledge.search":{"scope":"selected-by-user"},"network.http":{"hosts":["example.com"],"methods":["GET"]}}
            }""".trimIndent(),
        ) + extraFiles
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readArchiveManifest(bytes: ByteArray): String {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") return zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        error("Archive is missing manifest.json")
    }

    private fun instructionOnlyPackageBytes(title: String = "Instruction-only test skill"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write("# $title\nUse this local instruction only.\n".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun versionedInstructionPackageBytes(packageId: String, version: String, title: String): ByteArray {
        val files = mapOf(
            "SKILL.md" to "# $title\nUse this local instruction only. Version $version.\n",
            "mobile-skill.json" to """{
              "schemaVersion":1,"id":"$packageId","name":"$title","version":"$version","license":"AGPL-3.0-only",
              "runtime":{"kind":"instruction"}
            }""".trimIndent(),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val SHARED_SKILL_PACKAGE_ID = "skill.shared"
    }
}
