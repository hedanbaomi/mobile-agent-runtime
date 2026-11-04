// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RootChannelUiStatus {
    UNCONFIGURED,
    REQUESTING,
    MANAGER_DENIED,
    AWAITING_ROOT_CONSENT,
    AUTHORIZED_SERVICE_OFFLINE,
    READY_UID_0,
    PRIVILEGE_OR_IDENTITY_MISMATCH,
    DEGRADED_UNCERTAIN_DESCENDANTS_REQUIRES_ACK,
}

@Serializable
enum class RootChannelAction {
    REQUEST,
    CONFIRM_CONSENT,
    RECONNECT,
    SELECT,
    DISABLE,
    ACKNOWLEDGE_DEGRADED_RECOVERY,
}

@Serializable
data class RootChannelUiText(
    val title: String,
    val statusBadge: String,
    val detail: String,
    val riskNotice: String,
    val shellConsentNotice: String,
)

@Serializable
data class RootApprovalCardPresentation(
    val authorityBadge: String,
    val privilegeSummary: String,
    val targetWorkspaceId: String,
    val capability: CapabilityId,
    val toolName: String,
    val scopeDescription: String,
    val highRiskWarning: String,
)

@Serializable
data class RootChannelUiModel(
    val authority: Authority,
    val status: RootChannelUiStatus,
    val isSelected: Boolean,
    val rootConsentEnabled: Boolean,
    val rootShellConsentEnabled: Boolean,
    val dangerousMode: DangerousMode,
    val effectiveShellReady: Boolean,
    val availableActions: List<RootChannelAction>,
    val text: RootChannelUiText,
)

/**
 * Pure presentation and user-flow state machine for `ROOT_DIRECT` and `SHIZUKU_ROOT`
 * settings cards and Root approval prompts (`ROOT15`, `ROOT17`, `ROOT18`).
 *
 * Enforces:
 * - Neither `suBinaryPresent` nor `shizukuPermissionGranted` (at UID 2000) ever produces
 *   [RootChannelUiStatus.READY_UID_0]. Only a live verified `PrivilegedSessionIdentity`
 *   with `privilegeTier == PrivilegeTier.ROOT` (`serviceUid == 0`), matching `authority`,
 *   and active `RootAuthorityRecord` (`consent.enabled && !degraded`) renders `READY_UID_0`.
 * - A degraded epoch requires explicit recovery, whose controller verifies authenticated drain
 *   or kernel-reboot proof before rotating identity. This action itself grants no permission.
 * - Approval cards always surface the explicit Root channel and UID 0 badge and never
 *   conceal Root execution behind a generic background job label.
 */
object RootAuthorityPresentation {
    fun buildChannelModel(
        authority: Authority,
        selectedAuthority: Authority,
        record: RootAuthorityRecord,
        dangerousMode: DangerousMode,
        requestInFlight: Boolean = false,
        managerDenied: Boolean = false,
        platformGranted: Boolean = false,
        liveIdentity: PrivilegedSessionIdentity? = null,
        observedMismatchOrNonRootUid: Boolean = false,
        chinese: Boolean = true,
        livePolicyVersion: Long = record.consent.policyVersion,
    ): RootChannelUiModel {
        require(authority.isRootAuthority) { "RootChannelUiModel requires ROOT_DIRECT or SHIZUKU_ROOT" }
        val isSelected = selectedAuthority == authority
        val status = resolveStatus(
            authority = authority,
            record = record,
            requestInFlight = requestInFlight,
            managerDenied = managerDenied,
            platformGranted = platformGranted,
            liveIdentity = liveIdentity,
            observedMismatchOrNonRootUid = observedMismatchOrNonRootUid,
            livePolicyVersion = livePolicyVersion,
        )
        val actions = resolveActions(status, isSelected, record)
        val shellReady = status == RootChannelUiStatus.READY_UID_0 &&
            isSelected &&
            record.consent.enabled &&
            record.consent.shellEnabled
        return RootChannelUiModel(
            authority = authority,
            status = status,
            isSelected = isSelected,
            rootConsentEnabled = record.consent.enabled,
            rootShellConsentEnabled = record.consent.shellEnabled,
            dangerousMode = dangerousMode,
            effectiveShellReady = shellReady,
            availableActions = actions,
            text = renderText(authority, status, chinese),
        )
    }

