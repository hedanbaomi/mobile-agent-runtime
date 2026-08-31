// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.diagnostics

import runtime.mobileagent.provider.SecretRedactor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The small, fixed schema used by in-app diagnostics. It is deliberately not a general logger. */
enum class DiagnosticLevel(val wireName: String) {
    INFO("INFO"),
    ERROR("ERROR"),
}

data class DiagnosticBuildInfo(
    val revision: String,
    val dirty: Boolean,
    val schemaVersion: Int,
    val buildTimeUtc: String,
    val fingerprint: String,
)

data class DiagnosticStatus(
    val enabled: Boolean,
    val currentBytes: Long,
    val previousBytes: Long,
    val lastCrashBytes: Long,
    val totalBytes: Long,
    val totalLimitBytes: Long,
    val writeFailureCount: Long = 0,
    val droppedEventCount: Long = 0,
    val droppedByteCount: Long = 0,
    val health: DiagnosticHealth = DiagnosticHealth.HEALTHY,
) {
    val sizeBytes: Long get() = totalBytes
    val failureCount: Long get() = writeFailureCount
    val dropCount: Long get() = droppedEventCount
}

/** A process-local HMAC used for identifiers which are controlled by a model or user. */
class DiagnosticReferenceHasher private constructor(private val key: ByteArray) {
    fun hash(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.trim().take(512).toByteArray(StandardCharsets.UTF_8))
            .copyOf(REFERENCE_BYTES)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val REFERENCE_BYTES = 16
        const val REFERENCE_LENGTH = REFERENCE_BYTES * 2

        /**
         * The key is intentionally never written to a normal file. Android callers may replace
         * this with an app-local Keystore-backed implementation; a session HMAC is the safe
         * fallback when durable protected-key storage is unavailable.
         */
        fun session(): DiagnosticReferenceHasher {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            return DiagnosticReferenceHasher(key)
        }
    }
}

enum class DiagnosticHealth(val wireName: String) {
    HEALTHY("healthy"),
    DEGRADED("degraded"),
    DISABLED("disabled"),
}

enum class DiagnosticAuthority(val wireName: String) {
    NONE("none"),
    SHIZUKU("shizuku"),
    WIRED_ADB("wired_adb"),
}

