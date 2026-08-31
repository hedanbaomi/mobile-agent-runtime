/*
 * SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package runtime.mobileagent.skills.tooling

import java.time.Instant
import runtime.mobileagent.domain.CapabilityGrant as DomainCapabilityGrant
import runtime.mobileagent.domain.CapabilityId as DomainCapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.matchesIdentity

/** Domain-owned capability value re-exported for adapter source compatibility. */
typealias CapabilityId = DomainCapabilityId
typealias CapabilityGrant = DomainCapabilityGrant

/**
 * Stable capability names used by tooling contracts.  The value object and
 * persisted grant are owned by shared:domain; this object only exposes
 * well-known operation values and does not create a second domain model.
 */
object ToolCapabilities {
    val WORKSPACE_ENUMERATE = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE)
    val FILE_LIST = CapabilityId(CapabilityId.FILE_LIST)
    val FILE_STAT = CapabilityId(CapabilityId.FILE_STAT)
    val FILE_READ_TEXT = CapabilityId(CapabilityId.FILE_READ_TEXT)
    val FILE_WRITE_TEXT = CapabilityId(CapabilityId.FILE_WRITE_TEXT)
    val FILE_CREATE_DIRECTORY = CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY)
    val FILE_MOVE = CapabilityId(CapabilityId.FILE_MOVE)
    val FILE_DELETE = CapabilityId(CapabilityId.FILE_DELETE)
    val MEMORY_READ = CapabilityId(CapabilityId.MEMORY_READ)
    val MEMORY_SEARCH = CapabilityId(CapabilityId.MEMORY_SEARCH)
    val MEMORY_APPEND = CapabilityId(CapabilityId.MEMORY_APPEND)
    val MEMORY_REPLACE = CapabilityId(CapabilityId.MEMORY_REPLACE)
    val SHELL_EXECUTE = CapabilityId(CapabilityId.SHELL_EXECUTE)
    val SHELL_INSPECT_ENVIRONMENT = CapabilityId("shell.inspect_environment")

    val ALL: Set<CapabilityId> = setOf(
        WORKSPACE_ENUMERATE,
        FILE_LIST,
        FILE_STAT,
        FILE_READ_TEXT,
        FILE_WRITE_TEXT,
        FILE_CREATE_DIRECTORY,
        FILE_MOVE,
        FILE_DELETE,
        MEMORY_READ,
        MEMORY_SEARCH,
        MEMORY_APPEND,
        MEMORY_REPLACE,
        SHELL_EXECUTE,
        SHELL_INSPECT_ENVIRONMENT,
    )
}

/** Public manifest naming rules layered on top of the domain value object. */
object CapabilityNaming {
    private val segment = Regex("[a-z][a-z0-9_-]*")

    fun isValid(raw: String): Boolean {
        if (!CapabilityId.isValid(raw)) return false
        val segments = raw.split('.')
        return segments.size >= 2 && segments.all(segment::matches)
    }

    fun validate(raw: String): String? = when {
        raw.isBlank() -> "capability is blank"
        raw != raw.trim() -> "capability contains surrounding whitespace"
        !CapabilityId.isValid(raw) -> "capability contains unsupported characters"
        raw.split('.').size < 2 -> "capability must contain a namespace and operation"
        !raw.split('.').all(segment::matches) -> "capability segments are invalid"
        else -> null
    }

    fun requireValid(raw: String): CapabilityId {
        require(isValid(raw)) { "Invalid capability name" }
        return CapabilityId(raw)
    }

    fun requireValid(id: CapabilityId): CapabilityId {
        require(isValid(id.value)) { "Invalid capability name" }
        return id
    }
}

/** Relative path prefixes attached to an agent or skill grant. */
data class CapabilityPathScope(
    val prefixes: Set<String>,
    val maxDepth: Int = WorkspacePath.MAX_DEPTH,
) {
    init {
        require(prefixes.isNotEmpty()) { "path scope must not be empty" }
        require(maxDepth in 1..WorkspacePath.MAX_DEPTH)
        require(prefixes.all { runCatching { WorkspacePath.normalize(it, maxDepth) }.isSuccess }) {
            "path scope contains an unsafe path"
        }
    }

    private val normalizedPrefixes: Set<String> = prefixes.map { WorkspacePath.normalize(it, maxDepth) }.toSet()

    fun contains(path: String): Boolean = runCatching { WorkspacePath.normalize(path, maxDepth) }
        .getOrNull()
        ?.let { normalized -> normalizedPrefixes.any { normalized == it || normalized.startsWith("$it/") } }
        ?: false

    fun normalized(): Set<String> = normalizedPrefixes.toSet()
}

