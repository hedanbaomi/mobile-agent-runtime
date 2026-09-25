// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.ContextWindowProducer

class SiliconFlowContextCatalogTest {
    @Test fun exactModelIdReadsItsOwnDocumentedWindow() {
        val body = """\"modelName\":\"Other/Model\",\"contextLen\":8192,\"modelName\":\"Qwen/Qwen3.8-27B\",\"contextLen\":262144"""
        assertEquals(262144, SiliconFlowContextCatalog.parse(body, "Qwen/Qwen3.8-27B"))
        assertNull(SiliconFlowContextCatalog.parse(body, "Qwen/Qwen3.8-27"))
    }

    @Test fun missingOrAmbiguousWindowFailsClosed() {
        assertNull(SiliconFlowContextCatalog.parse("\"modelName\":\"Qwen/Qwen3.8-27B\",\"modelName\":\"Other\",\"contextLen\":8192", "Qwen/Qwen3.8-27B"))
    }

    @Test fun catalogHttpResultCanPassProducerTimestampValidation() = runBlocking {
        val target = "siliconflow|https://api.siliconflow.cn/v1|Qwen/Qwen3.8-27B"
        HttpClient(MockEngine { request ->
            assertEquals(SiliconFlowContextCatalog.CATALOG_URL, request.url.toString())
            respond("""\"modelName\":\"Qwen/Qwen3.8-27B\",\"contextLen\":262144""", HttpStatusCode.OK)
        }).use { http ->
            val value = ContextWindowProducer().metadata(target) { selected ->
                SiliconFlowContextCatalog(http).read("Qwen/Qwen3.8-27B", selected)
            }
            assertEquals(262144, value.value)
        }
    }

    @Test fun oversizedCatalogIsRejectedBeforeParsing() = runBlocking {
        HttpClient(MockEngine { respond("x".repeat(2_000_001), HttpStatusCode.OK) }).use { http ->
            assertNull(SiliconFlowContextCatalog(http).read("Qwen/Qwen3.8-27B", "target"))
        }
    }
}
