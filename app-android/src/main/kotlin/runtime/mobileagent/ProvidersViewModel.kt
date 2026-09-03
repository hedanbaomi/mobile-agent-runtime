// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.withEndpoint
import runtime.mobileagent.diagnostics.DiagnosticHttpClass
import runtime.mobileagent.diagnostics.DiagnosticProviderResultCode
import runtime.mobileagent.diagnostics.ProviderCapabilityProbeRecord
import runtime.mobileagent.diagnostics.ProviderModelSavePhase
import runtime.mobileagent.diagnostics.ProviderConnectionTestRecord
import runtime.mobileagent.feature.providers.parsePositiveProviderBudget
import runtime.mobileagent.feature.providers.ConnectionCheckUi
import runtime.mobileagent.feature.providers.ProbeCheckUi
import runtime.mobileagent.feature.providers.ProbeOperation
import runtime.mobileagent.feature.providers.ProbePhase
import runtime.mobileagent.feature.providers.ProviderProbeUiState
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.CapabilityProbeStatus
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.openai.OpenAiAdapterFactory
import runtime.mobileagent.provider.ProviderConnectionErrorCode
import runtime.mobileagent.provider.ProviderConnectionResult
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.provider.SecretRedactor
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

data class ProviderDraft(
    val providerId: String? = null,
    val modelProfileId: String? = null,
    val name: String,
    val baseUrl: String,
    val apiFormat: String = ApiFormat.OPENAI_COMPATIBLE.name,
    val modelId: String,
    val apiKey: String = "",
    val role: ModelRole = ModelRole.CHAT,
    val capabilities: Set<String> = setOf("stream"),
    val parametersJson: String = "{}",
    // These remain strings for the lifetime of the editable draft. Empty or
    // malformed input must reach save validation instead of falling back to a
    // previous persisted value.
    val contextLimit: String = "32768",
    val outputLimit: String = "4096",
)

fun interface ProviderAdapterFactory {
    fun create(provider: ProviderProfile): runtime.mobileagent.provider.ModelAdapter
}