/** A normalized workspace/path boundary used while resolving capabilities. */
data class WorkspaceCapabilityScope(
    val workspaceId: String,
    val allowedRoots: Set<String> = emptySet(),
    val writableRoots: Set<String> = emptySet(),
    val capabilities: Set<CapabilityId> = emptySet(),
    val enabled: Boolean = true,
) {
    init {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(allowedRoots.all(::isNormalizedPath)) { "allowedRoots must be normalized" }
        require(writableRoots.all(::isNormalizedPath)) { "writableRoots must be normalized" }
        require(writableRoots.all { root -> allowedRoots.any { it == root || root.startsWith("$it/") } }) {
            "writableRoots must be inside allowedRoots"
        }
        require(capabilities.all { CapabilityNaming.isValid(it.value) }) { "workspace capability is invalid" }
    }

    fun permits(path: String, write: Boolean = false): Boolean {
        if (!enabled) return false
        val normalized = normalizePath(path) ?: return false
        val roots = if (write) writableRoots else allowedRoots
        return roots.any { normalized == it || normalized.startsWith("$it/") }
    }

    companion object {
        fun normalizePath(path: String): String? {
            val candidate = path.trim().replace('\\', '/')
            if (candidate.isEmpty() || candidate.startsWith("/") || candidate.contains(':')) return null
            val parts = candidate.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return parts.joinToString("/")
        }

        private fun isNormalizedPath(path: String): Boolean = normalizePath(path) == path
    }
}

/** A budget is a gate in capability resolution; it is not a permission grant. */
data class CapabilityBudget(
    val maxCalls: Long? = null,
    val maxBytes: Long? = null,
    val consumedCalls: Long = 0,
    val consumedBytes: Long = 0,
) {
    init {
        require(maxCalls == null || maxCalls >= 0)
        require(maxBytes == null || maxBytes >= 0)
        require(consumedCalls >= 0 && consumedBytes >= 0)
    }

    val allows: Boolean get() = permits()

    val remainingCalls: Long get() = maxCalls?.minus(consumedCalls) ?: Long.MAX_VALUE
    val remainingBytes: Long get() = maxBytes?.minus(consumedBytes) ?: Long.MAX_VALUE
    val exhausted: Boolean get() = remainingCalls <= 0L || remainingBytes < 0L

    fun permits(calls: Long = 1, bytes: Long = 0): Boolean =
        calls >= 0 && bytes >= 0 &&
            (maxCalls == null || consumedCalls <= maxCalls - calls) &&
            (maxBytes == null || consumedBytes <= maxBytes - bytes)
}

