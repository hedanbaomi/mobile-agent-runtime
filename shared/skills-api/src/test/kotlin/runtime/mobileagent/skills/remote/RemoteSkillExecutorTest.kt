// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.remote

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteSkillExecutorTest {
    @Test
    fun invokeIsSingleUseAndMapsValidatedResult() = runBlocking {
        val transport = FakeTransport()
        val executor = RemoteSkillExecutor(transport, now = { NOW }, packageAllowed = { true })
        val request = request()

        val result = executor.invokeRemote(request)
        assertEquals(RemoteInvocationStatus.SUCCEEDED, result.status)
        assertEquals(RemoteInvocationState.SUCCEEDED, executor.state(request.invocationId))
        assertEquals(1, executor.sentInvocationCount())
        assertThrows(RemoteProtocolException::class.java) {
            runBlocking { executor.invokeRemote(request) }
        }
        assertEquals(1, transport.invocations)
    }

    @Test
    fun transportFailureBecomesUnknownWithoutReplay() = runBlocking {
        val transport = FakeTransport().apply { failInvoke = true }
        val executor = RemoteSkillExecutor(transport, now = { NOW }, packageAllowed = { true })
        val result = executor.invokeRemote(request())

        assertEquals(RemoteInvocationStatus.UNKNOWN_OUTCOME, result.status)
        assertEquals(RemoteInvocationState.UNKNOWN_OUTCOME, executor.state("inv-1"))
        assertEquals(1, transport.invocations)
    }

    @Test
    fun cancellationDeliveryDoesNotClaimRemoteStopped() = runBlocking {
        val transport = FakeTransport().apply { cancelAck = null }
        val executor = RemoteSkillExecutor(transport, now = { NOW }, packageAllowed = { true })
        val request = request()
        // Keep the invocation in a nonterminal state while transport.invoke is suspended.
        transport.holdInvoke = true
        val job = launch {
            executor.invokeRemote(request)
        }
        while (executor.state(request.invocationId) !in setOf(RemoteInvocationState.SENT, RemoteInvocationState.RUNNING)) {
            yield()
        }
        val ack = executor.cancel(
            RemoteCancelRequest(
                REMOTE_EXECUTOR_PROTOCOL_VERSION,
                request.invocationId,
                "cancel-1",
                RemoteCancelReason.USER,
            ),
        )
        assertFalse(ack.accepted)
        assertEquals(RemoteInvocationStatus.UNKNOWN_OUTCOME, ack.terminalStatus)
        assertEquals(RemoteInvocationState.UNKNOWN_OUTCOME, executor.state(request.invocationId))
        job.cancel()
    }

    @Test
    fun validatorRejectsUnsupportedVersionAndUppercaseHash() {
        assertThrows(RemoteProtocolException::class.java) {
            RemoteDtoValidator.validateCapabilities(defaultCapabilities().copy(protocolVersion = 2))
        }
        assertThrows(RemoteProtocolException::class.java) {
            RemoteDtoValidator.validateRequest(request().copy(packageSha256 = "A".repeat(64)))
        }
    }

    private fun request() = RemoteInvocationRequest(
        protocolVersion = REMOTE_EXECUTOR_PROTOCOL_VERSION,
        invocationId = "inv-1",
        runId = "run-1",
        grantId = "grant-1",
        executorId = "executor-1",
        skillId = "skill-1",
        skillVersion = "1.0.0",
        packageSha256 = "a".repeat(64),
        inputSchemaVersion = 1,
        arguments = JsonObject(mapOf("input" to JsonPrimitive("value"))),
        approvedCapabilities = listOf("network.none"),
        deadlineAt = "2026-08-29T01:00:00Z",
        limits = RemoteLimits(10_000, 10_000, 4, 4, 1_000),
    )

    private class FakeTransport : RemoteExecutorTransport {
        var invocations = 0
        var failInvoke = false
        var holdInvoke = false
        var cancelAck: RemoteCancelAck? = RemoteCancelAck(
            REMOTE_EXECUTOR_PROTOCOL_VERSION,
            "inv-1",
            "cancel-1",
            accepted = true,
            terminalStatus = null,
        )

        override suspend fun capabilities(): RemoteCapabilities = RemoteSkillExecutorTest.defaultCapabilities()

        override suspend fun invoke(request: RemoteInvocationRequest): RemoteInvocationResult {
            invocations += 1
            if (failInvoke) error("connection lost")
            if (holdInvoke) kotlinx.coroutines.awaitCancellation()
            return RemoteInvocationResult(
                protocolVersion = REMOTE_EXECUTOR_PROTOCOL_VERSION,
                invocationId = request.invocationId,
                status = RemoteInvocationStatus.SUCCEEDED,
                result = JsonObject(mapOf("ok" to JsonPrimitive(true))),
                startedAt = "2026-08-29T00:00:00Z",
                finishedAt = "2026-08-29T00:00:01Z",
                usage = RemoteUsage(1_000, 20, 0, 1, 1),
            )
        }

        override suspend fun cancel(request: RemoteCancelRequest): RemoteCancelAck? = cancelAck
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-29T00:00:00Z")

        private fun defaultCapabilities() = RemoteCapabilities(
            protocolVersion = REMOTE_EXECUTOR_PROTOCOL_VERSION,
            executorId = "executor-1",
            runtimes = listOf("python"),
            capabilities = listOf("network.none"),
            maxInputBytes = 10_000,
            maxOutputBytes = 10_000,
            maxTimeoutMs = 20_000,
            supportsCancel = true,
        )
    }
}
