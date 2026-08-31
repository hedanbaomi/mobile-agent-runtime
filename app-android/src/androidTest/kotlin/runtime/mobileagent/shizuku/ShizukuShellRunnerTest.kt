// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Device-local process-contract tests.  They do not require Shizuku: the
 * runner boundary is injectable, while the production runner is exercised
 * against the device's own /system/bin/sh.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuShellRunnerTest {
    @Test(timeout = 30_000)
    fun stdinShellSupportsPipelinesRedirectionControlOperatorsQuotesNewlinesAndUnicode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = newTestDirectory(context)
        val runner = ProcessShizukuShellRunner()
        try {
            val invocation = invoke(
                runner,
                ShizukuShellRunnerRequest(
                    callId = "syntax-${UUID.randomUUID()}",
                    command = """
                        printf '%s' 'single " quote'
                        printf '%s' "double ' quote"
                        echo echo-ok
                        printf '%s\n' 'line-one'
                        printf '%s\n' 'line-two'
                        printf '%s\n' 'pipe' | tr p P
                        printf '%s' 'redirected' > result.txt
                        cat result.txt
                        true && printf '%s' 'and-ok'
                        false && printf '%s' 'bad-and' || printf '%s' 'or-ok'
                        printf '%s' 'stderr' >&2
                        printf '%s' '你好，世界'
                    """.trimIndent(),
                    cwd = root.absolutePath,
                    timeoutMs = 5_000L,
                    maxStdoutBytes = 64 * 1024,
                    maxStderrBytes = 64 * 1024,
                ),
            )
            val stdout = String(invocation.stdout, StandardCharsets.UTF_8)
            val stderr = String(invocation.stderr, StandardCharsets.UTF_8)
            assertEquals("COMPLETED", invocation.envelope.getString("state"))
            assertEquals(0, invocation.envelope.getInt("exitCode"))
            assertTrue(stdout.contains("single \" quote"))
            assertTrue(stdout.contains("double ' quote"))
            assertTrue(stdout.contains("echo-ok"))
            assertTrue(stdout.contains("line-one\nline-two"))
            // `tr p P` replaces both lowercase p characters in "pipe".
            assertTrue(stdout.contains("PiPe"))
            assertTrue(stdout.contains("redirected"))
            assertTrue(stdout.contains("and-ok"))
            assertTrue(stdout.contains("or-ok"))
            assertTrue(stdout.contains("你好，世界"))
            assertEquals("stderr", stderr)
            assertEquals("redirected", File(root, "result.txt").readText(StandardCharsets.UTF_8))
        } finally {
            runner.close()
            deleteTestFiles(root)
        }
    }

    @Test(timeout = 20_000)
    fun nonzeroExitKeepsSeparatedOutputAndCompletedOutcome() {
        val runner = ProcessShizukuShellRunner()
        try {
            val invocation = invoke(
                runner,
                request(
                    callId = "nonzero-${UUID.randomUUID()}",
                    command = "printf '%s' 'out'; printf '%s' 'err' >&2; exit 7",
                ),
            )
            assertEquals("COMPLETED", invocation.envelope.getString("state"))
            assertEquals(7, invocation.envelope.getInt("exitCode"))
            assertFalse(invocation.envelope.getBoolean("ok"))
            assertEquals("out", String(invocation.stdout, StandardCharsets.UTF_8))
            assertEquals("err", String(invocation.stderr, StandardCharsets.UTF_8))
        } finally {
            runner.close()
        }
    }

    @Test(timeout = 30_000)
    fun eachStreamIsCappedAndTheProcessKeepsDrainingAfterTruncation() {
        val runner = ProcessShizukuShellRunner()
        try {
            val invocation = invoke(
                runner,
                request(
                    callId = "large-${UUID.randomUUID()}",
                    command = "dd if=/dev/zero bs=200000 count=1 2>/dev/null; dd if=/dev/zero bs=200000 count=1 1>&2 2>/dev/null",
                    maxStdoutBytes = 1_024,
                    maxStderrBytes = 1_024,
                ),
            )
            assertEquals("COMPLETED", invocation.envelope.getString("state"))
            assertEquals(1_024, invocation.stdout.size)
            assertEquals(1_024, invocation.stderr.size)
            assertTrue(invocation.envelope.getBoolean("stdoutTruncated"))
            assertTrue(invocation.envelope.getBoolean("stderrTruncated"))
            assertEquals(200_000L, invocation.envelope.getLong("stdoutBytes"))
            assertEquals(200_000L, invocation.envelope.getLong("stderrBytes"))
        } finally {
            runner.close()
        }
    }

    @Test(timeout = 20_000)
    fun timeoutIsConservativeUnknownAndItsCallIdCannotBeReplayed() {
        val runner = ProcessShizukuShellRunner()
        val callId = "timeout-${UUID.randomUUID()}"
        val request = request(
            callId = callId,
            command = "sleep 1 & while :; do :; done",
            timeoutMs = 100L,
        )
        try {
            val invocation = invoke(runner, request)
            assertEquals("UNKNOWN", invocation.envelope.getString("state"))
            assertTrue(invocation.envelope.getBoolean("timedOut"))
            assertTrue(invocation.envelope.getBoolean("unknownOutcome"))
            val replay = runner.start(request)
            assertFalse(replay.accepted)
            assertEquals(ShizukuShellLimits.REPLAY_DENIED, replay.errorCode)
        } finally {
            runner.close()
        }
    }

    @Test(timeout = 20_000)
    fun cancellationIsConservativeUnknownAfterDispatch() {
        val runner = ProcessShizukuShellRunner()
        val callId = "cancel-${UUID.randomUUID()}"
        val response = runner.start(
            request(
                callId = callId,
                command = "while :; do :; done",
                timeoutMs = 5_000L,
            ),
        )
        assertTrue(response.accepted)
        try {
            Thread.sleep(150L)
            assertTrue(runner.cancel(callId))
            val invocation = readAccepted(response)
            assertEquals("UNKNOWN", invocation.envelope.getString("state"))
            assertTrue(invocation.envelope.getBoolean("cancelled"))
            assertTrue(invocation.envelope.getBoolean("unknownOutcome"))
        } finally {
            runner.close()
            closeResponse(response)
        }
    }

    @Test(timeout = 20_000)
    fun serviceDeathAfterDispatchIsConservativeUnknown() {
        val runner = ProcessShizukuShellRunner()
        val callId = "death-${UUID.randomUUID()}"
        val response = runner.start(
            request(
                callId = callId,
                command = "while :; do :; done",
                timeoutMs = 5_000L,
            ),
        )
        assertTrue(response.accepted)
        try {
            Thread.sleep(150L)
            // Closing the service-owned runner is the local contract seam for
            // a UserService/binder death while work is in flight.
            runner.close()
            val invocation = readAccepted(response)
            assertEquals("UNKNOWN", invocation.envelope.getString("state"))
            assertTrue(invocation.envelope.getBoolean("unknownOutcome"))
        } finally {
            runner.close()
            closeResponse(response)
        }
    }

    @Test(timeout = 20_000)
    fun globalConcurrencyRejectsAThirdDispatchedCall() {
        val runner = ProcessShizukuShellRunner()
        val firstId = "concurrency-a-${UUID.randomUUID()}"
        val secondId = "concurrency-b-${UUID.randomUUID()}"
        val first = runner.start(request(firstId, "while :; do :; done", timeoutMs = 5_000L))
        val second = runner.start(request(secondId, "while :; do :; done", timeoutMs = 5_000L))
        assertTrue(first.accepted)
        assertTrue(second.accepted)
        val third = runner.start(request("concurrency-c-${UUID.randomUUID()}", "printf third"))
        assertFalse(third.accepted)
        assertEquals(ShizukuShellLimits.CONCURRENCY_LIMIT, third.errorCode)
        try {
            assertTrue(runner.cancel(firstId))
            assertTrue(runner.cancel(secondId))
        } finally {
            runner.close()
            closeResponse(first)
            closeResponse(second)
        }
    }

    @Test(timeout = 20_000)
    fun invalidCwdAndMalformedUtf8AreRejectedBeforeProcessStart() {
        val runner = ProcessShizukuShellRunner()
        try {
            val sensitiveCommand = "printf TOP_SECRET_SHIZUKU_OUTPUT"
            val invalidCwd = runner.start(
                request(
                    callId = "cwd-${UUID.randomUUID()}",
                    command = sensitiveCommand,
                    cwd = "/definitely/not/a/real/mobile-agent-directory",
                ),
            )
            assertFalse(invalidCwd.accepted)
            assertEquals(ShizukuShellLimits.INVALID_CWD, invalidCwd.errorCode)
            assertFalse(invalidCwd.errorCode.orEmpty().contains(sensitiveCommand))

            val malformed = runner.start(
                request(
                    callId = "utf8-${UUID.randomUUID()}",
                    command = "\uD800",
                ),
            )
            assertFalse(malformed.accepted)
            assertEquals(ShizukuShellLimits.INVALID_REQUEST, malformed.errorCode)
            assertFalse(malformed.errorCode.orEmpty().contains("\uD800"))
        } finally {
            runner.close()
        }
    }

    @Test
    fun injectedRunnerBoundaryCanBeExercisedWithoutShizukuAndUserServiceFailsClosedForAppUid() {
        val fake = RecordingRunner()
        val service = ShizukuUserService(fake, Unit)
        val status = JSONObject(service.getStatus())
        assertNotNull(status.optString("sessionId", null))
        assertEquals(ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION, status.getInt("protocolVersion"))
        assertEquals(android.os.Process.myUid(), status.getInt("serviceUid"))
        assertTrue("The app process must not pretend to be the shell UserService", status.getInt("serviceUid") != ShizukuBridgePolicy.SHELL_UID)

        val response = service.startShell(
            status.getString("sessionId"),
            "fake-call",
            "printf fake",
            null,
            1_000L,
            1_024,
            1_024,
        )
        assertFalse(response.accepted)
        assertEquals(ShizukuShellLimits.UID_UNTRUSTED, response.errorCode)
        assertEquals(0, fake.starts)
        assertEquals(
            ShizukuShellLimits.UID_UNTRUSTED,
            JSONObject(service.statSession(status.getString("sessionId"), "file.txt")).getString("code"),
        )
        assertEquals(
            ShizukuShellLimits.UID_UNTRUSTED,
            JSONObject(service.moveSession(status.getString("sessionId"), "file.txt", "moved.txt", false)).getString("code"),
        )
        service.destroy()
        assertEquals(0, fake.closeCalls)
        fake.close()
    }

    private fun request(
        callId: String,
        command: String,
        cwd: String? = null,
        timeoutMs: Long = 5_000L,
        maxStdoutBytes: Int = 64 * 1024,
        maxStderrBytes: Int = 64 * 1024,
    ) = ShizukuShellRunnerRequest(
        callId = callId,
        command = command,
        cwd = cwd,
        timeoutMs = timeoutMs,
        maxStdoutBytes = maxStdoutBytes,
        maxStderrBytes = maxStderrBytes,
    )

    private fun invoke(runner: ProcessShizukuShellRunner, request: ShizukuShellRunnerRequest): Invocation {
        val response = runner.start(request)
        assertTrue("runner rejected test request: ${response.errorCode}", response.accepted)
        return readAccepted(response).also { invocation ->
            assertEquals(request.callId, invocation.envelope.getString("callId"))
        }
    }

    private fun readAccepted(response: ShizukuShellResponse): Invocation {
        val stdout = checkNotNull(response.stdoutFd)
        val stderr = checkNotNull(response.stderrFd)
        val result = checkNotNull(response.resultFd)
        val pool = Executors.newFixedThreadPool(3)
        return try {
            val stdoutFuture = pool.submit<ByteArray> { read(stdout) }
            val stderrFuture = pool.submit<ByteArray> { read(stderr) }
            val resultFuture = pool.submit<ByteArray> { read(result) }
            Invocation(
                envelope = JSONObject(String(resultFuture.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8)),
                stdout = stdoutFuture.get(10, TimeUnit.SECONDS),
                stderr = stderrFuture.get(10, TimeUnit.SECONDS),
            )
        } finally {
            pool.shutdownNow()
            closeResponse(response)
        }
    }

    private fun read(descriptor: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun closeResponse(response: ShizukuShellResponse) {
        runCatching { response.stdoutFd?.close() }
        runCatching { response.stderrFd?.close() }
        runCatching { response.resultFd?.close() }
    }


    private fun newTestDirectory(context: Context): File {
        // The apostrophes prove cwd is passed as ProcessBuilder.directory(),
        // not interpolated into a shell command.
        val root = File(context.cacheDir, "shizuku-shell-'${UUID.randomUUID()}'")
        assertTrue(root.mkdirs())
        return root
    }

    private fun deleteTestFiles(root: File) {
        File(root, "result.txt").delete()
        root.delete()
    }

    private data class Invocation(
        val envelope: JSONObject,
        val stdout: ByteArray,
        val stderr: ByteArray,
    )

    private class RecordingRunner : ShizukuShellRunner {
        var starts = 0
        var closeCalls = 0

        override fun start(request: ShizukuShellRunnerRequest): ShizukuShellResponse {
            starts++
            return ShizukuShellResponse.rejected("FAKE")
        }

        override fun cancel(callId: String): Boolean = false

        override fun close() {
            closeCalls++
        }
    }

}
