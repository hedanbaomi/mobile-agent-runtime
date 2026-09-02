// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.time.Instant
import java.util.UUID
import runtime.mobileagent.domain.ApprovalDecision
import runtime.mobileagent.domain.ApprovalRecord
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityPreferences
import runtime.mobileagent.domain.AuthorityPolicy
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.DesktopTrust
import runtime.mobileagent.domain.DesktopTrustStatus
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.domain.SafWorkspaceGrant
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

/** Raised when a policy or grant update loses its optimistic concurrency race. */
class AuthorityPolicyConflictException(message: String) : IllegalStateException(message)

/**
 * Persists the user-selected authority and Dangerous Mode.  Availability, Binder state, USB
 * connection and bridge sessions deliberately have no write API here: a temporary connection
 * cannot silently change the durable policy or select a fallback provider.
 */
class AuthorityPolicyRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun getPolicy(): AuthorityPolicy = db.query(
        "SELECT selected_authority, dangerous_mode, policy_version, updated_at FROM authority_policy WHERE id = 1",
    ).singleOrNull()?.toAuthorityPolicy() ?: AuthorityPolicy()

    fun readPolicy(): AuthorityPolicy = getPolicy()
    fun policy(): AuthorityPolicy = getPolicy()

    /** Update only if [expectedPolicyVersion] is still the persisted version. */
    fun updatePolicy(
        expectedPolicyVersion: Long,
        selectedAuthority: Authority,
        dangerousMode: DangerousMode,
    ): AuthorityPolicy {
        require(expectedPolicyVersion >= 0) { "Policy version must not be negative" }
        return db.transaction {
            val current = getPolicy()
            if (current.policyVersion != expectedPolicyVersion) {
                throw AuthorityPolicyConflictException("Authority policy version changed")
            }
            val next = expectedPolicyVersion + 1
            val updatedAt = clock()
            db.execute(
                "UPDATE authority_policy SET selected_authority = ?, dangerous_mode = ?, policy_version = ?, updated_at = ? WHERE id = 1 AND policy_version = ?",
                listOf(selectedAuthority.name, dangerousMode.name, next, updatedAt, expectedPolicyVersion),
            )
            val persisted = getPolicy()
            if (persisted.policyVersion != next ||
                persisted.selectedAuthority != selectedAuthority ||
                persisted.dangerousMode != dangerousMode
            ) {
                throw AuthorityPolicyConflictException("Authority policy update lost its compare-and-set race")
            }
            persisted
        }
    }

    fun setPolicy(
        expectedPolicyVersion: Long,
        selectedAuthority: Authority,
        dangerousMode: DangerousMode,
    ): AuthorityPolicy = updatePolicy(expectedPolicyVersion, selectedAuthority, dangerousMode)

    fun compareAndSet(
        expectedPolicyVersion: Long,
        selectedAuthority: Authority,
        dangerousMode: DangerousMode,
    ): Boolean = runCatching {
        updatePolicy(expectedPolicyVersion, selectedAuthority, dangerousMode)
        true
    }.getOrElse { error ->
        if (error is AuthorityPolicyConflictException) false else throw error
    }

    fun selectAuthority(expectedPolicyVersion: Long, authority: Authority): AuthorityPolicy =
        updatePolicy(expectedPolicyVersion, authority, getPolicy().dangerousMode)

    fun disableDangerousMode(expectedPolicyVersion: Long): AuthorityPolicy =
        updatePolicy(expectedPolicyVersion, getPolicy().selectedAuthority, DangerousMode.DISABLED)

    /** Explicitly disable all elevated operations, including the durable provider selection. */
    fun disable(expectedPolicyVersion: Long): AuthorityPolicy =
        updatePolicy(expectedPolicyVersion, Authority.NONE, DangerousMode.DISABLED)

    /** Return the persisted selection, never another available provider. */
    fun requireSelectedAuthority(available: Set<Authority>): Authority {
        val selected = getPolicy().selectedAuthority
        require(selected != Authority.NONE) { "No elevated authority provider is selected" }
        require(selected in available) { "Selected authority is temporarily unavailable" }
        return selected
    }

    fun getPreferences(): AuthorityPreferences = getPreference(Authority.NONE)

    fun getPreference(authority: Authority): AuthorityPreferences = db.query(
        "SELECT authority, user_intent_enabled, explicitly_configured, updated_at FROM authority_preferences WHERE authority = ?",
        listOf(authority.name),
    ).singleOrNull()?.toAuthorityPreferences() ?: AuthorityPreferences(authority = authority)

    fun listPreferences(): List<AuthorityPreferences> = db.query(
        "SELECT authority, user_intent_enabled, explicitly_configured, updated_at FROM authority_preferences ORDER BY authority",
    ).map { it.toAuthorityPreferences() }

    fun savePreferences(preferences: AuthorityPreferences): AuthorityPreferences {
        validatePreferences(preferences)
        db.transaction {
            db.execute(
                "INSERT INTO authority_preferences(authority, user_intent_enabled, explicitly_configured, updated_at) VALUES(?,?,?,?) ON CONFLICT(authority) DO UPDATE SET user_intent_enabled=excluded.user_intent_enabled, explicitly_configured=excluded.explicitly_configured, updated_at=excluded.updated_at",
                listOf(
                    preferences.authority.name,
                    if (preferences.userIntentEnabled) 1 else 0,
                    if (preferences.explicitlyConfigured) 1 else 0,
                    clock(),
                ),
            )
        }
        return getPreference(preferences.authority)
    }

    fun setUserIntent(authority: Authority, enabled: Boolean, explicitlyConfigured: Boolean = true): AuthorityPreferences =
        savePreferences(AuthorityPreferences(authority, enabled, explicitlyConfigured, clock()))

    private fun validatePreferences(preferences: AuthorityPreferences) {
        require(preferences.updatedAt.length <= 128) { "Preference timestamp is invalid" }
    }
}

