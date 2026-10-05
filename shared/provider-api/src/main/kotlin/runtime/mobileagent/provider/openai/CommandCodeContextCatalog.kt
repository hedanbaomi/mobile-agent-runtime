// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.contextWindowTarget
import runtime.mobileagent.provider.ContextWindowMetadata

/** Opt-in public catalog documented at https://commandcode.ai/docs/provider. */
class CommandCodeContextCatalog(private val http: HttpClient) {
    suspend fun read(baseUrl: String, profile: ModelProfile): ContextWindowMetadata? {
        if (!eligible(baseUrl, profile.modelId)) return null
        return withTimeout(10_000L) {
            // No Provider credentials or custom headers are resolved for this public GET.
            http.prepareGet(CATALOG_URL).execute { response ->
                if (response.status.value != 200 || response.call.request.url.toString() != CATALOG_URL) return@execute null
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val channel = response.bodyAsChannel()
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count == -1) break
                    if (output.size() + count > MAX_BYTES) {
                        channel.cancel(null)
                        return@execute null
                    }
                    output.write(buffer, 0, count)
                }
                val rows = (Json.parseToJsonElement(output.toString("UTF-8")) as? JsonObject)
                    ?.get("data") as? JsonArray ?: return@execute null
                val matches = rows.filter { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == profile.modelId }
                val row = matches.singleOrNull() as? JsonObject ?: return@execute null
                val field = row["context_length"] as? JsonPrimitive ?: return@execute null
                if (field.isString) return@execute null
                val value = field.intOrNull?.takeIf { it > 0 } ?: return@execute null
                ContextWindowMetadata(value, contextWindowTarget(profile.providerId, baseUrl, profile.modelId),
                    java.time.Instant.now().toString(), CATALOG_URL)
            }
        }
    }

    companion object {
        const val CATALOG_URL = "https://api.commandcode.ai/provider/v1/models"
        private const val MAX_BYTES = 2_000_000
        fun eligible(baseUrl: String, modelId: String): Boolean {
            val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
            return modelId.isNotBlank() && uri.scheme.equals("https", true) &&
                uri.host.equals("api.commandcode.ai", true) && uri.port in setOf(-1, 443) &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.trimEnd('/') == "/provider/v1"
        }
    }
}
