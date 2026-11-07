// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.diagnostics.DiagnosticSanitizer
import runtime.mobileagent.diagnostics.DiagnosticLevel
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.domain.SecretStatus
import runtime.mobileagent.domain.WebSearchProvider
import runtime.mobileagent.domain.ThemePreference
import runtime.mobileagent.feature.settings.SettingsDiagnosticsFeedback
import runtime.mobileagent.feature.settings.SettingsUiState
import runtime.mobileagent.feature.settings.WiredPairingUiState
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.serialization.TransferConflictPolicy
import runtime.mobileagent.serialization.TransferOptions
import runtime.mobileagent.data.RootAuthorityRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

internal fun safeExportFailureDetail(failure: Exception): String = when (failure) {
    is AppException -> SecretRedactor.redact(failure.error.userMessage)
        .replace(Regex("(?i)(?:content|file)://[^\\s),]+"), "所选保存位置")
        .take(240)
    is SecurityException -> "没有保存到所选位置的权限，请重新选择保存位置后重试。"
    is IOException -> "无法写入所选位置；请检查可用空间和权限，或更换位置后重试。"
    else -> "本地校验或序列化失败（${failure.javaClass.simpleName}）；原数据未修改，请检查应用日志后重试。"
}.ifBlank { "本地导出校验失败；原数据未修改，请更换保存位置后重试。" }

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    private val buildTimeUtc by lazy { app.packagedBuildTimeUtc() }
    private val screenObservation = runtime.mobileagent.observation.ScreenObservationRegistry.get(application)
    private val screenObservationState = mutableStateOf(screenObservation.access.value)
    init { viewModelScope.launch { screenObservation.access.collect { screenObservationState.value = it } } }

    fun setScreenObservationEnabled(enabled: Boolean) = saveScreenSetting(
        { screenObservation.setConsent(enabled) }, "无法保存屏幕观察设置 / Could not save screen observation settings")

    private fun saveScreenSetting(save: () -> Boolean, failureMessage: String) {
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { save() }) {
                error.value = failureMessage
            }
        }
    }

    fun openAccessibilitySettings() {
        runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { error.value = "无法打开系统无障碍设置 / Could not open accessibility settings" }
    }
    fun setScreenActionsEnabled(enabled: Boolean) = saveScreenSetting(
        { screenObservation.setActionsAllowed(enabled) }, "无法保存辅助操作设置 / Could not save assisted control settings")
    private val authorityPort = settingsAuthorityPort(app)
    /** UI-facing copy of the adapter snapshot; the adapter remains the source of truth. */
    val authorityState = mutableStateOf(authorityPort.snapshot())
    val preferences = mutableStateOf(app.container.settings.get())
    val exportStatus = mutableStateOf("")
    val inspectorEnabled = mutableStateOf(app.container.uiPreferences.requestInspectorEnabled)
    /** Compose observes consent changes instead of waiting for another settings recomposition. */
    val statsEnabled = mutableStateOf(app.container.announcements.statsEnabled())
    val diagnosticsStatus = mutableStateOf(SettingsDiagnosticsFeedback.NONE)
    val webSearchStatus = mutableStateOf("")
    val error = mutableStateOf<String?>(null)
    private val authorityActions = SettingsAuthorityActions(
        scope = viewModelScope,
        port = authorityPort,
        onSnapshot = { authorityState.value = it },
        onError = { error.value = it },
        onFactsChanged = { refreshRuntimeFacts() },
        errorMapper = ::safeAuthorityError,
        reasonMapper = ::authorityReason,
    )
    private var pendingExport: Pair<String, TransferOptions>? = null
    private var transferRunning = false
    /** The pairing token is intentionally held only in this ViewModel process memory. */
    private var wiredPairingPrompt: SettingsWiredPairingPrompt? = null
    private var wiredPairingAttemptsRemaining = 0
    private var wiredPairingReplacingTrust = false
    private var wiredPairingStatus = ""
    private var wiredPairingCompleting = false
    private var wiredPairingExpiryJob: Job? = null
    private val wiredPairingUiState = mutableStateOf(WiredPairingUiState())

    /**
     * Facts whose read is I/O-bound: the diagnostics files, the license asset, the secret inventory
     * and the settings rows.  They are loaded off the main thread and read back from this state, so
     * `SettingsRoute` composition never queries the database, the keystore inventory or the assets.
     */
    private data class SettingsRuntimeFacts(
        val diagnosticsEnabled: Boolean = false,
        val diagnosticsLogLevel: String = "INFO",
        val diagnosticsSizeBytes: Long = 0L,
        val diagnosticsLimitBytes: Long = 0L,
        val licenseText: String = "",
        val webSearchConfigured: Boolean = false,
        val webSearchEnabled: Boolean = false,
        val webSearchProviderId: String = "brave",
        val globalRootPrompt: String = "",
        /** Root channels whose durable epoch still requires an explicit foreground recovery. */
        val degradedRootChannels: List<String> = emptyList(),
    )

    private val runtimeFacts = mutableStateOf(SettingsRuntimeFacts())

    /** Unread announcement count for the settings entry; loaded with [refreshRuntimeFacts]. */
    val noticeCount = mutableStateOf(0)

    private var runtimeFactsJob: Job? = null
    private var runtimeFactsRevision = 0L

    /**
     * Load the I/O-bound settings facts on [Dispatchers.IO].  Called from the route's lifecycle
     * effects and after a mutation that can change them — never from composition.
     *
     * Latest wins: a newer refresh cancels the in-flight read and a late result is discarded by its
     * revision, so a stale snapshot can never overwrite a setting the user just changed.  A storage
     * failure becomes the existing [error] state instead of an unhandled coroutine exception, while
     * cancellation still propagates.
     */
    fun refreshRuntimeFacts() {
        val revision = ++runtimeFactsRevision
        runtimeFactsJob?.cancel()
        runtimeFactsJob = viewModelScope.launch {
            try {
                val facts = withContext(Dispatchers.IO) { loadRuntimeFacts() }
                if (revision != runtimeFactsRevision) return@launch
                runtimeFacts.value = facts
                val notices = withContext(Dispatchers.IO) {
                    app.container.announcements.records().count { it.state.readAt == null }
                }
                if (revision != runtimeFactsRevision) return@launch
                noticeCount.value = notices
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (revision == runtimeFactsRevision) {
                    error.value = DiagnosticSanitizer.text(failure.message ?: "无法读取设置状态。")
                }
            } finally {
                if (runtimeFactsJob === currentCoroutineContext()[Job]) runtimeFactsJob = null
            }
        }
    }

    /** Always called on [Dispatchers.IO]. */
    private fun loadRuntimeFacts(): SettingsRuntimeFacts {
        val diagnostics = app.diagnostics.status()
        val searchRef = app.container.settings.webSearchSecretRef()
        val searchConfigured = searchRef != null &&
            app.container.secrets.inventory().status(searchRef) == SecretStatus.ACTIVE
        return SettingsRuntimeFacts(
            diagnosticsEnabled = diagnostics.enabled,
            diagnosticsLogLevel = app.diagnostics.logLevel.wireName,
            diagnosticsSizeBytes = diagnostics.sizeBytes,
            diagnosticsLimitBytes = diagnostics.totalLimitBytes,
            licenseText = runCatching {
                app.assets.open("AGPL-3.0-only.txt").bufferedReader().use { it.readText() }
            }.getOrDefault(""),
            webSearchConfigured = searchConfigured,
            webSearchEnabled = searchConfigured && app.container.settings.webSearchEnabled(),
            webSearchProviderId = app.container.settings.webSearchProvider()?.id.orEmpty(),
            globalRootPrompt = app.container.settings.effectiveGlobalRootPrompt(),
            // Durable Root recovery facts are read off the main thread with the other I/O facts.
            degradedRootChannels = degradedRootChannels(),
        )
    }

    /** Root channels whose durable state still carries an unconfirmed Root outcome. */
    private fun degradedRootChannels(): List<String> = runCatching {
        val roots = RootAuthorityRepository(app.container.db)
        listOf(Authority.ROOT_DIRECT, Authority.SHIZUKU_ROOT)
            .filter { roots.get(it).degraded }
            .map { it.name }
    }.getOrDefault(emptyList())

    fun uiState(noticeCount: Int): SettingsUiState {
        val facts = runtimeFacts.value
        val authority = authorityState.value
        return SettingsUiState(
        screenObservationEnabled = screenObservationState.value.consent,
        screenObservationConnected = screenObservationState.value.connected,
        screenActionsEnabled = screenObservationState.value.actionsAllowed,
        versionName = BuildConfig.VERSION_NAME + " ${BuildConfig.BUILD_TYPE}",
        gitRevision = BuildConfig.GIT_REVISION,
        gitDirty = BuildConfig.GIT_DIRTY,
        schemaVersion = BuildConfig.DB_SCHEMA_VERSION,
        buildTimeUtc = buildTimeUtc,
        buildType = BuildConfig.BUILD_TYPE,
        diagnosticText = buildString {
            appendLine("revision=${BuildConfig.GIT_REVISION}")
            appendLine("dirty=${BuildConfig.GIT_DIRTY}")
            appendLine("schema=${BuildConfig.DB_SCHEMA_VERSION}")
            appendLine("builtAt=$buildTimeUtc")
        },
        themeMode = when (preferences.value.theme) {
            ThemePreference.SYSTEM -> "system"; ThemePreference.LIGHT -> "light"
            ThemePreference.DARK -> "dark"; ThemePreference.COLOR_66CCFF -> "66ccff"
        },
        language = when (preferences.value.locale) {
            LocalePreference.SYSTEM -> "system"; LocalePreference.ZH_CN -> "zh-CN"; LocalePreference.EN_US -> "en-US"
        },
        statsEnabled = statsEnabled.value, requestInspectionEnabled = inspectorEnabled.value,
        diagnosticsEnabled = facts.diagnosticsEnabled,
        diagnosticsLogLevel = facts.diagnosticsLogLevel,
        diagnosticsSizeBytes = facts.diagnosticsSizeBytes,
        diagnosticsLimitBytes = facts.diagnosticsLimitBytes,
        diagnosticsFeedback = diagnosticsStatus.value,
        exportState = exportStatus.value, updateState = app.container.appUpdates.state.value.message, noticeCount = noticeCount,
        licenseText = facts.licenseText.takeIf { it.isNotBlank() },
        error = error.value,
        globalRootPrompt = facts.globalRootPrompt,
        globalRootPromptOverride = preferences.value.globalRootPromptOverride,
        globalRootPromptUnlocked = preferences.value.globalRootPromptUnlocked,
        globalRootPromptRevision = preferences.value.globalRootPromptRevision,
        globalRootPromptUpdatedAt = preferences.value.globalRootPromptUpdatedAt,
        webSearchConfigured = facts.webSearchConfigured,
        webSearchEnabled = facts.webSearchEnabled,
        webSearchProviderId = facts.webSearchProviderId,
        webSearchState = webSearchStatus.value,
        appPrivateExecutionActive = authority.appPrivateAvailable,
        selectedAuthority = authority.selectedAuthority.name,
        shizukuAuthority = authority.shizuku.toUiState().copy(selected = authority.selectedAuthority == Authority.SHIZUKU),
        wiredAdbAuthority = authority.wiredAdb.toUiState().copy(selected = authority.selectedAuthority == Authority.WIRED_ADB),
        rootDirectAuthority = authority.rootDirect.toUiState().copy(selected = authority.selectedAuthority == Authority.ROOT_DIRECT),
        shizukuRootAuthority = authority.shizukuRoot.toUiState().copy(selected = authority.selectedAuthority == Authority.SHIZUKU_ROOT),
        wiredPairing = wiredPairingUiState.value,
        safWorkspace = authority.saf.toUiState(),
        dangerousMode = authority.dangerousMode.name,
        dangerousModeDurable = authority.durableDangerousMode.name,
        dangerousModeBuildAllowed = authority.dangerousModeBuildAllowed,
        dangerousModeBuildKnown = authority.dangerousModeBuildKnown,
        dangerousModeReason = authority.dangerousModeReason,
        rootRecoveryRequired = facts.degradedRootChannels,
        )
    }

    fun refreshStatsEnabled() {
        statsEnabled.value = app.container.announcements.statsEnabled()
    }

    fun setStatsEnabled(enabled: Boolean) {
        app.container.announcementRefreshCoordinator.setStatsEnabled(enabled)
        refreshStatsEnabled()
    }

    /** Revalidates ephemeral provider state and persisted SAF grants on resume. */
    fun refreshAuthorities() = authorityActions.refreshAuthorities()

    fun selectAuthority(value: String) {
        parseAuthority(value)?.let { authority ->
            if (authority != Authority.WIRED_ADB) clearWiredPairing(notifyPort = true, status = "CANCELLED")
            authorityActions.selectAuthority(authority)
        }
    }

    fun setAuthorityIntent(value: String, enabled: Boolean) {
        parseAuthority(value)?.let { authority ->
            if (authority == Authority.WIRED_ADB && !enabled) {
                clearWiredPairing(notifyPort = true, status = "CANCELLED")
            }
            authorityActions.setAuthorityIntent(authority, enabled)
        }
    }

    fun requestShizukuPermission() = authorityActions.requestShizukuPermission()

    /** Explicit foreground enable of Root-over-Shizuku; selects first, then binds the intent. */
    fun enableShizukuRoot() = authorityActions.enableShizukuRoot()

    fun openRootManager() {
        error.value = RootChannelEntryActions.openRootManagerMessage(
            open = { authorityPort.openRootManager() },
            failureMessage = app.getString(R.string.root_manager_unavailable_message),
        )
    }

    /**
     * The primary Shizuku action deliberately performs all three explicit
     * user choices in a visible order. Live Binder state never implies intent
     * or selection, and a failure stops the remaining mutations.
     */
    fun enableShizuku() = authorityActions.enableShizuku()

    fun openShizuku() {
        error.value = RootChannelEntryActions.openRootManagerMessage(
            open = { authorityPort.openShizuku() },
            failureMessage = "无法打开 Shizuku 管理器；请稍后重试。",
        )
    }

    @Deprecated("Use the foreground pairing flow")
    fun reauthorizeWiredAdb() {
        requestWiredAdbPairing(replaceExistingTrust = true)
    }

    fun forgetWiredAdb() {
        clearWiredPairing(notifyPort = true, status = "CANCELLED")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { authorityPort.revokeWiredAdb() }
                authorityState.value = result.snapshot
                error.value = if (result.accepted) null else authorityReason(result.reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = safeAuthorityError(failure)
            }
        }
    }

    /**
     * Starts a foreground-only Wired ADB pairing exchange. The raw token is
     * retained by the adapter/bridge; this ViewModel keeps only the ephemeral
     * prompt needed by the visible Settings screen.
     */
    fun requestWiredAdbPairing(replaceExistingTrust: Boolean = false) {
        clearWiredPairing(notifyPort = true, status = "")
        val result = runCatching {
            // The visible foreground action explicitly enables and selects
            // this provider. Transport/trust discovery must never do so.
            authorityState.value = authorityPort.setUserIntent(Authority.WIRED_ADB, true)
            authorityState.value = authorityPort.selectAuthority(Authority.WIRED_ADB)
            authorityPort.requestWiredAdbPairingToken(replaceExistingTrust)
        }.getOrElse { failure ->
            wiredPairingStatus = "FAILED"
            publishWiredPairingState()
            error.value = safeAuthorityError(failure)
            return
        }
        authorityState.value = result.snapshot
        val prompt = result.prompt
        if (!result.accepted || prompt == null) {
            wiredPairingStatus = "FAILED"
            publishWiredPairingState()
            error.value = authorityReason(result.reason)
            return
        }
        wiredPairingPrompt = prompt
        wiredPairingAttemptsRemaining = prompt.remainingAttempts
        wiredPairingReplacingTrust = replaceExistingTrust
        wiredPairingStatus = ""
        wiredPairingCompleting = false
        publishWiredPairingState()
        scheduleWiredPairingExpiry(prompt)
        error.value = null
    }

    /** Completes the bridge-held pairing exchange; no token crosses this seam. */
    fun completeWiredAdbPairing() {
        val prompt = wiredPairingPrompt ?: run {
            error.value = "请先开始有线 ADB 前台配对。"
            return
        }
        if (prompt.isCleared() || prompt.expiresAtEpochMs <= System.currentTimeMillis()) {
            expireWiredPairing(prompt)
            return
        }
        if (wiredPairingCompleting || wiredPairingAttemptsRemaining <= 0) return
        wiredPairingCompleting = true
        publishWiredPairingState()
        viewModelScope.launch {
            try {
                val result = authorityPort.completeWiredAdbPairing()
                // A cancel/replacement may have happened while the bridge call
                // was suspended. Never let an old result mutate the new prompt.
                if (wiredPairingPrompt !== prompt) return@launch
                authorityState.value = result.snapshot
                if (result.accepted) {
                    clearWiredPairing(notifyPort = false, status = "COMPLETED")
                    error.value = null
                } else {
                    wiredPairingAttemptsRemaining = (wiredPairingAttemptsRemaining - 1).coerceAtLeast(0)
                    val expired = pairingFailureRequiresRestart(result.reason) ||
                        wiredPairingAttemptsRemaining == 0
                    if (expired) {
                        clearWiredPairing(
                            notifyPort = false,
                            status = if (pairingFailureIsExpired(result.reason)) "EXPIRED" else "FAILED",
                        )
                    } else {
                        wiredPairingStatus = "FAILED"
                        wiredPairingCompleting = false
                        publishWiredPairingState()
                        error.value = authorityReason(result.reason)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (wiredPairingPrompt === prompt) {
                    wiredPairingCompleting = false
                    publishWiredPairingState()
                    error.value = safeAuthorityError(failure)
                }
            }
        }
    }

    /**
     * Ephemeral rendering/copy accessor. The caller must not retain the
     * returned String; no SettingsUiState, snapshot, SavedState, DB, log, or
     * diagnostic value contains this token.
     */
    fun wiredPairingToken(): String? =
        wiredPairingPrompt?.tokenDisplay()?.takeIf { it.isNotEmpty() }

    /** Clears the one-time token and asks the adapter to cancel its exchange. */
    fun cancelWiredAdbPairing() {
        clearWiredPairing(notifyPort = true, status = "CANCELLED")
    }

    /** Called only from the OpenDocumentTree result callback. */
    fun authorizeSaf(
        uri: Uri?,
        grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) {
        if (uri == null) return
        authorityActions.mutate { authorityPort.authorizeSaf(uri, grantFlags) }
    }

    fun revokeSaf() {
        authorityActions.mutate { authorityPort.revokeSaf() }
    }

    /** The Settings UI displays and obtains explicit confirmation before this call. */
    fun setDangerousMode(value: String) {
        val mode = runCatching { DangerousMode.valueOf(value) }.getOrNull() ?: run {
            error.value = app.getString(R.string.root_authority_unsupported_dangerous_mode)
            return
        }
        applyDangerousMode(mode)
    }

    fun disableDangerousMode() = applyDangerousMode(DangerousMode.DISABLED)

    private fun applyDangerousMode(mode: DangerousMode) = authorityActions.setDangerousMode(mode)

    fun setRootShellConsent(value: String, enabled: Boolean, confirmed: Boolean = true) {
        val authority = when (value.trim().uppercase(Locale.ROOT)) {
            "ROOT_DIRECT", "ROOT-DIRECT", "ROOT DIRECT" -> Authority.ROOT_DIRECT
            "SHIZUKU_ROOT", "SHIZUKU-ROOT", "SHIZUKU ROOT" -> Authority.SHIZUKU_ROOT
            else -> null
        } ?: run {
            error.value = "不支持的权限通道。"
            return
        }
        authorityActions.setRootShellConsent(authority, enabled, confirmed)
    }

    /**
     * Explicit foreground confirmation of an unconfirmed Root outcome (accepted `M5` entry).
     *
     * The runtime integration owns the coordinator call; until it implements
     * [RootRecoveryAuthorityPort] this reports a precise reason and changes nothing. Recovery is
     * never implied by a reconnect, a refresh or a channel selection, and nothing is replayed.
     */
    fun acknowledgeRootRecovery(authorityValue: String) {
        val authority = parseAuthority(authorityValue)?.takeIf { it.isRootAuthority } ?: run {
            error.value = app.getString(R.string.root_authority_unsupported_channel)
            return
        }
        val recovery = authorityPort as? RootRecoveryAuthorityPort ?: run {
            error.value = app.getString(R.string.root_recovery_adapter_unavailable)
            return
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { recovery.acknowledgeRootRecovery(authority) }
                authorityState.value = result.snapshot
                error.value = if (result.accepted) null else app.getString(R.string.root_recovery_restart_required)
                refreshRuntimeFacts()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error.value = safeAuthorityError(failure)
            }
        }
    }

    private fun publishWiredPairingState() {
        val prompt = wiredPairingPrompt
        wiredPairingUiState.value = WiredPairingUiState(
            // The token itself is deliberately not part of the UI model. The
            // feature host receives it only through wiredPairingToken().
            hasToken = prompt?.isCleared() == false,
            expiresAtEpochMs = prompt?.expiresAtEpochMs ?: 0L,
            remainingAttempts = if (prompt == null) 0 else wiredPairingAttemptsRemaining,
            status = wiredPairingStatus,
            replacingExistingTrust = wiredPairingReplacingTrust,
            completing = wiredPairingCompleting,
        )
    }

    private fun scheduleWiredPairingExpiry(prompt: SettingsWiredPairingPrompt) {
        wiredPairingExpiryJob?.cancel()
        val waitMs = (prompt.expiresAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
        wiredPairingExpiryJob = viewModelScope.launch {
            delay(waitMs)
            if (wiredPairingPrompt === prompt) expireWiredPairing(prompt)
        }
    }

    private fun expireWiredPairing(prompt: SettingsWiredPairingPrompt) {
        if (wiredPairingPrompt !== prompt) return
        clearWiredPairing(notifyPort = true, status = "EXPIRED")
        refreshAuthorities()
    }

    private fun clearWiredPairing(notifyPort: Boolean, status: String) {
        val prompt = wiredPairingPrompt
        wiredPairingPrompt = null
        wiredPairingAttemptsRemaining = 0
        wiredPairingReplacingTrust = false
        wiredPairingCompleting = false
        wiredPairingExpiryJob?.cancel()
        wiredPairingExpiryJob = null
        wiredPairingStatus = status
        // Wipe the object after removing it from the published state. Any
        // later UI recomposition sees no display value, while the underlying
        // char array is overwritten even if an old object is still retained.
        prompt?.clear()
        publishWiredPairingState()
        if (notifyPort) {
            // Cancellation is best-effort during lifecycle teardown. The
            // fail-closed port has no pending token and must not fabricate one.
            runCatching { authorityPort.cancelWiredAdbPairing() }
                .onSuccess { authorityState.value = it }
        }
    }

    private fun pairingFailureRequiresRestart(reason: String?): Boolean =
        pairingFailureIsExpired(reason) || reason?.contains("ATTEMPT", ignoreCase = true) == true

    private fun pairingFailureIsExpired(reason: String?): Boolean =
        reason?.contains("EXPIRED", ignoreCase = true) == true ||
            reason?.contains("TIMEOUT", ignoreCase = true) == true

    /**
     * Parses an explicit channel id and reports the shared "unsupported channel" copy for an unknown
     * one; callers keep working with the nullable result instead of repeating the same guard.
     */
    private fun parseAuthority(value: String): Authority? {
        val authority = authorityChannelIds[value.trim().uppercase(Locale.ROOT)]
        if (authority == null) error.value = app.getString(R.string.root_authority_unsupported_channel)
        return authority
    }

    private fun safeAuthorityError(failure: Throwable): String =
        app.getString(R.string.root_permission_update_failed, authorityReason(failure.message))

    private fun authorityReason(reason: String?): String = when (reason) {
        "ROOT_RECOVERY_RESTART_REQUIRED" -> app.getString(R.string.root_recovery_restart_required)
        "ROOT_SHIZUKU_STATE_UNKNOWN" -> app.getString(R.string.root_shizuku_state_unknown)
        "ROOT_SHIZUKU_NOT_RUNNING" -> app.getString(R.string.root_shizuku_not_running)
        "ROOT_SHIZUKU_UID_NOT_ROOT" -> app.getString(R.string.root_shizuku_uid_not_root)
        "ROOT_SHIZUKU_VERSION_UNSUPPORTED" -> app.getString(R.string.root_shizuku_version_unsupported)
        "ROOT_SHIZUKU_PERMISSION_REQUEST_REJECTED" -> app.getString(R.string.root_shizuku_permission_rejected)
        "DANGEROUS_MODE_BUILD_DENIED" -> app.getString(R.string.root_authority_build_denied)
        "DANGEROUS_MODE_CONFIRMATION_REQUIRED" -> app.getString(R.string.root_authority_confirmation_required)
        "DANGEROUS_MODE_ADAPTER_UNAVAILABLE" -> app.getString(R.string.root_authority_adapter_unavailable)
        "AUTHORITY_ADAPTER_UNAVAILABLE" -> app.getString(R.string.root_authority_settings_adapter_unavailable)
        "RESIDENT_ADB_REVOKE_FAILED" -> "未能停止设备 ADB 服务，激活凭据已保留，请重试撤销。"
        "WIRED_ADB_POLICY_SAVE_FAILED" -> "设备 ADB 服务已停止，但权限配置未能保存，请刷新后重试。"
        else -> app.getString(R.string.root_permission_update_retry)
    }

    override fun onCleared() {
        clearWiredPairing(notifyPort = true, status = "CANCELLED")
        super.onCleared()
    }

    private fun searchProviderForAction(expectedId: String): WebSearchProvider? {
        val provider = WebSearchProvider.fromId(expectedId)
        if (provider == null || app.container.settings.webSearchProvider() != provider) {
            error.value = "搜索服务商已变化，请确认当前服务后重试。"
            refreshRuntimeFacts()
            return null
        }
        return provider
    }

    fun saveWebSearch(providerId: String, apiKey: String) {
        val provider = searchProviderForAction(providerId) ?: return
        val normalized = apiKey.trim()
        if (normalized.isEmpty() || normalized.length > 4096 || normalized.any { it.code !in 33..126 }) {
            error.value = "请输入有效的 ${provider.displayName} API Key。"
            return
        }
        val oldRef = app.container.settings.webSearchSecretRef(provider)
        val newRef = "search:${provider.id}:${UUID.randomUUID()}"
        try {
            app.container.secrets.put(newRef, normalized.toCharArray())
            app.container.settings.setWebSearch(newRef, enabled = true, provider = provider)
            oldRef?.takeIf { it != newRef }?.let { old ->
                runCatching { app.container.secrets.inventory().retireIfUnreferenced(old) }
            }
            webSearchStatus.value = "${provider.displayName} 已保存并启用。"
            error.value = null
        } catch (_: Exception) {
            runCatching { app.container.secrets.inventory().retireIfUnreferenced(newRef) }
            webSearchStatus.value = "联网搜索配置未保存。"
            error.value = "无法安全保存联网搜索凭据。"
        }
        refreshRuntimeFacts()
    }

    fun selectWebSearchProvider(value: String) {
        val provider = WebSearchProvider.fromId(value) ?: return
        runCatching { app.container.settings.selectWebSearchProvider(provider) }
            .onSuccess {
                // Change the label immediately; async facts never retain the previous key controls.
                runtimeFacts.value = runtimeFacts.value.copy(
                    webSearchProviderId = provider.id, webSearchConfigured = false, webSearchEnabled = false,
                )
                webSearchStatus.value = ""; error.value = null
            }
            .onFailure { error.value = "无法保存搜索服务商。" }
        refreshRuntimeFacts()
    }

    fun setWebSearchEnabled(providerId: String, enabled: Boolean) {
        val provider = searchProviderForAction(providerId) ?: return
        val ref = app.container.settings.webSearchSecretRef(provider)
        if (ref == null || app.container.secrets.inventory().status(ref) != SecretStatus.ACTIVE) {
            error.value = "请先保存当前服务商的 API Key。"
            return
        }
        app.container.settings.setWebSearch(ref, enabled, provider)
        webSearchStatus.value = if (enabled) "联网搜索已启用。" else "联网搜索已停用。"
        error.value = null
        refreshRuntimeFacts()
    }

    fun clearWebSearch(providerId: String) {
        val provider = searchProviderForAction(providerId) ?: return
        val oldRef = app.container.settings.webSearchSecretRef(provider)
        try {
            app.container.settings.setWebSearch(null, enabled = false, provider = provider)
            oldRef?.let { app.container.secrets.inventory().retireIfUnreferenced(it) }
            webSearchStatus.value = "联网搜索凭据已移除。"
            error.value = null
        } catch (_: Exception) {
            webSearchStatus.value = "联网搜索凭据未能完整移除。"
            error.value = "无法安全移除联网搜索凭据。"
        }
        refreshRuntimeFacts()
    }

    fun theme(value: String) {
        val mode = when (value) {
            "light" -> ThemePreference.LIGHT; "dark" -> ThemePreference.DARK
            "66ccff" -> ThemePreference.COLOR_66CCFF; else -> ThemePreference.SYSTEM
        }
        app.container.settings.setTheme(mode)
        preferences.value = app.container.settings.get()
    }

    fun language(value: String) {
        app.container.settings.setLocale(when (value) {
            "zh-CN" -> LocalePreference.ZH_CN; "en-US" -> LocalePreference.EN_US; else -> LocalePreference.SYSTEM
        })
        preferences.value = app.container.settings.get()
    }

    fun inspector(value: Boolean) {
        app.container.uiPreferences.edit().putBoolean(REQUEST_INSPECTOR_PREFERENCE, value).apply()
        inspectorEnabled.value = value
    }

    fun setDiagnosticsEnabled(value: Boolean) {
        try {
            app.diagnostics.setEnabled(value)
            diagnosticsStatus.value = if (value) SettingsDiagnosticsFeedback.ENABLED else SettingsDiagnosticsFeedback.DISABLED
            error.value = null
        } catch (failure: Exception) {
            diagnosticsStatus.value = SettingsDiagnosticsFeedback.SAVE_FAILED
            error.value = null
        }
        refreshRuntimeFacts()
    }

    fun setDiagnosticsLogLevel(value: String) {
        val level = when (value) {
            "DEBUG" -> DiagnosticLevel.DEBUG
            "INFO" -> DiagnosticLevel.INFO
            else -> return
        }
        try {
            app.diagnostics.setLogLevel(level)
            diagnosticsStatus.value = SettingsDiagnosticsFeedback.NONE
            error.value = null
        } catch (failure: Exception) {
            diagnosticsStatus.value = SettingsDiagnosticsFeedback.SAVE_FAILED
            error.value = null
        }
        refreshRuntimeFacts()
    }

    fun exportDiagnosticsTo(uri: Uri?) {
        if (uri == null) {
            diagnosticsStatus.value = SettingsDiagnosticsFeedback.EXPORT_CANCELLED
            return
        }
        viewModelScope.launch {
            try {
                diagnosticsStatus.value = SettingsDiagnosticsFeedback.EXPORTING
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开导出位置。")
                    output.use { app.diagnostics.exportTo(it) }
                }
                diagnosticsStatus.value = SettingsDiagnosticsFeedback.EXPORTED
                error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Deliberately do not clear records here: a failed SAF write must leave the
                // evidence available for another destination or a later export attempt.
                diagnosticsStatus.value = SettingsDiagnosticsFeedback.EXPORT_FAILED
                error.value = null
            }
        }
    }

    fun clearDiagnostics() {
        try {
            app.diagnostics.clear()
            diagnosticsStatus.value = SettingsDiagnosticsFeedback.CLEARED
            error.value = null
        } catch (failure: Exception) {
            diagnosticsStatus.value = SettingsDiagnosticsFeedback.CLEAR_FAILED
            error.value = null
        }
        refreshRuntimeFacts()
    }

    fun unlockRootPrompt() {
        app.container.settings.setGlobalRootPrompt(app.container.settings.get().globalRootPromptOverride, unlocked = true)
        preferences.value = app.container.settings.get()
        refreshRuntimeFacts()
    }

    fun saveRootPrompt(text: String) {
        app.container.settings.setGlobalRootPrompt(text, unlocked = true)
        preferences.value = app.container.settings.get()
        refreshRuntimeFacts()
    }

    fun restoreRootPrompt() {
        app.container.settings.restoreDefaultGlobalRootPrompt()
        preferences.value = app.container.settings.get()
        refreshRuntimeFacts()
    }

    fun exportAgents(): List<Pair<String, String>> = app.container.agents.listOverviews().map { it.id to it.name }

    fun prepareExport(agentId: String, includeSkillPackages: Boolean = false,
        includeKnowledgeContent: Boolean = false, includeConversations: Boolean = false,
        includeWorldBookContent: Boolean = false, ready: () -> Unit) {
        if (transferRunning) { error.value = "另一项导入或导出尚未结束。"; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { require(app.container.agents.get(agentId) != null) { "Agent 已不存在。" } }
                pendingExport = agentId to TransferOptions(includeSkillPackages, includeKnowledgeContent, includeConversations, includeWorldBookContent)
                exportStatus.value = "请选择 ZIP 保存位置。所勾选内容将流式写入；不包含密钥或可继承的授权。"
                error.value = null
                ready()
            } catch (failure: Exception) { error.value = SecretRedactor.redact(failure.message ?: "导出准备失败。") }
        }
    }

    fun exportTo(uri: Uri?) {
        val selection = pendingExport
        pendingExport = null
        if (uri == null || selection == null) { exportStatus.value = "已取消导出。"; return }
        if (transferRunning) { error.value = "另一项导入或导出尚未结束。"; return }
        transferRunning = true
        viewModelScope.launch {
            try {
                exportStatus.value = "正在校验并导出 ZIP…"
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开导出位置。")
                    output.use { app.container.transfer.exportArchive(selection.first, selection.second, it) }
                }
                exportStatus.value = "ZIP 已保存。跨设备导入后需重新配置凭据与权限；历史会话保留但不自动继续执行。"
                error.value = null
            } catch (cancelled: CancellationException) {
                // A cancelled export must not leave a zero-byte or truncated ZIP
                // behind either; the SAF document was created before streaming ran.
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri)
                }
                throw cancelled
            } catch (failure: Exception) {
                // The SAF document was created before validation/streaming ran.  A failed
                // export must not leave a zero-byte or truncated ZIP behind.
                val partialFileRemoved = runCatching {
                    android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri)
                }.getOrDefault(false)
                val detail = safeExportFailureDetail(failure)
                exportStatus.value = "导出失败：$detail" +
                    if (partialFileRemoved) "" else " 未完成文件未能自动删除，请手动移除。"
                error.value = exportStatus.value
            } finally { transferRunning = false }
        }
    }

    fun importFrom(uri: Uri?, conflictPolicy: TransferConflictPolicy = TransferConflictPolicy.REJECT) {
        if (uri == null) return
        if (transferRunning) { error.value = "另一项导入或导出尚未结束。"; return }
        transferRunning = true
        viewModelScope.launch {
            try {
                exportStatus.value = "正在校验并导入配置…"
                val result = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri) ?: error("无法读取导入文件。")
                    input.buffered().use { stream ->
                        stream.mark(4)
                        val signature = ByteArray(4)
                        val signatureLength = stream.read(signature)
                        stream.reset()
                        if (signatureLength == 4 && signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                            app.container.transfer.importArchive(stream, conflictPolicy)
                        } else {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 16 * 1024 * 1024) { "旧 JSON 配置超过 16 MiB 上限；完整内容请使用流式 ZIP。" }
                            output.write(buffer, 0, count)
                        }
                        app.container.transfer.importBundle(output.toByteArray().toString(Charsets.UTF_8), conflictPolicy)
                        }
                    }
                }
                result.agentId?.let { app.container.uiPreferences.edit().putString("selected-agent", it).apply() }
                exportStatus.value = "配置导入成功。${result.warnings.joinToString("；")} 密钥需重新配置，Skill 权限不会自动恢复。"
                Toast.makeText(app, "配置导入成功，请检查导入结果和密钥配置。", Toast.LENGTH_LONG).show()
                error.value = null
            } catch (failure: Exception) {
                error.value = SecretRedactor.redact(failure.message ?: "导入被拒绝；原有数据未清空。")
                exportStatus.value = "导入失败；现有配置未替换。"
                Toast.makeText(app, "导入失败，请查看设置页错误信息。", Toast.LENGTH_LONG).show()
            } finally { transferRunning = false }
        }
    }

    /** Manual checks bypass daily throttling and share the process-wide release task. */
    fun checkUpdates() {
        app.container.appUpdates.check(manual = true)
    }
}