enum class DiagnosticAuthorityState(val wireName: String) {
    UNAVAILABLE("unavailable"),
    AVAILABLE("available"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    REAUTH_REQUIRED("reauth_required"),
    FAILED("failed"),
    UNKNOWN("unknown"),
}

enum class DiagnosticLifecycleState(val wireName: String) {
    STARTED("started"),
    READY("ready"),
    STOPPED("stopped"),
    DISCONNECTED("disconnected"),
    FAILED("failed"),
    UNKNOWN("unknown"),
}

enum class DiagnosticGrantScope(val wireName: String) {
    NONE("none"),
    READ("read"),
    WRITE("write"),
    READ_WRITE("read_write"),
    UNKNOWN("unknown"),
}

enum class DiagnosticOperation(val wireName: String) {
    ENUMERATE("enumerate"),
    READ("read"),
    WRITE("write"),
    DELETE("delete"),
    SEARCH("search"),
    APPEND("append"),
    REPLACE("replace"),
    UNKNOWN("unknown"),
}

enum class DiagnosticToolCapability(val wireName: String) {
    WORKSPACE_READ("workspace_read"),
    WORKSPACE_WRITE("workspace_write"),
    MEMORY_READ("memory_read"),
    MEMORY_WRITE("memory_write"),
    SHELL_EXECUTE("shell_execute"),
    SEARCH("search"),
    UNKNOWN("unknown"),
}

enum class DiagnosticOperationState(val wireName: String) {
    STARTED("started"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    DENIED("denied"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
}

enum class DiagnosticDangerousModePolicy(val wireName: String) {
    DISABLED("disabled"),
    CONFIRM_HIGH_RISK("confirm_high_risk"),
    AUTONOMOUS("autonomous"),
    UNKNOWN("unknown"),
}

enum class DiagnosticExposureState(val wireName: String) {
    EXPOSED("exposed"),
    HIDDEN("hidden"),
    BLOCKED("blocked"),
    UNKNOWN("unknown"),
}

enum class DiagnosticApprovalState(val wireName: String) {
    REQUESTED("requested"),
    APPROVED("approved"),
    DENIED("denied"),
    EXPIRED("expired"),
    INVALIDATED("invalidated"),
    UNKNOWN("unknown"),
}

enum class DiagnosticTerminalState(val wireName: String) {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    TIMED_OUT("timed_out"),
    CANCELLED("cancelled"),
    DENIED("denied"),
    UNKNOWN("unknown"),
}

enum class DiagnosticBridgeRequestState(val wireName: String) {
    RECEIVED("received"),
    AUTHENTICATED("authenticated"),
    REJECTED("rejected"),
    STARTED("started"),
    COMPLETED("completed"),
    FAILED("failed"),
    DISCONNECTED("disconnected"),
    UNKNOWN("unknown"),
}

enum class DiagnosticLimitBucket(val wireName: String) {
    NONE("none"),
    TINY("tiny"),
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
    UNKNOWN("unknown"),
}

data class AuthoritySelectionChangedRecord(
    val selectedAuthority: DiagnosticAuthority,
    val previousAuthority: DiagnosticAuthority? = null,
    val requestRef: String? = null,
)

data class AuthorityStateChangedRecord(
    val authority: DiagnosticAuthority,
    val state: DiagnosticAuthorityState,
    val previousState: DiagnosticAuthorityState? = null,
    val errorCode: String = "unknown",
)

data class ShizukuLifecycleRecord(
    val state: DiagnosticLifecycleState,
    val errorCode: String = "unknown",
    val requestRef: String? = null,
)

data class WiredAdbLifecycleRecord(
    val state: DiagnosticLifecycleState,
    val errorCode: String = "unknown",
    val requestRef: String? = null,
)

data class WorkspaceGrantChangedRecord(
    val workspaceId: String,
    val scope: DiagnosticGrantScope,
    val granted: Boolean,
    val requestRef: String? = null,
    val errorCode: String = "unknown",
)

data class WorkspaceOperationStateRecord(
    val workspaceId: String,
    val operation: DiagnosticOperation,
    val state: DiagnosticOperationState,
    val count: Int = 0,
    val requestRef: String? = null,
    val errorCode: String = "unknown",
)

data class SkillMemoryOperationStateRecord(
    val skillId: String,
    val operation: DiagnosticOperation,
    val state: DiagnosticOperationState,
    val count: Int = 0,
    val requestRef: String? = null,
    val errorCode: String = "unknown",
)

data class DangerousModeChangedRecord(
    val enabled: Boolean,
    val policy: DiagnosticDangerousModePolicy,
    val requestRef: String? = null,
)

data class ShellToolExposureChangedRecord(
    val agentId: String,
    val exposed: DiagnosticExposureState,
    val skillId: String? = null,
    val authority: DiagnosticAuthority = DiagnosticAuthority.NONE,
    val reasonCode: String = "unknown",
    val requestRef: String? = null,
)

data class ToolApprovalStateRecord(
    val callId: String,
    val state: DiagnosticApprovalState,
    val approvalId: String? = null,
    val agentId: String? = null,
    val skillId: String? = null,
    val requestRef: String? = null,
    val reasonCode: String = "unknown",
    val capability: DiagnosticToolCapability = DiagnosticToolCapability.UNKNOWN,
    val authority: DiagnosticAuthority = DiagnosticAuthority.NONE,
    val sessionRef: String? = null,
)

data class ShellExecutionStateRecord(
    val commandSha256: String,
    val terminalState: DiagnosticTerminalState,
    val authority: DiagnosticAuthority,
    val limitBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
    val stdoutBytes: Int = 0,
    val stderrBytes: Int = 0,
    val durationBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
    val requestRef: String? = null,
    val callId: String? = null,
    val agentId: String? = null,
    val skillId: String? = null,
)

data class BridgeRequestStateRecord(
    val state: DiagnosticBridgeRequestState,
    val authority: DiagnosticAuthority,
    val requestRef: String,
    val errorCode: String = "unknown",
    val durationBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
    val count: Int = 0,
)

data class DiagnosticDropSummaryRecord(
    val droppedEvents: Int,
    val droppedBytes: Int,
    val failureCount: Int,
    val health: DiagnosticHealth,
    val reasonCode: String = "unknown",
)

enum class RuntimeToolingUnavailableCode(val wireName: String) {
    TOOL_EXECUTION_CONTEXT_UNAVAILABLE("TOOL_EXECUTION_CONTEXT_UNAVAILABLE"),
    TOOL_EXECUTOR_FACTORY_UNAVAILABLE("TOOL_EXECUTOR_FACTORY_UNAVAILABLE"),
}

data class RuntimeToolingUnavailableRecord(
    val errorCode: RuntimeToolingUnavailableCode,
    val sessionRef: String? = null,
    val runRef: String? = null,
)

// Short aliases keep call sites readable while the *Record names remain the canonical API docs.
typealias AuthoritySelectionChanged = AuthoritySelectionChangedRecord
typealias AuthorityStateChanged = AuthorityStateChangedRecord
typealias ShizukuLifecycle = ShizukuLifecycleRecord
typealias WiredAdbLifecycle = WiredAdbLifecycleRecord
typealias WorkspaceGrantChanged = WorkspaceGrantChangedRecord
typealias WorkspaceOperationState = WorkspaceOperationStateRecord
typealias SkillMemoryOperationState = SkillMemoryOperationStateRecord
typealias DangerousModeChanged = DangerousModeChangedRecord
typealias ShellToolExposureChanged = ShellToolExposureChangedRecord
typealias ToolApprovalState = ToolApprovalStateRecord
typealias ShellExecutionState = ShellExecutionStateRecord
typealias BridgeRequestState = BridgeRequestStateRecord
typealias DiagnosticDropSummary = DiagnosticDropSummaryRecord
typealias RuntimeToolingUnavailable = RuntimeToolingUnavailableRecord

/** A deliberately tiny persistence seam, allowing the file logger to be tested without Android. */
interface DiagnosticPreferenceStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

/**
 * Additional restrictions applied after [SecretRedactor]. Diagnostics only use a few explicitly
 * whitelisted fields, but this is still applied to every value before it reaches disk.
 */
object DiagnosticSanitizer {
    private val url = Regex("""(?i)\b(?:https?|wss?)://[^\s<>\"']+""")
    private val windowsPath = Regex("""(?i)(?:[a-z]:[\\/]|\\\\)[^\s,;)]*""")
    private val unixPath = Regex("""(?<![\w:])/(?:[A-Za-z0-9._-]+/)+[A-Za-z0-9._-]*""")
    private val query = Regex("""\?[^\s]*""")

    fun text(value: String, maxLength: Int = 256): String {
        require(maxLength > 0)
        var result = SecretRedactor.redact(value)
        // Do this before path/query handling so a URL cannot leak its host or query parameters.
        result = url.replace(result, "[url-redacted]")
        result = windowsPath.replace(result, "[path-redacted]")
        result = unixPath.replace(result, "[path-redacted]")
        result = query.replace(result, "?[redacted]")
        result = result.map { character ->
            when {
                character == '\n' || character == '\r' || character == '\t' -> ' '
                character.code < 0x20 -> ' '
                else -> character
            }
        }.joinToString("")
        return result.take(maxLength)
    }

    fun exceptionType(value: String): String {
        val cleaned = text(value, 160)
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' || it == '-' }
            .take(160)
        return cleaned.ifBlank { "unknown" }
    }

    /** Keep only class/method/line metadata; source file names and paths are intentionally omitted. */
    fun stackTrace(throwable: Throwable, maxFrames: Int = 12): String {
        val frames = throwable.stackTrace.take(maxFrames.coerceIn(1, 12))
        if (frames.isEmpty()) return "none"
        return frames.joinToString(";") { frame ->
            val owner = exceptionType(frame.className)
            val method = exceptionType(frame.methodName)
            val line = frame.lineNumber.coerceAtLeast(-1)
            "$owner.$method:$line"
        }.take(1_800)
    }
}

/**
 * A bounded, append-only-in-practice NDJSON store. It has no database, Keystore, network, or
 * Android dependency, which is important because the application also has an isolated Python
 * process. Callers must explicitly enable it before any event is written.
 */
class RollingDiagnosticLogStore(
    val rootDirectory: File,
    private val preferences: DiagnosticPreferenceStore,
    private val buildInfo: DiagnosticBuildInfo,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val nowUtc: () -> String = ::currentUtc,
    private val processId: () -> Int = { 0 },
    private val threadName: () -> String = { Thread.currentThread().name },
    private val referenceHasher: DiagnosticReferenceHasher = DiagnosticReferenceHasher.session(),
) {
    companion object {
        const val SCHEMA_VERSION = 2
        const val CURRENT_FILE_NAME = "current.ndjson"
        const val PREVIOUS_FILE_NAME = "previous.ndjson"
        const val LAST_CRASH_FILE_NAME = "last-crash.ndjson"
        const val MAX_CURRENT_BYTES = 256 * 1024
        const val MAX_PREVIOUS_BYTES = 256 * 1024
        const val MAX_LAST_CRASH_BYTES = 32 * 1024
        const val MAX_EVENT_BYTES = 4 * 1024
        const val MAX_EXPORT_BYTES = 640 * 1024
        const val MAX_COUNT = 1_000_000
        const val MAX_REFERENCE_LENGTH = DiagnosticReferenceHasher.REFERENCE_LENGTH

        private fun currentUtc(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private val lock = Any()
    private var writeFailureCount = 0L
    private var droppedEventCount = 0L
    private var droppedByteCount = 0L

    private val eventFields = mapOf(
        "process_started" to setOf<String>(),
        "diagnostics_toggle" to setOf("enabled"),
        "capability_toggle" to setOf("capability", "enabled"),
        "provider_model_save_start" to setOf("capabilities", "role"),
        "provider_model_save_success" to setOf("capabilities", "role"),
        "provider_model_save_failed" to setOf("capabilities", "role", "exceptionType"),
        "knowledge_import_start" to setOf("kind", "stage", "total"),
        "knowledge_import_progress" to setOf("kind", "stage", "completed", "total"),
        "knowledge_import_enqueued" to setOf("kind", "stage", "count"),
        "knowledge_import_staged" to setOf("kind", "stage", "count"),
        "knowledge_import_failed" to setOf("kind", "stage", "count", "exceptionType", "errorCode"),
        "skill_inspect_success" to setOf("kind", "stage", "count"),
        "skill_inspect_failed" to setOf("kind", "stage", "count", "exceptionType", "errorCode"),
        "skill_install_success" to setOf("kind", "stage", "count"),
        "skill_install_failed" to setOf("kind", "stage", "count", "exceptionType", "errorCode"),
        "batch_worker_start" to setOf("kind", "stage", "count"),
        "batch_worker_complete" to setOf("kind", "stage", "count"),
        "batch_worker_failed" to setOf("kind", "stage", "count", "exceptionType", "errorCode"),
        "uncaught_exception" to setOf("exceptionType", "stack"),
        "authority_selection_changed" to setOf("selectedAuthority", "previousAuthority", "requestRef"),
        "authority_state_changed" to setOf("authority", "state", "previousState", "errorCode"),
        "shizuku_lifecycle" to setOf("state", "errorCode", "requestRef"),
        "wired_adb_lifecycle" to setOf("state", "errorCode", "requestRef"),
        "workspace_grant_changed" to setOf("workspaceRef", "scope", "granted", "requestRef", "errorCode"),
        "workspace_operation_state" to setOf("workspaceRef", "operation", "state", "count", "requestRef", "errorCode"),
        "skill_memory_operation_state" to setOf("skillRef", "operation", "state", "count", "requestRef", "errorCode"),
        "dangerous_mode_changed" to setOf("enabled", "policy", "requestRef"),
        "shell_tool_exposure_changed" to setOf("agentRef", "skillRef", "authority", "exposed", "reasonCode", "requestRef"),
        "tool_approval_state" to setOf(
            "agentRef", "skillRef", "callRef", "approvalRef", "state", "requestRef", "reasonCode",
            "capability", "authority", "sessionRef",
        ),
        "shell_execution_state" to setOf(
            "requestRef", "callRef", "agentRef", "skillRef", "authority", "commandSha256", "limitBucket",
            "terminalState", "stdoutBytes", "stderrBytes", "durationBucket",
        ),
        "bridge_request_state" to setOf("requestRef", "authority", "state", "errorCode", "durationBucket", "count"),
        "diagnostic_drop_summary" to setOf("droppedEvents", "droppedBytes", "failureCount", "health", "reasonCode"),
        "runtime_tooling_unavailable" to setOf("errorCode", "sessionRef", "runRef"),
    )

    private val currentFile get() = File(rootDirectory, CURRENT_FILE_NAME)
    private val previousFile get() = File(rootDirectory, PREVIOUS_FILE_NAME)
    private val lastCrashFile get() = File(rootDirectory, LAST_CRASH_FILE_NAME)

    val isEnabled: Boolean
        get() = enabledSafely()

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (enabled) {
                runCatching { preferences.setEnabled(true) }
                    .onFailure { noteWriteFailure(0) }
                if (enabledSafely()) {
                    val line = renderLine("diagnostics_toggle", DiagnosticLevel.INFO, mapOf("enabled" to true), threadName())
                    if (!appendCurrent(line)) noteWriteFailure(line.toByteArray(StandardCharsets.UTF_8).size)
                }
            } else {
                if (enabledSafely()) {
                    val line = renderLine("diagnostics_toggle", DiagnosticLevel.INFO, mapOf("enabled" to false), threadName())
                    if (!appendCurrent(line)) noteWriteFailure(line.toByteArray(StandardCharsets.UTF_8).size)
                }
                runCatching { preferences.setEnabled(false) }
                    .onFailure { noteWriteFailure(0) }
            }
        }
    }

    /** Record one of the fixed events. Unknown event names or field keys are rejected. */
    fun record(event: String, fields: Map<String, Any?> = emptyMap()): Boolean = synchronized(lock) {
        if (!enabledSafely()) return@synchronized false
        val normalized = runCatching { normalizeFields(event, fields) }.getOrNull()
        if (normalized == null) {
            noteDrop(0)
            return@synchronized false
        }
        val line = runCatching { renderLine(event, levelFor(event), normalized, threadName()) }
            .getOrElse {
                noteWriteFailure(0)
                return@synchronized false
            }
        val written = runCatching { appendCurrent(line) }.getOrDefault(false)
        if (!written) noteWriteFailure(line.toByteArray(StandardCharsets.UTF_8).size)
        written
    }

    fun recordProcessStarted(): Boolean = record("process_started")

    fun recordCapabilityToggle(capability: String, enabled: Boolean): Boolean =
        record("capability_toggle", mapOf("capability" to capability, "enabled" to enabled))

    fun recordProviderModelSave(
        phase: ProviderModelSavePhase,
        capabilities: Set<String>,
        role: String,
        failure: Throwable? = null,
    ): Boolean {
        val fields = linkedMapOf<String, Any?>(
            "capabilities" to canonicalCapabilities(capabilities),
            "role" to canonicalRole(role),
        )
        if (phase == ProviderModelSavePhase.FAILURE) {
            fields["exceptionType"] = failure?.let { DiagnosticSanitizer.exceptionType(it.javaClass.name) } ?: "unknown"
        }
        return record(phase.eventName, fields)
    }

    fun recordKnowledgeImportStart(kind: String, total: Int): Boolean = record(
        "knowledge_import_start",
        mapOf("kind" to kind, "stage" to "staging", "total" to total.coerceIn(0, MAX_COUNT)),
    )

    fun recordKnowledgeImportProgress(kind: String, stage: String, completed: Int, total: Int): Boolean = record(
        "knowledge_import_progress",
        mapOf(
            "kind" to kind,
            "stage" to stage,
            "completed" to completed.coerceIn(0, MAX_COUNT),
            "total" to total.coerceIn(0, MAX_COUNT),
        ),
    )

    fun recordKnowledgeImportEnqueued(kind: String, count: Int): Boolean = record(
        "knowledge_import_enqueued",
        mapOf("kind" to kind, "stage" to "queued", "count" to count.coerceIn(0, MAX_COUNT)),
    )

    fun recordKnowledgeImportStaged(kind: String, count: Int): Boolean = record(
        "knowledge_import_staged",
        mapOf("kind" to kind, "stage" to "staged", "count" to count.coerceIn(0, MAX_COUNT)),
    )

    fun recordKnowledgeImportFailed(kind: String, stage: String, count: Int, failure: Throwable): Boolean = record(
        "knowledge_import_failed",
        mapOf(
            "kind" to kind,
            "stage" to stage,
            "count" to count.coerceIn(0, MAX_COUNT),
            "exceptionType" to failure.javaClass.name,
            "errorCode" to controlledErrorCode(failure),
        ),
    )

    fun recordSkillInspectSuccess(count: Int = 1): Boolean = record(
        "skill_inspect_success",
        mapOf("kind" to "skill", "stage" to "inspect", "count" to count.coerceIn(0, MAX_COUNT)),
    )

    fun recordSkillInspectFailed(count: Int, failure: Throwable): Boolean = record(
        "skill_inspect_failed",
        mapOf(
            "kind" to "skill",
            "stage" to "inspect",
            "count" to count.coerceIn(0, MAX_COUNT),
            "exceptionType" to failure.javaClass.name,
            "errorCode" to controlledErrorCode(failure),
        ),
    )

    fun recordSkillInstallSuccess(): Boolean = record(
        "skill_install_success",
        mapOf("kind" to "skill", "stage" to "install", "count" to 1),
    )

    fun recordSkillInstallFailed(failure: Throwable? = null, errorCode: String = "unknown"): Boolean = record(
        "skill_install_failed",
        mapOf(
            "kind" to "skill",
            "stage" to "install",
            "count" to 1,
            "exceptionType" to (failure?.javaClass?.name ?: "none"),
            "errorCode" to if (failure == null) errorCode else controlledErrorCode(failure),
        ),
    )

    fun recordBatchWorkerStart(): Boolean = record(
        "batch_worker_start",
        mapOf("kind" to "batch", "stage" to "start", "count" to 0),
    )

    fun recordBatchWorkerComplete(): Boolean = record(
        "batch_worker_complete",
        mapOf("kind" to "batch", "stage" to "complete", "count" to 0),
    )

    fun recordBatchWorkerFailed(failure: Throwable): Boolean = record(
        "batch_worker_failed",
        mapOf(
            "kind" to "batch",
            "stage" to "failed",
            "count" to 0,
            "exceptionType" to failure.javaClass.name,
            "errorCode" to controlledErrorCode(failure),
        ),
    )

    fun recordAuthoritySelectionChanged(record: AuthoritySelectionChangedRecord): Boolean = record(
        "authority_selection_changed",
        linkedMapOf<String, Any?>(
            "selectedAuthority" to record.selectedAuthority.wireName,
            "previousAuthority" to record.previousAuthority?.wireName,
            "requestRef" to record.requestRef,
        ).withoutNulls(),
    )

    fun recordAuthoritySelectionChanged(
        selectedAuthority: DiagnosticAuthority,
        previousAuthority: DiagnosticAuthority? = null,
        requestRef: String? = null,
    ): Boolean = recordAuthoritySelectionChanged(
        AuthoritySelectionChangedRecord(selectedAuthority, previousAuthority, requestRef),
    )

    fun recordAuthorityStateChanged(record: AuthorityStateChangedRecord): Boolean = record(
        "authority_state_changed",
        linkedMapOf<String, Any?>(
            "authority" to record.authority.wireName,
            "state" to record.state.wireName,
            "previousState" to record.previousState?.wireName,
            "errorCode" to record.errorCode,
        ).withoutNulls(),
    )

    fun recordAuthorityStateChanged(
        authority: DiagnosticAuthority,
        state: DiagnosticAuthorityState,
        previousState: DiagnosticAuthorityState? = null,
        errorCode: String = "unknown",
    ): Boolean = recordAuthorityStateChanged(
        AuthorityStateChangedRecord(authority, state, previousState, errorCode),
    )

    fun recordShizukuLifecycle(record: ShizukuLifecycleRecord): Boolean = record(
        "shizuku_lifecycle",
        linkedMapOf<String, Any?>(
            "state" to record.state.wireName,
            "errorCode" to record.errorCode,
            "requestRef" to record.requestRef,
        ).withoutNulls(),
    )

    fun recordShizukuLifecycle(
        state: DiagnosticLifecycleState,
        errorCode: String = "unknown",
        requestRef: String? = null,
    ): Boolean = recordShizukuLifecycle(ShizukuLifecycleRecord(state, errorCode, requestRef))

    fun recordWiredAdbLifecycle(record: WiredAdbLifecycleRecord): Boolean = record(
        "wired_adb_lifecycle",
        linkedMapOf<String, Any?>(
            "state" to record.state.wireName,
            "errorCode" to record.errorCode,
            "requestRef" to record.requestRef,
        ).withoutNulls(),
    )

    fun recordWiredAdbLifecycle(
        state: DiagnosticLifecycleState,
        errorCode: String = "unknown",
        requestRef: String? = null,
    ): Boolean = recordWiredAdbLifecycle(WiredAdbLifecycleRecord(state, errorCode, requestRef))

    fun recordWorkspaceGrantChanged(record: WorkspaceGrantChangedRecord): Boolean = record(
        "workspace_grant_changed",
        linkedMapOf<String, Any?>(
            "workspaceRef" to record.workspaceId,
            "scope" to record.scope.wireName,
            "granted" to record.granted,
            "requestRef" to record.requestRef,
            "errorCode" to record.errorCode,
        ).withoutNulls(),
    )

    fun recordWorkspaceGrantChanged(
        workspaceId: String,
        scope: DiagnosticGrantScope,
        granted: Boolean,
        requestRef: String? = null,
        errorCode: String = "unknown",
    ): Boolean = recordWorkspaceGrantChanged(
        WorkspaceGrantChangedRecord(workspaceId, scope, granted, requestRef, errorCode),
    )

    fun recordWorkspaceOperationState(record: WorkspaceOperationStateRecord): Boolean = record(
        "workspace_operation_state",
        linkedMapOf<String, Any?>(
            "workspaceRef" to record.workspaceId,
            "operation" to record.operation.wireName,
            "state" to record.state.wireName,
            "count" to record.count.coerceIn(0, MAX_COUNT),
            "requestRef" to record.requestRef,
            "errorCode" to record.errorCode,
        ).withoutNulls(),
    )

    fun recordWorkspaceOperationState(
        workspaceId: String,
        operation: DiagnosticOperation,
        state: DiagnosticOperationState,
        count: Int = 0,
        requestRef: String? = null,
        errorCode: String = "unknown",
    ): Boolean = recordWorkspaceOperationState(
        WorkspaceOperationStateRecord(workspaceId, operation, state, count, requestRef, errorCode),
    )

    fun recordSkillMemoryOperationState(record: SkillMemoryOperationStateRecord): Boolean = record(
        "skill_memory_operation_state",
        linkedMapOf<String, Any?>(
            "skillRef" to record.skillId,
            "operation" to record.operation.wireName,
            "state" to record.state.wireName,
            "count" to record.count.coerceIn(0, MAX_COUNT),
            "requestRef" to record.requestRef,
            "errorCode" to record.errorCode,
        ).withoutNulls(),
    )

    fun recordSkillMemoryOperationState(
        skillId: String,
        operation: DiagnosticOperation,
        state: DiagnosticOperationState,
        count: Int = 0,
        requestRef: String? = null,
        errorCode: String = "unknown",
    ): Boolean = recordSkillMemoryOperationState(
        SkillMemoryOperationStateRecord(skillId, operation, state, count, requestRef, errorCode),
    )

    fun recordDangerousModeChanged(record: DangerousModeChangedRecord): Boolean = record(
        "dangerous_mode_changed",
        linkedMapOf<String, Any?>(
            "enabled" to record.enabled,
            "policy" to record.policy.wireName,
            "requestRef" to record.requestRef,
        ).withoutNulls(),
    )

    fun recordDangerousModeChanged(
        enabled: Boolean,
        policy: DiagnosticDangerousModePolicy,
        requestRef: String? = null,
    ): Boolean = recordDangerousModeChanged(DangerousModeChangedRecord(enabled, policy, requestRef))

    fun recordShellToolExposureChanged(record: ShellToolExposureChangedRecord): Boolean = record(
        "shell_tool_exposure_changed",
        linkedMapOf<String, Any?>(
            "agentRef" to record.agentId,
            "skillRef" to record.skillId,
            "authority" to record.authority.wireName,
            "exposed" to record.exposed.wireName,
            "reasonCode" to record.reasonCode,
            "requestRef" to record.requestRef,
        ).withoutNulls(),
    )

    fun recordShellToolExposureChanged(
        agentId: String,
        exposed: DiagnosticExposureState,
        skillId: String? = null,
        authority: DiagnosticAuthority = DiagnosticAuthority.NONE,
        reasonCode: String = "unknown",
        requestRef: String? = null,
    ): Boolean = recordShellToolExposureChanged(
        ShellToolExposureChangedRecord(agentId, exposed, skillId, authority, reasonCode, requestRef),
    )

    fun recordToolApprovalState(record: ToolApprovalStateRecord): Boolean = record(
        "tool_approval_state",
        linkedMapOf<String, Any?>(
            "agentRef" to record.agentId,
            "skillRef" to record.skillId,
            "callRef" to record.callId,
            "approvalRef" to record.approvalId,
            "state" to record.state.wireName,
            "requestRef" to record.requestRef,
            "reasonCode" to record.reasonCode,
            "capability" to record.capability.wireName,
            "authority" to record.authority.wireName,
            "sessionRef" to record.sessionRef,
        ).withoutNulls(),
    )

    fun recordToolApprovalState(
        callId: String,
        state: DiagnosticApprovalState,
        approvalId: String? = null,
        agentId: String? = null,
        skillId: String? = null,
        requestRef: String? = null,
        reasonCode: String = "unknown",
        capability: DiagnosticToolCapability = DiagnosticToolCapability.UNKNOWN,
        authority: DiagnosticAuthority = DiagnosticAuthority.NONE,
        sessionRef: String? = null,
    ): Boolean = recordToolApprovalState(
        ToolApprovalStateRecord(
            callId, state, approvalId, agentId, skillId, requestRef, reasonCode, capability, authority, sessionRef,
        ),
    )

    fun recordShellExecutionState(record: ShellExecutionStateRecord): Boolean = record(
        "shell_execution_state",
        linkedMapOf<String, Any?>(
            "requestRef" to record.requestRef,
            "callRef" to record.callId,
            "agentRef" to record.agentId,
            "skillRef" to record.skillId,
            "authority" to record.authority.wireName,
            "commandSha256" to record.commandSha256,
            "limitBucket" to record.limitBucket.wireName,
            "terminalState" to record.terminalState.wireName,
            "stdoutBytes" to record.stdoutBytes.coerceIn(0, MAX_COUNT),
            "stderrBytes" to record.stderrBytes.coerceIn(0, MAX_COUNT),
            "durationBucket" to record.durationBucket.wireName,
        ).withoutNulls(),
    )

    fun recordShellExecutionState(
        commandSha256: String,
        terminalState: DiagnosticTerminalState,
        authority: DiagnosticAuthority,
        limitBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
        stdoutBytes: Int = 0,
        stderrBytes: Int = 0,
        durationBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
        requestRef: String? = null,
        callId: String? = null,
        agentId: String? = null,
        skillId: String? = null,
    ): Boolean = recordShellExecutionState(
        ShellExecutionStateRecord(
            commandSha256, terminalState, authority, limitBucket, stdoutBytes, stderrBytes,
            durationBucket, requestRef, callId, agentId, skillId,
        ),
    )

    fun recordBridgeRequestState(record: BridgeRequestStateRecord): Boolean = record(
        "bridge_request_state",
        linkedMapOf<String, Any?>(
            "requestRef" to record.requestRef,
            "authority" to record.authority.wireName,
            "state" to record.state.wireName,
            "errorCode" to record.errorCode,
            "durationBucket" to record.durationBucket.wireName,
            "count" to record.count.coerceIn(0, MAX_COUNT),
        ),
    )

    fun recordBridgeRequestState(
        state: DiagnosticBridgeRequestState,
        authority: DiagnosticAuthority,
        requestRef: String,
        errorCode: String = "unknown",
        durationBucket: DiagnosticLimitBucket = DiagnosticLimitBucket.UNKNOWN,
        count: Int = 0,
    ): Boolean = recordBridgeRequestState(
        BridgeRequestStateRecord(state, authority, requestRef, errorCode, durationBucket, count),
    )

    /** Record a snapshot of drops/failures without trying to record another event on failure. */
    fun recordDiagnosticDropSummary(record: DiagnosticDropSummaryRecord): Boolean = record(
        "diagnostic_drop_summary",
        linkedMapOf<String, Any?>(
            "droppedEvents" to record.droppedEvents.coerceIn(0, MAX_COUNT),
            "droppedBytes" to record.droppedBytes.coerceIn(0, MAX_COUNT),
            "failureCount" to record.failureCount.coerceIn(0, MAX_COUNT),
            "health" to record.health.wireName,
            "reasonCode" to record.reasonCode,
        ),
    )

    fun recordDiagnosticDropSummary(
        droppedEvents: Int = droppedEventCount.coerceIn(0, MAX_COUNT.toLong()).toInt(),
        droppedBytes: Int = droppedByteCount.coerceIn(0, MAX_COUNT.toLong()).toInt(),
        failureCount: Int = writeFailureCount.coerceIn(0, MAX_COUNT.toLong()).toInt(),
        health: DiagnosticHealth = currentHealth(),
        reasonCode: String = "unknown",
    ): Boolean = recordDiagnosticDropSummary(
        DiagnosticDropSummaryRecord(droppedEvents, droppedBytes, failureCount, health, reasonCode),
    )

    fun recordRuntimeToolingUnavailable(record: RuntimeToolingUnavailableRecord): Boolean = record(
        "runtime_tooling_unavailable",
        linkedMapOf<String, Any?>(
            "errorCode" to record.errorCode.wireName,
            "sessionRef" to record.sessionRef,
            "runRef" to record.runRef,
        ).withoutNulls(),
    )

    fun recordRuntimeToolingUnavailable(
        errorCode: RuntimeToolingUnavailableCode,
        sessionRef: String? = null,
        runRef: String? = null,
    ): Boolean = recordRuntimeToolingUnavailable(RuntimeToolingUnavailableRecord(errorCode, sessionRef, runRef))

    /** Crash logging never includes Throwable.message, which may contain user content or secrets. */
    fun recordCrash(thread: Thread, throwable: Throwable): Boolean = synchronized(lock) {
        if (!enabledSafely()) return@synchronized false
        val fields = linkedMapOf<String, Any?>(
            "exceptionType" to DiagnosticSanitizer.exceptionType(throwable.javaClass.name),
            "stack" to DiagnosticSanitizer.stackTrace(throwable),
        )
        val line = renderLine("uncaught_exception", DiagnosticLevel.ERROR, fields, thread.name)
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size
        if (lineBytes + 1 > MAX_EVENT_BYTES) {
            noteDrop(lineBytes + 1)
            return@synchronized false
        }
        if (!runCatching { appendCurrent(line) }.getOrDefault(false)) {
            noteWriteFailure(lineBytes + 1)
            return@synchronized false
        }
        // This file is intentionally replaced, so an export always has the most recent crash.
        val crashWritten = runCatching {
            writeBounded(lastCrashFile, (line + "\n").toByteArray(StandardCharsets.UTF_8), MAX_LAST_CRASH_BYTES)
            true
        }.getOrElse {
            noteWriteFailure(lineBytes + 1)
            false
        }
        crashWritten
    }

    fun status(): DiagnosticStatus = synchronized(lock) {
        normalizeFiles()
        val current = runCatching { boundedLength(currentFile, MAX_CURRENT_BYTES) }.getOrDefault(0L)
        val previous = runCatching { boundedLength(previousFile, MAX_PREVIOUS_BYTES) }.getOrDefault(0L)
        val crash = runCatching { boundedLength(lastCrashFile, MAX_LAST_CRASH_BYTES) }.getOrDefault(0L)
        DiagnosticStatus(
            enabled = enabledSafely(),
            currentBytes = current,
            previousBytes = previous,
            lastCrashBytes = crash,
            totalBytes = current + previous + crash,
            totalLimitBytes = MAX_CURRENT_BYTES.toLong() + MAX_PREVIOUS_BYTES + MAX_LAST_CRASH_BYTES,
            writeFailureCount = writeFailureCount,
            droppedEventCount = droppedEventCount,
            droppedByteCount = droppedByteCount,
            health = currentHealth(),
        )
    }

    /** Create a size-capped ZIP in memory; the caller owns the destination stream. */
    fun exportBytes(): ByteArray = synchronized(lock) {
        normalizeFiles()
        val files = listOf(
            CURRENT_FILE_NAME to readBounded(currentFile, MAX_CURRENT_BYTES),
            PREVIOUS_FILE_NAME to readBounded(previousFile, MAX_PREVIOUS_BYTES),
            LAST_CRASH_FILE_NAME to readBounded(lastCrashFile, MAX_LAST_CRASH_BYTES),
        )
        val manifest = renderManifest(files)
        val output = ByteArrayOutputStream(MAX_EXPORT_BYTES)
        val bounded = LimitOutputStream(output, MAX_EXPORT_BYTES)
        ZipOutputStream(bounded).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    fun exportTo(output: OutputStream) {
        output.write(exportBytes())
    }

    /** Clear only the three names owned by this logger. */
    fun clear() = synchronized(lock) {
        listOf(currentFile, previousFile, lastCrashFile).forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("无法清除诊断文件。")
        }
    }

    /** Test/read-only helper; only known file names can be inspected. */
    fun readFile(name: String): ByteArray = synchronized(lock) {
        when (name) {
            CURRENT_FILE_NAME -> readBounded(currentFile, MAX_CURRENT_BYTES)
            PREVIOUS_FILE_NAME -> readBounded(previousFile, MAX_PREVIOUS_BYTES)
            LAST_CRASH_FILE_NAME -> readBounded(lastCrashFile, MAX_LAST_CRASH_BYTES)
            else -> throw IllegalArgumentException("Unknown diagnostics file")
        }
    }

    private fun normalizeFields(event: String, fields: Map<String, Any?>): Map<String, Any?>? {
        val allowed = eventFields[event] ?: return null
        if (fields.keys.any { it !in allowed }) return null
        return when (event) {
            "process_started" -> if (fields.isEmpty()) emptyMap() else null
            "diagnostics_toggle" -> {
                val enabled = fields["enabled"] as? Boolean ?: return null
                linkedMapOf("enabled" to enabled)
            }
            "capability_toggle" -> {
                val capability = (fields["capability"] as? String)?.trim()?.lowercase() ?: return null
                val enabled = fields["enabled"] as? Boolean ?: return null
                if (capability !in setOf("image", "tools")) return null
                linkedMapOf("capability" to capability, "enabled" to enabled)
            }
            "provider_model_save_start", "provider_model_save_success", "provider_model_save_failed" -> {
                val capabilities = (fields["capabilities"] as? String) ?: return null
                val role = (fields["role"] as? String) ?: return null
                if (event.endsWith("failed") && fields["exceptionType"] !is String) return null
                linkedMapOf<String, Any?>(
                    "capabilities" to canonicalCapabilities(capabilities),
                    "role" to canonicalRole(role),
                ).apply {
                    if (event.endsWith("failed")) this["exceptionType"] = DiagnosticSanitizer.exceptionType(fields["exceptionType"] as String)
                }
            }
            "knowledge_import_start" -> {
                val kind = fields["kind"] as? String ?: return null
                val stage = fields["stage"] as? String ?: return null
                val total = fields["total"] as? Int ?: return null
                linkedMapOf(
                    "kind" to canonicalKind(kind),
                    "stage" to canonicalStage(stage),
                    "total" to total.coerceIn(0, MAX_COUNT),
                )
            }
            "knowledge_import_progress" -> {
                val kind = fields["kind"] as? String ?: return null
                val stage = fields["stage"] as? String ?: return null
                val completed = fields["completed"] as? Int ?: return null
                val total = fields["total"] as? Int ?: return null
                linkedMapOf(
                    "kind" to canonicalKind(kind),
                    "stage" to canonicalStage(stage),
                    "completed" to completed.coerceIn(0, MAX_COUNT),
                    "total" to total.coerceIn(0, MAX_COUNT),
                )
            }
            "knowledge_import_enqueued", "knowledge_import_staged", "batch_worker_start", "batch_worker_complete", "skill_inspect_success", "skill_install_success" -> {
                val kind = fields["kind"] as? String ?: return null
                val stage = fields["stage"] as? String ?: return null
                val count = fields["count"] as? Int ?: return null
                linkedMapOf(
                    "kind" to canonicalKind(kind),
                    "stage" to canonicalStage(stage),
                    "count" to count.coerceIn(0, MAX_COUNT),
                )
            }
            "knowledge_import_failed", "batch_worker_failed", "skill_inspect_failed", "skill_install_failed" -> {
                val kind = fields["kind"] as? String ?: return null
                val stage = fields["stage"] as? String ?: return null
                val count = fields["count"] as? Int ?: return null
                val type = fields["exceptionType"] as? String ?: return null
                val errorCode = fields["errorCode"] as? String ?: return null
                linkedMapOf(
                    "kind" to canonicalKind(kind),
                    "stage" to canonicalStage(stage),
                    "count" to count.coerceIn(0, MAX_COUNT),
                    "exceptionType" to DiagnosticSanitizer.exceptionType(type),
                    "errorCode" to canonicalErrorCode(errorCode),
                )
            }
            "authority_selection_changed" -> {
                val selected = canonicalAuthority(fields["selectedAuthority"] as? String ?: return null)
                val previous = (fields["previousAuthority"] as? String)?.let(::canonicalAuthority)
                val requestRef = canonicalOptionalReference(fields["requestRef"])
                linkedMapOf<String, Any?>(
                    "selectedAuthority" to selected,
                    "previousAuthority" to previous,
                    "requestRef" to requestRef,
                ).withoutNulls()
            }
            "authority_state_changed" -> {
                val authority = canonicalAuthority(fields["authority"] as? String ?: return null)
                val state = canonicalAuthorityState(fields["state"] as? String ?: return null)
                val previous = (fields["previousState"] as? String)?.let(::canonicalAuthorityState)
                val errorCode = canonicalErrorCode(fields["errorCode"] as? String ?: return null)
                linkedMapOf<String, Any?>(
                    "authority" to authority,
                    "state" to state,
                    "previousState" to previous,
                    "errorCode" to errorCode,
                ).withoutNulls()
            }
            "shizuku_lifecycle", "wired_adb_lifecycle" -> {
                val state = canonicalLifecycleState(fields["state"] as? String ?: return null)
                val errorCode = canonicalErrorCode(fields["errorCode"] as? String ?: return null)
                val requestRef = canonicalOptionalReference(fields["requestRef"])
                linkedMapOf<String, Any?>(
                    "state" to state,
                    "errorCode" to errorCode,
                    "requestRef" to requestRef,
                ).withoutNulls()
            }
            "workspace_grant_changed" -> {
                val workspace = canonicalReference(fields["workspaceRef"] as? String ?: return null)
                val scope = canonicalGrantScope(fields["scope"] as? String ?: return null)
                val granted = fields["granted"] as? Boolean ?: return null
                val requestRef = canonicalOptionalReference(fields["requestRef"])
                val errorCode = canonicalErrorCode(fields["errorCode"] as? String ?: return null)
                linkedMapOf<String, Any?>(
                    "workspaceRef" to workspace,
                    "scope" to scope,
                    "granted" to granted,
                    "requestRef" to requestRef,
                    "errorCode" to errorCode,
                ).withoutNulls()
            }
            "workspace_operation_state" -> {
                val workspace = canonicalReference(fields["workspaceRef"] as? String ?: return null)
                val operation = canonicalOperation(fields["operation"] as? String ?: return null)
                val state = canonicalOperationState(fields["state"] as? String ?: return null)
                val count = fields["count"] as? Int ?: return null
                val requestRef = canonicalOptionalReference(fields["requestRef"])
                val errorCode = canonicalErrorCode(fields["errorCode"] as? String ?: return null)
                linkedMapOf<String, Any?>(
                    "workspaceRef" to workspace,
                    "operation" to operation,
                    "state" to state,
                    "count" to count.coerceIn(0, MAX_COUNT),
                    "requestRef" to requestRef,
                    "errorCode" to errorCode,
                ).withoutNulls()
            }
            "skill_memory_operation_state" -> {
                val skill = canonicalReference(fields["skillRef"] as? String ?: return null)
                val operation = canonicalOperation(fields["operation"] as? String ?: return null)
                val state = canonicalOperationState(fields["state"] as? String ?: return null)
                val count = fields["count"] as? Int ?: return null
                val requestRef = canonicalOptionalReference(fields["requestRef"])
                val errorCode = canonicalErrorCode(fields["errorCode"] as? String ?: return null)
                linkedMapOf<String, Any?>(
                    "skillRef" to skill,
                    "operation" to operation,
                    "state" to state,
                    "count" to count.coerceIn(0, MAX_COUNT),
                    "requestRef" to requestRef,
                    "errorCode" to errorCode,
                ).withoutNulls()
            }
            "dangerous_mode_changed" -> {
                val enabled = fields["enabled"] as? Boolean ?: return null
                val policy = canonicalDangerousModePolicy(fields["policy"] as? String ?: return null)
                val requestRef = canonicalOptionalReference(fields["requestRef"])
                linkedMapOf<String, Any?>(
                    "enabled" to enabled,
                    "policy" to policy,
                    "requestRef" to requestRef,
                ).withoutNulls()
            }
            "shell_tool_exposure_changed" -> {
                val agent = canonicalReference(fields["agentRef"] as? String ?: return null)
                val skill = canonicalOptionalReference(fields["skillRef"])
                val authority = canonicalAuthority(fields["authority"] as? String ?: return null)
                val exposed = canonicalExposureState(fields["exposed"] as? String ?: return null)
                val reason = canonicalErrorCode(fields["reasonCode"] as? String ?: return null)
                val requestRef = canonicalOptionalReference(fields["requestRef"])
                linkedMapOf<String, Any?>(
                    "agentRef" to agent,
                    "skillRef" to skill,
                    "authority" to authority,
                    "exposed" to exposed,
                    "reasonCode" to reason,
                    "requestRef" to requestRef,
                ).withoutNulls()
            }
            "tool_approval_state" -> {
                val agent = canonicalOptionalReference(fields["agentRef"])
                val skill = canonicalOptionalReference(fields["skillRef"])
                val call = canonicalReference(fields["callRef"] as? String ?: return null)
                val approval = canonicalOptionalReference(fields["approvalRef"])
                val state = canonicalApprovalState(fields["state"] as? String ?: return null)
                val requestRef = canonicalOptionalReference(fields["requestRef"])
                val reason = canonicalErrorCode(fields["reasonCode"] as? String ?: return null)
                val capability = canonicalToolCapability(
                    fields["capability"] as? String ?: DiagnosticToolCapability.UNKNOWN.wireName,
                )
                val authority = canonicalAuthority(
                    fields["authority"] as? String ?: DiagnosticAuthority.NONE.wireName,
                )
                val sessionRef = canonicalOptionalReference(fields["sessionRef"])
                linkedMapOf<String, Any?>(
                    "agentRef" to agent,
                    "skillRef" to skill,
                    "callRef" to call,
                    "approvalRef" to approval,
                    "state" to state,
                    "requestRef" to requestRef,
                    "reasonCode" to reason,
                    "capability" to capability,
                    "authority" to authority,
                    "sessionRef" to sessionRef,
                ).withoutNulls()
            }
            "shell_execution_state" -> {
                val commandHash = canonicalCommandSha256(fields["commandSha256"] as? String ?: return null)
                val terminal = canonicalTerminalState(fields["terminalState"] as? String ?: return null)
                val authority = canonicalAuthority(fields["authority"] as? String ?: return null)
                val limit = canonicalLimitBucket(fields["limitBucket"] as? String ?: return null)
                val stdout = fields["stdoutBytes"] as? Int ?: return null
                val stderr = fields["stderrBytes"] as? Int ?: return null
                val duration = canonicalLimitBucket(fields["durationBucket"] as? String ?: return null)
                linkedMapOf<String, Any?>(
                    "requestRef" to canonicalOptionalReference(fields["requestRef"]),
                    "callRef" to canonicalOptionalReference(fields["callRef"]),
                    "agentRef" to canonicalOptionalReference(fields["agentRef"]),
                    "skillRef" to canonicalOptionalReference(fields["skillRef"]),
                    "authority" to authority,
                    "commandSha256" to commandHash,
                    "limitBucket" to limit,
                    "terminalState" to terminal,
                    "stdoutBytes" to stdout.coerceIn(0, MAX_COUNT),
                    "stderrBytes" to stderr.coerceIn(0, MAX_COUNT),
                    "durationBucket" to duration,
                ).withoutNulls()
            }
            "bridge_request_state" -> {
                val requestRef = canonicalReference(fields["requestRef"] as? String ?: return null)
                val authority = canonicalAuthority(fields["authority"] as? String ?: return null)
                val state = canonicalBridgeRequestState(fields["state"] as? String ?: return null)
                val errorCode = canonicalErrorCode(fields["errorCode"] as? String ?: return null)
                val duration = canonicalLimitBucket(fields["durationBucket"] as? String ?: return null)
                val count = fields["count"] as? Int ?: return null
                linkedMapOf(
                    "requestRef" to requestRef,
                    "authority" to authority,
                    "state" to state,
                    "errorCode" to errorCode,
                    "durationBucket" to duration,
                    "count" to count.coerceIn(0, MAX_COUNT),
                )
            }
            "diagnostic_drop_summary" -> {
                val droppedEvents = fields["droppedEvents"] as? Int ?: return null
                val droppedBytes = fields["droppedBytes"] as? Int ?: return null
                val failureCount = fields["failureCount"] as? Int ?: return null
                val health = canonicalHealth(fields["health"] as? String ?: return null)
                val reason = canonicalErrorCode(fields["reasonCode"] as? String ?: return null)
                linkedMapOf(
                    "droppedEvents" to droppedEvents.coerceIn(0, MAX_COUNT),
                    "droppedBytes" to droppedBytes.coerceIn(0, MAX_COUNT),
                    "failureCount" to failureCount.coerceIn(0, MAX_COUNT),
                    "health" to health,
                    "reasonCode" to reason,
                )
            }
            "runtime_tooling_unavailable" -> {
                val errorCode = when (fields["errorCode"] as? String) {
                    RuntimeToolingUnavailableCode.TOOL_EXECUTION_CONTEXT_UNAVAILABLE.wireName ->
                        RuntimeToolingUnavailableCode.TOOL_EXECUTION_CONTEXT_UNAVAILABLE.wireName
                    RuntimeToolingUnavailableCode.TOOL_EXECUTOR_FACTORY_UNAVAILABLE.wireName ->
                        RuntimeToolingUnavailableCode.TOOL_EXECUTOR_FACTORY_UNAVAILABLE.wireName
                    else -> return null
                }
                linkedMapOf<String, Any?>(
                    "errorCode" to errorCode,
                    "sessionRef" to canonicalOptionalReference(fields["sessionRef"]),
                    "runRef" to canonicalOptionalReference(fields["runRef"]),
                ).withoutNulls()
            }
            "uncaught_exception" -> {
                val type = fields["exceptionType"] as? String ?: return null
                val stack = fields["stack"] as? String ?: return null
                linkedMapOf(
                    "exceptionType" to DiagnosticSanitizer.exceptionType(type),
                    "stack" to DiagnosticSanitizer.text(stack, 1_800),
                )
            }
            else -> null
        }
    }

    private fun levelFor(event: String): DiagnosticLevel =
        if (event.endsWith("_failed") || event == "uncaught_exception" || event == "runtime_tooling_unavailable") {
            DiagnosticLevel.ERROR
        } else {
            DiagnosticLevel.INFO
        }

    private fun renderLine(
        event: String,
        level: DiagnosticLevel,
        fields: Map<String, Any?>,
        eventThread: String,
    ): String {
        val entries = linkedMapOf<String, Any?>(
            "schemaVersion" to SCHEMA_VERSION,
            "sessionId" to DiagnosticSanitizer.text(sessionId, 96),
            "pid" to processId().coerceAtLeast(0),
            // Thread names are process-global mutable strings. Persist only a fixed category so
            // a library cannot smuggle user text into an otherwise closed diagnostics schema.
            "thread" to canonicalThread(eventThread),
            "utc" to DiagnosticSanitizer.text(nowUtc(), 64),
            "level" to level.wireName,
            "event" to event,
            "build" to linkedMapOf<String, Any?>(
                "revision" to DiagnosticSanitizer.text(buildInfo.revision, 160),
                "dirty" to buildInfo.dirty,
                "schemaVersion" to buildInfo.schemaVersion,
                "buildTimeUtc" to DiagnosticSanitizer.text(buildInfo.buildTimeUtc, 64),
            ),
            "fields" to fields,
        )
        return jsonObject(entries)
    }

    private fun renderManifest(files: List<Pair<String, ByteArray>>): String {
        val currentStatus = status()
        val entries = linkedMapOf<String, Any?>(
            "format" to "mobile-agent-diagnostics",
            "schemaVersion" to SCHEMA_VERSION,
            "generatedAtUtc" to DiagnosticSanitizer.text(nowUtc(), 64),
            "sessionId" to DiagnosticSanitizer.text(sessionId, 96),
            "enabled" to currentStatus.enabled,
            "build" to linkedMapOf<String, Any?>(
                "revision" to DiagnosticSanitizer.text(buildInfo.revision, 160),
                "dirty" to buildInfo.dirty,
                "schemaVersion" to buildInfo.schemaVersion,
                "buildTimeUtc" to DiagnosticSanitizer.text(buildInfo.buildTimeUtc, 64),
                "fingerprint" to DiagnosticSanitizer.text(buildInfo.fingerprint, 256),
            ),
            "health" to currentStatus.health.wireName,
            "failureCount" to currentStatus.writeFailureCount,
            "droppedEvents" to currentStatus.droppedEventCount,
            "droppedBytes" to currentStatus.droppedByteCount,
            "limits" to linkedMapOf<String, Any?>(
                "currentBytes" to MAX_CURRENT_BYTES,
                "previousBytes" to MAX_PREVIOUS_BYTES,
                "lastCrashBytes" to MAX_LAST_CRASH_BYTES,
                "exportBytes" to MAX_EXPORT_BYTES,
            ),
            "files" to files.map { (name, bytes) -> linkedMapOf<String, Any?>("name" to name, "bytes" to bytes.size) },
        )
        return jsonObject(entries)
    }

    private fun appendCurrent(line: String): Boolean {
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_EVENT_BYTES) return false
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) return false
        if (!normalizeFiles()) return false
        if (currentFile.length() + bytes.size > MAX_CURRENT_BYTES) rotateCurrent()
        if (currentFile.length() + bytes.size > MAX_CURRENT_BYTES) return false
        return runCatching {
            FileOutputStream(currentFile, true).use { it.write(bytes) }
            true
        }.getOrDefault(false)
    }

