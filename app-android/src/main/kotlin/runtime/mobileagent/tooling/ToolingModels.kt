// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.AuthorityPolicy
import runtime.mobileagent.domain.AuthorityPreferences
import runtime.mobileagent.domain.AuthorityUserIntent
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.skills.tooling.ApprovalBinding as SharedApprovalBinding
import runtime.mobileagent.skills.tooling.AuditDegradedFuse as SharedAuditDegradedFuse
import runtime.mobileagent.skills.tooling.ApprovalLifecycleEvent as SharedApprovalLifecycleEvent
import runtime.mobileagent.skills.tooling.ApprovalLifecycleSink as SharedApprovalLifecycleSink
import runtime.mobileagent.skills.tooling.ApprovalLifecycleTransition as SharedApprovalLifecycleTransition
import runtime.mobileagent.skills.tooling.Availability as SharedAvailability
import runtime.mobileagent.skills.tooling.AuthoritySelection as SharedAuthoritySelection
import runtime.mobileagent.skills.tooling.AuthorityState as SharedAuthorityState
import runtime.mobileagent.skills.tooling.Connection as SharedConnection
import runtime.mobileagent.skills.tooling.ElevatedAuthority as SharedElevatedAuthority
import runtime.mobileagent.skills.tooling.PlatformGrant as SharedPlatformGrant
import runtime.mobileagent.skills.tooling.ShellAuditEvent as SharedShellAuditEvent
import runtime.mobileagent.skills.tooling.ShellAuditSink as SharedShellAuditSink
import runtime.mobileagent.skills.tooling.ShellExecRequest as SharedShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecResult as SharedShellExecResult
import runtime.mobileagent.skills.tooling.ShellExecutionStatus as SharedShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellLimits as SharedShellLimits
import runtime.mobileagent.skills.tooling.ToolError as SharedToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode as SharedToolErrorCode
import runtime.mobileagent.skills.tooling.ToolHandler as SharedToolHandler
import runtime.mobileagent.skills.tooling.ToolInvocation as SharedToolInvocation
import runtime.mobileagent.skills.tooling.ToolRegistration as SharedToolRegistration
import runtime.mobileagent.skills.tooling.ToolSpec as SharedToolSpec
import runtime.mobileagent.skills.tooling.ToolExecution as SharedToolExecution
import runtime.mobileagent.skills.tooling.WorkspaceBackend as SharedWorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType as SharedWorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor as SharedWorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceEntry as SharedWorkspaceEntry
import runtime.mobileagent.skills.tooling.WorkspaceEntryType as SharedWorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceFileStat as SharedWorkspaceFileStat
import runtime.mobileagent.skills.tooling.WorkspaceListing as SharedWorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceMutation as SharedWorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceText as SharedWorkspaceText

/**
 * This package is an Android adapter around the canonical shared contracts.
 * Persisted values come from shared:domain and model-facing execution values
 * come from shared:skills-api.  The aliases below intentionally do not define
 * another set of authority, grant, workspace, shell, or error enums.
 */
typealias DomainAuthority = Authority
typealias DomainAuthorityPolicy = AuthorityPolicy
typealias DomainAuthorityPreferences = AuthorityPreferences
typealias DomainAuthorityUserIntent = AuthorityUserIntent
typealias DomainCapabilityGrant = CapabilityGrant
typealias DomainCapabilityId = CapabilityId
typealias DomainDangerousMode = DangerousMode
typealias DomainGrantLifetime = GrantLifetime
typealias ApprovalLifetime = GrantLifetime
typealias DomainSnapshotGrantBinding = SnapshotGrantBinding
typealias DomainWorkspace = Workspace