/** Compatibility name for callers that refer to the aggregate rather than its policy row. */
class AuthorityRepository(
    db: SqlConnection,
    clock: () -> String = { Utc.nowIso() },
) : AuthorityPolicyRepositoryFacade(db, clock)

open class AuthorityPolicyRepositoryFacade(
    db: SqlConnection,
    clock: () -> String = { Utc.nowIso() },
) {
    private val delegate = AuthorityPolicyRepository(db, clock)
    fun getPolicy() = delegate.getPolicy()
    fun readPolicy() = delegate.readPolicy()
    fun updatePolicy(expectedPolicyVersion: Long, selectedAuthority: Authority, dangerousMode: DangerousMode) =
        delegate.updatePolicy(expectedPolicyVersion, selectedAuthority, dangerousMode)
    fun setPolicy(expectedPolicyVersion: Long, selectedAuthority: Authority, dangerousMode: DangerousMode) =
        delegate.setPolicy(expectedPolicyVersion, selectedAuthority, dangerousMode)
    fun compareAndSet(expectedPolicyVersion: Long, selectedAuthority: Authority, dangerousMode: DangerousMode) =
        delegate.compareAndSet(expectedPolicyVersion, selectedAuthority, dangerousMode)
    fun selectAuthority(expectedPolicyVersion: Long, authority: Authority) =
        delegate.selectAuthority(expectedPolicyVersion, authority)
    fun disableDangerousMode(expectedPolicyVersion: Long) = delegate.disableDangerousMode(expectedPolicyVersion)
    fun disable(expectedPolicyVersion: Long) = delegate.disable(expectedPolicyVersion)
    fun requireSelectedAuthority(available: Set<Authority>) = delegate.requireSelectedAuthority(available)
    fun getPreferences() = delegate.getPreferences()
    fun getPreference(authority: Authority) = delegate.getPreference(authority)
    fun listPreferences() = delegate.listPreferences()
    fun savePreferences(preferences: AuthorityPreferences) = delegate.savePreferences(preferences)
    fun setUserIntent(authority: Authority, enabled: Boolean, explicitlyConfigured: Boolean = true) =
        delegate.setUserIntent(authority, enabled, explicitlyConfigured)
}

class AuthorityPreferencesRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val delegate by lazy { AuthorityPolicyRepository(db, clock) }
    fun get(): AuthorityPreferences = delegate.getPreferences()
    fun read(): AuthorityPreferences = get()
    fun save(preferences: AuthorityPreferences): AuthorityPreferences = delegate.savePreferences(preferences)
    fun setUserIntent(authority: Authority, enabled: Boolean, explicitlyConfigured: Boolean = true) =
        delegate.setUserIntent(authority, enabled, explicitlyConfigured)
}

private fun SqlRow.toAuthorityPolicy() = AuthorityPolicy(
    selectedAuthority = Authority.valueOf(string("selected_authority")),
    dangerousMode = DangerousMode.valueOf(string("dangerous_mode")),
    policyVersion = long("policy_version"),
    updatedAt = string("updated_at"),
)

private fun SqlRow.toAuthorityPreferences() = AuthorityPreferences(
    authority = Authority.valueOf(string("authority")),
    userIntentEnabled = long("user_intent_enabled") != 0L,
    explicitlyConfigured = long("explicitly_configured") != 0L,
    updatedAt = string("updated_at"),
)

class WorkspaceRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun get(id: String): Workspace? = db.query("SELECT * FROM workspaces WHERE id = ?", listOf(id))
        .singleOrNull()?.toWorkspace()

    fun find(id: String): Workspace? = get(id)

    fun list(enabledOnly: Boolean = false): List<Workspace> {
        val rows = if (enabledOnly) {
            db.query("SELECT * FROM workspaces WHERE enabled = 1 ORDER BY id")
        } else {
            db.query("SELECT * FROM workspaces ORDER BY id")
        }
        return rows.map { it.toWorkspace() }
    }

    fun save(workspace: Workspace): Workspace {
        validate(workspace)
        val now = clock()
        db.transaction {
            db.execute(
                "INSERT INTO workspaces(id,display_name,backend_type,root_reference,readable,writable,quota_bytes,max_file_bytes,enabled,revision,created_at,updated_at,scope) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name, backend_type=excluded.backend_type, root_reference=excluded.root_reference, readable=excluded.readable, writable=excluded.writable, quota_bytes=excluded.quota_bytes, max_file_bytes=excluded.max_file_bytes, enabled=excluded.enabled, revision=excluded.revision, updated_at=excluded.updated_at, scope=excluded.scope",
                listOf(
                    workspace.id, workspace.displayName, workspace.backendType.name, workspace.rootReference,
                    bool(workspace.readable), bool(workspace.writable), workspace.quotaBytes, workspace.maxFileBytes,
                    bool(workspace.enabled), workspace.revision, workspace.createdAt.ifBlank { now }, now, workspace.scope.name,
                ),
            )
        }
        return get(workspace.id) ?: error("Workspace save failed")
    }

    fun upsert(workspace: Workspace): Workspace = save(workspace)

    fun delete(id: String): Boolean {
        val existing = get(id) ?: return false
        db.execute("DELETE FROM saf_workspace_grants WHERE workspace_id = ?", listOf(existing.id))
        db.execute("DELETE FROM workspaces WHERE id = ?", listOf(id))
        return true
    }

    private fun validate(workspace: Workspace) {
        // Constructing Workspace performs the common bounds checks.  Keep these checks at the
        // persistence seam too, since rows may have been decoded by a different adapter.
        require(workspace.id.isNotBlank() && workspace.rootReference.isNotBlank()) { "Workspace is invalid" }
    }
}

