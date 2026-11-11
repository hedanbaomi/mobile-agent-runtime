// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.memory.SkillMemoryDiagnosticRefProvider
import runtime.mobileagent.memory.SkillMemoryDiagnosticSink
import runtime.mobileagent.memory.SkillMemoryRepositoryPort
import runtime.mobileagent.memory.SkillMemoryToolExecutor
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.tooling.AuditDegradedFuse
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.tooling.ApprovalEngine
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.DangerousModeManager
import runtime.mobileagent.tooling.EffectiveCapabilityResolver
import runtime.mobileagent.tooling.ShellToolExecutor
import runtime.mobileagent.tooling.ToolExecutionContext
import runtime.mobileagent.tooling.ToolExecutorFactory
import runtime.mobileagent.tooling.UnifiedWorkspaceToolExecutor
import runtime.mobileagent.tooling.WorkspaceAuditFuse
import runtime.mobileagent.tooling.WorkspaceRegistry

/** Per-run tool composition shares the host's canonical policy, grants, approval and audit owners. */
internal class RuntimeRunToolAssembly(
    private val db: runtime.mobileagent.data.SqlConnection,
    private val workspaceRegistry: WorkspaceRegistry,
    private val approvalEngine: ApprovalEngine,
    private val effectiveCapabilityResolver: EffectiveCapabilityResolver,
    private val capabilityGrantRepository: CapabilityGrantRepository,
    private val authorityPolicyRepository: AuthorityPolicyRepository,
    private val authorityManager: AuthorityManager,
    private val dangerousModeManager: DangerousModeManager,
    private val shellBackends: Map<ElevatedAuthority, ShellExecutor>,
    private val auditSink: SqliteRuntimeAuditSink,
    private val auditFuse: AuditDegradedFuse,
    private val workspaceAuditFuse: WorkspaceAuditFuse,
    private val skillMemoryRepositoryPort: SkillMemoryRepositoryPort,
    private val skillMemoryDiagnosticSink: SkillMemoryDiagnosticSink,
    private val skillMemoryDiagnosticRefProvider: SkillMemoryDiagnosticRefProvider,
    private val rootCoordinator: () -> runtime.mobileagent.privileged.RootRuntimeCoordinator,
    private val freezeContext: (ToolExecutionContext) -> ToolExecutionContext,
) {
    /**
     * Build the tool set for exactly one frozen run context. Null web/MCP/
     * Python inputs mean that provider is absent for this run; this method
     * never creates or substitutes one.
     */
    fun createToolExecutorFactory(
        context: ToolExecutionContext,
        web: ToolExecutor? = null,
        mcp: ToolExecutor? = null,
        python: ToolExecutor? = null,
    ): ToolExecutorFactory {
        val frozen = freezeContext(context)
        // Attached Skill identity is only for Skill-owned tools (such as memory).
        // Built-in Agent workspace tools must resolve the Agent's own grants again;
        // changing only effectiveCapabilities would still select Skill grants at dispatch.
        val agentContext = freezeContext(context.copy(skillId = null, skillRevision = null, trustedSkillEnvelope = false))
        val workspace = UnifiedWorkspaceToolExecutor(
            registry = workspaceRegistry,
            approvalEngine = approvalEngine,
            resolver = effectiveCapabilityResolver,
            contextProvider = { agentContext },
            auditSink = auditSink,
            auditFuse = workspaceAuditFuse,
            dangerousModeProvider = { dangerousModeManager.policy() },
            authoritySelectionProvider = { authorityManager.selection.value },
            onceGrantConsumer = { grant ->
                capabilityGrantRepository.tryConsumeOnce(
                    grantId = grant.grantId,
                    expectedRevision = grant.revision,
                    taskIdentity = frozen.taskIdentity.takeIf { it.isNotBlank() },
                    sessionIdentity = frozen.sessionIdentity,
                )
            },
        )
        val shell = ShellToolExecutor(
            authorityManager = authorityManager,
            dangerousModeManager = dangerousModeManager,
            approvalEngine = approvalEngine,
            // This is the built-in Agent tool. An attached Skill is not the
            // owner of its global Dangerous Mode consent or its audit identity.
            contextProvider = { agentContext },
            backends = shellBackends.toMap(),
            resolver = effectiveCapabilityResolver,
            grantChanges = capabilityGrantRepository.changes,
            auditSink = auditSink,
            auditFuse = auditFuse,
            agentShellAuthorization = {
                dangerousModeManager.isEnabled() && authorityPolicyRepository.getPolicy().policyVersion == frozen.policyVersion
            },
            onceGrantConsumer = { grant ->
                capabilityGrantRepository.tryConsumeOnce(
                    grantId = grant.grantId,
                    expectedRevision = grant.revision,
                    taskIdentity = frozen.taskIdentity.takeIf { it.isNotBlank() },
                    sessionIdentity = frozen.sessionIdentity,
                )
            },
        )
        val memory = createSkillMemoryToolExecutor(frozen)
        val root = runtime.mobileagent.privileged.RootToolExecutorAdmission.create(db, rootCoordinator(), agentContext)
        val rootSelected = authorityPolicyRepository.getPolicy().selectedAuthority.isRootAuthority
        return ToolExecutorFactory(
            web = web,
            mcp = mcp,
            python = python,
            memory = memory,
            workspace = workspace,
            shell = if (rootSelected) null else shell,
            root = root,
            boundSnapshotId = agentContext.snapshotId,
            rootWorkspaceIds = frozenRootWorkspaceIds(agentContext),
        )
    }

    /** Frozen binding IDs reserve their Root route even while the corresponding service is offline. */
    fun frozenRootWorkspaceIds(context: ToolExecutionContext): Set<String> =
        (context.snapshotGrantBindings.mapNotNull { it.workspaceId } + context.canonicalGrants.mapNotNull { it.workspaceId })
            .filter { runtime.mobileagent.data.RootWorkspaceRepository(db).getOrigin(it) != null }.toSet()

    /** Create the memory executor for this run; bindings are rechecked at each approval. */
    fun createSkillMemoryToolExecutor(context: ToolExecutionContext): SkillMemoryToolExecutor {
        val frozen = freezeContext(context)
        return SkillMemoryToolExecutor(
            repository = skillMemoryRepositoryPort,
            agentId = frozen.agentId,
            snapshotId = frozen.snapshotId,
            trustedSkillId = frozen.skillId,
            // Every frozen Skill identity gets its own runtime-assigned tool
            // namespace; single-Skill runs keep the legacy shared names.
            trustedSkillIds = frozen.trustedSkillIds,
            effectiveCapabilities = frozen.effectiveCapabilityNames,
            diagnosticSink = skillMemoryDiagnosticSink,
            diagnosticRefProvider = skillMemoryDiagnosticRefProvider,
        )
    }

}
