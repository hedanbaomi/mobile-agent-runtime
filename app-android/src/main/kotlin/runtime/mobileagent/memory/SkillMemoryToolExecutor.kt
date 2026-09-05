// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

/** Canonical domain capability for strict UTF-8 file reads. */
const val SKILL_MEMORY_READ_CAPABILITY: String = CapabilityId.MEMORY_READ

/** Canonical domain capability for bounded literal text search. */
const val SKILL_MEMORY_SEARCH_CAPABILITY: String = CapabilityId.MEMORY_SEARCH

/** Canonical domain capability for atomic append. */
const val SKILL_MEMORY_APPEND_CAPABILITY: String = CapabilityId.MEMORY_APPEND

/** Canonical domain capability for whole-file create/replace. */
const val SKILL_MEMORY_REPLACE_CAPABILITY: String = CapabilityId.MEMORY_REPLACE

private val SKILL_MEMORY_CAPABILITIES = setOf(
    SKILL_MEMORY_READ_CAPABILITY,
    SKILL_MEMORY_SEARCH_CAPABILITY,
    SKILL_MEMORY_APPEND_CAPABILITY,
    SKILL_MEMORY_REPLACE_CAPABILITY,
)

/** Provider call IDs are model supplied; keep them bounded before they reach approval/ref sinks. */
private val SKILL_MEMORY_CALL_ID = Regex("[A-Za-z0-9._:-]{1,128}")

/**
 * Small injection port for the host persistence layer.  The Android executor does not depend
 * on data/sqlite: the eventual SkillMemoryRepository can implement this port and provide the
 * current Agent/snapshot/install/grant state at each approval boundary.
 */
interface SkillMemoryBindingPort {
    /** Return only the bindings visible to this exact immutable Agent snapshot. */
    fun bindings(agentId: String, snapshotId: String): List<SkillMemoryBinding>

    /**
     * Optional trusted-envelope refinement.  A host adapter should normally perform this
     * intersection from canonical grant rows; the default keeps older adapters source-compatible
     * while ensuring a frozen per-Skill capability set can never be widened by the executor.
     */
    fun bindings(
        agentId: String,
        snapshotId: String,
        trustedSkillId: String?,
        effectiveCapabilities: Set<String>?,
    ): List<SkillMemoryBinding> = bindings(agentId, snapshotId)
        .asSequence()
        .filter { trustedSkillId == null || it.installId == trustedSkillId }
        .map { binding ->
            if (effectiveCapabilities == null) binding
            else binding.copy(capabilities = binding.capabilities intersect effectiveCapabilities)
        }
        .toList()

    /** Return the current binding, or null if the binding is no longer valid. */
    fun current(agentId: String, snapshotId: String, original: SkillMemoryBinding): SkillMemoryBinding?

    fun current(
        agentId: String,
        snapshotId: String,
        original: SkillMemoryBinding,
        effectiveCapabilities: Set<String>?,
    ): SkillMemoryBinding? = (current(agentId, snapshotId, original)
        ?: bindings(agentId, snapshotId).singleOrNull { it.sameAuthorizationIdentityAs(original) })?.let { binding ->
        if (effectiveCapabilities == null) binding
        else binding.copy(capabilities = binding.capabilities intersect effectiveCapabilities)
    }
}

private fun SkillMemoryBinding.sameAuthorizationIdentityAs(other: SkillMemoryBinding): Boolean =
    installId == other.installId &&
        packageHash == other.packageHash &&
        memorySpaceId == other.memorySpaceId &&
        agentId == other.agentId &&
        snapshotId == other.snapshotId &&
        enabled == other.enabled &&
        grantId == other.grantId &&
        grantRevision == other.grantRevision &&
        memoryMetadataRevision == other.memoryMetadataRevision

/** In-memory adapter useful for tests and for a host before the DB repository is wired. */
class StaticSkillMemoryBindingPort(
    private val source: () -> List<SkillMemoryBinding>,
) : SkillMemoryBindingPort {
    override fun bindings(agentId: String, snapshotId: String): List<SkillMemoryBinding> =
        source().filter { it.agentId == agentId && it.snapshotId == snapshotId }

    override fun current(agentId: String, snapshotId: String, original: SkillMemoryBinding): SkillMemoryBinding? =
        bindings(agentId, snapshotId).singleOrNull { it == original }
}

/**
 * Read-only projection used by Skills UI and diagnostics.  It deliberately contains no Skill,
 * Agent, install, package, path, or storage identifiers.  [EMPTY] means the current trusted
 * binding is valid but has no entries; it is distinct from [GRANT_LOST] and [UNAVAILABLE].
 */
enum class SkillMemoryAvailabilityState {
    ENABLED,
    EMPTY,
    GRANT_LOST,
    UNAVAILABLE,
}

data class SkillMemoryAvailabilitySnapshot(
    val state: SkillMemoryAvailabilityState,
    val canRead: Boolean = false,
    val canSearch: Boolean = false,
    val canAppend: Boolean = false,
    val canReplace: Boolean = false,
    val entryCount: Int = 0,
    val totalBytes: Long = 0L,
) {
    init {
        require(entryCount >= 0 && totalBytes >= 0L)
    }

    companion object {
        val UNAVAILABLE = SkillMemoryAvailabilitySnapshot(SkillMemoryAvailabilityState.UNAVAILABLE)
        val GRANT_LOST = SkillMemoryAvailabilitySnapshot(SkillMemoryAvailabilityState.GRANT_LOST)
    }
}

/**
 * Canonical Skill-memory persistence seam.  The binding and every storage operation are owned by
 * one adapter, so the executor cannot accidentally use a second raw filesystem source of truth.
 * Implementations must revalidate [SkillMemoryBinding] identity and grant revision themselves;
 * the executor repeats that check immediately before consuming an approval.
 */
interface SkillMemoryRepositoryPort : SkillMemoryBindingPort {
    fun list(binding: SkillMemoryBinding): SkillMemoryListResult

    fun read(binding: SkillMemoryBinding, path: String, maxBytes: Int): SkillMemoryReadResult

    fun search(binding: SkillMemoryBinding, query: String, maxResults: Int): SkillMemorySearchResult

    fun append(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
    ): SkillMemoryWriteResult

    fun replace(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
    ): SkillMemoryWriteResult

    /** The UI/status seam is not model-facing and returns no raw identity or content. */
    fun availability(
        agentId: String,
        snapshotId: String,
        trustedSkillId: String,
    ): SkillMemoryAvailabilitySnapshot = runCatching {
        val binding = bindings(agentId, snapshotId)
            .singleOrNull { it.installId == trustedSkillId }
            ?: return@runCatching SkillMemoryAvailabilitySnapshot.GRANT_LOST
        if (!binding.enabled || binding.grantRevision <= 0 || binding.capabilities.none { it in SKILL_MEMORY_CAPABILITIES }) {
            return@runCatching SkillMemoryAvailabilitySnapshot.GRANT_LOST
        }
        val entries = list(binding).entries
        val caps = binding.capabilities
        SkillMemoryAvailabilitySnapshot(
            state = if (entries.isEmpty()) SkillMemoryAvailabilityState.EMPTY else SkillMemoryAvailabilityState.ENABLED,
            canRead = SKILL_MEMORY_READ_CAPABILITY in caps,
            canSearch = SKILL_MEMORY_SEARCH_CAPABILITY in caps,
            canAppend = SKILL_MEMORY_APPEND_CAPABILITY in caps,
            canReplace = SKILL_MEMORY_REPLACE_CAPABILITY in caps,
            entryCount = entries.size,
            totalBytes = entries.sumOf { it.bytes },
        )
    }.getOrElse { SkillMemoryAvailabilitySnapshot.UNAVAILABLE }
}