private fun SqlRow.toWorkspace() = Workspace(
    id = string("id"),
    displayName = string("display_name"),
    backendType = WorkspaceBackendType.valueOf(string("backend_type")),
    rootReference = string("root_reference"),
    readable = long("readable") != 0L,
    writable = long("writable") != 0L,
    quotaBytes = columns["quota_bytes"]?.let { (it as? Number)?.toLong() ?: it.toString().toLongOrNull() },
    maxFileBytes = long("max_file_bytes"),
    enabled = long("enabled") != 0L,
    revision = long("revision"),
    createdAt = string("created_at"),
    updatedAt = string("updated_at"),
    scope = columns["scope"]?.toString()?.let { WorkspaceScope.valueOf(it) } ?: WorkspaceScope.SELECTED_DIRECTORY,
)

class CapabilityGrantRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun get(grantId: String): CapabilityGrant? = db.query(
        "SELECT * FROM capability_grants WHERE grant_id = ?", listOf(grantId),
    ).singleOrNull()?.toCapabilityGrant()

    fun find(grantId: String): CapabilityGrant? = get(grantId)

    fun list(agentId: String? = null, workspaceId: String? = null, includeRevoked: Boolean = false): List<CapabilityGrant> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (agentId != null) { clauses += "agent_id = ?"; args += agentId }
        if (workspaceId != null) { clauses += "workspace_id = ?"; args += workspaceId }
        if (!includeRevoked) clauses += "revoked_at IS NULL"
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")?.let { " WHERE $it" }.orEmpty()
        return db.query("SELECT * FROM capability_grants$where ORDER BY agent_id,grant_id", args)
            .map { it.toCapabilityGrant() }
    }

    fun forAgent(agentId: String, includeRevoked: Boolean = false): List<CapabilityGrant> = list(agentId, includeRevoked = includeRevoked)
    fun forWorkspace(workspaceId: String, includeRevoked: Boolean = false): List<CapabilityGrant> = list(workspaceId = workspaceId, includeRevoked = includeRevoked)

    fun save(grant: CapabilityGrant): CapabilityGrant {
        require(grant.createdAt.length <= 128) { "Grant timestamp is invalid" }
        db.transaction {
            val existing = get(grant.grantId)
            val actual = if (grant.createdAt.isBlank()) grant.copy(createdAt = existing?.createdAt ?: clock()) else grant
            if (actual.createdAt.length > 128) throw IllegalArgumentException("Grant timestamp is invalid")
            if (existing == null) {
                insert(actual)
            } else if (existing != actual) {
                require(actual.createdAt == existing.createdAt) { "Capability grant creation timestamp is immutable" }
                if (actual.revision <= existing.revision) {
                    throw AuthorityPolicyConflictException("Capability grant revision changed")
                }
                update(existing.revision, actual)
            }
            val persisted = get(actual.grantId)
            if (persisted != actual) {
                throw AuthorityPolicyConflictException("Capability grant update lost its compare-and-set race")
            }
        }
        return get(grant.grantId) ?: error("Capability grant save failed")
    }

    fun upsert(grant: CapabilityGrant): CapabilityGrant = save(grant)

    /**
     * Update a grant only when its current revision is [expectedRevision].
     * The next row must carry exactly the following revision, making stale
     * one-shot consumers and concurrent revocations fail closed.
     */
    fun compareAndSet(expectedRevision: Long, next: CapabilityGrant): Boolean {
        require(expectedRevision > 0) { "Expected grant revision must be positive" }
        require(next.revision == expectedRevision + 1) { "Next grant revision must increment by one" }
        return db.transaction {
            val current = get(next.grantId) ?: return@transaction false
            if (current.revision != expectedRevision) return@transaction false
            val actual = if (next.createdAt.isBlank()) next.copy(createdAt = current.createdAt) else next
            if (actual.createdAt != current.createdAt) return@transaction false
            update(expectedRevision, actual)
            get(actual.grantId) == actual
        }
    }

    private fun insert(grant: CapabilityGrant) {
        db.execute(
            "INSERT INTO capability_grants(grant_id,agent_id,skill_install_id,package_hash,capability,workspace_id,path_scope,lifetime,policy_version,created_at,expires_at,revoked_at,revision,task_id,session_id,consumed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                grant.grantId, grant.agentId, grant.skillInstallId, grant.packageHash, grant.capability.value,
                grant.workspaceId, grant.pathScope, grant.lifetime.name, grant.policyVersion,
                grant.createdAt, grant.expiresAt, grant.revokedAt, grant.revision,
                grant.taskId, grant.sessionId, grant.consumedAt,
            ),
        )
    }

    private fun update(expectedRevision: Long, grant: CapabilityGrant) {
        db.execute(
            "UPDATE capability_grants SET agent_id=?, skill_install_id=?, package_hash=?, capability=?, workspace_id=?, path_scope=?, lifetime=?, policy_version=?, expires_at=?, revoked_at=?, revision=?, task_id=?, session_id=?, consumed_at=? WHERE grant_id=? AND revision=?",
            listOf(
                grant.agentId, grant.skillInstallId, grant.packageHash, grant.capability.value,
                grant.workspaceId, grant.pathScope, grant.lifetime.name, grant.policyVersion,
                grant.expiresAt, grant.revokedAt, grant.revision,
                grant.taskId, grant.sessionId, grant.consumedAt, grant.grantId, expectedRevision,
            ),
        )
    }

    fun revoke(grantId: String, expectedRevision: Long? = null): Boolean {
        return db.transaction {
            val current = get(grantId) ?: return@transaction false
            if (expectedRevision != null && current.revision != expectedRevision) {
                throw AuthorityPolicyConflictException("Capability grant revision changed")
            }
            val where = if (expectedRevision == null) {
                "grant_id = ?"
            } else {
                "grant_id = ? AND revision = ?"
            }
            val args = mutableListOf<Any?>(clock(), grantId)
            if (expectedRevision != null) args += expectedRevision
            db.execute(
                "UPDATE capability_grants SET revoked_at = COALESCE(revoked_at, ?), revision = revision + 1 WHERE $where",
                args,
            )
            val persisted = get(grantId)
            if (persisted == null || persisted.revision != current.revision + 1 || !persisted.revoked) {
                throw AuthorityPolicyConflictException("Capability grant revoke lost its compare-and-set race")
            }
            true
        }
    }

    fun active(
        agentId: String,
        capability: CapabilityId,
        workspaceId: String? = null,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): List<CapabilityGrant> = list(agentId, workspaceId).filter {
        it.capability == capability && it.isActiveAt(clock(), taskIdentity, sessionIdentity)
    }

    /**
     * Durable ONCE consumption.  The revision predicate and consumed marker
     * form the CAS: exactly one caller can move an unconsumed row to consumed.
     */
    fun consumeOnce(
        grantId: String,
        expectedRevision: Long,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
        consumedAt: String = clock(),
    ): CapabilityGrant? {
        require(expectedRevision > 0) { "Expected grant revision must be positive" }
        require(consumedAt.isNotBlank() && consumedAt.length <= 128) { "Grant consumption timestamp is invalid" }
        return db.transaction {
            val current = get(grantId) ?: return@transaction null
            if (current.revision != expectedRevision ||
                current.lifetime != GrantLifetime.ONCE ||
                !current.isUsableFor(taskIdentity, sessionIdentity) ||
                !current.isActiveAt(consumedAt, taskIdentity, sessionIdentity)
            ) return@transaction null
            db.execute(
                "UPDATE capability_grants SET consumed_at = ?, revision = revision + 1 WHERE grant_id = ? AND revision = ? AND lifetime = 'ONCE' AND revoked_at IS NULL AND consumed_at IS NULL",
                listOf(consumedAt, grantId, expectedRevision),
            )
            val updated = get(grantId)
            if (updated?.revision == expectedRevision + 1 && updated.consumedAt == consumedAt) updated else null
        }
    }

    fun consume(
        grantId: String,
        expectedRevision: Long,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
        consumedAt: String = clock(),
    ): CapabilityGrant? = consumeOnce(grantId, expectedRevision, taskIdentity, sessionIdentity, consumedAt)

    fun tryConsumeOnce(
        grantId: String,
        expectedRevision: Long,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
        consumedAt: String = clock(),
    ): Boolean = consumeOnce(grantId, expectedRevision, taskIdentity, sessionIdentity, consumedAt) != null

    /** Snapshot bindings are immutable: a duplicate must describe exactly the same grant. */
    fun bindSnapshot(binding: SnapshotGrantBinding): SnapshotGrantBinding {
        db.transaction {
            val existing = db.query(
                "SELECT * FROM snapshot_grant_bindings WHERE snapshot_id = ? AND grant_id = ?",
                listOf(binding.snapshotId, binding.grantId),
            ).singleOrNull()
            // Reuse the stored timestamp when a caller uses the default blank boundAt.  This
            // keeps repeated bind calls idempotent without allowing any other field to mutate.
            val boundAt = binding.boundAt.ifBlank { existing?.string("bound_at") ?: clock() }
            val normalized = binding.copy(boundAt = boundAt)
            val grant = get(normalized.grantId)
                ?: throw IllegalArgumentException("Capability grant is missing")
            val snapshot = db.query(
                "SELECT agent_id FROM agent_snapshots WHERE id = ?",
                listOf(normalized.snapshotId),
            ).singleOrNull() ?: throw IllegalArgumentException("Agent snapshot is missing")
            require(snapshot.string("agent_id") == grant.agentId) {
                "Snapshot and capability grant belong to different Agents"
            }
            // Binding is snapshot metadata, so scoped grants are checked with
            // their persisted owner here; the runtime rechecks the caller's
            // task/session identity before dispatch.
            require(grant.isActiveAt(clock(), grant.taskId, grant.sessionId)) {
                "An expired or revoked capability grant cannot be snapshotted"
            }
            require(grant.capability == normalized.capability && grant.workspaceId == normalized.workspaceId &&
                grant.pathScope == normalized.pathScope && grant.policyVersion == normalized.policyVersion) {
                "Snapshot binding does not match the capability grant"
            }
            if (existing != null) {
                val stored = existing.toSnapshotGrantBinding()
                if (stored != normalized) throw IllegalArgumentException("Snapshot grant binding is immutable")
            } else {
                db.execute(
                    "INSERT INTO snapshot_grant_bindings(snapshot_id,grant_id,capability,workspace_id,path_scope,policy_version,bound_at) VALUES(?,?,?,?,?,?,?)",
                    listOf(normalized.snapshotId, normalized.grantId, normalized.capability.value, normalized.workspaceId, normalized.pathScope, normalized.policyVersion, normalized.boundAt),
                )
            }
        }
        return listSnapshotBindings(binding.snapshotId).single { it.grantId == binding.grantId && it.capability == binding.capability }
    }

    fun listSnapshotBindings(snapshotId: String): List<SnapshotGrantBinding> = db.query(
        "SELECT * FROM snapshot_grant_bindings WHERE snapshot_id = ? ORDER BY grant_id, capability", listOf(snapshotId),
    ).map { it.toSnapshotGrantBinding() }

    fun workspaceAcl(workspaceId: String, agentId: String? = null): List<CapabilityGrant> {
        val now = clock()
        val args = mutableListOf<Any?>(workspaceId, now)
        val predicate = if (agentId == null) {
            "workspace_id = ? AND (expires_at IS NULL OR expires_at > ?)"
        } else {
            "workspace_id = ? AND agent_id = ? AND (expires_at IS NULL OR expires_at > ?)".also { args.add(1, agentId) }
        }
        return db.query("SELECT * FROM workspace_acl WHERE $predicate ORDER BY agent_id,grant_id", args)
            .map { it.toCapabilityGrant() }
            .filter { it.isActiveAt(now, it.taskId, it.sessionId) }
    }
}

