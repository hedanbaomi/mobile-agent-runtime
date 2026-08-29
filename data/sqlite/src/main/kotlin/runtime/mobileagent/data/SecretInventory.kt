// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.SecretStatus
import runtime.mobileagent.domain.Utc

class SecretInventory(private val db: SqlConnection) {
    fun status(ref: String): SecretStatus? {
        val raw = db.query("SELECT status FROM secrets WHERE ref = ?", listOf(ref))
            .singleOrNull()?.string("status") ?: return null
        return runCatching { SecretStatus.valueOf(raw) }.getOrNull()
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
        if (row.string("status") != SecretStatus.ACTIVE.name) return null
        return row.columns["ciphertext"] as? ByteArray
    }

    fun retire(ref: String) {
        db.execute(
            "UPDATE secrets SET status = ?, retired_at = ?, ciphertext = ? WHERE ref = ? AND status = ?",
            listOf(SecretStatus.RETIRED.name, Utc.nowIso(), ByteArray(0), ref, SecretStatus.ACTIVE.name),
        )
    }

    fun retireIfUnreferenced(ref: String) {
        val providerUses = db.query(
            "SELECT COUNT(*) AS n FROM provider_profiles WHERE secret_ref = ?",
            listOf(ref),
        ).single().long("n")
        if (providerUses > 0L) return
        retire(ref)
        collectOrphans()
    }

    fun collectOrphans() {
        val referenced = db.query("SELECT secret_ref FROM provider_profiles")
            .map { it.string("secret_ref") }
            .filter { it.isNotBlank() }
            .toSet()
        db.query("SELECT ref, status FROM secrets").forEach { row ->
            val ref = row.string("ref")
            val status = runCatching { SecretStatus.valueOf(row.string("status")) }.getOrNull() ?: return@forEach
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
}
