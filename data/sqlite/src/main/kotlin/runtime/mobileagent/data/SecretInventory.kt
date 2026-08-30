// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.SecretStatus
import runtime.mobileagent.domain.Utc

class SecretInventory(private val db: SqlConnection) {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun status(ref: String): SecretStatus? {
        val raw = db.query("SELECT status FROM secrets WHERE ref = ?", listOf(ref))
            .singleOrNull()?.columns?.get("status") ?: return null
        return decodeStatus(raw.toString())
    }

    fun putActive(ref: String, ciphertext: ByteArray) {
        db.execute(
            "INSERT OR REPLACE INTO secrets(ref, ciphertext, created_at, status, retired_at) VALUES (?,?,?,?,NULL)",
            listOf(ref, ciphertext, Utc.nowIso(), SecretStatus.ACTIVE.name),
        )
    }

    fun ciphertext(ref: String): ByteArray? {
        val row = db.query(
            "SELECT ciphertext, status FROM secrets WHERE ref = ?",
            listOf(ref),
        ).singleOrNull() ?: return null
        if (decodeStatus(row.string("status")) != SecretStatus.ACTIVE) return null
        return row.columns["ciphertext"] as? ByteArray
    }

    /**
     * Retire one secret only when no live provider or immutable snapshot references it.
     *
     * A caller may still explicitly request retirement of an individual ref, so the guard is
     * kept here as well as in [retireIfUnreferenced].  This prevents a future delete/update path
     * from accidentally bypassing the reference scan.
     */
    fun retire(ref: String): Boolean {
        if (status(ref) != SecretStatus.ACTIVE) return false
        if (ref in referencedSecretRefs()) return false
        return retireStored(ref)
    }

    private fun retireStored(ref: String): Boolean {
        db.execute(
            "UPDATE secrets SET status = ?, retired_at = ?, ciphertext = ? WHERE ref = ? AND status = ?",
            listOf(SecretStatus.RETIRED.name, Utc.nowIso(), ByteArray(0), ref, SecretStatus.ACTIVE.name),
        )
        return true
    }

    fun retireIfUnreferenced(ref: String) {
        retireIfUnreferenced(listOf(ref))
    }

    /** Retire the requested refs that are not used by any provider/header/snapshot. */
    fun retireIfUnreferenced(refs: Iterable<String>) {
        val candidates = refs.filter { it.isNotBlank() }.toSet()
        if (candidates.isEmpty()) {
            collectOrphans()
            return
        }
        val referenced = referencedSecretRefs()
        candidates.filterNot { it in referenced }.forEach { retireStored(it) }
        collectOrphans()
    }

    fun collectOrphans() {
        val referenced = referencedSecretRefs()
        db.query("SELECT ref, status FROM secrets").forEach { row ->
            val ref = row.string("ref")
            val status = decodeStatus(row.string("status"))
            if (ref in referenced) return@forEach
            when (status) {
                SecretStatus.ACTIVE -> {
                    db.execute(
                        "UPDATE secrets SET status = ?, retired_at = COALESCE(retired_at, ?), ciphertext = ? WHERE ref = ?",
                        listOf(SecretStatus.ORPHANED.name, Utc.nowIso(), ByteArray(0), ref),
                    )
                }
                SecretStatus.RETIRED, SecretStatus.ORPHANED -> {
                    db.execute(
                        "UPDATE secrets SET status = ?, ciphertext = ? WHERE ref = ?",
                        listOf(SecretStatus.DELETED.name, ByteArray(0), ref),
                    )
                }
                SecretStatus.DELETED -> Unit
            }
        }
    }

    /**
     * Return every persisted reference that must keep a secret alive.
     *
     * Provider headers are references too; looking only at [ProviderProfile.secretRef] used to
     * delete an API key still needed by a custom Authorization/header entry.  Snapshot manifests
     * are included because they are immutable run boundaries and retain provider references after
     * a live profile is edited or removed.  Malformed persisted JSON is an error, never an empty
     * map fallback, so GC fails closed rather than deleting a possibly live secret.
     */
    fun referencedSecretRefs(): Set<String> {
        val refs = linkedSetOf<String>()
        if (tableExists("provider_profiles")) {
            db.query("SELECT id, secret_ref, header_secret_refs FROM provider_profiles").forEach { row ->
                val providerId = requiredText(row, "id", "provider profile")
                addReference(refs, row.columns["secret_ref"], "provider $providerId secret_ref")
                val headerRaw = requiredText(row, "header_secret_refs", "provider $providerId header_secret_refs")
                decodeHeaderReferences(headerRaw, "provider $providerId header_secret_refs").forEach { refs += it }
            }
        }
        if (tableExists("agent_snapshots")) {
            db.query("SELECT id, binding_manifest_json, expanded_json FROM agent_snapshots").forEach { row ->
                val snapshotId = requiredText(row, "id", "snapshot")
                val bindingRaw = requiredText(row, "binding_manifest_json", "snapshot $snapshotId binding manifest")
                val expandedRaw = requiredText(row, "expanded_json", "snapshot $snapshotId expanded manifest")
                snapshotReferences(bindingRaw, expandedRaw, snapshotId).forEach { refs += it }
            }
        }
        if (tableExists("app_prefs")) {
            db.query("SELECT value FROM app_prefs WHERE key = ?", listOf(SettingsRepository.KEY_WEB_SEARCH_SECRET_REF))
                .singleOrNull()?.let { row ->
                    val ref = requiredText(row, "value", "web-search secret reference")
                    if (ref.isNotBlank()) refs += ref
                }
        }
        return refs
    }