private fun SqlRow.toCapabilityGrant() = CapabilityGrant(
    grantId = string("grant_id"),
    agentId = string("agent_id"),
    skillInstallId = string("skill_install_id").ifBlank { null },
    packageHash = string("package_hash").ifBlank { null },
    capability = CapabilityId(string("capability")),
    workspaceId = string("workspace_id").ifBlank { null },
    pathScope = string("path_scope").ifBlank { null },
    lifetime = GrantLifetime.valueOf(string("lifetime")),
    policyVersion = long("policy_version"),
    createdAt = string("created_at"),
    expiresAt = string("expires_at").ifBlank { null },
    revokedAt = string("revoked_at").ifBlank { null },
    revision = long("revision"),
    taskId = string("task_id").ifBlank { null },
    sessionId = string("session_id").ifBlank { null },
    consumedAt = string("consumed_at").ifBlank { null },
)

private fun CapabilityGrant.isActiveAt(
    nowIso: String,
    taskIdentity: String? = null,
    sessionIdentity: String? = null,
): Boolean {
    if (!isUsableFor(taskIdentity, sessionIdentity)) return false
    if (expiresAt.isNullOrBlank()) return true
    val now = runCatching { Instant.parse(nowIso) }.getOrNull() ?: return false
    return isActiveFor(now, taskIdentity, sessionIdentity)
}