    private fun rotateCurrent() {
        if (!currentFile.exists() || currentFile.length() == 0L) return
        if (previousFile.exists()) previousFile.delete()
        if (!currentFile.renameTo(previousFile)) {
            val bytes = readBounded(currentFile, MAX_CURRENT_BYTES)
            writeBounded(previousFile, bytes, MAX_PREVIOUS_BYTES)
            writeBounded(currentFile, ByteArray(0), MAX_CURRENT_BYTES)
        }
        trimFile(previousFile, MAX_PREVIOUS_BYTES)
    }

    /** Best effort: damaged or unwritable diagnostics must never destabilize the host app. */
    private fun normalizeFiles(): Boolean = runCatching {
        if (rootDirectory.exists()) {
            if (currentFile.length() > MAX_CURRENT_BYTES) rotateCurrent()
            trimFile(previousFile, MAX_PREVIOUS_BYTES)
            trimFile(lastCrashFile, MAX_LAST_CRASH_BYTES)
        }
        true
    }.getOrDefault(false)

    private fun trimFile(file: File, limit: Int) {
        if (!file.exists() || file.length() <= limit) return
        writeBounded(file, readBounded(file, limit), limit)
    }

    private fun writeBounded(file: File, bytes: ByteArray, limit: Int) {
        val parent = file.parentFile ?: rootDirectory
        if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建诊断目录。")
        FileOutputStream(file, false).use { output -> output.write(bytes, 0, minOf(bytes.size, limit)) }
    }

