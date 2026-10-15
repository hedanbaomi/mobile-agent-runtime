// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.sha256Hex

/**
 * Pure protocol layer of the managed-job tools: names, schemas, strict argument
 * parsing, opaque cursors, UTF-8 windows, result classification, and
 * payload-free status envelopes.
 *
 * Nothing here holds, locks, or mutates job state: the executor owns the single
 * lock, the job lifecycle, and every authorization decision, and calls into this
 * file only with values it already resolved.
 */

internal const val MANAGED_JOB_START = RunControlTools.JOB_START
internal const val MANAGED_JOB_OUTPUT = RunControlTools.JOB_OUTPUT
internal const val MANAGED_JOB_LIST = RunControlTools.JOB_LIST
internal const val MANAGED_JOB_CANCEL = RunControlTools.JOB_CANCEL
internal val MANAGED_JOB_NAMES = RunControlTools.JOB_NAMES
internal val MANAGED_JOB_RESERVED_NAMES = RunControlTools.ALL

internal const val JOB_ARGS_INCOMPLETE = "Tool arguments are incomplete JSON"
internal const val JOB_UNKNOWN_NOTICE = "The background tool outcome is unknown; it cannot be replayed automatically"
internal const val JOB_INTERNAL_NOTICE = "The background job failed"
internal const val JOB_READ_STOPPED_NOTICE =
    "The side-effect-free background read stopped before it finished; it changed nothing and may be started again"
internal const val JOB_DENIED_NOTICE = "The background tool call was denied by the delegate"
internal const val JOB_INVALID_NOTICE = "The background tool call was rejected as invalid by the delegate"
internal const val JOB_APPROVAL_NOTICE = "The background tool call still requires its own approval"

internal val JOB_START_KEYS = setOf("tool", "arguments")
internal val JOB_OUTPUT_KEYS = setOf("job_id", "cursor", "offset_bytes", "max_bytes", "wait_ms")
internal val JOB_CANCEL_KEYS = setOf("job_id")

private const val CURSOR_VERSION = "c1"
private const val CURSOR_MAC_CHARS = 24

/**
 * Payload-free teardown view of an executor that can start background work.
 *
 * The Run's `finally` block reads this after [ToolExecutor.close] to decide
 * whether the Run may be persisted as completed.  It is status only: no ids,
 * arguments, commands, paths, or results, so it is safe to log or persist.
 *
 * `hasUnknownOutcomes` is true only for children that were **actually
 * dispatched**, may have had side effects, and ended without a definite result
 * (deadline, cancellation, or an unknown delegate outcome).  Work that was
 * revoked before dispatch is not unknown: nothing ran.  A stopped child that the
 * caller declared side-effect-free changed nothing either, so it is not unknown.
 */
interface BackgroundWorkSummary {
    /** True while any background child may still be running. */
    val hasRunningJobs: Boolean

    /** True when a dispatched child ended without a definite result. */
    val hasUnknownOutcomes: Boolean

    /** Children handed to the delegate so far. */
    val dispatchedChildren: Int

    /** Dispatched children whose outcome is unknown. */
    val unknownOutcomeChildren: Int

    /** Run-level unknowns that no single child accounts for. */
    val unknownBatches: Int get() = 0
}

/**
 * Hard limits for [ManagedJobToolExecutor]. Every value is a real ceiling: the
 * executor never exceeds one because a caller asked nicely.
 */
