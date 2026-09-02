// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.time.Instant
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SnapshotBinding
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.skills.tooling.ToolCapabilities

fun interface CapabilityGrantReader {
    fun read(agentId: String, workspaceId: String?): Iterable<CapabilityGrant>
}

fun interface SnapshotGrantBindingReader {
    fun read(snapshotId: String): Iterable<SnapshotGrantBinding>
}

private val EMPTY_CAPABILITY_GRANT_READER = CapabilityGrantReader { _, _ -> emptyList() }
private val EMPTY_SNAPSHOT_BINDING_READER = SnapshotGrantBindingReader { emptyList() }

/**
 * Result of the one canonical pre-dispatch grant gate.  The distinction is
 * useful to callers for audit/telemetry: an existing non-ONCE grant requires
 * no durable mutation, while an ONCE grant was admitted only after its CAS
 * consumer accepted it.
 */
enum class DispatchAuthorization {
    DENIED,
    ALLOWED_EXISTING_GRANT,
    ALLOWED_AFTER_ONCE_CONSUMPTION,
}

/**
 * Effective capability material for one immutable run snapshot.  Grants are
 * the canonical domain records; [bindings] prove that those exact grant ids
 * were bound to this snapshot.  A caller must resolve again immediately before
 * dispatch so revocation, expiry, revision and policy changes are observed.
 */
data class EffectiveCapabilitySnapshot(
    val agentId: String,
    val snapshotId: String,
    val capabilities: Set<CapabilityId>,
    val perSkillCapabilities: Map<String, Set<CapabilityId>>,
    val grants: List<CapabilityGrant>,
    val bindings: List<SnapshotGrantBinding>,
    val policyVersion: Long,
    val configSnapshotHash: String,
    val grantRevisions: Map<String, Long> = grants.associate { it.grantId to it.revision },
    /** Identity used to admit TASK/SESSION grants; retained with the snapshot for auditability. */
    val taskIdentity: String? = null,
    val sessionIdentity: String? = null,
) {
    fun allows(capability: CapabilityId, skillId: String? = null, workspaceId: String? = null, path: String? = null, write: Boolean = false): Boolean {
        val candidates = grants.filter { grant ->
            grant.capability == capability &&
                grant.skillInstallId == skillId &&
                (workspaceId == null || grant.workspaceId == null || grant.workspaceId == workspaceId) &&
                (path == null || pathAllowed(grant.pathScope, path))
        }
        val capabilityAllowed = if (skillId == null) capability in capabilities else {
            capability in perSkillCapabilities[skillId].orEmpty()
        }
        // A pre-resolved Runtime snapshot may intentionally carry only its
        // effective set; when canonical rows are present they remain mandatory.
        return capabilityAllowed && (grants.isEmpty() || candidates.isNotEmpty())
    }

    fun allows(capability: String, skillId: String? = null, workspaceId: String? = null, path: String? = null, write: Boolean = false): Boolean =
        runCatching { allows(CapabilityId(capability), skillId, workspaceId, path, write) }.getOrDefault(false)

    fun forSkill(skillId: String): Set<CapabilityId> = perSkillCapabilities[skillId].orEmpty()

    companion object {
        private fun pathAllowed(scope: String?, path: String): Boolean {
            if (scope == null) return true
            return path == scope || path.startsWith("$scope/")
        }
    }
}

/**
 * Adapter from SQLite/domain grant rows to the shared skills-api resolver
 * semantics.  It never treats AgentSnapshot.skillIds as a grant: the
 * grant's canonical agent/skill/workspace fields and immutable snapshot binding
 * are the source of truth.
 */