    private fun boundedLength(file: File, limit: Int): Long = file.takeIf { it.isFile }?.length()?.coerceAtMost(limit.toLong()) ?: 0L

    private fun readBounded(file: File, limit: Int): ByteArray {
        if (!file.isFile) return ByteArray(0)
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val count = minOf(length, limit.toLong()).toInt()
            input.seek(length - count)
            val bytes = ByteArray(count).also { input.readFully(it) }
            if (length <= limit || bytes.isEmpty()) return bytes
            // Never export or retain a suffix that starts halfway through an NDJSON record.
            val firstLineEnd = bytes.indexOf('\n'.code.toByte())
            if (firstLineEnd < 0) return ByteArray(0)
            var lastLineEnd = bytes.lastIndex
            while (lastLineEnd >= 0 && bytes[lastLineEnd] != '\n'.code.toByte()) lastLineEnd--
            if (lastLineEnd < firstLineEnd + 1) return ByteArray(0)
            return bytes.copyOfRange(firstLineEnd + 1, lastLineEnd + 1)
        }
    }

    private fun canonicalKind(value: String): String = when (value.trim().lowercase()) {
        "files", "file" -> "files"
        "folder", "directory" -> "folder"
        "zip", "archive" -> "zip"
        "batch" -> "batch"
        "skill" -> "skill"
        else -> "unknown"
    }

