// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode as DomainDangerousMode

/** Domain-owned persistent mode; tooling does not define a second mode enum. */
typealias DangerousMode = DomainDangerousMode

enum class ShellExposureReason {
    EXPOSED,
    DANGEROUS_MODE_DISABLED,
    SHELL_CAPABILITY_DENIED,
    AUTHORITY_PROVIDER_NOT_SELECTED,
    AUTHORITY_NOT_CONFIGURED,
    AUTHORITY_UNSUPPORTED,
    AUTHORITY_SELECTION_MISMATCH,
}

data class ShellExposureDecision(
    val exposed: Boolean,
    val reason: ShellExposureReason,
    val selectedAuthority: Authority? = null,
) {
    val hidden: Boolean
        get() = !exposed
}

data class ShellDispatchDecision(
    val allowed: Boolean,
    val error: ToolError? = null,
    val risk: ShellRiskAssessment? = null,
    val requiresConfirmation: Boolean = false,
) {
    companion object {
        fun allow(risk: ShellRiskAssessment) = ShellDispatchDecision(true, risk = risk)
        fun deny(error: ToolError, risk: ShellRiskAssessment? = null, requiresConfirmation: Boolean = false) =
            ShellDispatchDecision(false, error, risk, requiresConfirmation)
    }
}

/**
 * Decides only whether the backend-neutral shell schema is visible.  It does
 * not select a fallback provider and deliberately does not require a live
 * connection: a temporarily disconnected selected authority stays exposed,
 * then fails closed at dispatch with AUTHORITY_TEMPORARILY_UNAVAILABLE.
 */
object DangerousModeExposure {
    val shellCapability: CapabilityId = CapabilityId(CapabilityId.SHELL_EXECUTE)

    fun decide(
        mode: DangerousMode,
        effectiveCapabilities: Set<CapabilityId>,
        selectedAuthority: Authority?,
        authorityState: AuthorityState?,
    ): ShellExposureDecision {
        if (mode == DangerousMode.DISABLED) {
            return ShellExposureDecision(false, ShellExposureReason.DANGEROUS_MODE_DISABLED, selectedAuthority)
        }
        if (shellCapability !in effectiveCapabilities) {
            return ShellExposureDecision(false, ShellExposureReason.SHELL_CAPABILITY_DENIED, selectedAuthority)
        }
        if (selectedAuthority == null) {
            return ShellExposureDecision(false, ShellExposureReason.AUTHORITY_PROVIDER_NOT_SELECTED)
        }
        if (authorityState == null) {
            return ShellExposureDecision(false, ShellExposureReason.AUTHORITY_NOT_CONFIGURED, selectedAuthority)
        }
        if (authorityState.authority != selectedAuthority) {
            return ShellExposureDecision(false, ShellExposureReason.AUTHORITY_SELECTION_MISMATCH, selectedAuthority)
        }
        if (authorityState.availability == Availability.UNSUPPORTED) {
            return ShellExposureDecision(false, ShellExposureReason.AUTHORITY_UNSUPPORTED, selectedAuthority)
        }
        if (!authorityState.isConfiguredForSelection) {
            return ShellExposureDecision(false, ShellExposureReason.AUTHORITY_NOT_CONFIGURED, selectedAuthority)
        }
        // Do not check authorityState.isReady here.  Connection state is
        // revalidated by the executor for each invocation.
        return ShellExposureDecision(true, ShellExposureReason.EXPOSED, selectedAuthority)
    }

    /** Agent grant and applicable Skill grant are intersected before exposure. */
    fun decide(
        mode: DangerousMode,
        agentCapabilities: Set<CapabilityId>,
        skillCapabilities: Set<CapabilityId>?,
        selectedAuthority: Authority?,
        authorityState: AuthorityState?,
    ): ShellExposureDecision = decide(
        mode = mode,
        effectiveCapabilities = if (skillCapabilities == null) agentCapabilities else agentCapabilities intersect skillCapabilities,
        selectedAuthority = selectedAuthority,
        authorityState = authorityState,
    )

    fun shouldExpose(
        mode: DangerousMode,
        effectiveCapabilities: Set<CapabilityId>,
        selectedAuthority: Authority?,
        authorityState: AuthorityState?,
    ): Boolean = decide(mode, effectiveCapabilities, selectedAuthority, authorityState).exposed

    /** Alias useful to adapters that call the exposure policy an engine. */
    fun evaluate(
        mode: DangerousMode,
        effectiveCapabilities: Set<CapabilityId>,
        selectedAuthority: Authority?,
        authorityState: AuthorityState?,
    ): ShellExposureDecision = decide(mode, effectiveCapabilities, selectedAuthority, authorityState)
}

/**
 * Thin adapter seam for app/runtime dispatch.  Exposure is persistent-state
 * based, while this gate revalidates the selected provider's live connection
 * and the current approval binding for every invocation.  It never searches
 * for or falls back to another provider.
 */
object ShellDispatchValidator {
    fun validate(
        request: ShellExecRequest,
        effectiveCapabilities: Set<CapabilityId>,
        authorityState: AuthorityState?,
        approvedBinding: ApprovalBinding? = null,
    ): ShellDispatchDecision {
        val exposure = DangerousModeExposure.decide(
            mode = request.dangerousMode,
            effectiveCapabilities = effectiveCapabilities,
            selectedAuthority = request.selectedAuthority,
            authorityState = authorityState,
        )
        if (!exposure.exposed) return ShellDispatchDecision.deny(exposure.error())

        val risk = HighRiskDetector.assess(request.command)
        if (authorityState?.isReady != true) {
            return ShellDispatchDecision.deny(
                ToolError(
                    ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
                    message = "Selected authority is temporarily unavailable",
                    retryable = true,
                ),
                risk,
            )
        }
        if (request.dangerousMode == DangerousMode.ENABLED_CONFIRM_HIGH_RISK && risk.requiresConfirmation) {
            val current = ApprovalBinding.fromRequest(request)
            if (approvedBinding == null) {
                return ShellDispatchDecision.deny(ToolError.approvalRequired(), risk, requiresConfirmation = true)
            }
            if (approvedBinding.isStaleComparedWith(current)) {
                return ShellDispatchDecision.deny(ToolError(ToolErrorCode.SNAPSHOT_STALE), risk)
            }
        }
        return ShellDispatchDecision.allow(risk)
    }

    private fun ShellExposureDecision.error(): ToolError = when (reason) {
        ShellExposureReason.DANGEROUS_MODE_DISABLED -> ToolError(ToolErrorCode.DANGEROUS_MODE_DISABLED)
        ShellExposureReason.SHELL_CAPABILITY_DENIED -> ToolError(ToolErrorCode.SHELL_CAPABILITY_DENIED)
        ShellExposureReason.AUTHORITY_PROVIDER_NOT_SELECTED -> ToolError(ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED)
        ShellExposureReason.AUTHORITY_UNSUPPORTED -> ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        ShellExposureReason.AUTHORITY_SELECTION_MISMATCH,
        ShellExposureReason.AUTHORITY_NOT_CONFIGURED -> ToolError(ToolErrorCode.AUTHORITY_NOT_GRANTED)
        ShellExposureReason.EXPOSED -> ToolError(ToolErrorCode.INTERNAL_ERROR)
    }
}