    private fun snapshotReferences(bindingRaw: String, expandedRaw: String, snapshotId: String): Set<String> {
        val binding = decodeObjectOrEmpty(bindingRaw, "snapshot $snapshotId binding manifest")
        val root = if (binding.isEmpty() && expandedRaw.isNotBlank() && expandedRaw.trim() != "{}") {
            val expanded = decodeObject(expandedRaw, "snapshot $snapshotId expanded manifest")
            val nested = expanded["bindingManifest"]
            if (nested != null) {
                nested as? JsonObject ?: invalid("Snapshot $snapshotId bindingManifest is not an object")
            } else {
                expanded
            }
        } else {
            binding
        }
        val refs = linkedSetOf<String>()
        SNAPSHOT_PROVIDER_KEYS.forEach { key ->
            val element = root[key] ?: return@forEach
            val provider = element as? JsonObject
                ?: invalid("Snapshot $snapshotId $key is not an object")
            provider["secretRef"]?.let {
                addReference(refs, it, "snapshot $snapshotId $key secretRef")
            }
            val headers = provider["headerSecretRefs"]
            if (headers != null) {
                val headerObject = headers as? JsonObject
                    ?: invalid("Snapshot $snapshotId $key headerSecretRefs is not an object")
                headerObject.forEach { (header, value) ->
                    val primitive = value as? JsonPrimitive
                    if (primitive == null || !primitive.isString) {
                        invalid("Snapshot $snapshotId $key header $header is not a string reference")
                    }
                    addReference(refs, primitive, "snapshot $snapshotId $key header $header")
                }
            }
        }
        return refs
    }

    private fun decodeHeaderReferences(raw: String, context: String): Set<String> {
        val normalized = raw.ifBlank { "{}" }
        val values = runCatching { json.decodeFromString<Map<String, String>>(normalized) }
            .getOrElse { invalid("$context is invalid") }
        return values.entries.mapNotNull { (header, ref) ->
            if (header.isBlank()) invalid("$context contains a blank header name")
            ref.takeIf { it.isNotBlank() }
        }.toSet()
    }

    private fun decodeObjectOrEmpty(raw: String, context: String): JsonObject {
        if (raw.isBlank() || raw.trim() == "{}") return JsonObject(emptyMap())
        return decodeObject(raw, context)
    }

    private fun decodeObject(raw: String, context: String): JsonObject =
        runCatching { json.parseToJsonElement(raw) as? JsonObject ?: invalid("$context is not an object") }
            .getOrElse { error ->
                if (error is AppException) throw error
                invalid("$context is invalid")
            }

    private fun addReference(refs: MutableSet<String>, value: Any?, context: String) {
        when (value) {
            null -> invalid("$context is not a string reference")
            is JsonPrimitive -> {
                if (!value.isString) invalid("$context is not a string reference")
                value.content.takeIf { it.isNotBlank() }?.let { refs += it }
            }
            is String -> value.takeIf { it.isNotBlank() }?.let { refs += it }
            else -> invalid("$context is not a string reference")
        }
    }

    private fun requiredText(row: SqlRow, column: String, context: String): String =
        row.columns[column] as? String ?: invalid("$context has an invalid $column")

    private fun decodeStatus(raw: String): SecretStatus =
        runCatching { SecretStatus.valueOf(raw) }
            .getOrElse { invalid("Persisted secret status is invalid") }

    private fun tableExists(name: String): Boolean = db.query(
        "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name = ?",
        listOf(name),
    ).isNotEmpty()

    private fun invalid(message: String): Nothing = throw AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = "Secret inventory data is invalid: $message",
        retryClass = RetryClass.USER_ACTION,
        stage = "secret-inventory",
        operationId = "secret-gc",
        sanitizedDetails = message,
    ).asException()

    private companion object {
        val SNAPSHOT_PROVIDER_KEYS = listOf("provider", "visionProvider", "embeddingProvider", "rerankerProvider")
    }
}
