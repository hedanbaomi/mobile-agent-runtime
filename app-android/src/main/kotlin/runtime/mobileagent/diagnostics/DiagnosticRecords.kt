// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.diagnostics

/** The small, fixed schema used by in-app diagnostics. It is deliberately not a general logger. */
enum class DiagnosticLevel(val wireName: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    ERROR("ERROR"),
}

data class DiagnosticBuildInfo(
    val revision: String,
    val dirty: Boolean,
    val schemaVersion: Int,
    val buildTimeUtc: String,
    val fingerprint: String,
)

data class DiagnosticStatus(
    val enabled: Boolean,
    val currentBytes: Long,
    val previousBytes: Long,
    val lastCrashBytes: Long,
    val totalBytes: Long,
    val totalLimitBytes: Long,
    val writeFailureCount: Long = 0,
    val droppedEventCount: Long = 0,
    val droppedByteCount: Long = 0,
    val health: DiagnosticHealth = DiagnosticHealth.HEALTHY,
) {
    val sizeBytes: Long get() = totalBytes
    val failureCount: Long get() = writeFailureCount
    val dropCount: Long get() = droppedEventCount
}

enum class DiagnosticHealth(val wireName: String) {
    HEALTHY("healthy"),
    DEGRADED("degraded"),
    DISABLED("disabled"),
}

enum class DiagnosticAuthority(val wireName: String) {
    NONE("none"),
    SHIZUKU("shizuku"),
    WIRED_ADB("wired_adb"),
    ROOT_DIRECT("root_direct"),
    SHIZUKU_ROOT("shizuku_root"),
}

