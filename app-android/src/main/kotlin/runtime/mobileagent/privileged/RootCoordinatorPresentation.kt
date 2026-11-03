// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.PrivilegedSessionIdentity

internal object RootCoordinatorPresentation {
    private val CONTROL_PLANE_SUBTREES = listOf(
        "databases",
        "shared_prefs",
        "files",
        "no_backup",
        "code_cache",
        "cache",
    )

    fun observeControlPlaneBoundary(
        appContext: android.content.Context,
        fdOps: PosixFdOperations,
        overrideBoundary: runtime.mobileagent.domain.RootControlPlaneBoundary?,
    ): runtime.mobileagent.domain.RootControlPlaneBoundary? {
        overrideBoundary?.let { return it }
        val ceDir = (appContext.dataDir ?: appContext.filesDir?.parentFile ?: return null).toPath()
        val deContext = appContext.createDeviceProtectedStorageContext()
        val deDir = (deContext?.dataDir ?: ceDir.toFile()).toPath()
        fun inspectDir(dir: java.nio.file.Path): runtime.mobileagent.domain.RootFdNodeIdentity? =
            fdOps.inspectDirectoryIdentityNoFollow(dir)
                ?: (fdOps as? AndroidOsPosixFdOperations)?.inspectOwnAppDirectoryIdentityNoFollow(dir)
        val ceIdentity = inspectDir(ceDir) ?: return null
        val deIdentity = inspectDir(deDir) ?: return null
        val cePosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(ceDir)
        val dePosix = AndroidRootPosixFileDriver.toPosixAbsolutePath(deDir)
        val subtrees = buildList {
            for (name in CONTROL_PLANE_SUBTREES) {
                val subId = inspectDir(ceDir.resolve(name))
                if (subId != null) add(subId)
            }
        }
        return runtime.mobileagent.domain.RootControlPlaneBoundary(
            credentialEncryptedDataDir = cePosix,
            credentialEncryptedIdentity = ceIdentity,
            deviceProtectedDataDir = dePosix,
            deviceProtectedIdentity = deIdentity,
            pinnedSubtreeIdentities = subtrees,
        )
    }

    fun normalizeFileResultWorkspaceId(
        result: runtime.mobileagent.domain.RootFileOperationResult<runtime.mobileagent.domain.RootFileMetadataSnapshot>,
        requestedWorkspaceId: String,
    ): runtime.mobileagent.domain.RootFileOperationResult<runtime.mobileagent.domain.RootFileMetadataSnapshot> =
        if (result is runtime.mobileagent.domain.RootFileOperationResult.Succeeded &&
            result.value.workspaceId != requestedWorkspaceId
        ) {
            result.copy(value = result.value.copy(workspaceId = requestedWorkspaceId))
        } else {
            result
        }

    fun evaluateShizukuRootGranted(
        shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
        liveIdentity: PrivilegedSessionIdentity?,
        degraded: Boolean,
    ): Boolean {
        if (liveIdentity?.serviceUid == 0) return true
        val state = shizukuState ?: return false
        val decision = RootShizukuBridgePolicy.evaluateRootShizukuServer(
            selectedAuthority = runtime.mobileagent.domain.Authority.SHIZUKU_ROOT,
            binderAlive = state.binderAlive,
            preV11 = state.preV11,
            permissionGranted = state.permissionGranted,
            serverUid = state.serverUid,
            serverVersion = state.apiVersion,
            sessionDegraded = degraded,
        )
        return decision is RootBridgeGateDecision.Allowed
    }

    fun mapShellOutcomeError(
        status: runtime.mobileagent.skills.tooling.ShellExecutionStatus,
    ): runtime.mobileagent.skills.tooling.ToolError? = when (status) {
        runtime.mobileagent.skills.tooling.ShellExecutionStatus.SUCCEEDED -> null
        runtime.mobileagent.skills.tooling.ShellExecutionStatus.TIMED_OUT ->
            runtime.mobileagent.skills.tooling.ToolError(runtime.mobileagent.skills.tooling.ToolErrorCode.SHELL_TIMED_OUT)
        runtime.mobileagent.skills.tooling.ShellExecutionStatus.CANCELLED ->
            runtime.mobileagent.skills.tooling.ToolError(runtime.mobileagent.skills.tooling.ToolErrorCode.SHELL_CANCELLED)
        runtime.mobileagent.skills.tooling.ShellExecutionStatus.UNKNOWN_OUTCOME ->
            runtime.mobileagent.skills.tooling.ToolError.unknownOutcome()
        runtime.mobileagent.skills.tooling.ShellExecutionStatus.FAILED ->
            runtime.mobileagent.skills.tooling.ToolError(runtime.mobileagent.skills.tooling.ToolErrorCode.SHELL_EXECUTION_FAILED)
    }

