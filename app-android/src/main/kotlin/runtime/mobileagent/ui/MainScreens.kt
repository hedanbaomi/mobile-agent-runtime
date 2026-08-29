// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import runtime.mobileagent.AgentsViewModel
import runtime.mobileagent.ChatViewModel
import runtime.mobileagent.KnowledgeViewModel
import runtime.mobileagent.McpViewModel
import runtime.mobileagent.ProviderDraft as RuntimeProviderDraft
import runtime.mobileagent.ProvidersViewModel
import runtime.mobileagent.SettingsViewModel
import runtime.mobileagent.SkillsViewModel
import runtime.mobileagent.AnnouncementsViewModel
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.feature.agents.AgentsActions
import runtime.mobileagent.feature.announcements.AnnouncementsActions
import runtime.mobileagent.feature.announcements.AnnouncementsUiState
import runtime.mobileagent.feature.chat.ChatActions
import runtime.mobileagent.feature.knowledge.KnowledgeActions
import runtime.mobileagent.feature.providers.ProviderCardUi
import runtime.mobileagent.feature.providers.ProviderDraft as ProviderDraftUi
import runtime.mobileagent.feature.providers.ProviderModelUi
import runtime.mobileagent.feature.providers.ProbeUiState
import runtime.mobileagent.feature.providers.ProvidersActions
import runtime.mobileagent.feature.providers.ProvidersUiState
import runtime.mobileagent.feature.settings.SettingsActions
import runtime.mobileagent.feature.settings.ThirdPartyNoticeAssets
import runtime.mobileagent.feature.settings.ThirdPartyNoticesUiState
import runtime.mobileagent.feature.skills.SkillsActions

