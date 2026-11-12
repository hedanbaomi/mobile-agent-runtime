// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.catalog

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.CancellationException
import runtime.mobileagent.provider.ContextWindowMetadata

/** Reads SiliconFlow's public, server-rendered model catalog without provider credentials. */
public class SiliconFlowContextCatalog(private val http: HttpClient) {
    public suspend fun read(modelId: String, target: String): ContextWindowMetadata? {
        val body = http.get(CATALOG_URL).let { response ->
            if (response.status.value != 200) return null
            val channel = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > MAX_CATALOG_BYTES) {
                    channel.cancel(CancellationException("catalog response too large"))
                    return null
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
        val value = parse(body, modelId) ?: return null
        return ContextWindowMetadata(value, target, Instant.now().toString(), CATALOG_URL)
    }

    public companion object {
        public const val CATALOG_URL: String = "https://cloud-rd.siliconflow.cn/models"
        private const val MAX_CATALOG_BYTES = 2_000_000
        private const val MAX_CATALOG_CHARS = 2_000_000
        private val WINDOW = Regex("\\\"contextLen\\\"\\s*:\\s*(\\d{1,9})")
        private val NEXT_MODEL = Regex("\\\"modelName\\\"\\s*:")

        public fun eligible(baseUrl: String, modelId: String): Boolean =
            baseUrl.trimEnd('/') == "https://api.siliconflow.cn/v1" && modelId.isNotBlank()

        internal fun parse(html: String, modelId: String): Int? {
            if (html.length > MAX_CATALOG_CHARS || modelId.length !in 1..200) return null
            // Next.js serializes the official model records inside escaped RSC data.
            // Match an exact model id and the first contextLen in that same record.
            val decoded = html.replace("\\\"", "\"")
            val marker = "\"modelName\":\"$modelId\""
            val start = decoded.indexOf(marker)
            if (start < 0 || decoded.indexOf(marker, start + marker.length) >= 0) return null
            val record = decoded.substring(start + marker.length, minOf(decoded.length, start + 3000))
            val match = WINDOW.find(record) ?: return null
            if (NEXT_MODEL.find(record)?.range?.first?.let { it < match.range.first } == true) return null
            return match.groupValues[1].toIntOrNull()?.takeIf { it in 1024..2_000_000 }
        }
    }
}