/** Request-facing view of a grant; persisted grants remain domain-owned. */
data class CapabilityGrantSet(
    val capabilities: Set<CapabilityId>,
    val ownerId: String? = null,
    val workspaceId: String? = null,
    val pathScope: CapabilityPathScope? = null,
    val revision: Long = 1,
    val revoked: Boolean = false,
    val expiresAtEpochMs: Long? = null,
    /** Optional lifecycle metadata retained when a set represents one grant. */
    val lifetime: GrantLifetime? = null,
    val taskId: String? = null,
    val sessionId: String? = null,
    /** Durable one-shot marker; this is not the process-local approval TTL. */
    val consumedAtEpochMs: Long? = null,
) {
    init {
        require(revision > 0)
        require(capabilities.all { CapabilityNaming.isValid(it.value) }) { "Grant contains an invalid capability" }
    }

    fun active(
        nowEpochMs: Long,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): Boolean {
        if (revoked || consumedAtEpochMs != null) return false
        if (expiresAtEpochMs != null && expiresAtEpochMs <= nowEpochMs) return false
        if (lifetime == null && (taskId != null || sessionId != null)) return false
        return lifetime?.matchesIdentity(taskId, sessionId, taskIdentity, sessionIdentity) ?: true
    }

    companion object {
        fun fromDomain(
            grants: Iterable<CapabilityGrant>,
            ownerId: String? = null,
            skillInstallId: String? = null,
            workspaceId: String? = null,
            taskIdentity: String? = null,
            sessionIdentity: String? = null,
            nowEpochMs: Long? = null,
        ): CapabilityGrantSet {
            val selected = grants.filter {
                (ownerId == null || it.agentId == ownerId) &&
                    (if (skillInstallId == null) it.skillInstallId == null else it.skillInstallId == skillInstallId) &&
                    (workspaceId == null || it.workspaceId == null || it.workspaceId == workspaceId) &&
                    it.isUsableFor(taskIdentity, sessionIdentity) &&
                    (it.expiresAt.isNullOrBlank() || runCatching { Instant.parse(it.expiresAt!!) }.isSuccess) &&
                    (nowEpochMs == null || it.isActiveFor(
                        now = Instant.ofEpochMilli(nowEpochMs),
                        taskIdentity = taskIdentity,
                        sessionIdentity = sessionIdentity,
                    ))
            }
            val pathPrefixes = selected.mapNotNull { it.pathScope }.toSet()
            val expiry = selected.mapNotNull { grant ->
                grant.expiresAt?.takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                }
            }.minOrNull()
            return CapabilityGrantSet(
                capabilities = selected.map { it.capability }.toSet(),
                ownerId = ownerId,
                workspaceId = workspaceId ?: selected.mapNotNull { it.workspaceId }.distinct().singleOrNull(),
                pathScope = pathPrefixes.takeIf { it.isNotEmpty() }?.let(::CapabilityPathScope),
                // An empty projection is not an active grant.  Keeping this
                // bit explicit prevents a caller from treating a mismatched
                // task/session projection as an unrestricted empty grant.
                revoked = selected.isEmpty(),
                revision = selected.maxOfOrNull { it.revision } ?: 1,
                lifetime = selected.map { it.lifetime }.distinct().singleOrNull(),
                taskId = selected.mapNotNull { it.taskId }.distinct().singleOrNull(),
                sessionId = selected.mapNotNull { it.sessionId }.distinct().singleOrNull(),
                expiresAtEpochMs = expiry,
                consumedAtEpochMs = null,
            )
        }
    }
}

typealias AgentCapabilityGrant = CapabilityGrantSet
typealias SkillCapabilityGrant = CapabilityGrantSet

data class CapabilityResolutionRequest(
    val globalPolicy: Set<CapabilityId>,
    val agentGrant: CapabilityGrantSet,
    val skillGrant: CapabilityGrantSet? = null,
    val skillApplicable: Boolean = skillGrant != null,
    val workspaceScope: WorkspaceCapabilityScope? = null,
    val workspaceId: String? = null,
    val relativePath: String? = null,
    val writeOperation: Boolean = false,
    val budget: CapabilityBudget = CapabilityBudget(),
    val agentId: String? = null,
    val skillId: String? = null,
    val nowEpochMs: Long = Long.MAX_VALUE,
    val taskIdentity: String? = null,
    val sessionIdentity: String? = null,
) {
    init {
        require(globalPolicy.all { CapabilityNaming.isValid(it.value) }) { "Global capability is invalid" }
        require(workspaceScope == null || workspaceId == null || workspaceScope.workspaceId == workspaceId) {
            "Workspace scope belongs to another workspace"
        }
    }
}

data class EffectiveCapabilityResolution(
    val capabilities: Set<CapabilityId>,
    val budgetAllowed: Boolean,
    val workspaceAllowed: Boolean,
    val skillApplied: Boolean,
) {
    fun contains(capability: CapabilityId): Boolean = capability in capabilities

    val fullyPermitted: Boolean
        get() = budgetAllowed && workspaceAllowed
}

/** Pure global ∩ agent ∩ applicable-skill ∩ workspace/path ∩ budget resolution. */
class EffectiveCapabilityResolver {
    fun resolve(request: CapabilityResolutionRequest): Set<CapabilityId> = resolveDetailed(request).capabilities

