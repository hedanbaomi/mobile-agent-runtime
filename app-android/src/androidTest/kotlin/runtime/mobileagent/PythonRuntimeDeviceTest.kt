// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ipc.InvocationTicket
import runtime.mobileagent.ipc.PythonIpcProtocol
import runtime.mobileagent.python.IsolatedPythonRuntime
import runtime.mobileagent.python.PythonCapabilityBroker
import runtime.mobileagent.python.PythonExecutionRequest
import runtime.mobileagent.python.PythonExecutionResult
import runtime.mobileagent.python.PythonPackageSource
import runtime.mobileagent.skills.CompatibilityClass
import runtime.mobileagent.skills.SkillArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Non-UI tests must not initialize MobileAgentApp's database, secrets or network clients.
 * The real isolated service and its packaged JNI/official CPython are not replaced.
 * MainActivity explicitly initializes the deferred host for the release UI smoke; production
 * MobileAgentApp still initializes normally and keeps its isolated-process early return.
 */
class PythonRuntimeDeviceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        MobileAgentApp.deferHostInitializationForInstrumentation = true
        return super.newApplication(cl, MobileAgentApp::class.java.name, context)
    }
}

/** Real device tests: no mocked runtime, no real database/key, and no network connection. */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 45_000)
    fun officialCpythonExecutesJsonWithFreshPidAndNoPreviousGlobals() = runBlocking {
        val fixture = skillZip(IDENTITY_SOURCE)
        val first = execute(fixture)
        val firstValue = succeeded(first)
        assertEquals("cpython", firstValue.getValue("implementation").jsonPrimitive.content)
        assertEquals(EXPECTED_PYTHON_VERSION, firstValue.getValue("version").jsonPrimitive.content)
        assertEquals(JsonNull, firstValue["previous"])
        assertEquals(JsonPrimitive(42), firstValue["answer"])
        assertIsolated(first, firstValue)
        // Do not sleep or wait for death before the next execute: the host facade owns that boundary.
        val second = execute(fixture)
        val secondValue = succeeded(second)
        assertEquals("A fresh interpreter must not retain the previous builtins marker", JsonNull, secondValue["previous"])
        assertIsolated(second, secondValue)
        assertTrue("Each execution must receive a fresh process", first.isolatedPid != second.isolatedPid)
        awaitProcessGone(checkNotNull(first.isolatedPid))
        awaitProcessGone(checkNotNull(second.isolatedPid))
    }

    @Test(timeout = 30_000)
    fun hostMarkerAndDirectSocketSubprocessAndCtypesAreDenied() = runBlocking {
        // The only host-private path supplied to Python is this newly-created test marker.
        val marker = File(context.filesDir, "python-device-marker-${UUID.randomUUID()}.txt")
        val markerContent = "device-test-only-${UUID.randomUUID()}"
        assertTrue("Test marker must be new", marker.createNewFile())
        marker.writeText(markerContent)
        try {
            val result = execute(skillZip(RESTRICTED_SOURCE), buildJsonObject { put("markerPath", marker.absolutePath) }.toString())
            val value = succeeded(result)
            listOf("host_open", "direct_socket", "subprocess", "posix_spawn", "ctypes").forEach { operation ->
                val observed = value.getValue(operation).jsonObject
                assertEquals("$operation must be denied, not merely fail for unrelated reasons", JsonPrimitive(true), observed["denied"])
                assertTrue("$operation needs a policy/import denial", observed.getValue("kind").jsonPrimitive.content in
                    setOf("PermissionError", "ModuleNotFoundError", "ImportError", "AttributeError"))
            }
            assertEquals("The marker must remain unchanged", markerContent, marker.readText())
            awaitProcessGone(checkNotNull(result.isolatedPid))
        } finally {
            // Never recursively delete, and never remove anything except this exact self-created marker.
            assertEquals(context.filesDir.canonicalFile, marker.canonicalFile.parentFile)
            assertTrue("Remove only the test's own marker", marker.delete())
        }
    }

    @Test(timeout = 45_000)
    fun infiniteLoopTimesOutDiesAndNextInvocationSucceeds() = runBlocking {
        val fixture = skillZip(LOOP_SOURCE)
        val ticket = ticket(fixture)
        val broker = TicketGateBroker(ticket)
        val started = SystemClock.elapsedRealtime()
        val result = withTimeout(20_000) {
            IsolatedPythonRuntime(context, broker).execute(request(fixture, ticket,
                limits = PythonIpcProtocol.PythonLimits(timeoutMs = 3_000)))
        }
        assertTrue("The Python loop must actually begin before the timeout is accepted", broker.ready.isCompleted)
        assertEquals(PythonIpcProtocol.RESULT_UNKNOWN, result.status)
        assertTrue("The looping invocation must have been accepted", result.dispatchAccepted)
        assertTrue("Host watchdog must bound the call", SystemClock.elapsedRealtime() - started < 15_000)
        val loopingPid = broker.ready.await().argumentsJson.let { Json.parseToJsonElement(it).jsonObject.getValue("pid").jsonPrimitive.int }
        assertEquals(loopingPid, result.isolatedPid)
        val next = execute(skillZip(IDENTITY_SOURCE))
        assertEquals(JsonNull, succeeded(next)["previous"])
        assertTrue("Timed-out process must not be reused", loopingPid != next.isolatedPid)
        awaitProcessGone(loopingPid)
        awaitProcessGone(checkNotNull(next.isolatedPid))
    }

    @Test(timeout = 45_000)
    fun cancellationAfterReadyKillsWorkerAndNextInvocationSucceeds() = runBlocking {
        val fixture = skillZip(LOOP_SOURCE)
        val ticket = ticket(fixture)
        val broker = TicketGateBroker(ticket)
        val dispatches = AtomicInteger()
        val job = async { IsolatedPythonRuntime(context, broker).execute(request(fixture, ticket).copy(
            onDispatched = { dispatches.incrementAndGet() })) }
        val ready = try {
            withTimeout(15_000) { broker.ready.await() }
        } catch (error: Throwable) {
            job.cancel()
            throw error
        }
        val cancelledPid = Json.parseToJsonElement(ready.argumentsJson).jsonObject.getValue("pid").jsonPrimitive.int
        job.cancel()
        withTimeout(8_000) { job.join() }
        assertTrue("The executing coroutine must observe cancellation", job.isCancelled)
        assertEquals("START dispatch notification must occur exactly once", 1, dispatches.get())
        // A cancelled execute must leave the facade ready immediately; do not hide reuse races with a sleep.
        val next = execute(skillZip(IDENTITY_SOURCE))
        assertEquals(JsonNull, succeeded(next)["previous"])
        assertTrue("Cancelled process must not be reused", cancelledPid != next.isolatedPid)
        awaitProcessGone(cancelledPid)
        awaitProcessGone(checkNotNull(next.isolatedPid))
    }

    @Test(timeout = 30_000)
    fun cancellationBeforeAuthorizationCompletesNeverDispatches() = runBlocking {
        val fixture = skillZip(IDENTITY_SOURCE)
        val ticket = ticket(fixture)
        val authorizationEntered = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val dispatches = AtomicInteger()
        val gate = object : PythonCapabilityBroker {
            override suspend fun authorize(ticket: InvocationTicket): Boolean {
                authorizationEntered.complete(Unit)
                awaitCancellation()
            }
            override suspend fun invoke(request: PythonIpcProtocol.BrokerRequest): PythonIpcProtocol.BrokerResponse {
                requests.incrementAndGet()
                return PythonIpcProtocol.BrokerResponse(request.requestId, "DENIED", errorCode = "permission_denied")
            }
        }
        val job = async { IsolatedPythonRuntime(context, gate).execute(request(fixture, ticket).copy(
            onDispatched = { dispatches.incrementAndGet() })) }
        try {
            withTimeout(5_000) { authorizationEntered.await() }
        } finally {
            job.cancel()
        }
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals("No capability can be dispatched before authorization completes", 0, requests.get())
        assertEquals("Cancellation during authorization must never dispatch START", 0, dispatches.get())
        val next = execute(fixture)
        assertIsolated(next, succeeded(next))
        awaitProcessGone(checkNotNull(next.isolatedPid))
    }

    @Test(timeout = 40_000)
    fun ticketIdentityAndLiveRevocationAreCheckedBeforeBrokerDispatch() = runBlocking {
        val fixture = skillZip(REVOCATION_SOURCE)
        val original = ticket(fixture)
        val wrongTickets = listOf(
            original.copy(invocationId = UUID.randomUUID().toString()),
            original.copy(runId = UUID.randomUUID().toString()),
            original.copy(grantRevision = original.grantRevision + 1),
            original.copy(packageHash = "0".repeat(64)),
            original.copy(oneTimeToken = randomToken()),
        )
        wrongTickets.forEach { wrong ->
            val broker = TicketGateBroker(original)
            val result = withTimeout(5_000) { IsolatedPythonRuntime(context, broker).execute(request(fixture, wrong)) }
            assertEquals(PythonIpcProtocol.RESULT_FAILED, result.status)
            assertEquals("permission_denied", result.errorCode)
            assertNull("Rejected ticket must not start an isolated process", result.isolatedPid)
            assertTrue("Rejected ticket must never dispatch a capability", broker.requests.isEmpty())
        }

        val broker = TicketGateBroker(original, revokeAfterFirst = true)
        val result = withTimeout(20_000) { IsolatedPythonRuntime(context, broker).execute(request(fixture, original)) }
        val value = succeeded(result)
        assertEquals(JsonPrimitive(true), value["firstGranted"])
        assertEquals(JsonPrimitive(true), value["secondDenied"])
        assertEquals("Only the first request may reach the capability handler", 1, broker.requests.size)
        assertTrue("Authorize must run for entry and both frames", broker.authorizationChecks.get() >= 3)
        assertTrue("The actual frame must retain the exact invocation ticket", broker.requests.all { it.ticket == original })
        awaitProcessGone(checkNotNull(result.isolatedPid))
    }

    @Test(timeout = 45_000)
    fun inputAndOutputLimitsAreEnforcedAndLargeOutputUsesTheRealPipe() = runBlocking {
        val fixture = skillZip("def run(value):\n    return {\"payload\": value.get(\"payload\", \"\")}\n")
        val input = buildJsonObject { put("payload", "abcdefgh") }.toString()
        val exactInputBytes = input.toByteArray(Charsets.UTF_8).size
        val ticket = ticket(fixture)
        val broker = TicketGateBroker(ticket)
        val oversized = IsolatedPythonRuntime(context, broker).execute(request(fixture, ticket, input,
            PythonIpcProtocol.PythonLimits(maxInputBytes = exactInputBytes - 1)))
        assertEquals(PythonIpcProtocol.RESULT_FAILED, oversized.status)
        assertEquals("input_limit", oversized.errorCode)
        assertNull(oversized.isolatedPid)
        assertEquals("Input overflow must fail before any authorization/service work", 0, broker.authorizationChecks.get())

        val exact = execute(fixture, input, PythonIpcProtocol.PythonLimits(maxInputBytes = exactInputBytes))
        assertEquals(JsonPrimitive("abcdefgh"), succeeded(exact)["payload"])
        awaitProcessGone(checkNotNull(exact.isolatedPid))

        val largeSize = PythonIpcProtocol.MAX_CONTROL_FRAME_BYTES + 1024
        val largeFixture = skillZip("def run(value):\n    return {\"payload\": \"x\" * value[\"size\"]}\n")
        val sizeInput = buildJsonObject { put("size", largeSize) }.toString()
        val large = execute(largeFixture, sizeInput)
        assertEquals(largeSize, succeeded(large).getValue("payload").jsonPrimitive.content.length)
        assertTrue("Real output must exceed one Binder control frame", checkNotNull(large.valueJson).toByteArray().size > PythonIpcProtocol.MAX_CONTROL_FRAME_BYTES)
        awaitProcessGone(checkNotNull(large.isolatedPid))

        val overflow = execute(largeFixture, sizeInput, PythonIpcProtocol.PythonLimits(maxOutputBytes = 128))
        assertEquals(PythonIpcProtocol.RESULT_UNKNOWN, overflow.status)
        assertTrue(overflow.dispatchAccepted)
        assertEquals("output_limit", overflow.errorCode)
        assertNull("Oversized result must not be exposed", overflow.valueJson)
        awaitProcessGone(checkNotNull(overflow.isolatedPid))
    }

    @Test(timeout = 30_000)
    fun realSdkRoundTripsBrokerResponseLargerThanOneControlFrame() = runBlocking {
        val fixture = skillZip("import mobileagent_sdk\ndef run(value):\n    return mobileagent_sdk._request(\"test.large\", {})\n")
        val ticket = ticket(fixture)
        val text = "synthetic-broker-data-".repeat(5_000)
        val response = buildJsonObject { put("payload", text) }.toString()
        assertTrue(response.toByteArray().size > PythonIpcProtocol.MAX_CONTROL_FRAME_BYTES)
        val broker = TicketGateBroker(ticket, valueJson = response)
        val result = withTimeout(20_000) { IsolatedPythonRuntime(context, broker).execute(request(fixture, ticket)) }
        assertEquals(text, succeeded(result).getValue("payload").jsonPrimitive.content)
        assertEquals(1, broker.requests.size)
        assertTrue("Broker frame identity must remain bound", broker.requests.single().ticket == ticket)
        awaitProcessGone(checkNotNull(result.isolatedPid))
    }

    @Test(timeout = 60_000)
    fun rawDescriptorsCannotInjectBrokerRequestsOrForgeSuccessfulResults() = runBlocking {
        val fixture = skillZip(RAW_FD_SOURCE)
        // Probe each wire type in a fresh worker; the first private-channel injection ends a worker.
        for (kind in listOf("result", "broker")) {
            val ticket = ticket(fixture)
            val broker = TicketGateBroker(ticket)
            val guessedNonce = randomToken() // Synthetic guess only; the real native channel nonce is never exposed.
            val forgedOutput = "{\"forged\":true}".toByteArray(Charsets.UTF_8)
            val payload = if (kind == "result") {
                framed(PythonIpcProtocol.encodeResultHeader(PythonIpcProtocol.ResultHeader(
                    PythonIpcProtocol.VERSION, "result", PythonIpcProtocol.RESULT_SUCCEEDED, forgedOutput.size,
                    channelNonce = guessedNonce))) + forgedOutput
            } else {
                val encoded = PythonIpcProtocol.encodeBrokerRequest(PythonIpcProtocol.BrokerRequest(
                    ticket, "raw-fd-forged", "test.forged", "{}"))
                val frame = JsonObject(Json.parseToJsonElement(encoded.toString(Charsets.UTF_8)).jsonObject +
                    ("channelNonce" to JsonPrimitive(guessedNonce))).toString().toByteArray(Charsets.UTF_8)
                assertTrue("Probe must contain a valid frame with its own test ticket",
                    PythonIpcProtocol.decodeBrokerRequestFrame(frame).request.ticket == ticket)
                framed(frame)
            }
            // A stronger probe has its own synthetic invocation token, but cannot know the channel nonce.
            val input = buildJsonObject { put("payload", payload.joinToString("") { "%02x".format(it.toInt() and 0xff) }) }.toString()
            val result = withTimeout(25_000) { IsolatedPythonRuntime(context, broker).execute(request(fixture, ticket, input)) }
            assertTrue("A real SDK ready frame must precede injection", broker.ready.isCompleted)
            assertTrue("A forged capability frame must never reach the host", broker.requests.none { it.capability == "test.forged" })
            if (result.status == PythonIpcProtocol.RESULT_SUCCEEDED) {
                // An implementation may instead remove raw FD APIs entirely; require positive policy denial.
                val value = succeeded(result)
                assertEquals(JsonPrimitive(false), value["forged"])
                assertEquals(JsonPrimitive(true), value["probeComplete"])
                val knownFd = value.getValue("knownFd").jsonObject
                listOf("os_write", "posix_write", "os_writev", "open_fd", "fileio_fd", "fdopen").forEach { api ->
                    assertEquals("$api must explicitly refuse known-valid FD1", JsonPrimitive("DENIED"), knownFd[api])
                }
                assertEquals(JsonPrimitive(0), value["injectionWrites"])
                assertEquals(1, broker.requests.count { it.capability == "log.info" })
            } else {
                assertEquals("Raw injection may never produce a forged successful result", PythonIpcProtocol.RESULT_UNKNOWN, result.status)
                assertEquals("A timeout or log limit is not evidence of channel authentication", "invalid_nonce", result.errorCode)
                assertNull(result.valueJson)
                assertTrue(result.dispatchAccepted)
            }
            awaitProcessGone(checkNotNull(result.isolatedPid))
        }
    }

    @Test(timeout = 45_000)
    fun logOverflowStopsTheRealWorkerAndDoesNotBecomeSuccess() = runBlocking {
        val fixture = skillZip(LOG_OVERFLOW_SOURCE)
        val ticket = ticket(fixture)
        val broker = TicketGateBroker(ticket)
        val started = SystemClock.elapsedRealtime()
        val result = withTimeout(20_000) {
            IsolatedPythonRuntime(context, broker).execute(request(fixture, ticket,
                limits = PythonIpcProtocol.PythonLimits(timeoutMs = 15_000, maxLogBytes = 1024)))
        }
        assertTrue("The real script must start before the log overflow", broker.ready.isCompleted)
        assertEquals(PythonIpcProtocol.RESULT_UNKNOWN, result.status)
        assertTrue(result.dispatchAccepted)
        assertEquals("log_limit", result.errorCode)
        assertNull("An over-limit execution must not return a fake success", result.valueJson)
        assertTrue("The log limit must stop execution before its time watchdog", SystemClock.elapsedRealtime() - started < 12_000)
        val childPid = broker.ready.await().argumentsJson.let { Json.parseToJsonElement(it).jsonObject.getValue("pid").jsonPrimitive.int }
        assertEquals(childPid, result.isolatedPid)
        val next = execute(skillZip(IDENTITY_SOURCE))
        assertEquals(JsonNull, succeeded(next)["previous"])
        assertTrue(childPid != next.isolatedPid)
        awaitProcessGone(childPid)
        awaitProcessGone(checkNotNull(next.isolatedPid))
    }

    @Test(timeout = 30_000)
    fun logOverflowCannotLoseToAnImmediatelyReturnedValidResult() = runBlocking {
        val fixture = skillZip(LOG_THEN_RETURN_SOURCE)
        val result = withTimeout(20_000) {
            execute(fixture, limits = PythonIpcProtocol.PythonLimits(timeoutMs = 10_000, maxLogBytes = 1024))
        }
        assertEquals("A complete JSON result cannot win the log-limit race", PythonIpcProtocol.RESULT_UNKNOWN, result.status)
        assertEquals("log_limit", result.errorCode)
        assertTrue("The overflowing script must have been accepted", result.dispatchAccepted)
        assertNull("The valid-looking result must be discarded after log overflow", result.valueJson)
        val overflowPid = checkNotNull(result.isolatedPid)
        assertTrue(overflowPid > 0 && overflowPid != Process.myPid())
        // Start a real call immediately; the facade must have retired the overflowing worker.
        val next = execute(skillZip(IDENTITY_SOURCE))
        assertEquals(JsonNull, succeeded(next)["previous"])
        assertTrue("A log-limited worker cannot be reused", overflowPid != next.isolatedPid)
        awaitProcessGone(overflowPid)
        awaitProcessGone(checkNotNull(next.isolatedPid))
    }

    @Test(timeout = 45_000)
    fun realWorkerDeathAfterReadyIsUnknownAndNextInvocationIsFresh() = runBlocking {
        val fixture = skillZip(SELF_EXIT_SOURCE)
        val ticket = ticket(fixture)
        val broker = TicketGateBroker(ticket)
        val result = withTimeout(20_000) { IsolatedPythonRuntime(context, broker).execute(request(fixture, ticket)) }
        assertTrue("A real Broker request must precede the controlled worker death", broker.ready.isCompleted)
        assertEquals("Losing the real result after execution started is uncertain", PythonIpcProtocol.RESULT_UNKNOWN, result.status)
        assertTrue(result.dispatchAccepted)
        assertNull(result.valueJson)
        val childPid = broker.ready.await().argumentsJson.let { Json.parseToJsonElement(it).jsonObject.getValue("pid").jsonPrimitive.int }
        assertEquals(childPid, result.isolatedPid)
        val next = execute(skillZip(IDENTITY_SOURCE))
        assertEquals(JsonNull, succeeded(next)["previous"])
        assertTrue("A dead worker cannot be reused", childPid != next.isolatedPid)
        awaitProcessGone(childPid)
        awaitProcessGone(checkNotNull(next.isolatedPid))
    }

    private fun framed(payload: ByteArray): ByteArray = ByteArrayOutputStream().also {
        PythonIpcProtocol.Frames.write(it, payload)
    }.toByteArray()

    private fun succeeded(result: PythonExecutionResult): JsonObject {
        val diagnostic = "Real runtime failed (status=${safeFailureText(result.status, 32)}, " +
            "code=${safeFailureText(result.errorCode, 80)}, dispatchAccepted=${result.dispatchAccepted}, " +
            "uid=${result.isolatedUid}, pid=${result.isolatedPid}, " +
            "message=${safeFailureText(result.errorMessage, 1200)})"
        assertEquals(diagnostic, PythonIpcProtocol.RESULT_SUCCEEDED, result.status)
        assertNotNull("Successful execution must carry JSON", result.valueJson)
        return Json.parseToJsonElement(checkNotNull(result.valueJson)).jsonObject
    }

    /** Fixture diagnostics only: never stringify the result, ticket, nonce, package or JSON payload. */
    private fun safeFailureText(value: String?, maximum: Int): String {
        if (value == null) return "none"
        val sensitiveField = Regex("(?i)(nonce|ticket|token|secret|password|authorization|api.?key|bearer)")
        val opaqueValue = Regex("[A-Za-z0-9_+/=-]{32,}")
        return value.take(4096).lineSequence().take(16).joinToString(" | ") { line ->
            if (sensitiveField.containsMatchIn(line)) "[sensitive diagnostic omitted]"
            else opaqueValue.replace(line, "[opaque value omitted]")
                .map { character -> if (character.isISOControl()) ' ' else character }.joinToString("")
        }.take(maximum)
    }

    private fun assertIsolated(result: PythonExecutionResult, value: JsonObject) {
        assertNotNull("The runtime must report the Binder-observed UID", result.isolatedUid)
        assertNotNull("The runtime must report the Binder-observed PID", result.isolatedPid)
        assertTrue("Python must not share the host UID", Process.myUid() != result.isolatedUid)
        assertTrue("Python must not run in the host process", Process.myPid() != result.isolatedPid)
        assertEquals(result.isolatedUid, value.getValue("uid").jsonPrimitive.int)
        assertEquals(result.isolatedPid, value.getValue("pid").jsonPrimitive.int)
    }

    private suspend fun awaitProcessGone(pid: Int) {
        assertTrue("Only a known child PID may be checked", pid > 0 && pid != Process.myPid())
        withTimeout(8_000) {
            while (true) {
                val gone = try {
                    Os.kill(pid, 0) // Signal zero only checks existence; it never kills another process.
                    false
                } catch (error: ErrnoException) {
                    error.errno == OsConstants.ESRCH
                }
                if (gone) return@withTimeout
                delay(25)
            }
        }
    }

    private suspend fun execute(
        fixture: Fixture,
        input: String = "{}",
        limits: PythonIpcProtocol.PythonLimits = PythonIpcProtocol.PythonLimits(),
    ): PythonExecutionResult {
        val ticket = ticket(fixture)
        return withTimeout(20_000) { IsolatedPythonRuntime(context, TicketGateBroker(ticket)).execute(request(fixture, ticket, input, limits)) }
    }

    private fun request(fixture: Fixture, ticket: InvocationTicket, input: String = "{}",
                        limits: PythonIpcProtocol.PythonLimits = PythonIpcProtocol.PythonLimits()): PythonExecutionRequest =
        PythonExecutionRequest(ticket, "device_fixture:run", input, PythonPackageSource.Bytes(fixture.bytes), limits)

    private fun ticket(fixture: Fixture): InvocationTicket = InvocationTicket(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), fixture.hash, 1, randomToken(),
    ).also { assertTrue("Generated ticket must satisfy the real wire contract", it.validate()) }

    private data class Fixture(val bytes: ByteArray, val hash: String)

    private fun skillZip(source: String): Fixture {
        val manifest = """{"schemaVersion":1,"id":"dev.mobileagent.device_fixture","name":"Device fixture","version":"1.0.0","license":"AGPL-3.0-only","runtime":{"kind":"python","python":"3.14","mode":"pure-python","entrypoint":"device_fixture:run"},"permissions":{"log.info":{},"test.ready":{},"test.large":{}},"inputSchema":{"type":"object","properties":{},"additionalProperties":true},"outputSchema":{"type":"object","properties":{},"additionalProperties":true}}"""
        val entries = linkedMapOf(
            "SKILL.md" to "# Device fixture\nSynthetic instrumentation data only.\n",
            "mobile-skill.json" to manifest,
            "device_fixture.py" to source,
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> entries.forEach { (name, content) ->
                val payload = content.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED // No zlib dependency is assumed for zipimport.
                    size = payload.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(payload) }.value
                    time = 0L
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            } }
        }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val inspection = SkillArchive.inspect(bytes, hash)
        assertTrue("Synthetic fixture must pass the actual package inspector", inspection.installable)
        assertEquals(CompatibilityClass.B, inspection.classification)
        assertEquals("device_fixture:run", inspection.manifest?.entrypoint)
        return Fixture(bytes, hash)
    }

    /** A deterministic in-memory capability endpoint; the Binder, SDK and CPython paths remain real. */
    private class TicketGateBroker(
        private val expected: InvocationTicket,
        private val revokeAfterFirst: Boolean = false,
        private val valueJson: String = "{\"granted\":true}",
    ) : PythonCapabilityBroker {
        val authorizationChecks = AtomicInteger()
        val requests = ConcurrentLinkedQueue<PythonIpcProtocol.BrokerRequest>()
        val ready = CompletableDeferred<PythonIpcProtocol.BrokerRequest>()
        private val enabled = AtomicBoolean(true)

        override suspend fun authorize(ticket: InvocationTicket): Boolean {
            authorizationChecks.incrementAndGet()
            return enabled.get() && expected == ticket
        }

        override suspend fun invoke(request: PythonIpcProtocol.BrokerRequest): PythonIpcProtocol.BrokerResponse {
            if (!enabled.get() || request.ticket != expected) {
                return PythonIpcProtocol.BrokerResponse(request.requestId, "DENIED", errorCode = "permission_denied")
            }
            requests.add(request)
            if (request.capability == "test.ready") ready.complete(request)
            if (revokeAfterFirst) enabled.set(false)
            return PythonIpcProtocol.BrokerResponse(request.requestId, "OK", valueJson)
        }
    }

    private companion object {
        const val EXPECTED_PYTHON_VERSION = "3.14.7"
        fun randomToken(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        val IDENTITY_SOURCE = """
            import os
            import sys
            import builtins
            def run(value):
                previous = getattr(builtins, '_mobileagent_device_test_previous', None)
                builtins._mobileagent_device_test_previous = 'set-by-previous-invocation'
                return {'implementation': sys.implementation.name,
                        'version': '.'.join(str(x) for x in sys.version_info[:3]),
                        'uid': os.getuid(), 'pid': os.getpid(), 'previous': previous, 'answer': 6 * 7}
        """.trimIndent()

        val LOOP_SOURCE = """
            import os
            import mobileagent_sdk
            def run(value):
                mobileagent_sdk._request('test.ready', {'pid': os.getpid()})
                while True:
                    pass
        """.trimIndent()

        val REVOCATION_SOURCE = """
            import mobileagent_sdk
            def run(value):
                first = mobileagent_sdk.log_info('synthetic first request')
                denied = False
                try:
                    mobileagent_sdk.log_info('synthetic second request')
                except PermissionError:
                    denied = True
                return {'firstGranted': first.get('granted') is True, 'secondDenied': denied}
        """.trimIndent()

        val LOG_OVERFLOW_SOURCE = """
            import os
            import mobileagent_sdk
            def run(value):
                mobileagent_sdk._request('test.ready', {'pid': os.getpid()})
                for index in range(32):
                    print('synthetic-log-' * 256, flush=True)
                while True:
                    pass
        """.trimIndent()

        val LOG_THEN_RETURN_SOURCE = """
            def run(value):
                print('x' * 2048, flush=True)
                return {'returnedValidJson': True}
        """.trimIndent()

        val SELF_EXIT_SOURCE = """
            import os
            import mobileagent_sdk
            def run(value):
                mobileagent_sdk._request('test.ready', {'pid': os.getpid()})
                os._exit(91)
        """.trimIndent()

        val RESTRICTED_SOURCE = """
            def run(value):
                def host_open():
                    with open(value['markerPath'], 'rb') as source:
                        source.read(1)
                def direct_socket():
                    import socket
                    channel = socket.socket()
                    channel.close()
                def subprocess_call():
                    import subprocess
                    subprocess.run(['/system/bin/sh', '-c', 'exit 0'], check=True, timeout=1)
                def posix_spawn_call():
                    import os
                    pid = os.posix_spawn('/system/bin/sh', ['/system/bin/sh', '-c', 'exit 0'], {})
                    os.waitpid(pid, 0)
                def ctypes_load():
                    import ctypes
                    ctypes.CDLL(value['markerPath'])
                def observed(operation):
                    try:
                        operation()
                    except (PermissionError, ModuleNotFoundError, ImportError, AttributeError) as error:
                        return {'denied': True, 'kind': type(error).__name__}
                    except Exception as error:
                        return {'denied': False, 'kind': type(error).__name__}
                    return {'denied': False, 'kind': 'allowed'}
                return {'host_open': observed(host_open), 'direct_socket': observed(direct_socket),
                        'subprocess': observed(subprocess_call), 'posix_spawn': observed(posix_spawn_call),
                        'ctypes': observed(ctypes_load)}
        """.trimIndent()

        val RAW_FD_SOURCE = """
            import os
            import io
            import mobileagent_sdk
            def run(value):
                mobileagent_sdk._request('test.ready', {'pid': os.getpid()})
                payload = bytes.fromhex(value['payload'])
                def write_open(fd):
                    with open(fd, 'wb', closefd=False) as stream:
                        stream.write(payload)
                        stream.flush()
                def write_fileio(fd):
                    with io.FileIO(fd, 'w', closefd=False) as stream:
                        stream.write(payload)
                def write_fdopen(fd):
                    with os.fdopen(fd, 'wb', closefd=False) as stream:
                        stream.write(payload)
                        stream.flush()
                operations = {
                    'os_write': lambda fd: os.write(fd, payload),
                    'posix_write': lambda fd: __import__('posix').write(fd, payload),
                    'os_writev': lambda fd: os.writev(fd, [payload]),
                    'open_fd': write_open, 'fileio_fd': write_fileio, 'fdopen': write_fdopen}
                def probe(operation, fd):
                    try:
                        operation(fd)
                    except (PermissionError, AttributeError, ImportError):
                        return 'DENIED'
                    except Exception:
                        return 'UNRELATED_ERROR'
                    return 'WRITABLE'
                known = {name: probe(operation, 1) for name, operation in operations.items()}
                writes = 0
                for fd in range(3, 64):
                    for operation in operations.values():
                        if probe(operation, fd) == 'WRITABLE':
                            writes += 1
                mobileagent_sdk.log_info('synthetic SDK remains available')
                return {'forged': False, 'probeComplete': True, 'knownFd': known, 'injectionWrites': writes}
        """.trimIndent()
    }
}