private fun SqlRow.toSnapshotGrantBinding() = SnapshotGrantBinding(
    snapshotId = string("snapshot_id"),
    grantId = string("grant_id"),
    capability = CapabilityId(string("capability")),
    workspaceId = string("workspace_id").ifBlank { null },
    pathScope = string("path_scope").ifBlank { null },
    policyVersion = long("policy_version"),
    boundAt = string("bound_at"),
)

/** Durable approval records contain only digests and closed metadata, never raw arguments. */
class ApprovalRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
    private val pendingTtlMillis: Long = DEFAULT_PENDING_TTL_MILLIS,
) {
    private data class Pending(val record: ApprovalRecord, val createdMillis: Long)
    private val pending = linkedMapOf<String, Pending>()

    init {
        require(pendingTtlMillis > 0) { "Pending approval TTL must be positive" }
    }

    fun get(approvalId: String): ApprovalRecord? {
        purgeExpired()
        return pending[approvalId]?.record ?: persisted(approvalId)
    }

    fun find(approvalId: String): ApprovalRecord? = get(approvalId)

    fun list(agentId: String? = null): List<ApprovalRecord> {
        purgeExpired()
        val rows = if (agentId == null) {
            db.query("SELECT * FROM approval_records ORDER BY created_at, approval_id")
        } else {
            db.query("SELECT * FROM approval_records WHERE agent_id = ? ORDER BY created_at, approval_id", listOf(agentId))
        }
        val durable = rows.map { it.toApprovalRecord() }
        val memory = pending.values.map { it.record }.filter { agentId == null || it.agentId == agentId }
        return (durable + memory).sortedWith(compareBy<ApprovalRecord> { it.createdAt }.thenBy { it.approvalId })
    }

    /**
     * Generates the internal request UUID at the Runtime boundary.  A model callId remains an
     * association only and is never used as the durable approval identity.
     */
    fun create(
        callId: String,
        agentId: String,
        configSnapshotHash: String,
        selectedAuthority: Authority,
        dangerousMode: DangerousMode,
        policyVersion: Long,
        skillId: String? = null,
        commandHash: String? = null,
        cwdHash: String? = null,
        toolSchemaVersion: Int = 1,
        expiresAt: String? = null,
    ): ApprovalRecord {
        val record = ApprovalRecord(
            approvalId = UUID.randomUUID().toString(),
            requestId = UUID.randomUUID().toString(),
            callId = callId,
            agentId = agentId,
            skillId = skillId,
            commandHash = commandHash,
            cwdHash = cwdHash,
            selectedAuthority = selectedAuthority,
            dangerousMode = dangerousMode,
            toolSchemaVersion = toolSchemaVersion,
            policyVersion = policyVersion,
            configSnapshotHash = configSnapshotHash,
            decision = ApprovalDecision.PENDING,
            createdAt = clock(),
            expiresAt = expiresAt,
        )
        pending[record.approvalId] = Pending(record, System.currentTimeMillis())
        return record
    }

    fun save(record: ApprovalRecord): ApprovalRecord {
        require(record.decision != ApprovalDecision.PENDING) {
            "Pending approvals are process-local and must not be persisted"
        }
        val actual = if (record.requestId.isBlank()) record.copy(requestId = UUID.randomUUID().toString()) else record
        pending.remove(actual.approvalId)
        return persistTerminalRow(actual)
    }

    fun persistTerminal(record: ApprovalRecord): ApprovalRecord = save(record)

    private fun persistTerminalRow(record: ApprovalRecord): ApprovalRecord {
        db.transaction {
            val existing = db.query("SELECT * FROM approval_records WHERE approval_id = ?", listOf(record.approvalId))
                .singleOrNull()
            if (existing != null) {
                if (existing.toApprovalRecord() != record) throw IllegalArgumentException("Approval record is immutable")
                return@transaction
            }
            val requestCollision = db.query("SELECT approval_id FROM approval_records WHERE request_id = ?", listOf(record.requestId))
            if (requestCollision.isNotEmpty()) throw IllegalArgumentException("Approval request id is already used")
            db.execute(
                "INSERT INTO approval_records(approval_id,request_id,call_id,agent_id,skill_id,command_hash,cwd_hash,selected_authority,dangerous_mode,tool_schema_version,policy_version,config_snapshot_hash,decision,created_at,expires_at,consumed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(
                    record.approvalId, record.requestId, record.callId, record.agentId, record.skillId,
                    record.commandHash, record.cwdHash, record.selectedAuthority.name, record.dangerousMode.name,
                    record.toolSchemaVersion, record.policyVersion, record.configSnapshotHash, record.decision.name,
                    record.createdAt, record.expiresAt, record.consumedAt,
                ),
            )
        }
        return persisted(record.approvalId) ?: error("Approval save failed")
    }

    fun approve(approvalId: String): ApprovalRecord = transition(approvalId, ApprovalDecision.APPROVED)
    fun deny(approvalId: String): ApprovalRecord = transition(approvalId, ApprovalDecision.DENIED)

    /** Consume once; replaying an already consumed request always fails closed. */
    fun consume(approvalId: String, expected: ApprovalRecord? = null): ApprovalRecord {
        return db.transaction {
            val current = persisted(approvalId) ?: throw IllegalArgumentException("Approval record is missing")
            if (expected != null && !sameBinding(current, expected)) {
                throw AuthorityPolicyConflictException("Approval binding changed")
            }
            if (current.decision != ApprovalDecision.APPROVED || current.consumedAt != null) {
                throw IllegalStateException("Approval is not available for one-time consumption")
            }
            db.execute(
                "UPDATE approval_records SET decision = ?, consumed_at = ? WHERE approval_id = ? AND decision = ? AND consumed_at IS NULL",
                listOf(ApprovalDecision.CONSUMED.name, clock(), approvalId, ApprovalDecision.APPROVED.name),
            )
            persisted(approvalId) ?: error("Approval consume failed")
        }
    }

    fun isValidFor(
        approvalId: String,
        callId: String,
        agentId: String,
        commandHash: String?,
        cwdHash: String?,
        selectedAuthority: Authority,
        dangerousMode: DangerousMode,
        policyVersion: Long,
        configSnapshotHash: String,
        toolSchemaVersion: Int,
    ): Boolean {
        val record = persisted(approvalId) ?: return false
        return record.decision == ApprovalDecision.APPROVED && record.consumedAt == null &&
            record.callId == callId && record.agentId == agentId && record.commandHash == commandHash &&
            record.cwdHash == cwdHash && record.selectedAuthority == selectedAuthority &&
            record.dangerousMode == dangerousMode && record.policyVersion == policyVersion &&
            record.configSnapshotHash == configSnapshotHash && record.toolSchemaVersion == toolSchemaVersion
    }

    private fun transition(approvalId: String, decision: ApprovalDecision): ApprovalRecord {
        require(decision != ApprovalDecision.PENDING && decision != ApprovalDecision.CONSUMED) {
            "Invalid approval transition"
        }
        purgeExpired()
        val memory = pending.remove(approvalId)
        if (memory != null) return persistTerminalRow(memory.record.copy(decision = decision))
        throw IllegalStateException("Approval is not pending in this process")
    }

    fun expire(approvalId: String): ApprovalRecord = transition(approvalId, ApprovalDecision.EXPIRED)

    private fun persisted(approvalId: String): ApprovalRecord? = db.query(
        "SELECT * FROM approval_records WHERE approval_id = ?", listOf(approvalId),
    ).singleOrNull()?.toApprovalRecord()

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val expired = pending.values.filter { now - it.createdMillis >= pendingTtlMillis }
        expired.forEach { item ->
            pending.remove(item.record.approvalId)
            persistTerminalRow(item.record.copy(decision = ApprovalDecision.EXPIRED))
        }
    }

