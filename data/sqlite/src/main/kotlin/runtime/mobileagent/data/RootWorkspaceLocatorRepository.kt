// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.*

/** Ciphertext-only storage. Android must authenticate the envelope before using its locator. */
class RootWorkspaceLocatorRepository(private val db: SqlConnection) {
    fun attachNew(locator: RootWorkspaceLocator, execution: RootExecutionStamp): RootWorkspaceLocator = db.transaction {
        requireLocalRootState(db,execution)
        val origin = RootWorkspaceRepository(db).requireOrigin(locator.origin.workspaceId,execution)
        require(origin == locator.origin) { "Root workspace provenance changed" }
        require(get(origin.workspaceId) == null) { "A Root locator cannot be replaced; create a fresh workspace" }
        val encrypted = locator.encryptedEnvelopeCopy()
        try {
            db.execute(
                "INSERT INTO root_workspace_locators(workspace_id,generation_id,encrypted_envelope,locator_version,key_version,aad_version) VALUES(?,?,?,?,?,?)",
                listOf(origin.workspaceId,origin.generationId,encrypted,locator.locatorVersion,locator.keyVersion,locator.aadVersion),
            )
        } finally { encrypted.fill(0) }
        check(get(origin.workspaceId) == locator) { "Root locator was not persisted" }
        locator
    }

    /** Local preflight only; opening it still requires Keystore and a live verified Root session. */
    fun getCurrent(workspaceId: String, execution: RootExecutionStamp): RootWorkspaceLocator? = db.transaction {
        if (runCatching { requireLocalRootState(db,execution) }.isFailure) return@transaction null
        val origin = runCatching { RootWorkspaceRepository(db).requireOrigin(workspaceId,execution) }
            .getOrNull() ?: return@transaction null
        get(workspaceId)?.takeIf { it.origin == origin }
    }

    /** Storage readback only; Android adapters must use getCurrent. */
    internal fun get(workspaceId: String): RootWorkspaceLocator? {
        val row = db.query("SELECT * FROM root_workspace_locators WHERE workspace_id=?",listOf(workspaceId))
            .singleOrNull() ?: return null
        val origin = RootWorkspaceRepository(db).getOrigin(workspaceId) ?: return null
        if (origin.generationId != row.string("generation_id")) return null
        return RootWorkspaceLocator(
            origin,requireNotNull(row.columns["encrypted_envelope"] as? ByteArray),Math.toIntExact(row.long("locator_version")),
            Math.toIntExact(row.long("key_version")),Math.toIntExact(row.long("aad_version")),
        )
    }

    companion object {
        internal fun install(db: SqlConnection) {
            db.execute("CREATE TABLE IF NOT EXISTS root_workspace_locators(workspace_id TEXT NOT NULL PRIMARY KEY REFERENCES root_workspace_origins(workspace_id) ON DELETE CASCADE,generation_id TEXT NOT NULL,encrypted_envelope BLOB NOT NULL CHECK(length(encrypted_envelope) BETWEEN 1 AND 16384),locator_version INTEGER NOT NULL CHECK(locator_version>0),key_version INTEGER NOT NULL CHECK(key_version>0),aad_version INTEGER NOT NULL CHECK(aad_version=2))")
            db.execute("CREATE TRIGGER IF NOT EXISTS root_workspace_locators_immutable BEFORE UPDATE ON root_workspace_locators BEGIN SELECT RAISE(ABORT,'Root workspace locator is immutable'); END")
        }
    }
}