    private fun canonicalReference(value: String): String = referenceHasher.hash(value.ifBlank { "unknown" })

    private fun canonicalOptionalReference(value: Any?): String? {
        if (value == null) return null
        return (value as? String)?.let(::canonicalReference)
    }

    private fun canonicalAuthority(value: String): String = when (value.trim().lowercase()) {
        "none" -> DiagnosticAuthority.NONE.wireName
        "shizuku" -> DiagnosticAuthority.SHIZUKU.wireName
        "wired_adb", "wired-adb", "adb" -> DiagnosticAuthority.WIRED_ADB.wireName
        else -> DiagnosticAuthority.NONE.wireName
    }

    private fun canonicalAuthorityState(value: String): String = when (value.trim().lowercase()) {
        "unavailable" -> DiagnosticAuthorityState.UNAVAILABLE.wireName
        "available" -> DiagnosticAuthorityState.AVAILABLE.wireName
        "connecting" -> DiagnosticAuthorityState.CONNECTING.wireName
        "connected" -> DiagnosticAuthorityState.CONNECTED.wireName
        "disconnected" -> DiagnosticAuthorityState.DISCONNECTED.wireName
        "reauth_required", "reauth-required" -> DiagnosticAuthorityState.REAUTH_REQUIRED.wireName
        "failed" -> DiagnosticAuthorityState.FAILED.wireName
        else -> DiagnosticAuthorityState.UNKNOWN.wireName
    }

