// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolExecutorTest {
    @Test
    fun blockingBridgePublishesSpecsAndDelegatesSuspendCalls() = runBlocking {
        val broker = ToolBroker(
            effectiveCapabilities = emptySet(),
            context = ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { _, _ -> "{}" },
            ),
        )
        val executor: ToolExecutor = broker.asToolExecutor()

        assertTrue(executor.specs.any { it.name == "calculator" })
        assertEquals(
            ToolResult.Value("{\"value\":4.0}"),
            executor.invoke(ToolCall("call-1", "calculator", "{\"expression\":\"2+2\"}")),
        )
    }

    @Test
    fun blockingBridgeInterruptsBlockingToolWhenFlowIsCancelled() = runBlocking {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val broker = ToolBroker(
            effectiveCapabilities = setOf("network.http"),
            context = ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { _, _ -> "{}" },
                allowedHosts = setOf("example.invalid"),
                httpGet = {
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                        "{}"
                    } catch (error: InterruptedException) {
                        interrupted.countDown()
                        throw error
                    }
                },
            ),
            autoApproveSideEffects = true,
        )
        val executor = broker.asToolExecutor()
        val job = launch(Dispatchers.IO) {
            executor.invoke(
                ToolCall("blocking-http", "http_request", "{\"url\":\"https://example.invalid/resource\"}"),
            )
        }

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
    }
}