private fun sameBinding(left: ApprovalRecord, right: ApprovalRecord): Boolean =
        left.approvalId == right.approvalId && left.requestId == right.requestId && left.callId == right.callId &&
            left.agentId == right.agentId && left.skillId == right.skillId && left.commandHash == right.commandHash &&
            left.cwdHash == right.cwdHash && left.selectedAuthority == right.selectedAuthority &&
            left.dangerousMode == right.dangerousMode && left.toolSchemaVersion == right.toolSchemaVersion &&
            left.policyVersion == right.policyVersion && left.configSnapshotHash == right.configSnapshotHash

    private companion object {
        const val DEFAULT_PENDING_TTL_MILLIS = 5L * 60L * 1000L
    }
}

private fun SqlRow.toApprovalRecord() = ApprovalRecord(
    approvalId = string("approval_id"),
    requestId = string("request_id"),
    callId = string("call_id"),
    agentId = string("agent_id"),
    skillId = string("skill_id").ifBlank { null },
    commandHash = string("command_hash").ifBlank { null },
    cwdHash = string("cwd_hash").ifBlank { null },
    selectedAuthority = Authority.valueOf(string("selected_authority")),
    dangerousMode = DangerousMode.valueOf(string("dangerous_mode")),
    toolSchemaVersion = long("tool_schema_version").toInt(),
    policyVersion = long("policy_version"),
    configSnapshotHash = string("config_snapshot_hash"),
    decision = ApprovalDecision.valueOf(string("decision")),
    createdAt = string("created_at"),
    expiresAt = string("expires_at").ifBlank { null },
    consumedAt = string("consumed_at").ifBlank { null },
)