    private fun canonicalLifecycleState(value: String): String = when (value.trim().lowercase()) {
        "started" -> DiagnosticLifecycleState.STARTED.wireName
        "ready" -> DiagnosticLifecycleState.READY.wireName
        "stopped" -> DiagnosticLifecycleState.STOPPED.wireName
        "disconnected" -> DiagnosticLifecycleState.DISCONNECTED.wireName
        "failed" -> DiagnosticLifecycleState.FAILED.wireName
        else -> DiagnosticLifecycleState.UNKNOWN.wireName
    }

    private fun canonicalGrantScope(value: String): String = when (value.trim().lowercase()) {
        "none" -> DiagnosticGrantScope.NONE.wireName
        "read" -> DiagnosticGrantScope.READ.wireName
        "write" -> DiagnosticGrantScope.WRITE.wireName
        "read_write", "read-write", "readwrite" -> DiagnosticGrantScope.READ_WRITE.wireName
        else -> DiagnosticGrantScope.UNKNOWN.wireName
    }

    private fun canonicalOperation(value: String): String = when (value.trim().lowercase()) {
        "enumerate" -> DiagnosticOperation.ENUMERATE.wireName
        "read" -> DiagnosticOperation.READ.wireName
        "write" -> DiagnosticOperation.WRITE.wireName
        "delete" -> DiagnosticOperation.DELETE.wireName
        "search" -> DiagnosticOperation.SEARCH.wireName
        "append" -> DiagnosticOperation.APPEND.wireName
        "replace" -> DiagnosticOperation.REPLACE.wireName
        else -> DiagnosticOperation.UNKNOWN.wireName
    }

