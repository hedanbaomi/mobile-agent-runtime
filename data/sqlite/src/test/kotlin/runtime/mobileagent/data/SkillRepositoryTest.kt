// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
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
        assertThrows(IllegalArgumentException::class.java) { repository.setEnabled(id, true) }
        repository.importPackage(packageBytes())
        assertTrue(repository.grantForInvocation(id, setOf(id), setOf("kb-a")).revoked)
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

    private fun database(block: (SqlConnection) -> Unit) {
        JdbcSqlConnection("jdbc:sqlite::memory:").use { db ->
            Migrations.apply(db)
            block(db)
        }
    }

    private fun packageBytes(): ByteArray {
        val files = mapOf(
            "SKILL.md" to "# Test skill\nUser-selected local test package.",
            "scripts/main.py" to "def run(ctx, arguments):\n    return {\"ok\": True}\n",
            "mobile-skill.json" to """{
              "schemaVersion":1,"id":"test.scoped","name":"Scoped test","version":"1.0.0","license":"AGPL-3.0-only",
              "runtime":{"kind":"python","python":"3.14","entrypoint":"scripts.main:run","mode":"pure-python"},
              "permissions":{"knowledge.search":{"scope":"selected-by-user"},"network.http":{"hosts":["example.com"],"methods":["GET"]}}
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
}