typealias ToolAuthority = SharedElevatedAuthority
typealias ToolAuthoritySelection = SharedAuthoritySelection
typealias ToolAuthorityState = SharedAuthorityState
typealias ToolPlatformGrant = SharedPlatformGrant
typealias ToolAvailability = SharedAvailability
typealias ToolConnection = SharedConnection
typealias ToolDangerousMode = DangerousMode
typealias ToolApprovalBinding = SharedApprovalBinding
typealias ToolError = SharedToolError
typealias ToolErrorCode = SharedToolErrorCode
typealias ToolSpec = SharedToolSpec
typealias ToolInvocation = SharedToolInvocation
typealias ToolExecution = SharedToolExecution
typealias ToolHandler = SharedToolHandler
typealias ToolRegistration = SharedToolRegistration
typealias ToolWorkspaceBackend = SharedWorkspaceBackend
typealias ToolWorkspaceDescriptor = SharedWorkspaceDescriptor
typealias ToolWorkspaceBackendType = SharedWorkspaceBackendType
typealias ToolWorkspaceEntry = SharedWorkspaceEntry
typealias ToolWorkspaceEntryType = SharedWorkspaceEntryType
typealias ToolWorkspaceListing = SharedWorkspaceListing
typealias ToolWorkspaceFileStat = SharedWorkspaceFileStat
typealias ToolWorkspaceText = SharedWorkspaceText
typealias ToolWorkspaceMutation = SharedWorkspaceMutation
typealias ToolShellExecRequest = SharedShellExecRequest
typealias ToolShellExecResult = SharedShellExecResult
typealias ToolShellExecutionStatus = SharedShellExecutionStatus
typealias ToolShellLimits = SharedShellLimits
typealias ToolShellAuditEvent = SharedShellAuditEvent
typealias ToolShellAuditSink = SharedShellAuditSink
typealias ToolAuditDegradedFuse = SharedAuditDegradedFuse
typealias ToolApprovalLifecycleEvent = SharedApprovalLifecycleEvent
typealias ToolApprovalLifecycleSink = SharedApprovalLifecycleSink
typealias ToolApprovalLifecycleTransition = SharedApprovalLifecycleTransition

/** Runtime-only clock seam; it is not persisted domain state. */
fun interface ToolingClock {
    fun nowMillis(): Long
}

val SYSTEM_TOOLING_CLOCK: ToolingClock = ToolingClock { System.currentTimeMillis() }

/**
 * User-visible provider choice plus per-provider persistent intent/configuration.
 * The fields intentionally use domain-owned enums.  This is only an adapter
 * aggregate for stores that do not expose the SQLite repository directly.
 */
data class AuthorityPersistentState(
    val revision: Long = 0L,
    val selectedAuthority: DomainAuthority = DomainAuthority.NONE,
    val preferences: Map<DomainAuthority, DomainAuthorityPreferences> = emptyMap(),
) {
    init {
        require(revision >= 0L)
        require(preferences.keys.all { it in DomainAuthority.entries })
    }

    val userIntents: Map<DomainAuthority, DomainAuthorityUserIntent>
        get() = DomainAuthority.entries.associateWith { authority ->
            preferences[authority]?.let { if (it.userIntentEnabled) authority.userIntent() else DomainAuthorityUserIntent.NONE }
                ?: DomainAuthorityUserIntent.NONE
        }

    val configured: Map<DomainAuthority, Boolean>
        get() = DomainAuthority.entries.associateWith { preferences[it]?.explicitlyConfigured == true }
}

interface AuthorityStateStore {
    fun load(): AuthorityPersistentState
    fun compareAndSet(expectedRevision: Long, next: AuthorityPersistentState): Boolean
}

/** Small deterministic store useful for tests and for an injected app adapter. */
class InMemoryAuthorityStateStore(initial: AuthorityPersistentState = AuthorityPersistentState()) : AuthorityStateStore {
    private val lock = Any()
    private var value = initial

    override fun load(): AuthorityPersistentState = synchronized(lock) { value }

    override fun compareAndSet(expectedRevision: Long, next: AuthorityPersistentState): Boolean = synchronized(lock) {
        if (value.revision != expectedRevision) return@synchronized false
        value = next
        true
    }
}

/** Durable Dangerous Mode storage is expressed in the domain policy aggregate. */
data class DangerousModePersistentState(
    val revision: Long = 0L,
    val policy: DomainAuthorityPolicy = DomainAuthorityPolicy(),
) {
    init { require(revision >= 0L) }
}

interface DangerousModeStateStore {
    fun load(): DangerousModePersistentState
    fun compareAndSet(expectedRevision: Long, next: DangerousModePersistentState): Boolean
}

class InMemoryDangerousModeStateStore(initial: DangerousModePersistentState = DangerousModePersistentState()) : DangerousModeStateStore {
    private val lock = Any()
    private var value = initial

    override fun load(): DangerousModePersistentState = synchronized(lock) { value }

    override fun compareAndSet(expectedRevision: Long, next: DangerousModePersistentState): Boolean = synchronized(lock) {
        if (value.revision != expectedRevision) return@synchronized false
        value = next
        true
    }
}

/**
 * Build-time security gate.  The defaults are deliberately fail-closed: an
 * AppContainer must inject the generated BuildConfig flags for a non-debuggable
 * review/release variant.  `testOnlyOverride` is an explicit test seam and must
 * never be populated by production wiring.
 */
