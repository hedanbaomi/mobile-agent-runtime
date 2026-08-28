// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

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
    fun importPackage(bytes: ByteArray, expectedHash: String? = null, enable: Boolean = false): SkillInstallResult {
        val result = SkillInstaller.install(bytes, expectedHash)
        if (!result.accepted) return result
        val inspection = result.inspection
        val manifest = inspection.manifest
        db.transaction {
            db.execute(
                "INSERT OR REPLACE INTO skill_packages(package_hash,id,name,version,license_id,classification,manifest_json,skill_markdown,reasons,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                listOf(
                    inspection.packageHash,
                    manifest?.id ?: "instruction.${inspection.packageHash.take(12)}",
                    manifest?.name ?: "Instruction-only skill",
                    manifest?.version ?: "0",
                    manifest?.license ?: "unknown",
                    inspection.classification.name,
                    manifest?.let { """{"id":"${it.id}"}""" },
                    inspection.skillMarkdown,
                    inspection.reasons.joinToString(" | "),
                    Utc.nowIso(),
                ),
            )
            val existing = db.query("SELECT install_id FROM skill_installs WHERE package_hash = ?", listOf(inspection.packageHash)).singleOrNull()
            val installId = existing?.string("install_id") ?: EntityId.random().value
            db.execute(
                "INSERT OR REPLACE INTO skill_installs(install_id,package_hash,enabled,created_at) VALUES (?,?,?,?)",
                listOf(installId, inspection.packageHash, if (enable && inspection.classification != CompatibilityClass.E) 1 else 0, Utc.nowIso()),
            )
            val caps = manifest?.permissions.orEmpty().joinToString(",")
            db.execute(
                "INSERT OR REPLACE INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked) VALUES (?,?,?,?,?,?)",
                listOf(EntityId.random().value, installId, inspection.packageHash, caps, 1, 0),
            )
        }
        return result
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

    fun enabledInstructions(): List<String> =
        list().filter { it.enabled }.mapNotNull { skill ->
            skill.skillMarkdown?.takeIf { it.isNotBlank() }
        }

    fun effectiveCapabilities(): Set<String> {
        val rows = db.query(
            """
            SELECT g.capabilities AS capabilities FROM permission_grants g
            JOIN skill_installs i ON i.install_id = g.install_id
            WHERE i.enabled = 1 AND g.revoked = 0
            """.trimIndent(),
        )
        return rows.flatMap { it.string("capabilities").split(',').filter { cap -> cap.isNotBlank() } }.toSet()
    }

    fun grantsFor(installId: String): List<PermissionGrant> =
        db.query("SELECT * FROM permission_grants WHERE install_id = ?", listOf(installId)).map { row ->
            PermissionGrant(
                grantId = row.string("grant_id"),
                installId = row.string("install_id"),
                packageHash = row.string("package_hash"),
                capabilities = row.string("capabilities").split(',').filter { it.isNotBlank() }.toSet(),
                revoked = row.long("revoked") != 0L,
                revision = row.long("revision").toInt(),
            )
        }
}