data class ManagedJobBudget(
    /** Live (non-terminal) jobs per Run. */
    val maxConcurrentJobs: Int = 2,
    /** Total job starts per Run; bounded again by the model-facing schema. */
    val maxJobsPerRun: Int = 8,
    /** Buffered result bytes per job. */
    val maxResultBytesPerJob: Int = 128 * 1024,
    /** Buffered result bytes across all jobs of the Run. */
    val maxTotalResultBytes: Int = 256 * 1024,
    /** Default `job_output` chunk size. */
    val defaultChunkBytes: Int = 16 * 1024,
    /** Hard `job_output` chunk ceiling; escaping stays inside the 1 MiB serialization budget. */
    val maxChunkBytes: Int = 64 * 1024,
    /** Hard ceiling for `job_output.wait_ms`. */
    val maxWaitMs: Long = 5_000,
    /** Hard per-job deadline; backgrounding never cancels it early. */
    val jobDeadlineMs: Long = 120_000,
    /** Ceiling for the serialized nested `arguments` object. */
    val maxArgumentsBytes: Int = 32 * 1024,
    /** Revocation reaction time while a job runs. */
    val authorizationPollMs: Long = 250,
) {
    init {
        require(maxConcurrentJobs > 0) { "maxConcurrentJobs must be positive" }
        require(maxJobsPerRun >= maxConcurrentJobs) { "maxJobsPerRun must cover the concurrency limit" }
        require(maxJobsPerRun <= 256) { "maxJobsPerRun must stay small enough for the status envelope" }
        require(maxResultBytesPerJob > 0) { "maxResultBytesPerJob must be positive" }
        require(maxTotalResultBytes >= maxResultBytesPerJob) { "maxTotalResultBytes must cover one job" }
        require(defaultChunkBytes in 1..maxChunkBytes) { "defaultChunkBytes must be within the chunk ceiling" }
        require(maxChunkBytes in 1024..(64 * 1024)) { "maxChunkBytes must stay within the serialization budget" }
        require(maxWaitMs >= 0) { "maxWaitMs must not be negative" }
        require(jobDeadlineMs > 0) { "jobDeadlineMs must be positive" }
        require(maxArgumentsBytes > 0) { "maxArgumentsBytes must be positive" }
        require(authorizationPollMs > 0) { "authorizationPollMs must be positive" }
    }
}

/** Lifecycle states of one managed job. */
enum class ManagedJobStatus {
    PENDING_APPROVAL,
    RUNNING,
    SUCCEEDED,
    FAILED,
    DENIED,
    INVALID,
    NEEDS_APPROVAL,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN_OUTCOME,
    ;

    val isTerminal: Boolean get() = this != PENDING_APPROVAL && this != RUNNING
}

// ------------------------------------------------------------------ arguments

/** Strictly parsed arguments, or the reason they were refused. */
internal sealed interface JobArguments<out T> {
    data class Accepted<T>(val value: T) : JobArguments<T>

    data class Rejected(val reason: String) : JobArguments<Nothing>
}

internal data class JobStartArguments(val tool: String, val argumentsJson: String)

internal data class JobOutputArguments(
    val jobId: String,
    val offsetBytes: Int?,
    val cursor: String?,
    val maxBytes: Int?,
    val waitMs: Int,
)

internal data class JobIdArguments(val jobId: String)

internal fun parseJobStartArguments(raw: String): JobArguments<JobStartArguments> {
    val args = decodeJsonObject(raw) ?: return JobArguments.Rejected(JOB_ARGS_INCOMPLETE)
    unexpectedKeys(args, JOB_START_KEYS)?.let { return JobArguments.Rejected(it) }
    val tool = args.textOrNull("tool") ?: return JobArguments.Rejected("tool must be a nonblank string")
    val arguments = args["arguments"] as? JsonObject
        ?: return JobArguments.Rejected("arguments must be a JSON object")
    return JobArguments.Accepted(JobStartArguments(tool, arguments.toString()))
}

internal fun parseJobOutputArguments(raw: String): JobArguments<JobOutputArguments> {
    val args = decodeJsonObject(raw) ?: return JobArguments.Rejected(JOB_ARGS_INCOMPLETE)
    unexpectedKeys(args, JOB_OUTPUT_KEYS)?.let { return JobArguments.Rejected(it) }
    val jobId = args.textOrNull("job_id") ?: return JobArguments.Rejected("job_id must be a nonblank string")
    val cursor = args.textOrNull("cursor")
    val offset = args.numberOrNull("offset_bytes")
    if (cursor != null && offset != null) {
        return JobArguments.Rejected("cursor and offset_bytes are mutually exclusive")
    }
    return JobArguments.Accepted(
        JobOutputArguments(jobId, offset, cursor, args.numberOrNull("max_bytes"), args.numberOrNull("wait_ms") ?: 0),
    )
}

internal fun parseJobIdArguments(raw: String): JobArguments<JobIdArguments> {
    val args = decodeJsonObject(raw) ?: return JobArguments.Rejected(JOB_ARGS_INCOMPLETE)
    unexpectedKeys(args, JOB_CANCEL_KEYS)?.let { return JobArguments.Rejected(it) }
    val jobId = args.textOrNull("job_id") ?: return JobArguments.Rejected("job_id must be a nonblank string")
    return JobArguments.Accepted(JobIdArguments(jobId))
}