/** Persistence for the Android Storage Access Framework's durable local binding. */
class SafWorkspaceGrantRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun get(workspaceId: String): SafWorkspaceGrant? = db.query(
        "SELECT * FROM saf_workspace_grants WHERE workspace_id = ?", listOf(workspaceId),
    ).singleOrNull()?.toSafWorkspaceGrant()

    fun find(workspaceId: String): SafWorkspaceGrant? = get(workspaceId)

    fun list(includeRevoked: Boolean = true): List<SafWorkspaceGrant> {
        val sql = if (includeRevoked) {
            "SELECT * FROM saf_workspace_grants ORDER BY workspace_id"
        } else {
            "SELECT * FROM saf_workspace_grants WHERE status <> ? ORDER BY workspace_id"
        }
        val args = if (includeRevoked) emptyList() else listOf(SafGrantStatus.REVOKED.name)
        return db.query(sql, args).map { it.toSafWorkspaceGrant() }
    }

    fun save(grant: SafWorkspaceGrant): SafWorkspaceGrant {
        require(grant.status != SafGrantStatus.GRANT_LOST || grant.lostAt != null) {
            "A lost SAF grant must record when it was lost"
        }
        val now = clock()
        db.transaction {
            db.execute(
                "INSERT INTO saf_workspace_grants(workspace_id,uri_reference,read_granted,write_granted,persisted_flags,status,created_at,lost_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(workspace_id) DO UPDATE SET uri_reference=excluded.uri_reference, read_granted=excluded.read_granted, write_granted=excluded.write_granted, persisted_flags=excluded.persisted_flags, status=excluded.status, lost_at=excluded.lost_at, updated_at=excluded.updated_at",
                listOf(
                    grant.workspaceId, grant.uriReference, bool(grant.readGranted), bool(grant.writeGranted),
                    grant.persistedFlags, grant.status.name, grant.createdAt.ifBlank { now }, grant.lostAt, now,
                ),
            )
        }
        return get(grant.workspaceId) ?: error("SAF grant save failed")
    }

    fun upsert(grant: SafWorkspaceGrant): SafWorkspaceGrant = save(grant)

    /** A lost platform grant is retained so the user can re-authorize it; it is not deleted. */
    fun markLost(workspaceId: String): SafWorkspaceGrant? {
        val current = get(workspaceId) ?: return null
        if (current.status == SafGrantStatus.REVOKED) return current
        val now = clock()
        db.execute(
            "UPDATE saf_workspace_grants SET status = ?, lost_at = COALESCE(lost_at, ?), updated_at = ? WHERE workspace_id = ?",
            listOf(SafGrantStatus.GRANT_LOST.name, now, now, workspaceId),
        )
        return get(workspaceId)
    }

    fun markRevoked(workspaceId: String): SafWorkspaceGrant? {
        if (get(workspaceId) == null) return null
        db.execute(
            "UPDATE saf_workspace_grants SET status = ?, updated_at = ? WHERE workspace_id = ?",
            listOf(SafGrantStatus.REVOKED.name, clock(), workspaceId),
        )
        return get(workspaceId)
    }
}

private fun SqlRow.toSafWorkspaceGrant() = SafWorkspaceGrant(
    workspaceId = string("workspace_id"),
    uriReference = string("uri_reference"),
    readGranted = long("read_granted") != 0L,
    writeGranted = long("write_granted") != 0L,
    persistedFlags = long("persisted_flags").toInt(),
    status = SafGrantStatus.valueOf(string("status")),
    createdAt = string("created_at"),
    lostAt = string("lost_at").ifBlank { null },
    updatedAt = string("updated_at"),
)

/** The single durable desktop identity is intentionally separate from trust state. */
class DesktopIdentityRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun get(): runtime.mobileagent.domain.DesktopIdentity? = db.query(
        "SELECT desktop_id, app_instance_id, updated_at FROM desktop_identity WHERE id = 1",
    ).singleOrNull()?.toDesktopIdentity()

    fun save(identity: runtime.mobileagent.domain.DesktopIdentity): runtime.mobileagent.domain.DesktopIdentity {
        db.execute(
            "INSERT INTO desktop_identity(id,desktop_id,app_instance_id,updated_at) VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET desktop_id=excluded.desktop_id, app_instance_id=excluded.app_instance_id, updated_at=excluded.updated_at",
            listOf(identity.desktopId, identity.appInstanceId, clock()),
        )
        return get() ?: error("Desktop identity save failed")
    }
}