enum class DiagnosticAuthorityState(val wireName: String) {
    UNAVAILABLE("unavailable"),
    AVAILABLE("available"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    REAUTH_REQUIRED("reauth_required"),
    FAILED("failed"),
    UNKNOWN("unknown"),
}

enum class DiagnosticLifecycleState(val wireName: String) {
    STARTED("started"),
    READY("ready"),
    STOPPED("stopped"),
    DISCONNECTED("disconnected"),
    FAILED("failed"),
    UNKNOWN("unknown"),
}

enum class DiagnosticGrantScope(val wireName: String) {
    NONE("none"),
    READ("read"),
    WRITE("write"),
    READ_WRITE("read_write"),
    UNKNOWN("unknown"),
}

enum class DiagnosticOperation(val wireName: String) {
    ENUMERATE("enumerate"),
    READ("read"),
    WRITE("write"),
    DELETE("delete"),
    SEARCH("search"),
    APPEND("append"),
    REPLACE("replace"),
    UNKNOWN("unknown"),
}

enum class DiagnosticToolCapability(val wireName: String) {
    WORKSPACE_READ("workspace_read"),
    WORKSPACE_WRITE("workspace_write"),
    MEMORY_READ("memory_read"),
    MEMORY_WRITE("memory_write"),
    SHELL_EXECUTE("shell_execute"),
    PYTHON_EXECUTE("python_execute"),
    SEARCH("search"),
    UNKNOWN("unknown"),
}

enum class DiagnosticOperationState(val wireName: String) {
    STARTED("started"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    DENIED("denied"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
}

/** Closed backend identities for workspace-operation diagnostics. */
enum class DiagnosticWorkspaceBackendType(val wireName: String) {
    INTERNAL("internal"),
    SAF_TREE("saf_tree"),
    SHIZUKU("shizuku"),
    WIRED_ADB("wired_adb"),
    UNKNOWN("unknown"),
}

enum class DiagnosticDangerousModePolicy(val wireName: String) {
    DISABLED("disabled"),
    CONFIRM_HIGH_RISK("confirm_high_risk"),
    AUTONOMOUS("autonomous"),
    UNKNOWN("unknown"),
}

enum class DiagnosticExposureState(val wireName: String) {
    EXPOSED("exposed"),
    HIDDEN("hidden"),
    BLOCKED("blocked"),
    UNKNOWN("unknown"),
}

enum class DiagnosticApprovalState(val wireName: String) {
    REQUESTED("requested"),
    APPROVED("approved"),
    DENIED("denied"),
    EXPIRED("expired"),
    INVALIDATED("invalidated"),
    UNKNOWN("unknown"),
}

enum class DiagnosticTerminalState(val wireName: String) {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    TIMED_OUT("timed_out"),
    CANCELLED("cancelled"),
    DENIED("denied"),
    UNKNOWN("unknown"),
}

enum class DiagnosticBridgeRequestState(val wireName: String) {
    RECEIVED("received"),
    AUTHENTICATED("authenticated"),
    REJECTED("rejected"),
    STARTED("started"),
    COMPLETED("completed"),
    FAILED("failed"),
    DISCONNECTED("disconnected"),
    UNKNOWN("unknown"),
}

enum class DiagnosticLimitBucket(val wireName: String) {
    NONE("none"),
    TINY("tiny"),
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
    UNKNOWN("unknown"),
}

enum class DiagnosticPlatformGrant(val wireName: String) {
    GRANTED("granted"),
    DENIED("denied"),
    UNKNOWN("unknown"),
}

enum class DiagnosticAvailability(val wireName: String) {
    READY("ready"),
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown"),
}

enum class DiagnosticConnection(val wireName: String) {
    CONNECTED("connected"),
    CONNECTING("connecting"),
    DISCONNECTED("disconnected"),
    DEGRADED("degraded"),
    UNKNOWN("unknown"),
}

enum class DiagnosticAuthorityConfigurationReason(val wireName: String) {
    SNAPSHOT("snapshot"),
    USER_ACTION("user_action"),
    REFRESH("refresh"),
    PLATFORM_STATE_CHANGE("platform_state_change"),
    MUTATION_FAILED("mutation_failed"),
}

enum class DiagnosticDangerousModeDecisionReason(val wireName: String) {
    ACCEPTED("accepted"),
    BUILD_DENIED("build_denied"),
    AUTHORITY_UNAVAILABLE("authority_unavailable"),
    USER_REJECTED("user_rejected"),
    MUTATION_FAILED("mutation_failed"),
    UNKNOWN("unknown"),
}

enum class RuntimeToolExposureReason(val wireName: String) {
    EXPOSED("exposed"),
    MODEL_TOOL_TRANSPORT_DISABLED("model_tool_transport_disabled"),
    EMPTY_EFFECTIVE_TOOL_SET("empty_effective_tool_set"),
    /** No active, policy-valid Agent-level workspace capability survived resolution. */
    NO_AGENT_GRANT("no_agent_grant"),
    /** The immutable session snapshot has no matching workspace grant binding. */
    SESSION_BINDING_MISSING("session_binding_missing"),
    /** A trusted Skill's workspace capabilities intersected to an empty set. */
    SKILL_INTERSECTION_EMPTY("skill_intersection_empty"),
    /** A registered backend could not produce a usable operation capability set. */
    BACKEND_PROBE_FAILED("backend_probe_failed"),
    /** The executor schema was frozen before a later grant/backend change could be observed. */
    SCHEMA_FROZEN("schema_frozen"),
    /** The selected Authority does not match the workspace backend's authority scope. */
    AUTHORITY_MISMATCH("authority_mismatch"),
    NO_EFFECTIVE_AGENT_GRANTS("no_effective_agent_grants"),
    NO_SNAPSHOT_BINDINGS("no_snapshot_bindings"),
    FACTORY_UNAVAILABLE("factory_unavailable"),
}

/** Safe aggregate state for a dynamic workspace backend probe. */
enum class DiagnosticBackendProbeState(val wireName: String) {
    NOT_APPLICABLE("not_applicable"),
    READY("ready"),
    EMPTY_CAPABILITIES("empty_capabilities"),
    FAILED("failed"),
    UNKNOWN("unknown"),
}

/** Closed phases for the privileged workspace picker. */
enum class DiagnosticWorkspaceSelectionPhase(val wireName: String) {
    STARTED("started"),
    COMPLETED("completed"),
}

/** Closed phases for restoring an ephemeral privileged workspace handle. */
enum class DiagnosticWorkspaceReattachPhase(val wireName: String) {
    STARTED("started"),
    COMPLETED("completed"),
    FAILED("failed"),
}

/** Closed outcomes for an Agent default workspace mutation. */
enum class DiagnosticWorkspaceDefaultResult(val wireName: String) {
    SET("set"),
    CLEARED("cleared"),
    SUCCESS("success"),
    REJECTED("rejected"),
    FAILED("failed"),
    UNKNOWN("unknown"),
}

/** Closed resolution states for the immutable Thread workspace relation. */
enum class DiagnosticThreadWorkspaceState(val wireName: String) {
    BOUND("BOUND"),
    UNBOUND_AGENT_DEFAULT_AVAILABLE("UNBOUND_AGENT_DEFAULT_AVAILABLE"),
    UNBOUND_NO_AGENT_DEFAULT("UNBOUND_NO_AGENT_DEFAULT"),
}

/** Short alias for integrations that refer to the field's domain name directly. */
typealias ThreadWorkspaceState = DiagnosticThreadWorkspaceState

/** Only safe, aggregate result codes may enter provider diagnostics. */
enum class DiagnosticProviderResultCode(val wireName: String) {
    STARTED("started"),
    SUCCESS("success"),
    SUCCEEDED("succeeded"),
    PARTIAL("partial"),
    FAILED("failed"),
    NETWORK_UNREACHABLE("network_unreachable"),
    TLS_FAILURE("tls_failure"),
    TIMEOUT("timeout"),
    AUTH_FAILED("auth_failed"),
    MODEL_NOT_FOUND("model_not_found"),
    RATE_LIMITED("rate_limited"),
    PROVIDER_REJECTED("provider_rejected"),
    INVALID_RESPONSE("invalid_response"),
    CONFIG_INVALID("config_invalid"),
    UNKNOWN("unknown"),
}

/** HTTP class only; status codes and response bodies are intentionally omitted. */
enum class DiagnosticHttpClass(val wireName: String) {
    TWO_HUNDRED("2xx"),
    FOUR_HUNDRED("4xx"),
    FIVE_HUNDRED("5xx"),
    NONE("none"),
    UNKNOWN("unknown"),
}

data class PrivilegedWorkspaceSelectionRecord(
    val phase: DiagnosticWorkspaceSelectionPhase,
    val authority: DiagnosticAuthority,
    val workspaceId: String? = null,
    val requestRef: String? = null,
    val durationMs: Long = 0,
    val errorCode: String = "none",
)

data class PrivilegedWorkspaceBindingPersistedRecord(
    val workspaceId: String,
    val authority: DiagnosticAuthority,
    val bindingRevision: Int,
    val grantGeneration: Int,
    val requestRef: String? = null,
    val errorCode: String = "none",
)

data class PrivilegedWorkspaceReattachRecord(
    val phase: DiagnosticWorkspaceReattachPhase,
    val workspaceId: String,
    val authority: DiagnosticAuthority,
    val bindingRevision: Int,
    val grantGeneration: Int = 0,
    val requestRef: String? = null,
    val durationMs: Long? = null,
    val errorCode: String = "none",
)

data class AgentWorkspaceDefaultChangedRecord(
    val agentId: String,
    val previousWorkspaceId: String? = null,
    val workspaceId: String? = null,
    val grantGeneration: Int = 0,
    val result: DiagnosticWorkspaceDefaultResult,
    val errorCode: String? = null,
)

data class ConversationWorkspaceResolutionRecord(
    val sessionId: String,
    val agentId: String,
    val threadWorkspaceState: DiagnosticThreadWorkspaceState,
    val workspaceId: String? = null,
    val agentDefaultWorkspaceId: String? = null,
    val snapshotWorkspaceCount: Int,
    val effectiveWorkspaceGrantCount: Int,
)

data class ConversationWorkspaceRecord(
    val event: ConversationWorkspaceEvent,
    val sessionId: String,
    val agentId: String,
    val workspaceId: String,
    val authority: DiagnosticAuthority,
    val bindingRevision: Int,
    val grantGeneration: Int = 0,
    val snapshotVersion: Int = 0,
    val previousWorkspaceId: String? = null,
)

enum class ConversationWorkspaceEvent(val eventName: String) {
    BOUND("conversation_workspace_bound"),
    CHANGED("conversation_workspace_changed"),
    RESOLVED("conversation_workspace_resolved"),
}

data class WorkspaceToolExposureRecord(
    val agentId: String,
    val sessionId: String? = null,
    val workspaceId: String,
    val authority: DiagnosticAuthority,
    val capability: DiagnosticToolCapability,
    val exposed: DiagnosticExposureState,
    val grantGeneration: Int = 0,
    val snapshotVersion: Int = 0,
    val reasonCode: String = "unknown",
)

data class ProviderConnectionTestRecord(
    val providerId: String,
    val modelId: String,
    val resultCode: DiagnosticProviderResultCode,
    val httpClass: DiagnosticHttpClass = DiagnosticHttpClass.NONE,
    val durationMs: Long = 0,
)

data class ProviderCapabilityProbeRecord(
    val providerId: String,
    val modelId: String,
    val resultCode: DiagnosticProviderResultCode,
    val httpClass: DiagnosticHttpClass = DiagnosticHttpClass.NONE,
    val durationMs: Long = 0,
)

data class AuthoritySelectionChangedRecord(
    val selectedAuthority: DiagnosticAuthority,
    val previousAuthority: DiagnosticAuthority? = null,
    val requestRef: String? = null,
)

data class AuthorityStateChangedRecord(
    val authority: DiagnosticAuthority,
    val state: DiagnosticAuthorityState,
    val previousState: DiagnosticAuthorityState? = null,
    val errorCode: String = "unknown",
)

data class ShizukuLifecycleRecord(
    val state: DiagnosticLifecycleState,
    val errorCode: String = "unknown",
    val requestRef: String? = null,
)

data class WiredAdbLifecycleRecord(
    val state: DiagnosticLifecycleState,
    val errorCode: String = "unknown",
    val requestRef: String? = null,
)

data class WorkspaceGrantChangedRecord(
    val workspaceId: String,
    val scope: DiagnosticGrantScope,
    val granted: Boolean,
    val requestRef: String? = null,
    val errorCode: String = "unknown",
)

data class WorkspaceOperationStateRecord(
    val workspaceId: String,
    val operation: DiagnosticOperation,
    val state: DiagnosticOperationState,
    val count: Int = 0,
    val requestRef: String? = null,
    val errorCode: String = "unknown",
    val backendType: DiagnosticWorkspaceBackendType = DiagnosticWorkspaceBackendType.UNKNOWN,
)

data class SkillMemoryOperationStateRecord(
    val skillId: String,
    val operation: DiagnosticOperation,
    val state: DiagnosticOperationState,
    val count: Int = 0,
    val requestRef: String? = null,
    val errorCode: String = "unknown",
)

data class DangerousModeChangedRecord(
    val enabled: Boolean,
    val policy: DiagnosticDangerousModePolicy,
    val requestRef: String? = null,
)

data class ShellToolExposureChangedRecord(
    val agentId: String,
    val exposed: DiagnosticExposureState,
    val skillId: String? = null,
    val authority: DiagnosticAuthority = DiagnosticAuthority.NONE,
    val reasonCode: String = "unknown",
    val requestRef: String? = null,
)

data class ToolApprovalStateRecord(
    val callId: String,
    val state: DiagnosticApprovalState,
    val approvalId: String? = null,
    val agentId: String? = null,
    val skillId: String? = null,
    val requestRef: String? = null,
    val reasonCode: String = "unknown",
    val capability: DiagnosticToolCapability = DiagnosticToolCapability.UNKNOWN,
    val authority: DiagnosticAuthority = DiagnosticAuthority.NONE,
    val sessionRef: String? = null,
)

data class ShellExecutionStateRecord(
    val commandSha256: String,
    val terminalState: DiagnosticTerminalState,
    val authority: DiagnosticAuthority,
    val limitBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
    val stdoutBytes: Int = 0,
    val stderrBytes: Int = 0,
    val durationBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
    val requestRef: String? = null,
    val callId: String? = null,
    val agentId: String? = null,
    val skillId: String? = null,
)

data class BridgeRequestStateRecord(
    val state: DiagnosticBridgeRequestState,
    val authority: DiagnosticAuthority,
    val requestRef: String,
    val errorCode: String = "unknown",
    val durationBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
    val count: Int = 0,
)

data class DiagnosticDropSummaryRecord(
    val droppedEvents: Int,
    val droppedBytes: Int,
    val failureCount: Int,
    val health: DiagnosticHealth,
    val reasonCode: String = "unknown",
)

enum class RuntimeToolingUnavailableCode(val wireName: String) {
    TOOL_EXECUTION_CONTEXT_UNAVAILABLE("TOOL_EXECUTION_CONTEXT_UNAVAILABLE"),
    TOOL_EXECUTOR_FACTORY_UNAVAILABLE("TOOL_EXECUTOR_FACTORY_UNAVAILABLE"),
}

data class RuntimeToolingUnavailableRecord(
    val errorCode: RuntimeToolingUnavailableCode,
    val sessionRef: String? = null,
    val runRef: String? = null,
)

/** Tool dispatch/result lifecycle as observed at the runtime boundary. */
enum class DiagnosticToolRunState(val wireName: String) {
    DISPATCHED("dispatched"),
    WAITING_APPROVAL("waiting_approval"),
    VALUE("value"),
    DENIED("denied"),
    COMPLETED_WITHHELD("completed_withheld"),
    INVALID("invalid"),
    FAILED("failed"),
    UNKNOWN_OUTCOME("unknown_outcome"),
}

data class ToolInvocationStateRecord(
    val callId: String,
    val state: DiagnosticToolRunState,
    val requestRef: String? = null,
    val sessionRef: String? = null,
    val agentId: String? = null,
    val capability: DiagnosticToolCapability = DiagnosticToolCapability.UNKNOWN,
    val authority: DiagnosticAuthority = DiagnosticAuthority.NONE,
    val errorCode: String? = null,
)

/** Model transport stages; wire names mirror provider-api's ModelDiagnosticStage. */
enum class DiagnosticModelStage(val wireName: String) {
    REQUEST_VALIDATION("request_validation"),
    REQUEST_READY("request_ready"),
    REQUEST_DISPATCH("request_dispatch"),
    RESPONSE_HEADERS("response_headers"),
    RESPONSE_BODY("response_body"),
    STREAM_EVENT("stream_event"),
    TERMINAL("terminal"),
}

enum class DiagnosticModelDispatchState(val wireName: String) {
    NOT_DISPATCHED("not_dispatched"),
    DISPATCHED("dispatched"),
    RESPONSE_RECEIVED("response_received"),
    UNKNOWN_AFTER_DISPATCH("unknown_after_dispatch"),
}

/** Typed model transport evidence.  Bodies, URLs and provider text never enter it. */
data class ModelRequestStateRecord(
    val stage: DiagnosticModelStage,
    val dispatchState: DiagnosticModelDispatchState,
    val requestRef: String? = null,
    val sessionRef: String? = null,
    val endpointKind: String? = null,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val exceptionType: String? = null,
    val durationMs: Long = 0,
    val responseBytes: Long = 0,
    val streaming: Boolean? = null,
    val messageCount: Int = 0,
    val imageCount: Int = 0,
    val toolCount: Int = 0,
    val eventType: String? = null,
)

data class RunStateRecord(
    val state: DiagnosticTerminalState,
    val requestRef: String? = null,
    val sessionRef: String? = null,
    val reasonCode: String = "unknown",
    /**
     * Closed failure classification (a `MessageErrorCode` name) so a terminal
     * run can be told apart from the log alone — stream truncation, malformed
     * provider response, tool validation failure and unknown outcome all used
     * to collapse into one opaque row (R2 QA P2).
     */
    val failureCode: String? = null,
    val modelRounds: Int = 0,
    val toolCalls: Int = 0,
)

/** Compaction lifecycle so a failed/unknown summary is never only a UI string. */
enum class DiagnosticCompactionState(val wireName: String) {
    PREPARED("prepared"),
    DISPATCHED("dispatched"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN_OUTCOME("unknown_outcome"),
}

data class ContextCompactionStateRecord(
    val state: DiagnosticCompactionState,
    val requestRef: String? = null,
    val sessionRef: String? = null,
    val reasonCode: String = "unknown",
)

data class AuthorityConfigurationStateRecord(
    val authority: DiagnosticAuthority,
    val userIntentEnabled: Boolean,
    val selected: Boolean,
    val platformGrant: DiagnosticPlatformGrant,
    val availability: DiagnosticAvailability,
    val connection: DiagnosticConnection,
    val configured: Boolean,
    val reason: DiagnosticAuthorityConfigurationReason,
)

data class DangerousModeDecisionRecord(
    val requestedPolicy: DiagnosticDangerousModePolicy,
    val accepted: Boolean,
    val buildAllowed: Boolean,
    val buildKnown: Boolean,
    val authority: DiagnosticAuthority,
    val reason: DiagnosticDangerousModeDecisionReason,
    val requestRef: String? = null,
)

data class RuntimeToolExposureRecord(
    val agentId: String,
    val sessionRef: String? = null,
    val runRef: String? = null,
    val effectiveGrantCount: Int,
    val snapshotBindingCount: Int,
    val exposedToolCount: Int,
    val webToolCount: Int = 0,
    val mcpToolCount: Int = 0,
    val pythonToolCount: Int = 0,
    val memoryToolCount: Int = 0,
    val workspaceToolCount: Int = 0,
    val shellToolCount: Int = 0,
    val registeredWorkspaceCount: Int = 0,
    val grantedWorkspaceCount: Int = 0,
    val boundWorkspaceCount: Int = 0,
    val registeredGrantedWorkspaceCount: Int = 0,
    val selectedAuthority: DiagnosticAuthority = DiagnosticAuthority.NONE,
    val selectedAuthorityReady: Boolean = false,
    val safGrantActive: Boolean = false,
    val safBackendRegistered: Boolean = false,
    val modelToolTransportEnabled: Boolean = true,
    val reason: RuntimeToolExposureReason,
    /** Count of generic Agent grants that survived policy and snapshot revalidation. */
    val effectiveAgentWorkspaceCapabilityCount: Int = 0,
    /** Count remaining after applying the current trusted Skill intersection. */
    val effectiveSkillWorkspaceCapabilityCount: Int = 0,
    /** Number of registered workspace backends whose current descriptor is usable. */
    val backendReadyWorkspaceCount: Int = 0,
    /** Number of registered workspace backends whose probe failed or was unusable. */
    val backendProbeFailureCount: Int = 0,
    /** Number of workspaces rejected because their Authority differs from the selected one. */
    val authorityMismatchWorkspaceCount: Int = 0,
    /** True when this executor's model-facing schema was frozen for the current run. */
    val schemaFrozen: Boolean = false,
    /** Aggregate probe result for the selected/delivered workspace backends. */
    val backendProbeState: DiagnosticBackendProbeState = DiagnosticBackendProbeState.UNKNOWN,
    /** Current SAF descriptor operation-capability count; no URI or provider identity. */
    val safOperationCapabilityCount: Int = 0,
    /** Current SAF dynamic descriptor probe state. */
    val safProbeState: DiagnosticBackendProbeState = DiagnosticBackendProbeState.UNKNOWN,
)

// Short aliases keep call sites readable while the *Record names remain the canonical API docs.
typealias AuthoritySelectionChanged = AuthoritySelectionChangedRecord
typealias AuthorityStateChanged = AuthorityStateChangedRecord
typealias ShizukuLifecycle = ShizukuLifecycleRecord
typealias WiredAdbLifecycle = WiredAdbLifecycleRecord
typealias WorkspaceGrantChanged = WorkspaceGrantChangedRecord
typealias WorkspaceOperationState = WorkspaceOperationStateRecord
typealias SkillMemoryOperationState = SkillMemoryOperationStateRecord
typealias DangerousModeChanged = DangerousModeChangedRecord
typealias ShellToolExposureChanged = ShellToolExposureChangedRecord
typealias ToolApprovalState = ToolApprovalStateRecord
typealias ShellExecutionState = ShellExecutionStateRecord
typealias BridgeRequestState = BridgeRequestStateRecord
typealias DiagnosticDropSummary = DiagnosticDropSummaryRecord
typealias RuntimeToolingUnavailable = RuntimeToolingUnavailableRecord
typealias AuthorityConfigurationState = AuthorityConfigurationStateRecord
typealias DangerousModeDecision = DangerousModeDecisionRecord
typealias RuntimeToolExposure = RuntimeToolExposureRecord