    private fun canonicalToolCapability(value: String): String = when (value.trim().lowercase()) {
        "workspace_read", "workspace-read" -> DiagnosticToolCapability.WORKSPACE_READ.wireName
        "workspace_write", "workspace-write" -> DiagnosticToolCapability.WORKSPACE_WRITE.wireName
        "memory_read", "memory-read" -> DiagnosticToolCapability.MEMORY_READ.wireName
        "memory_write", "memory-write" -> DiagnosticToolCapability.MEMORY_WRITE.wireName
        "shell_execute", "shell-execute", "shell" -> DiagnosticToolCapability.SHELL_EXECUTE.wireName
        "search" -> DiagnosticToolCapability.SEARCH.wireName
        else -> DiagnosticToolCapability.UNKNOWN.wireName
    }

    private fun canonicalOperationState(value: String): String = when (value.trim().lowercase()) {
        "started" -> DiagnosticOperationState.STARTED.wireName
        "succeeded", "success", "completed", "complete" -> DiagnosticOperationState.SUCCEEDED.wireName
        "failed" -> DiagnosticOperationState.FAILED.wireName
        "denied" -> DiagnosticOperationState.DENIED.wireName
        "cancelled", "canceled" -> DiagnosticOperationState.CANCELLED.wireName
        else -> DiagnosticOperationState.UNKNOWN.wireName
    }

    private fun canonicalDangerousModePolicy(value: String): String = when (value.trim().lowercase()) {
        "disabled", "off" -> DiagnosticDangerousModePolicy.DISABLED.wireName
        "confirm_high_risk", "confirm-high-risk" -> DiagnosticDangerousModePolicy.CONFIRM_HIGH_RISK.wireName
        "autonomous", "on" -> DiagnosticDangerousModePolicy.AUTONOMOUS.wireName
        else -> DiagnosticDangerousModePolicy.UNKNOWN.wireName
    }

