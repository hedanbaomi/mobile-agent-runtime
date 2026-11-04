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
    /**
     * Selection and ADB mode edits fence old runs, but do not revoke an existing Root grant.
     * Only currently valid persistent permissions move with the policy CAS; frozen snapshots
     * and approvals are never rewritten. Explicit Root/grant revocation uses its own revisions.
     */
    internal fun carryForwardPolicyEdit(previous: AuthorityPolicy, nextPolicy: AuthorityPolicy) {
        require(nextPolicy.policyVersion == Math.addExact(previous.policyVersion, 1L))
        require(AuthorityPolicyRepository(db, clock).getPolicy() == nextPolicy)
        for (authority in listOf(Authority.ROOT_DIRECT, Authority.SHIZUKU_ROOT)) {
            carryAuthorityPolicy(authority, previous, nextPolicy.policyVersion)
        }
    }

    private fun carryAuthorityPolicy(authority: Authority, previous: AuthorityPolicy, nextPolicyVersion: Long) {
        val roots = RootAuthorityRepository(db, clock)
        val state = roots.get(authority)
        if (!state.consent.enabled || state.consent.policyVersion != previous.policyVersion) return
        val now = Instant.parse(clock())
        val canonical = CapabilityGrantRepository(db, clock)
        val candidates = db.query(
            "SELECT g.grant_id FROM capability_grants g JOIN root_grant_bindings r ON r.grant_id=g.grant_id " +
                "WHERE r.authority=? AND r.policy_version=? AND g.policy_version=? AND g.lifetime='PERSISTENT' " +
                "AND g.revoked_at IS NULL AND g.consumed_at IS NULL",
            listOf(authority.name,previous.policyVersion,previous.policyVersion),
        )
        val eligible = candidates.mapNotNull { row ->
            val grant = canonical.get(row.string("grant_id")) ?: return@mapNotNull null
            if (grant.lifetime != GrantLifetime.PERSISTENT || !grant.isActiveFor(now, null, null) ||
                !RootApprovalTools.isSupportedCapability(grant.capability)
            ) return@mapNotNull null
            val workspaceId = grant.workspaceId ?: return@mapNotNull null
            val origin = RootWorkspaceRepository(db).getOrigin(workspaceId) ?: return@mapNotNull null
            // A paused Shell keeps its durable grants eligible for policy bookkeeping. This only
            // advances policy_version; it neither re-enables Shell nor makes the grant usable.
            if (origin.authority != authority ||
                !isDurablyBound(grant, origin, previous, requireShellConsent = false)
            ) return@mapNotNull null
            val workspace = WorkspaceRepository(db, clock).get(workspaceId) ?: return@mapNotNull null
            if (!workspace.enabled || !workspace.readable || workspace.backendType != WorkspaceBackendType.PRIVILEGED ||
                workspace.rootReference != "root:${origin.generationId}" ||
                PrivilegedWorkspaceBindingRepository(db).get(workspaceId) != null ||
                (RootApprovalTools.requiresWritableWorkspace(grant.capability) && !workspace.writable)
            ) return@mapNotNull null
            grant to requireNotNull(get(grant.grantId))
        }
        eligible.forEach { (grant, binding) ->
            val next = grant.copy(policyVersion = nextPolicyVersion, revision = Math.addExact(grant.revision, 1L))
            check(canonical.compareAndSet(grant.revision, next)) { "Root durable policy carry lost its grant CAS" }
            db.execute(
                "UPDATE root_grant_bindings SET policy_version=?,grant_revision=? WHERE grant_id=? AND policy_version=? AND grant_revision=? AND generation_id=?",
                listOf(nextPolicyVersion,next.revision,grant.grantId,previous.policyVersion,grant.revision,binding.generationId),
            )
            check(get(grant.grantId) == binding.copy(policyVersion = nextPolicyVersion, grantRevision = next.revision)) {
                "Root durable policy carry lost its binding CAS"
            }
        }
        db.execute(
            "UPDATE root_authority_state SET policy_version=?,revision=?,updated_at=? WHERE authority=? AND policy_version=? AND revision=? AND enabled=1",
            listOf(nextPolicyVersion,Math.addExact(state.revision,1L),clock(),authority.name,previous.policyVersion,state.revision),
        )
        check(roots.get(authority) == state.copy(
            consent = state.consent.copy(policyVersion = nextPolicyVersion), revision = Math.addExact(state.revision,1L),
        )) { "Root durable policy carry lost its consent CAS" }
    }

    fun create(grant: CapabilityGrant, execution: RootExecutionStamp): RootGrantBinding = db.transaction {
        val identity = execution.identity
        val authority = identity.authority
        val root = requireLocalRootState(db,execution)
        require(RootApprovalTools.isSupportedCapability(grant.capability)) { "Unsupported Root capability" }
        if (grant.capability.value == CapabilityId.SHELL_EXECUTE) {
            require(
                root.consent.shellEnabled &&
                    execution.shellConsentRevision > 0 &&
                    root.consent.shellRevision == execution.shellConsentRevision,
            ) { "Root shell grant requires the channel's shell consent" }
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
    fun isDurablyBoundGrant(grant: CapabilityGrant, origin: RootWorkspaceOrigin, policy: AuthorityPolicy): Boolean =
        isDurablyBound(grant, origin, policy, requireShellConsent = true)

    /**
     * [requireShellConsent] is false only for the policy-version carry while Root Shell is
     * paused. That bookkeeping keeps an already-valid persistent grant from being stranded at an
     * old policy version, but it never makes the grant usable: authorization and display paths
     * keep the default and still require the live Shell consent.
     */
    private fun isDurablyBound(
        grant: CapabilityGrant,
        origin: RootWorkspaceOrigin,
        policy: AuthorityPolicy,
        requireShellConsent: Boolean,
    ): Boolean {
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
        if (grant.capability.value == CapabilityId.SHELL_EXECUTE && requireShellConsent) {
            return state.consent.shellEnabled
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
