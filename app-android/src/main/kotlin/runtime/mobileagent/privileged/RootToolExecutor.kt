// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.skills.ResultDisclosure
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.tooling.TypedToolExecutor

/**
 * Runtime-side Root tool seam.  Every operation is keyed by the runtime
 * invocation id and revalidates against the live DB/service facts; this
 * executor never creates a grant, Run, invocation or approval, and never
 * searches approvals by matching arguments.
 */
interface RootToolBackend {
    /**
     * Live exposure frozen for this run: only tools that are actually
     * implemented and currently backed by an unrevoked grant.  Returning a
     * tool here is not an approval; [invoke] must revalidate it.
     */
    fun exposedTools(): List<RootToolExposure>

    /**
     * Dispatch exactly [invocation].  A pending human decision is
     * [ToolResult.NeedsApproval]; a definite refusal is Denied/Invalid/Failure;
     * anything whose external effect cannot be proven must be UnknownOutcome.
     */
    suspend fun invoke(invocation: RootToolInvocation): ToolResult

    /** Settle the pending approval with this exact runtime id; the DB owns the original model id. */
    suspend fun approve(runtimeInvocationId: String): ToolResult

    /** Explicitly deny a pending approval for this exact runtime id. */
    suspend fun reject(runtimeInvocationId: String): ToolResult

    /** Expire a pending approval for this exact runtime id. */
    suspend fun expire(runtimeInvocationId: String): ToolResult

    /**
     * Revalidate disclosure of an already-settled result.  This must not
     * dispatch the tool again and must not widen to a same-argument search.
     */
    suspend fun authorizeDisclosure(invocation: RootToolInvocation, disclosure: ResultDisclosure): Boolean

    /** Non-consuming preflight check before releasing a multi-child batch envelope. */
    suspend fun precheckDisclosure(invocation: RootToolInvocation): Boolean = true

    /** Explicitly withhold a deferred result that was never disclosed to the model. */
    suspend fun withholdDisclosure(invocation: RootToolInvocation) {}

    /** Cancel one pending/in-flight runtime invocation; true only when confirmed. */
    suspend fun cancel(runtimeInvocationId: String): Boolean
}

/**
 * Provider-neutral projection of a granted Root tool binding.  It intentionally
 * carries no absolute device path: the model-visible workspace id plus the
 * relative path scope are enough for the runtime, and errors never echo either
 * the grant locator or a device path.
 */
