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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
) {
    val sizeBytes: Long get() = totalBytes
}

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
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val CURRENT_FILE_NAME = "current.ndjson"
        const val PREVIOUS_FILE_NAME = "previous.ndjson"
        const val LAST_CRASH_FILE_NAME = "last-crash.ndjson"
        const val MAX_CURRENT_BYTES = 64 * 1024
        const val MAX_PREVIOUS_BYTES = 64 * 1024
        const val MAX_LAST_CRASH_BYTES = 32 * 1024
        const val MAX_EVENT_BYTES = 4 * 1024
        const val MAX_EXPORT_BYTES = 192 * 1024
        const val MAX_COUNT = 1_000_000

        private fun currentUtc(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private val lock = Any()

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
    )

    private val currentFile get() = File(rootDirectory, CURRENT_FILE_NAME)
    private val previousFile get() = File(rootDirectory, PREVIOUS_FILE_NAME)
    private val lastCrashFile get() = File(rootDirectory, LAST_CRASH_FILE_NAME)

    val isEnabled: Boolean
        get() = preferences.isEnabled()

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (enabled) {
                preferences.setEnabled(true)
                appendCurrent(renderLine("diagnostics_toggle", DiagnosticLevel.INFO, mapOf("enabled" to true), threadName()))
            } else {
                if (preferences.isEnabled()) {
                    appendCurrent(renderLine("diagnostics_toggle", DiagnosticLevel.INFO, mapOf("enabled" to false), threadName()))
                }
                preferences.setEnabled(false)
            }
        }
    }

    /** Record one of the fixed events. Unknown event names or field keys are rejected. */
    fun record(event: String, fields: Map<String, Any?> = emptyMap()): Boolean = synchronized(lock) {
        if (!preferences.isEnabled()) return@synchronized false
        runCatching {
            val normalized = normalizeFields(event, fields) ?: return@runCatching false
            appendCurrent(renderLine(event, levelFor(event), normalized, threadName()))
        }.getOrDefault(false)
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

    /** Crash logging never includes Throwable.message, which may contain user content or secrets. */
    fun recordCrash(thread: Thread, throwable: Throwable): Boolean = synchronized(lock) {
        if (!preferences.isEnabled()) return@synchronized false
        val fields = linkedMapOf<String, Any?>(
            "exceptionType" to DiagnosticSanitizer.exceptionType(throwable.javaClass.name),
            "stack" to DiagnosticSanitizer.stackTrace(throwable),
        )
        val line = renderLine("uncaught_exception", DiagnosticLevel.ERROR, fields, thread.name)
        if (line.toByteArray(StandardCharsets.UTF_8).size > MAX_EVENT_BYTES) return@synchronized false
        if (!appendCurrent(line)) return@synchronized false
        // This file is intentionally replaced, so an export always has the most recent crash.
        writeBounded(lastCrashFile, (line + "\n").toByteArray(StandardCharsets.UTF_8), MAX_LAST_CRASH_BYTES)
        true
    }

    fun status(): DiagnosticStatus = synchronized(lock) {
        normalizeFiles()
        val current = runCatching { boundedLength(currentFile, MAX_CURRENT_BYTES) }.getOrDefault(0L)
        val previous = runCatching { boundedLength(previousFile, MAX_PREVIOUS_BYTES) }.getOrDefault(0L)
        val crash = runCatching { boundedLength(lastCrashFile, MAX_LAST_CRASH_BYTES) }.getOrDefault(0L)
        DiagnosticStatus(
            enabled = preferences.isEnabled(),
            currentBytes = current,
            previousBytes = previous,
            lastCrashBytes = crash,
            totalBytes = current + previous + crash,
            totalLimitBytes = MAX_CURRENT_BYTES.toLong() + MAX_PREVIOUS_BYTES + MAX_LAST_CRASH_BYTES,
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
        if (event.endsWith("_failed") || event == "uncaught_exception") DiagnosticLevel.ERROR else DiagnosticLevel.INFO

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
            return ByteArray(count).also { input.readFully(it) }
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
