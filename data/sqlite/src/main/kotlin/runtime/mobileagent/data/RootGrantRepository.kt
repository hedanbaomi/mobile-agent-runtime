// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.time.Instant
import java.util.UUID
import runtime.mobileagent.domain.*

/**
 * Local UI control plane only. Creates the canonical grant and Root binding atomically;
 * cannot upgrade an existing grant. A verified adapter must supply the execution stamp.
 * This is not an OS authentication or snapshot-admission API.
 */
class RootGrantRepository(private val db: SqlConnection, private val clock: () -> String = { Utc.nowIso() }) {
    fun create(grant: CapabilityGrant, execution: RootExecutionStamp): RootGrantBinding = db.transaction {
        val identity = execution.identity
        val authority = identity.authority
        val root = requireLocalRootState(db,execution)
        require(RootApprovalTools.isSupportedCapability(grant.capability)) { "Unsupported Root capability" }
        if (grant.capability.value == CapabilityId.SHELL_EXECUTE) {
            require(
                root.consent.shellEnabled &&
                    execution.shellConsentRevision > 0 &&
                    root.consent.shellRevision == execution.shellConsentRevision &&
                    AuthorityPolicyRepository(db).getPolicy().dangerousMode != DangerousMode.DISABLED,
            ) { "Root shell grant requires shell consent and active DangerousMode" }
        }
        require(grant.workspaceId != null && grant.skillInstallId == null && grant.packageHash == null)
        require(grant.revision == 1L && grant.policyVersion == execution.policyVersion)
        require(grant.isActiveFor(Instant.parse(clock()), grant.taskId, grant.sessionId))
        val workspace = WorkspaceRepository(db).get(requireNotNull(grant.workspaceId))
        require(
            workspace != null &&
                workspace.backendType == WorkspaceBackendType.PRIVILEGED &&
                workspace.enabled &&
                workspace.readable &&
                (!RootApprovalTools.requiresWritableWorkspace(grant.capability) || workspace.writable),
        )
        val origin = RootWorkspaceRepository(db).requireOrigin(requireNotNull(grant.workspaceId),execution)
        val canonical = CapabilityGrantRepository(db, clock)
        require(canonical.get(grant.grantId) == null) { "Root requires a new canonical grant" }
        val binding = RootGrantBinding(
            grant.grantId, grant.revision, requireNotNull(grant.workspaceId), origin.generationId, authority,
            identity.appInstanceId, identity.packageName, identity.appUid, identity.androidUserId, identity.signingSha256,
            root.consent.revision, execution.policyVersion, UUID.randomUUID().toString(),
        )
        canonical.save(grant)
        db.execute(
            "INSERT INTO root_grant_bindings(grant_id,grant_revision,workspace_id,workspace_generation_id,authority,privilege_tier,app_instance_id,package_name,app_uid,android_user_id,signing_sha256,consent_revision,policy_version,generation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(binding.grantId,binding.grantRevision,binding.workspaceId,binding.workspaceGenerationId,binding.authority.name,
                binding.privilegeTier.name,binding.appInstanceId,binding.packageName,binding.appUid,binding.androidUserId,
                binding.signingSha256,binding.consentRevision,binding.policyVersion,binding.generationId),
        )
        check(get(grant.grantId) == binding) { "Root grant binding was not persisted" }
        binding
    }

    fun get(grantId: String): RootGrantBinding? = db.query(
        "SELECT * FROM root_grant_bindings WHERE grant_id=? AND workspace_generation_id<>'' AND package_name<>''", listOf(grantId),
    ).singleOrNull()?.let { row ->
        RootGrantBinding(
            row.string("grant_id"), row.long("grant_revision"), row.string("workspace_id"), row.string("workspace_generation_id"),
            Authority.valueOf(row.string("authority")), row.string("app_instance_id"), row.string("package_name"),
            Math.toIntExact(row.long("app_uid")), Math.toIntExact(row.long("android_user_id")),
            row.string("signing_sha256"), row.long("consent_revision"), row.long("policy_version"),
            row.string("generation_id"), PrivilegeTier.valueOf(row.string("privilege_tier")),
        )
    }

    /**
     * Durable Root grant validity for Agent default & UI display.
     * Deliberately checks RootGrantBinding, workspace generation, consent revision and policy version,
     * while NOT comparing identityEpoch so valid persistent Root grants remain reusable across sessions.
     */
    fun isDurablyBoundGrant(grant: CapabilityGrant, origin: RootWorkspaceOrigin, policy: AuthorityPolicy): Boolean {
        if (grant.workspaceId != origin.workspaceId || grant.skillInstallId != null || grant.packageHash != null) {
            return false
        }
        val binding = get(grant.grantId) ?: return false
        if (binding.grantRevision != grant.revision ||
            binding.workspaceId != origin.workspaceId ||
            binding.workspaceGenerationId != origin.generationId ||
            binding.authority != origin.authority ||
            binding.policyVersion != policy.policyVersion ||
            grant.policyVersion != policy.policyVersion ||
            binding.appInstanceId != origin.appInstanceId ||
            binding.packageName != origin.packageName ||
            binding.appUid != origin.appUid ||
            binding.androidUserId != origin.androidUserId ||
            binding.signingSha256 != origin.signingSha256
        ) {
            return false
        }
        val state = RootAuthorityRepository(db, clock).get(origin.authority)
        if (!state.consent.enabled ||
            state.consent.revision != binding.consentRevision ||
            state.consent.policyVersion != policy.policyVersion
        ) {
            return false
        }
        if (grant.capability.value == CapabilityId.SHELL_EXECUTE) {
            return state.consent.shellEnabled && policy.dangerousMode != DangerousMode.DISABLED
        }
        return true
    }

    /**
     * Returns null when [workspaceId] is not Root-backed so ordinary workspaces keep their
     * standard check without ever requiring or manufacturing a RootGrantBinding.
     */
    fun hasDurablePersistentDefaultGrant(agentId: String, workspaceId: String, now: Instant): Boolean? {
        val origin = RootWorkspaceRepository(db, clock).getOrigin(workspaceId)
        if (origin == null) {
            val isRootRef = WorkspaceRepository(db, clock).get(workspaceId)?.rootReference?.startsWith("root:") == true
            return if (isRootRef) false else null
        }
        val policy = AuthorityPolicyRepository(db, clock).getPolicy()
        return CapabilityGrantRepository(db, clock).forAgent(agentId, includeRevoked = false).any { grant ->
            grant.workspaceId == workspaceId &&
                grant.lifetime == GrantLifetime.PERSISTENT &&
                grant.isActiveFor(now, null, null) &&
                isDurablyBoundGrant(grant, origin, policy)
        }
    }

    companion object {
        internal fun install(db: SqlConnection) {
            db.execute(
                "CREATE TABLE IF NOT EXISTS root_grant_bindings(" +
                    "grant_id TEXT NOT NULL PRIMARY KEY REFERENCES capability_grants(grant_id) ON DELETE CASCADE," +
                    "grant_revision INTEGER NOT NULL CHECK(grant_revision>0)," +
                    "workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE," +
                    "workspace_generation_id TEXT NOT NULL," +
                    "authority TEXT NOT NULL CHECK(authority IN('ROOT_DIRECT','SHIZUKU_ROOT'))," +
                    "privilege_tier TEXT NOT NULL CHECK(privilege_tier='ROOT')," +
                    "app_instance_id TEXT NOT NULL,package_name TEXT NOT NULL,app_uid INTEGER NOT NULL,android_user_id INTEGER NOT NULL," +
                    "signing_sha256 TEXT NOT NULL,consent_revision INTEGER NOT NULL CHECK(consent_revision>0)," +
                    "policy_version INTEGER NOT NULL CHECK(policy_version>=0),generation_id TEXT NOT NULL UNIQUE)",
            )
            // Early unpublished WIP databases had weaker Root bindings. Preserve their rows,
            // but never infer the missing provenance: empty additions remain unusable.
            val columns = db.query("PRAGMA table_info(root_grant_bindings)").map { it.string("name") }.toSet()
            listOf("workspace_generation_id","package_name").filterNot(columns::contains).forEach { column ->
                db.execute("ALTER TABLE root_grant_bindings ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