internal fun managedJobStartSchema(names: List<String>): String {
    val enum = names.joinToString(separator = ",") { "\"$it\"" }
    return """{"type":"object","additionalProperties":false,"required":["tool","arguments"],""" +
        """"properties":{""" +
        """"tool":{"type":"string","enum":[$enum],""" +
        """"description":"Name of an eligible tool to run in the background."},""" +
        """"arguments":{"type":"object","additionalProperties":true,""" +
        """"description":"Arguments object passed unchanged to the selected tool."}}}"""
}

internal fun managedJobOutputSchema(maxChunkBytes: Int, maxWaitMs: Long): String =
    """{"type":"object","additionalProperties":false,"required":["job_id"],""" +
        """"properties":{"job_id":{"type":"string","minLength":1,"maxLength":64},""" +
        """"cursor":{"type":"string","minLength":1,"maxLength":160},""" +
        """"offset_bytes":{"type":"integer","minimum":0,"maximum":2147483647},""" +
        """"max_bytes":{"type":"integer","minimum":1,"maximum":$maxChunkBytes},""" +
        """"wait_ms":{"type":"integer","minimum":0,"maximum":$maxWaitMs}}}"""

internal fun managedJobListSchema(): String =
    """{"type":"object","additionalProperties":false,"properties":{}}"""

internal fun managedJobCancelSchema(): String =
    """{"type":"object","additionalProperties":false,"required":["job_id"],""" +
        """"properties":{"job_id":{"type":"string","minLength":1,"maxLength":64}}}"""

// ------------------------------------------------------------------ results

/** Terminal classification of one delegate result; no state is touched here. */
internal data class JobCompletion(
    val status: ManagedJobStatus,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val unknownOutcome: Boolean = false,
)

internal fun classifyJobResult(result: ToolResult): JobCompletion = when (result) {
    is ToolResult.Value -> JobCompletion(ManagedJobStatus.SUCCEEDED)
    is ToolResult.Denied ->
        JobCompletion(ManagedJobStatus.DENIED, ToolErrorCode.PERMISSION_DENIED.name, JOB_DENIED_NOTICE)
    is ToolResult.Invalid ->
        JobCompletion(ManagedJobStatus.INVALID, ToolErrorCode.INVALID_REQUEST.name, JOB_INVALID_NOTICE)
    is ToolResult.Failure -> JobCompletion(ManagedJobStatus.FAILED, result.error.code.name, result.error.message)
    is ToolResult.UnknownOutcome ->
        JobCompletion(ManagedJobStatus.UNKNOWN_OUTCOME, ToolErrorCode.UNKNOWN_OUTCOME.name, JOB_UNKNOWN_NOTICE, true)
    ToolResult.NeedsApproval ->
        JobCompletion(ManagedJobStatus.NEEDS_APPROVAL, ToolErrorCode.APPROVAL_REQUIRED.name, JOB_APPROVAL_NOTICE)
}

/** Read-only declarations only narrow: the delegate must also declare the tool side-effect-free. */
internal fun sideEffectFreeTools(
    declared: Set<String>,
    eligible: Set<String>,
    delegateSpecs: Map<String, ToolSpec>,
): Set<String> = declared.filterTo(linkedSetOf()) { it in eligible && delegateSpecs[it]?.sideEffect == false }

/** A dispatched child that may have changed something and then failed has an unknown outcome. */
internal fun failedAfterDispatchStatus(dispatched: Boolean, sideEffect: Boolean): ManagedJobStatus =
    if (dispatched && sideEffect) ManagedJobStatus.UNKNOWN_OUTCOME else ManagedJobStatus.FAILED

/** Typed failure facts of a terminal status that did not come from a result. */
internal data class JobTerminal(val code: String, val message: String, val unknownOutcome: Boolean)

/**
 * A stopped child is an unknown outcome only when it may have changed something:
 * a side-effect-free read that was cancelled or timed out changed nothing, so it
 * settles as a definite, retryable stop instead of lifting the whole Run.
 */