class ProvidersViewModel @JvmOverloads constructor(
    application: Application,
    private val adapterFactory: ProviderAdapterFactory? = null,
) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val providers = mutableStateListOf<ProviderProfile>()
    val models = mutableStateListOf<ModelProfile>()
    val status = mutableStateOf("")
    val busy = mutableStateOf(false)
    /** Typed operation state; UI must not derive completion from [busy]. */
    val probeState = mutableStateOf(ProviderProbeUiState())
    /** Invalidates an in-flight result when the user changes provider/model. */
    private var probeGeneration = 0L
    /** Cancels a superseded network operation instead of merely hiding its UI result. */
    private var probeJob: Job? = null

    init { reload() }

    fun reload() {
        providers.clear()
        providers.addAll(app.container.profiles.listProviders())
        models.clear()
        models.addAll(app.container.profiles.listModels())
        val target = probeState.value
        if (target.phase != ProbePhase.IDLE && target.modelId != null && models.none { it.id == target.modelId }) {
            clearProbe()
        }
    }

    fun save(name: String, baseUrl: String, modelId: String, apiKey: String, vision: Boolean, tools: Boolean = false): Boolean =
        saveDraft(ProviderDraft(
            name = name, baseUrl = baseUrl, modelId = modelId, apiKey = apiKey,
            capabilities = buildSet { add("stream"); if (vision) add("image"); if (tools) add("tools") },
        ))

    fun saveDraft(draft: ProviderDraft): Boolean {
        if (busy.value) return false
        runCatching {
            app.diagnostics.recordProviderModelSave(
                ProviderModelSavePhase.START,
                draft.capabilities,
                draft.role.name,
            )
        }
        return try {
            require(draft.name.isNotBlank()) { "请填写名称。" }
            if (draft.modelId.isNotBlank() || draft.modelProfileId != null) {
                require(draft.modelId.isNotBlank()) { "请填写模型 ID。" }
            }
            val endpoint = URI(draft.baseUrl.trim().trimEnd('/'))
            require(endpoint.host != null && endpoint.rawUserInfo == null && endpoint.rawFragment == null && endpoint.rawQuery == null) { "服务地址必须是有效的 Base URL，不能含凭据、查询或片段。" }
            val debugLocal = BuildConfig.DEBUG && endpoint.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")
            require(endpoint.scheme == "https" || (debugLocal && endpoint.scheme == "http")) { "服务地址必须使用 HTTPS。Debug 仅允许本机测试 HTTP。" }
            val apiFormat = runCatching { ApiFormat.valueOf(draft.apiFormat.trim()) }
                .getOrElse { throw IllegalArgumentException("API 格式不受支持。") }
            val previous = draft.providerId?.let { app.container.profiles.getProvider(it) }
            require(draft.providerId == null || previous != null) { "服务已被删除，请重新打开表单。" }
            require(previous != null || draft.apiKey.isNotBlank()) { "新服务需要 API Key；密钥只会以 Keystore 密文保存。" }
            require(previous == null || previous.baseUrl == endpoint.toASCIIString() || draft.apiKey.isNotBlank()) {
                "服务目标发生变化，请重新填写该目标的 API Key；不会把旧目标的密钥转发到新地址。"
            }
            val providerId = previous?.id ?: EntityId.random().value
            val saveModel = draft.modelId.isNotBlank() || draft.modelProfileId != null
            var parameters: JsonObject = JsonObject(emptyMap())
            var modelPrevious: ModelProfile? = null
            var contextLimit = 0
            var outputLimit = 0
            if (saveModel) {
                require(draft.modelId.isNotBlank()) { "请填写模型 ID。" }
                contextLimit = parsePositiveProviderBudget(draft.contextLimit)
                    ?: error("上下文预算必须是正整数。")
                outputLimit = parsePositiveProviderBudget(draft.outputLimit)
                    ?: error("输出预算必须是正整数。")
                require(outputLimit <= contextLimit) { "输出预算不能超过上下文预算。" }
                val parsed = Json.parseToJsonElement(draft.parametersJson)
                require(parsed is JsonObject) { "模型参数必须是 JSON 对象。" }
                rejectReserved(parsed)
                parameters = parsed
                modelPrevious = draft.modelProfileId?.let { app.container.profiles.getModel(it) }
                require(modelPrevious == null || modelPrevious.providerId == providerId) { "模型不属于当前服务。" }
            }
            val provider = ProviderProfile(
                id = providerId, name = draft.name.trim(), apiFormat = apiFormat,
                baseUrl = endpoint.toASCIIString(), secretRef = if (draft.apiKey.isNotBlank()) "provider:$providerId:${EntityId.random().value}" else previous!!.secretRef,
                headerSecretRefs = previous?.headerSecretRefs.orEmpty(), nonSecretHeaders = previous?.nonSecretHeaders.orEmpty(),
                revision = (previous?.revision ?: 0) + 1,
            )
            val model = if (saveModel) ModelProfile(
                id = modelPrevious?.id ?: EntityId.random().value, providerId = providerId,
                role = draft.role, modelId = draft.modelId.trim(), capabilities = draft.capabilities,
                parameterSchemaJson = modelPrevious?.parameterSchemaJson ?: "{}",
                parametersJson = parameters.toString(), contextLimit = contextLimit, outputLimit = outputLimit,
                revision = (modelPrevious?.revision ?: 0) + 1,
            ).withEndpoint() else null
            app.container.db.transaction {
                if (draft.apiKey.isNotBlank()) app.container.secrets.put(provider.secretRef, draft.apiKey.toCharArray())
                app.container.profiles.upsertProvider(provider)
                if (model != null) app.container.profiles.upsertModel(model)
            }
            // A replacement key gets a fresh reference. Retire the previous
            // reference only after the provider row points at the new one;
            // SecretInventory also considers shared/header and immutable-snapshot
            // references before retiring, so this cannot invalidate a shared key.
            val oldSecretCleanupFailed = previous?.secretRef
                ?.takeIf { it.isNotBlank() && it != provider.secretRef }
                ?.let { oldRef ->
                    runCatching { app.container.secrets.inventory().retireIfUnreferenced(oldRef) }.isFailure
                } == true
            reload()
            status.value = if (draft.modelId.isBlank()) {
                "已保存 ${provider.name}。" + if (oldSecretCleanupFailed) "旧密钥仍保留，引用检查失败；请修复存储后重试回收。" else ""
            } else {
                "已保存 ${provider.name} / ${draft.modelId.trim()}。能力标记来自手动配置，尚未发送探测请求。" +
                    if (oldSecretCleanupFailed) "旧密钥仍保留，引用检查失败；请修复存储后重试回收。" else ""
            }
            runCatching {
                app.diagnostics.recordProviderModelSave(
                    ProviderModelSavePhase.SUCCESS,
                    draft.capabilities,
                    draft.role.name,
                )
            }
            true
        } catch (error: Exception) {
            runCatching {
                app.diagnostics.recordProviderModelSave(
                    ProviderModelSavePhase.FAILURE,
                    draft.capabilities,
                    draft.role.name,
                    error,
                )
            }
            status.value = SecretRedactor.redact(error.message ?: "保存失败。", listOf(draft.apiKey).filter { it.isNotBlank() })
            false
        }
    }

    /** Record only the anonymous capability changed by the editor; no provider identity is kept. */
    fun recordCapabilityToggle(capability: String, enabled: Boolean) {
        if (capability !in setOf("image", "tools")) return
        runCatching { app.diagnostics.recordCapabilityToggle(capability, enabled) }
    }

    fun deleteModel(id: String) {
        try {
            val deleted = app.container.profiles.deleteModel(id)
            status.value = if (deleted) "模型配置已删除。" else "此模型仍被 Agent 或会话快照使用，不能删除。"
            reload()
        } catch (error: Exception) { status.value = SecretRedactor.redact(error.message ?: "删除失败。") }
    }

    fun deletePreview(id: String) = app.container.profiles.providerDeletePreview(id)

    fun deleteProvider(id: String) {
        try {
            val deleted = app.container.profiles.deleteProvider(id)
            status.value = if (deleted) "服务配置已删除。" else "请先移除未被引用的模型；被 Agent 或快照引用的配置不能删除。"
            reload()
        } catch (error: Exception) { status.value = SecretRedactor.redact(error.message ?: "删除失败。") }
    }

    /** Requires a separate UI confirmation because even a minimal chat may be billed. */
    fun testConnection(modelId: String, approved: Boolean) {
        if (!approved) return
        probeJob?.cancel()
        probeJob = null
        val generation = ++probeGeneration
        val model = app.container.profiles.getModel(modelId)
        val provider = model?.let { app.container.profiles.getProvider(it.providerId) }
        if (model == null || provider == null) {
            probeState.value = probeState.value.copy(
                phase = ProbePhase.FAILURE,
                operation = ProbeOperation.CONNECTION,
                providerId = provider?.id,
                modelId = modelId,
                connection = ConnectionCheckUi(success = false, error = ProviderConnectionErrorCode.CONFIG_INVALID),
                error = ProviderConnectionErrorCode.CONFIG_INVALID,
                checks = emptyList(),
                charged = false,
                latencyMs = null,
                lastChecked = null,
            )
            return
        }
        val operationId = EntityId.random().value
        val started = System.nanoTime()
        recordConnectionStarted(provider, model)
        probeState.value = probeState.value.copy(
            phase = ProbePhase.RUNNING,
            operation = ProbeOperation.CONNECTION,
            providerId = provider.id,
            modelId = model.id,
            connection = null,
            error = null,
            checks = emptyList(),
            charged = false,
            latencyMs = null,
            lastChecked = null,
        )
        busy.value = true
        probeJob = viewModelScope.launch {
            var secret: CharArray? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    secret = app.container.secrets.resolveForHost(provider.secretRef)
                    adapterFor(provider).testConnection(model, secret!!, operationId)
                }
                recordConnectionCompleted(provider, model, result, elapsedMillis(started))
                if (generation == probeGeneration) {
                    applyConnectionResult(result)
                    status.value = connectionStatus(result)
                }
            } catch (error: runtime.mobileagent.domain.AppException) {
                val failureCode = if (error.error.code == runtime.mobileagent.domain.ErrorCode.SECRET_UNAVAILABLE) {
                    ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE
                } else {
                    ProviderConnectionErrorCode.UNKNOWN
                }
                val result = ProviderConnectionResult.Failure(
                    code = failureCode,
                    retryable = false,
                    charged = false,
                )
                recordConnectionCompleted(provider, model, result, elapsedMillis(started))
                if (generation == probeGeneration) {
                    applyConnectionResult(result)
                    status.value = connectionStatus(result)
                }
            } catch (cancel: CancellationException) {
                recordConnectionCompleted(
                    provider,
                    model,
                    ProviderConnectionResult.Failure(
                        code = ProviderConnectionErrorCode.UNKNOWN,
                        retryable = false,
                        charged = true,
                    ),
                    elapsedMillis(started),
                )
                throw cancel
            } catch (_: Exception) {
                val result = ProviderConnectionResult.Failure(
                    code = ProviderConnectionErrorCode.UNKNOWN,
                    retryable = false,
                    charged = true,
                )
                recordConnectionCompleted(provider, model, result, elapsedMillis(started))
                if (generation == probeGeneration) {
                    applyConnectionResult(result)
                    status.value = connectionStatus(result)
                }
            } finally {
                secret?.fill('\u0000')
                if (generation == probeGeneration) {
                    busy.value = false
                    probeJob = null
                }
            }
        }
    }

    /** Requires a separate UI confirmation because capability checks may be billed. */
    fun probe(modelId: String, approved: Boolean) {
        if (!approved) return
        probeJob?.cancel()
        probeJob = null
        val generation = ++probeGeneration
        val model = app.container.profiles.getModel(modelId)
        val provider = model?.let { app.container.profiles.getProvider(it.providerId) }
        if (model == null || provider == null) {
            probeState.value = probeState.value.copy(
                phase = ProbePhase.FAILURE,
                operation = ProbeOperation.CAPABILITY,
                providerId = provider?.id,
                modelId = modelId,
                error = ProviderConnectionErrorCode.CONFIG_INVALID,
                connection = null,
                checks = emptyList(),
                charged = false,
                latencyMs = null,
                lastChecked = null,
            )
            return
        }
        val operationId = EntityId.random().value
        val started = System.nanoTime()
        recordCapabilityProbeStarted(provider, model)
        val previous = probeState.value
        val retainedConnection = previous.connection.takeIf {
            previous.providerId == provider.id && previous.modelId == model.id
        }
        probeState.value = probeState.value.copy(
            phase = ProbePhase.RUNNING,
            operation = ProbeOperation.CAPABILITY,
            providerId = provider.id,
            modelId = model.id,
            // Keep a successful/failed connection result visible while the
            // independent capability operation is in flight.
            connection = retainedConnection,
            error = null,
            checks = emptyList(),
            charged = false,
            latencyMs = null,
            lastChecked = null,
        )
        busy.value = true
        probeJob = viewModelScope.launch {
            var secret: CharArray? = null
            try {
                val report = withContext(Dispatchers.IO) {
                    secret = app.container.secrets.resolveForHost(provider.secretRef)
                    adapterFor(provider).probe(
                        model,
                        secret!!,
                        runtime.mobileagent.provider.ProbeConsent.GRANTED,
                        operationId,
                    )
                }
                val checks = report.checks.map { check ->
                    ProbeCheckUi(check.capability, check.status, check.httpStatus)
                }
                val phase = when (report.status) {
                    CapabilityProbeStatus.SUCCEEDED -> ProbePhase.SUCCESS
                    CapabilityProbeStatus.PARTIAL -> ProbePhase.PARTIAL
                    CapabilityProbeStatus.FAILED,
                    CapabilityProbeStatus.PROFILE_ONLY,
                    -> ProbePhase.FAILURE
                }
                recordCapabilityProbeCompleted(provider, model, report, checks, elapsedMillis(started))
                val tools = checks.firstOrNull { it.capability == runtime.mobileagent.provider.CapabilityCheck.TOOLS }
                    ?.status?.toProbeSummary() ?: "unknown"
                val images = checks.firstOrNull { it.capability == runtime.mobileagent.provider.CapabilityCheck.IMAGE }
                    ?.status?.toProbeSummary() ?: "unknown"
                val persisted = runCatching {
                    app.container.profiles.recordProbe(
                        model.id,
                        provider.revision,
                        tools,
                        images,
                        report.source,
                        report.status != CapabilityProbeStatus.PROFILE_ONLY,
                    )
                }
                if (generation == probeGeneration) {
                    probeState.value = probeState.value.copy(
                        phase = phase,
                        operation = ProbeOperation.CAPABILITY,
                        checks = checks,
                        error = probeFailureCode(checks).takeIf { phase == ProbePhase.FAILURE },
                        charged = report.charged,
                        latencyMs = null,
                        lastChecked = report.probedAt,
                    )
                    status.value = capabilityStatus(report.status, report.charged)
                    persisted.onFailure {
                        // The network result remains authoritative; a local
                        // audit write failure must not turn a successful probe
                        // into a connection failure in the UI.
                        status.value = "能力探测完成，但验证记录未能保存。"
                    }
                    reload()
                }
            } catch (error: runtime.mobileagent.domain.AppException) {
                val failureCode = if (error.error.code == runtime.mobileagent.domain.ErrorCode.SECRET_UNAVAILABLE) {
                    ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE
                } else {
                    ProviderConnectionErrorCode.UNKNOWN
                }
                recordCapabilityProbeCompleted(
                    provider,
                    model,
                    status = CapabilityProbeStatus.FAILED,
                    checks = emptyList(),
                    durationMs = elapsedMillis(started),
                    error = failureCode,
                )
                if (generation == probeGeneration) {
                    probeState.value = probeState.value.copy(
                        phase = ProbePhase.FAILURE,
                        operation = ProbeOperation.CAPABILITY,
                        error = failureCode,
                        charged = false,
                        latencyMs = null,
                        lastChecked = null,
                    )
                    status.value = "能力探测失败。"
                }
            } catch (cancel: CancellationException) {
                recordCapabilityProbeCompleted(
                    provider,
                    model,
                    status = CapabilityProbeStatus.FAILED,
                    checks = emptyList(),
                    durationMs = elapsedMillis(started),
                    error = ProviderConnectionErrorCode.UNKNOWN,
                )
                throw cancel
            } catch (_: Exception) {
                recordCapabilityProbeCompleted(
                    provider,
                    model,
                    status = CapabilityProbeStatus.FAILED,
                    checks = emptyList(),
                    durationMs = elapsedMillis(started),
                    error = ProviderConnectionErrorCode.UNKNOWN,
                )
                if (generation == probeGeneration) {
                    probeState.value = probeState.value.copy(
                        phase = ProbePhase.FAILURE,
                        operation = ProbeOperation.CAPABILITY,
                        error = ProviderConnectionErrorCode.UNKNOWN,
                        charged = true,
                        latencyMs = null,
                        lastChecked = null,
                    )
                    status.value = "能力探测失败。"
                }
            } finally {
                secret?.fill('\u0000')
                if (generation == probeGeneration) {
                    busy.value = false
                    probeJob = null
                }
            }
        }
    }

    fun clearProbe() {
        probeJob?.cancel()
        probeJob = null
        probeGeneration += 1
        busy.value = false
        probeState.value = ProviderProbeUiState()
    }

    private fun adapterFor(provider: ProviderProfile): runtime.mobileagent.provider.ModelAdapter =
        adapterFactory?.create(provider) ?: createAdapter(provider)

    private fun createAdapter(provider: ProviderProfile): ModelAdapter {
        val headers = linkedMapOf<String, RequestHeaderValue>()
        provider.nonSecretHeaders.forEach { (name, value) -> headers[name] = RequestHeaderValue.Plain(value) }
        provider.headerSecretRefs.forEach { (name, ref) -> headers[name] = RequestHeaderValue.SecretRef(ref) }
        return OpenAiAdapterFactory.create(
            format = provider.apiFormat,
            http = app.container.http,
            baseUrl = provider.baseUrl,
            headerSecretResolver = HeaderSecretResolver { host, ref ->
                require(host.equals(URI(provider.baseUrl).host, true) && ref in provider.headerSecretRefs.values) {
                    "Header secret destination mismatch"
                }
                app.container.secrets.resolveForHost(ref)
            },
            defaultHeaders = headers,
        )
    }

    private fun applyConnectionResult(result: ProviderConnectionResult) {
        val connection = when (result) {
            is ProviderConnectionResult.Success -> ConnectionCheckUi(
                success = true,
                latencyMs = result.latencyMs,
                charged = result.charged,
            )
            is ProviderConnectionResult.Failure -> ConnectionCheckUi(
                success = false,
                error = result.code,
                httpStatus = result.httpStatus,
                retryable = result.retryable,
                charged = result.charged,
            )
        }
        probeState.value = probeState.value.copy(
            phase = if (connection.success) ProbePhase.SUCCESS else ProbePhase.FAILURE,
            operation = ProbeOperation.CONNECTION,
            connection = connection,
            error = connection.error,
            charged = connection.charged,
            latencyMs = connection.latencyMs,
            lastChecked = null,
        )
    }

    private fun recordConnectionStarted(provider: ProviderProfile, model: ModelProfile) {
        runCatching {
            app.diagnostics.recordProviderConnectionTestStarted(
                ProviderConnectionTestRecord(
                    providerId = provider.id,
                    modelId = model.id,
                    resultCode = DiagnosticProviderResultCode.STARTED,
                ),
            )
        }
    }

    private fun recordConnectionCompleted(
        provider: ProviderProfile,
        model: ModelProfile,
        result: ProviderConnectionResult,
        durationMs: Long,
    ) {
        val httpStatus = (result as? ProviderConnectionResult.Failure)?.httpStatus
        runCatching {
            app.diagnostics.recordProviderConnectionTestCompleted(
                ProviderConnectionTestRecord(
                    providerId = provider.id,
                    modelId = model.id,
                    resultCode = result.toDiagnosticResultCode(),
                    httpClass = httpClass(httpStatus ?: if (result is ProviderConnectionResult.Success) 200 else null),
                    durationMs = durationMs,
                ),
            )
        }
    }

    private fun recordCapabilityProbeStarted(provider: ProviderProfile, model: ModelProfile) {
        runCatching {
            app.diagnostics.recordProviderCapabilityProbeStarted(
                ProviderCapabilityProbeRecord(
                    providerId = provider.id,
                    modelId = model.id,
                    resultCode = DiagnosticProviderResultCode.STARTED,
                ),
            )
        }
    }

    private fun recordCapabilityProbeCompleted(
        provider: ProviderProfile,
        model: ModelProfile,
        report: CapabilityReport,
        checks: List<ProbeCheckUi>,
        durationMs: Long,
    ) {
        recordCapabilityProbeCompleted(
            provider = provider,
            model = model,
            status = report.status,
            checks = checks,
            durationMs = durationMs,
            error = probeFailureCode(checks),
        )
    }

    private fun recordCapabilityProbeCompleted(
        provider: ProviderProfile,
        model: ModelProfile,
        status: CapabilityProbeStatus,
        checks: List<ProbeCheckUi>,
        durationMs: Long,
        error: ProviderConnectionErrorCode? = null,
    ) {
        val httpStatus = checks.firstOrNull { it.httpStatus != null }?.httpStatus
        runCatching {
            app.diagnostics.recordProviderCapabilityProbeCompleted(
                ProviderCapabilityProbeRecord(
                    providerId = provider.id,
                    modelId = model.id,
                    resultCode = status.toDiagnosticResultCode(error),
                    httpClass = httpClass(httpStatus),
                    durationMs = durationMs,
                ),
            )
        }
    }

    private fun ProviderConnectionResult.toDiagnosticResultCode(): DiagnosticProviderResultCode = when (this) {
        is ProviderConnectionResult.Success -> DiagnosticProviderResultCode.SUCCESS
        is ProviderConnectionResult.Failure -> when (code) {
            ProviderConnectionErrorCode.NETWORK_UNREACHABLE -> DiagnosticProviderResultCode.NETWORK_UNREACHABLE
            ProviderConnectionErrorCode.TLS_FAILURE -> DiagnosticProviderResultCode.TLS_FAILURE
            ProviderConnectionErrorCode.TIMEOUT -> DiagnosticProviderResultCode.TIMEOUT
            ProviderConnectionErrorCode.AUTH_FAILED -> DiagnosticProviderResultCode.AUTH_FAILED
            ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED,
            ProviderConnectionErrorCode.MODEL_NOT_FOUND -> DiagnosticProviderResultCode.MODEL_NOT_FOUND
            ProviderConnectionErrorCode.RATE_LIMITED -> DiagnosticProviderResultCode.RATE_LIMITED
            ProviderConnectionErrorCode.FEATURE_UNSUPPORTED,
            ProviderConnectionErrorCode.PROVIDER_REJECTED -> DiagnosticProviderResultCode.PROVIDER_REJECTED
            ProviderConnectionErrorCode.INVALID_RESPONSE -> DiagnosticProviderResultCode.INVALID_RESPONSE
            ProviderConnectionErrorCode.CONFIG_INVALID,
            ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE,
            -> DiagnosticProviderResultCode.CONFIG_INVALID
            ProviderConnectionErrorCode.UNKNOWN -> DiagnosticProviderResultCode.UNKNOWN
        }
    }

    private fun CapabilityProbeStatus.toDiagnosticResultCode(
        error: ProviderConnectionErrorCode?,
    ): DiagnosticProviderResultCode = error?.let { failure ->
        when (failure) {
            ProviderConnectionErrorCode.NETWORK_UNREACHABLE -> DiagnosticProviderResultCode.NETWORK_UNREACHABLE
            ProviderConnectionErrorCode.TLS_FAILURE -> DiagnosticProviderResultCode.TLS_FAILURE
            ProviderConnectionErrorCode.TIMEOUT -> DiagnosticProviderResultCode.TIMEOUT
            ProviderConnectionErrorCode.AUTH_FAILED -> DiagnosticProviderResultCode.AUTH_FAILED
            ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED,
            ProviderConnectionErrorCode.MODEL_NOT_FOUND -> DiagnosticProviderResultCode.MODEL_NOT_FOUND
            ProviderConnectionErrorCode.RATE_LIMITED -> DiagnosticProviderResultCode.RATE_LIMITED
            ProviderConnectionErrorCode.FEATURE_UNSUPPORTED,
            ProviderConnectionErrorCode.PROVIDER_REJECTED -> DiagnosticProviderResultCode.PROVIDER_REJECTED
            ProviderConnectionErrorCode.INVALID_RESPONSE -> DiagnosticProviderResultCode.INVALID_RESPONSE
            ProviderConnectionErrorCode.CONFIG_INVALID,
            ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE,
            -> DiagnosticProviderResultCode.CONFIG_INVALID
            ProviderConnectionErrorCode.UNKNOWN -> DiagnosticProviderResultCode.UNKNOWN
        }
    } ?: when (this) {
        CapabilityProbeStatus.SUCCEEDED -> DiagnosticProviderResultCode.SUCCEEDED
        CapabilityProbeStatus.PARTIAL -> DiagnosticProviderResultCode.PARTIAL
        CapabilityProbeStatus.FAILED,
        CapabilityProbeStatus.PROFILE_ONLY,
        -> DiagnosticProviderResultCode.FAILED
    }

    private fun httpClass(status: Int?): DiagnosticHttpClass = when (status) {
        null -> DiagnosticHttpClass.NONE
        in 200..299 -> DiagnosticHttpClass.TWO_HUNDRED
        in 400..499 -> DiagnosticHttpClass.FOUR_HUNDRED
        in 500..599 -> DiagnosticHttpClass.FIVE_HUNDRED
        else -> DiagnosticHttpClass.UNKNOWN
    }

    private fun elapsedMillis(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    private fun connectionStatus(result: ProviderConnectionResult): String = when (result) {
        is ProviderConnectionResult.Success -> "连接成功。"
        is ProviderConnectionResult.Failure -> "连接失败：${connectionErrorStatusLabel(result.code)}。"
    }

    private fun capabilityStatus(result: CapabilityProbeStatus, charged: Boolean): String =
        when (result) {
            CapabilityProbeStatus.SUCCEEDED -> "能力探测完成。"
            CapabilityProbeStatus.PARTIAL -> "能力探测完成，部分能力不可用。"
            CapabilityProbeStatus.FAILED -> "能力探测失败。"
            CapabilityProbeStatus.PROFILE_ONLY -> "仅记录配置声明，未完成真实验证。"
        } + if (charged) "可能产生服务商费用。" else "未发送可能计费请求。"

    private fun probeFailureCode(checks: List<ProbeCheckUi>): ProviderConnectionErrorCode? {
        val failed = checks.firstOrNull {
            it.status == CapabilityCheckStatus.FAILED || it.status == CapabilityCheckStatus.UNKNOWN
        } ?: return null
        return failed.httpStatus?.let(::connectionErrorForHttp)
            ?: when (failed.status) {
                CapabilityCheckStatus.FAILED -> ProviderConnectionErrorCode.INVALID_RESPONSE
                CapabilityCheckStatus.UNKNOWN -> ProviderConnectionErrorCode.UNKNOWN
                else -> null
            }
    }

    private fun connectionErrorStatusLabel(code: ProviderConnectionErrorCode): String = when (code) {
        ProviderConnectionErrorCode.NETWORK_UNREACHABLE -> "网络不可达"
        ProviderConnectionErrorCode.TLS_FAILURE -> "安全连接失败"
        ProviderConnectionErrorCode.TIMEOUT -> "请求超时"
        ProviderConnectionErrorCode.AUTH_FAILED -> "认证失败"
        ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED -> "Responses 端点不支持"
        ProviderConnectionErrorCode.MODEL_NOT_FOUND -> "模型不存在"
        ProviderConnectionErrorCode.RATE_LIMITED -> "请求受限"
        ProviderConnectionErrorCode.FEATURE_UNSUPPORTED -> "请求能力不支持"
        ProviderConnectionErrorCode.PROVIDER_REJECTED -> "服务商拒绝请求"
        ProviderConnectionErrorCode.INVALID_RESPONSE -> "响应无效"
        ProviderConnectionErrorCode.CONFIG_INVALID -> "配置无效"
        ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE -> "凭据不可用"
        ProviderConnectionErrorCode.UNKNOWN -> "未知错误"
    }

    private fun connectionErrorForHttp(status: Int): ProviderConnectionErrorCode = when (status) {
        401, 403 -> ProviderConnectionErrorCode.AUTH_FAILED
        404 -> ProviderConnectionErrorCode.MODEL_NOT_FOUND
        408 -> ProviderConnectionErrorCode.TIMEOUT
        429 -> ProviderConnectionErrorCode.RATE_LIMITED
        in 400..599 -> ProviderConnectionErrorCode.PROVIDER_REJECTED
        else -> ProviderConnectionErrorCode.UNKNOWN
    }

    private fun CapabilityCheckStatus.toProbeSummary(): String = when (this) {
        CapabilityCheckStatus.VERIFIED -> "verified"
        CapabilityCheckStatus.UNSUPPORTED -> "unsupported"
        CapabilityCheckStatus.NOT_DECLARED -> "not-declared"
        CapabilityCheckStatus.NOT_RUN -> "not-run"
        CapabilityCheckStatus.FAILED -> "failed"
        CapabilityCheckStatus.UNKNOWN -> "unknown"
    }

    private fun rejectReserved(objectValue: JsonObject) {
        objectValue.forEach { (key, value) ->
            require(key.lowercase() !in setOf("model", "messages", "input", "instructions", "tools", "stream", "authorization", "api_key", "headers")) { "参数 $key 由运行时控制，不能覆盖。" }
            if (value is JsonObject) rejectReserved(value)
        }
    }

}