class EffectiveCapabilityResolver(
    private val policyCapabilities: Set<CapabilityId> = ToolCapabilities.ALL,
    private val policyVersion: Long = 0L,
    private val configSnapshotHash: String = "unbound-config",
    private val grants: CapabilityGrantReader = EMPTY_CAPABILITY_GRANT_READER,
    private val bindings: SnapshotGrantBindingReader = EMPTY_SNAPSHOT_BINDING_READER,
    /** Optional durable policy source used by live pre-dispatch checks. */
    private val currentPolicyVersionReader: (() -> Long)? = null,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(policyVersion >= 0)
        require(configSnapshotHash.isNotBlank())
    }

    /** Resolve an immutable snapshot against an explicit canonical DB view. */
    fun resolve(
        snapshot: AgentSnapshot,
        grants: Iterable<CapabilityGrant>,
        snapshotBindings: Iterable<SnapshotGrantBinding> = emptyList(),
        currentPolicyVersion: Long = policyVersion,
        now: Long = nowEpochMs(),
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): EffectiveCapabilitySnapshot = resolveInternal(
        snapshot = snapshot,
        grants = grants,
        snapshotBindings = snapshotBindings,
        currentPolicyVersion = currentPolicyVersion,
        now = now,
        taskIdentity = taskIdentity,
        sessionIdentity = sessionIdentity,
        allowUnboundLiveGrants = false,
    )

    /**
     * Resolve the grant view which will be frozen for a new run.
     *
     * Persistent/session/task grants are allowed to be unbound here because
     * the run is the first place where the current Agent/Session grant view is
     * materialized.  ONCE grants still require an immutable snapshot binding;
     * callers must use the normal snapshot flow when admitting one-shot
     * capabilities.  Existing [resolve] remains strict for historical
     * snapshots and audit/replay reads.
     */
    fun resolveForRun(
        snapshot: AgentSnapshot,
        grants: Iterable<CapabilityGrant>,
        snapshotBindings: Iterable<SnapshotGrantBinding> = emptyList(),
        currentPolicyVersion: Long = policyVersion,
        now: Long = nowEpochMs(),
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): EffectiveCapabilitySnapshot = resolveInternal(
        snapshot = snapshot,
        grants = grants,
        snapshotBindings = snapshotBindings,
        currentPolicyVersion = currentPolicyVersion,
        now = now,
        taskIdentity = taskIdentity,
        sessionIdentity = sessionIdentity,
        allowUnboundLiveGrants = true,
    )

    private fun resolveInternal(
        snapshot: AgentSnapshot,
        grants: Iterable<CapabilityGrant>,
        snapshotBindings: Iterable<SnapshotGrantBinding>,
        currentPolicyVersion: Long,
        now: Long,
        taskIdentity: String?,
        sessionIdentity: String?,
        allowUnboundLiveGrants: Boolean,
    ): EffectiveCapabilitySnapshot {
        val allBindings = snapshotBindings.filter { it.snapshotId == snapshot.id }.toList()
        val bindingByGrantAndCapability = allBindings.associateBy { it.grantId to it.capability }
        val accepted = grants.asSequence()
            .filter { it.agentId == snapshot.agentId }
            .filter { it.revision > 0 && it.isActiveFor(Instant.ofEpochMilli(now), taskIdentity, sessionIdentity) }
            .filter { it.policyVersion == currentPolicyVersion }
            .filter { grant ->
                val binding = bindingByGrantAndCapability[grant.grantId to grant.capability]
                val bindingMatches = binding != null &&
                    binding.policyVersion == currentPolicyVersion &&
                    binding.workspaceId == grant.workspaceId &&
                    binding.pathScope == grant.pathScope
                val unboundLiveGrant = allowUnboundLiveGrants &&
                    grant.lifetime != GrantLifetime.ONCE &&
                    grant.isActiveFor(Instant.ofEpochMilli(now), taskIdentity, sessionIdentity)
                bindingMatches || unboundLiveGrant
            }
            .toList()
            .distinctBy { it.grantId to it.capability }

        val agent = accepted.filter { it.skillInstallId == null }.map { it.capability }.toSet() intersect policyCapabilities
        val perSkill = accepted.filter { it.skillInstallId != null }
            .groupBy { it.skillInstallId!! }
            .mapValues { (_, values) -> values.map { it.capability }.toSet() intersect agent intersect policyCapabilities }
        return EffectiveCapabilitySnapshot(
            agentId = snapshot.agentId,
            snapshotId = snapshot.id,
            capabilities = agent,
            perSkillCapabilities = perSkill,
            grants = accepted,
            bindings = accepted.mapNotNull { bindingByGrantAndCapability[it.grantId to it.capability] },
            policyVersion = currentPolicyVersion,
            configSnapshotHash = configSnapshotHash,
            taskIdentity = taskIdentity,
            sessionIdentity = sessionIdentity,
        )
    }

    fun resolve(
        snapshot: AgentSnapshot,
        currentPolicyVersion: Long = policyVersion,
        now: Long = nowEpochMs(),
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): EffectiveCapabilitySnapshot = resolve(
        snapshot = snapshot,
        grants = grants.read(snapshot.agentId, null).toList(),
        snapshotBindings = bindings.read(snapshot.id).toList(),
        currentPolicyVersion = currentPolicyVersion,
        now = now,
        taskIdentity = taskIdentity,
        sessionIdentity = sessionIdentity,
    )

    /** Repository adapter form used while freezing a new run. */
    fun resolveForRun(
        snapshot: AgentSnapshot,
        currentPolicyVersion: Long = policyVersion,
        now: Long = nowEpochMs(),
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): EffectiveCapabilitySnapshot = resolveForRun(
        snapshot = snapshot,
        grants = grants.read(snapshot.agentId, null).toList(),
        snapshotBindings = bindings.read(snapshot.id).toList(),
        currentPolicyVersion = currentPolicyVersion,
        now = now,
        taskIdentity = taskIdentity,
        sessionIdentity = sessionIdentity,
    )

    fun resolve(
        binding: SnapshotBinding,
        grants: Iterable<CapabilityGrant>,
        snapshotBindings: Iterable<SnapshotGrantBinding> = emptyList(),
        currentPolicyVersion: Long = policyVersion,
        now: Long = nowEpochMs(),
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): EffectiveCapabilitySnapshot = resolve(
        binding.snapshot,
        grants,
        snapshotBindings,
        currentPolicyVersion,
        now,
        taskIdentity,
        sessionIdentity,
    )

    /** Repository adapter form for a fresh pre-dispatch check. */
    fun resolve(
        snapshot: AgentSnapshot,
        grantsProvider: () -> Iterable<CapabilityGrant>,
        snapshotBindingsProvider: () -> Iterable<SnapshotGrantBinding>,
        currentPolicyVersion: Long = policyVersion,
        now: Long = nowEpochMs(),
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): EffectiveCapabilitySnapshot = resolve(
        snapshot,
        grantsProvider(),
        snapshotBindingsProvider(),
        currentPolicyVersion,
        now,
        taskIdentity,
        sessionIdentity,
    )

    /** Fresh grant/binding/policy check for one operation. */
    fun revalidate(
        snapshot: AgentSnapshot,
        capability: CapabilityId,
        skillId: String? = null,
        workspaceId: String? = null,
        path: String? = null,
        write: Boolean = false,
        currentPolicyVersion: Long = policyVersion,
        now: Long = nowEpochMs(),
    ): Boolean {
        val result = resolve(snapshot, currentPolicyVersion, now)
        return result.allows(capability, skillId, workspaceId, path, write)
    }

    /** Context form used by app executors when the Runtime owns the snapshot. */
    fun revalidate(
        context: ToolExecutionContext,
        capability: CapabilityId,
        workspaceId: String? = null,
        path: String? = null,
        write: Boolean = false,
    ): Boolean {
        return runCatching {
            resolveContext(context).allows(capability, context.skillId, workspaceId, path, write)
        }.getOrDefault(false)
    }

    /**
     * Return the live revision which authorizes this exact operation.  The
     * revision is part of the approval scope, so an approval created against
     * revision N cannot be consumed after the repository advances the same
     * grant to revision N+1.  This deliberately performs the same fresh read
     * and binding checks as [revalidate].
     */
    fun liveGrantRevision(
        context: ToolExecutionContext,
        capability: CapabilityId,
        workspaceId: String? = null,
        path: String? = null,
        write: Boolean = false,
    ): Long? {
        return runCatching {
            val snapshot = resolveContext(context)
            if (!snapshot.allows(capability, context.skillId, workspaceId, path, write)) return@runCatching null
            snapshot.grants.asSequence()
                .filter { grant ->
                    grant.capability == capability && grant.skillInstallId == context.skillId &&
                        (workspaceId == null || grant.workspaceId == null || grant.workspaceId == workspaceId) &&
                        (path == null || grant.pathScope == null || path == grant.pathScope || path.startsWith("${grant.pathScope}/"))
                }
                .map { it.revision }
                .maxOrNull()
        }.getOrNull()
    }

    private fun resolveContext(context: ToolExecutionContext): EffectiveCapabilitySnapshot {
        /*
         * A context normally contains the immutable-at-run-start rows, but
         * those rows are not a live authorization source.  When repository
         * readers are injected, always read them again here so a revocation,
         * expiry, grant revision, binding removal, or policy change between
         * approval and dispatch is observed.  The context rows remain useful
         * for a deterministic test/adapter seam when no reader is available.
         */
        val livePolicyVersion = currentPolicyVersionReader?.invoke() ?: context.policyVersion
        require(livePolicyVersion >= 0)
        if (livePolicyVersion != context.policyVersion) {
            return EffectiveCapabilitySnapshot(
                agentId = context.agentId,
                snapshotId = context.snapshotId,
                capabilities = emptySet(),
                perSkillCapabilities = emptyMap(),
                grants = emptyList(),
                bindings = emptyList(),
                policyVersion = livePolicyVersion,
                configSnapshotHash = context.configSnapshotHash,
                taskIdentity = context.taskIdentity.takeIf { it.isNotBlank() },
                sessionIdentity = context.sessionIdentity,
            )
        }
        val liveReadersConfigured = grants !== EMPTY_CAPABILITY_GRANT_READER ||
            bindings !== EMPTY_SNAPSHOT_BINDING_READER
        val liveGrants = if (liveReadersConfigured) grants.read(context.agentId, null).toList() else emptyList()
        val liveBindings = if (liveReadersConfigured) bindings.read(context.snapshotId).toList() else emptyList()
        val sourceGrants = when {
            liveReadersConfigured -> liveGrants
            context.canonicalGrants.isNotEmpty() -> context.canonicalGrants
            else -> emptyList()
        }
        val sourceBindings = when {
            liveReadersConfigured -> liveBindings
            context.snapshotGrantBindings.isNotEmpty() -> context.snapshotGrantBindings
            else -> emptyList()
        }
        val now = nowEpochMs()
        val frozenByGrantAndCapability = context.canonicalGrants.associateBy { it.grantId to it.capability }
        val accepted = sourceGrants.filter { grant ->
            if (grant.agentId != context.agentId ||
                grant.revision <= 0 ||
                !grant.isActiveFor(
                    now = Instant.ofEpochMilli(now),
                    taskIdentity = context.taskIdentity.takeIf { it.isNotBlank() },
                    sessionIdentity = context.sessionIdentity,
                ) ||
                grant.policyVersion != livePolicyVersion
            ) {
                false
            } else {
                val bindingMatches = sourceBindings.any { binding ->
                    binding.snapshotId == context.snapshotId &&
                        binding.grantId == grant.grantId &&
                        binding.capability == grant.capability &&
                        binding.policyVersion == livePolicyVersion &&
                        binding.workspaceId == grant.workspaceId &&
                        binding.pathScope == grant.pathScope
                }
                if (!liveReadersConfigured) {
                    // Without a live repository reader, retain the historical
                    // strict behavior: a context-only grant is not enough to
                    // prove authorization for dispatch.
                    bindingMatches
                } else {
                    // A run may only continue with the exact canonical row it
                    // froze at start.  This blocks both revocation and silent
                    // scope/revision edits, while also preventing a grant
                    // created after the run began from expanding it.
                    val frozen = frozenByGrantAndCapability[grant.grantId to grant.capability]
                    frozen != null && frozen == grant && (
                        bindingMatches || (
                            grant.lifetime != GrantLifetime.ONCE &&
                                grant.isActiveFor(
                                    now = Instant.ofEpochMilli(now),
                                    taskIdentity = context.taskIdentity.takeIf { it.isNotBlank() },
                                    sessionIdentity = context.sessionIdentity,
                                )
                            )
                        )
                }
            }
        }
        val agent = accepted.filter { it.skillInstallId == null }.map { it.capability }.toSet() intersect policyCapabilities
        val perSkill = accepted.filter { it.skillInstallId != null }.groupBy { it.skillInstallId!! }
            .mapValues { (_, values) -> values.map { it.capability }.toSet() intersect agent intersect policyCapabilities }
        val snapshot = EffectiveCapabilitySnapshot(
            agentId = context.agentId,
            snapshotId = context.snapshotId,
            // An effective set without canonical grant rows is not an
            // authorization proof.  It is intentionally ignored here; the
            // Runtime must inject domain grants and snapshot bindings (or
            // live readers) before any dispatch is possible.
            capabilities = agent,
            perSkillCapabilities = perSkill,
            grants = accepted,
            bindings = accepted.mapNotNull { grant ->
                sourceBindings.firstOrNull { binding ->
                    binding.snapshotId == context.snapshotId &&
                        binding.grantId == grant.grantId &&
                        binding.capability == grant.capability &&
                        binding.policyVersion == livePolicyVersion &&
                        binding.workspaceId == grant.workspaceId &&
                        binding.pathScope == grant.pathScope
                }
            },
            policyVersion = livePolicyVersion,
            configSnapshotHash = context.configSnapshotHash,
            taskIdentity = context.taskIdentity.takeIf { it.isNotBlank() },
            sessionIdentity = context.sessionIdentity,
        )
        return snapshot
    }

    /**
     * Atomically consume a canonical ONCE grant through the caller's CAS seam.
     * Resolution alone never consumes a grant; callers must pass the persisted
     * repository operation so retries and process restarts cannot replay it.
     */
    fun consumeOnce(
        context: ToolExecutionContext,
        capability: CapabilityId,
        consumer: (CapabilityGrant) -> Boolean,
        workspaceId: String? = null,
        path: String? = null,
        write: Boolean = false,
    ): Boolean = authorizeForDispatch(
        context = context,
        capability = capability,
        consumer = consumer,
        workspaceId = workspaceId,
        path = path,
        write = write,
    ) == DispatchAuthorization.ALLOWED_AFTER_ONCE_CONSUMPTION

    /**
     * Fresh, canonical authorization gate for the moment immediately before
     * backend dispatch.  A valid non-ONCE grant is sufficient and is never
     * passed to [consumer].  If the only matching grants are ONCE, the highest
     * revision (grant id breaks ties) is passed to [consumer] for durable CAS;
     * a failed CAS denies dispatch.  The effective-capability set alone can
     * never authorize this method when canonical grant rows are absent.
     */
    fun authorizeForDispatch(
        context: ToolExecutionContext,
        capability: CapabilityId,
        consumer: (CapabilityGrant) -> Boolean,
        workspaceId: String? = null,
        path: String? = null,
        write: Boolean = false,
    ): DispatchAuthorization {
        return runCatching {
            val resolved = resolveContext(context)
            val matching = matchingDispatchGrants(
                resolved = resolved,
                context = context,
                capability = capability,
                workspaceId = workspaceId,
                path = path,
                write = write,
            )
            if (matching.isEmpty()) return@runCatching DispatchAuthorization.DENIED

            // A durable grant with a longer lifetime already authorizes this
            // operation.  Never consume an ONCE row merely because it is also
            // present: doing so would make a one-shot grant unexpectedly burn
            // while a persistent/task/session grant remains available.
            if (matching.any { it.lifetime != GrantLifetime.ONCE }) {
                return@runCatching DispatchAuthorization.ALLOWED_EXISTING_GRANT
            }

            val once = matching
                .filter { it.lifetime == GrantLifetime.ONCE }
                .sortedWith(compareByDescending<CapabilityGrant> { it.revision }.thenBy { it.grantId })
                .firstOrNull()
                ?: return@runCatching DispatchAuthorization.DENIED
            if (consumer(once)) DispatchAuthorization.ALLOWED_AFTER_ONCE_CONSUMPTION
            else DispatchAuthorization.DENIED
        }.getOrDefault(DispatchAuthorization.DENIED)
    }

    /**
     * Match the exact operation against the already fresh-resolved canonical
     * rows.  Null scope means an intentionally unscoped grant; a scoped grant
     * cannot authorize an operation that omits that scope.  This prevents a
     * workspace/path-specific row from widening into a workspace-less or
     * path-less dispatch.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun matchingDispatchGrants(
        resolved: EffectiveCapabilitySnapshot,
        context: ToolExecutionContext,
        capability: CapabilityId,
        workspaceId: String?,
        path: String?,
        write: Boolean,
    ): List<CapabilityGrant> {
        // Keep the write argument in the canonical signature so callers bind
        // the same operation shape as revalidate; read/write capability names
        // remain the domain's authority distinction.
        if (resolved.grants.isEmpty()) return emptyList()
        val effective = if (context.skillId == null) {
            resolved.capabilities
        } else {
            resolved.perSkillCapabilities[context.skillId].orEmpty()
        }
        if (capability !in effective) return emptyList()
        return resolved.grants.filter { grant ->
            grant.capability == capability &&
                grant.skillInstallId == context.skillId &&
                workspaceMatches(grant.workspaceId, workspaceId) &&
                pathMatches(grant.pathScope, path)
        }
    }

    private fun workspaceMatches(grantWorkspaceId: String?, requestedWorkspaceId: String?): Boolean =
        grantWorkspaceId == null || requestedWorkspaceId != null && grantWorkspaceId == requestedWorkspaceId

    private fun pathMatches(grantPathScope: String?, requestedPath: String?): Boolean = when {
        grantPathScope == null -> true
        requestedPath == null -> false
        else -> requestedPath == grantPathScope || requestedPath.startsWith("$grantPathScope/")
    }
}