internal fun terminalDecision(status: ManagedJobStatus, reason: String?, sideEffect: Boolean = true): JobTerminal? = when (status) {
    ManagedJobStatus.TIMED_OUT, ManagedJobStatus.CANCELLED ->
        if (sideEffect) JobTerminal(reason ?: ToolErrorCode.UNKNOWN_OUTCOME.name, JOB_UNKNOWN_NOTICE, true)
        else JobTerminal(reason ?: status.name, JOB_READ_STOPPED_NOTICE, false)
    ManagedJobStatus.UNKNOWN_OUTCOME ->
        JobTerminal(reason ?: ToolErrorCode.UNKNOWN_OUTCOME.name, JOB_UNKNOWN_NOTICE, true)
    ManagedJobStatus.FAILED ->
        JobTerminal(reason ?: ToolErrorCode.INTERNAL_ERROR.name, JOB_INTERNAL_NOTICE, false)
    else -> null
}

// ------------------------------------------------------------------ envelopes

/** Immutable status snapshot of one job; built under the executor's lock. */
internal data class ManagedJobView(
    val id: String,
    val tool: String,
    val status: ManagedJobStatus,
    val resultBytes: Int,
    val bufferTruncated: Boolean,
    val contentWithheld: Boolean,
    val unknownOutcome: Boolean,
    val statusDetail: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
)

internal fun managedJobEnvelope(
    view: ManagedJobView,
    chunk: String? = null,
    offset: Int = 0,
    maxBytes: Int = 0,
    nextOffset: Int? = null,
    cursor: String? = null,
): JsonObject {
    val fields = linkedMapOf<String, JsonElement>(
        "job_id" to jsonString(view.id),
        "tool" to jsonString(view.tool),
        "job_status" to jsonString(view.status.name),
        "terminal" to jsonBool(view.status.isTerminal),
        "result_pending" to jsonBool(!view.status.isTerminal),
        "result_bytes" to jsonInt(view.resultBytes),
        "result_truncated" to jsonBool(view.bufferTruncated),
        "content_withheld" to jsonBool(view.contentWithheld),
    )
    if (chunk != null) {
        fields["offset_bytes"] = jsonInt(offset)
        fields["max_bytes"] = jsonInt(maxBytes)
        fields["next_offset_bytes"] = nextOffset?.let { jsonInt(it) } ?: JsonNull
        fields["truncated"] = jsonBool(nextOffset != null)
        fields["cursor"] = cursor?.let { jsonString(it) } ?: JsonNull
        fields["chunk"] = jsonString(chunk)
    }
    view.statusDetail?.let { fields["status_detail"] = jsonString(it) }
    view.failureCode?.let { fields["failure_code"] = jsonString(it) }
    view.failureMessage?.let { fields["failure_message"] = jsonString(it) }
    if (view.unknownOutcome) {
        fields["outcome"] = jsonString(ToolErrorCode.UNKNOWN_OUTCOME.name)
        fields["automaticReplayAllowed"] = jsonBool(false)
    }
    if (view.startedAtMs > 0L) fields["started_at_ms"] = jsonLong(view.startedAtMs)
    view.finishedAtMs?.let { fields["finished_at_ms"] = jsonLong(it) }
    return JsonObject(fields)
}

/** Status only: never arguments, commands, paths, or child call ids. */
internal fun managedJobListEnvelope(
    views: List<ManagedJobView>,
    jobsStarted: Int,
    totalResultBytes: Long,
    budget: ManagedJobBudget,
): JsonObject {
    val entries = views.map { view ->
        val fields = linkedMapOf<String, JsonElement>(
            "job_id" to jsonString(view.id),
            "tool" to jsonString(view.tool),
            "job_status" to jsonString(view.status.name),
            "terminal" to jsonBool(view.status.isTerminal),
            "result_pending" to jsonBool(!view.status.isTerminal),
            "result_bytes" to jsonInt(view.resultBytes),
            "result_available" to jsonBool(view.resultBytes > 0 && !view.contentWithheld),
            "content_withheld" to jsonBool(view.contentWithheld),
            "result_truncated" to jsonBool(view.bufferTruncated),
        )
        view.statusDetail?.let { fields["status_detail"] = jsonString(it) }
        if (view.unknownOutcome) {
            fields["outcome"] = jsonString(ToolErrorCode.UNKNOWN_OUTCOME.name)
            fields["automaticReplayAllowed"] = jsonBool(false)
        }
        view.failureCode?.let { fields["failure_code"] = jsonString(it) }
        if (view.startedAtMs > 0L) fields["started_at_ms"] = jsonLong(view.startedAtMs)
        view.finishedAtMs?.let { fields["finished_at_ms"] = jsonLong(it) }
        JsonObject(fields)
    }
    return JsonObject(
        linkedMapOf(
            "jobs" to JsonArray(entries),
            "job_count" to jsonInt(entries.size),
            "running_jobs" to jsonInt(views.count { !it.status.isTerminal }),
            "jobs_started" to jsonInt(jobsStarted),
            "max_concurrent_jobs" to jsonInt(budget.maxConcurrentJobs),
            "max_jobs_per_run" to jsonInt(budget.maxJobsPerRun),
            "total_result_bytes" to jsonLong(totalResultBytes),
            "max_total_result_bytes" to jsonInt(budget.maxTotalResultBytes),
        ),
    )
}

