// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.SkillMemoryRepository
import runtime.mobileagent.data.SkillRepository
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.memory.SkillMemoryBinding
import runtime.mobileagent.memory.SkillMemoryException
import runtime.mobileagent.memory.SkillMemoryFailureCode
import runtime.mobileagent.memory.SkillMemoryListResult
import runtime.mobileagent.memory.SkillMemoryReadResult
import runtime.mobileagent.memory.SkillMemorySearchHit
import runtime.mobileagent.memory.SkillMemorySearchResult
import runtime.mobileagent.memory.SkillMemoryWriteResult
import runtime.mobileagent.memory.SkillMemoryRepositoryPort
import runtime.mobileagent.tooling.EffectiveCapabilityResolver

/**
 * Canonical DB/sidecar memory adapter. Binding, availability, and every
 * memory operation use the same repository so the agent executor and the
 * read-only UI cannot drift to a second filesystem truth.
 */
internal class SqliteSkillMemoryRepositoryPort(
    private val agents: AgentRepository,
    private val skills: SkillRepository,
    private val capabilityGrants: CapabilityGrantRepository,
    private val effectiveCapabilityResolver: EffectiveCapabilityResolver,
    private val currentPolicyVersion: () -> Long,
    private val memoryRepository: SkillMemoryRepository,
) : SkillMemoryRepositoryPort {
    override fun bindings(agentId: String, snapshotId: String): List<SkillMemoryBinding> {
        val snapshot = agents.getSnapshot(snapshotId) ?: return emptyList()
        if (snapshot.agentId != agentId) return emptyList()
        val policyVersion = currentPolicyVersion()
        // Resolve from the canonical Agent grants and immutable snapshot bindings.  The old
        // package-permission table is only a second trust boundary below; it is never treated as
        // an Agent grant or as proof that a snapshot is authorized.
        val resolved = effectiveCapabilityResolver.resolve(
            snapshot = snapshot,
            grants = capabilityGrants.forAgent(agentId, includeRevoked = true),
            snapshotBindings = capabilityGrants.listSnapshotBindings(snapshotId),
            currentPolicyVersion = policyVersion,
        )
        return snapshot.skillIds.mapNotNull { installId ->
            val skill = skills.get(installId) ?: return@mapNotNull null
            // A package grant establishes that the installed bytes were trusted.  Its
            // capabilities are intersected with the canonical Agent/snapshot/policy result;
            // package approval alone can never expose memory to an Agent.
            val packageGrant = skills.grantsFor(installId)
                .asSequence()
                .filter { it.packageHash == skill.packageHash && !it.revoked }
                .maxByOrNull { it.revision }
            if (!skill.enabled || packageGrant == null) return@mapNotNull null
            val packageCapabilities = packageGrant.capabilities
            val effectiveCapabilities = resolved.forSkill(installId).mapTo(linkedSetOf()) { it.value }
            val memoryCapabilities = (effectiveCapabilities intersect packageCapabilities).filterTo(linkedSetOf()) {
                it == runtime.mobileagent.memory.SKILL_MEMORY_READ_CAPABILITY ||
                    it == runtime.mobileagent.memory.SKILL_MEMORY_SEARCH_CAPABILITY ||
                    it == runtime.mobileagent.memory.SKILL_MEMORY_APPEND_CAPABILITY ||
                    it == runtime.mobileagent.memory.SKILL_MEMORY_REPLACE_CAPABILITY
            }
            if (memoryCapabilities.isEmpty()) return@mapNotNull null
            // Keep a stable canonical grant identity for the binding.  Capabilities may be
            // represented by several rows, so the binding carries one anchor revision while the
            // complete capability intersection is retained. Any row change changes the resolved
            // capability set and is caught by the executor's current-binding revalidation.
            val grant = resolved.grants
                .asSequence()
                .filter { it.agentId == agentId && it.skillInstallId == installId }
                .filter { it.packageHash == skill.packageHash && it.capability.value in memoryCapabilities }
                .minWithOrNull(compareBy<CapabilityGrant> { it.grantId }.thenBy { it.revision })
                ?: return@mapNotNull null
            val grantRevision = grant.revision
                .takeIf { it in 1..Int.MAX_VALUE.toLong() }
                ?.toInt()
                ?: return@mapNotNull null
            // A repository/store failure must remain distinguishable from a
            // missing grant. The canonical availability default maps thrown
            // failures to UNAVAILABLE; swallowing them here would misreport
            // a persistence outage as GRANT_LOST.
            val space = memoryRepository.ensureSpace(skill.installId, skill.packageHash)
            SkillMemoryBinding(
                installId = skill.installId,
                packageHash = skill.packageHash,
                memorySpaceId = space.spaceId,
                agentId = agentId,
                snapshotId = snapshotId,
                capabilities = memoryCapabilities,
                enabled = true,
                grantId = grant.grantId,
                grantRevision = grantRevision,
                memoryMetadataRevision = space.version,
            )
        }
    }

    override fun current(agentId: String, snapshotId: String, original: SkillMemoryBinding): SkillMemoryBinding? =
        bindings(agentId, snapshotId).singleOrNull { current ->
            current.installId == original.installId &&
                current.packageHash == original.packageHash &&
                current.memorySpaceId == original.memorySpaceId &&
                current.agentId == original.agentId &&
                current.snapshotId == original.snapshotId &&
                current.enabled == original.enabled &&
                current.grantId == original.grantId &&
                current.grantRevision == original.grantRevision &&
                current.memoryMetadataRevision == original.memoryMetadataRevision
        }

    override fun list(binding: SkillMemoryBinding): SkillMemoryListResult =
        memoryRepository.listEntries(binding.memorySpaceId).map { entry ->
            requireSameBinding(entry, binding)
            runtime.mobileagent.memory.SkillMemoryEntry(
                path = entry.path,
                bytes = entry.byteLength,
                version = entry.version.toString(),
            )
        }.let(::SkillMemoryListResult)

    override fun read(
        binding: SkillMemoryBinding,
        path: String,
        maxBytes: Int,
    ): SkillMemoryReadResult {
        val entry = memoryRepository.get(binding.memorySpaceId, path)
            ?: throw SkillMemoryException(SkillMemoryFailureCode.NOT_FOUND)
        requireSameBinding(entry, binding)
        if (entry.byteLength > maxBytes.toLong()) {
            throw SkillMemoryException(SkillMemoryFailureCode.FILE_TOO_LARGE)
        }
        return SkillMemoryReadResult(
            path = entry.path,
            text = entry.content,
            bytes = entry.byteLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            version = entry.version.toString(),
        )
    }

    override fun search(
        binding: SkillMemoryBinding,
        query: String,
        maxResults: Int,
    ): SkillMemorySearchResult {
        if (maxResults !in 1..100) throw SkillMemoryException(SkillMemoryFailureCode.INVALID_QUERY)
        val queryLimit = if (maxResults < 100) maxResults + 1 else maxResults
        val entries = memoryRepository.search(binding.memorySpaceId, query, queryLimit)
        val truncated = entries.size > maxResults
        return SkillMemorySearchResult(
            hits = entries.take(maxResults).map { entry ->
                requireSameBinding(entry, binding)
                val lineStart = entry.content.lastIndexOf('\n', startIndex = entry.content.indexOf(query).coerceAtLeast(0))
                val line = entry.content.substring(0, lineStart.coerceAtLeast(0)).count { it == '\n' } + 1
                val snippet = entry.content.lineSequence().firstOrNull { query in it }?.take(512)
                    ?: entry.path
                SkillMemorySearchHit(entry.path, line, snippet)
            },
            truncated = truncated,
        )
    }

    override fun append(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
    ): SkillMemoryWriteResult = write(binding, path, text, expectedVersion, append = true)

    override fun replace(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
    ): SkillMemoryWriteResult = write(binding, path, text, expectedVersion, append = false)

    private fun write(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
        append: Boolean,
    ): SkillMemoryWriteResult {
        val previous = memoryRepository.get(binding.memorySpaceId, path)
        val expected = expectedVersion?.let {
            it.toLongOrNull() ?: throw SkillMemoryException(SkillMemoryFailureCode.CONFLICT)
        }
        val entry = if (append) {
            memoryRepository.append(binding.installId, binding.packageHash, path, text, expected)
        } else {
            memoryRepository.replace(binding.installId, binding.packageHash, path, text, expected)
        }
        requireSameBinding(entry, binding)
        return SkillMemoryWriteResult(
            path = entry.path,
            bytes = entry.byteLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            version = entry.version.toString(),
            created = previous == null,
        )
    }

    private fun requireSameBinding(
        entry: runtime.mobileagent.domain.SkillMemoryEntry,
        binding: SkillMemoryBinding,
    ) {
        if (entry.spaceId != binding.memorySpaceId ||
            entry.installId != binding.installId ||
            entry.packageHash != binding.packageHash
        ) {
            throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        }
    }
}
