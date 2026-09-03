// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.Authority
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
        val sourceAllowed = resolver.revalidate(
            context,
            operation.capability,
            workspaceIdForGrant,
            pathForGrant,
            operation.isMutation,
        )
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

        // The resolver's canonical gate is the final grant/revision/lifetime
        // check. It runs immediately before dispatch, so a stale or ONCE grant
        // can never reach the backend.
        if (!authorizeForDispatch(context, operation)) {
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
            dispatch(operation, context).toToolExecution(operation)
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
            return ToolExecution.Failed(
                ToolError(ToolErrorCode.AUDIT_UNAVAILABLE, details = mapOf("audit_degraded" to "true")),
            )
        }
        return toolExecution
    }

    private suspend fun dispatch(parsed: ParsedCall, context: ToolExecutionContext): WorkspaceResult<Any> {
        if (parsed.kind == WorkspaceOperation.WORKSPACE_LIST) {
            @Suppress("UNCHECKED_CAST")
            val authorized = authorizedWorkspaces(context)
            if (authorized.isEmpty()) return WorkspaceResult.Failure(ToolError(ToolErrorCode.CAPABILITY_DENIED))
            return WorkspaceResult.Success(authorized.map { it.descriptor.forAgent() }) as WorkspaceResult<Any>
        }
        val registered = registry.registered(parsed.workspaceId)
            ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.WORKSPACE_NOT_FOUND))
        if (!workspaceOperationAvailable(context, registered, parsed.kind)) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.CAPABILITY_DENIED))
        }
        @Suppress("UNCHECKED_CAST")
        return when (parsed.kind) {
            WorkspaceOperation.WORKSPACE_LIST -> WorkspaceResult.Success(authorizedWorkspaces(context).map { it.descriptor.forAgent() }) as WorkspaceResult<Any>
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
        }
    }

    /** Build the model schema from the exact run snapshot, never from a global list. */
    private fun exposedSpecs(context: ToolExecutionContext): List<ToolSpec> =
        TOOL_SPECS.filter { spec ->
            val operation = operationForTool(spec.name) ?: return@filter false
            // A persisted selected authority remains part of the run schema
            // while its Binder/USB/Wi-Fi transport is temporarily offline.
            // Dispatch repeats the live-ready check and fails closed without
            // switching providers, matching the shell exposure contract.
            authorizedWorkspaces(context, operation, requireLiveReady = false).isNotEmpty()
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
    ): List<WorkspaceRegistry.RegisteredWorkspace> =
        registry.descriptors().mapNotNull { publicDescriptor ->
            registry.registered(publicDescriptor.id)
        }.filter { registered -> workspaceOperationAvailable(context, registered, operation, requireLiveReady) }

    private fun workspaceOperationAvailable(
        context: ToolExecutionContext,
        registered: WorkspaceRegistry.RegisteredWorkspace,
        operation: WorkspaceOperation,
        requireLiveReady: Boolean = true,
    ): Boolean {
        val descriptor = registered.descriptor
        if (!descriptor.enabled) return false
        if (descriptor.backendType == WorkspaceBackendType.PRIVILEGED) {
            if (!privilegedProviderConfigured(descriptor, context)) return false
            if (requireLiveReady && !privilegedProviderReady(descriptor, context)) return false
        }
        if (!backendSupports(registered.backend, operation)) return false
        if (!descriptor.readable && !operation.isMutation) return false
        if (operation.isMutation && !descriptor.writable) return false
        return resolver.revalidate(
            context = context,
            capability = operation.capability,
            workspaceId = descriptor.id,
            path = null,
            write = operation.isMutation,
        )
    }

    /** Exact path checks are repeated after approval and immediately before dispatch. */
    private fun exactPathAuthorization(context: ToolExecutionContext, operation: ParsedCall): Boolean {
        if (operation.kind == WorkspaceOperation.WORKSPACE_LIST) return true
        val workspaceId = operation.workspaceId
        if (!resolver.revalidate(
                context = context,
                capability = operation.capability,
                workspaceId = workspaceId,
                path = operation.relativePath,
                write = operation.isMutation,
            )
        ) return false
        return operation.destinationPath?.let { destination ->
            resolver.revalidate(
                context = context,
                capability = operation.capability,
                workspaceId = workspaceId,
                path = destination,
                write = operation.isMutation,
            )
        } ?: true
    }

    /**
     * Resolve the exact workspace/path scope immediately before dispatch.  A
     * MOVE has two paths but is one logical operation: the same one-shot grant
     * may authorize both paths, so its durable consumer is invoked at most once
     * per invocation while every path still receives an exact resolver check.
     */
    private fun authorizeForDispatch(context: ToolExecutionContext, operation: ParsedCall): Boolean {
        val consumedOnceGrantIds = mutableSetOf<String>()
        val consumeOnce: (CapabilityGrant) -> Boolean = { grant ->
            if (grant.grantId in consumedOnceGrantIds) {
                true
            } else {
                onceGrantConsumer(grant).also { accepted ->
                    if (accepted) consumedOnceGrantIds += grant.grantId
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
        for (workspaceId in workspaceIds) {
            for (path in paths) {
                if (resolver.authorizeForDispatch(
                        context = context,
                        capability = operation.capability,
                        consumer = consumeOnce,
                        workspaceId = workspaceId.takeUnless { it.isBlank() },
                        path = path,
                        write = operation.isMutation,
                    ) == DispatchAuthorization.DENIED
                ) return false
            }
        }
        return true
    }

    /**
     * The shared backend interface has default unsupported methods.  A
     * capability bit is therefore considered real only when its exact typed
     * request method is implemented by the adapter as well.
     */
    private fun backendSupports(
        backend: WorkspaceBackend,
        operation: WorkspaceOperation,
    ): Boolean {
        if (operation.capability !in backend.capabilities) return false
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
                grantRevision = resolver.liveGrantRevision(
                    context = context,
                    capability = parsed.capability,
                    workspaceId = parsed.workspaceId.takeIf { it.isNotBlank() },
                    path = parsed.relativePath.takeUnless { it.isEmpty() },
                    write = parsed.isMutation,
                ) ?: 0L,
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
            registry.validatePath(rawPath, kind == WorkspaceOperation.LIST)
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
            canonicalArguments = canonicalJson(root).ifEmpty { "{}" },
        )
    }.getOrNull()

    private fun renderSuccess(parsed: ParsedCall, value: Any): String? {
        val json = runCatching {
            buildJsonObject {
                put("success", true)
                if (parsed.workspaceId.isNotBlank()) put("workspace_id", parsed.workspaceId)
                when (value) {
                    is List<*> -> putJsonArray("workspaces") {
                        value.filterIsInstance<runtime.mobileagent.skills.tooling.WorkspaceDescriptor>().forEach { descriptor ->
                            add(buildJsonObject {
                                put("workspace_id", descriptor.id)
                                put("display_name", descriptor.displayName)
                                put("readable", descriptor.readable)
                                put("writable", descriptor.writable)
                                put("enabled", descriptor.enabled)
                            })
                        }
                    }
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

    private data class BoundCall(
        val requestId: String,
        val call: ToolCall,
        val parsed: ParsedCall,
        val context: ToolExecutionContext,
        val binding: ApprovalBinding,
        val scope: ApprovalScope,
        var approvalPending: Boolean = false,
        var pendingApprovalId: String? = null,
        var approvalGrant: ApprovalGrant? = null,
        var auditStarted: Boolean = false,
        var auditApprovalId: String? = null,
        var auditTerminal: Boolean = false,
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
        FILE_MOVE -> WorkspaceOperation.MOVE
        FILE_APPLY_PATCH -> WorkspaceOperation.APPLY_PATCH
        else -> null
    }

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

    companion object {
        const val TOOL_SCHEMA_VERSION = 3
        const val WORKSPACE_LIST = "workspace_list"
        const val FILE_LIST = "file_list"
        const val FILE_STAT = "file_stat"
        const val FILE_READ_TEXT = "file_read_text"
        const val FILE_WRITE_TEXT = "file_write_text"
        const val FILE_CREATE_DIRECTORY = "file_create_directory"
        const val FILE_DELETE = "file_delete"
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
        private val TOOL_NAMES = setOf(
            WORKSPACE_LIST, FILE_LIST, FILE_STAT, FILE_READ_TEXT, FILE_WRITE_TEXT,
            FILE_CREATE_DIRECTORY, FILE_DELETE, FILE_MOVE, FILE_APPLY_PATCH, APPLY_PATCH,
        )

        private val FILE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512}}}"""
        private val VERSIONED_FILE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"expected_version":{"type":"integer","minimum":0}}}"""
        private val TOOL_SPECS = listOf(
            ToolSpec(WORKSPACE_LIST, "List authorized workspaces.", """{"type":"object","additionalProperties":false,"properties":{}}""", CapabilityId(CapabilityId.WORKSPACE_ENUMERATE), false, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_LIST, "List entries in an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":512},"max_entries":{"type":"integer","minimum":1,"maximum":1000},"cursor":{"type":"string","minLength":1,"maxLength":512}}}""", CapabilityId(CapabilityId.FILE_LIST), false, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_STAT, "Read metadata for an authorized workspace entry.", FILE_SCHEMA, CapabilityId(CapabilityId.FILE_STAT), false, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_READ_TEXT, "Read a bounded UTF-8 chunk from an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"max_bytes":{"type":"integer","minimum":1,"maximum":262144},"offset_bytes":{"type":"integer","minimum":0}}}""", CapabilityId(CapabilityId.FILE_READ_TEXT), false, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_WRITE_TEXT, "Create or replace UTF-8 text in an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path","text"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"text":{"type":"string","maxLength":262144},"replace":{"type":"boolean"},"expected_version":{"type":"integer","minimum":0}}}""", CapabilityId(CapabilityId.FILE_WRITE_TEXT), true, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_CREATE_DIRECTORY, "Create a directory in an authorized workspace.", VERSIONED_FILE_SCHEMA, CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY), true, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_DELETE, "Delete one authorized workspace file or empty directory.", VERSIONED_FILE_SCHEMA, CapabilityId(CapabilityId.FILE_DELETE), true, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_MOVE, "Move an entry within an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path","destination_relative_path"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"destination_relative_path":{"type":"string","minLength":1,"maxLength":512},"expected_version":{"type":"integer","minimum":0}}}""", CapabilityId(CapabilityId.FILE_MOVE), true, TOOL_SCHEMA_VERSION),
            ToolSpec(FILE_APPLY_PATCH, "Apply a conditional text patch in an authorized workspace.", """{"type":"object","additionalProperties":false,"required":["workspace_id","relative_path","patch","expected_version"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","minLength":1,"maxLength":512},"patch":{"type":"string","minLength":1,"maxLength":262144},"expected_version":{"type":"integer","minimum":0},"format":{"type":"string","enum":["unified_diff","replace"]}}}""", CapabilityId("file.apply_patch"), true, TOOL_SCHEMA_VERSION),
        )

        private fun canonicalJson(root: JsonObject): String = root.toSortedMap().entries.joinToString(";") { (key, value) -> "$key=$value" }
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
