// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.ApprovalBinding
import runtime.mobileagent.skills.tooling.AuditedShellExecutor
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.DangerousModeExposure
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.skills.tooling.HighRiskDetector
import runtime.mobileagent.skills.tooling.InternalRequestIds
import runtime.mobileagent.skills.tooling.ShellRiskAssessment
import runtime.mobileagent.skills.tooling.ShellAuditSink
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecResult
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.skills.tooling.ShellLimits
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.ToolSpec

fun interface ShellRiskClassifier {
    fun classify(command: String): ShellRiskAssessment
}

/**
 * Shared detector is the base classification source.  The Android adapter adds
 * the platform-sensitive read forms that are easy to miss in a portable
 * lexical detector (logcat/dumpsys/proc).  It still only changes confirmation
 * classification; it never rewrites or rejects the command.
 */
val DefaultShellRiskClassifier: ShellRiskClassifier = ShellRiskClassifier { command ->
    val assessment = HighRiskDetector.assess(command)
    val sensitiveRead = Regex("(?i)(^|\\s)(?:logcat|dumpsys)(?:\\s|$)").containsMatchIn(command) ||
        Regex("(?i)(?:^|[\\s/])proc(?:/|\\s|$)").containsMatchIn(command) ||
        Regex("(?i)(?:^|\\s)cat(?:\\s+[^\\n]*?/)?/proc(?:/|\\s|$)").containsMatchIn(command)
    if (!sensitiveRead) assessment else assessment.copy(
        level = runtime.mobileagent.skills.tooling.ShellRiskLevel.HIGH,
        reasons = assessment.reasons + runtime.mobileagent.skills.tooling.ShellRiskReason.SENSITIVE_READ,
    )
}

/**
 * Model-facing one-shot shell adapter.  Provider routing is determined only by
 * AuthorityManager's selected authority.  A disconnected selected provider
 * remains exposed for this run but invocation fails with a temporary-unavailable
 * error; another provider is never searched.
 */
