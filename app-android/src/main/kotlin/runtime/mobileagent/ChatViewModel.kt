// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.AgentRuntime
import runtime.mobileagent.agent.EffectivePrompt
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.feature.chat.ChatLine
import runtime.mobileagent.knowledge.RetrievalBudget
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.provider.openai.OpenAiCompatibleAdapter

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val lines = mutableStateListOf<ChatLine>()
    val input = mutableStateOf("")
    val streaming = mutableStateOf(false)
    val status = mutableStateOf("Save a Provider key, then send a message. Images in the knowledge base wait until a Vision model is configured.")
    private var runJob: Job? = null

    fun send() {
        val text = input.value.trim()
        if (text.isBlank() || streaming.value) return
        val binding = app.container.profiles.chatBinding()
        if (binding == null) {
            status.value = "Configure a Provider and chat model first."
            return
        }
        val (provider, model) = binding
        if (model.providerId != provider.id) {
            status.value = "Chat model is not bound to the selected provider."
            return
        }
        input.value = ""
        lines.add(ChatLine("You", text))
        val assistantIndex = lines.size
        lines.add(ChatLine("Assistant", ""))
        streaming.value = true
        status.value = "Streaming from ${provider.baseUrl} (${model.modelId}). Nothing is sent to this project's servers."
        val run = AgentRun(
            runId = EntityId.random().value,
            snapshotId = "live",
            conversationId = "default",
        )
        val result = app.container.knowledge.retrieve(run.runId, text)
        val hits = RetrievalBudget.clip(result.hits)
        val citations = runtime.mobileagent.knowledge.CitationMap.bind(run.runId, hits)
        val retrieved = hits.mapIndexed { i, hit ->
            val id = citations.getOrNull(i)?.citationId ?: i.toString()
            "[citation:$id ${hit.chunkId}] ${hit.text}"
        }
        if (result.warnings.isNotEmpty()) {
            status.value += " " + result.warnings.joinToString(" ")
        }
        if (app.container.knowledge.waitingForVisionCount() > 0) {
            status.value += " Some imported images are waiting for a Vision model and were not added to context."
        }
        val prompt = EffectivePrompt(
            runtimeContract = "You are a local Android agent runtime. Cite retrieved snippets by citation id. Unknown citation ids are invalid. Do not claim images were processed if they were not.",
            userSystemPrompt = "",
            skillInstructions = emptyList(),
            retrieved = retrieved,
            history = lines.dropLast(2).takeLast(12).map { line ->
                val role = if (line.role == "You") "user" else "assistant"
                role to line.text
            },
            currentUser = text,
        )
        val adapter = OpenAiCompatibleAdapter(app.container.http, provider.baseUrl)
        val runtime = AgentRuntime(adapter)
        runJob = viewModelScope.launch {
            var secret: CharArray? = null
            try {
                secret = app.container.secrets.resolveForHost(provider.secretRef)
                runtime.run(run, prompt, model.modelId, secret, toolsEnabled = false)
                    .flowOn(Dispatchers.IO)
                    .catch { e ->
                        val msg = SecretRedactor.redact(e.message ?: ErrorCode.NETWORK_UNAVAILABLE.name)
                        appendAssistant(assistantIndex, "\n[$msg]")
                    }
                    .collect { event ->
                        when (event) {
                            is ModelEvent.TextDelta -> appendAssistant(assistantIndex, event.text)
                            is ModelEvent.Failed -> {
                                val sanitized = SecretRedactor.redact(event.sanitizedMessage)
                                appendAssistant(assistantIndex, "\n[$sanitized]")
                                status.value = sanitized
                            }
                            ModelEvent.Completed -> status.value = "Completed locally-orchestrated request."
                            is ModelEvent.Usage -> status.value = "Tokens in ${event.inputTokens} / out ${event.outputTokens}"
                            is ModelEvent.ToolCallDelta -> Unit
                        }
                    }
            } catch (e: AppException) {
                status.value = e.error.userMessage
                appendAssistant(assistantIndex, "\n[${e.error.code}]")
            } catch (e: kotlinx.coroutines.CancellationException) {
                status.value = "Cancelled. The partial answer is not a successful completion."
                throw e
            } catch (e: Exception) {
                status.value = SecretRedactor.redact(e.message ?: "request failed")
            } finally {
                secret?.fill('\u0000')
                streaming.value = false
            }
        }
    }

    fun cancel() {
        runJob?.cancel()
        streaming.value = false
        status.value = "Cancelled."
    }

    private fun appendAssistant(index: Int, delta: String) {
        if (index in lines.indices) {
            val current = lines[index]
            lines[index] = current.copy(text = current.text + delta)
        }
    }
}