private fun SettingsAuthorityProviderState.toUiState() =
    runtime.mobileagent.feature.settings.AuthorityUiState(
        authority = authority.name,
        userIntentEnabled = userIntent != runtime.mobileagent.domain.AuthorityUserIntent.NONE,
        platformGrant = platformGrant.name,
        availability = availability.name,
        connection = connection.name,
        configured = configured,
        trust = trust?.name.orEmpty(),
        rootShellConsentEnabled = rootShellConsentEnabled,
    )

private fun SettingsSafGrantState.toUiState() =
    runtime.mobileagent.feature.settings.SafWorkspaceUiState(
        configured = configured,
        readGranted = readGranted,
        writeGranted = writeGranted,
        persisted = persisted,
        status = status.name,
    )

/**
 * Optional host capability: explicit foreground confirmation of an unconfirmed Root outcome.
 *
 * Required wiring (see the UI package report): `RuntimeIntegration` implements this by delegating
 * to `RootRuntimeCoordinator.acknowledgeDegradedRecovery(authority, userConfirmed = true,
 * authorityManager, shizukuState)` and returning the refreshed snapshot. Declared here so the
 * settings entry can ship before the runtime side lands; when no port implements it, the action
 * reports "not wired" and changes nothing.
 */
interface RootRecoveryAuthorityPort {
    fun acknowledgeRootRecovery(authority: Authority): SettingsAuthorityMutation
}

/**
 * Channel ids accepted from the Settings UI. Enum names are canonical; the extra spellings are the
 * values older screens, deep links and persisted intents already send, so they stay accepted.
 */
private val authorityChannelIds: Map<String, Authority> = mapOf(
    "NONE" to Authority.NONE,
    "APP_PRIVATE" to Authority.NONE,
    "SHIZUKU" to Authority.SHIZUKU,
    "SHIZUKU_BINDER" to Authority.SHIZUKU,
    "WIRED_ADB" to Authority.WIRED_ADB,
    "WIRED-ADB" to Authority.WIRED_ADB,
    "WIRED ADB" to Authority.WIRED_ADB,
    "ROOT_DIRECT" to Authority.ROOT_DIRECT,
    "ROOT-DIRECT" to Authority.ROOT_DIRECT,
    "ROOT DIRECT" to Authority.ROOT_DIRECT,
    "SHIZUKU_ROOT" to Authority.SHIZUKU_ROOT,
    "SHIZUKU-ROOT" to Authority.SHIZUKU_ROOT,
    "SHIZUKU ROOT" to Authority.SHIZUKU_ROOT,
)