// ------------------------------------------------------------------ cursors

/**
 * Opaque, job-bound pagination cursor.  It encodes only the job id and the byte
 * offset, authenticated with a per-executor nonce that is never disclosed, so a
 * cursor cannot be forged for another job or another offset.
 */
internal class JobCursor(private val nonce: String) {
    fun forOffset(jobId: String, offset: Int): String =
        "$CURSOR_VERSION.$jobId.$offset.${mac(jobId, offset)}"

    /** Returns (jobId, offset); tampered or foreign cursors decode to null. */
    fun decode(cursor: String): Pair<String, Int>? {
        val parts = cursor.split('.')
        if (parts.size != 4 || parts[0] != CURSOR_VERSION) return null
        val jobId = parts[1]
        val offset = parts[2].toIntOrNull() ?: return null
        if (offset < 0 || jobId.isBlank()) return null
        if (mac(jobId, offset) != parts[3]) return null
        return jobId to offset
    }

    private fun mac(jobId: String, offset: Int): String =
        sha256Hex("$nonce|$jobId|$offset").substring(0, CURSOR_MAC_CHARS)
}

// ------------------------------------------------------------------ utf8

/**
 * Largest code-point boundary at or before [index] (clamped to the array).
 *
 * Callers only ever pass valid UTF-8 (a Kotlin `String` encoded as UTF-8 always
 * is), so the result is the start of a code point and never the middle of one.
 */
internal fun utf8Boundary(bytes: ByteArray, index: Int): Int {
    if (index <= 0) return 0
    if (index >= bytes.size) return bytes.size
    var at = index
    while (at > 0 && (bytes[at].toInt() and 0xFF and 0xC0) == 0x80) at -= 1
    return at
}

/**
 * Smallest code-point boundary strictly after [index], used to guarantee that a
 * chunk request smaller than one code point still makes progress: the byte cap
 * is a target, never a reason to stall the reader.
 */
internal fun utf8NextBoundary(bytes: ByteArray, index: Int): Int {
    if (index >= bytes.size) return bytes.size
    var at = index + 1
    while (at < bytes.size && (bytes[at].toInt() and 0xFF and 0xC0) == 0x80) at += 1
    return at
}

// ------------------------------------------------------------------ ids and json

private val secureRandom = SecureRandom()

internal fun randomHex(bytes: Int): String =
    ByteArray(bytes).also { secureRandom.nextBytes(it) }.joinToString("") { byte -> "%02x".format(byte) }

internal fun randomJobId(): String = "job-" + randomHex(12)

internal fun decodeJsonObject(raw: String): JsonObject? {
    if (raw.isBlank()) return null
    return runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
}

internal fun JsonObject.textOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}

internal fun JsonObject.numberOrNull(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.intOrNull
}

/** Strict parameter checking: an unexpected key is a caller error, not a hint. */
internal fun unexpectedKeys(args: JsonObject, allowed: Set<String>): String? {
    val unknown = args.keys - allowed
    if (unknown.isEmpty()) return null
    return "Unknown parameter: ${unknown.sorted().joinToString()}"
}

internal fun jsonString(value: String): JsonElement = JsonPrimitive(value)

internal fun jsonInt(value: Int): JsonElement = JsonPrimitive(value)

internal fun jsonLong(value: Long): JsonElement = JsonPrimitive(value)

internal fun jsonBool(value: Boolean): JsonElement = JsonPrimitive(value)
