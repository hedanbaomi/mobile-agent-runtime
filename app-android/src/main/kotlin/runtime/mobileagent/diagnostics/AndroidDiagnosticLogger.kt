// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import runtime.mobileagent.BuildConfig
import java.io.File
import java.io.OutputStream

/** Android-only adapter. It touches only files and a small SharedPreferences flag. */
class AndroidDiagnosticLogger private constructor(
    val store: RollingDiagnosticLogStore,
) {
    companion object {
        private const val ENABLED_KEY = "enabled"
        // Shared by all facades in this app process so the fallback remains a stable session HMAC.
        private val sessionReferenceHasher by lazy { DiagnosticReferenceHasher.session() }

        private fun androidStore(context: Context, directory: File, preferencesName: String): RollingDiagnosticLogStore {
            val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            val preferenceStore = object : DiagnosticPreferenceStore {
                override fun isEnabled(): Boolean = preferences.getBoolean(ENABLED_KEY, false)

                override fun setEnabled(enabled: Boolean) {
                    // commit keeps the user's opt-in across an immediate process death.
                    check(preferences.edit().putBoolean(ENABLED_KEY, enabled).commit()) {
                        "无法保存诊断开关。"
                    }
                }
            }
            return RollingDiagnosticLogStore(
                rootDirectory = directory,
                preferences = preferenceStore,
                buildInfo = DiagnosticBuildInfo(
                    revision = BuildConfig.GIT_REVISION,
                    dirty = BuildConfig.GIT_DIRTY,
                    schemaVersion = BuildConfig.DB_SCHEMA_VERSION,
                    buildTimeUtc = BuildConfig.BUILD_TIME_UTC,
                    fingerprint = Build.FINGERPRINT,
                ),
                processId = Process::myPid,
                referenceHasher = sessionReferenceHasher,
            )
        }

        /** A permanently disabled facade used only when Android diagnostics initialization fails. */
        fun disabledFallback(): AndroidDiagnosticLogger = AndroidDiagnosticLogger(
            RollingDiagnosticLogStore(
                rootDirectory = File("disabled-diagnostics"),
                preferences = object : DiagnosticPreferenceStore {
                    override fun isEnabled(): Boolean = false
                    override fun setEnabled(enabled: Boolean) = error("诊断记录在此设备上不可用。")
                },
                buildInfo = DiagnosticBuildInfo("unknown", true, 0, "unknown", "unknown"),
            ),
        )
    }

    constructor(
        context: Context,
        directory: File = File(context.applicationContext.filesDir, "diagnostics"),
        preferencesName: String = "diagnostic-preferences",
    ) : this(androidStore(context, directory, preferencesName))

    private var installedHandler: DiagnosticUncaughtExceptionHandler? = null

    val isEnabled: Boolean get() = store.isEnabled

    fun setEnabled(enabled: Boolean) = store.setEnabled(enabled)

    fun recordProcessStarted(): Boolean = store.recordProcessStarted()

    fun recordCapabilityToggle(capability: String, enabled: Boolean): Boolean =
        store.recordCapabilityToggle(capability, enabled)

    fun recordProviderModelSave(
        phase: ProviderModelSavePhase,
        capabilities: Set<String>,
        role: String,
        failure: Throwable? = null,
    ): Boolean = store.recordProviderModelSave(phase, capabilities, role, failure)

    fun recordKnowledgeImportStart(kind: String, total: Int): Boolean = store.recordKnowledgeImportStart(kind, total)

    fun recordKnowledgeImportProgress(kind: String, stage: String, completed: Int, total: Int): Boolean =
        store.recordKnowledgeImportProgress(kind, stage, completed, total)

    fun recordKnowledgeImportEnqueued(kind: String, count: Int): Boolean = store.recordKnowledgeImportEnqueued(kind, count)

    fun recordKnowledgeImportStaged(kind: String, count: Int): Boolean = store.recordKnowledgeImportStaged(kind, count)

    fun recordKnowledgeImportFailed(kind: String, stage: String, count: Int, failure: Throwable): Boolean =
        store.recordKnowledgeImportFailed(kind, stage, count, failure)

    fun recordSkillInspectSuccess(count: Int = 1): Boolean = store.recordSkillInspectSuccess(count)

    fun recordSkillInspectFailed(count: Int, failure: Throwable): Boolean = store.recordSkillInspectFailed(count, failure)

    fun recordSkillInstallSuccess(): Boolean = store.recordSkillInstallSuccess()

    fun recordSkillInstallFailed(failure: Throwable? = null, errorCode: String = "unknown"): Boolean =
        store.recordSkillInstallFailed(failure, errorCode)

    fun recordBatchWorkerStart(): Boolean = store.recordBatchWorkerStart()

    fun recordBatchWorkerComplete(): Boolean = store.recordBatchWorkerComplete()

    fun recordBatchWorkerFailed(failure: Throwable): Boolean = store.recordBatchWorkerFailed(failure)

    /** Low-level compatibility seam; callers should prefer one of the typed record methods below. */
    fun record(event: String, fields: Map<String, Any?> = emptyMap()): Boolean = store.record(event, fields)

    fun recordAuthoritySelectionChanged(record: AuthoritySelectionChangedRecord): Boolean =
        store.recordAuthoritySelectionChanged(record)

    fun recordAuthorityStateChanged(record: AuthorityStateChangedRecord): Boolean =
        store.recordAuthorityStateChanged(record)

    fun recordShizukuLifecycle(record: ShizukuLifecycleRecord): Boolean = store.recordShizukuLifecycle(record)

    fun recordWiredAdbLifecycle(record: WiredAdbLifecycleRecord): Boolean = store.recordWiredAdbLifecycle(record)

    fun recordWorkspaceGrantChanged(record: WorkspaceGrantChangedRecord): Boolean =
        store.recordWorkspaceGrantChanged(record)

    fun recordWorkspaceOperationState(record: WorkspaceOperationStateRecord): Boolean =
        store.recordWorkspaceOperationState(record)

    fun recordSkillMemoryOperationState(record: SkillMemoryOperationStateRecord): Boolean =
        store.recordSkillMemoryOperationState(record)

    fun recordDangerousModeChanged(record: DangerousModeChangedRecord): Boolean =
        store.recordDangerousModeChanged(record)

    fun recordShellToolExposureChanged(record: ShellToolExposureChangedRecord): Boolean =
        store.recordShellToolExposureChanged(record)

    fun recordToolApprovalState(record: ToolApprovalStateRecord): Boolean = store.recordToolApprovalState(record)

    fun recordShellExecutionState(record: ShellExecutionStateRecord): Boolean =
        store.recordShellExecutionState(record)

    fun recordBridgeRequestState(record: BridgeRequestStateRecord): Boolean =
        store.recordBridgeRequestState(record)

    fun recordDiagnosticDropSummary(record: DiagnosticDropSummaryRecord): Boolean =
        store.recordDiagnosticDropSummary(record)

    fun recordRuntimeToolingUnavailable(record: RuntimeToolingUnavailableRecord): Boolean =
        store.recordRuntimeToolingUnavailable(record)

    fun status(): DiagnosticStatus = store.status()

    fun exportTo(output: OutputStream) = store.exportTo(output)

    fun clear() = store.clear()

    /** Install once per app instance and preserve/delegate to the handler that was present. */
    @Synchronized
    fun installUncaughtExceptionHandler() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (installedHandler != null && current === installedHandler) return
        val handler = DiagnosticUncaughtExceptionHandler(store, current)
        installedHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }
}