    fun resolveDetailed(request: CapabilityResolutionRequest): EffectiveCapabilityResolution {
        if (!request.budget.allows || !request.budget.permits()) {
            return EffectiveCapabilityResolution(emptySet(), budgetAllowed = false, workspaceAllowed = false, skillApplied = false)
        }
        if (!matches(request.agentGrant, request.agentId) ||
            !request.agentGrant.active(request.nowEpochMs, request.taskIdentity, request.sessionIdentity)) {
            return EffectiveCapabilityResolution(emptySet(), budgetAllowed = true, workspaceAllowed = false, skillApplied = false)
        }

        var effective = request.globalPolicy intersect request.agentGrant.capabilities
        var skillApplied = false
        if (request.skillApplicable) {
            val skill = request.skillGrant
            if (skill == null || !matches(skill, request.skillId) ||
                !skill.active(request.nowEpochMs, request.taskIdentity, request.sessionIdentity)) {
                return EffectiveCapabilityResolution(emptySet(), budgetAllowed = true, workspaceAllowed = false, skillApplied = true)
            }
            effective = effective intersect skill.capabilities
            skillApplied = true
        }

        var workspaceAllowed = true
        val workspace = request.workspaceScope
        if (workspace != null) {
            workspaceAllowed = workspace.enabled &&
                (request.workspaceId == null || request.workspaceId == workspace.workspaceId) &&
                (request.agentGrant.workspaceId == null || request.agentGrant.workspaceId == workspace.workspaceId)
            if (workspaceAllowed && workspace.capabilities.isNotEmpty()) effective = effective intersect workspace.capabilities
            if (workspaceAllowed && request.relativePath != null) {
                val workspacePathAllowed = workspace.permits(request.relativePath, request.writeOperation)
                val agentPathAllowed = request.agentGrant.pathScope?.contains(request.relativePath) ?: true
                // Workspace and agent path scopes are independent mandatory
                // boundaries.  Unioning them would let either grant expand the
                // other grant's authority.
                workspaceAllowed = workspacePathAllowed && agentPathAllowed
            }
            if (!workspaceAllowed) effective = emptySet()
        } else if (request.relativePath != null && request.agentGrant.pathScope != null) {
            workspaceAllowed = request.agentGrant.pathScope.contains(request.relativePath)
            if (!workspaceAllowed) effective = emptySet()
        }

        return EffectiveCapabilityResolution(effective.toSet(), true, workspaceAllowed, skillApplied)
    }

    /** Convenience overload for adapters that already have plain sets. */
    fun resolve(
        globalPolicy: Set<CapabilityId>,
        agentGrant: Set<CapabilityId>,
        skillGrant: Set<CapabilityId>? = null,
        workspaceScope: Set<CapabilityId>? = null,
        budgetAllowed: Boolean = true,
    ): Set<CapabilityId> {
        if (!budgetAllowed) return emptySet()
        var effective = globalPolicy intersect agentGrant
        if (skillGrant != null) effective = effective intersect skillGrant
        if (workspaceScope != null) effective = effective intersect workspaceScope
        return effective.toSet()
    }

    private fun matches(grant: CapabilityGrantSet, expectedOwner: String?): Boolean =
        grant.ownerId == null || grant.ownerId == expectedOwner
}

/** Shared relative-path normalization for typed backends; performs no I/O. */
object WorkspacePath {
    const val MAX_DEPTH = 32

    fun normalize(path: String, maxDepth: Int = MAX_DEPTH): String {
        require(path.isNotEmpty()) { "path is empty" }
        require(!path.contains('\u0000')) { "path contains NUL" }
        val candidate = path.replace('\\', '/')
        require(candidate.isNotEmpty() && !candidate.startsWith('/')) { "path must be relative" }
        require(!Regex("^[A-Za-z]:($|/)").containsMatchIn(candidate)) { "path must be relative" }
        val segments = candidate.split('/')
        require(segments.size <= maxDepth) { "path is too deep" }
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) { "path contains an unsafe segment" }
        require(segments.none { it.contains(':') }) { "path contains a drive separator" }
        return segments.joinToString("/")
    }
}