/**
 * Compatibility adapter for the old Android-only backend.  RuntimeIntegration must not use this
 * adapter in production; it exists only while focused legacy tests migrate to the SQLite-backed
 * [SkillMemoryRepositoryPort].
 */
@Deprecated("Use the canonical SkillMemoryRepositoryPort backed by data/sqlite")
private class LegacySkillMemoryRepositoryPort(
    private val backend: SkillMemoryBackend,
    private val bindingsPort: SkillMemoryBindingPort,
) : SkillMemoryRepositoryPort {
    override fun bindings(agentId: String, snapshotId: String): List<SkillMemoryBinding> =
        bindingsPort.bindings(agentId, snapshotId)

    override fun current(agentId: String, snapshotId: String, original: SkillMemoryBinding): SkillMemoryBinding? =
        bindingsPort.current(agentId, snapshotId, original)

    override fun list(binding: SkillMemoryBinding): SkillMemoryListResult = backend.space(binding).list()

    override fun read(binding: SkillMemoryBinding, path: String, maxBytes: Int): SkillMemoryReadResult =
        backend.space(binding).read(path, maxBytes)

    override fun search(binding: SkillMemoryBinding, query: String, maxResults: Int): SkillMemorySearchResult =
        backend.space(binding).search(query, maxResults)

    override fun append(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
    ): SkillMemoryWriteResult = backend.space(binding).append(path, text, expectedVersion)

    override fun replace(
        binding: SkillMemoryBinding,
        path: String,
        text: String,
        expectedVersion: String?,
    ): SkillMemoryWriteResult = backend.space(binding).replace(path, text, expectedVersion)
}

/** Typed hand-off to the canonical process-local approval owner.  No model arguments are passed. */
enum class SkillMemoryApprovalDecision {
    REQUIRED,
    APPROVED,
    DENIED,
    EXPIRED,
    CANCELLED,
    UNKNOWN,
}

data class SkillMemoryApprovalRequest(
    val callId: String,
    val operation: SkillMemoryDiagnosticOperation,
    val memoryHandle: String,
    val agentId: String,
    val snapshotId: String,
    val grantRevision: Int,
) {
    init {
        require(SKILL_MEMORY_CALL_ID.matches(callId))
        require(agentId.isNotBlank() && snapshotId.isNotBlank())
        require(memoryHandle.matches(Regex("[0-9a-f]{64}")))
        require(grantRevision > 0)
    }
}

interface SkillMemoryApprovalOwner {
    fun request(request: SkillMemoryApprovalRequest): SkillMemoryApprovalDecision
    fun approve(callId: String): SkillMemoryApprovalDecision
    fun reject(callId: String): SkillMemoryApprovalDecision
    fun expire(callId: String): SkillMemoryApprovalDecision
    fun cancel(callId: String): SkillMemoryApprovalDecision
}

/**
 * Agent-facing executor for Skill memory. Every operation is approval-gated because even a read
 * or search result may be sent to a remote model. Handles are generated once per trusted binding
 * and resolved only against this executor's immutable discovery map. The public names map one to
 * one to the v2 canonical capabilities: read/search/append/replace.
 */