    fun buildApprovalCard(
        binding: RootApprovalBinding,
        chinese: Boolean = true,
    ): RootApprovalCardPresentation {
        val authority = binding.request.execution.identity.authority
        require(authority.isRootAuthority)
        val channelLabel = when (authority) {
            Authority.ROOT_DIRECT -> if (chinese) "Root / 直接 Root (UID 0)" else "Root / Direct Root (UID 0)"
            Authority.SHIZUKU_ROOT -> if (chinese) "Root / Shizuku Root (UID 0)" else "Root / Shizuku Root (UID 0)"
            else -> error("Unreachable non-Root authority")
        }
        val privilegeSummary = if (chinese) {
            "特权等级：ROOT (UID 0) · 每次调用绑定独立收据与工作区句柄"
        } else {
            "Privilege tier: ROOT (UID 0) · Bound to per-invocation receipt and workspace handle"
        }
        val scopeDesc = if (chinese) {
            "工作区：${binding.workspaceId} · 工具：${binding.invocationName} (${binding.capability.value})"
        } else {
            "Workspace: ${binding.workspaceId} · Tool: ${binding.invocationName} (${binding.capability.value})"
        }
        val warning = if (chinese) {
            "高风险 Root 操作：将在 UID 0 下执行，导出诊断日志不会包含路径或命令原文。"
        } else {
            "High-risk Root operation: executes as UID 0; exported diagnostics redact paths and command text."
        }
        return RootApprovalCardPresentation(
            authorityBadge = channelLabel,
            privilegeSummary = privilegeSummary,
            targetWorkspaceId = binding.workspaceId,
            capability = binding.capability,
            toolName = binding.invocationName,
            scopeDescription = scopeDesc,
            highRiskWarning = warning,
        )
    }

    private fun resolveStatus(
        authority: Authority,
        record: RootAuthorityRecord,
        requestInFlight: Boolean,
        managerDenied: Boolean,
        platformGranted: Boolean,
        liveIdentity: PrivilegedSessionIdentity?,
        observedMismatchOrNonRootUid: Boolean,
        livePolicyVersion: Long,
    ): RootChannelUiStatus {
        if (record.degraded) {
            return RootChannelUiStatus.DEGRADED_UNCERTAIN_DESCENDANTS_REQUIRES_ACK
        }
        if (observedMismatchOrNonRootUid) {
            return RootChannelUiStatus.PRIVILEGE_OR_IDENTITY_MISMATCH
        }
        if (liveIdentity != null && !isValidLiveRootIdentity(authority, record, liveIdentity)) {
            return RootChannelUiStatus.PRIVILEGE_OR_IDENTITY_MISMATCH
        }
        if (requestInFlight) return RootChannelUiStatus.REQUESTING
        if (managerDenied) return RootChannelUiStatus.MANAGER_DENIED
        if (!record.consent.enabled) {
            return if (platformGranted) {
                RootChannelUiStatus.AWAITING_ROOT_CONSENT
            } else {
                RootChannelUiStatus.UNCONFIGURED
            }
        }
        if (record.consent.policyVersion != livePolicyVersion) {
            return RootChannelUiStatus.AWAITING_ROOT_CONSENT
        }
        return if (platformGranted && liveIdentity != null) {
            RootChannelUiStatus.READY_UID_0
        } else {
            RootChannelUiStatus.AUTHORIZED_SERVICE_OFFLINE
        }
    }

    private fun isValidLiveRootIdentity(
        authority: Authority,
        record: RootAuthorityRecord,
        liveIdentity: PrivilegedSessionIdentity,
    ): Boolean = liveIdentity.authority == authority &&
        liveIdentity.privilegeTier == PrivilegeTier.ROOT &&
        liveIdentity.serviceUid == 0 &&
        liveIdentity.protocolVersion == PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION &&
        (!record.consent.enabled || liveIdentity.identityEpoch == record.identityEpoch)

    private fun resolveActions(
        status: RootChannelUiStatus,
        isSelected: Boolean,
        record: RootAuthorityRecord,
    ): List<RootChannelAction> = when (status) {
        RootChannelUiStatus.UNCONFIGURED, RootChannelUiStatus.MANAGER_DENIED ->
            listOf(RootChannelAction.REQUEST)
        RootChannelUiStatus.REQUESTING ->
            listOf(RootChannelAction.DISABLE)
        RootChannelUiStatus.AWAITING_ROOT_CONSENT ->
            listOf(RootChannelAction.CONFIRM_CONSENT, RootChannelAction.DISABLE)
        RootChannelUiStatus.AUTHORIZED_SERVICE_OFFLINE,
        RootChannelUiStatus.PRIVILEGE_OR_IDENTITY_MISMATCH ->
            listOf(RootChannelAction.RECONNECT, RootChannelAction.DISABLE)
        RootChannelUiStatus.READY_UID_0 ->
            if (isSelected) {
                listOf(RootChannelAction.RECONNECT, RootChannelAction.DISABLE)
            } else {
                listOf(RootChannelAction.SELECT, RootChannelAction.RECONNECT, RootChannelAction.DISABLE)
            }
        RootChannelUiStatus.DEGRADED_UNCERTAIN_DESCENDANTS_REQUIRES_ACK -> {
            if (record.consent.enabled) {
                listOf(RootChannelAction.ACKNOWLEDGE_DEGRADED_RECOVERY, RootChannelAction.DISABLE)
            } else {
                listOf(RootChannelAction.RECONNECT, RootChannelAction.DISABLE)
            }
        }
    }