    private fun canonicalExposureState(value: String): String = when (value.trim().lowercase()) {
        "exposed", "enabled" -> DiagnosticExposureState.EXPOSED.wireName
        "hidden", "disabled" -> DiagnosticExposureState.HIDDEN.wireName
        "blocked" -> DiagnosticExposureState.BLOCKED.wireName
        else -> DiagnosticExposureState.UNKNOWN.wireName
    }

    private fun canonicalApprovalState(value: String): String = when (value.trim().lowercase()) {
        "requested", "pending" -> DiagnosticApprovalState.REQUESTED.wireName
        "approved" -> DiagnosticApprovalState.APPROVED.wireName
        "denied" -> DiagnosticApprovalState.DENIED.wireName
        "expired" -> DiagnosticApprovalState.EXPIRED.wireName
        "invalidated", "invalid" -> DiagnosticApprovalState.INVALIDATED.wireName
        else -> DiagnosticApprovalState.UNKNOWN.wireName
    }

    private fun canonicalTerminalState(value: String): String = when (value.trim().lowercase()) {
        "running", "started" -> DiagnosticTerminalState.RUNNING.wireName
        "succeeded", "success", "completed", "complete" -> DiagnosticTerminalState.SUCCEEDED.wireName
        "failed" -> DiagnosticTerminalState.FAILED.wireName
        "timed_out", "timeout", "timed-out" -> DiagnosticTerminalState.TIMED_OUT.wireName
        "cancelled", "canceled" -> DiagnosticTerminalState.CANCELLED.wireName
        "denied" -> DiagnosticTerminalState.DENIED.wireName
        else -> DiagnosticTerminalState.UNKNOWN.wireName
    }

    private fun canonicalBridgeRequestState(value: String): String = when (value.trim().lowercase()) {
        "received" -> DiagnosticBridgeRequestState.RECEIVED.wireName
        "authenticated", "auth" -> DiagnosticBridgeRequestState.AUTHENTICATED.wireName
        "rejected", "denied" -> DiagnosticBridgeRequestState.REJECTED.wireName
        "started", "running" -> DiagnosticBridgeRequestState.STARTED.wireName
        "completed", "complete", "succeeded", "success" -> DiagnosticBridgeRequestState.COMPLETED.wireName
        "failed" -> DiagnosticBridgeRequestState.FAILED.wireName
        "disconnected" -> DiagnosticBridgeRequestState.DISCONNECTED.wireName
        else -> DiagnosticBridgeRequestState.UNKNOWN.wireName
    }

    private fun canonicalLimitBucket(value: String): String = when (value.trim().lowercase()) {
        "none" -> DiagnosticLimitBucket.NONE.wireName
        "tiny" -> DiagnosticLimitBucket.TINY.wireName
        "small" -> DiagnosticLimitBucket.SMALL.wireName
        "medium" -> DiagnosticLimitBucket.MEDIUM.wireName
        "large" -> DiagnosticLimitBucket.LARGE.wireName
        else -> DiagnosticLimitBucket.UNKNOWN.wireName
    }

    private fun canonicalHealth(value: String): String = when (value.trim().lowercase()) {
        "healthy" -> DiagnosticHealth.HEALTHY.wireName
        "degraded" -> DiagnosticHealth.DEGRADED.wireName
        "disabled" -> DiagnosticHealth.DISABLED.wireName
        else -> DiagnosticHealth.DEGRADED.wireName
    }

    private fun enabledSafely(): Boolean = runCatching { preferences.isEnabled() }.getOrDefault(false)

    private fun canonicalCommandSha256(value: String): String {
        val candidate = value.trim().lowercase()
        return if (candidate.length == 64 && candidate.all { it in '0'..'9' || it in 'a'..'f' }) {
            candidate
        } else {
            // A caller that accidentally supplies the command itself is fail-closed and emits no
            // command bytes; the hash must be computed by the execution boundary before calling us.
            "unknown"
        }
    }

    private fun currentHealth(): DiagnosticHealth = when {
        !enabledSafely() -> DiagnosticHealth.DISABLED
        writeFailureCount > 0 || droppedEventCount > 0 -> DiagnosticHealth.DEGRADED
        else -> DiagnosticHealth.HEALTHY
    }

    private fun noteWriteFailure(bytes: Int) {
        writeFailureCount = (writeFailureCount + 1).coerceAtMost(Long.MAX_VALUE)
        if (bytes > 0) droppedByteCount = (droppedByteCount + bytes).coerceAtMost(Long.MAX_VALUE)
    }

    private fun noteDrop(bytes: Int) {
        droppedEventCount = (droppedEventCount + 1).coerceAtMost(Long.MAX_VALUE)
        if (bytes > 0) droppedByteCount = (droppedByteCount + bytes).coerceAtMost(Long.MAX_VALUE)
    }

    private fun canonicalStage(value: String): String = when (value.trim().lowercase()) {
        "start", "staging", "copying", "copied", "queued", "processing",
        "staged", "completed", "complete", "failed", "inspect", "install", "success" -> value.trim().lowercase()
        else -> "unknown"
    }

    private fun canonicalErrorCode(value: String): String = when (value.trim().lowercase()) {
        "cancelled", "permission", "resource_limit", "io", "validation", "rejected", "unknown" -> value.trim().lowercase()
        else -> "unknown"
    }

    /** Convert only to a small controlled vocabulary; never persist an exception message. */
    private fun controlledErrorCode(failure: Throwable): String {
        val typeName = failure.javaClass.name
        val message = failure.message.orEmpty()
        return when {
            typeName.endsWith("CancellationException") -> "cancelled"
            message.contains("RESOURCE_LIMIT", ignoreCase = true) -> "resource_limit"
            failure is SecurityException -> "permission"
            failure is IOException -> "io"
            failure is IllegalArgumentException -> "validation"
            else -> "unknown"
        }
    }

    private fun canonicalCapabilities(value: String): String = canonicalCapabilities(value.split(','))

    private fun canonicalCapabilities(value: Set<String>): String = canonicalCapabilities(value.asIterable())

    private fun canonicalCapabilities(value: Iterable<String>): String {
        val known = listOf("stream", "image", "tools")
        val selected = known.filter { wanted -> value.any { it.trim().equals(wanted, true) } }
        return selected.joinToString(",").ifBlank { "none" }
    }

    private fun canonicalRole(value: String): String = when (value.trim().lowercase()) {
        "chat" -> "chat"
        "vision" -> "vision"
        "embedding" -> "embedding"
        "reranker" -> "reranker"
        else -> "unknown"
    }

    private fun canonicalThread(value: String): String = when {
        value.equals("main", ignoreCase = true) -> "main"
        value.contains("worker", ignoreCase = true) ||
            value.contains("dispatcher", ignoreCase = true) ||
            value.startsWith("WM.", ignoreCase = true) -> "worker"
        else -> "other"
    }

    private fun jsonObject(values: Map<String, Any?>): String = values.entries.joinToString(
        prefix = "{",
        postfix = "}",
        separator = ",",
    ) { (key, value) -> "${jsonString(key)}:${jsonValue(value)}" }

    private fun jsonValue(value: Any?): String = when (value) {
        is String -> jsonString(value)
        is Boolean, is Int, is Long, is Short -> value.toString()
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            jsonObject(value as Map<String, Any?>)
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        else -> throw IllegalArgumentException("Unsupported diagnostics value")
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001F' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }

    private class LimitOutputStream(
        private val delegate: OutputStream,
        private val limit: Int,
    ) : OutputStream() {
        private var count = 0

        override fun write(value: Int) {
            check(count < limit) { "诊断导出超过大小上限。" }
            delegate.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > limit - count) throw IOException("诊断导出超过大小上限。")
            delegate.write(bytes, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()
    }
}

enum class ProviderModelSavePhase(val eventName: String) {
    START("provider_model_save_start"),
    SUCCESS("provider_model_save_success"),
    FAILURE("provider_model_save_failed"),
}

/** Keeps import progress useful without turning one large folder into one event per file. */
class DiagnosticProgressGate(private val interval: Int = 10) {
    init { require(interval > 0) }

    private var lastStage: String? = null
    private var lastCompleted = -1

    fun shouldRecord(stage: String, completed: Int, total: Int): Boolean {
        val stageChanged = stage != lastStage
        val milestone = completed > 0 && completed % interval == 0 && completed != lastCompleted
        val finished = total > 0 && completed >= total
        val record = (stageChanged || milestone || finished) && (stageChanged || completed != lastCompleted)
        if (record) {
            lastStage = stage
            lastCompleted = completed
        }
        return record
    }
}

/** Delegates to Android's original handler after the best-effort append. It never swallows a crash. */
class DiagnosticUncaughtExceptionHandler(
    private val recorder: RollingDiagnosticLogStore,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { recorder.recordCrash(thread, throwable) }
        delegate?.uncaughtException(thread, throwable)
    }
}

private fun Map<String, Any?>.withoutNulls(): Map<String, Any?> = filterValues { it != null }
