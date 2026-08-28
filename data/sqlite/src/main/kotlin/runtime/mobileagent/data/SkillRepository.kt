// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.skills.CompatibilityClass
import runtime.mobileagent.skills.PermissionGrant
import runtime.mobileagent.skills.SkillInstallResult
import runtime.mobileagent.skills.SkillInstaller

data class InstalledSkill(
    val installId: String,
    val packageHash: String,
    val name: String,
    val classification: CompatibilityClass,
    val enabled: Boolean,
    val skillMarkdown: String?,
    val reasons: List<String>,
    val license: String,
)

class SkillRepository(private val db: SqlConnection) {
    private val json = Json { ignoreUnknownKeys = true }

    fun importPackage(bytes: ByteArray, expectedHash: String? = null, enable: Boolean = false): SkillInstallResult {
        val result = SkillInstaller.install(bytes, expectedHash)
        if (!result.accepted) return result
        val inspection = result.inspection
        val manifest = inspection.manifest
        val manifestJson = inspection.rawManifestJson
            ?: manifest?.let { """{"id":"${it.id}","name":"${it.name}","version":"${it.version}","license":"${it.license}"}""" }
            ?: "{}"
        db.transaction {
            db.execute(
                "INSERT OR REPLACE INTO skill_packages(package_hash,id,name,version,license_id,classification,manifest_json,skill_markdown,reasons,created_at,package_bytes,source_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(
                    inspection.packageHash,
                    manifest?.id ?: "instruction.${inspection.packageHash.take(12)}",
                    manifest?.name ?: "Instruction-only skill",
                    manifest?.version ?: "0",
                    manifest?.license ?: "unknown",
                    inspection.classification.name,
                    manifestJson,
                    inspection.skillMarkdown,
                    inspection.reasons.joinToString(" | "),
                    Utc.nowIso(),
                    inspection.packageBytes ?: bytes,
                    inspection.packageHash,
                ),
            )
            val existing = db.query("SELECT install_id FROM skill_installs WHERE package_hash = ?", listOf(inspection.packageHash)).singleOrNull()
            val installId = existing?.string("install_id") ?: EntityId.random().value
            val existingEnabled = existing?.let {
                db.query("SELECT enabled FROM skill_installs WHERE install_id = ?", listOf(installId)).singleOrNull()?.long("enabled")
            }
            val enabledFlag = existingEnabled ?: if (enable && inspection.classification != CompatibilityClass.E) 1L else 0L
            db.execute(
                "INSERT OR REPLACE INTO skill_installs(install_id,package_hash,enabled,created_at) VALUES (?,?,?,?)",
                listOf(installId, inspection.packageHash, enabledFlag, Utc.nowIso()),
            )
            val existingGrant = db.query(
                "SELECT grant_id FROM permission_grants WHERE install_id = ? AND package_hash = ?",
                listOf(installId, inspection.packageHash),
            ).singleOrNull()
            if (existingGrant == null) {
                val specs = manifest?.permissionSpecs.orEmpty()
                val caps = specs.map { it.capability }.ifEmpty { manifest?.permissions.orEmpty().toList() }
                val kbs = specs.flatMap { it.knowledgeBaseIds }.toSet()
                val hosts = specs.flatMap { it.hosts }.toSet()
                val methods = specs.flatMap { it.methods }.toSet()
                val scopes = """{"capabilities":${caps.toJsonArray()},"knowledgeBaseIds":${kbs.toJsonArray()},"hosts":${hosts.toJsonArray()},"methods":${methods.toJsonArray()}}"""
                db.execute(
                    "INSERT INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked,scopes_json) VALUES (?,?,?,?,?,?,?)",
                    listOf(EntityId.random().value, installId, inspection.packageHash, caps.joinToString(","), 1, 0, scopes),
                )
            }
        }
        return result
    }

    fun packageBytes(packageHash: String): ByteArray? {
        val row = db.query("SELECT package_bytes FROM skill_packages WHERE package_hash = ?", listOf(packageHash)).singleOrNull()
            ?: return null
        val blob = row.columns["package_bytes"]
        return when (blob) {
            is ByteArray -> blob
            is java.sql.Blob -> blob.getBytes(1, blob.length().toInt())
            else -> null
        }
    }

    fun list(): List<InstalledSkill> =
        db.query(
            """
            SELECT i.install_id AS install_id, p.package_hash AS package_hash, p.name AS name, p.classification AS classification,
                   i.enabled AS enabled, p.skill_markdown AS skill_markdown, p.reasons AS reasons, p.license_id AS license_id
            FROM skill_installs i JOIN skill_packages p ON p.package_hash = i.package_hash
            ORDER BY i.created_at
            """.trimIndent(),
        ).map { row ->
            InstalledSkill(
                installId = row.string("install_id"),
                packageHash = row.string("package_hash"),
                name = row.string("name"),
                classification = CompatibilityClass.valueOf(row.string("classification")),
                enabled = row.long("enabled") != 0L,
                skillMarkdown = row.string("skill_markdown").ifBlank { null },
                reasons = row.string("reasons").split(" | ").filter { it.isNotBlank() },
                license = row.string("license_id"),
            )
        }

    fun setEnabled(installId: String, enabled: Boolean) {
        db.execute("UPDATE skill_installs SET enabled = ? WHERE install_id = ?", listOf(if (enabled) 1 else 0, installId))
    }

    fun revoke(installId: String) {
        db.execute("UPDATE permission_grants SET revoked = 1 WHERE install_id = ?", listOf(installId))
        db.execute("UPDATE skill_installs SET enabled = 0 WHERE install_id = ?", listOf(installId))
    }

    fun enabledInstructions(): List<String> =
        list().filter { it.enabled }.mapNotNull { skill ->
            skill.skillMarkdown?.takeIf { it.isNotBlank() }
        }

    fun effectiveCapabilities(): Set<String> = effectiveGrant().capabilities

    fun effectiveGrant(): PermissionGrant {
        val rows = db.query(
            """
            SELECT g.* FROM permission_grants g
            JOIN skill_installs i ON i.install_id = g.install_id
            WHERE i.enabled = 1 AND g.revoked = 0
            """.trimIndent(),
        )
        val grants = rows.map { rowToGrant(it) }
        if (grants.isEmpty()) {
            return PermissionGrant("", "", "", emptySet(), revoked = true)
        }
        return PermissionGrant(
            grantId = grants.joinToString(",") { it.grantId },
            installId = grants.joinToString(",") { it.installId },
            packageHash = grants.joinToString(",") { it.packageHash },
            capabilities = grants.flatMap { it.capabilities }.toSet(),
            knowledgeBaseIds = grants.flatMap { it.knowledgeBaseIds }.toSet(),
            hosts = grants.flatMap { it.hosts }.toSet(),
            methods = grants.flatMap { it.methods }.toSet(),
        )
    }

    fun grantsFor(installId: String): List<PermissionGrant> =
        db.query("SELECT * FROM permission_grants WHERE install_id = ?", listOf(installId)).map { rowToGrant(it) }

    private fun rowToGrant(row: SqlRow): PermissionGrant {
        val scopes = row.string("scopes_json").ifBlank { "{}" }
        val parsed = runCatching { json.parseToJsonElement(scopes) as? JsonObject }.getOrNull()
        fun setOf(key: String): Set<String> =
            parsed?.get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet()
        val caps = row.string("capabilities").split(',').filter { it.isNotBlank() }.toSet()
        return PermissionGrant(
            grantId = row.string("grant_id"),
            installId = row.string("install_id"),
            packageHash = row.string("package_hash"),
            capabilities = caps.ifEmpty { setOf("capabilities") },
            revoked = row.long("revoked") != 0L,
            revision = row.long("revision").toInt(),
            knowledgeBaseIds = setOf("knowledgeBaseIds"),
            hosts = setOf("hosts"),
            methods = setOf("methods"),
            scopesJson = scopes,
        )
    }

    private fun Collection<String>.toJsonArray(): String =
        joinToString(prefix = "[", postfix = "]") { """"$it"""" }
}