data class RootToolExposure(
    val toolName: String,
    val capability: CapabilityId,
    val workspaceId: String,
    val grantId: String,
    val pathScope: String? = null,
    val writableWorkspace: Boolean = true,
) {
    init {
        require(toolName.matches(TOOL_NAME))
        require(workspaceId.matches(SAFE_ID) && grantId.matches(SAFE_ID))
    }

    private companion object {
        val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

/**
 * Exact invocation facts handed to [RootToolBackend].
 *
 * The legacy [ToolExecutor] boundary only carries [runtimeInvocationId]
 * (`RunTools.ownerCall()` already replaced the model call id); the typed
 * boundary additionally retains the model correlation id.  `modelCallId` is
 * never an execution, approval or replay key, and is never substituted for the
 * runtime id.  `toString()` stays path-free so it can be logged.
 */
class RootToolInvocation internal constructor(
    val runtimeInvocationId: String,
    val modelCallId: String?,
    val snapshotId: String?,
    val agentId: String?,
    val toolName: String,
    val capability: CapabilityId,
    val workspaceId: String,
    val grantId: String,
    val pathScope: String?,
    val request: RootToolRequest,
) {
    init {
        require(runtimeInvocationId.isNotBlank() && runtimeInvocationId.length <= 128)
        require(modelCallId == null || (modelCallId.isNotBlank() && modelCallId.length <= 512))
        require(request.toolName == toolName && request.capability == capability)
        require(request.workspaceId == workspaceId)
        require(grantId.isNotBlank())
    }

    /** Canonical typed JSON omitted text/patch content and device paths. */
    val normalizedArgumentsJson: String get() = request.canonicalJson
    val normalizedArgumentsSha256: String get() = request.canonicalSha256

    /** Bounded payload for write/patch only; never printed by this object. */
    val payload: RootToolPayload get() = request.payload

    override fun toString(): String = "RootToolInvocation(tool=$toolName)"
}

/**
 * Root-only [ToolExecutor].
 *
 * The executor freezes the model-facing specs and the backend exposure at run
 * start, normalizes every model JSON payload into [RootTypedArguments], and
 * keeps approval/replay/cancel state per exact runtime invocation id.  All
 * authorization and dispatch is delegated to [RootToolBackend]; this class
 * does not touch the DB, the Root service or a grant store.
 *
 * Construction fails closed: a frozen spec that is not implemented, whose
 * schema/capability/side-effect differs from the Root catalog, or that has no
 * live grant is not exposed.  An empty surface is valid (no Root tools in this
 * run) and must not be replaced by a shell fallback.
 */
class RootToolExecutor(
    frozenSpecs: List<ToolSpec>,
    private val backend: RootToolBackend,
) : ToolExecutor, TypedToolExecutor {
    init {
        require(frozenSpecs.map { it.name }.distinct().size == frozenSpecs.size) {
            "Duplicate Root tool specification"
        }
    }

    private val exposuresByTool: Map<String, List<RootToolExposure>> = backend.exposedTools()
        .filter { it.toolName in RootToolArguments.supportedToolNames }
        .filter { RootApprovalTools.capability(it.toolName) == it.capability }
        .filter { !RootApprovalTools.requiresWritableWorkspace(it.capability) || it.writableWorkspace }
        .groupBy { it.toolName }

    private val acceptedSpecs: List<ToolSpec> = frozenSpecs.filter { spec ->
        val catalog = RootToolArguments.spec(spec.name)
        catalog != null &&
            spec.capability == catalog.capability &&
            spec.inputSchema == catalog.inputSchema &&
            spec.sideEffect == catalog.sideEffect &&
            exposuresByTool.containsKey(spec.name)
    }

    /** Frozen model-facing schema list; never copies a provider or grant into the schema. */
    val toolingSpecs: List<ToolSpec> = Collections.unmodifiableList(acceptedSpecs.toList())

    /** Only tools that are implemented, granted and valid for this frozen surface. */
    val exposedToolNames: Set<String> = Collections.unmodifiableSet(toolingSpecs.mapTo(linkedSetOf()) { it.name })

    /** Supported names that were intentionally withheld (diagnostics only, no paths). */
    val rejectedToolNames: Set<String> = Collections.unmodifiableSet(
        frozenSpecs.map { it.name }.filter { it !in exposedToolNames }.toSet(),
    )

    /** Legacy compatibility adapter for [ToolExecutor] consumers. */
    override val specs: List<LegacyToolSpec> = Collections.unmodifiableList(toolingSpecs.map { it.toLegacySpec() })

    internal var afterCancellationSettlement: suspend () -> Unit = {}
    internal var beforeBackendEntry: suspend () -> Unit = {}

    private val lock = Any()
    private var closed = false
    private val closeCompletion = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val byRuntimeId = linkedMapOf<String, BoundCall>()
    private val runtimeByModelCall = linkedMapOf<String, MutableSet<String>>()

    // ---- Legacy ToolExecutor boundary -------------------------------------------------

    /**
     * `call.callId` is the runtime invocation id by RunTools contract.  This
     * boundary has no model id, so `modelCallId` stays null; a broker must read
     * the original model id from the DB by runtime id.
     */
    override suspend fun invoke(call: ToolCall): ToolResult {
        if (call.callId.isBlank()) return INVALID_CALL
        if (call.name !in exposedToolNames) return unavailable()
        val request = RootToolArguments.normalize(call.name, call.argumentsJson) ?: return invalidArguments()
        val exposure = exposureFor(request) ?: return unavailable()
        return dispatch(
            RootToolInvocation(
                runtimeInvocationId = call.callId,
                modelCallId = null,
                snapshotId = null,
                agentId = null,
                toolName = request.toolName,
                capability = request.capability,
                workspaceId = request.workspaceId,
                grantId = exposure.grantId,
                pathScope = exposure.pathScope,
                request = request,
            ),
        )
    }

    override suspend fun approve(callId: String): ToolResult = settlePending(callId) { runtimeId ->
        backend.approve(runtimeId)
    }

    override suspend fun reject(callId: String): ToolResult = settlePending(callId) { runtimeId ->
        backend.reject(runtimeId)
    }

    override suspend fun expire(callId: String): ToolResult = settlePending(callId) { runtimeId ->
        backend.expire(runtimeId)
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean = authorize(call, ResultDisclosure.REPLAY)

    override suspend fun authorizeDeferredResult(call: ToolCall): Boolean =
        authorize(call, ResultDisclosure.DEFERRED)

    override suspend fun precheckDeferredResult(call: ToolCall): Boolean =
        authorize(call, ResultDisclosure.DEFERRED, precheckOnly = true)

    override suspend fun withholdDeferredResult(call: ToolCall) {
        resolve(call.callId)?.let { bound -> runCatching { backend.withholdDisclosure(bound.invocation) } }
    }

    override suspend fun close() {
        val pending = synchronized(lock) {
            if (closed) null else {
                closed = true
                byRuntimeId.values.filter { it.inFlight || it.approvalPending || it.cancelRequired }.toList()
            }
        }
        withContext(NonCancellable) {
            if (pending == null) {
                closeCompletion.await()
                return@withContext
            }
            try {
                coroutineScope {
                    pending.map { bound -> async(Dispatchers.IO) { cancelBound(bound, awaitExisting = true) } }.awaitAll()
                }
            } finally {
                synchronized(lock) {
                    byRuntimeId.values.forEach {
                        it.cancelled = true
                        it.approvalPending = false
                        it.result = ToolResult.UnknownOutcome(CANCELLED_REASON)
                    }
                    byRuntimeId.clear()
                    runtimeByModelCall.clear()
                }
                closeCompletion.complete(Unit)
            }
        }
    }

    // ---- Typed boundary --------------------------------------------------------------

    /**
     * Shared skills-api execution entry.  Unlike the legacy bridge, this
     * preserves the model call id as correlation while the runtime request id
     * remains the sole execution/approval key.
     */
    override suspend fun invoke(invocation: ToolInvocation): ToolExecution {
        if (invocation.name !in exposedToolNames) return ToolExecution.Failed(unavailableError())
        val request = RootToolArguments.normalize(invocation.name, invocation.argumentsJson)
            ?: return ToolExecution.Failed(invalidRequestError())
        val exposure = exposureFor(request) ?: return ToolExecution.Failed(unavailableError())
        val root = RootToolInvocation(
            runtimeInvocationId = invocation.requestId,
            modelCallId = invocation.callId,
            snapshotId = invocation.snapshotId,
            agentId = invocation.agentId,
            toolName = request.toolName,
            capability = request.capability,
            workspaceId = request.workspaceId,
            grantId = exposure.grantId,
            pathScope = exposure.pathScope,
            request = request,
        )
        return dispatch(root).toToolExecution()
    }

    /**
     * Cancel exactly one runtime invocation.  `requestId` may also be a unique
     * model correlation id for compatibility, but it is resolved to the exact
     * runtime id before the backend sees it.
     */
    override suspend fun cancel(requestId: String): Boolean =
        resolve(requestId)?.let { cancelBound(it) } ?: false

    private suspend fun cancelBound(bound: BoundCall, awaitExisting: Boolean = false): Boolean = withContext(NonCancellable) {
        val claimed = synchronized(lock) {
            bound.cancelled = true
            bound.cancelRequired = true
            bound.approvalPending = false
            bound.result = ToolResult.UnknownOutcome(CANCELLED_REASON)
            if (bound.cancelSent) false else { bound.cancelSent = true; true }
        }
        if (!claimed) return@withContext if (awaitExisting) bound.cancelConfirmation.await() else false
        val confirmed = try {
            withContext(Dispatchers.IO) {
                backend.cancel(bound.invocation.runtimeInvocationId)
            }
        } catch (_: Exception) {
            // A failed stop remains unknown and may never authorize disclosure or retry.
            false
        }
        bound.cancelConfirmation.complete(confirmed)
        confirmed
    }

    private fun settleCancellation(bound: BoundCall) = synchronized(lock) {
        bound.cancelRequired = true
        bound.cancelled = true
        settle(bound, ToolResult.UnknownOutcome(CANCELLED_REASON))
    }

    // ---- Internals -------------------------------------------------------------------

    private fun exposureFor(request: RootToolRequest): RootToolExposure? = exposuresByTool[request.toolName]
        ?.filter { it.workspaceId == request.workspaceId && it.capability == request.capability }
        ?.singleOrNull()

    private suspend fun dispatch(invocation: RootToolInvocation): ToolResult {
        val binding = synchronized(lock) {
            if (closed) return unavailable()
            val existing = byRuntimeId[invocation.runtimeInvocationId]
            if (existing != null) {
                if (!existing.matches(invocation)) return INVALID_REPLAY
                if (existing.cancelled) return ToolResult.UnknownOutcome(CANCELLED_REASON)
                existing.result?.let { return it }
                if (existing.approvalPending) return ToolResult.NeedsApproval
                return ToolResult.UnknownOutcome(IN_FLIGHT_REASON)
            }
            BoundCall(invocation).also {
                it.inFlight = true
                byRuntimeId[invocation.runtimeInvocationId] = it
                invocation.modelCallId?.let { modelId ->
                    runtimeByModelCall.getOrPut(modelId) { linkedSetOf() }.add(invocation.runtimeInvocationId)
                }
            }
        }
        val result = try {
            beforeBackendEntry()
            backend.invoke(invocation)
        } catch (signal: CancellationException) {
            settleCancellation(binding)
            withContext(NonCancellable) {
                afterCancellationSettlement()
                cancelBound(binding)
            }
            throw signal
        } catch (_: Exception) {
            ToolResult.UnknownOutcome(UNKNOWN_REASON)
        }
        return settle(binding, result)
    }

    private suspend fun settlePending(
        callId: String,
        operation: suspend (runtimeInvocationId: String) -> ToolResult,
    ): ToolResult {
        val bound = resolve(callId) ?: return INVALID_APPROVAL
        synchronized(lock) {
            if (closed || bound.result != null || !bound.approvalPending || bound.inFlight || bound.cancelled) {
                return INVALID_APPROVAL
            }
            bound.approvalPending = false
            bound.inFlight = true
        }
        val result = try {
            beforeBackendEntry()
            operation(bound.invocation.runtimeInvocationId)
        } catch (signal: CancellationException) {
            settleCancellation(bound)
            withContext(NonCancellable) {
                afterCancellationSettlement()
                cancelBound(bound)
            }
            throw signal
        } catch (_: Exception) {
            ToolResult.UnknownOutcome(UNKNOWN_REASON)
        }
        return settle(bound, result)
    }

    private suspend fun authorize(
        call: ToolCall,
        disclosure: ResultDisclosure,
        precheckOnly: Boolean = false,
    ): Boolean {
        val bound = resolve(call.callId) ?: return false
        val request = RootToolArguments.normalize(call.name, call.argumentsJson) ?: return false
        val settled = synchronized(lock) {
            if (closed || bound.cancelled) return false
            if (bound.invocation.toolName != call.name) return false
            if (bound.invocation.request.canonicalSha256 != request.canonicalSha256) return false
            if (bound.inFlight || bound.approvalPending) return false
            // Root one-shot file mutations have no deferred continuation; only a
            // read-only result or backgrounded shell_exec may ask the backend.
            if (disclosure == ResultDisclosure.DEFERRED &&
                RootApprovalTools.requiresWritableWorkspace(bound.invocation.capability) &&
                bound.invocation.capability != CapabilityId(CapabilityId.SHELL_EXECUTE)
            ) return false
            val current = bound.result ?: return false
            if (current is ToolResult.UnknownOutcome) return false
            current
        }
        val allowed = try {
            if (precheckOnly) backend.precheckDisclosure(bound.invocation)
            else backend.authorizeDisclosure(bound.invocation, disclosure)
        } catch (signal: CancellationException) {
            throw signal
        } catch (_: Exception) {
            false
        }
        return allowed && synchronized(lock) { !closed && !bound.cancelled && bound.result === settled }
    }

    private fun settle(bound: BoundCall, result: ToolResult): ToolResult = synchronized(lock) {
        bound.inFlight = false
        val visible = if (closed || bound.cancelled) ToolResult.UnknownOutcome(CANCELLED_REASON) else result
        bound.approvalPending = visible == ToolResult.NeedsApproval
        if (visible != ToolResult.NeedsApproval) bound.result = visible
        visible
    }

    private fun resolve(id: String): BoundCall? = synchronized(lock) {
        byRuntimeId[id] ?: runtimeByModelCall[id]?.let { runtimeIds ->
            val candidates = runtimeIds.mapNotNull(byRuntimeId::get)
            candidates.singleOrNull { it.approvalPending || it.inFlight } ?: candidates.singleOrNull()
        }
    }

    private fun ToolResult.toToolExecution(): ToolExecution = when (this) {
        is ToolResult.Value -> ToolExecution.Value(json)
        is ToolResult.Denied -> ToolExecution.Failed(ToolError(reason.asErrorCode(ToolErrorCode.CAPABILITY_DENIED)))
        is ToolResult.Invalid -> ToolExecution.Failed(ToolError(reason.asErrorCode(ToolErrorCode.INVALID_REQUEST)))
        is ToolResult.Failure -> ToolExecution.Failed(error)
        is ToolResult.UnknownOutcome -> ToolExecution.Unknown(ToolError(reason.asErrorCode(ToolErrorCode.UNKNOWN_OUTCOME)))
        ToolResult.NeedsApproval -> ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED))
    }

    private fun String.asErrorCode(fallback: ToolErrorCode): ToolErrorCode =
        runCatching { ToolErrorCode.valueOf(this) }.getOrDefault(fallback)

    private fun ToolSpec.toLegacySpec(): LegacyToolSpec = LegacyToolSpec(
        name = name,
        description = description,
        parametersJson = inputSchema,
        capability = capability?.value.orEmpty(),
        sideEffect = sideEffect,
    )

    private class BoundCall(val invocation: RootToolInvocation) {
        var result: ToolResult? = null
        var approvalPending: Boolean = false
        var inFlight: Boolean = false
        var cancelled: Boolean = false
        var cancelRequired: Boolean = false
        var cancelSent: Boolean = false
        val cancelConfirmation = kotlinx.coroutines.CompletableDeferred<Boolean>()

        fun matches(candidate: RootToolInvocation): Boolean =
            invocation.toolName == candidate.toolName &&
                invocation.workspaceId == candidate.workspaceId &&
                invocation.request.canonicalSha256 == candidate.request.canonicalSha256
    }

    private companion object {
        val INVALID_CALL = ToolResult.Invalid("Tool call ID is missing")
        val INVALID_REPLAY = ToolResult.Invalid("Tool call ID was already used for a different request")
        val INVALID_APPROVAL = ToolResult.Invalid("No pending tool approval")
        const val IN_FLIGHT_REASON = "Tool call is already executing"
        const val CANCELLED_REASON = "Tool call was cancelled; outcome cannot be confirmed"
        const val UNKNOWN_REASON = "Tool execution failed; outcome is unknown"

        fun unavailableError(): ToolError = ToolError(
            ToolErrorCode.OPERATION_UNAVAILABLE,
            message = "Root tool is not available in this run",
        )

        fun unavailable(): ToolResult = ToolResult.Failure(unavailableError())

        fun invalidArguments(): ToolResult = ToolResult.Invalid("Invalid Root tool arguments")

        fun invalidRequestError(): ToolError = ToolError(ToolErrorCode.INVALID_REQUEST)
    }
}