    fun mapPlatformGrant(
        record: runtime.mobileagent.domain.RootAuthorityRecord,
        model: runtime.mobileagent.domain.RootChannelUiModel,
    ): runtime.mobileagent.skills.tooling.PlatformGrant = when (model.status) {
        // Only a live, proven UID0 session is a platform grant. Consent written in the app,
        // "requesting", an offline authorized service or a degraded session prove nothing about
        // the platform authorization and must not be presented as GRANTED.
        runtime.mobileagent.domain.RootChannelUiStatus.READY_UID_0 ->
            runtime.mobileagent.skills.tooling.PlatformGrant.GRANTED
        runtime.mobileagent.domain.RootChannelUiStatus.MANAGER_DENIED,
        runtime.mobileagent.domain.RootChannelUiStatus.PRIVILEGE_OR_IDENTITY_MISMATCH,
        -> runtime.mobileagent.skills.tooling.PlatformGrant.DENIED
        else -> runtime.mobileagent.skills.tooling.PlatformGrant.UNKNOWN
    }

    fun mapAvailability(
        model: runtime.mobileagent.domain.RootChannelUiModel,
    ): runtime.mobileagent.skills.tooling.Availability = when (model.status) {
        runtime.mobileagent.domain.RootChannelUiStatus.READY_UID_0 ->
            runtime.mobileagent.skills.tooling.Availability.READY
        runtime.mobileagent.domain.RootChannelUiStatus.PRIVILEGE_OR_IDENTITY_MISMATCH ->
            runtime.mobileagent.skills.tooling.Availability.UNSUPPORTED
        else -> runtime.mobileagent.skills.tooling.Availability.TEMPORARILY_UNAVAILABLE
    }

    fun mapConnection(
        model: runtime.mobileagent.domain.RootChannelUiModel,
    ): runtime.mobileagent.skills.tooling.Connection = when (model.status) {
        runtime.mobileagent.domain.RootChannelUiStatus.READY_UID_0 ->
            runtime.mobileagent.skills.tooling.Connection.CONNECTED
        runtime.mobileagent.domain.RootChannelUiStatus.REQUESTING ->
            runtime.mobileagent.skills.tooling.Connection.CONNECTING
        runtime.mobileagent.domain.RootChannelUiStatus.DEGRADED_UNCERTAIN_DESCENDANTS_REQUIRES_ACK ->
            runtime.mobileagent.skills.tooling.Connection.DEGRADED
        else -> runtime.mobileagent.skills.tooling.Connection.DISCONNECTED
    }

    fun buildSettingsProviderState(
        authority: runtime.mobileagent.domain.Authority,
        elevated: runtime.mobileagent.skills.tooling.ElevatedAuthority,
        authorityManager: runtime.mobileagent.tooling.AuthorityManager,
        record: runtime.mobileagent.domain.RootAuthorityRecord,
    ): runtime.mobileagent.SettingsAuthorityProviderState {
        val status = authorityManager.state.value.statuses[elevated]
        return runtime.mobileagent.SettingsAuthorityProviderState(
            authority = authority,
            userIntent = status?.userIntent ?: if (record.consent.enabled) {
                runtime.mobileagent.skills.tooling.AuthorityState.intentFor(authority)
            } else {
                runtime.mobileagent.domain.AuthorityUserIntent.NONE
            },
            platformGrant = status?.grant ?: runtime.mobileagent.skills.tooling.PlatformGrant.UNKNOWN,
            availability = status?.availability ?: runtime.mobileagent.skills.tooling.Availability.TEMPORARILY_UNAVAILABLE,
            connection = status?.connection ?: runtime.mobileagent.skills.tooling.Connection.DISCONNECTED,
            configured = status?.configured ?: record.consent.enabled,
            rootShellConsentEnabled = record.consent.shellEnabled,
        )
    }

    fun buildUiModel(
        authority: runtime.mobileagent.domain.Authority,
        selectedAuthority: runtime.mobileagent.domain.Authority,
        dangerousMode: runtime.mobileagent.domain.DangerousMode,
        shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
        record: runtime.mobileagent.domain.RootAuthorityRecord,
        liveIdentity: PrivilegedSessionIdentity?,
        livePolicyVersion: Long = record.consent.policyVersion,
    ): runtime.mobileagent.domain.RootChannelUiModel {
        // A consent bound to an older policy version is stale: the execution stamp is null and the
        // channel must not be presented as READY. It projects as "needs re-consent" instead.
        val consentCurrent = !record.consent.enabled || record.consent.policyVersion == livePolicyVersion
        val platformGranted = if (!consentCurrent) false else when (authority) {
            runtime.mobileagent.domain.Authority.ROOT_DIRECT -> liveIdentity?.serviceUid == 0 || record.consent.enabled
            runtime.mobileagent.domain.Authority.SHIZUKU_ROOT -> evaluateShizukuRootGranted(
                shizukuState,
                liveIdentity,
                record.degraded,
            )
            else -> false
        }
        val nonRootMismatch = authority == runtime.mobileagent.domain.Authority.SHIZUKU_ROOT &&
            shizukuState?.binderAlive == true &&
            shizukuState.serverUid != null &&
            shizukuState.serverUid != 0
        return runtime.mobileagent.domain.RootAuthorityPresentation.buildChannelModel(
            authority = authority,
            selectedAuthority = selectedAuthority,
            record = record,
            dangerousMode = dangerousMode,
            platformGranted = platformGranted,
            liveIdentity = liveIdentity,
            observedMismatchOrNonRootUid = nonRootMismatch,
        )
    }
}