data class DangerousBuildPolicy(
    /** Unknown until the AppContainer injects the generated variant flag. */
    val debuggable: Boolean = false,
    val controlPlaneAllowed: Boolean = false,
    val variantKnown: Boolean = false,
    val testOnlyOverride: Boolean = false,
) {
    fun permitsDangerousMode(): Boolean = variantKnown && controlPlaneAllowed &&
        ((!debuggable) || testOnlyOverride)

    companion object {
        /** Explicit production construction from generated BuildConfig flags. */
        fun fromBuildFlags(
            isDebuggable: Boolean,
            controlPlaneAllowed: Boolean,
            variantKnown: Boolean = true,
        ): DangerousBuildPolicy = DangerousBuildPolicy(
            debuggable = isDebuggable,
            controlPlaneAllowed = controlPlaneAllowed,
            variantKnown = variantKnown,
            testOnlyOverride = false,
        )

        /** Test-only seam; production code must use [fromBuildFlags]. */
        fun testOnlyOverride(): DangerousBuildPolicy = DangerousBuildPolicy(
            debuggable = true,
            controlPlaneAllowed = true,
            variantKnown = true,
            testOnlyOverride = true,
        )
    }
}

data class DangerousModeState(
    val revision: Long,
    val policy: DomainDangerousMode,
) {
    val enabled: Boolean get() = policy != DomainDangerousMode.DISABLED
}

data class ToolExecutionContext(
    val agentId: String,
    val snapshotId: String,
    val modelCallId: String,
    val sessionIdentity: String,
    val taskIdentity: String = "",
    val configSnapshotHash: String,
    val policyVersion: Long,
    val effectiveCapabilities: Set<DomainCapabilityId> = emptySet(),
    val canonicalGrants: List<DomainCapabilityGrant> = emptyList(),
    val snapshotGrantBindings: List<DomainSnapshotGrantBinding> = emptyList(),
    val skillId: String? = null,
    val skillRevision: Long? = null,
    /** Only Runtime-created skill envelopes may carry a skill grant. */
    val trustedSkillEnvelope: Boolean = false,
    /**
     * Frozen provider selection for this run.  A missing selection is the
     * safe default and therefore cannot authorize a privileged workspace.
     * The live provider seam in [UnifiedWorkspaceToolExecutor] may provide a
     * fresher value immediately before dispatch.
     */
    val authoritySelection: ToolAuthoritySelection = ToolAuthoritySelection(selected = null),
) {
    init {
        require(agentId.isNotBlank() && snapshotId.isNotBlank() && modelCallId.isNotBlank())
        require(sessionIdentity.isNotBlank() && configSnapshotHash.isNotBlank())
        require(policyVersion >= 0)
        require(skillId == null || trustedSkillEnvelope)
        require(skillRevision == null || skillRevision > 0)
    }

    val effectiveCapabilityNames: Set<String> get() = effectiveCapabilities.map { it.value }.toSet()
}

/** Optional copy extension because the shared backend contract intentionally has no copy method. */
data class WorkspaceCopyRequest(
    val workspaceId: String,
    val sourcePath: String,
    val destinationPath: String,
    val replace: Boolean = false,
    val expectedVersion: Long? = null,
)

interface WorkspaceCopyBackend {
    suspend fun copy(request: WorkspaceCopyRequest): runtime.mobileagent.skills.tooling.WorkspaceResult<SharedWorkspaceMutation>
}

/** Redacted workspace audit; path plaintext is never represented. */
data class WorkspaceAuditEvent(
    val phase: WorkspaceAuditPhase,
    val requestId: String,
    val agentId: String,
    val capability: DomainCapabilityId,
    val workspaceId: String,
    val relativePathSha256: String,
    val resultCode: String? = null,
    val durationMs: Long = 0L,
    /** Correlates STARTED and COMPLETED with the process-local approval. */
    val approvalId: String? = null,
    /** Canonical operation bucket; no backend/provider name is exposed. */
    val operation: WorkspaceAuditOperation = WorkspaceAuditOperation.fromCapability(capability),
    /** Terminal outcome is populated only for the terminal audit record. */
    val outcome: WorkspaceAuditOutcome? = null,
    /** Move destination is redacted independently from the source path. */
    val destinationPathSha256: String? = null,
) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 256)
        require(agentId.isNotBlank() && agentId.length <= 256)
        require(workspaceId.length <= 256)
        require(relativePathSha256.matches(Regex("[0-9a-f]{64}")))
        require(destinationPathSha256 == null || destinationPathSha256.matches(Regex("[0-9a-f]{64}")))
        require(resultCode == null || resultCode.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        require(durationMs >= 0L)
        require(approvalId == null || (approvalId.isNotBlank() && approvalId.length <= 256))
    }

    /** Compatibility/readability alias for callers that call this terminal status. */
    val terminalOutcome: WorkspaceAuditOutcome?
        get() = outcome
}