/** The stateful host for the seven product destinations. All content comes from ViewModels. */
@Composable
internal fun MainApp() {
    val chatVm: ChatViewModel = viewModel()
    val agentsVm: AgentsViewModel = viewModel()
    val providersVm: ProvidersViewModel = viewModel()
    val knowledgeVm: KnowledgeViewModel = viewModel()
    val skillsVm: SkillsViewModel = viewModel()
    val announcementsVm: AnnouncementsViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    val mcpVm: McpViewModel = viewModel()

    var route by remember { mutableStateOf(AppRoutes.CHAT) }
    var mcpOpen by remember { mutableStateOf(false) }
    var mcpReturnRoute by remember { mutableStateOf(AppRoutes.SETTINGS) }
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    var unsavedDialog by remember { mutableStateOf(false) }

    var providerEditorOpen by remember { mutableStateOf(false) }
    var providerDraft by remember { mutableStateOf(ProviderDraftUi()) }
    var providerSelectedId by remember { mutableStateOf<String?>(null) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var providerProbeModelId by remember { mutableStateOf<String?>(null) }
    var providerProbeMessage by remember { mutableStateOf("") }

    var exportChooserOpen by remember { mutableStateOf(false) }
    var exportAgents by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var thirdPartyNotices by remember { mutableStateOf(ThirdPartyNoticesUiState()) }
    val assetContext = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        settingsVm.importFrom(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        settingsVm.exportTo(uri)
    }

    val announcementRecords = announcementsVm.records.value
    val unreadNoticeCount = announcementRecords.count { it.state.readAt == null }
    val mcpState = mcpVm.state.value
    val rawSettings = settingsVm.uiState(statsEnabled = announcementsVm.statsEnabled.value, noticeCount = unreadNoticeCount)
    val deviceLanguage = LocalConfiguration.current.locales.get(0)?.language.orEmpty()
    val language = effectiveLanguage(rawSettings.language, deviceLanguage)
    val chinese = language == "zh-CN"
    val settingsState = rawSettings.copy(
        language = language,
        themeMode = rawSettings.themeMode.takeUnless { it.equals("system", true) } ?: "66ccff",
        mcpConfigured = mcpState.configured,
        mcpDisabledReason = if (mcpState.configured) {
            if (chinese) "MCP 配置入口可用；当前端点为 ${mcpState.host}，工具发现和实际调用仍需逐次确认。" else "MCP configuration is available; endpoint ${mcpState.host} is saved, while discovery and calls still require explicit confirmation."
        } else {
            if (chinese) "MCP 配置入口可用；尚未配置服务器，保存 HTTPS 端点后才能发现工具。" else "MCP configuration is available; no server is configured. Save an HTTPS endpoint before discovering tools."
        },
        mcpEntryEnabled = true,
        thirdPartyNotices = thirdPartyNotices,
    )
    // Existing installations may still persist SYSTEM. The product's initial visual mode remains 66ccff;
    // the explicit selector exposes only the three supported visual choices.
    val themeMode = if (settingsState.themeMode.equals("system", true)) AppThemeMode.CC66FF else appThemeMode(settingsState.themeMode)

    LaunchedEffect(
        thirdPartyNotices.opened,
        thirdPartyNotices.selectedComponentId,
        thirdPartyNotices.components.size,
        thirdPartyNotices.error,
    ) {
        if (!thirdPartyNotices.opened || thirdPartyNotices.loading || thirdPartyNotices.error != null) return@LaunchedEffect
        if (thirdPartyNotices.components.isEmpty() && thirdPartyNotices.overview.isBlank()) {
            thirdPartyNotices = thirdPartyNotices.copy(loading = true)
            thirdPartyNotices = ThirdPartyNoticeAssets.loadCatalog(assetContext).copy(opened = true)
            return@LaunchedEffect
        }
        val selected = thirdPartyNotices.components.firstOrNull { it.id == thirdPartyNotices.selectedComponentId }
        if (selected != null && thirdPartyNotices.selectedLicenseText == null) {
            thirdPartyNotices = thirdPartyNotices.copy(loading = true)
            val loaded = ThirdPartyNoticeAssets.loadComponentText(assetContext, selected)
            thirdPartyNotices = loaded.fold(
                onSuccess = { text -> thirdPartyNotices.copy(loading = false, selectedLicenseText = text, error = null) },
                onFailure = { failure -> thirdPartyNotices.copy(loading = false, error = thirdPartyNoticeError(failure, chinese)) },
            )
        }
    }

    fun openMcpSettings(returnRoute: String) {
        mcpReturnRoute = returnRoute
        mcpOpen = true
    }

    fun closeMcpSettings() {
        mcpOpen = false
    }

    val runtimeProviders = providersVm.providers.toList()
    val runtimeModels = providersVm.models.toList()
    val providerIds = runtimeProviders.map { it.id }
    val effectiveProviderId = providerSelectedId?.takeIf { it in providerIds } ?: providerIds.firstOrNull()
    LaunchedEffect(effectiveProviderId) {
        if (providerSelectedId != effectiveProviderId) providerSelectedId = effectiveProviderId
    }

    fun hasUnsavedEditor(): Boolean = agentsVm.state.value.editor != null || providerEditorOpen

    fun requestRoute(target: String) {
        if (mcpOpen) {
            mcpOpen = false
            if (target != route) route = target
            return
        }
        if (target == route) return
        if (hasUnsavedEditor()) {
            pendingRoute = target
            unsavedDialog = true
        } else {
            route = target
        }
    }

    fun requestEditorClose() {
        if (hasUnsavedEditor()) {
            pendingRoute = null
            unsavedDialog = true
        }
    }

    fun discardUnsaved() {
        unsavedDialog = false
        agentsVm.closeEditor()
        providerEditorOpen = false
        providerError = null
        val target = pendingRoute
        pendingRoute = null
        if (target != null) route = target
    }

    fun openProviderEditor(providerId: String?, modelId: String? = null) {
        val selectedProvider = providerId ?: effectiveProviderId
        val provider = runtimeProviders.firstOrNull { it.id == selectedProvider }
        val model = runtimeModels.firstOrNull { item ->
            (modelId != null && item.id == modelId) || (modelId == null && item.providerId == selectedProvider)
        }
        providerDraft = providerDraftFrom(provider, model)
        providerError = null
        providerEditorOpen = true
    }

    val providerModels = runtimeModels.filter { it.providerId == effectiveProviderId }
    val providerProbe = when {
        providerProbeModelId == null -> ProbeUiState()
        providerProbeModelId == "__none__" -> ProbeUiState(phase = "error", message = providerProbeMessage)
        providersVm.busy.value -> ProbeUiState(phase = "running", message = providerProbeMessage)
        else -> ProbeUiState(phase = "complete", message = providersVm.status.value)
    }
    val providerState = ProvidersUiState(
        providers = runtimeProviders.map { profile -> providerCardFrom(profile, runtimeModels) },
        selectedProviderId = effectiveProviderId,
        models = providerModels.map(::providerModelFrom),
        draft = providerDraft,
        probe = providerProbe,
        editorOpen = providerEditorOpen,
        loading = providersVm.busy.value,
        error = providerError,
        status = providersVm.status.value,
        language = language,
        mcpReason = if (mcpState.configured) {
            if (chinese) "MCP 配置入口可用；当前端点为 ${mcpState.host}，工具发现和实际调用仍需逐次确认。" else "MCP configuration is available; endpoint ${mcpState.host} is saved, while discovery and calls still require explicit confirmation."
        } else {
            if (chinese) "MCP 配置入口可用；尚未配置服务器，保存 HTTPS 端点后才能发现工具。" else "MCP configuration is available; no server is configured. Save an HTTPS endpoint before discovering tools."
        },
        mcpEntryEnabled = true,
        editorError = providerError,
    )
    val providerActions = ProvidersActions(
        onSelectProvider = { providerSelectedId = it; providerProbeModelId = null },
        onDraftChange = { providerDraft = it },
        onOpenEditor = { openProviderEditor(it) },
        onCloseEditor = ::requestEditorClose,
        onSave = {
            val role = runCatching { ModelRole.valueOf(providerDraft.role.uppercase()) }.getOrDefault(ModelRole.CHAT)
            val currentModel = runtimeModels.firstOrNull { it.id == providerDraft.modelProfileId }
            val capabilities = buildSet {
                add("stream")
                if (providerDraft.vision) add("image")
                if (providerDraft.tools) add("tools")
                currentModel?.capabilities.orEmpty().filter { it !in setOf("image", "tools") }.forEach { add(it) }
            }
            val saved = providersVm.saveDraft(
                RuntimeProviderDraft(
                    providerId = providerDraft.id,
                    modelProfileId = providerDraft.modelProfileId,
                    name = providerDraft.name,
                    baseUrl = providerDraft.baseUrl,
                    modelId = providerDraft.modelId,
                    apiKey = providerDraft.apiKey,
                    role = role,
                    capabilities = capabilities,
                    parametersJson = providerDraft.parametersJson,
                    contextLimit = providerDraft.contextLimit,
                    outputLimit = providerDraft.outputLimit,
                ),
            )
            if (saved) {
                providerError = null
                providerEditorOpen = false
                providerSelectedId = providerDraft.id ?: providersVm.providers.firstOrNull { it.name == providerDraft.name.trim() }?.id
                agentsVm.reload()
                chatVm.reload()
            } else {
                providerError = providersVm.status.value.ifBlank { if (chinese) "保存服务商失败。" else "Provider save failed." }
            }
        },
        onDelete = {
            effectiveProviderId?.let { id ->
                providersVm.deleteProvider(id)
                if (providersVm.providers.none { it.id == id }) providerSelectedId = null
            }
            agentsVm.reload()
            chatVm.reload()
        },
        onEditModel = { modelId ->
            val model = runtimeModels.firstOrNull { it.id == modelId }
            openProviderEditor(model?.providerId, model?.id)
        },
        onDeleteModel = { modelId ->
            providersVm.deleteModel(modelId)
            agentsVm.reload()
            chatVm.reload()
        },
        onProbe = {
            val model = providerModels.firstOrNull()
            if (model == null) {
                providerProbeModelId = "__none__"
                providerProbeMessage = if (chinese) "当前服务商没有可探测的模型元数据。" else "This provider has no model metadata to probe."
            } else {
                providerProbeModelId = model.id
                providerProbeMessage = if (chinese) "正在向已配置端点发送明确批准的探测请求。" else "Sending the explicitly approved probe request to the configured endpoint."
                providersVm.probe(model.id, approved = true)
            }
        },
        onCloseProbe = { providerProbeModelId = null; providerProbeMessage = "" },
        onOpenMcpSettings = { openMcpSettings(AppRoutes.PROVIDERS) },
    )

    val agentsState = agentsVm.state.value.copy(language = language)
    val agentsActions = AgentsActions(
        onQuery = agentsVm::query,
        onSelectAgent = {
            agentsVm.select(it)
            chatVm.selectAgent(it)
        },
        onOpenEditor = agentsVm::openEditor,
        onCloseEditor = ::requestEditorClose,
        onEditorChange = agentsVm::edit,
        onSave = {
            if (agentsVm.save()) {
                mcpVm.reload()
                chatVm.reload()
            }
        },
        onSavePromptRevision = {
            if (agentsVm.save()) {
                mcpVm.reload()
                chatVm.reload()
            }
        },
        onRestorePrompt = agentsVm::restorePrompt,
        onToggleResource = agentsVm::toggleResource,
        onSnapshot = {
            if (agentsVm.createConversation() != null) {
                mcpVm.reload()
                chatVm.reload()
                requestRoute(AppRoutes.CHAT)
            }
        },
    )

    val chatState = chatVm.state.value.copy(language = language)
    val chatActions = ChatActions(
        onInput = chatVm::input,
        onSend = chatVm::send,
        onCancel = chatVm::cancel,
        onToggleDegradation = chatVm::degrade,
        onSelectSession = chatVm::selectSession,
        onNewSession = { chatVm.newSession() },
        onSelectAgent = chatVm::selectAgent,
        onOpenCitation = chatVm::openCitation,
        onCloseCitation = chatVm::closeCitation,
        onToolApproval = { choice -> chatVm.approveTool(choice == runtime.mobileagent.feature.chat.ToolApprovalChoice.APPROVE) },
        onOpenRequestInspector = { chatVm.inspector(true) },
        onCloseRequestInspector = { chatVm.inspector(false) },
    )

    val knowledgeState = knowledgeVm.state.value.copy(language = language)
    val knowledgeActions = KnowledgeActions(
        onImport = knowledgeVm::importUris,
        onSelectBase = knowledgeVm::selectBase,
        onOpenEvidence = knowledgeVm::openEvidence,
        onRebuild = knowledgeVm::rebuild,
        onGrantVision = knowledgeVm::grantVision,
        onRetryVision = knowledgeVm::retryVision,
        onTextOnly = knowledgeVm::textOnly,
        onConfigureVision = { requestRoute(AppRoutes.PROVIDERS) },
        onKeepWaiting = knowledgeVm::keepWaiting,
        onDeleteDocument = knowledgeVm::deleteDocument,
        onCloseEvidence = knowledgeVm::closeEvidence,
        onCreateBase = knowledgeVm::createBase,
        onDeleteBase = knowledgeVm::deleteBase,
        onCancelJob = knowledgeVm::cancelJob,
        onConfigureEmbedding = knowledgeVm::configureEmbedding,
        onGrantEmbedding = knowledgeVm::grantEmbedding,
        onRetryEmbedding = knowledgeVm::retryEmbedding,
        onAuthorizeQueryRetry = { spaceId, queryHash -> knowledgeVm.requestQueryRetry(spaceId, queryHash) },
    )

    val skillsState = skillsVm.state.value.copy(language = language)
    val permissionRequest = skillsVm.permissionRequest.value
    val permissionDetail = skillsState.detail
    val permissionCapability = permissionRequest?.second.orEmpty()
    val permissionScope = permissionDetail?.permissions?.firstOrNull { it.capability == permissionCapability }?.scope.orEmpty()
    val knowledgeScope = permissionCapability in setOf("knowledge.search", "knowledge.read", "document.read")
    val skillsActions = SkillsActions(
        onImport = skillsVm::importUris,
        onQuery = skillsVm::query,
        onFilter = skillsVm::filter,
        onOpenDetail = skillsVm::openDetail,
        onCloseDetail = skillsVm::closeDetail,
        onToggle = skillsVm::toggle,
        onGrantPermission = skillsVm::beginGrant,
        onRevokePermission = skillsVm::revokePermission,
        onConfirmInstall = skillsVm::confirmInstall,
        onCancelInstall = skillsVm::cancelInstall,
        onOpenSource = skillsVm::openSource,
        onCloseSource = skillsVm::closeSource,
    )

    val announcementState = AnnouncementsUiState(
        items = announcementsVm.visible(),
        status = announcementsVm.status.value,
        filter = when (announcementsVm.filter.value) {
            AnnouncementsViewModel.Filter.ALL -> "all"
            AnnouncementsViewModel.Filter.HISTORY -> "history"
            AnnouncementsViewModel.Filter.UNREAD -> "unread"
        },
        banner = announcementsVm.banner(),
        modal = announcementsVm.modal(),
        selected = announcementsVm.selected.value,
        baseUrl = announcementsVm.baseUrl.value,
        publicKeyHex = announcementsVm.publicKeyHex.value,
        language = language,
    )
    val announcementActions = AnnouncementsActions(
        onFilter = { filter ->
            announcementsVm.filter.value = when (filter) {
                "all" -> AnnouncementsViewModel.Filter.ALL
                "history" -> AnnouncementsViewModel.Filter.HISTORY
                else -> AnnouncementsViewModel.Filter.UNREAD
            }
            announcementsVm.reload()
        },
        onRefresh = { announcementsVm.refresh(force = true) },
        onOpen = announcementsVm::open,
        onCloseDetail = { announcementsVm.selected.value = null },
        onMarkAllRead = announcementsVm::markAllRead,
        onDismiss = announcementsVm::dismiss,
        onAcknowledge = announcementsVm::acknowledge,
        onSaveEndpoint = announcementsVm::saveEndpoint,
        onAppRoute = { appRoute -> requestRoute(routeFromAnnouncement(appRoute)) },
    )

    val settingsActions = SettingsActions(
        onLanguage = settingsVm::language,
        onTheme = settingsVm::theme,
        onStats = announcementsVm::setStats,
        onRequestInspection = settingsVm::inspector,
        onExport = {
            exportAgents = settingsVm.exportAgents()
            exportChooserOpen = true
        },
        onImport = { importLauncher.launch(arrayOf("application/json", "application/zip", "*/*")) },
        onCheckUpdates = settingsVm::checkUpdates,
        onOpenProviders = { requestRoute(AppRoutes.PROVIDERS) },
        onOpenKnowledge = { requestRoute(AppRoutes.KNOWLEDGE) },
        onOpenSkills = { requestRoute(AppRoutes.SKILLS) },
        onOpenAnnouncements = { requestRoute(AppRoutes.NEWS) },
        onOpenMcpSettings = { openMcpSettings(AppRoutes.SETTINGS) },
        onOpenThirdPartyNotices = {
            thirdPartyNotices = thirdPartyNotices.copy(opened = true, error = null)
        },
        onCloseThirdPartyNotices = {
            thirdPartyNotices = thirdPartyNotices.copy(opened = false)
        },
        onSelectThirdPartyNotice = { id ->
            thirdPartyNotices = thirdPartyNotices.copy(
                opened = true,
                selectedComponentId = id,
                selectedLicenseText = null,
                error = null,
            )
        },
    )

    val mcpActions = McpActions(
        onSaveEndpoint = { endpoint, namespace, password -> mcpVm.saveEndpoint(endpoint, namespace, password) },
        onRequestDiscovery = mcpVm::requestDiscovery,
        onConfirmDiscovery = mcpVm::confirmDiscovery,
        onCancelDiscovery = mcpVm::cancelDiscovery,
        onSelectAgent = mcpVm::selectAgent,
        onToggleTool = mcpVm::toggleTool,
        onRequestGrant = mcpVm::requestGrant,
        onConfirmGrant = mcpVm::confirmGrant,
        onCancelGrant = mcpVm::cancelGrant,
        onRevokeGrant = { mcpVm.revokeGrant() },
        onClearConfig = mcpVm::clearConfig,
    )

    BackHandler(enabled = hasUnsavedEditor()) { requestEditorClose() }
    BackHandler(enabled = mcpOpen) { closeMcpSettings() }

    MobileAgentTheme(mode = themeMode) {
        AppNavigationScaffold(
            destinations = defaultAppDestinations(chinese),
            selectedRoute = route,
            onRouteSelected = ::requestRoute,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (mcpOpen) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        BackLabel(
                            onClick = ::closeMcpSettings,
                            label = if (chinese) {
                                if (mcpReturnRoute == AppRoutes.PROVIDERS) "返回服务商" else "返回设置"
                            } else {
                                if (mcpReturnRoute == AppRoutes.PROVIDERS) "Back to providers" else "Back to settings"
                            },
                        )
                        McpSettingsScreen(mcpState, mcpActions, Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    when (route) {
                        AppRoutes.CHAT -> runtime.mobileagent.feature.chat.ChatScreen(chatState, chatActions)
                        AppRoutes.AGENTS -> runtime.mobileagent.feature.agents.AgentsScreen(agentsState, agentsActions)
                        AppRoutes.PROVIDERS -> runtime.mobileagent.feature.providers.ProvidersScreen(providerState, providerActions)
                        AppRoutes.KNOWLEDGE -> runtime.mobileagent.feature.knowledge.KnowledgeScreen(knowledgeState, knowledgeActions)
                        AppRoutes.SKILLS -> runtime.mobileagent.feature.skills.SkillsScreen(skillsState, skillsActions)
                        AppRoutes.NEWS -> runtime.mobileagent.feature.announcements.AnnouncementsScreen(announcementState, announcementActions)
                        AppRoutes.SETTINGS -> runtime.mobileagent.feature.settings.SettingsScreen(settingsState, settingsActions)
                    }
                }
            }
        }

        if (unsavedDialog) {
            UnsavedChangesDialog(chinese, ::discardUnsaved, { unsavedDialog = false })
        }
        if (exportChooserOpen) {
            ExportAgentDialog(
                chinese = chinese,
                agents = exportAgents,
                onConfirm = { agentId, includeSkillPackages, includeKnowledgeContent, includeConversations ->
                    exportChooserOpen = false
                    settingsVm.prepareExport(
                        agentId = agentId,
                        includeSkillPackages = includeSkillPackages,
                        includeKnowledgeContent = includeKnowledgeContent,
                        includeConversations = includeConversations,
                    ) {
                        exportLauncher.launch("mobile-agent-$agentId.zip")
                    }
                },
                onCancel = { exportChooserOpen = false },
            )
        }
        permissionRequest?.let {
            SkillPermissionScopeDialog(
                chinese = chinese,
                capability = permissionCapability,
                declaredScope = permissionScope,
                knowledgeScope = knowledgeScope,
                knowledgeBases = if (knowledgeScope) skillsVm.availableKnowledgeBases() else emptyList(),
                onConfirm = skillsVm::confirmGrant,
                onCancel = skillsVm::cancelGrant,
            )
        }
        knowledgeVm.visionRequest.value?.let { (jobId, retry) ->
            val waiting = knowledgeState.waiting.firstOrNull { it.jobId == jobId }
            VisionConsentDialog(
                chinese = chinese,
                displayName = waiting?.displayName.orEmpty(),
                target = knowledgeVm.visionTarget.value,
                retry = retry,
                onConfirm = knowledgeVm::confirmVision,
                onCancel = knowledgeVm::dismissVision,
            )
        }
        knowledgeVm.embeddingRequest.value?.let { request ->
            EmbeddingConsentDialog(
                chinese = chinese,
                target = request.target,
                retry = request.retry,
                rebind = request.rebind,
                documentCount = request.documentCount,
                onConfirm = knowledgeVm::confirmEmbedding,
                onCancel = knowledgeVm::dismissEmbedding,
                queryRetry = request.queryRetry,
            )
        }
        if (chatVm.unknownRetry.value != null) {
            UnknownOutcomeDialog(
                chinese = chinese,
                onConfirm = chatVm::acknowledgeUnknown,
                onCancel = chatVm::cancelUnknownRetry,
            )
        }
    }
}

