// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.ApprovalBinding
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceApplyPatchRequest
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspacePatchFormat
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.skills.tooling.ShellLimits

/**
 * Backend-neutral workspace tools.  Backend identity, root references and
 * provider handles stay in [WorkspaceRegistry]; the model sees only an opaque
 * workspace id and normalized relative paths.
 */
class UnifiedWorkspaceToolExecutor(
    private val registry: WorkspaceRegistry,
    private val approvalEngine: ApprovalEngine,
    private val resolver: EffectiveCapabilityResolver = EffectiveCapabilityResolver(),
    private val contextProvider: () -> ToolExecutionContext,
    private val auditSink: WorkspaceAuditSink? = null,
    private val auditFuse: WorkspaceAuditFuse = WorkspaceAuditFuse(),
    private val clock: ToolingClock = SYSTEM_TOOLING_CLOCK,
    private val dangerousModeProvider: () -> DangerousMode = { DangerousMode.DISABLED },
    /** Optional live provider state; null falls back to the run context snapshot. */
    private val authoritySelectionProvider: (() -> ToolAuthoritySelection?)? = null,
    /** Durable CAS seam for canonical ONCE workspace grants; absent means deny. */
    private val onceGrantConsumer: (CapabilityGrant) -> Boolean = { false },
) : ToolExecutor {
    /**
     * Schema is evaluated once against this executor's run snapshot.  It must
     * never be a process-global cache: a second Agent/authority/workspace run
     * receives an independent, capability-filtered list.
     */
    private val schemaContext: ToolExecutionContext = contextProvider()
    val toolingSpecs: List<ToolSpec> = Collections.unmodifiableList(exposedSpecs(schemaContext))

    /** Compatibility adapter for the existing ToolExecutor boundary. */
    override val specs: List<LegacyToolSpec> = Collections.unmodifiableList(toolingSpecs.map(::toLegacySpec))

    private val lock = Any()
    private val callsByRequest = linkedMapOf<String, BoundCall>()
    private val requestByModelCall = linkedMapOf<ModelCallKey, String>()

    /**
     * Operations that can ever reach the model for a workspace, in exposure
     * order.  `file_move` is deliberately absent: it is never exposed, so a
     * workspace enumeration must never imply it.
     */
    private val exposedWorkspaceOperations = listOf(
        WorkspaceOperation.LIST,
        WorkspaceOperation.GLOB,
        WorkspaceOperation.STAT,
        WorkspaceOperation.READ,
        WorkspaceOperation.GREP,
        WorkspaceOperation.WRITE,
        WorkspaceOperation.CREATE_DIRECTORY,
        WorkspaceOperation.DELETE,
        WorkspaceOperation.APPLY_PATCH,
    )

    override suspend fun invoke(call: ToolCall): ToolResult = invoke(call, contextProvider())

    suspend fun invoke(call: ToolCall, context: ToolExecutionContext): ToolResult {
        return invoke(call, context, UUID.randomUUID().toString())
    }

    /**
     * The shared ToolInvocation already carries a Runtime-generated request id.
     * Preserve it when crossing this adapter; only the legacy model-facing
     * ToolCall overload has to create a fresh opaque id here.
     */
    private suspend fun invoke(
        call: ToolCall,
        context: ToolExecutionContext,
        requestId: String,
    ): ToolResult {
        // Fail-closed backstop for stale model schemas: file_move is never
        // exposed, and the Android workspace backends cannot honor a move.
        // Return the typed failure before any registry, audit, or backend
        // work and direct the model to the supported explicit steps.
        if (call.name == FILE_MOVE) return ToolResult.Failure(FILE_MOVE_UNAVAILABLE)
        val parsed = parse(call) ?: return ToolResult.Invalid(ToolErrorCode.INVALID_REQUEST.name)
        val candidate = bind(requestId, call, parsed, context)
        val existingReservation = synchronized(lock) {
            val key = modelKey(context, call.callId)
            val existingRequestId = requestByModelCall[key]
            if (existingRequestId != null) {
                true to callsByRequest[existingRequestId]
            } else {
                // Claim the model call id and publish its BoundCall in one
                // critical section. Two concurrent deliveries can therefore
                // never both pass the replay gate and reach the backend.
                requestByModelCall[key] = requestId
                callsByRequest[requestId] = candidate
                false to null
            }
        }
        if (existingReservation.first) {
            val bound = existingReservation.second
            return when {
                bound == null -> ToolResult.UnknownOutcome(ToolErrorCode.CALL_ID_REPLAY.name)
                bound.call != call -> ToolResult.Invalid(ToolErrorCode.CALL_ID_REPLAY.name)
                bound.result != null -> bound.result!!
                bound.approvalPending -> ToolResult.NeedsApproval
                else -> ToolResult.UnknownOutcome(ToolErrorCode.CALL_ID_REPLAY.name)
            }
        }
        return invokeBound(candidate, context)
    }

    /** Shared skills-api execution contract adapter. */
    suspend fun invoke(invocation: ToolInvocation, context: ToolExecutionContext): ToolExecution {
        if (invocation.agentId != context.agentId || invocation.snapshotId != context.snapshotId) {
            return ToolExecution.Failed(ToolError(ToolErrorCode.SNAPSHOT_STALE))
        }
        val call = ToolCall(invocation.callId, invocation.name, invocation.argumentsJson)
        val result = invoke(
            call,
            context.copy(modelCallId = invocation.callId),
            invocation.requestId,
        )
        return result.toToolExecution()
    }

    suspend fun invoke(invocation: ToolInvocation): ToolExecution = invoke(invocation, contextProvider())

    override suspend fun approve(callId: String): ToolResult {
        // The legacy approval seam has no run context. Resolve a model call
        // only when it is unambiguous; duplicate ids across runs cannot pick
        // an arbitrary pending request.
        val requestId = resolveRequestId(callId)
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolResult.Invalid(ToolErrorCode.INVALID_REQUEST.name)
        val context = contextProvider()
        val current = bind(bound.requestId, bound.call, bound.parsed, context)
        val decision = approvalEngine.approve(
            requestId = bound.requestId,
            expectedBinding = current.binding,
            scope = current.scope,
            lifetime = GrantLifetime.ONCE,
        )
        if (decision !is ApprovalDecision.Approved) {
            bound.approvalPending = false
            val terminal = decision.toToolExecution()
            val audited = finishAudit(bound, bound.parsed, terminal, context)
            bound.result = audited.toLegacyResult()
            return bound.result!!
        }
        bound.approvalPending = false
        bound.approvalGrant = decision.grant
        return invokeBound(bound, context)
    }

    /** Explicitly deny a pending approval using either model call or request id. */
    override suspend fun reject(callId: String): ToolResult {
        val requestId = resolveRequestId(callId)
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolResult.Invalid(ToolErrorCode.INVALID_REQUEST.name)
        val context = contextProvider()
        val result = reject(requestId, context).toLegacyResult()
        bound.result = result
        return result
    }

    /** Explicitly expire a pending approval using either model call or request id. */
    override suspend fun expire(callId: String): ToolResult {
        val requestId = resolveRequestId(callId)
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolResult.Invalid(ToolErrorCode.INVALID_REQUEST.name)
        val context = contextProvider()
        val result = expire(requestId, context).toLegacyResult()
        bound.result = result
        return result
    }

    suspend fun approve(requestId: String, context: ToolExecutionContext = contextProvider()): ToolExecution {
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolExecution.Failed(ToolError(ToolErrorCode.INVALID_REQUEST))
        val current = bind(requestId, bound.call, bound.parsed, context)
        val decision = approvalEngine.approve(requestId, current.binding, current.scope, GrantLifetime.ONCE)
        if (decision !is ApprovalDecision.Approved) {
            bound.approvalPending = false
            val terminal = decision.toToolExecution()
            val result = finishAudit(bound, bound.parsed, terminal, context)
            bound.result = result.toLegacyResult()
            return result
        }
        bound.approvalPending = false
        bound.approvalGrant = decision.grant
        val result = invokeBoundExecution(bound, context)
        bound.result = result.toLegacyResult()
        return result
    }

    /** Explicitly deny a pending approval keyed by Runtime's internal id. */
    suspend fun reject(requestId: String, context: ToolExecutionContext = contextProvider()): ToolExecution {
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolExecution.Failed(ToolError(ToolErrorCode.INVALID_REQUEST))
        val decision = approvalEngine.reject(requestId)
        bound.approvalPending = false
        val result = finishAudit(bound, bound.parsed, decision.toToolExecution(), context)
        bound.result = result.toLegacyResult()
        return result
    }

    /**
     * Disclosure check for a cached workspace result.  Allows only a settled,
     * non-unknown completion for exactly this call while the workspace, path
     * scope, and live provider facts still authorize it.  This is read-only:
     * no backend dispatch, no audit writes, and no ONCE-grant consumption.  A
     * consumed ONCE grant therefore denies replay fail-closed.
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean {
        val requestId = resolveRequestId(call.callId)
        val bound = synchronized(lock) { callsByRequest[requestId] } ?: return false
        if (bound.call != call) return false
        val result = synchronized(lock) { bound.result } ?: return false
        if (result is ToolResult.UnknownOutcome) return false
        return revalidateReplay(bound)
    }

    /**
     * Deferred disclosure of a bounded read-only result the **same** execution
     * already produced: the first envelope of an explicit batch, or one page of a
     * job's output.  This is not a replay of an earlier answer and must not be
     * turned into one.
     *
     * It therefore admits exactly one case [authorizeReplay] must keep denying: a
     * ONCE grant this exact call consumed at dispatch.  The pre-consumption
     * receipt is revalidated through the resolver's exact consumption rule — the
     * live row must be precisely that row advanced by its consumption CAS — and
     * the current live workspace, backend, provider, path, snapshot binding and
     * policy must all still agree.  A revocation, expiry, scope/workspace edit,
     * binding removal or policy change therefore denies, and that denial is
     * sticky: the retained value can never be revived by a later restored
     * authorization.
     *
     * Only a settled [ToolResult.Value] for a read-only LIST/STAT/READ/GLOB/GREP
     * call is eligible.  A pending approval, a denial, an unknown outcome, a
     * mutation or a closed executor all deny.  Repeating a call id for an
     * already-delivered answer still goes through [authorizeReplay].
     */
    override suspend fun authorizeDeferredResult(call: ToolCall): Boolean {
        val requestId = resolveRequestId(call.callId)
        val bound = synchronized(lock) { callsByRequest[requestId] } ?: return false
        // Exact bound call only: never a different argument shape for a reused id.
        if (bound.call != call) return false
        if (synchronized(lock) { bound.approvalPending }) return false
        if (synchronized(lock) { bound.deferredInvalidated }) return false
        val result = synchronized(lock) { bound.result }
        if (result !is ToolResult.Value) return false
        if (!bound.parsed.kind.isReadOnlyDisclosable) return false
        if (revalidateDeferred(bound)) return true
        // Fail closed permanently: a payload whose authority could not be proven
        // again must not become disclosable if the authorization is restored.
        synchronized(lock) { bound.deferredInvalidated = true }
        return false
    }

    /**
     * Release the run-owned deferred state.  After this, every disclosure check
     * for a previously bound call denies because the bound call and its ONCE
     * receipts no longer exist.
     */
    override suspend fun close() {
        synchronized(lock) {
            callsByRequest.values.forEach { bound -> bound.onceReceipts.clear() }
            callsByRequest.clear()
            requestByModelCall.clear()
        }
    }

    /**
     * Live re-check for a deferred disclosure.
     *
     * Identity is frozen to the run that produced the value: a different Agent,
     * snapshot, session, task, Skill or policy/config revision may never continue
     * another run's deferred result.  Everything else — registered workspace,
     * descriptor, live provider/SAF readiness, dangerous mode, backend method
     * support, exact workspace/path scope, snapshot binding and policy version —
     * is re-read now, exactly as dispatch does.
     */
    private fun revalidateDeferred(bound: BoundCall): Boolean {
        val context = contextProvider()
        val frozen = bound.context
        if (frozen.agentId != context.agentId ||
            frozen.snapshotId != context.snapshotId ||
            frozen.sessionIdentity != context.sessionIdentity ||
            frozen.taskIdentity != context.taskIdentity ||
            frozen.skillId != context.skillId ||
            frozen.skillRevision != context.skillRevision ||
            frozen.policyVersion != context.policyVersion ||
            frozen.configSnapshotHash != context.configSnapshotHash
        ) {
            return false
        }
        val operation = bound.parsed
        val registered = registry.registered(operation.workspaceId) ?: return false
        if (!workspaceOperationStructurallyAvailable(context, registered, operation.kind, requireLiveReady = false)) return false
        if (!workspaceOperationStructurallyAvailable(context, registered, operation.kind, requireLiveReady = true)) return false
        val workspaceId = operation.workspaceId.takeIf { it.isNotBlank() }
        val path = operation.relativePath
        val receipts = synchronized(lock) { bound.onceReceipts.toList() }
        return operationCapabilities(operation.kind).all { capability ->
            if (resolver.revalidate(context, capability, workspaceId, path, operation.isMutation)) {
                return@all true
            }
            // The grant side of a deferred disclosure is proven either by a live
            // standing grant above, or by this exact call's ONCE consumption
            // receipt.  Nothing else may authorize it.
            receipts.any { consumed ->
                consumed.capability == capability &&
                    resolver.revalidateInFlight(
                        context = context,
                        capability = capability,
                        consumedOnceGrant = consumed,
                        scope = GrantScope(workspaceId, path, operation.isMutation),
                    )
            }
        }
    }

    private fun revalidateReplay(bound: BoundCall): Boolean {
        // A payload whose deferred disclosure already failed to re-prove its
        // authority stays denied, so a restored authorization cannot revive it.
        if (synchronized(lock) { bound.deferredInvalidated }) return false
        val context = contextProvider()
        if (bound.context.agentId != context.agentId || bound.context.snapshotId != context.snapshotId) return false
        val operation = bound.parsed
        if (operation.kind == WorkspaceOperation.WORKSPACE_LIST) {
            return authorizedWorkspaces(context, WorkspaceOperation.WORKSPACE_LIST, requireLiveReady = true).isNotEmpty()
        }
        val registered = registry.registered(operation.workspaceId) ?: return false
        if (!workspaceOperationAvailable(context, registered, operation.kind, requireLiveReady = true)) return false
        return exactPathAuthorization(context, operation)
    }

    /** Explicitly expire a pending approval keyed by Runtime's internal id. */
    suspend fun expire(requestId: String, context: ToolExecutionContext = contextProvider()): ToolExecution {
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolExecution.Failed(ToolError(ToolErrorCode.INVALID_REQUEST))
        val decision = approvalEngine.expire(requestId)
        bound.approvalPending = false
        val result = finishAudit(bound, bound.parsed, decision.toToolExecution(), context)
        bound.result = result.toLegacyResult()
        return result
    }

    private suspend fun invokeBound(bound: BoundCall, context: ToolExecutionContext): ToolResult {
        val result = invokeBoundExecution(bound, context)
        bound.result = result.toLegacyResult()
        return bound.result!!
    }

    private suspend fun invokeBoundExecution(bound: BoundCall, context: ToolExecutionContext): ToolExecution {
        val operation = bound.parsed
        val registered = operation.workspaceId.takeIf { it.isNotBlank() }?.let(registry::registered)
        val descriptor = registered?.descriptor

        // Check the selected provider and the exact backend implementation at
        // every entry, including a legacy approval continuation. A stale grant or
        // a provider switch can therefore never make the executor fall back
        // to another privileged backend.
        val structuralError = when {
            operation.kind == WorkspaceOperation.WORKSPACE_LIST -> {
                workspaceListAvailabilityError(context)
            }
            registered == null -> ToolError(ToolErrorCode.WORKSPACE_NOT_FOUND)
            !workspaceOperationAvailable(context, registered, operation.kind, requireLiveReady = false) ->
                ToolError(ToolErrorCode.CAPABILITY_DENIED)
            !workspaceOperationAvailable(context, registered, operation.kind, requireLiveReady = true) ->
                ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
            else -> null
        }
        if (structuralError != null) return finishAudit(bound, operation, ToolExecution.Failed(structuralError), context)

        val workspaceIdForGrant = operation.workspaceId.takeIf { it.isNotBlank() }
        val pathForGrant = when {
            operation.kind == WorkspaceOperation.WORKSPACE_LIST -> null
            else -> operation.relativePath
        }
        val sourceAllowed = if (operation.kind == WorkspaceOperation.WORKSPACE_LIST) {
            // Enumeration has no caller-supplied workspace. Check each disclosed
            // workspace explicitly; a scoped row must never authorize null scope.
            val workspaces = authorizedWorkspaces(context, requireLiveReady = false)
            workspaces.isNotEmpty() && workspaces.all {
                resolver.revalidate(context, operation.capability, it.descriptor.id)
            }
        } else {
            // A composite read operation (recursive search) needs every one of its
            // capabilities on the exact same path; the primary capability alone is
            // never enough to widen a subtree walk.
            operationCapabilities(operation.kind).all { capability ->
                resolver.revalidate(context, capability, workspaceIdForGrant,
                    pathForGrant, operation.isMutation)
            }
        }
        // A move has two independently user-visible paths.  A source grant
        // must never silently authorize a destination outside its scope.
        val destinationAllowed = operation.destinationPath?.let { destination ->
            resolver.revalidate(
                context,
                operation.capability,
                workspaceIdForGrant,
                destination,
                operation.isMutation,
            )
        } ?: true
        if (!sourceAllowed || !destinationAllowed) {
            return finishAudit(
                bound,
                operation,
                ToolExecution.Failed(ToolError(ToolErrorCode.CAPABILITY_DENIED)),
                context,
            )
        }
        if (descriptor != null) {
            if (!descriptor.readable && !operation.isMutation) {
                return finishAudit(bound, operation, ToolExecution.Failed(ToolError(ToolErrorCode.CAPABILITY_DENIED)), context)
            }
            if (operation.isMutation && !descriptor.writable) {
                return finishAudit(bound, operation, ToolExecution.Failed(ToolError(ToolErrorCode.WORKSPACE_READ_ONLY)), context)
            }
        }

        /*
         * CapabilityGrant + SnapshotGrantBinding are the user's durable authorization. Requiring
         * a second process-local ApprovalEngine grant here made every already-authorized file
         * read/write stall behind a hidden per-call prompt. The canonical resolver below still
         * rechecks revocation, expiry, policy version, workspace/path scope and consumes ONCE
         * grants atomically immediately before dispatch. High-risk shell reconfirmation remains a
         * separate ShellToolExecutor policy and is not weakened by this workspace rule.
         */

        // Revalidate all policy/provider/backend facts immediately before the
        // one-shot backend call. A durable grant does not pin a provider
        // connection, so all live routing facts are deliberately checked again.
        if (operation.kind == WorkspaceOperation.WORKSPACE_LIST) {
            val availabilityError = workspaceListAvailabilityError(context)
            if (availabilityError != null) {
                return finishAudit(bound, operation, ToolExecution.Failed(availabilityError), context)
            }
        } else if (registered == null ||
            !workspaceOperationAvailable(context, registered, operation.kind, requireLiveReady = false) ||
            !exactPathAuthorization(context, operation)
        ) {
            return finishAudit(bound, operation, ToolExecution.Failed(ToolError(ToolErrorCode.CAPABILITY_DENIED)), context)
        } else if (!workspaceOperationAvailable(context, registered, operation.kind, requireLiveReady = true)) {
            return finishAudit(
                bound,
                operation,
                ToolExecution.Failed(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)),
                context,
            )
        }

        // An observed entry version is not a promise that every mutation can
        // enforce it. Reject stale/mixed-backend requests before consuming an
        // ONCE grant or dispatching; never drop the caller's precondition.
        if (operation.expectedVersion != null && registered != null &&
            operation.capability !in registered.backend.expectedVersionCapabilities
        ) {
            return finishAudit(
                bound, operation,
                ToolExecution.Failed(ToolError(ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED)), context,
            )
        }

        // The resolver's canonical gate is the final grant/revision/lifetime
        // check. It runs immediately before dispatch, so a stale or ONCE grant
        // can never reach the backend.  A successful ONCE CAS is retained on the
        // bound call as the receipt a later deferred disclosure may revalidate.
        if (!authorizeForDispatch(context, operation, bound.onceReceipts)) {
            return finishAudit(bound, operation, ToolExecution.Failed(ToolError(ToolErrorCode.CAPABILITY_DENIED)), context)
        }

        if (auditFuse.isOpen) return ToolExecution.Failed(ToolError(ToolErrorCode.AUDIT_FUSE_OPEN))
        val startedAt = clock.nowMillis()
        if (!startAudit(bound, operation, bound.approvalGrant?.approvalId ?: bound.pendingApprovalId)) {
            return ToolExecution.Failed(ToolError(ToolErrorCode.AUDIT_UNAVAILABLE))
        }

        // Once STARTED is acknowledged, cancellation/transport failure is an
        // externally ambiguous outcome.  Preserve that fact and still emit
        // the completion audit before returning to the caller.
        val toolExecution = try {
            dispatch(operation, context, bound.onceReceipts).toToolExecution(operation)
        } catch (_: CancellationException) {
            ToolExecution.Unknown(ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
        } catch (_: Throwable) {
            // STARTED was already durably accepted, so an exception cannot
            // prove that the provider did not mutate state. Never advertise a
            // retryable failure or replay this request.
            ToolExecution.Unknown(ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
        }
        val completed = recordTerminal(bound, operation, toolExecution, clock.nowMillis() - startedAt)
        if (!completed) {
            auditFuse.trip()
            return ToolExecution.Unknown(
                ToolError(ToolErrorCode.UNKNOWN_OUTCOME, details = mapOf("audit_degraded" to "true")),
            )
        }
        return toolExecution
    }

    private suspend fun dispatch(
        parsed: ParsedCall,
        context: ToolExecutionContext,
        consumedReceipts: List<CapabilityGrant> = emptyList(),
    ): WorkspaceResult<Any> {
        if (parsed.kind == WorkspaceOperation.WORKSPACE_LIST) {
            @Suppress("UNCHECKED_CAST")
            val authorized = authorizedWorkspaceViews(context, consumedReceipts = consumedReceipts)
            if (authorized.isEmpty()) return WorkspaceResult.Failure(ToolError(ToolErrorCode.CAPABILITY_DENIED))
            return WorkspaceResult.Success(authorized) as WorkspaceResult<Any>
        }
        val registered = registry.registered(parsed.workspaceId)
            ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.WORKSPACE_NOT_FOUND))
        // This is the post-CAS availability probe: a request that was authorized only
        // by a ONCE grant has already consumed it, so the plain scope probe would
        // reject the request's own execution. The captured receipt is accepted here
        // instead, revalidated against the live workspace/path/binding/policy facts.
        if (!workspaceOperationAvailable(
                context,
                registered,
                parsed.kind,
                consumedReceipts = consumedReceipts,
                requestedPath = parsed.relativePath,
            )
        ) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.CAPABILITY_DENIED))
        }
        if (parsed.kind.isSearch) {
            return dispatchSearch(parsed, context, registered, consumedReceipts)
        }
        @Suppress("UNCHECKED_CAST")
        return when (parsed.kind) {
            WorkspaceOperation.WORKSPACE_LIST -> WorkspaceResult.Success(authorizedWorkspaceViews(context)) as WorkspaceResult<Any>
            WorkspaceOperation.LIST -> registered.backend.list(
                WorkspaceListRequest(
                    workspaceId = parsed.workspaceId,
                    relativePath = parsed.relativePath.takeUnless { it.isEmpty() },
                    maxEntries = parsed.maxEntries,
                    cursor = parsed.cursor,
                ),
            ) as WorkspaceResult<Any>
            WorkspaceOperation.STAT -> registered.backend.stat(WorkspaceStatRequest(parsed.workspaceId, parsed.relativePath)) as WorkspaceResult<Any>
            WorkspaceOperation.READ -> registered.backend.readText(
                WorkspaceReadTextRequest(
                    workspaceId = parsed.workspaceId,
                    relativePath = parsed.relativePath,
                    maxBytes = parsed.maxBytes.toLong(),
                    offsetBytes = parsed.offsetBytes,
                ),
            ) as WorkspaceResult<Any>
            WorkspaceOperation.APPLY_PATCH -> registered.backend.applyPatch(
                WorkspaceApplyPatchRequest(
                    workspaceId = parsed.workspaceId,
                    relativePath = parsed.relativePath,
                    patch = parsed.patch!!,
                    expectedVersion = parsed.expectedVersion!!,
                    format = parsed.patchFormat,
                ),
            ) as WorkspaceResult<Any>
            WorkspaceOperation.WRITE -> registered.backend.writeText(
                WorkspaceWriteTextRequest(parsed.workspaceId, parsed.relativePath, parsed.text!!, parsed.replaceExisting, parsed.expectedVersion),
            ) as WorkspaceResult<Any>
            WorkspaceOperation.CREATE_DIRECTORY -> registered.backend.createDirectory(
                WorkspaceCreateDirectoryRequest(parsed.workspaceId, parsed.relativePath, parsed.expectedVersion),
            ) as WorkspaceResult<Any>
            WorkspaceOperation.MOVE -> registered.backend.move(
                WorkspaceMoveRequest(parsed.workspaceId, parsed.relativePath, parsed.destinationPath!!, parsed.expectedVersion),
            ) as WorkspaceResult<Any>
            WorkspaceOperation.DELETE -> registered.backend.delete(
                WorkspaceDeleteRequest(parsed.workspaceId, parsed.relativePath, parsed.expectedVersion),
            ) as WorkspaceResult<Any>
            // Recursive search never reaches this direct backend dispatch; the
            // bounded engine owns those calls. These branches only satisfy
            // exhaustiveness for the closed operation set.
            WorkspaceOperation.GLOB,
            WorkspaceOperation.GREP,
                -> WorkspaceResult.Failure(ToolError(ToolErrorCode.INTERNAL_ERROR))
        }
    }

    /**
     * Run a bounded recursive search through [WorkspaceSearch].
     *
     * Authorization is proven once on the exact search subtree root immediately before dispatch by
     * the canonical gate above, and every entry the engine reaches is lexically inside that proven
     * subtree. The engine still re-runs [searchStillAuthorized] before every backend call and once
     * more here before any result (including path names) can be disclosed. A revoked, re-scoped or
     * de-authorized subtree therefore yields a typed denial instead of a partial listing.
     */
    private suspend fun dispatchSearch(
        parsed: ParsedCall,
        context: ToolExecutionContext,
        registered: WorkspaceRegistry.RegisteredWorkspace,
        consumedReceipts: List<CapabilityGrant>,
    ): WorkspaceResult<Any> {
        val spec = parsed.search ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        val authorityGuard: suspend () -> Boolean = {
            val live = registry.registered(parsed.workspaceId)
            live != null && live.backend === registered.backend && live.descriptor == registered.descriptor &&
                workspaceOperationStructurallyAvailable(context, live, parsed.kind, requireLiveReady = true) &&
                searchStillAuthorized(context, parsed, consumedReceipts)
        }
        val outcome = when (parsed.kind) {
            WorkspaceOperation.GLOB -> {
                val pattern = spec.glob
                    ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
                WorkspaceSearch.glob(
                    backend = registered.backend,
                    workspaceId = parsed.workspaceId,
                    root = parsed.relativePath,
                    patternText = spec.pattern ?: "",
                    pattern = pattern,
                    limits = spec.limits,
                    clock = clock,
                    authorityGuard = authorityGuard,
                )
            }
            WorkspaceOperation.GREP -> WorkspaceSearch.grep(
                backend = registered.backend,
                workspaceId = parsed.workspaceId,
                root = parsed.relativePath,
                query = spec.query ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST)),
                caseSensitive = spec.caseSensitive,
                limits = spec.limits,
                clock = clock,
                authorityGuard = authorityGuard,
            )
            else -> return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        }
        outcome.failure?.let { return WorkspaceResult.Failure(it) }
        // Disclosure gate. Cancellation and the exact-subtree authorization are
        // re-checked after the walk and before a single path or body is rendered.
        currentCoroutineContext().ensureActive()
        if (outcome.authorityFailed || !authorityGuard()) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.CAPABILITY_DENIED))
        }
        return WorkspaceResult.Success(outcome)
    }

    /**
     * Live re-check of the exact recursive-search subtree.
     *
     * Every access path of a search is inside the subtree proven by the same
     * path-scope rule the dispatch gate uses: an unscoped grant, or a grant whose
     * canonical scope is this path or one of its ancestors, covers the whole
     * subtree.  A grant scoped to a *descendant* (for example `src/sub` when the
     * search root is `src`) deliberately fails this check, so a directory-range
     * grant can never be widened into its parent.
     *
     * A ONCE grant this exact request already consumed cannot be resolved as an
     * active grant any more, so it is re-checked through the resolver's exact
     * consumption logic: the pre-consumption row is honoured only while the live
     * row is precisely that row advanced by its consumption CAS.  Revocation,
     * expiry, a scope/capability edit, a removed snapshot binding or a policy
     * change therefore still stops the search, and the request's own
     * workspace/path scope is revalidated on every check rather than assumed
     * from the row that was consumed once.
     */
    private fun searchStillAuthorized(
        context: ToolExecutionContext,
        parsed: ParsedCall,
        consumedReceipts: List<CapabilityGrant>,
    ): Boolean {
        val workspaceId = parsed.workspaceId.takeIf { it.isNotBlank() }
        val path = parsed.relativePath
        return operationCapabilities(parsed.kind).all { capability ->
            if (resolver.revalidate(context, capability, workspaceId, path, false)) return@all true
            consumedReceipts.any { consumed ->
                consumed.capability == capability &&
                    resolver.revalidateInFlight(
                        context = context,
                        capability = capability,
                        consumedOnceGrant = consumed,
                        scope = GrantScope(workspaceId, path, false),
                    )
            }
        }
    }

    /**
     * Every capability one operation needs on the same path. Ordinary operations
     * need exactly their own capability; recursive search is composite so a
     * `file_grep` cannot run with only one half of `file.list` + `file.read_text`.
     */
    private fun operationCapabilities(operation: WorkspaceOperation): List<CapabilityId> =
        operation.requiredOperations.map { it.capability }.distinct()

    /** Build the model schema from the exact run snapshot, never from a global list. */
    private fun exposedSpecs(context: ToolExecutionContext): List<ToolSpec> =
        TOOL_SPECS.mapNotNull { spec ->
            val operation = operationForTool(spec.name) ?: return@mapNotNull null
            // A persisted selected authority remains part of the run schema
            // while its Binder/USB/Wi-Fi transport is temporarily offline.
            // Dispatch repeats the live-ready check and fails closed without
            // switching providers, matching the shell exposure contract.
            val workspaces = authorizedWorkspaces(context, operation, requireLiveReady = false)
            if (workspaces.isEmpty()) return@mapNotNull null
            if (!operation.isMutation) return@mapNotNull spec
            val versioned = workspaces.filter { operation.capability in it.backend.expectedVersionCapabilities }
            val schema = Json.parseToJsonElement(spec.inputSchema) as JsonObject
            val properties = schema["properties"] as JsonObject
            if ("expected_version" !in properties) return@mapNotNull spec
            if (versioned.isEmpty()) {
                // Patch always requires its version; never expose a backend
                // that cannot implement that mandatory conditional contract.
                if (operation == WorkspaceOperation.APPLY_PATCH) return@mapNotNull null
                spec.copy(
                    inputSchema = JsonObject(schema + ("properties" to JsonObject(properties - "expected_version"))).toString(),
                    description = spec.description + " Omit expected_version on these workspaces; ordinary authorized mutations remain available.",
                )
            } else {
                spec.copy(description = spec.description +
                    " expected_version checks the target entry only; use it only when workspace_list lists this tool in expected_version_operations.")
            }
        }

    /**
     * Return only workspaces this Agent/snapshot can use for this operation.
     * Registry entries are process-global, so every descriptor is checked
     * against the resolver and provider state before it reaches the model.
     */
    private fun authorizedWorkspaces(
        context: ToolExecutionContext,
        operation: WorkspaceOperation = WorkspaceOperation.WORKSPACE_LIST,
        requireLiveReady: Boolean = true,
        consumedReceipts: List<CapabilityGrant> = emptyList(),
    ): List<WorkspaceRegistry.RegisteredWorkspace> =
        registry.descriptors().mapNotNull { publicDescriptor ->
            registry.registered(publicDescriptor.id)
        }.filter { registered ->
            workspaceOperationAvailable(context, registered, operation, requireLiveReady, consumedReceipts)
        }

    /**
     * Model-facing workspace entries for this Agent/snapshot.
     *
     * Enumeration keeps its existing gate ([WorkspaceOperation.WORKSPACE_LIST]
     * requires the enumerate capability and a live-ready backend).  Each entry
     * then reports the intersection of the live resolver decision and the
     * backend's supported operations, so a read-only Agent is never told that a
     * platform-writable workspace is writable for it.  [WorkspaceDescriptor.forAgent]
     * alone only hides the root reference and describes the backend's
     * registration-time surface, so it is never the source of these fields.
     */
    private fun authorizedWorkspaceViews(
        context: ToolExecutionContext,
        requireLiveReady: Boolean = true,
        consumedReceipts: List<CapabilityGrant> = emptyList(),
    ): List<AgentWorkspaceView> =
        authorizedWorkspaces(context, WorkspaceOperation.WORKSPACE_LIST, requireLiveReady, consumedReceipts)
            .map { registered -> workspaceView(context, registered, requireLiveReady) }

    private fun workspaceView(
        context: ToolExecutionContext,
        registered: WorkspaceRegistry.RegisteredWorkspace,
        requireLiveReady: Boolean,
    ): AgentWorkspaceView {
        val descriptor = registered.descriptor
        val available = exposedWorkspaceOperations.filter { operation ->
            workspaceOperationAvailable(context, registered, operation, requireLiveReady)
        }
        return AgentWorkspaceView(
            id = descriptor.id,
            displayName = descriptor.displayName,
            enabled = descriptor.enabled,
            workspaceScope = workspaceScopeWireName(descriptor.scope),
            readable = available.any { !it.isMutation },
            writable = available.any { it.isMutation },
            // Atomic replacement is a backend contract, never inferred from an
            // implementation class: it is exactly the backend's deterministic
            // `file.apply_patch` support (capability bit plus the typed
            // conditional method).  SAF_TREE advertises neither and stays false.
            atomicReplace = backendSupports(registered.backend, WorkspaceOperation.APPLY_PATCH),
            createOnlyOperations = available
                .filter { it.isCreateOnly(descriptor.backendType) }
                .map { it.modelToolName() }
                .sorted(),
            authorizedOperations = available.map { it.modelToolName() }.sorted(),
            expectedVersionOperations = available
                .filter { it.isMutation && it.capability in registered.backend.expectedVersionCapabilities }
                .map { it.modelToolName() }.sorted(),
            operationScopes = available.associate { operation ->
                operation.modelToolName() to operationScope(context, registered, operation)
            }.toSortedMap(),
        )
    }

    /**
     * Resolve the exact path scope the live resolver grants for one operation.
     *
     * `wholeWorkspace` is proven with an empty relative path: the canonical
     * resolver admits a path for an unscoped grant only, so a path-bounded grant
     * can never be reported as whole-workspace authority.  Bounded candidates
     * come from the run's canonical grant rows and are reported only after the
     * same live revalidation dispatch performs, so a revoked, expired, re-scoped
     * or cross-Agent/cross-Skill row is never advertised.  Only already
     * canonical workspace-relative scopes are emitted; anything else is dropped
     * rather than exposed or widened.
     */
    private fun operationScope(
        context: ToolExecutionContext,
        registered: WorkspaceRegistry.RegisteredWorkspace,
        operation: WorkspaceOperation,
    ): OperationScopeView {
        val workspaceId = registered.descriptor.id
        val capabilities = operationCapabilities(operation)
        // A composite operation is whole-workspace only when every required
        // capability authorizes the unscoped root probe.
        val wholeWorkspace = capabilities.all { capability ->
            resolver.revalidate(
                context = context,
                capability = capability,
                workspaceId = workspaceId,
                path = UNSCOPED_GRANT_PROBE_PATH,
                write = operation.isMutation,
            )
        }
        // Bounded candidates come from the run's canonical grant rows for any
        // required capability.  Each candidate is reported only after every
        // required capability revalidates it, so a composite report is never
        // wider than the authority it describes.
        val pathScopes = context.canonicalGrants.asSequence()
            .filter { grant ->
                grant.capability in capabilities &&
                    grant.agentId == context.agentId &&
                    grant.skillInstallId == context.skillId &&
                    (grant.workspaceId == null || grant.workspaceId == workspaceId)
            }
            .mapNotNull { it.pathScope }
            .distinct()
            .filter { scope -> isCanonicalRelativeScope(scope) }
            .filter { scope ->
                capabilities.all { capability ->
                    resolver.revalidate(
                        context = context,
                        capability = capability,
                        workspaceId = workspaceId,
                        path = scope,
                        write = operation.isMutation,
                    )
                }
            }
            .distinct()
            .sorted()
            .toList()
        return OperationScopeView(wholeWorkspace = wholeWorkspace, pathScopes = pathScopes)
    }

    /** A reportable scope is a relative workspace path that normalizes to itself. */
    private fun isCanonicalRelativeScope(scope: String): Boolean =
        runCatching { WorkspacePathPolicy.normalize(scope, false) == scope }.getOrDefault(false)

    /** SAF directory creation has no replace operation; ordinary text writes can replace. */
    private fun WorkspaceOperation.isCreateOnly(backendType: WorkspaceBackendType): Boolean =
        backendType == WorkspaceBackendType.SAF_TREE && this == WorkspaceOperation.CREATE_DIRECTORY

    private fun workspaceScopeWireName(scope: WorkspaceScope): String = when (scope) {
        WorkspaceScope.SELECTED_DIRECTORY -> "selected_directory"
        WorkspaceScope.FULL_DEVICE_FILES -> "full_device_files"
    }

    /** Model-facing workspace entry; never built from a raw backend descriptor. */
    private data class AgentWorkspaceView(
        val id: String,
        val displayName: String,
        val enabled: Boolean,
        val workspaceScope: String,
        val readable: Boolean,
        val writable: Boolean,
        val atomicReplace: Boolean,
        val createOnlyOperations: List<String>,
        val authorizedOperations: List<String>,
        val expectedVersionOperations: List<String>,
        val operationScopes: Map<String, OperationScopeView>,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("workspace_id", id)
            put("display_name", displayName)
            put("enabled", enabled)
            put("readable", readable)
            put("writable", writable)
            put("workspace_scope", workspaceScope)
            put("atomic_replace", atomicReplace)
            putJsonArray("create_only_operations") { createOnlyOperations.forEach { add(it) } }
            putJsonArray("authorized_operations") { authorizedOperations.forEach { add(it) } }
            put("version_scope", "target_entry")
            putJsonArray("expected_version_operations") { expectedVersionOperations.forEach { add(it) } }
            putJsonObject("operation_scopes") {
                operationScopes.forEach { (toolName, scope) ->
                    putJsonObject(toolName) {
                        put("whole_workspace", scope.wholeWorkspace)
                        putJsonArray("path_scopes") { scope.pathScopes.forEach { add(it) } }
                    }
                }
            }
        }
    }

    /** One operation's resolved scope: whole-workspace, bounded relative scopes, or neither. */
    private data class OperationScopeView(
        val wholeWorkspace: Boolean,
        val pathScopes: List<String>,
    )

    /**
     * Whether this operation has any permitted path at all.
     *
     * [consumedReceipts] is the post-CAS form: after [authorizeForDispatch] consumed a
     * ONCE grant for this exact request, the plain scope probe can no longer see it,
     * so the captured pre-consumption receipt is accepted as the only substitute and
     * is revalidated through the resolver's exact consumption rule (revision, live
     * workspace/path/binding/policy).  Standing grants keep their original semantics
     * because the ordinary path is tried first, and callers that must keep rejecting
     * a consumed ONCE (replay, deferred disclosure) simply pass no receipts.
     *
     * [requestedPath] is the request's own relative path (`ParsedCall.relativePath`);
     * the operation enum carries no path of its own.
     */
    private fun workspaceOperationAvailable(
        context: ToolExecutionContext,
        registered: WorkspaceRegistry.RegisteredWorkspace,
        operation: WorkspaceOperation,
        requireLiveReady: Boolean = true,
        consumedReceipts: List<CapabilityGrant> = emptyList(),
        requestedPath: String? = null,
    ): Boolean {
        if (!workspaceOperationStructurallyAvailable(context, registered, operation, requireLiveReady)) return false
        val workspaceId = registered.descriptor.id
        if (operation == WorkspaceOperation.WORKSPACE_LIST) {
            if (resolver.revalidate(context, operation.capability, workspaceId)) return true
            return consumedReceipts.any { receipt ->
                receipt.capability == operation.capability &&
                    resolver.revalidateInFlight(context, operation.capability, receipt, GrantScope(workspaceId, null))
            }
        }
        // Schema/availability describe whether some permitted path exists.
        // Exact dispatch still revalidates the actual source and destination.
        val scope = operationScope(context, registered, operation)
        if (scope.wholeWorkspace || scope.pathScopes.isNotEmpty()) return true
        if (consumedReceipts.isEmpty()) return false
        val path = requestedPath ?: return false
        // Per capability, not per request: a mixed-lifetime request (one half already
        // consumed as ONCE, the other half still standing) is satisfied when each
        // capability has *either* its own live standing scope *or* its own exact
        // consumed receipt.  Demanding receipts for every capability would wrongly
        // deny the standing half.
        return operationCapabilities(operation).all { capability ->
            resolver.revalidate(context, capability, workspaceId, path, operation.isMutation) ||
                consumedReceipts.any { receipt ->
                    receipt.capability == capability &&
                        resolver.revalidateInFlight(
                            context = context,
                            capability = capability,
                            consumedOnceGrant = receipt,
                            scope = GrantScope(workspaceId, path, operation.isMutation),
                        )
                }
        }
    }

    /**
     * Everything [workspaceOperationAvailable] proves that is *not* derived from a
     * live grant: the workspace/backend descriptor, the live provider or SAF
     * availability, the dangerous-mode gate and the exact typed backend method.
     *
     * Deferred disclosure needs this part on its own, because it authorizes the
     * grant side through this call's exact ONCE consumption receipts, which the
     * plain scope probe cannot see.
     */
    private fun workspaceOperationStructurallyAvailable(
        context: ToolExecutionContext,
        registered: WorkspaceRegistry.RegisteredWorkspace,
        operation: WorkspaceOperation,
        requireLiveReady: Boolean,
    ): Boolean {
        val descriptor = registered.descriptor
        if (!descriptor.enabled) return false
        if (descriptor.backendType == WorkspaceBackendType.SAF_TREE) {
            // A dead SAF root is reported by the real adapter as a disabled
            // live descriptor plus a cleared operation-capability set.  That
            // is a live-availability signal, not a persistent capability
            // denial — the grant-backed evaluation below must still pass at
            // requireLiveReady=false so callers see
            // AUTHORITY_TEMPORARILY_UNAVAILABLE, not CAPABILITY_DENIED.
            val safCapabilitiesCleared = registered.backend.capabilities.isEmpty()
            if (requireLiveReady &&
                (!registered.backend.descriptor.enabled || safCapabilitiesCleared)
            ) {
                return false
            }
            if (!safCapabilitiesCleared && !backendSupports(registered.backend, operation)) return false
        } else if (descriptor.backendType == WorkspaceBackendType.PRIVILEGED) {
            if (!privilegedProviderConfigured(descriptor, context)) return false
            if (requireLiveReady && !privilegedProviderReady(descriptor, context)) return false
        }
        if (descriptor.scope == WorkspaceScope.FULL_DEVICE_FILES && dangerousModeProvider() == DangerousMode.DISABLED) {
            return false
        }
        if (descriptor.backendType != WorkspaceBackendType.SAF_TREE &&
            !backendSupports(registered.backend, operation)
        ) {
            return false
        }
        if (!descriptor.readable && !operation.isMutation) return false
        if (operation.isMutation && !descriptor.writable) return false
        return true
    }

    /** Exact path checks are repeated after approval and immediately before dispatch. */
    private fun exactPathAuthorization(context: ToolExecutionContext, operation: ParsedCall): Boolean {
        if (operation.kind == WorkspaceOperation.WORKSPACE_LIST) return true
        val workspaceId = operation.workspaceId
        val capabilities = operationCapabilities(operation.kind)
        val paths = listOf(operation.relativePath) + listOfNotNull(operation.destinationPath)
        return paths.all { path ->
            capabilities.all { capability ->
                resolver.revalidate(
                    context = context,
                    capability = capability,
                    workspaceId = workspaceId,
                    path = path,
                    write = operation.isMutation,
                )
            }
        }
    }

    /**
     * Resolve the exact workspace/path scope immediately before dispatch.  A
     * MOVE has two paths but is one logical operation: the same one-shot grant
     * may authorize both paths, so its durable consumer is invoked at most once
     * per invocation while every path still receives an exact resolver check.
     *
     * [consumedSink] records the ONCE grant rows this exact request consumed, so
     * a long-running composite read can keep proving its in-flight authority
     * without re-consuming or replaying the grant.
     */
    private fun authorizeForDispatch(
        context: ToolExecutionContext,
        operation: ParsedCall,
        consumedSink: MutableList<CapabilityGrant>? = null,
    ): Boolean {
        val consumedOnceGrantIds = mutableSetOf<String>()
        val consumeOnce: (CapabilityGrant) -> Boolean = { grant ->
            if (grant.grantId in consumedOnceGrantIds) {
                true
            } else {
                onceGrantConsumer(grant).also { accepted ->
                    if (accepted) {
                        consumedOnceGrantIds += grant.grantId
                        consumedSink?.add(grant)
                    }
                }
            }
        }
        val workspaceIds = if (operation.kind == WorkspaceOperation.WORKSPACE_LIST) {
            authorizedWorkspaces(context, WorkspaceOperation.WORKSPACE_LIST, requireLiveReady = false)
                .map { it.descriptor.id }
        } else {
            listOf(operation.workspaceId)
        }
        if (workspaceIds.isEmpty()) return false
        val paths = if (operation.kind == WorkspaceOperation.WORKSPACE_LIST) {
            listOf<String?>(null)
        } else {
            listOf(operation.relativePath) + listOfNotNull(operation.destinationPath)
        }
        val capabilities = operationCapabilities(operation.kind)
        for (workspaceId in workspaceIds) {
            for (path in paths) {
                for (capability in capabilities) {
                    if (resolver.authorizeForDispatch(
                            context = context,
                            capability = capability,
                            consumer = consumeOnce,
                            workspaceId = workspaceId.takeUnless { it.isBlank() },
                            path = path,
                            write = operation.isMutation,
                        ) == DispatchAuthorization.DENIED
                    ) return false
                }
            }
        }
        return true
    }

    /**
     * The shared backend interface has default unsupported methods.  A
     * capability bit is therefore considered real only when its exact typed
     * request method is implemented by the adapter as well.  A composite
     * operation is supported only when every one of its required operations is.
     */
    private fun backendSupports(
        backend: WorkspaceBackend,
        operation: WorkspaceOperation,
    ): Boolean = operation.requiredOperations.all { required -> backendSupportsSingle(backend, required) }

    private fun backendSupportsSingle(
        backend: WorkspaceBackend,
        operation: WorkspaceOperation,
    ): Boolean {
        if (operation.capability !in backend.capabilities) return false
        // Patch's version is mandatory. A capability bit without an enforced
        // conditional contract cannot be advertised as atomic replacement.
        if (operation == WorkspaceOperation.APPLY_PATCH &&
            operation.capability !in backend.expectedVersionCapabilities
        ) return false
        return runCatching {
            backend.javaClass.methods.any { method ->
                method.name == operation.backendMethod &&
                    method.parameterTypes.firstOrNull() == operation.requestType &&
                    method.declaringClass != WorkspaceBackend::class.java
            }
        }.getOrDefault(false)
    }

    private fun workspaceListAvailabilityError(context: ToolExecutionContext): ToolError? {
        if (authorizedWorkspaces(context, requireLiveReady = true).isNotEmpty()) return null
        return if (authorizedWorkspaces(context, requireLiveReady = false).isNotEmpty()) {
            ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        } else {
            ToolError(ToolErrorCode.CAPABILITY_DENIED)
        }
    }

    /** A privileged descriptor is bound to one provider at registration time. */
    private fun privilegedAuthority(descriptor: runtime.mobileagent.skills.tooling.WorkspaceDescriptor): Authority? {
        if (descriptor.backendType != WorkspaceBackendType.PRIVILEGED) return null
        return descriptor.rootReference
            .removePrefix(PRIVILEGED_AUTHORITY_PREFIX)
            .takeIf { descriptor.rootReference.startsWith(PRIVILEGED_AUTHORITY_PREFIX) }
            ?.let { runCatching { Authority.valueOf(it) }.getOrNull() }
    }

    private fun privilegedProviderReady(descriptor: runtime.mobileagent.skills.tooling.WorkspaceDescriptor, context: ToolExecutionContext): Boolean {
        if (!privilegedProviderConfigured(descriptor, context)) return false
        val selection = currentAuthoritySelection(context) ?: return false
        return selection.selectedState?.isReady == true
    }

    private fun privilegedProviderConfigured(
        descriptor: runtime.mobileagent.skills.tooling.WorkspaceDescriptor,
        context: ToolExecutionContext,
    ): Boolean {
        val expected = privilegedAuthority(descriptor) ?: return false
        val selection = currentAuthoritySelection(context) ?: return false
        return selection.selected == expected && selection.selectedState?.isConfiguredForSelection == true
    }

    // A supplied live provider is authoritative. A null value means that no
    // provider selection can be established; it never permits stale fallback.
    private fun currentAuthoritySelection(context: ToolExecutionContext): ToolAuthoritySelection? =
        if (authoritySelectionProvider != null) authoritySelectionProvider.invoke() else context.authoritySelection

    /** Start exactly once; approval id is carried through both audit records. */
    private suspend fun startAudit(bound: BoundCall, parsed: ParsedCall, approvalId: String?): Boolean {
        synchronized(lock) {
            if (bound.auditStarted) {
                return bound.auditApprovalId == approvalId
            }
        }
        if (auditFuse.isOpen) return false
        val sink = auditSink ?: return false
        val accepted = runCatching {
            sink.record(WorkspaceAuditEvent(
                phase = WorkspaceAuditPhase.STARTED,
                requestId = bound.requestId,
                agentId = bound.context.agentId,
                capability = parsed.capability,
                workspaceId = parsed.workspaceId,
                relativePathSha256 = sha256(parsed.relativePath),
                approvalId = approvalId,
                operation = parsed.kind.auditOperation,
                destinationPathSha256 = parsed.destinationPath?.let(::sha256),
                backendType = auditBackendType(bound, parsed),
            ))
        }.getOrDefault(false)
        if (accepted) {
            synchronized(lock) {
                bound.auditStarted = true
                bound.auditApprovalId = approvalId
            }
        }
        return accepted
    }

    private suspend fun finishAudit(
        bound: BoundCall,
        parsed: ParsedCall,
        result: ToolExecution,
        context: ToolExecutionContext,
    ): ToolExecution {
        if (auditFuse.isOpen) return ToolExecution.Failed(ToolError(ToolErrorCode.AUDIT_FUSE_OPEN))
        val approvalId = bound.approvalGrant?.approvalId ?: bound.pendingApprovalId
        if (!startAudit(bound, parsed, approvalId)) {
            return ToolExecution.Failed(ToolError(ToolErrorCode.AUDIT_UNAVAILABLE))
        }
        if (bound.auditTerminal) return result
        val completed = recordTerminal(bound, parsed, result, 0L)
        if (!completed) {
            auditFuse.trip()
            return ToolExecution.Failed(
                ToolError(ToolErrorCode.AUDIT_UNAVAILABLE, details = mapOf("audit_degraded" to "true")),
            )
        }
        return result
    }

    private suspend fun recordTerminal(
        bound: BoundCall,
        parsed: ParsedCall,
        result: ToolExecution,
        durationMs: Long,
    ): Boolean {
        val sink = auditSink ?: return false
        val outcome = result.auditOutcome()
        val accepted = runCatching {
            sink.record(WorkspaceAuditEvent(
                phase = WorkspaceAuditPhase.TERMINAL,
                requestId = bound.requestId,
                agentId = bound.context.agentId,
                capability = parsed.capability,
                workspaceId = parsed.workspaceId,
                relativePathSha256 = sha256(parsed.relativePath),
                // Keep the coarse audit outcome separate from the typed error
                // code.  The latter is the only actionable reason that can be
                // safely carried through the diagnostics adapter.
                resultCode = result.auditResultCode(),
                durationMs = durationMs.coerceAtLeast(0),
                approvalId = bound.auditApprovalId ?: bound.approvalGrant?.approvalId ?: bound.pendingApprovalId,
                operation = parsed.kind.auditOperation,
                outcome = outcome,
                destinationPathSha256 = parsed.destinationPath?.let(::sha256),
                backendType = auditBackendType(bound, parsed),
            ))
        }.getOrDefault(false)
        if (accepted) {
            synchronized(lock) { bound.auditTerminal = true }
        }
        return accepted
    }

    private fun ToolExecution.auditOutcome(): WorkspaceAuditOutcome = when (this) {
        is ToolExecution.Value -> WorkspaceAuditOutcome.SUCCEEDED
        is ToolExecution.Unknown -> WorkspaceAuditOutcome.UNKNOWN
        is ToolExecution.Failed -> when (error.code) {
            ToolErrorCode.CAPABILITY_DENIED,
            ToolErrorCode.APPROVAL_REQUIRED,
            ToolErrorCode.APPROVAL_DENIED,
            ToolErrorCode.AUTHORITY_NOT_GRANTED,
            ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED,
            ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            ToolErrorCode.SHIZUKU_PERMISSION_DENIED,
            ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE,
            ToolErrorCode.BRIDGE_NOT_PAIRED,
            ToolErrorCode.BRIDGE_DISCONNECTED,
            ToolErrorCode.ADB_DEVICE_UNAUTHORIZED,
            ToolErrorCode.ADB_DEVICE_OFFLINE,
            ToolErrorCode.ADB_DEVICE_DISCONNECTED,
            ToolErrorCode.ADB_APP_NOT_INSTALLED,
            ToolErrorCode.DANGEROUS_MODE_DISABLED,
            ToolErrorCode.SHELL_CAPABILITY_DENIED,
            ToolErrorCode.SHELL_HIGH_RISK_APPROVAL_REQUIRED,
            ToolErrorCode.WORKSPACE_NOT_FOUND,
            ToolErrorCode.WORKSPACE_READ_ONLY,
            ToolErrorCode.PERMISSION_DENIED,
            ToolErrorCode.PATH_OUT_OF_SCOPE,
            ToolErrorCode.SYMLINK_FORBIDDEN,
            ToolErrorCode.ROOT_OPERATION_FORBIDDEN,
            ToolErrorCode.SNAPSHOT_STALE,
                -> WorkspaceAuditOutcome.DENIED
            ToolErrorCode.SHELL_CANCELLED -> WorkspaceAuditOutcome.CANCELLED
            ToolErrorCode.UNKNOWN_OUTCOME,
            ToolErrorCode.SHELL_TIMED_OUT,
                -> WorkspaceAuditOutcome.UNKNOWN
            // Approval expiry/settlement timeouts are pre-dispatch policy
            // failures.  Backend timeouts are converted to Unknown by
            // parseResult, so they retain their externally ambiguous audit
            // outcome without conflating them with approval denial.
            ToolErrorCode.TIMEOUT -> WorkspaceAuditOutcome.DENIED
            else -> WorkspaceAuditOutcome.FAILED
        }
    }

    /** Stable terminal result code; never collapse a typed failure to FAILED. */
    private fun ToolExecution.auditResultCode(): String = when (this) {
        is ToolExecution.Value -> "SUCCEEDED"
        is ToolExecution.Failed -> error.code.name
        is ToolExecution.Unknown -> error.code.name
    }

    private fun auditBackendType(bound: BoundCall, parsed: ParsedCall): WorkspaceAuditBackendType {
        val descriptor = parsed.workspaceId.takeIf { it.isNotBlank() }
            ?.let(registry::registered)
            ?.descriptor
            ?: return WorkspaceAuditBackendType.UNKNOWN
        return when (descriptor.backendType) {
            WorkspaceBackendType.INTERNAL -> WorkspaceAuditBackendType.INTERNAL
            WorkspaceBackendType.SAF_TREE -> WorkspaceAuditBackendType.SAF_TREE
            WorkspaceBackendType.PRIVILEGED -> when (
                privilegedAuthority(descriptor) ?: currentAuthoritySelection(bound.context)?.selected
            ) {
                Authority.SHIZUKU -> WorkspaceAuditBackendType.SHIZUKU
                Authority.WIRED_ADB -> WorkspaceAuditBackendType.WIRED_ADB
                else -> WorkspaceAuditBackendType.UNKNOWN
            }
        }
    }

    /**
     * The approval scope revision must cover every capability the request needs.
     * A missing live revision for any of them keeps the scope at 0, so a
     * revision-bound approval can never be created against a half-authorized
     * composite request.
     */
    private fun liveGrantRevisionFor(context: ToolExecutionContext, parsed: ParsedCall): Long {
        val workspaceId = parsed.workspaceId.takeIf { it.isNotBlank() }
        val path = parsed.relativePath.takeUnless { it.isEmpty() }
        val revisions = operationCapabilities(parsed.kind).map { capability ->
            resolver.liveGrantRevision(context, capability, workspaceId, path, parsed.isMutation)
        }
        if (revisions.any { it == null }) return 0L
        return revisions.filterNotNull().maxOrNull() ?: 0L
    }

    private fun bind(requestId: String, call: ToolCall, parsed: ParsedCall, context: ToolExecutionContext): BoundCall {
        val mode = dangerousModeProvider()
        val binding = ApprovalBinding(
            requestId = requestId,
            callId = call.callId,
            agentId = context.agentId,
            snapshotId = context.snapshotId,
            skillId = context.skillId,
            skillRevision = context.skillRevision,
            command = parsed.canonicalArguments,
            normalizedCwd = null,
            timeoutMs = 1,
            // ApprovalBinding shares the shell output ceiling even for typed
            // workspace results; keep the workspace serializer's larger
            // budget as an independent outer guard, but never issue an
            // invalid binding.
            maxOutputBytes = ShellLimits.MAX_OUTPUT_BYTES,
            selectedAuthority = parsed.workspaceId.takeIf { it.isNotBlank() }
                ?.let(registry::internalDescriptor)
                ?.let(::privilegedAuthority)
                ?: Authority.NONE,
            dangerousMode = mode,
            toolSchemaVersion = TOOL_SCHEMA_VERSION,
            policyVersion = context.policyVersion,
            configSnapshotHash = context.configSnapshotHash,
            sessionIdentity = context.sessionIdentity,
        )
        return BoundCall(
            requestId = requestId,
            call = call,
            parsed = parsed,
            context = context,
            binding = binding,
            scope = ApprovalScope(
                capability = parsed.capability,
                workspaceId = parsed.workspaceId.takeIf { it.isNotBlank() },
                pathScope = parsed.relativePath + (parsed.destinationPath?.let { "->$it" } ?: ""),
                grantRevision = liveGrantRevisionFor(context, parsed),
                policyVersion = context.policyVersion,
                taskId = context.taskIdentity.takeIf { it.isNotBlank() },
                sessionId = context.sessionIdentity,
            ),
        )
    }

    private fun parse(call: ToolCall): ParsedCall? = runCatching {
        if (call.callId.isBlank() || call.name !in TOOL_NAMES) return null
        val root = Json.parseToJsonElement(call.argumentsJson) as? JsonObject ?: return null
        val kind = when (call.name) {
            WORKSPACE_LIST -> WorkspaceOperation.WORKSPACE_LIST
            FILE_LIST -> WorkspaceOperation.LIST
            FILE_STAT -> WorkspaceOperation.STAT
            FILE_READ_TEXT -> WorkspaceOperation.READ
            FILE_WRITE_TEXT -> WorkspaceOperation.WRITE
            FILE_CREATE_DIRECTORY -> WorkspaceOperation.CREATE_DIRECTORY
            FILE_DELETE -> WorkspaceOperation.DELETE
            FILE_MOVE -> WorkspaceOperation.MOVE
            FILE_GLOB -> WorkspaceOperation.GLOB
            FILE_GREP -> WorkspaceOperation.GREP
            FILE_APPLY_PATCH, APPLY_PATCH -> WorkspaceOperation.APPLY_PATCH
            else -> return null
        }
        val allowed = allowedKeys(kind)
        if (root.keys.any { it !in allowed }) return null
        val workspaceId = if (kind == WorkspaceOperation.WORKSPACE_LIST) {
            ""
        } else {
            root.aliasString("workspaceId", "workspace_id") ?: return null
        }
        if (!root.aliasStringElement("relativePath", "relative_path")) return null
        val rawPath = root.aliasStringAllowEmpty("relativePath", "relative_path")
        val path = if (kind == WorkspaceOperation.WORKSPACE_LIST) {
            ""
        } else {
            // A recursive search root is a caller-supplied directory; the empty
            // value means the workspace root and is authorized as such (never as
            // an implicit widening of a path-bounded grant).
            registry.validatePath(rawPath, kind == WorkspaceOperation.LIST || kind.isSearch)
        }
        val search = if (kind.isSearch) {
            parseSearchSpec(root, kind) ?: return null
        } else {
            null
        }
        val destination = if (kind == WorkspaceOperation.MOVE) {
            registry.validatePath(root.aliasString("destinationRelativePath", "destination_relative_path"), false)
        } else null
        if (!root.aliasLongElement("maxBytes", "max_bytes")) return null
        if (!root.aliasLongElement("maxEntries", "max_entries")) return null
        if (root.containsKey("cursor") && !root.stringElement("cursor")) return null
        if (!root.aliasLongElement("offsetBytes", "offset_bytes")) return null
        val maxBytes = root.aliasLong("maxBytes", "max_bytes")?.also { require(it in 1..MAX_READ_BYTES) } ?: MAX_READ_BYTES.toLong()
        val maxEntries = root.aliasLong("maxEntries", "max_entries")?.also { require(it in 1..MAX_ENTRIES) }?.toInt() ?: DEFAULT_MAX_ENTRIES
        val cursor = root.string("cursor")
        if (kind == WorkspaceOperation.LIST && root.containsKey("cursor") && cursor == null) return null
        val offsetBytes = root.aliasLong("offsetBytes", "offset_bytes") ?: 0L
        if (kind != WorkspaceOperation.READ && root.containsAny("offsetBytes", "offset_bytes")) return null
        if (kind == WorkspaceOperation.READ && offsetBytes < 0L) return null
        val text = root.stringAllowEmpty("text")?.also {
            require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_WRITE_BYTES)
        }
        if (root.containsKey("text") && !root.stringElement("text")) return null
        if (kind == WorkspaceOperation.WRITE && text == null) return null
        if (kind != WorkspaceOperation.WRITE && text != null) return null
        if (root.containsKey("replace") && !root.booleanElement("replace")) return null
        if (!root.aliasLongElement("expectedVersion", "expected_version")) return null
        val replace = root.boolean("replace") ?: false
        val expected = root.aliasLong("expectedVersion", "expected_version")
        val patch = root.stringAllowEmpty("patch")?.also {
            require(it.isNotEmpty())
            require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATCH_BYTES)
        }
        if (root.containsKey("patch") && !root.stringElement("patch")) return null
        if (kind == WorkspaceOperation.APPLY_PATCH && patch == null) return null
        if (kind != WorkspaceOperation.APPLY_PATCH && patch != null) return null
        if (kind == WorkspaceOperation.APPLY_PATCH && expected == null) return null
        val patchFormat = if (root.containsKey("format")) {
            val rawFormat = root.stringAllowEmpty("format") ?: return null
            when (rawFormat) {
                "unified_diff" -> WorkspacePatchFormat.UNIFIED_DIFF
                "replace" -> WorkspacePatchFormat.REPLACE
                else -> return null
            }
        } else WorkspacePatchFormat.UNIFIED_DIFF
        ParsedCall(
            kind = kind,
            workspaceId = workspaceId,
            relativePath = path,
            destinationPath = destination,
            text = text,
            patch = patch,
            patchFormat = patchFormat,
            maxBytes = maxBytes.toInt(),
            maxEntries = maxEntries,
            cursor = cursor,
            offsetBytes = offsetBytes,
            replaceExisting = replace,
            expectedVersion = expected,
            capability = kind.capability,
            search = search,
            canonicalArguments = canonicalJson(root).ifEmpty { "{}" },
        )
    }.getOrNull()

    /**
     * Read the search-only arguments and let [WorkspaceSearchProtocol] validate and compile them.
     *
     * The shared JSON argument helpers stay here because every typed file tool uses them; only the
     * type-shape guards remain, so a wrong JSON type is still rejected instead of silently falling
     * back to a default.  All bounds validation and the pattern/query compilation live in the
     * protocol file.
     */
    private fun parseSearchSpec(root: JsonObject, kind: WorkspaceOperation): WorkspaceSearchRequest? {
        val isGlob = kind == WorkspaceOperation.GLOB
        // Type-check every alias pair once, then read the already-validated values.
        if (SEARCH_LIMIT_KEYS.any { (camel, snake) -> !root.aliasLongElement(camel, snake) }) return null
        if (!root.aliasBooleanElement("caseSensitive", "case_sensitive")) return null
        if (isGlob && root.containsAny("caseSensitive", "case_sensitive")) return null
        val limits = SEARCH_LIMIT_KEYS.map { (camel, snake) -> root.aliasLong(camel, snake) }
        return WorkspaceSearchProtocol.request(
            isGlob = isGlob,
            pattern = if (isGlob) root.string("pattern") else null,
            query = if (isGlob) null else root.string("query"),
            caseSensitive = root.aliasBoolean("caseSensitive", "case_sensitive"),
            maxDepth = limits[0],
            maxScannedEntries = limits[1],
            maxScannedFiles = limits[2],
            maxScannedBytes = limits[3],
            maxResults = limits[4],
            maxMillis = limits[5],
        )
    }

    private fun renderSuccess(parsed: ParsedCall, value: Any): String? {
        val json = runCatching {
            buildJsonObject {
                put("success", true)
                if (parsed.workspaceId.isNotBlank()) put("workspace_id", parsed.workspaceId)
                when (value) {
                    is List<*> -> putJsonArray("workspaces") {
                        value.forEach { entry ->
                            // Only the live-authorized view is representable here.
                            // Anything else fails closed as INTERNAL_ERROR rather
                            // than falling back to a raw backend descriptor.
                            val view = entry as? AgentWorkspaceView
                                ?: error("Unsupported workspace enumeration entry")
                            add(view.toJson())
                        }
                    }
                    is WorkspaceSearch.WorkspaceSearchGlobOutcome ->
                        WorkspaceSearchProtocol.putGlobResult(this, value)
                    is WorkspaceSearch.WorkspaceSearchGrepOutcome ->
                        WorkspaceSearchProtocol.putGrepResult(this, value)
                    is runtime.mobileagent.skills.tooling.WorkspaceListing -> {
                        putJsonArray("entries") {
                            value.entries.forEach { entry -> add(buildJsonObject {
                                put("relative_path", normalizeOutputPath(entry.relativePath))
                                put("type", if (entry.type == WorkspaceEntryType.DIRECTORY) "directory" else "file")
                                put("bytes", entry.sizeBytes)
                                entry.version?.let { put("version", it) }
                            }) }
                        }
                        if (value.skippedEntries > 0 || value.warnings.isNotEmpty()) {
                            put("skipped_entries", value.skippedEntries)
                            putJsonArray("warnings") {
                                value.warnings.forEach { warning ->
                                    add(buildJsonObject {
                                        put("code", warning.wireCode)
                                        put("count", warning.count)
                                    })
                                }
                            }
                        }
                        val nextCursor = value.nextCursor
                        if (nextCursor != null) {
                            put("next_cursor", nextCursor)
                            put("has_more", true)
                        } else {
                            put("has_more", false)
                        }
                    }
                    is runtime.mobileagent.skills.tooling.WorkspaceFileStat -> {
                        put("relative_path", WorkspacePathPolicy.normalize(value.relativePath, false))
                        put("type", if (value.type == WorkspaceEntryType.DIRECTORY) "directory" else "file")
                        put("bytes", value.sizeBytes)
                        value.version?.let { put("version", it) }
                    }
                    is runtime.mobileagent.skills.tooling.WorkspaceText -> {
                        put("relative_path", WorkspacePathPolicy.normalize(value.relativePath, false))
                        put("text", value.text)
                        put("bytes", value.byteSize)
                        value.version?.let { put("version", it) }
                        put("offset_bytes", value.offsetBytes)
                        value.totalBytes?.let { put("total_bytes", it) }
                        put("eof", value.eof)
                        put("next_offset", value.nextOffsetBytes)
                    }
                    is WorkspaceMutation -> {
                        put("relative_path", WorkspacePathPolicy.normalize(value.relativePath, false))
                        put("type", if (value.type == WorkspaceEntryType.DIRECTORY) "directory" else "file")
                        put("bytes", value.byteSize)
                        value.version?.let { put("version", it) }
                    }
                    else -> put("value", value.toString())
                }
            }.toString()
        }.getOrNull() ?: return null
        return json.takeIf { it.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESULT_BYTES }
    }

    private fun parseResult(parsed: ParsedCall, result: WorkspaceResult<Any>): ToolExecution = when (result) {
        is WorkspaceResult.Success -> renderSuccess(parsed, result.value)?.let(ToolExecution::Value)
            ?: ToolExecution.Failed(ToolError(ToolErrorCode.INTERNAL_ERROR))
        is WorkspaceResult.Failure -> when (result.error.code) {
            ToolErrorCode.TIMEOUT,
            ToolErrorCode.SHELL_TIMED_OUT,
            ToolErrorCode.UNKNOWN_OUTCOME,
                -> ToolExecution.Unknown(result.error)
            else -> ToolExecution.Failed(result.error)
        }
    }

    private fun WorkspaceResult<Any>.toToolExecution(parsed: ParsedCall): ToolExecution = parseResult(parsed, this)

    private fun ApprovalDecision.toToolResult(): ToolResult = when (this) {
        is ApprovalDecision.Approved -> ToolResult.Invalid(ToolErrorCode.INTERNAL_ERROR.name)
        is ApprovalDecision.Required -> ToolResult.NeedsApproval
        is ApprovalDecision.Rejected -> ToolResult.Failure(ToolError(code))
    }

    private fun ApprovalDecision.toToolExecution(): ToolExecution = when (this) {
        is ApprovalDecision.Approved -> ToolExecution.Failed(ToolError(ToolErrorCode.INTERNAL_ERROR))
        is ApprovalDecision.Required -> ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED))
        is ApprovalDecision.Rejected -> ToolExecution.Failed(ToolError(code))
    }

    private fun ToolResult.toToolExecution(): ToolExecution = when (this) {
        is ToolResult.Value -> ToolExecution.Value(json)
        is ToolResult.Denied -> ToolExecution.Failed(ToolError(reason.toToolErrorCode(ToolErrorCode.CAPABILITY_DENIED)))
        is ToolResult.Invalid -> ToolExecution.Failed(ToolError(reason.toToolErrorCode(ToolErrorCode.INVALID_REQUEST)))
        is ToolResult.Failure -> ToolExecution.Failed(error)
        is ToolResult.UnknownOutcome -> ToolExecution.Unknown(ToolError(reason.toToolErrorCode(ToolErrorCode.UNKNOWN_OUTCOME)))
        ToolResult.NeedsApproval -> ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED))
    }

    private fun String.toToolErrorCode(fallback: ToolErrorCode): ToolErrorCode =
        runCatching { ToolErrorCode.valueOf(this) }.getOrDefault(fallback)

    private fun ToolExecution.toLegacyResult(): ToolResult = when (this) {
        is ToolExecution.Value -> ToolResult.Value(json)
        is ToolExecution.Failed -> when {
            error.code == ToolErrorCode.APPROVAL_REQUIRED -> ToolResult.NeedsApproval
            else -> ToolResult.Failure(error)
        }
        is ToolExecution.Unknown -> ToolResult.UnknownOutcome(error.code.name)
    }

    private fun modelKey(context: ToolExecutionContext, callId: String): ModelCallKey = ModelCallKey(
        agentId = context.agentId,
        snapshotId = context.snapshotId,
        sessionIdentity = context.sessionIdentity,
        taskIdentity = context.taskIdentity,
        callId = callId,
    )

    private fun resolveRequestId(callId: String): String = synchronized(lock) {
        val matches = requestByModelCall.asSequence()
            .filter { (key, _) -> key.callId == callId }
            .map { it.value }
            .distinct()
            .toList()
        if (matches.size == 1) matches.single() else callId
    }

    private fun toLegacySpec(spec: ToolSpec): LegacyToolSpec = LegacyToolSpec(
        name = spec.name,
        description = spec.description,
        parametersJson = spec.inputSchema,
        capability = spec.capability?.value.orEmpty(),
        sideEffect = spec.sideEffect,
    )

    private fun CapabilityId.toLegacyString(): String = value

    private fun normalizeOutputPath(path: String): String =
        if (path == ".") "" else WorkspacePathPolicy.normalize(path, false)

    /**
     * The pre-consumption ONCE rows this exact bound call already consumed through
     * its durable CAS consumer.  They are the only authority a later deferred
     * disclosure of the same execution may rely on, and every use revalidates
     * them against the live row (revision + workspace/path/binding/policy).
     */
    private data class BoundCall(
        val requestId: String,
        val call: ToolCall,
        val parsed: ParsedCall,
        val context: ToolExecutionContext,
        val binding: ApprovalBinding,
        val scope: ApprovalScope,
        val onceReceipts: MutableList<CapabilityGrant> = mutableListOf(),
        var approvalPending: Boolean = false,
        var pendingApprovalId: String? = null,
        var approvalGrant: ApprovalGrant? = null,
        var auditStarted: Boolean = false,
        var auditApprovalId: String? = null,
        var auditTerminal: Boolean = false,
        /**
         * Set when a deferred-disclosure revalidation failed.  It makes the denial
         * sticky, so a later restored authorization can never revive a payload that
         * was already observed to lose its authority.
         */
        var deferredInvalidated: Boolean = false,
        var result: ToolResult? = null,
    )

    private data class ParsedCall(
        val kind: WorkspaceOperation,
        val workspaceId: String,
        val relativePath: String,
        val destinationPath: String?,
        val text: String?,
        val patch: String?,
        val patchFormat: WorkspacePatchFormat,
        val maxBytes: Int,
        val maxEntries: Int,
        val cursor: String?,
        val offsetBytes: Long,
        val replaceExisting: Boolean,
        val expectedVersion: Long?,
        val capability: CapabilityId,
        val search: WorkspaceSearchRequest?,
        val canonicalArguments: String,
    ) {
        val isMutation: Boolean get() = kind.isMutation
    }

    private data class ModelCallKey(
        val agentId: String,
        val snapshotId: String,
        val sessionIdentity: String,
        val taskIdentity: String,
        val callId: String,
    )

    private fun operationForTool(name: String): WorkspaceOperation? = when (name) {
        WORKSPACE_LIST -> WorkspaceOperation.WORKSPACE_LIST
        FILE_LIST -> WorkspaceOperation.LIST
        FILE_STAT -> WorkspaceOperation.STAT
        FILE_READ_TEXT -> WorkspaceOperation.READ
        FILE_WRITE_TEXT -> WorkspaceOperation.WRITE
        FILE_CREATE_DIRECTORY -> WorkspaceOperation.CREATE_DIRECTORY
        FILE_DELETE -> WorkspaceOperation.DELETE
        FILE_GLOB -> WorkspaceOperation.GLOB
        FILE_GREP -> WorkspaceOperation.GREP
        // FILE_MOVE intentionally has no exposure mapping: file_move is never
        // produced in the model schema.  Its parse/dispatch code stays only to
        // fail closed for stale schemas.
        FILE_APPLY_PATCH -> WorkspaceOperation.APPLY_PATCH
        else -> null
    }

    /** Inverse of [operationForTool]; `file_move` keeps its retired input-only name. */
    private fun WorkspaceOperation.modelToolName(): String = when (this) {
        WorkspaceOperation.WORKSPACE_LIST -> WORKSPACE_LIST
        WorkspaceOperation.LIST -> FILE_LIST
        WorkspaceOperation.STAT -> FILE_STAT
        WorkspaceOperation.READ -> FILE_READ_TEXT
        WorkspaceOperation.WRITE -> FILE_WRITE_TEXT
        WorkspaceOperation.CREATE_DIRECTORY -> FILE_CREATE_DIRECTORY
        WorkspaceOperation.DELETE -> FILE_DELETE
        WorkspaceOperation.MOVE -> FILE_MOVE
        WorkspaceOperation.GLOB -> FILE_GLOB
        WorkspaceOperation.GREP -> FILE_GREP
        WorkspaceOperation.APPLY_PATCH -> FILE_APPLY_PATCH
    }

    /**
     * Closed operation set for this executor.
     *
     * [requiredOperations] lets one model-visible operation need more than one
     * capability on the exact same path.  Recursive search is the only composite
     * operation: `file_grep` must not run with only one half of `file.list` +
     * `file.read_text`, and it is never satisfied by grant scopes that only
     * intersect somewhere below the requested root.  The dependencies are
     * resolved in a property getter rather than an enum-initializer argument so
     * no entry ever depends on another entry's initialization order.
     */
    private enum class WorkspaceOperation(
        val capability: CapabilityId,
        val isMutation: Boolean,
        val backendMethod: String,
        val requestType: Class<*>,
        val auditOperation: WorkspaceAuditOperation,
    ) {
        WORKSPACE_LIST(
            CapabilityId(CapabilityId.WORKSPACE_ENUMERATE), false,
            "list", WorkspaceListRequest::class.java, WorkspaceAuditOperation.ENUMERATE,
        ),
        LIST(
            CapabilityId(CapabilityId.FILE_LIST), false,
            "list", WorkspaceListRequest::class.java, WorkspaceAuditOperation.LIST,
        ),
        STAT(
            CapabilityId(CapabilityId.FILE_STAT), false,
            "stat", WorkspaceStatRequest::class.java, WorkspaceAuditOperation.STAT,
        ),
        READ(
            CapabilityId(CapabilityId.FILE_READ_TEXT), false,
            "readText", WorkspaceReadTextRequest::class.java, WorkspaceAuditOperation.READ,
        ),
        WRITE(
            CapabilityId(CapabilityId.FILE_WRITE_TEXT), true,
            "writeText", WorkspaceWriteTextRequest::class.java, WorkspaceAuditOperation.WRITE,
        ),
        CREATE_DIRECTORY(
            CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY), true,
            "createDirectory", WorkspaceCreateDirectoryRequest::class.java, WorkspaceAuditOperation.MKDIR,
        ),
        DELETE(
            CapabilityId(CapabilityId.FILE_DELETE), true,
            "delete", WorkspaceDeleteRequest::class.java, WorkspaceAuditOperation.DELETE,
        ),
        MOVE(
            CapabilityId(CapabilityId.FILE_MOVE), true,
            "move", WorkspaceMoveRequest::class.java, WorkspaceAuditOperation.MOVE,
        ),
        APPLY_PATCH(
            CapabilityId("file.apply_patch"), true,
            "applyPatch", WorkspaceApplyPatchRequest::class.java, WorkspaceAuditOperation.WRITE,
        ),
        /** Recursive bounded glob: enumerate the subtree with `file.list`. */
        GLOB(
            CapabilityId(CapabilityId.FILE_LIST), false,
            "list", WorkspaceListRequest::class.java, WorkspaceAuditOperation.LIST,
        ),
        /**
         * Recursive bounded literal search: enumerate with `file.list` and read
         * with `file.read_text`. Both must hold on the whole searched subtree.
         */
        GREP(
            CapabilityId(CapabilityId.FILE_READ_TEXT), false,
            "readText", WorkspaceReadTextRequest::class.java, WorkspaceAuditOperation.READ,
        ),
        ;

        /** This operation plus every capability it composites. */
        val requiredOperations: List<WorkspaceOperation>
            get() = when (this) {
                GLOB -> listOf(LIST)
                GREP -> listOf(LIST, READ)
                else -> listOf(this)
            }

        /** True for the bounded recursive search operations. */
        val isSearch: Boolean
            get() = this == GLOB || this == GREP

        /**
         * Operations whose result may be disclosed through the deferred channel.
         *
         * Deferred disclosure is the first/continued page of a read-only result
         * this execution already produced; it must never become a way to publish
         * a mutation outcome (or a workspace enumeration) that the ordinary
         * replay contract would refuse.
         */
        val isReadOnlyDisclosable: Boolean
            get() = when (this) {
                LIST, STAT, READ, GLOB, GREP -> true
                else -> false
            }
    }

    private fun allowedKeys(kind: WorkspaceOperation): Set<String> = when (kind) {
        WorkspaceOperation.WORKSPACE_LIST -> emptySet()
        WorkspaceOperation.LIST -> setOf("workspaceId", "workspace_id", "relativePath", "relative_path", "maxEntries", "max_entries", "cursor")
        WorkspaceOperation.STAT -> setOf("workspaceId", "workspace_id", "relativePath", "relative_path")
        WorkspaceOperation.READ -> setOf("workspaceId", "workspace_id", "relativePath", "relative_path", "maxBytes", "max_bytes", "offsetBytes", "offset_bytes")
        WorkspaceOperation.WRITE -> setOf("workspaceId", "workspace_id", "relativePath", "relative_path", "text", "replace", "expectedVersion", "expected_version")
        WorkspaceOperation.CREATE_DIRECTORY, WorkspaceOperation.DELETE -> setOf("workspaceId", "workspace_id", "relativePath", "relative_path", "expectedVersion", "expected_version")
        WorkspaceOperation.MOVE -> setOf("workspaceId", "workspace_id", "relativePath", "relative_path", "destinationRelativePath", "destination_relative_path", "expectedVersion", "expected_version")
        WorkspaceOperation.APPLY_PATCH -> setOf("workspaceId", "workspace_id", "relativePath", "relative_path", "patch", "expectedVersion", "expected_version", "format")
        WorkspaceOperation.GLOB -> WorkspaceSearchProtocol.globKeys
        WorkspaceOperation.GREP -> WorkspaceSearchProtocol.grepKeys
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
    private fun JsonObject.stringAllowEmpty(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonObject.stringElement(key: String): Boolean = (this[key] as? JsonPrimitive)?.isString == true
    private fun JsonObject.longElement(key: String): Boolean = (this[key] as? JsonPrimitive)?.let { !it.isString && it.longOrNull != null } == true
    private fun JsonObject.optionalLong(key: String): Long? {
        if (!containsKey(key)) return null
        if (!longElement(key)) return null
        return long(key)
    }
    private fun JsonObject.booleanElement(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.let { !it.isString && it.contentOrNull?.toBooleanStrictOrNull() != null } == true

    private fun JsonObject.containsAny(first: String, second: String): Boolean =
        containsKey(first) || containsKey(second)

    private fun JsonObject.aliasElement(first: String, second: String): kotlinx.serialization.json.JsonElement? {
        val firstValue = this[first]
        val secondValue = this[second]
        require(firstValue == null || secondValue == null || firstValue == secondValue) {
            "Conflicting argument aliases"
        }
        return firstValue ?: secondValue
    }

    private fun JsonObject.aliasString(first: String, second: String): String? =
        (aliasElement(first, second) as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotBlank() }
            ?.content

    private fun JsonObject.aliasStringAllowEmpty(first: String, second: String): String? =
        (aliasElement(first, second) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.aliasStringElement(first: String, second: String): Boolean =
        !containsAny(first, second) || (aliasElement(first, second) as? JsonPrimitive)?.isString == true

    private fun JsonObject.aliasLong(first: String, second: String): Long? =
        (aliasElement(first, second) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.aliasLongElement(first: String, second: String): Boolean =
        !containsAny(first, second) ||
            (aliasElement(first, second) as? JsonPrimitive)?.let { !it.isString && it.longOrNull != null } == true

    private fun JsonObject.aliasBoolean(first: String, second: String): Boolean? =
        (aliasElement(first, second) as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.contentOrNull
            ?.toBooleanStrictOrNull()

    private fun JsonObject.aliasBooleanElement(first: String, second: String): Boolean =
        !containsAny(first, second) ||
            (aliasElement(first, second) as? JsonPrimitive)
                ?.let { !it.isString && it.contentOrNull?.toBooleanStrictOrNull() != null } == true

    companion object {
        /** camelCase/snake_case aliases of the search limits, in [WorkspaceSearchProtocol.request] order. */
        private val SEARCH_LIMIT_KEYS = listOf(
            "maxDepth" to "max_depth",
            "maxScannedEntries" to "max_scanned_entries",
            "maxScannedFiles" to "max_scanned_files",
            "maxScannedBytes" to "max_scanned_bytes",
            "maxResults" to "max_results",
            "maxMillis" to "max_millis",
        )
        const val TOOL_SCHEMA_VERSION = 3
        const val WORKSPACE_LIST = "workspace_list"
        const val FILE_LIST = "file_list"
        const val FILE_STAT = "file_stat"
        const val FILE_READ_TEXT = "file_read_text"
        const val FILE_WRITE_TEXT = "file_write_text"
        const val FILE_CREATE_DIRECTORY = "file_create_directory"
        const val FILE_DELETE = "file_delete"
        /**
         * Bounded recursive glob over an authorized subtree.  Enumerate-only: it
         * needs `file.list` on the whole searched subtree.
         */
        const val FILE_GLOB = "file_glob"
        /**
         * Bounded recursive literal text search.  Composite: it needs both
         * `file.list` and `file.read_text` on the whole searched subtree, and is
         * never a regular-expression engine.
         */
        const val FILE_GREP = "file_grep"
        /**
         * Input-only legacy name.  It is absent from [TOOL_SPECS]; a stale
         * model schema that still calls it fails closed with
         * [ToolErrorCode.OPERATION_UNAVAILABLE] before any backend is touched.
         */
        const val FILE_MOVE = "file_move"
        const val FILE_APPLY_PATCH = "file_apply_patch"
        /** Compatibility alias accepted on input; only [FILE_APPLY_PATCH] is exposed. */
        const val APPLY_PATCH = "apply_patch"
        const val MAX_READ_BYTES = 256 * 1024
        const val MAX_WRITE_BYTES = 256 * 1024
        const val MAX_PATCH_BYTES = MAX_WRITE_BYTES
        const val MAX_ENTRIES = 1_000
        const val DEFAULT_MAX_ENTRIES = 256
        const val MAX_RESULT_BYTES = 900 * 1024
        private const val PRIVILEGED_AUTHORITY_PREFIX = "authority:"

        /**
         * Probe used to prove whole-workspace (unscoped) authority.  The canonical
         * resolver matches a non-null path only for an unscoped grant, and the
         * empty relative path is the workspace root, so a path-bounded grant can
         * never satisfy it.
         */
        private const val UNSCOPED_GRANT_PROBE_PATH = ""
        private val TOOL_NAMES = setOf(
            WORKSPACE_LIST, FILE_LIST, FILE_STAT, FILE_READ_TEXT, FILE_WRITE_TEXT,
            FILE_CREATE_DIRECTORY, FILE_DELETE, FILE_MOVE, FILE_GLOB, FILE_GREP,
            FILE_APPLY_PATCH, APPLY_PATCH,
        )

        /** Typed fail-closed answer for a stale schema that still names [FILE_MOVE]. */
        private val FILE_MOVE_UNAVAILABLE = ToolError(
            ToolErrorCode.OPERATION_UNAVAILABLE,
            message = "file_move is not available in this run; use the supported explicit " +
                "steps instead: copy the entry first (file_copy where exposed, otherwise " +
                "file_read_text + file_write_text), then file_delete the source.",
        )

        private val FILE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512}}}"""
        private val VERSIONED_FILE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"expected_version":{"type":"integer","minimum":0}}}"""

        /**
         * The model-visible schema.  The bounded-search entries come from
         * [WorkspaceSearchProtocol], which owns their schema text, argument
         * validation and result rendering.
         */
        private val TOOL_SPECS = listOf(
            ToolSpec(WORKSPACE_LIST, "List authorized workspaces.", """{"type":"object","additionalProperties":false,"properties":{}}""", CapabilityId(CapabilityId.WORKSPACE_ENUMERATE), false, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_LIST, "List entries in an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":512},"max_entries":{"type":"integer","minimum":1,"maximum":1000},"cursor":{"type":"string","minLength":1,"maxLength":512}}}""", CapabilityId(CapabilityId.FILE_LIST), false, TOOL_SCHEMA_VERSION),
            WorkspaceSearchProtocol.globSpec,
            ToolSpec(FILE_STAT, "Read metadata for an authorized workspace entry.", FILE_SCHEMA, CapabilityId(CapabilityId.FILE_STAT), false, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_READ_TEXT, "Read a bounded UTF-8 chunk from an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"max_bytes":{"type":"integer","minimum":1,"maximum":262144},"offset_bytes":{"type":"integer","minimum":0}}}""", CapabilityId(CapabilityId.FILE_READ_TEXT), false, TOOL_SCHEMA_VERSION),
            WorkspaceSearchProtocol.grepSpec,
            ToolSpec(FILE_WRITE_TEXT, "Create or replace UTF-8 text in an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path","text"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"text":{"type":"string","maxLength":262144},"replace":{"type":"boolean"},"expected_version":{"type":"integer","minimum":0}}}""", CapabilityId(CapabilityId.FILE_WRITE_TEXT), true, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_CREATE_DIRECTORY, "Create a directory in an authorized workspace.", VERSIONED_FILE_SCHEMA, CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY), true, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_DELETE, "Delete one authorized workspace file or empty directory.", VERSIONED_FILE_SCHEMA, CapabilityId(CapabilityId.FILE_DELETE), true, TOOL_SCHEMA_VERSION),
            // file_move has no model-visible spec: the Android workspace
            // backends cannot honor a move.  Supported explicit steps are a
            // copy followed by file_delete; stale calls fail closed above.
            ToolSpec(FILE_APPLY_PATCH, "Apply a conditional text patch in an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path","patch","expected_version"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"patch":{"type":"string","minLength":1,"maxLength":262144},"expected_version":{"type":"integer","minimum":0},"format":{"type":"string","enum":["unified_diff","replace"]}}}""", CapabilityId("file.apply_patch"), true, TOOL_SCHEMA_VERSION),
        )

        private fun canonicalJson(root: JsonObject): String = root.toSortedMap().entries.joinToString(";") { (key, value) -> "$key=$value" }
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
