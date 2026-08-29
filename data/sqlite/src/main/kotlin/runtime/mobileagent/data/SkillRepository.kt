// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
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
import runtime.mobileagent.skills.SkillArchive
import runtime.mobileagent.skills.SkillInspection
import runtime.mobileagent.skills.HttpPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

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
                    listOf(EntityId.random().value, installId, inspection.packageHash, caps.joinToString(","), 1, if (enable) 0 else 1, scopes),
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
        val skill = get(installId) ?: error("Skill is not installed")
        require(skill.classification != CompatibilityClass.E) { "Invalid packages cannot be enabled" }
        if (enabled && skill.classification == CompatibilityClass.B) {
            require(grantsFor(installId).any { !it.revoked && it.packageHash == skill.packageHash }) {
                "Review and approve this package's permissions before enabling it"
            }
        }
        db.execute("UPDATE skill_installs SET enabled = ? WHERE install_id = ?", listOf(if (enabled) 1 else 0, installId))
    }

    fun revoke(installId: String) {
        db.transaction {
            db.execute("UPDATE permission_grants SET revoked = 1, revision = revision + 1 WHERE install_id = ?", listOf(installId))
            db.execute("UPDATE skill_installs SET enabled = 0 WHERE install_id = ?", listOf(installId))
        }
    }

    fun enabledInstructions(installIds: Set<String>? = null): List<String> =
        list().filter { it.enabled && (installIds == null || it.installId in installIds) }.mapNotNull { skill ->
            skill.skillMarkdown?.takeIf { it.isNotBlank() }
        }

    fun effectiveCapabilities(): Set<String> = effectiveGrant().capabilities

    fun effectiveGrant(installIds: Set<String>? = null, knowledgeBaseIds: Set<String>? = null): PermissionGrant {
        val rows = db.query(
            """
            SELECT g.* FROM permission_grants g
            JOIN skill_installs i ON i.install_id = g.install_id
            WHERE i.enabled = 1 AND g.revoked = 0 AND g.package_hash = i.package_hash
            """.trimIndent(),
        )
        val grants = rows.map { rowToGrant(it) }.filter { installIds == null || it.installId in installIds }
        if (grants.isEmpty()) {
            return PermissionGrant("", "", "", emptySet(), revoked = true)
        }
        return PermissionGrant(
            grantId = grants.joinToString(",") { it.grantId },
            installId = grants.joinToString(",") { it.installId },
            packageHash = grants.joinToString(",") { it.packageHash },
            capabilities = grants.flatMap { it.capabilities }.toSet(),
            knowledgeBaseIds = grants.flatMap { it.knowledgeBaseIds }.toSet().let { ids ->
                if (knowledgeBaseIds == null) ids else ids intersect knowledgeBaseIds
            },
            hosts = grants.flatMap { it.hosts }.toSet(),
            methods = grants.flatMap { it.methods }.toSet(),
        )
    }

    fun grantsFor(installId: String): List<PermissionGrant> =
        db.query("SELECT * FROM permission_grants WHERE install_id = ?", listOf(installId)).map { rowToGrant(it) }

    fun get(installId: String): InstalledSkill? = list().firstOrNull { it.installId == installId }

    /** Revalidate the immutable original bytes before displaying or executing a package. */
    fun inspect(installId: String): SkillInspection {
        val skill = get(installId) ?: error("Skill is not installed")
        val bytes = packageBytes(skill.packageHash) ?: error("Skill package is missing")
        val inspection = SkillArchive.inspect(bytes, skill.packageHash)
        require(inspection.installable) { "Stored skill failed integrity validation" }
        return inspection
    }

    fun approvePermissions(
        installId: String,
        capabilities: Set<String>,
        knowledgeBaseIds: Set<String> = emptySet(),
        hosts: Set<String> = emptySet(),
        methods: Set<String> = emptySet(),
    ): PermissionGrant {
        val inspection = inspect(installId)
        val declared = inspection.manifest?.permissions.orEmpty()
        require(capabilities.all { it in declared }) { "A grant cannot exceed the package declaration" }
        val specs = inspection.manifest?.permissionSpecs.orEmpty()
        val declaredKbs = specs.flatMap { it.knowledgeBaseIds }.toSet()
        require(declaredKbs.isEmpty() || knowledgeBaseIds.all { it in declaredKbs }) { "Knowledge scope exceeds declaration" }
        require(knowledgeBaseIds.isEmpty() || capabilities.any { it == "knowledge.search" || it == "knowledge.read" || it == "document.read" }) { "Knowledge capability is not selected" }
        knowledgeBaseIds.forEach { id ->
            require(db.query("SELECT id FROM knowledge_bases WHERE id = ? AND deleted_at IS NULL", listOf(id)).size == 1) {
                "Knowledge base is unavailable"
            }
        }
        val normalizedHosts = hosts.map { it.lowercase().trim('.') }.toSet()
        val allowedHosts = specs.flatMap { it.hosts }.map { it.lowercase().trim('.') }.toSet()
        require(normalizedHosts.all { it in allowedHosts }) { "Network host exceeds declaration" }
        normalizedHosts.forEach { HttpPolicy.assertRequest("https://$it/", normalizedHosts) }
        val normalizedMethods = methods.map { it.uppercase() }.toSet()
        val declaredMethods = specs.flatMap { it.methods }.map { it.uppercase() }.toSet().ifEmpty { setOf("GET") }
        require(normalizedMethods.all { it in declaredMethods }) { "Network method exceeds declaration" }
        require((normalizedHosts.isEmpty() && normalizedMethods.isEmpty()) || "network.http" in capabilities) { "Network capability is not selected" }
        val scopes = json.encodeToString(mapOf(
            "capabilities" to capabilities.sorted(), "knowledgeBaseIds" to knowledgeBaseIds.sorted(),
            "hosts" to normalizedHosts.sorted(), "methods" to normalizedMethods.sorted(),
        ))
        db.transaction {
            val skill = get(installId) ?: error("Skill was removed")
            require(skill.packageHash == inspection.packageHash) { "Package changed during permission review" }
            val existing = grantsFor(installId).singleOrNull { it.packageHash == skill.packageHash }
            if (existing == null) {
                db.execute(
                    "INSERT INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked,scopes_json) VALUES (?,?,?,?,?,?,?)",
                    listOf(EntityId.random().value, installId, skill.packageHash, capabilities.sorted().joinToString(","), 1, 0, scopes),
                )
            } else {
                db.execute(
                    "UPDATE permission_grants SET capabilities = ?, scopes_json = ?, revoked = 0, revision = revision + 1 WHERE grant_id = ?",
                    listOf(capabilities.sorted().joinToString(","), scopes, existing.grantId),
                )
            }
        }
        return grantsFor(installId).single { it.packageHash == inspection.packageHash }
    }

    /** Invocation grants never union permissions from another installed skill. */
    fun grantForInvocation(installId: String, agentSkillIds: Set<String>, agentKnowledgeBaseIds: Set<String>): PermissionGrant {
        if (installId !in agentSkillIds) return PermissionGrant("", installId, "", emptySet(), revoked = true)
        return effectiveGrant(setOf(installId), agentKnowledgeBaseIds)
    }

    fun sourceFiles(installId: String): List<String> = inspect(installId).files.sorted()

    fun sourceText(installId: String, path: String): String {
        val inspection = inspect(installId)
        require(path in inspection.files) { "Source file is not part of this package" }
        val bytes = inspection.packageBytes ?: error("Skill package is missing")
        val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) {
            return inspection.skillMarkdown.orEmpty().take(128 * 1024)
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.replace('\\', '/') == path && !entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        require(out.size() + read <= 128 * 1024) { "Source preview exceeds 128 KiB" }
                        out.write(buffer, 0, read)
                    }
                    val content = out.toByteArray()
                    require(content.none { it == 0.toByte() }) { "Binary files cannot be shown as source text" }
                    return content.toString(Charsets.UTF_8)
                }
            }
        }
        error("Source file is missing")
    }

    private fun rowToGrant(row: SqlRow): PermissionGrant {
        val scopes = row.string("scopes_json").ifBlank { "{}" }
        val parsed = runCatching { json.parseToJsonElement(scopes) as? JsonObject }.getOrNull()
        fun scopeValues(key: String): Set<String> =
            parsed?.get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet()
        val caps = row.string("capabilities").split(',').filter { it.isNotBlank() }.toSet()
        return PermissionGrant(
            grantId = row.string("grant_id"),
            installId = row.string("install_id"),
            packageHash = row.string("package_hash"),
            capabilities = caps.ifEmpty { scopeValues("capabilities") },
            revoked = row.long("revoked") != 0L,
            revision = row.long("revision").toInt(),
            knowledgeBaseIds = scopeValues("knowledgeBaseIds"),
            hosts = scopeValues("hosts"),
            methods = scopeValues("methods"),
            scopesJson = scopes,
        )
    }

    private fun Collection<String>.toJsonArray(): String =
        json.encodeToString(toList().sorted())
}