    private fun renderText(
        authority: Authority,
        status: RootChannelUiStatus,
        chinese: Boolean,
    ): RootChannelUiText {
        val title = when (authority) {
            Authority.ROOT_DIRECT -> if (chinese) "直接 Root (libsu RootService)" else "Direct Root (libsu RootService)"
            Authority.SHIZUKU_ROOT -> if (chinese) "Shizuku Root (UID 0)" else "Shizuku Root (UID 0)"
            else -> authority.name
        }
        val (badge, detail) = statusCopy(status, chinese)
        val risk = if (chinese) {
            "开启 Root 通道仅允许访问您明确选择并授权的受控工作区；不会自动开启 Root Shell 或全设备权限。"
        } else {
            "Enabling a Root channel only permits access to workspaces you explicitly attach and grant; it never enables Root Shell or full-device access automatically."
        }
        val shellNotice = if (chinese) {
            "Root Shell (UID 0) 需独立风险确认及工作区授权，并要求当前通道已选定且 UID 0 服务就绪；不受 ADB 危险模式开关控制。"
        } else {
            "Root Shell (UID 0) requires separate risk confirmation, workspace authorization, and the selected channel's live UID 0 service. The ADB Dangerous Mode switch does not control Root Shell."
        }
        return RootChannelUiText(
            title = title,
            statusBadge = badge,
            detail = detail,
            riskNotice = risk,
            shellConsentNotice = shellNotice,
        )
    }

    private fun statusCopy(status: RootChannelUiStatus, chinese: Boolean): Pair<String, String> = when (status) {
        RootChannelUiStatus.UNCONFIGURED ->
            if (chinese) "未配置" to "尚未请求 Root 授权；已安装 su 或普通 Shizuku 不会自动视为 Root 就绪。"
            else "Unconfigured" to "Root authorization has not been requested; an installed su binary or shell-tier Shizuku never counts as Root ready."
        RootChannelUiStatus.REQUESTING ->
            if (chinese) "请求中" to "正在等待 Root 管理器响应并校验受控服务身份…"
            else "Requesting" to "Waiting for the Root manager and verifying controlled service identity…"
        RootChannelUiStatus.MANAGER_DENIED ->
            if (chinese) "管理器拒绝" to "Root 管理器拒绝或未授予 UID 0 权限。"
            else "Manager denied" to "The Root manager denied or did not grant UID 0 privilege."
        RootChannelUiStatus.AWAITING_ROOT_CONSENT ->
            if (chinese) "待 Root 同意" to "平台服务已通过 UID 0 校验，请确认应用内 Root 风险说明以启用该通道。"
            else "Awaiting Root consent" to "Platform service verified at UID 0; confirm the in-app Root risk notice to enable this channel."
        RootChannelUiStatus.AUTHORIZED_SERVICE_OFFLINE ->
            if (chinese) "已授权但服务离线" to "应用内已保存 Root 同意，但受控 UID 0 服务当前未连接。"
            else "Authorized, service offline" to "In-app Root consent is saved, but the controlled UID 0 service is currently disconnected."
        RootChannelUiStatus.READY_UID_0 ->
            if (chinese) "实际 Root 就绪 (UID 0)" to "受控服务已通过 OS 包名、签名、用户与 UID 0 实时核验。"
            else "Root ready (UID 0)" to "Controlled service passed live OS package, signature, user, and UID 0 verification."
        RootChannelUiStatus.PRIVILEGE_OR_IDENTITY_MISMATCH ->
            if (chinese) "身份或权限不匹配" to "观察到的服务 UID 非 0（如普通 ADB UID 2000）或包签名/会话代际不匹配，已拒绝降级回退。"
            else "Identity or privilege mismatch" to "Observed service UID is not 0 (e.g. shell UID 2000) or package/session epoch mismatched; silent fallback is rejected."
        RootChannelUiStatus.DEGRADED_UNCERTAIN_DESCENDANTS_REQUIRES_ACK ->
            if (chinese) "会话降级（待用户确认清理）" to "存在无法确认已退出的 Root 后代进程或未决变异操作，重连后需您明确确认方可恢复派发。"
            else "Session degraded (awaiting user confirmation)" to "Uncertain Root descendant processes or mutations remain; explicit user confirmation after reconnecting a new epoch is required before dispatch."
    }
}
