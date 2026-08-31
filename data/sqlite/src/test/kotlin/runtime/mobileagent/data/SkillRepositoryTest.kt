// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.skills.CompatibilityClass
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private fun instructionOnlyPackageBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write("# Instruction-only test skill\nUse this local instruction only.\n".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
