// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import runtime.mobileagent.domain.InputModality
import runtime.mobileagent.domain.ModelFeature
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.isChatEndpoint
import runtime.mobileagent.domain.withEndpoint
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.CapabilityCheckResult
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.CapabilityProbeStatus
import runtime.mobileagent.provider.CapabilityReport

internal data class OpenAiFeatureProbeResult(
    val summary: String,
    val supported: Boolean,
    val charged: Boolean,
    val httpStatus: Int? = null,
    val status: CapabilityCheckStatus = CapabilityCheckStatus.FAILED,
)

/** Pure report mapping; each transport retains its own probe requests and failure handling. */
internal fun openAiProfileReport(
    profile: ModelProfile,
    source: String,
    supportsImages: Boolean,
): CapabilityReport = CapabilityReport(
    modelId = profile.modelId,
    supportsStream = "stream" in profile.capabilities,
    supportsTools = "tools" in profile.capabilities,
    supportsImages = supportsImages,
    source = source,
    probedAt = Utc.nowIso(),
    charged = false,
    status = CapabilityProbeStatus.PROFILE_ONLY,
    checks = openAiProfileChecks(profile),
)

internal fun openAiProfileChecks(profile: ModelProfile): List<CapabilityCheckResult> {
    val configured = profile.withEndpoint()
    val chat = configured.isChatEndpoint()
    fun declared(feature: ProbeFeature): Boolean = when (feature) {
        ProbeFeature.STREAM -> ModelFeature.STREAMING in configured.endpoint.features || "stream" in configured.capabilities
        ProbeFeature.TOOLS -> ModelFeature.TOOL_CALLING in configured.endpoint.features || "tools" in configured.capabilities
        ProbeFeature.IMAGE -> InputModality.IMAGE in configured.endpoint.inputModalities ||
            "image" in configured.capabilities || configured.role == ModelRole.VISION
    }
    fun status(feature: ProbeFeature): CapabilityCheckStatus =
        if (chat && declared(feature)) CapabilityCheckStatus.NOT_RUN else CapabilityCheckStatus.NOT_DECLARED
    return listOf(
        CapabilityCheckResult(CapabilityCheck.METADATA, CapabilityCheckStatus.NOT_RUN),
        CapabilityCheckResult(CapabilityCheck.STREAM, status(ProbeFeature.STREAM)),
        CapabilityCheckResult(CapabilityCheck.TOOLS, status(ProbeFeature.TOOLS)),
        CapabilityCheckResult(CapabilityCheck.IMAGE, status(ProbeFeature.IMAGE)),
    )
}

internal fun openAiCapabilityProbeStatus(results: List<OpenAiFeatureProbeResult>): CapabilityProbeStatus = when {
    results.all { it.status == CapabilityCheckStatus.VERIFIED || it.status == CapabilityCheckStatus.NOT_DECLARED } ->
        CapabilityProbeStatus.SUCCEEDED
    results.any { it.status == CapabilityCheckStatus.UNSUPPORTED } -> CapabilityProbeStatus.PARTIAL
    results.any { it.status == CapabilityCheckStatus.VERIFIED || it.status == CapabilityCheckStatus.NOT_DECLARED } ->
        CapabilityProbeStatus.PARTIAL
    else -> CapabilityProbeStatus.FAILED
}

internal fun missingOpenAiProbeCredentialReport(
    profileReport: CapabilityReport,
    profile: ModelProfile,
    operationId: String,
): CapabilityReport = profileReport.copy(
    supportsStream = false,
    supportsTools = false,
    supportsImages = false,
    source = "metadata=not-run;stream=not-run;tools=not-run;image=not-run;secret=unavailable",
    status = CapabilityProbeStatus.FAILED,
    operationId = operationId,
    checks = openAiProfileChecks(profile).map { check ->
        if (check.capability == CapabilityCheck.METADATA) check.copy(status = CapabilityCheckStatus.FAILED) else check
    },
)

internal fun failedOpenAiProbeReport(
    profileReport: CapabilityReport,
    operationId: String,
    reason: String,
): CapabilityReport = profileReport.copy(
    supportsStream = false,
    supportsTools = false,
    supportsImages = false,
    source = "metadata=$reason;stream=not-run;tools=not-run;image=not-run",
    status = CapabilityProbeStatus.FAILED,
    charged = false,
    operationId = operationId,
    checks = listOf(
        CapabilityCheckResult(CapabilityCheck.METADATA, CapabilityCheckStatus.UNKNOWN),
        CapabilityCheckResult(CapabilityCheck.STREAM, CapabilityCheckStatus.NOT_RUN),
        CapabilityCheckResult(CapabilityCheck.TOOLS, CapabilityCheckStatus.NOT_RUN),
        CapabilityCheckResult(CapabilityCheck.IMAGE, CapabilityCheckStatus.NOT_RUN),
    ),
)