/** Audit lifecycle; COMPLETED is retained for older sinks and is terminal. */
enum class WorkspaceAuditPhase { STARTED, TERMINAL, COMPLETED }

/** Provider-neutral workspace operation buckets used by audit sinks. */
enum class WorkspaceAuditOperation {
    ENUMERATE,
    LIST,
    STAT,
    READ,
    WRITE,
    MKDIR,
    MOVE,
    DELETE,
    ;

    companion object {
        fun fromCapability(capability: DomainCapabilityId): WorkspaceAuditOperation = when (capability.value) {
            DomainCapabilityId.WORKSPACE_ENUMERATE -> ENUMERATE
            DomainCapabilityId.FILE_LIST -> LIST
            DomainCapabilityId.FILE_STAT -> STAT
            DomainCapabilityId.FILE_READ_TEXT -> READ
            DomainCapabilityId.FILE_WRITE_TEXT -> WRITE
            DomainCapabilityId.FILE_CREATE_DIRECTORY -> MKDIR
            DomainCapabilityId.FILE_MOVE -> MOVE
            DomainCapabilityId.FILE_DELETE -> DELETE
            // Patch is a conditional file mutation. Keep it in the existing
            // write audit bucket so older diagnostic sinks remain compatible.
            "file.apply_patch" -> WRITE
            else -> throw IllegalArgumentException("Unsupported workspace capability")
        }
    }
}

/** Coarse terminal classification that never embeds backend/error text. */
enum class WorkspaceAuditOutcome { SUCCEEDED, FAILED, DENIED, CANCELLED, UNKNOWN }

interface WorkspaceAuditSink {
    suspend fun record(event: WorkspaceAuditEvent): Boolean
}

class WorkspaceAuditFuse {
    @Volatile var isOpen: Boolean = false
        private set

    fun trip() { isOpen = true }
    fun reset() { isOpen = false }
}

/**
 * Persistent capability-grant metadata deliberately excludes command/cwd
 * plaintext.  This is separate from a pending command approval: pending
 * approvals are process-local and are never reconstructed from this record.
 */
data class PersistentApprovalRule(
    val approvalId: String,
    val agentId: String,
    val capability: DomainCapabilityId,
    val skillId: String? = null,
    val skillRevision: Long? = null,
    val workspaceId: String? = null,
    val pathScope: String? = null,
    val selectedAuthority: DomainAuthority = DomainAuthority.NONE,
    val dangerousMode: DomainDangerousMode = DomainDangerousMode.DISABLED,
    val toolSchemaVersion: Int = 1,
    val policyVersion: Long = 0L,
    val configSnapshotHash: String,
    val grantRevision: Long = 0L,
    val expiresAtMs: Long? = null,
) {
    init {
        require(approvalId.isNotBlank() && agentId.isNotBlank())
        require(skillRevision == null || skillRevision > 0)
        require(toolSchemaVersion > 0 && policyVersion >= 0 && grantRevision >= 0)
        require(configSnapshotHash.isNotBlank())
    }
}

interface ApprovalStateStore {
    fun load(): List<PersistentApprovalRule>
    fun save(rule: PersistentApprovalRule)
    fun remove(approvalId: String) = Unit
}

class InMemoryApprovalStateStore(initial: Iterable<PersistentApprovalRule> = emptyList()) : ApprovalStateStore {
    private val lock = Any()
    private val values = linkedMapOf<String, PersistentApprovalRule>()

    init { initial.forEach { values[it.approvalId] = it } }
    override fun load(): List<PersistentApprovalRule> = synchronized(lock) { values.values.toList() }
    override fun save(rule: PersistentApprovalRule) = synchronized(lock) { values[rule.approvalId] = rule }
    override fun remove(approvalId: String) {
        synchronized(lock) { values.remove(approvalId) }
    }
}

private fun Authority.userIntent(): AuthorityUserIntent = when (this) {
    Authority.NONE -> AuthorityUserIntent.NONE
    Authority.SHIZUKU -> AuthorityUserIntent.SHIZUKU
    Authority.WIRED_ADB -> AuthorityUserIntent.WIRED_ADB
}