private fun routeFromAnnouncement(appRoute: String): String = when (appRoute) {
    "app://settings/providers" -> AppRoutes.PROVIDERS
    "app://settings/knowledge" -> AppRoutes.KNOWLEDGE
    "app://about", "app://update" -> AppRoutes.SETTINGS
    "app://announcements" -> AppRoutes.NEWS
    else -> AppRoutes.NEWS
}

private fun effectiveLanguage(preference: String, deviceLanguage: String): String = when {
    preference.startsWith("en", true) -> "en-US"
    preference.startsWith("zh", true) -> "zh-CN"
    preference.equals("system", true) && deviceLanguage.startsWith("en", true) -> "en-US"
    preference.equals("system", true) && deviceLanguage.startsWith("zh", true) -> "zh-CN"
    else -> "zh-CN"
}

private fun providerCardFrom(profile: ProviderProfile, models: List<ModelProfile>): ProviderCardUi = ProviderCardUi(
    id = profile.id,
    name = profile.name,
    baseUrl = profile.baseUrl,
    apiFormat = profile.apiFormat.name,
    modelCount = models.count { it.providerId == profile.id },
    // This only reflects a persisted secret reference; the secret value is never read by the UI.
    secretConfigured = profile.secretRef.isNotBlank(),
)

private fun providerModelFrom(model: ModelProfile): ProviderModelUi = ProviderModelUi(
    id = model.id,
    modelId = model.modelId,
    role = model.role.name,
    capabilities = model.capabilities,
    contextLimit = model.contextLimit,
    outputLimit = model.outputLimit,
)

private fun providerDraftFrom(provider: ProviderProfile?, model: ModelProfile?): ProviderDraftUi = ProviderDraftUi(
    id = provider?.id,
    modelProfileId = model?.id,
    name = provider?.name.orEmpty(),
    baseUrl = provider?.baseUrl.orEmpty(),
    apiFormat = provider?.apiFormat?.name ?: "OPENAI_COMPATIBLE",
    modelId = model?.modelId.orEmpty(),
    role = model?.role?.name ?: "CHAT",
    parametersJson = model?.parametersJson ?: "{}",
    contextLimit = model?.contextLimit ?: 32_768,
    outputLimit = model?.outputLimit ?: 4_096,
    vision = model?.capabilities?.contains("image") == true,
    tools = model?.capabilities?.contains("tools") == true,
)

private fun thirdPartyNoticeError(failure: Throwable, chinese: Boolean): String {
    val fallback = if (chinese) "第三方声明文件读取失败。" else "The third-party notice could not be read."
    return failure.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(256)?.ifBlank { fallback } ?: fallback
}