class SkillMemoryToolExecutor(
    private val repository: SkillMemoryRepositoryPort,
    private val agentId: String,
    private val snapshotId: String,
    /** Runtime-created trusted Skill envelope.  Null fails closed and exposes no memory tools. */
    private val trustedSkillId: String? = null,
    /**
     * Runtime-created trusted Skill envelopes for multi-Skill runs.  The
     * single [trustedSkillId] is always included.  Each Skill's memory is
     * exposed under its own runtime-assigned tool namespace
     * (`memory_<opaque>.<operation>`); the model never supplies the identity.
     */
    private val trustedSkillIds: Set<String> = emptySet(),
    /** Frozen per-Skill capabilities; null means the canonical adapter supplies the intersection. */
    private val effectiveCapabilities: Set<String>? = null,
    private val limits: SkillMemoryLimits = SkillMemoryLimits(),
    private val diagnosticSink: SkillMemoryDiagnosticSink = NoopSkillMemoryDiagnosticSink,
    private val diagnosticRefProvider: SkillMemoryDiagnosticRefProvider = EmptySkillMemoryDiagnosticRefProvider,
    private val approvalOwner: SkillMemoryApprovalOwner? = null,
) : ToolExecutor {
    /**
     * Compatibility constructor for hosts/tests still passing the old raw backend.  The
     * constructor is intentionally deprecated and still requires [trustedSkillId] to avoid
     * exposing every Skill in a snapshot through the legacy path.
     */
    @Deprecated("Pass a canonical SkillMemoryRepositoryPort")
    constructor(
        backend: SkillMemoryBackend,
        agentId: String,
        snapshotId: String,
        bindingPort: SkillMemoryBindingPort,
        trustedSkillId: String? = null,
        effectiveCapabilities: Set<String>? = null,
        limits: SkillMemoryLimits = SkillMemoryLimits(),
        diagnosticSink: SkillMemoryDiagnosticSink = NoopSkillMemoryDiagnosticSink,
        diagnosticRefProvider: SkillMemoryDiagnosticRefProvider = EmptySkillMemoryDiagnosticRefProvider,
    ) : this(
        repository = LegacySkillMemoryRepositoryPort(backend, bindingPort),
        agentId = agentId,
        snapshotId = snapshotId,
        trustedSkillId = trustedSkillId,
        effectiveCapabilities = effectiveCapabilities,
        limits = limits,
        diagnosticSink = diagnosticSink,
        diagnosticRefProvider = diagnosticRefProvider,
    )

    /** Convenience adapter for hosts/tests that have not introduced a repository class yet. */
    @Deprecated("Pass a canonical SkillMemoryRepositoryPort")
    constructor(
        backend: SkillMemoryBackend,
        agentId: String,
        snapshotId: String,
        bindings: () -> List<SkillMemoryBinding>,
        trustedSkillId: String? = null,
        limits: SkillMemoryLimits = SkillMemoryLimits(),
    ) : this(
        repository = LegacySkillMemoryRepositoryPort(backend, StaticSkillMemoryBindingPort(bindings)),
        agentId = agentId,
        snapshotId = snapshotId,
        trustedSkillId = trustedSkillId,
        limits = limits,
    )

    @Deprecated("Pass a canonical SkillMemoryRepositoryPort")
    constructor(
        backend: SkillMemoryBackend,
        agentId: String,
        snapshotId: String,
        bindings: () -> List<SkillMemoryBinding>,
        limits: SkillMemoryLimits,
    ) : this(
        repository = LegacySkillMemoryRepositoryPort(backend, StaticSkillMemoryBindingPort(bindings)),
        agentId = agentId,
        snapshotId = snapshotId,
        limits = limits,
    )

    @Deprecated("Pass a canonical SkillMemoryRepositoryPort")
    constructor(
        backend: SkillMemoryBackend,
        agentId: String,
        snapshotId: String,
        bindings: List<SkillMemoryBinding>,
        trustedSkillId: String? = null,
        limits: SkillMemoryLimits = SkillMemoryLimits(),
    ) : this(
        repository = LegacySkillMemoryRepositoryPort(backend, StaticSkillMemoryBindingPort { bindings }),
        agentId = agentId,
        snapshotId = snapshotId,
        trustedSkillId = trustedSkillId,
        limits = limits,
    )

    @Deprecated("Pass a canonical SkillMemoryRepositoryPort")
    constructor(
        backend: SkillMemoryBackend,
        agentId: String,
        snapshotId: String,
        bindings: List<SkillMemoryBinding>,
        limits: SkillMemoryLimits,
    ) : this(
        repository = LegacySkillMemoryRepositoryPort(backend, StaticSkillMemoryBindingPort { bindings }),
        agentId = agentId,
        snapshotId = snapshotId,
        limits = limits,
    )

    private data class Discovered(
        val handle: String,
        val binding: SkillMemoryBinding,
    )

    private data class Pending(
        val call: ToolCall,
        val args: JsonObject,
        val discovered: Discovered,
        val operation: SkillMemoryDiagnosticOperation,
    )

    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val usedCallIds = linkedSetOf<String>()
    /** Keeps the operation type for a terminal diagnostic even after its pending state is gone. */
    private val callOperations = linkedMapOf<String, SkillMemoryDiagnosticOperation>()
    /** Prevents reject/expire/approve races from emitting more than one terminal event. */
    private val terminalCallIds = linkedSetOf<String>()
    /** Bindings of terminally completed calls, for replay-disclosure revalidation. */
    private val completedBindings = linkedMapOf<String, SkillMemoryBinding>()
    /** Every trusted Skill identity for this run: single envelope plus the multi-Skill set. */
    private val effectiveTrustedSkillIds: Set<String> =
        (trustedSkillIds + listOfNotNull(trustedSkillId)).map { it.trim() }.filter { it.isNotBlank() }.toSet()
    private val discovered: Map<String, Discovered> = discover()

    override val specs: List<ToolSpec> = buildSpecs(discovered.values.toList(), limits, effectiveTrustedSkillIds)

    override suspend fun invoke(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            // The tool name selects the runtime-assigned Skill namespace; the
            // handle argument must belong to that same namespace.  The model
            // never supplies the Skill identity itself.
            val resolved = resolveTool(call.name)
                ?: run {
                    val requestedOperation = SkillMemoryDiagnosticOperation.UNKNOWN
                    callOperations.putIfAbsent(call.callId, requestedOperation)
                    emitTerminal(requestedOperation, call, null, errorCode = "unknown_tool")
                    return@synchronized ToolResult.Invalid("Unknown memory tool")
                }
            val requestedOperation = resolved.operation
            if (!SKILL_MEMORY_CALL_ID.matches(call.callId)) {
                emitTerminal(requestedOperation, call, null, errorCode = "invalid_call_id")
                return@synchronized ToolResult.Invalid(
                    if (call.callId.isBlank()) "Memory call ID is missing" else "Memory call ID is invalid",
                )
            }
            val operation = callOperations.putIfAbsent(call.callId, requestedOperation) ?: requestedOperation
            if (!usedCallIds.add(call.callId)) {
                // A duplicate of a still-pending request is the same approval, not a second
                // operation.  Return the pending signal without producing a second terminal row.
                if (pending.containsKey(call.callId)) return@synchronized ToolResult.NeedsApproval
                emitTerminal(operation, call, null, errorCode = "duplicate_call")
                return@synchronized ToolResult.Invalid("Memory call ID was already used")
            }
            val spec = specs.singleOrNull { it.name == call.name }
                ?: run {
                    emitTerminal(operation, call, null, errorCode = "unknown_tool")
                    return@synchronized ToolResult.Invalid("Unknown memory tool")
                }
            if (call.argumentsJson.toByteArray(Charsets.UTF_8).size > MAX_ARGUMENT_BYTES) {
                emitTerminal(operation, call, null, errorCode = "arguments_limit")
                return@synchronized ToolResult.Invalid("Memory arguments exceed the limit")
            }
            val args = parseObject(call.argumentsJson)
                ?: run {
                    emitTerminal(operation, call, null, errorCode = "invalid_arguments")
                    return@synchronized ToolResult.Invalid("Memory arguments must be a JSON object")
                }
            val validationError = validateArguments(spec.name, args)
            if (validationError != null) {
                val safeHandle = args.stringValue(HANDLE)?.takeIf { it in discovered }
                emitTerminal(operation, call, safeHandle, errorCode = "invalid_arguments")
                return@synchronized ToolResult.Invalid(validationError)
            }
            val entry = discovered[args.stringValue(HANDLE)]
                ?: run {
                    emitTerminal(operation, call, null, errorCode = "binding_unavailable", state = SkillMemoryDiagnosticState.DENIED)
                    return@synchronized ToolResult.Denied("Memory handle is unavailable")
                }
            if (entry.binding.installId !in resolved.installIds) {
                emitTerminal(operation, call, entry.handle, errorCode = "namespace_mismatch", state = SkillMemoryDiagnosticState.DENIED)
                return@synchronized ToolResult.Denied("Memory handle does not belong to this Skill")
            }
            if (!hasCapability(entry.binding, resolved.legacyName)) {
                emitTerminal(operation, call, entry.handle, errorCode = "capability_denied", state = SkillMemoryDiagnosticState.DENIED)
                return@synchronized ToolResult.Denied("Memory capability is not granted")
            }
            if (!isCurrent(entry.binding)) {
                emitTerminal(operation, call, entry.handle, errorCode = "binding_stale", state = SkillMemoryDiagnosticState.EXPIRED)
                return@synchronized ToolResult.Denied("Agent, snapshot, Skill, package, or grant changed")
            }
            val approval = approvalOwner?.let {
                try {
                    it.request(
                        SkillMemoryApprovalRequest(
                            callId = call.callId,
                            operation = operation,
                            memoryHandle = entry.handle,
                            agentId = agentId,
                            snapshotId = snapshotId,
                            grantRevision = entry.binding.grantRevision,
                        ),
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    emitTerminal(operation, call, entry.handle,
                        errorCode = "cancelled_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    throw cancelled
                } catch (_: Throwable) {
                    emitTerminal(operation, call, entry.handle, errorCode = "approval_owner_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    return@synchronized ToolResult.UnknownOutcome("Memory approval owner outcome is unknown")
                }
            } ?: SkillMemoryApprovalDecision.REQUIRED
            when (approval) {
                SkillMemoryApprovalDecision.REQUIRED -> {
                    pending[call.callId] = Pending(call, args, entry, operation)
                    emitDiagnostic(operation, SkillMemoryDiagnosticState.STARTED, call, entry.handle)
                    ToolResult.NeedsApproval
                }
                SkillMemoryApprovalDecision.APPROVED -> {
                    if (!isCurrent(entry.binding)) {
                        emitTerminal(operation, call, entry.handle,
                            errorCode = "approval_stale", state = SkillMemoryDiagnosticState.EXPIRED)
                        ToolResult.Denied("Memory approval expired after binding changed")
                    } else {
                        executeApproved(Pending(call, args, entry, operation))
                    }
                }
                SkillMemoryApprovalDecision.DENIED -> {
                    emitTerminal(operation, call, entry.handle, errorCode = "approval_denied", state = SkillMemoryDiagnosticState.DENIED)
                    ToolResult.Denied("Memory approval denied")
                }
                SkillMemoryApprovalDecision.EXPIRED -> {
                    emitTerminal(operation, call, entry.handle, errorCode = "approval_expired", state = SkillMemoryDiagnosticState.EXPIRED)
                    ToolResult.Denied("Memory approval expired")
                }
                SkillMemoryApprovalDecision.CANCELLED -> {
                    emitTerminal(operation, call, entry.handle, errorCode = "approval_cancelled", state = SkillMemoryDiagnosticState.CANCELLED)
                    ToolResult.Denied("Memory approval cancelled")
                }
                SkillMemoryApprovalDecision.UNKNOWN -> {
                    emitTerminal(operation, call, entry.handle, errorCode = "approval_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    ToolResult.UnknownOutcome("Memory approval outcome is unknown")
                }
            }
        }
    }

    override suspend fun approve(callId: String): ToolResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val pendingCall = pending[callId]
                ?: run {
                    val operation = callOperations[callId] ?: SkillMemoryDiagnosticOperation.UNKNOWN
                    emitTerminal(operation, ToolCall(callId, operation.toolName(), "{}"), null, errorCode = "approval_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    return@synchronized ToolResult.Invalid("No pending memory approval")
                }
            val entry = pendingCall.discovered
            val approval = approvalOwner?.let {
                try {
                    it.approve(callId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, entry.handle,
                        errorCode = "cancelled_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    throw cancelled
                } catch (_: Throwable) {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, entry.handle,
                        errorCode = "approval_owner_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    return@synchronized ToolResult.UnknownOutcome("Memory approval owner outcome is unknown")
                }
            } ?: SkillMemoryApprovalDecision.APPROVED
            when (approval) {
                SkillMemoryApprovalDecision.REQUIRED -> return@synchronized ToolResult.NeedsApproval
                SkillMemoryApprovalDecision.DENIED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, entry.handle,
                        errorCode = "approval_denied", state = SkillMemoryDiagnosticState.DENIED)
                    return@synchronized ToolResult.Denied("Memory approval denied")
                }
                SkillMemoryApprovalDecision.EXPIRED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, entry.handle,
                        errorCode = "approval_expired", state = SkillMemoryDiagnosticState.EXPIRED)
                    return@synchronized ToolResult.Denied("Memory approval expired")
                }
                SkillMemoryApprovalDecision.CANCELLED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, entry.handle,
                        errorCode = "approval_cancelled", state = SkillMemoryDiagnosticState.CANCELLED)
                    return@synchronized ToolResult.Denied("Memory approval cancelled")
                }
                SkillMemoryApprovalDecision.UNKNOWN -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, entry.handle,
                        errorCode = "approval_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    return@synchronized ToolResult.UnknownOutcome("Memory approval outcome is unknown")
                }
                SkillMemoryApprovalDecision.APPROVED -> Unit
            }
            pending.remove(callId)
            // This check is intentionally immediately before the filesystem side effect (or
            // disclosure).  Equality covers install ID, package hash, memory space, enablement,
            // capability set, grant revision, and memory metadata revision.
            if (!isCurrent(entry.binding)) {
                emitTerminal(pendingCall.operation, pendingCall.call, entry.handle,
                    errorCode = "approval_stale", state = SkillMemoryDiagnosticState.EXPIRED)
                return@synchronized ToolResult.Denied("Memory approval expired after binding changed")
            }
            executeApproved(pendingCall)
        }
    }

    /** Explicitly deny the process-local approval without touching the repository. */
    override suspend fun reject(callId: String): ToolResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val pendingCall = pending[callId]
                ?: return@synchronized unknownApproval(callId, "approval_unknown", ToolResult.Invalid("No pending memory approval"))
            val decision = approvalOwner?.let { owner ->
                try {
                    owner.reject(callId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "cancelled_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    throw cancelled
                } catch (_: Throwable) {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_owner_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    return@synchronized ToolResult.UnknownOutcome("Memory approval owner outcome is unknown")
                }
            } ?: SkillMemoryApprovalDecision.DENIED
            when (decision) {
                SkillMemoryApprovalDecision.REQUIRED -> ToolResult.NeedsApproval
                SkillMemoryApprovalDecision.DENIED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_denied", state = SkillMemoryDiagnosticState.DENIED)
                    ToolResult.Denied("Memory approval denied")
                }
                SkillMemoryApprovalDecision.EXPIRED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_expired", state = SkillMemoryDiagnosticState.EXPIRED)
                    ToolResult.Denied("Memory approval expired")
                }
                SkillMemoryApprovalDecision.CANCELLED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_cancelled", state = SkillMemoryDiagnosticState.CANCELLED)
                    ToolResult.Denied("Memory approval cancelled")
                }
                SkillMemoryApprovalDecision.UNKNOWN -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    ToolResult.UnknownOutcome("Memory approval outcome is unknown")
                }
                SkillMemoryApprovalDecision.APPROVED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_owner_invalid", state = SkillMemoryDiagnosticState.UNKNOWN)
                    ToolResult.UnknownOutcome("Memory approval owner returned an invalid denial state")
                }
            }
        }
    }

    /** Expire the process-local approval without persisting a replayable command. */
    override suspend fun expire(callId: String): ToolResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val pendingCall = pending[callId]
                ?: return@synchronized unknownApproval(callId, "approval_unknown", ToolResult.Invalid("No pending memory approval"))
            val decision = approvalOwner?.let { owner ->
                try {
                    owner.expire(callId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "cancelled_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    throw cancelled
                } catch (_: Throwable) {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_owner_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    return@synchronized ToolResult.UnknownOutcome("Memory approval owner outcome is unknown")
                }
            } ?: SkillMemoryApprovalDecision.EXPIRED
            when (decision) {
                SkillMemoryApprovalDecision.REQUIRED -> ToolResult.NeedsApproval
                SkillMemoryApprovalDecision.EXPIRED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_expired", state = SkillMemoryDiagnosticState.EXPIRED)
                    ToolResult.Denied("Memory approval expired")
                }
                SkillMemoryApprovalDecision.DENIED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_denied", state = SkillMemoryDiagnosticState.DENIED)
                    ToolResult.Denied("Memory approval denied")
                }
                SkillMemoryApprovalDecision.CANCELLED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_cancelled", state = SkillMemoryDiagnosticState.CANCELLED)
                    ToolResult.Denied("Memory approval cancelled")
                }
                SkillMemoryApprovalDecision.UNKNOWN -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    ToolResult.UnknownOutcome("Memory approval outcome is unknown")
                }
                SkillMemoryApprovalDecision.APPROVED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_owner_invalid", state = SkillMemoryDiagnosticState.UNKNOWN)
                    ToolResult.UnknownOutcome("Memory approval owner returned an invalid expiry state")
                }
            }
        }
    }

    /** Typed cancellation hook for a host approval owner; cancellation never executes memory. */
    suspend fun cancel(callId: String): ToolResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val pendingCall = pending[callId]
                ?: return@synchronized unknownApproval(
                    callId,
                    "approval_unknown",
                    ToolResult.UnknownOutcome("Memory approval cancellation outcome is unknown"),
                )
            val decision = approvalOwner?.let { owner ->
                try {
                    owner.cancel(callId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "cancelled_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    throw cancelled
                } catch (_: Throwable) {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_owner_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    return@synchronized ToolResult.UnknownOutcome("Memory approval owner outcome is unknown")
                }
            } ?: SkillMemoryApprovalDecision.CANCELLED
            when (decision) {
                SkillMemoryApprovalDecision.REQUIRED -> ToolResult.NeedsApproval
                SkillMemoryApprovalDecision.CANCELLED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_cancelled", state = SkillMemoryDiagnosticState.CANCELLED)
                    ToolResult.Denied("Memory approval cancelled")
                }
                SkillMemoryApprovalDecision.DENIED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_denied", state = SkillMemoryDiagnosticState.DENIED)
                    ToolResult.Denied("Memory approval denied")
                }
                SkillMemoryApprovalDecision.EXPIRED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_expired", state = SkillMemoryDiagnosticState.EXPIRED)
                    ToolResult.Denied("Memory approval expired")
                }
                SkillMemoryApprovalDecision.UNKNOWN -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
                    ToolResult.UnknownOutcome("Memory approval outcome is unknown")
                }
                SkillMemoryApprovalDecision.APPROVED -> {
                    pending.remove(callId)
                    emitTerminal(pendingCall.operation, pendingCall.call, pendingCall.discovered.handle,
                        errorCode = "approval_owner_invalid", state = SkillMemoryDiagnosticState.UNKNOWN)
                    ToolResult.UnknownOutcome("Memory approval owner returned an invalid cancellation state")
                }
            }
        }
    }

    private fun discover(): Map<String, Discovered> {
        val result = linkedMapOf<String, Discovered>()
        val collisions = mutableSetOf<String>()
        // A snapshot can bind multiple Skills.  Memory for every trusted Skill
        // envelope is discovered; each Skill keeps its own opaque namespace and
        // tool names, so one Skill can never address another Skill's memory.
        // Missing or empty identity fails closed rather than putting an
        // all-Snapshot handle enum into the model-visible schema.
        if (effectiveTrustedSkillIds.isEmpty()) return emptyMap()
        effectiveTrustedSkillIds.sorted().forEach { trustedId ->
            val candidates = runCatching {
                repository.bindings(agentId, snapshotId, trustedId, effectiveCapabilities)
            }.getOrElse { emptyList() }
            candidates.asSequence()
                .filter { binding ->
                    binding.agentId == agentId && binding.snapshotId == snapshotId &&
                        binding.installId == trustedId &&
                        binding.agentId.isNotBlank() && binding.snapshotId.isNotBlank() &&
                        binding.installId.isNotBlank() && binding.packageHash.isNotBlank() &&
                        binding.memorySpaceId.isNotBlank() && binding.enabled && binding.grantRevision > 0 &&
                        binding.capabilities.any {
                            it == SKILL_MEMORY_READ_CAPABILITY ||
                                it == SKILL_MEMORY_SEARCH_CAPABILITY ||
                                it == SKILL_MEMORY_APPEND_CAPABILITY ||
                                it == SKILL_MEMORY_REPLACE_CAPABILITY
                        }
                }
                .sortedWith(compareBy<SkillMemoryBinding> { it.installId }.thenBy { it.packageHash }.thenBy { it.memorySpaceId })
                .forEach { binding ->
                    val handle = SkillMemoryHandle.forBinding(binding.installId, binding.packageHash, binding.memorySpaceId)
                    // A collision would make two capabilities ambiguous.  Refuse both entries rather
                    // than allowing a model to select an arbitrary namespace.
                    if (handle in collisions) return@forEach
                    if (handle !in result) {
                        result[handle] = Discovered(handle, binding)
                    } else {
                        result.remove(handle)
                        collisions += handle
                    }
                }
        }
        return result
    }

    private fun isCurrent(original: SkillMemoryBinding): Boolean = runCatching {
        val current = repository.current(agentId, snapshotId, original, effectiveCapabilities) ?: return false
        current == original && current.agentId == agentId && current.snapshotId == snapshotId &&
            current.enabled && current.grantRevision > 0 && current.packageHash == original.packageHash &&
            current.memorySpaceId == original.memorySpaceId && current.installId in effectiveTrustedSkillIds
    }.getOrDefault(false)

    private data class ExecutionResult(val json: String, val count: Int)

    private fun execute(name: String, args: JsonObject, binding: SkillMemoryBinding): ExecutionResult {
        // Namespaced multi-Skill names share their operation's implementation.
        val legacy = NAMESPACED_TOOL.matchEntire(name)?.let { "memory_${it.destructured.component2()}" } ?: name
        return when (legacy) {
            MEMORY_READ -> {
                val maxBytes = args.intValue(MAX_BYTES) ?: limits.maxReadBytes
                val value = repository.read(binding, args.requiredString(PATH), maxBytes)
                ExecutionResult(readResult(value), value.bytes)
            }
            MEMORY_SEARCH -> {
                val maxResults = args.intValue(MAX_RESULTS) ?: limits.maxSearchResults
                val value = repository.search(binding, args.requiredString(QUERY), maxResults)
                ExecutionResult(searchResult(value), value.hits.size)
            }
            MEMORY_APPEND -> {
                val expected = args.optionalString(EXPECTED_VERSION)
                val value = repository.append(binding, args.requiredString(PATH), args.requiredString(TEXT), expected)
                ExecutionResult(writeResult(value), value.bytes)
            }
            MEMORY_REPLACE -> {
                val expected = args.optionalString(EXPECTED_VERSION)
                val value = repository.replace(binding, args.requiredString(PATH), args.requiredString(TEXT), expected)
                ExecutionResult(writeResult(value), value.bytes)
            }
            else -> throw SkillMemoryException(SkillMemoryFailureCode.IO_ERROR)
        }
    }

    private fun executeApproved(pendingCall: Pending): ToolResult {
        val entry = pendingCall.discovered
        try {
            val result = execute(pendingCall.call.name, pendingCall.args, entry.binding)
            if (result.json.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) {
                emitTerminal(pendingCall.operation, pendingCall.call, entry.handle, errorCode = "output_limit")
                return ToolResult.Invalid("Memory output exceeds the limit")
            }
            // Bind the completed call to its Skill binding for replay-disclosure
            // revalidation: a later duplicate discloses only while this binding
            // is still current, and never re-reads memory on revocation.
            // (Callers already hold the executor lock here.)
            completedBindings[pendingCall.call.callId] = entry.binding
            emitTerminal(
                operation = pendingCall.operation,
                call = pendingCall.call,
                memoryHandle = entry.handle,
                count = result.count,
                errorCode = "none",
                state = SkillMemoryDiagnosticState.SUCCEEDED,
            )
            return ToolResult.Value(result.json)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A cancellation can race with a repository side effect.  Do not claim failure or
            // invite an automatic retry when the durable outcome cannot be established.
            emitTerminal(pendingCall.operation, pendingCall.call, entry.handle,
                errorCode = "cancelled_unknown", state = SkillMemoryDiagnosticState.UNKNOWN)
            throw cancelled
        } catch (error: Throwable) {
            emitTerminal(pendingCall.operation, pendingCall.call, entry.handle, errorCode = diagnosticErrorCode(error))
            return mapFailure(error)
        }
    }

    private fun hasCapability(binding: SkillMemoryBinding, name: String): Boolean = when (name) {
        MEMORY_READ -> SKILL_MEMORY_READ_CAPABILITY in binding.capabilities
        MEMORY_SEARCH -> SKILL_MEMORY_SEARCH_CAPABILITY in binding.capabilities
        MEMORY_APPEND -> SKILL_MEMORY_APPEND_CAPABILITY in binding.capabilities
        MEMORY_REPLACE -> SKILL_MEMORY_REPLACE_CAPABILITY in binding.capabilities
        else -> false
    }

    /**
     * Runtime-assigned Skill namespace embedded in multi-Skill tool names.
     * Opaque digest of the code identity — never the raw install id — so the
     * model addresses a namespace it cannot mint or confuse.
     */
    private data class ResolvedTool(
        val operation: SkillMemoryDiagnosticOperation,
        /** Legacy operation name used for capability mapping. */
        val legacyName: String,
        /** Skill installs addressable through this tool name. */
        val installIds: Set<String>,
        /** Their handles. */
        val handles: Set<String>,
    )

    private fun namespaceFor(binding: SkillMemoryBinding): String =
        memoryNamespace(binding.installId, binding.packageHash)

    private fun resolveTool(name: String): ResolvedTool? {
        operationFor(name)?.let { operation ->
            // Legacy shared name, only exposed for single-Skill runs.
            return ResolvedTool(operation, name, effectiveTrustedSkillIds, discovered.keys)
        }
        val match = NAMESPACED_TOOL.matchEntire(name) ?: return null
        val (namespace, operation) = match.destructured
        val legacyName = "memory_$operation"
        val resolvedOperation = operationFor(legacyName) ?: return null
        val installIds = discovered.values
            .filter { namespaceFor(it.binding) == namespace }
            .map { it.binding.installId }
            .toSet()
        if (installIds.isEmpty()) return null
        val handles = discovered.filter { it.value.binding.installId in installIds }.keys
        return ResolvedTool(resolvedOperation, legacyName, installIds, handles)
    }

    /**
     * Disclosure check for RunTools-level cached memory results.  Re-checks
     * the completed call's binding against live facts (grant, package,
     * enablement) without re-reading memory and without disclosing when the
     * Skill was revoked or replaced.
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val binding = completedBindings[call.callId] ?: return@synchronized false
            isCurrent(binding)
        }
    }

    private fun operationFor(name: String): SkillMemoryDiagnosticOperation? = when (name) {
        MEMORY_READ -> SkillMemoryDiagnosticOperation.READ
        MEMORY_SEARCH -> SkillMemoryDiagnosticOperation.SEARCH
        MEMORY_APPEND -> SkillMemoryDiagnosticOperation.APPEND
        MEMORY_REPLACE -> SkillMemoryDiagnosticOperation.REPLACE
        else -> null
    }

    private fun SkillMemoryDiagnosticOperation.toolName(): String = when (this) {
        SkillMemoryDiagnosticOperation.READ -> MEMORY_READ
        SkillMemoryDiagnosticOperation.SEARCH -> MEMORY_SEARCH
        SkillMemoryDiagnosticOperation.APPEND -> MEMORY_APPEND
        SkillMemoryDiagnosticOperation.REPLACE -> MEMORY_REPLACE
        SkillMemoryDiagnosticOperation.UNKNOWN -> "memory_unknown"
    }

    private fun emitTerminal(
        operation: SkillMemoryDiagnosticOperation,
        call: ToolCall,
        memoryHandle: String?,
        count: Int = 0,
        errorCode: String = "unknown",
        state: SkillMemoryDiagnosticState = SkillMemoryDiagnosticState.FAILED,
    ) {
        if (!state.terminal) {
            emitDiagnostic(operation, SkillMemoryDiagnosticState.FAILED, call, memoryHandle, count, "invalid_terminal_state")
            return
        }
        if (!terminalCallIds.add(call.callId)) return
        emitDiagnostic(operation, state, call, memoryHandle, count, errorCode)
    }

    private fun unknownApproval(callId: String, errorCode: String, result: ToolResult): ToolResult {
        val operation = callOperations[callId] ?: SkillMemoryDiagnosticOperation.UNKNOWN
        emitTerminal(
            operation = operation,
            call = ToolCall(callId, operation.toolName(), "{}"),
            memoryHandle = null,
            errorCode = errorCode,
            state = SkillMemoryDiagnosticState.UNKNOWN,
        )
        return result
    }

    private fun emitDiagnostic(
        operation: SkillMemoryDiagnosticOperation,
        state: SkillMemoryDiagnosticState,
        call: ToolCall,
        memoryHandle: String?,
        count: Int = 0,
        errorCode: String = "none",
    ) {
        val references = if (memoryHandle != null) {
            runCatching {
                diagnosticRefProvider.references(
                    SkillMemoryDiagnosticRefRequest(operation, memoryHandle, call.callId),
                )
            }.getOrDefault(SkillMemoryDiagnosticReferences.EMPTY)
        } else {
            SkillMemoryDiagnosticReferences.EMPTY
        }
        runCatching {
            diagnosticSink.record(
                SkillMemoryDiagnosticEvent(
                    operation = operation,
                    state = state,
                    count = count.coerceAtLeast(0),
                    errorCode = diagnosticErrorCode(errorCode),
                    references = references,
                ),
            )
        }
    }

    private fun diagnosticErrorCode(error: Throwable): String = when (error) {
        is SkillMemoryException -> error.code.name.lowercase()
        else -> "memory_operation_failed"
    }

    private fun diagnosticErrorCode(errorCode: String): String =
        errorCode.trim().lowercase().takeIf { it.matches(DIAGNOSTIC_ERROR_CODE) } ?: "memory_operation_failed"

    private fun validateArguments(name: String, args: JsonObject): String? {
        // Namespaced multi-Skill names share their operation's argument contract.
        // Namespace membership itself is enforced separately as a Denied
        // decision (not a validation error), so cross-Skill confusion is typed.
        val legacy = NAMESPACED_TOOL.matchEntire(name)?.let { "memory_${it.destructured.component2()}" } ?: name
        val allowed = when (legacy) {
            MEMORY_READ -> setOf(HANDLE, PATH, MAX_BYTES)
            MEMORY_SEARCH -> setOf(HANDLE, QUERY, MAX_RESULTS)
            MEMORY_APPEND, MEMORY_REPLACE -> setOf(HANDLE, PATH, TEXT, EXPECTED_VERSION)
            else -> return "Memory tool is unknown"
        }
        val required = when (legacy) {
            MEMORY_READ -> setOf(HANDLE, PATH)
            MEMORY_SEARCH -> setOf(HANDLE, QUERY)
            MEMORY_APPEND, MEMORY_REPLACE -> setOf(HANDLE, PATH, TEXT)
            else -> return "Memory tool is unknown"
        }
        if (args.keys.any { it !in allowed }) return "Memory parameter is unsupported"
        if (required.any { it !in args }) return "Memory parameter is missing"
        val handle = args[HANDLE] as? JsonPrimitive
        if (handle?.isString != true || handle.content.isBlank() || handle.content !in discovered) {
            return "Memory handle is unavailable"
        }
        args[PATH]?.let {
            val value = it as? JsonPrimitive ?: return "Memory path must be text"
            if (!value.isString || value.content.isBlank()) return "Memory path must be text"
            try {
                SkillMemoryPathPolicy.validate(value.content, limits.maxPathBytes)
            } catch (_: SkillMemoryException) {
                // Keep the failure path-free: model-provided path text is untrusted and must not
                // be reflected into diagnostics, errors, or the approval surface.
                return "Memory path is outside the allowed memory files"
            }
        }
        args[TEXT]?.let {
            val value = it as? JsonPrimitive ?: return "Memory text must be text"
            if (!value.isString) return "Memory text must be text"
            if (value.content.toByteArray(Charsets.UTF_8).size.toLong() > limits.maxFileBytes) return "Memory text exceeds the limit"
        }
        args[QUERY]?.let {
            val value = it as? JsonPrimitive ?: return "Memory query must be text"
            if (!value.isString || value.content.isBlank()) return "Memory query must be text"
            if (value.content.any(Char::isISOControl)) return "Memory query is invalid"
            if (value.content.toByteArray(Charsets.UTF_8).size > limits.maxSearchQueryBytes) {
                return "Memory query exceeds the limit"
            }
        }
        args[MAX_RESULTS]?.let {
            val value = it as? JsonPrimitive ?: return "Memory maxResults must be an integer"
            val max = value.takeUnless(JsonPrimitive::isString)?.intOrNull
                ?: return "Memory maxResults must be an integer"
            if (max !in 1..limits.maxSearchResults) return "Memory maxResults exceeds the limit"
        }
        args[MAX_BYTES]?.let {
            val value = it as? JsonPrimitive ?: return "Memory maxBytes must be an integer"
            val max = value.takeUnless(JsonPrimitive::isString)?.intOrNull
                ?: return "Memory maxBytes must be an integer"
            if (max !in 1..limits.maxReadBytes) return "Memory maxBytes exceeds the limit"
        }
        args[EXPECTED_VERSION]?.let {
            val value = it as? JsonPrimitive ?: return "Memory expectedVersion must be text"
            if (!value.isString || value.content.length > VERSION_BYTES) return "Memory expectedVersion is invalid"
        }
        return null
    }

    private fun readResult(value: SkillMemoryReadResult): String = output(buildJsonObject {
        put(PATH, value.path)
        put("bytes", value.bytes)
        put("version", value.version)
        put("text", value.text)
    })

    private fun writeResult(value: SkillMemoryWriteResult): String = output(buildJsonObject {
        put(PATH, value.path)
        put("bytes", value.bytes)
        put("version", value.version)
        put("created", value.created)
    })

    private fun searchResult(value: SkillMemorySearchResult): String = output(buildJsonObject {
        put("hits", buildJsonArray {
            value.hits.forEach { hit ->
                add(buildJsonObject {
                    put(PATH, hit.path)
                    put("line", hit.line)
                    put("snippet", hit.snippet)
                })
            }
        })
        put("truncated", value.truncated)
    })

    private fun output(value: JsonObject): String {
        val result = value.toString()
        if (result.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) {
            throw SkillMemoryException(SkillMemoryFailureCode.OUTPUT_LIMIT)
        }
        return result
    }

    private fun mapFailure(error: Throwable): ToolResult = when (error) {
        is SkillMemoryException -> when (error.code) {
            SkillMemoryFailureCode.CONFLICT -> ToolResult.Invalid("CONFLICT: memory version changed")
            SkillMemoryFailureCode.SYMLINK_FORBIDDEN -> ToolResult.Invalid("SYMLINK_FORBIDDEN")
            SkillMemoryFailureCode.ROOT_OPERATION_FORBIDDEN -> ToolResult.Invalid("ROOT_OPERATION_FORBIDDEN")
            SkillMemoryFailureCode.INVALID_PATH -> ToolResult.Invalid("PATH_OUT_OF_SCOPE")
            SkillMemoryFailureCode.INVALID_CONTENT -> ToolResult.Invalid("Memory content must be strict UTF-8")
            SkillMemoryFailureCode.INVALID_QUERY -> ToolResult.Invalid("QUERY_INVALID")
            SkillMemoryFailureCode.NOT_FOUND -> ToolResult.Invalid("Memory file was not found")
            SkillMemoryFailureCode.FILE_TOO_LARGE -> ToolResult.Invalid("FILE_TOO_LARGE")
            SkillMemoryFailureCode.QUOTA_EXCEEDED -> ToolResult.Invalid("QUOTA_EXCEEDED")
            SkillMemoryFailureCode.ENTRY_LIMIT -> ToolResult.Invalid("ENTRY_LIMIT")
            SkillMemoryFailureCode.OUTPUT_LIMIT -> ToolResult.Invalid("OUTPUT_LIMIT")
            SkillMemoryFailureCode.IO_ERROR -> ToolResult.Invalid("Memory operation failed")
        }
        else -> ToolResult.Invalid("Memory operation failed")
    }

    private fun parseObject(raw: String): JsonObject? = runCatching {
        (Json.parseToJsonElement(raw) as? JsonObject)
    }.getOrNull()

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.requiredString(key: String): String =
        stringValue(key)?.takeIf { it.isNotBlank() } ?: throw SkillMemoryException(SkillMemoryFailureCode.INVALID_CONTENT)

    private fun JsonObject.optionalString(key: String): String? =
        if (key !in this) null else stringValue(key) ?: throw SkillMemoryException(SkillMemoryFailureCode.INVALID_CONTENT)

    private fun JsonObject.intValue(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    companion object {
        const val MEMORY_READ: String = "memory_read"
        const val MEMORY_SEARCH: String = "memory_search"
        const val MEMORY_APPEND: String = "memory_append"
        const val MEMORY_REPLACE: String = "memory_replace"

        private const val HANDLE = "memoryHandle"
        private const val PATH = "path"
        private const val TEXT = "text"
        private const val QUERY = "query"
        private const val MAX_BYTES = "maxBytes"
        private const val MAX_RESULTS = "maxResults"
        private const val EXPECTED_VERSION = "expectedVersion"
        private const val MAX_ARGUMENT_BYTES = 384 * 1024
        private const val VERSION_BYTES = 128
        private val DIAGNOSTIC_ERROR_CODE = Regex("[a-z0-9][a-z0-9_.-]{0,63}")

        private fun buildSpecs(
            entries: List<Discovered>,
            limits: SkillMemoryLimits,
            trustedIds: Set<String>,
        ): List<ToolSpec> {
            if (trustedIds.size > 1) return buildNamespacedSpecs(entries, limits)
            return buildSharedSpecs(entries, limits)
        }

        /** Legacy shared names, only for single-Skill runs (no union possible). */
        private fun buildSharedSpecs(entries: List<Discovered>, limits: SkillMemoryLimits): List<ToolSpec> {
            val readHandles = entries.filter { SKILL_MEMORY_READ_CAPABILITY in it.binding.capabilities }.map { it.handle }
            val searchHandles = entries.filter { SKILL_MEMORY_SEARCH_CAPABILITY in it.binding.capabilities }.map { it.handle }
            val appendHandles = entries.filter { SKILL_MEMORY_APPEND_CAPABILITY in it.binding.capabilities }.map { it.handle }
            val replaceHandles = entries.filter { SKILL_MEMORY_REPLACE_CAPABILITY in it.binding.capabilities }.map { it.handle }
            return buildList {
                if (readHandles.isNotEmpty()) {
                    add(ToolSpec(MEMORY_READ, "读取当前 Skill 的严格 UTF-8 MEMORY.md 或指定日期 journal。每次调用需要确认。", schema(HANDLE, readHandles, required = listOf(HANDLE, PATH), path = true, maxBytes = true, limits = limits), SKILL_MEMORY_READ_CAPABILITY, true))
                }
                if (searchHandles.isNotEmpty()) {
                    add(ToolSpec(MEMORY_SEARCH, "在当前 Skill 的记忆文件中执行有界、大小写敏感的 literal text 查询。结果只包含相对路径、行号和安全片段；每次调用需要确认。", schema(HANDLE, searchHandles, required = listOf(HANDLE, QUERY), query = true, maxResults = true, limits = limits), SKILL_MEMORY_SEARCH_CAPABILITY, true))
                }
                if (appendHandles.isNotEmpty()) {
                    add(ToolSpec(MEMORY_APPEND, "原子追加到当前 Skill 的 MEMORY.md 或指定日期 journal；使用返回的 version 做乐观并发控制；每次调用需要确认。", schema(HANDLE, appendHandles, required = listOf(HANDLE, PATH, TEXT), path = true, text = true, expected = true, limits = limits), SKILL_MEMORY_APPEND_CAPABILITY, true))
                }
                if (replaceHandles.isNotEmpty()) {
                    add(ToolSpec(MEMORY_REPLACE, "原子创建或整文件替换当前 Skill 的 MEMORY.md 或指定日期 journal；使用返回的 version 做乐观并发控制；每次调用需要确认。", schema(HANDLE, replaceHandles, required = listOf(HANDLE, PATH, TEXT), path = true, text = true, expected = true, limits = limits), SKILL_MEMORY_REPLACE_CAPABILITY, true))
                }
            }
        }

        /**
         * Per-Skill namespaces for multi-Skill runs.  Each Skill's memory is
         * addressed only through `memory_<opaque>.<operation>` tools whose
         * handle enum contains that Skill's handles alone.  A namespace that
         * would cover two installs is dropped entirely (fail closed).
         */
        private fun buildNamespacedSpecs(entries: List<Discovered>, limits: SkillMemoryLimits): List<ToolSpec> {
            val byNamespace = entries.groupBy { memoryNamespace(it.binding.installId, it.binding.packageHash) }
            return buildList {
                byNamespace.entries.sortedBy { it.key }.forEach { (namespace, group) ->
                    if (group.map { it.binding.installId }.toSet().size != 1) return@forEach
                    val readHandles = group.filter { SKILL_MEMORY_READ_CAPABILITY in it.binding.capabilities }.map { it.handle }
                    val searchHandles = group.filter { SKILL_MEMORY_SEARCH_CAPABILITY in it.binding.capabilities }.map { it.handle }
                    val appendHandles = group.filter { SKILL_MEMORY_APPEND_CAPABILITY in it.binding.capabilities }.map { it.handle }
                    val replaceHandles = group.filter { SKILL_MEMORY_REPLACE_CAPABILITY in it.binding.capabilities }.map { it.handle }
                    if (readHandles.isNotEmpty()) {
                        add(ToolSpec("$MEMORY_NAMESPACE_PREFIX$namespace.read", "读取该 Skill 专属记忆（严格 UTF-8 MEMORY.md 或指定日期 journal）。memoryHandle 只能是本工具列出的句柄；每次调用需要确认。", schema(HANDLE, readHandles, required = listOf(HANDLE, PATH), path = true, maxBytes = true, limits = limits), SKILL_MEMORY_READ_CAPABILITY, true))
                    }
                    if (searchHandles.isNotEmpty()) {
                        add(ToolSpec("$MEMORY_NAMESPACE_PREFIX$namespace.search", "在该 Skill 专属记忆文件中执行有界 literal text 查询。结果只包含相对路径、行号和安全片段；每次调用需要确认。", schema(HANDLE, searchHandles, required = listOf(HANDLE, QUERY), query = true, maxResults = true, limits = limits), SKILL_MEMORY_SEARCH_CAPABILITY, true))
                    }
                    if (appendHandles.isNotEmpty()) {
                        add(ToolSpec("$MEMORY_NAMESPACE_PREFIX$namespace.append", "原子追加到该 Skill 专属 MEMORY.md 或指定日期 journal；使用返回的 version 做乐观并发控制；每次调用需要确认。", schema(HANDLE, appendHandles, required = listOf(HANDLE, PATH, TEXT), path = true, text = true, expected = true, limits = limits), SKILL_MEMORY_APPEND_CAPABILITY, true))
                    }
                    if (replaceHandles.isNotEmpty()) {
                        add(ToolSpec("$MEMORY_NAMESPACE_PREFIX$namespace.replace", "原子创建或整文件替换该 Skill 专属 MEMORY.md 或指定日期 journal；使用返回的 version 做乐观并发控制；每次调用需要确认。", schema(HANDLE, replaceHandles, required = listOf(HANDLE, PATH, TEXT), path = true, text = true, expected = true, limits = limits), SKILL_MEMORY_REPLACE_CAPABILITY, true))
                    }
                }
            }
        }

        /** Opaque runtime-assigned Skill namespace; the model can use it but never mint it. */
        fun memoryNamespace(installId: String, packageHash: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$installId\u0000$packageHash".toByteArray(Charsets.UTF_8))
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }

        private const val MEMORY_NAMESPACE_PREFIX = "memory_"
        private val NAMESPACED_TOOL = Regex("memory_([0-9a-f]{12})\\.(read|search|append|replace)")

        private fun schema(
            handle: String,
            handles: List<String>,
            required: List<String>,
            path: Boolean = false,
            text: Boolean = false,
            maxBytes: Boolean = false,
            expected: Boolean = false,
            query: Boolean = false,
            maxResults: Boolean = false,
            limits: SkillMemoryLimits,
        ): String = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            put("properties", buildJsonObject {
                put(handle, buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 64)
                    put("enum", buildJsonArray { handles.forEach { add(JsonPrimitive(it)) } })
                })
                if (path) put(PATH, buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", limits.maxPathBytes) })
                if (text) put(TEXT, buildJsonObject { put("type", "string"); put("maxLength", limits.maxFileBytes) })
                if (maxBytes) put(MAX_BYTES, buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", limits.maxReadBytes) })
                if (query) put(QUERY, buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", limits.maxSearchQueryBytes) })
                if (maxResults) put(MAX_RESULTS, buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", limits.maxSearchResults) })
                if (expected) put(EXPECTED_VERSION, buildJsonObject { put("type", "string"); put("maxLength", VERSION_BYTES) })
            })
        }.toString()
    }

}