class ShellToolExecutor(
    private val authorityManager: AuthorityManager,
    private val dangerousModeManager: DangerousModeManager,
    private val approvalEngine: ApprovalEngine,
    private val contextProvider: () -> ToolExecutionContext,
    backends: Map<ElevatedAuthority, ShellExecutor>,
    private val resolver: EffectiveCapabilityResolver = EffectiveCapabilityResolver(),
    auditSink: ShellAuditSink? = null,
    private val auditFuse: runtime.mobileagent.skills.tooling.AuditDegradedFuse = runtime.mobileagent.skills.tooling.AuditDegradedFuse(),
    private val classifier: ShellRiskClassifier = DefaultShellRiskClassifier,
    private val clock: ToolingClock = SYSTEM_TOOLING_CLOCK,
    private val maxConcurrent: Int = 1,
    /**
     * Runtime-owned CAS for a canonical ONCE capability grant.  A missing
     * consumer is deliberately fail-closed; durable grants never invoke it.
     */
    private val onceGrantConsumer: (CapabilityGrant) -> Boolean = { false },
) : ToolExecutor {
    private val runContext = contextProvider()
    private val selectedAtRunStart = authorityManager.selectedAuthorityForExposure()
    private val runMode = dangerousModeManager.policy()
    private val selectedBackend: ShellExecutor? = selectedAtRunStart?.let { backends[it] }
    private val active = AtomicInteger(0)
    private val lock = Any()
    private val callsByRequest = linkedMapOf<String, BoundShellCall>()
    private val requestByModelCall = linkedMapOf<ModelCallKey, String>()
    private val requestAlias = linkedMapOf<String, String>()
    /** Correlation exists only for the duration of a backend dispatch. */
    private val auditCorrelation = linkedMapOf<String, AuditCorrelation>()
    private val auditedBackend: ShellExecutor? = selectedBackend?.let { backend ->
        auditSink?.let {
            AuditedShellExecutor(
                delegate = backend,
                audit = it,
                fuse = auditFuse,
                nowMs = clock::nowMillis,
                requestIdProvider = { request ->
                    synchronized(lock) { auditCorrelation[request.requestId]?.requestId ?: request.requestId }
                },
                approvalIdProvider = { request ->
                    synchronized(lock) { auditCorrelation[request.requestId]?.approvalId }
                },
            )
        }
    }

    /** Immutable per-run exposure decision. */
    val exposure = DangerousModeExposure.decide(
        mode = runMode,
        effectiveCapabilities = effectiveCapabilitiesAtRunStart(),
        selectedAuthority = selectedAtRunStart,
        authorityState = selectedAtRunStart?.let { authorityManager.state.value.statuses[it] },
    )

    val toolingSpecs: List<ToolSpec> = Collections.unmodifiableList(if (exposure.exposed) listOf(SHELL_SPEC) else emptyList())
    override val specs: List<LegacyToolSpec> = Collections.unmodifiableList(toolingSpecs.map { spec ->
        LegacyToolSpec(spec.name, spec.description, spec.inputSchema, spec.capability?.value.orEmpty(), spec.sideEffect)
    })

    override suspend fun invoke(call: ToolCall): ToolResult = invoke(call, contextProvider())

    suspend fun invoke(call: ToolCall, context: ToolExecutionContext): ToolResult =
        invoke(call, context, InternalRequestIds.new())

    private suspend fun invoke(
        call: ToolCall,
        context: ToolExecutionContext,
        requestId: String,
    ): ToolResult {
        val key = modelKey(context, call.callId)
        // Keep duplicate detection and request registration in one critical
        // section.  A caller can suspend as soon as invokeBound starts the
        // provider dispatch; the internal request must already be reserved by
        // then, otherwise two concurrent deliveries can both execute it.
        val bound: BoundShellCall
        synchronized(lock) {
            requestByModelCall[key]?.let { existingRequestId ->
                val old = callsByRequest[existingRequestId]
                return when {
                    old == null -> ToolResult.UnknownOutcome(ToolErrorCode.CALL_ID_REPLAY.name)
                    old.call != call -> ToolResult.Invalid(ToolErrorCode.CALL_ID_REPLAY.name)
                    old.result != null -> old.result!!
                    old.approvalPending -> ToolResult.NeedsApproval
                    else -> ToolResult.UnknownOutcome(ToolErrorCode.CALL_ID_REPLAY.name)
                }
            }

            // Parsing and binding are synchronous, so doing them while holding
            // this lock still reserves the Runtime request before any suspend
            // point and does not hold the lock over provider I/O.
            if (!exposure.exposed) return ToolResult.Denied(exposure.reason.name)
            val parsed = parse(call) ?: return ToolResult.Invalid(ToolErrorCode.INVALID_REQUEST.name)
            bound = bind(call, parsed, context, requestId)
            requestByModelCall[key] = bound.requestId
            callsByRequest[bound.requestId] = bound
            requestAlias[bound.request.requestId] = bound.requestId
            requestAlias[bound.requestId] = bound.requestId
        }
        val result = invokeBound(bound, context)
        synchronized(lock) { bound.result = result }
        return result
    }

    suspend fun invoke(invocation: ToolInvocation, context: ToolExecutionContext): ToolExecution {
        if (invocation.agentId != context.agentId || invocation.snapshotId != context.snapshotId) {
            return ToolExecution.Failed(ToolError(ToolErrorCode.SNAPSHOT_STALE))
        }
        // Preserve the Runtime-created invocation id as the approval/call map
        // key.  ShellExecRequest owns a separate opaque transport id.
        val result = invoke(
            ToolCall(invocation.callId, invocation.name, invocation.argumentsJson),
            context,
            invocation.requestId,
        )
        return result.toToolExecution()
    }

    suspend fun invoke(invocation: ToolInvocation): ToolExecution = invoke(invocation, contextProvider())

    /** Cancel only the selected provider's active one-shot request. */
    suspend fun cancel(requestId: String): Boolean {
        val shellRequestId = synchronized(lock) { requestAlias[requestId] ?: requestId }
        val bound = synchronized(lock) { callsByRequest[shellRequestId] } ?: return false
        val backend = auditedBackend ?: return false
        return runCatching { backend.cancel(bound.request.requestId) }.getOrDefault(false)
    }

    override suspend fun approve(callId: String): ToolResult {
        // The legacy approval seam has no run context. Resolve a model call
        // only when it is unambiguous; a duplicate model id across runs must
        // not select an arbitrary request.
        val requestId = resolveRequestId(callId)
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolResult.Invalid(ToolErrorCode.INVALID_REQUEST.name)
        val current = currentBinding(bound, contextProvider())
        val currentScope = currentScope(bound, contextProvider())
        val decision = approvalEngine.approve(requestId, current, currentScope, GrantLifetime.ONCE)
        if (decision !is ApprovalDecision.Approved) return decision.toToolResult()
        bound.approvalPending = false
        bound.approvalGrant = decision.grant
        val result = invokeAfterApproval(bound, contextProvider())
        synchronized(lock) { bound.result = result }
        return result
    }

    /** Explicitly deny a pending approval using either model call or request id. */
    override suspend fun reject(callId: String): ToolResult {
        val requestId = resolveRequestId(callId)
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolResult.Invalid(ToolErrorCode.INVALID_REQUEST.name)
        val result = reject(requestId, contextProvider()).toLegacyResult()
        synchronized(lock) { bound.result = result }
        return result
    }

    /** Explicitly expire a pending approval using either model call or request id. */
    override suspend fun expire(callId: String): ToolResult {
        val requestId = resolveRequestId(callId)
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolResult.Invalid(ToolErrorCode.INVALID_REQUEST.name)
        val result = expire(requestId, contextProvider()).toLegacyResult()
        synchronized(lock) { bound.result = result }
        return result
    }

    suspend fun approve(requestId: String, context: ToolExecutionContext = contextProvider()): ToolExecution {
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolExecution.Failed(ToolError(ToolErrorCode.INVALID_REQUEST))
        val current = currentBinding(bound, context)
        val scope = currentScope(bound, context)
        val decision = approvalEngine.approve(requestId, current, scope, GrantLifetime.ONCE)
        if (decision !is ApprovalDecision.Approved) return decision.toToolExecution()
        bound.approvalPending = false
        bound.approvalGrant = decision.grant
        val result = invokeAfterApprovalExecution(bound, context)
        synchronized(lock) { bound.result = result.toLegacyResult() }
        return result
    }

    /** Explicitly deny a pending approval keyed by Runtime's internal id. */
    suspend fun reject(requestId: String, context: ToolExecutionContext = contextProvider()): ToolExecution {
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolExecution.Failed(ToolError(ToolErrorCode.INVALID_REQUEST))
        val decision = approvalEngine.reject(requestId)
        bound.approvalPending = false
        val result = decision.toToolExecution()
        synchronized(lock) { bound.result = result.toLegacyResult() }
        return result
    }

    /** Explicitly expire a pending approval keyed by Runtime's internal id. */
    suspend fun expire(requestId: String, context: ToolExecutionContext = contextProvider()): ToolExecution {
        val bound = synchronized(lock) { callsByRequest[requestId] }
            ?: return ToolExecution.Failed(ToolError(ToolErrorCode.INVALID_REQUEST))
        val decision = approvalEngine.expire(requestId)
        bound.approvalPending = false
        val result = decision.toToolExecution()
        synchronized(lock) { bound.result = result.toLegacyResult() }
        return result
    }

    private suspend fun invokeBound(bound: BoundShellCall, context: ToolExecutionContext): ToolResult {
        val currentMode = dangerousModeManager.policy()
        if (currentMode == DangerousMode.DISABLED) return ToolResult.Denied(ToolErrorCode.DANGEROUS_MODE_DISABLED.name)
        if (!resolver.revalidate(context, CapabilityId(CapabilityId.SHELL_EXECUTE))) {
            return ToolResult.Denied(ToolErrorCode.SHELL_CAPABILITY_DENIED.name)
        }
        val selected = authorityManager.selectedAuthorityForExecution()
        val selectedState = selected?.let { authorityManager.state.value.statuses[it] }
        if (selected == null || selected != selectedAtRunStart) return ToolResult.Denied(ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED.name)
        if (selectedState == null || !selectedState.isConfiguredForSelection) return ToolResult.Denied(ToolErrorCode.AUTHORITY_NOT_GRANTED.name)
        if (selectedState.availability == Availability.UNSUPPORTED || !selectedState.isReady) {
            return ToolResult.Denied(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE.name)
        }
        if (selectedBackend == null) {
            return ToolResult.Denied(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE.name)
        }
        val requiresApproval = currentMode == DangerousMode.ENABLED_CONFIRM_HIGH_RISK && bound.risk.requiresConfirmation
        if (!bound.approvalPending && bound.approvalGrant == null) {
            val request = approvalEngine.request(
                requestId = bound.requestId,
                modelCallId = bound.call.callId,
                binding = bound.binding,
                scope = bound.scope,
                suggestedLifetime = GrantLifetime.ONCE,
            )
            if (request.grant != null) {
                bound.approvalGrant = request.grant
            } else if (request.pending != null) {
                if (requiresApproval) {
                    bound.approvalPending = true
                    return ToolResult.NeedsApproval
                }
                // Autonomous and low-risk calls still receive an in-memory
                // approval identity for audit correlation, but no user-facing
                // confirmation is required.  GrantLifetime does not make this
                // pending command durable.
                val automatic = approvalEngine.approve(
                    requestId = bound.requestId,
                    expectedBinding = bound.binding,
                    scope = bound.scope,
                    lifetime = GrantLifetime.ONCE,
                )
                if (automatic !is ApprovalDecision.Approved) return automatic.toToolResult()
                bound.approvalGrant = automatic.grant
            } else {
                return ToolResult.Denied(request.reasonCode?.name ?: ToolErrorCode.APPROVAL_DENIED.name)
            }
        }
        val execution = invokeAfterApprovalExecution(bound, context, approvalRequired = true)
        val result = execution.toLegacyResult()
        return result
    }

    private suspend fun invokeAfterApproval(bound: BoundShellCall, context: ToolExecutionContext): ToolResult =
        invokeAfterApprovalExecution(bound, context).toLegacyResult()

    private suspend fun invokeAfterApprovalExecution(
        bound: BoundShellCall,
        context: ToolExecutionContext,
        approvalRequired: Boolean = true,
    ): ToolExecution {
        if (!resolver.revalidate(context, CapabilityId(CapabilityId.SHELL_EXECUTE))) {
            return ToolExecution.Failed(ToolError(ToolErrorCode.SHELL_CAPABILITY_DENIED))
        }
        val selected = authorityManager.selectedAuthorityForExecution()
        val selectedState = selected?.let { authorityManager.state.value.statuses[it] }
        if (selected == null || selected != selectedAtRunStart || selectedState == null) {
            return ToolExecution.Failed(ToolError(ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED))
        }
        if (!selectedState.isReady) return ToolExecution.Failed(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE))
        if (selectedBackend == null) return ToolExecution.Failed(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE))
        val currentBinding = currentBinding(bound, context)
        val currentScope = currentScope(bound, context)
        if (approvalRequired) {
            val grant = bound.approvalGrant ?: return ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED))
            val decision = approvalEngine.consume(
                grant = grant,
                currentBinding = currentBinding,
                currentScope = currentScope,
                currentGrantRevision = currentScope.grantRevision,
                currentPolicyVersion = currentScope.policyVersion,
            )
            if (decision !is ApprovalDecision.Approved) return decision.toToolExecution()
        }
        val dispatchAuthorization = resolver.authorizeForDispatch(
            context = context,
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
            consumer = onceGrantConsumer,
        )
        if (dispatchAuthorization == DispatchAuthorization.DENIED) {
            // This is the final live grant/revalidation gate.  It must run
            // after approval binding/consume but before the selected backend
            // can observe the request, including autonomous shell calls.
            return ToolExecution.Failed(ToolError(ToolErrorCode.SHELL_CAPABILITY_DENIED))
        }
        if (auditFuse.isOpen) return ToolExecution.Failed(ToolError(ToolErrorCode.AUDIT_FUSE_OPEN))
        val backend = auditedBackend
            ?: return ToolExecution.Failed(ToolError(ToolErrorCode.AUDIT_UNAVAILABLE))
        if (active.incrementAndGet() > maxConcurrent) {
            active.decrementAndGet()
            return ToolExecution.Failed(ToolError(ToolErrorCode.SHELL_EXECUTION_FAILED, details = mapOf("reason" to "concurrency_limit")))
        }
        try {
            val started = clock.nowMillis()
            synchronized(lock) {
                auditCorrelation[bound.request.requestId] = AuditCorrelation(
                    requestId = bound.requestId,
                    approvalId = bound.approvalGrant?.approvalId,
                )
            }
            val result = try {
                withTimeout(bound.request.timeoutMs) { backend.execute(bound.request) }
            } catch (_: TimeoutCancellationException) {
                backend.cancel(bound.request.requestId)
                ShellExecResult.unknownOutcome(bound.request, clock.nowMillis() - started)
            } catch (_: CancellationException) {
                backend.cancel(bound.request.requestId)
                ShellExecResult.unknownOutcome(bound.request, clock.nowMillis() - started)
            } catch (_: Throwable) {
                ShellExecResult.failed(bound.request)
            }
            // STARTED has already been accepted and the provider may have run
            // the command.  A completion-audit failure therefore cannot be
            // reported as an ordinary retryable failure: preserve the
            // externally ambiguous outcome and the replay cache entry.
            if (auditFuse.isOpen) return ToolExecution.Unknown(ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
            return result.toToolExecution()
        } finally {
            synchronized(lock) { auditCorrelation.remove(bound.request.requestId) }
            active.decrementAndGet()
        }
    }

    private fun bind(
        call: ToolCall,
        parsed: ParsedShellCall,
        context: ToolExecutionContext,
        requestId: String,
    ): BoundShellCall {
        val selected = selectedAtRunStart ?: Authority.NONE
        val mode = dangerousModeManager.policy()
        val skillId = context.skillId?.takeIf { context.trustedSkillEnvelope }
        val request = ShellExecRequest.fromRuntime(
            callId = call.callId,
            command = parsed.command,
            cwd = parsed.cwd,
            limits = ShellLimits(parsed.timeoutMs, parsed.maxOutputBytes).clamped(maxOutputBytes = MAX_OUTPUT_BYTES),
            agentId = context.agentId,
            snapshotId = context.snapshotId,
            selectedAuthority = selected,
            dangerousMode = mode,
            skillId = skillId,
            toolSchemaVersion = TOOL_SCHEMA_VERSION,
            policyVersion = context.policyVersion.toString(),
            configSnapshotHash = context.configSnapshotHash,
            sessionIdentity = context.sessionIdentity,
            trustedSkillEnvelope = skillId != null,
            skillRevision = context.skillRevision,
        )
        val binding = ApprovalBinding.fromRequest(request, skillRevision = context.skillRevision).copy(
            // ApprovalEngine keys pending state by the Runtime invocation id,
            // not by the transport request id generated by the shared port.
            requestId = requestId,
        )
        val scope = currentScope(
            binding = binding,
            context = context,
            command = parsed.command,
        )
        return BoundShellCall(
            // The Runtime invocation id is the approval/call identity; the
            // ShellExecRequest id remains a private backend transport id.
            requestId = requestId,
            call = call,
            context = context,
            parsed = parsed,
            request = request,
            binding = binding,
            scope = scope,
            risk = classifier.classify(parsed.command),
        )
    }

    private fun currentBinding(bound: BoundShellCall, context: ToolExecutionContext): ApprovalBinding = bound.binding.copy(
        requestId = bound.binding.requestId,
        callId = bound.call.callId,
        agentId = context.agentId,
        snapshotId = context.snapshotId,
        skillId = context.skillId?.takeIf { context.trustedSkillEnvelope },
        skillRevision = context.skillRevision,
        selectedAuthority = authorityManager.selectedAuthorityForExecution() ?: Authority.NONE,
        dangerousMode = dangerousModeManager.policy(),
        policyVersion = context.policyVersion,
        configSnapshotHash = context.configSnapshotHash,
        sessionIdentity = context.sessionIdentity,
    )

    private fun currentScope(bound: BoundShellCall, context: ToolExecutionContext): ApprovalScope = currentScope(
        currentBinding(bound, context), context, bound.parsed.command,
    )

    private fun currentScope(binding: ApprovalBinding, context: ToolExecutionContext, command: String): ApprovalScope = ApprovalScope(
        capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
        grantRevision = resolver.liveGrantRevision(
            context = context,
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
        ) ?: 0L,
        policyVersion = context.policyVersion,
        taskId = context.taskIdentity.takeIf { it.isNotBlank() },
        sessionId = context.sessionIdentity,
    )

    private fun parse(call: ToolCall): ParsedShellCall? = runCatching {
        if (call.callId.isBlank() || call.name != SHELL_EXEC) return null
        val root = Json.parseToJsonElement(call.argumentsJson) as? JsonObject ?: return null
        val allowed = setOf("command", "cwd", "timeout_ms", "max_output_bytes")
        if (root.keys.any { it !in allowed }) return null
        val command = (root["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() && it.length <= MAX_COMMAND_LENGTH && !it.contains('\u0000') } ?: return null
        val cwd = when (val value = root["cwd"]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                if (!value.isString || value.content.isBlank()) return null
                value.content
            }
            else -> return null
        }
        if (cwd?.contains('\u0000') == true || cwd?.length ?: 0 > MAX_CWD_LENGTH) return null
        val timeoutMs = when (val value = root["timeout_ms"]) {
            null -> ShellLimits.DEFAULT_TIMEOUT_MS
            is JsonPrimitive -> {
                if (value.isString) return null
                value.longOrNull?.also { require(it in 1..ShellLimits.MAX_TIMEOUT_MS) } ?: return null
            }
            else -> return null
        }
        val maxOutputBytes = when (val value = root["max_output_bytes"]) {
            null -> MAX_OUTPUT_BYTES
            is JsonPrimitive -> {
                if (value.isString) return null
                value.longOrNull?.also { require(it in 1..MAX_OUTPUT_BYTES) } ?: return null
            }
            else -> return null
        }
        ParsedShellCall(command, cwd, timeoutMs, maxOutputBytes)
    }.getOrNull()

    private fun effectiveCapabilitiesAtRunStart(): Set<CapabilityId> {
        val canonicalRowsPresent = runContext.canonicalGrants.isNotEmpty() ||
            runContext.snapshotGrantBindings.isNotEmpty()
        if (!canonicalRowsPresent) return emptySet()

        val now = clock.nowMillis()
        val accepted = runContext.canonicalGrants.filter { grant ->
            grant.agentId == runContext.agentId &&
                grant.revision > 0 &&
                !grant.revoked &&
                grant.policyVersion == runContext.policyVersion &&
                (grant.expiresAt.isNullOrBlank() || runCatching { java.time.Instant.parse(grant.expiresAt).toEpochMilli() > now }.getOrDefault(false)) &&
                runContext.snapshotGrantBindings.any { binding ->
                    binding.snapshotId == runContext.snapshotId &&
                        binding.grantId == grant.grantId &&
                        binding.capability == grant.capability &&
                        binding.policyVersion == runContext.policyVersion &&
                        binding.workspaceId == grant.workspaceId &&
                        binding.pathScope == grant.pathScope
                }
        }
        val agent = accepted.filter { it.skillInstallId == null }.map { it.capability }.toSet()
        val effective = if (runContext.skillId == null) {
            agent
        } else {
            val skill = accepted.filter { it.skillInstallId == runContext.skillId }
                .map { it.capability }.toSet()
            agent intersect skill
        }
        return if (runContext.effectiveCapabilities.isEmpty()) effective
        else effective intersect runContext.effectiveCapabilities
    }

    private fun ShellExecResult.toToolExecution(): ToolExecution {
        val json = buildJsonObject {
            put("success", success)
            put("status", status.name)
            exitCode?.let { put("exit_code", it) }
            put("stdout", stdout)
            put("stderr", stderr)
            put("timed_out", timedOut)
            put("cancelled", cancelled)
            put("stdout_truncated", stdoutTruncated)
            put("stderr_truncated", stderrTruncated)
            authority?.let { put("authority", it.name) }
            put("duration_ms", durationMs)
            error?.let { put("error", it.code.name) }
        }.toString()
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_RESULT_BYTES) {
            return ToolExecution.Failed(ToolError(ToolErrorCode.SHELL_OUTPUT_TRUNCATED))
        }
        return when (status) {
            ShellExecutionStatus.UNKNOWN_OUTCOME -> ToolExecution.Unknown(error ?: ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
            ShellExecutionStatus.SUCCEEDED -> ToolExecution.Value(json)
            else -> ToolExecution.Failed(error ?: ToolError(ToolErrorCode.SHELL_EXECUTION_FAILED))
        }
    }

    private fun ToolResult.toToolExecution(): ToolExecution = when (this) {
        is ToolResult.Value -> ToolExecution.Value(json)
        is ToolResult.Denied -> ToolExecution.Failed(ToolError(reason.toToolErrorCode(ToolErrorCode.CAPABILITY_DENIED)))
        is ToolResult.Invalid -> ToolExecution.Failed(ToolError(reason.toToolErrorCode(ToolErrorCode.INVALID_REQUEST)))
        is ToolResult.UnknownOutcome -> ToolExecution.Unknown(ToolError(reason.toToolErrorCode(ToolErrorCode.UNKNOWN_OUTCOME)))
        ToolResult.NeedsApproval -> ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED))
    }

    private fun String.toToolErrorCode(fallback: ToolErrorCode): ToolErrorCode =
        runCatching { ToolErrorCode.valueOf(this) }.getOrDefault(fallback)

    private fun ToolExecution.toLegacyResult(): ToolResult = when (this) {
        is ToolExecution.Value -> ToolResult.Value(json)
        is ToolExecution.Failed -> if (error.code == ToolErrorCode.APPROVAL_REQUIRED) ToolResult.NeedsApproval else ToolResult.Denied(error.code.name)
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

    private fun ApprovalDecision.toToolResult(): ToolResult = when (this) {
        is ApprovalDecision.Approved -> ToolResult.Invalid(ToolErrorCode.INTERNAL_ERROR.name)
        is ApprovalDecision.Required -> ToolResult.NeedsApproval
        is ApprovalDecision.Rejected -> ToolResult.Denied(code.name)
    }

    private fun ApprovalDecision.toToolExecution(): ToolExecution = when (this) {
        is ApprovalDecision.Approved -> ToolExecution.Failed(ToolError(ToolErrorCode.INTERNAL_ERROR))
        is ApprovalDecision.Required -> ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED))
        is ApprovalDecision.Rejected -> ToolExecution.Failed(ToolError(code))
    }

    private data class ParsedShellCall(
        val command: String,
        val cwd: String?,
        val timeoutMs: Long,
        val maxOutputBytes: Long,
    )

    private data class ModelCallKey(
        val agentId: String,
        val snapshotId: String,
        val sessionIdentity: String,
        val taskIdentity: String,
        val callId: String,
    )

    private data class AuditCorrelation(
        val requestId: String,
        val approvalId: String?,
    )

    private data class BoundShellCall(
        val requestId: String,
        val call: ToolCall,
        val context: ToolExecutionContext,
        val parsed: ParsedShellCall,
        val request: ShellExecRequest,
        val binding: ApprovalBinding,
        val scope: ApprovalScope,
        val risk: ShellRiskAssessment,
        var approvalPending: Boolean = false,
        var approvalGrant: ApprovalGrant? = null,
        var result: ToolResult? = null,
    )

    companion object {
        const val SHELL_EXEC = "shell_exec"
        const val TOOL_SCHEMA_VERSION = 1
        const val MAX_COMMAND_LENGTH = 256 * 1024
        const val MAX_CWD_LENGTH = 4096
        /** Keep the request and shared ShellLimits ceiling identical. */
        const val MAX_OUTPUT_BYTES = ShellLimits.MAX_OUTPUT_BYTES
        const val MAX_RESULT_BYTES = 900 * 1024

        val SHELL_EXEC_SCHEMA = """{"type":"object","additionalProperties":false,"required":["command"],"properties":{"command":{"type":"string","minLength":1,"maxLength":262144},"cwd":{"type":["string","null"],"maxLength":4096},"timeout_ms":{"type":"integer","minimum":1,"maximum":300000},"max_output_bytes":{"type":"integer","minimum":1,"maximum":131072}}}"""

        val SHELL_SPEC = ToolSpec(
            name = SHELL_EXEC,
            description = "Execute one shell command through the selected authority",
            inputSchema = SHELL_EXEC_SCHEMA,
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
            sideEffect = true,
            schemaVersion = TOOL_SCHEMA_VERSION,
        )
    }
}