private fun SqlRow.toDesktopIdentity() = runtime.mobileagent.domain.DesktopIdentity(
    desktopId = string("desktop_id"),
    appInstanceId = string("app_instance_id"),
    updatedAt = string("updated_at"),
)

/** Trust lifecycle: disconnect/reauth-required preserves the secret reference; forget is explicit. */
class DesktopTrustRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun get(desktopId: String): DesktopTrust? = db.query(
        "SELECT * FROM desktop_trust WHERE desktop_id = ?", listOf(desktopId),
    ).singleOrNull()?.toDesktopTrust()

    fun find(desktopId: String): DesktopTrust? = get(desktopId)

    fun list(includeForgotten: Boolean = false): List<DesktopTrust> {
        val sql = if (includeForgotten) {
            "SELECT * FROM desktop_trust ORDER BY desktop_id"
        } else {
            "SELECT * FROM desktop_trust WHERE status <> ? ORDER BY desktop_id"
        }
        val args = if (includeForgotten) emptyList() else listOf(DesktopTrustStatus.FORGOTTEN.name)
        return db.query(sql, args).map { it.toDesktopTrust() }
    }

    fun trust(desktopId: String, appInstanceId: String, secretRef: String): DesktopTrust = save(
        DesktopTrust(
            desktopId = desktopId,
            appInstanceId = appInstanceId,
            secretRef = secretRef,
            status = DesktopTrustStatus.TRUSTED,
            createdAt = get(desktopId)?.createdAt.orEmpty(),
            lastSeenAt = clock(),
            revision = (get(desktopId)?.revision ?: 0L) + 1L,
        ),
    )

    fun save(trust: DesktopTrust): DesktopTrust {
        val now = clock()
        val existing = get(trust.desktopId)
        val actual = if (trust.createdAt.isBlank()) trust.copy(createdAt = existing?.createdAt ?: now) else trust
        if (existing != null && existing != actual && actual.revision <= existing.revision) {
            throw AuthorityPolicyConflictException("Desktop trust revision changed")
        }
        db.execute(
            "INSERT INTO desktop_trust(desktop_id,app_instance_id,secret_ref,status,created_at,last_seen_at,forgotten_at,revision) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(desktop_id) DO UPDATE SET app_instance_id=excluded.app_instance_id, secret_ref=excluded.secret_ref, status=excluded.status, last_seen_at=excluded.last_seen_at, forgotten_at=excluded.forgotten_at, revision=excluded.revision",
            listOf(
                actual.desktopId, actual.appInstanceId, actual.secretRef, actual.status.name,
                actual.createdAt, actual.lastSeenAt, actual.forgottenAt, actual.revision,
            ),
        )
        return get(actual.desktopId) ?: error("Desktop trust save failed")
    }

    fun markReauthRequired(desktopId: String): DesktopTrust? = transition(desktopId, DesktopTrustStatus.REAUTH_REQUIRED)

    /** Connection state is ephemeral; touching last_seen does not change trust or secret binding. */
    fun markSeen(desktopId: String): DesktopTrust? {
        val current = get(desktopId) ?: return null
        db.execute(
            "UPDATE desktop_trust SET last_seen_at = ? WHERE desktop_id = ?",
            listOf(clock(), desktopId),
        )
        return get(current.desktopId)
    }

    /** A disconnect is not a forget operation and intentionally leaves the durable row unchanged. */
    fun markDisconnected(desktopId: String): DesktopTrust? = get(desktopId)

    fun reauthenticate(desktopId: String): DesktopTrust? = transition(desktopId, DesktopTrustStatus.TRUSTED)
    fun reauth(desktopId: String): DesktopTrust? = reauthenticate(desktopId)
    fun markUnauthorized(desktopId: String): DesktopTrust? = markReauthRequired(desktopId)

    /** Explicit forget retires the trust reference from SecretInventory's live set. */
    fun forget(desktopId: String): DesktopTrust? {
        val current = get(desktopId) ?: return null
        if (current.status == DesktopTrustStatus.FORGOTTEN) return current
        val now = clock()
        db.execute(
            "UPDATE desktop_trust SET status = ?, forgotten_at = COALESCE(forgotten_at, ?), revision = revision + 1 WHERE desktop_id = ?",
            listOf(DesktopTrustStatus.FORGOTTEN.name, now, desktopId),
        )
        return get(desktopId)
    }

    private fun transition(desktopId: String, status: DesktopTrustStatus): DesktopTrust? {
        val current = get(desktopId) ?: return null
        if (current.status == DesktopTrustStatus.FORGOTTEN) return current
        db.execute(
            "UPDATE desktop_trust SET status = ?, last_seen_at = ?, revision = revision + 1 WHERE desktop_id = ?",
            listOf(status.name, clock(), desktopId),
        )
        return get(desktopId)
    }
}

private fun SqlRow.toDesktopTrust() = DesktopTrust(
    desktopId = string("desktop_id"),
    appInstanceId = string("app_instance_id"),
    secretRef = string("secret_ref"),
    status = DesktopTrustStatus.valueOf(string("status")),
    createdAt = string("created_at"),
    lastSeenAt = string("last_seen_at").ifBlank { null },
    forgottenAt = string("forgotten_at").ifBlank { null },
    revision = long("revision"),
)

private fun bool(value: Boolean): Int = if (value) 1 else 0
