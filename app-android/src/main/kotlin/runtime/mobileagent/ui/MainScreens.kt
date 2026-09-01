// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.ShellViewModel

/**
 * The application shell owns navigation state and long-running ViewModels. Chat,
 * its inspector, and knowledge import staging deliberately share shell-scoped
 * instances so route changes cannot cancel active work or lose its progress.
 */
@Composable
internal fun MainApp() {
    val app = LocalContext.current.applicationContext as MobileAgentApp
    val shellVm: ShellViewModel = viewModel()
    val shellOwner = checkNotNull(LocalViewModelStoreOwner.current) { "MainApp requires a stable shell ViewModelStoreOwner" }
    val chatVm: runtime.mobileagent.ChatViewModel = viewModel(viewModelStoreOwner = shellOwner)
    val knowledgeVm: runtime.mobileagent.KnowledgeViewModel = viewModel(viewModelStoreOwner = shellOwner)
    val navController = rememberNavController()
    val initialRoute = shellVm.route().takeIf { it in allAppRoutes } ?: AppRoutes.CHAT
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    var mcpReturnRoute by rememberSaveable { mutableStateOf(AppRoutes.SETTINGS) }
    var pendingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var unsavedDialog by rememberSaveable { mutableStateOf(false) }
    var inspectorReturnRoute by rememberSaveable { mutableStateOf(AppRoutes.MORE) }
    var editorOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var editorDirty by rememberSaveable { mutableStateOf(false) }
    var editorDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var settingsRevision by remember { mutableIntStateOf(0) }
    var pendingUpdateCheck by rememberSaveable { mutableStateOf(false) }

    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.destination?.route) {
        val destination = currentEntry?.destination?.route?.takeIf { it in allAppRoutes } ?: return@LaunchedEffect
        if (route != destination) route = destination
        shellVm.setRoute(destination)
    }
    LaunchedEffect(route, currentEntry?.destination?.route) {
        val currentRoute = currentEntry?.destination?.route ?: return@LaunchedEffect
        if (currentRoute == route) return@LaunchedEffect
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun navigate(target: String) {
        if (target !in allAppRoutes || target == route) return
        if (target == AppRoutes.INSPECTOR) inspectorReturnRoute = route
        route = target
        shellVm.setRoute(target)
    }

    fun requestRoute(target: String) {
        if (target !in allAppRoutes || target == route) return
        if (editorOwner == route && editorDirty) {
            pendingRoute = target
            unsavedDialog = true
        } else {
            editorOwner = null
            editorDirty = false
            editorDiscard = null
            navigate(target)
        }
    }

    fun requestEditorClose() {
        if (editorOwner == route && editorDirty) {
            pendingRoute = null
            unsavedDialog = true
        } else {
            editorDiscard?.invoke()
        }
    }

    fun registerEditorState(owner: String, dirty: Boolean, discard: (() -> Unit)?) {
        if (owner != route) return
        editorOwner = owner
        editorDirty = dirty
        editorDiscard = discard
    }

    fun discardUnsaved() {
        val target = pendingRoute
        unsavedDialog = false
        pendingRoute = null
        editorDiscard?.invoke()
        editorOwner = null
        editorDirty = false
        editorDiscard = null
        target?.let(::navigate)
    }

    fun handleBack(compact: Boolean) {
        if (editorOwner == route && editorDirty) {
            requestEditorClose()
            return
        }
        if (route == AppRoutes.INSPECTOR) chatVm.inspector(false)
        val target = appBackTarget(
            compact = compact,
            currentRoute = route,
            inspectorReturnRoute = inspectorReturnRoute,
            hasPreviousEntry = navController.previousBackStackEntry != null,
        )
        if (target != null && target != route) {
            requestRoute(target)
        } else if (!navController.popBackStack() && route != AppRoutes.CHAT) {
            requestRoute(AppRoutes.CHAT)
        }
    }

    val settings = remember(settingsRevision) { app.container.settings.get() }
    val deviceLanguage = LocalConfiguration.current.locales.get(0)?.language.orEmpty()
    val language = effectiveLanguage(
        when (settings.locale) {
            runtime.mobileagent.domain.LocalePreference.SYSTEM -> "system"
            runtime.mobileagent.domain.LocalePreference.ZH_CN -> "zh-CN"
            runtime.mobileagent.domain.LocalePreference.EN_US -> "en-US"
        },
        deviceLanguage,
    )
    val chinese = language == "zh-CN"
    val themeMode = when (settings.theme) {
        runtime.mobileagent.domain.ThemePreference.LIGHT -> AppThemeMode.LIGHT
        runtime.mobileagent.domain.ThemePreference.DARK -> AppThemeMode.DARK
        runtime.mobileagent.domain.ThemePreference.COLOR_66CCFF -> AppThemeMode.CC66FF
        runtime.mobileagent.domain.ThemePreference.SYSTEM -> AppThemeMode.SYSTEM
    }

    BackHandler(enabled = editorOwner == route && editorDirty) { requestEditorClose() }
    MobileAgentTheme(mode = themeMode) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            BackHandler(
                enabled = !(editorOwner == route && editorDirty) &&
                    (route != AppRoutes.CHAT || navController.previousBackStackEntry != null),
            ) { handleBack(compact) }
            val destinations = if (compact) phonePrimaryDestinations(chinese) else defaultAppDestinations(chinese)
            val selected = if (compact && route !in destinations.map { it.route }) AppRoutes.MORE else route
            AppNavigationScaffold(
                destinations = destinations,
                selectedRoute = selected,
                onRouteSelected = { target ->
                    val actual = if (compact && target == AppRoutes.MORE && route in moreHubItems(chinese).map { it.route } + AppRoutes.MORE) {
                        AppRoutes.MORE
                    } else target
                    requestRoute(actual)
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    NavHost(navController = navController, startDestination = initialRoute) {
                        composable(AppRoutes.CHAT) { ChatRoute(chatVm, chinese, ::requestRoute, ::registerEditorState) }
                        composable(AppRoutes.AGENTS) { AgentsRoute(it, chinese, ::requestRoute, ::requestEditorClose, ::registerEditorState) }
                        composable(AppRoutes.PROVIDERS) {
                            ProvidersRoute(it, chinese, ::requestRoute, ::requestEditorClose, ::registerEditorState,
                                { mcpReturnRoute = AppRoutes.PROVIDERS; requestRoute(AppRoutes.MCP) }, compact, { handleBack(compact) })
                        }
                        composable(AppRoutes.KNOWLEDGE) { KnowledgeRoute(knowledgeVm, chinese, ::requestRoute) }
                        composable(AppRoutes.SKILLS) { SkillsRoute(it, chinese) }
                        composable(AppRoutes.NEWS) {
                            AnnouncementsRoute(it, chinese, compact, { handleBack(compact) }) { appRoute ->
                                if (appRoute == "app://update") pendingUpdateCheck = true
                                requestRoute(routeFromAnnouncement(appRoute))
                            }
                        }
                        composable(AppRoutes.SETTINGS) {
                            SettingsRoute(it, chinese, ::requestRoute,
                                { mcpReturnRoute = AppRoutes.SETTINGS; requestRoute(AppRoutes.MCP) },
                                { settingsRevision++ }, autoCheckUpdate = pendingUpdateCheck,
                                onAutoCheckConsumed = { pendingUpdateCheck = false }, showBack = compact,
                                onBack = { handleBack(compact) })
                        }
                        composable(AppRoutes.ABOUT) {
                            SettingsRoute(it, chinese, ::requestRoute,
                                { mcpReturnRoute = AppRoutes.ABOUT; requestRoute(AppRoutes.MCP) },
                                { settingsRevision++ }, aboutOnly = true, showBack = compact,
                                onBack = { handleBack(compact) })
                        }
                        composable(AppRoutes.MORE) { MoreHub(chinese, ::requestRoute) }
                        composable(AppRoutes.MCP) { McpRoute(it, chinese, if (compact) AppRoutes.MORE else mcpReturnRoute, ::requestRoute) }
                        composable(AppRoutes.INSPECTOR) {
                            InspectorRoute(
                                vm = chatVm,
                                chinese = chinese,
                                showBack = compact,
                                inspectorEnabled = app.container.uiPreferences.getBoolean("request-inspector", true),
                                onBack = { handleBack(compact) },
                            )
                        }
                    }
                }
            }
        }
        if (unsavedDialog) UnsavedChangesDialog(chinese, ::discardUnsaved, { unsavedDialog = false })
    }
}

private val allAppRoutes = setOf(
    AppRoutes.CHAT, AppRoutes.AGENTS, AppRoutes.PROVIDERS, AppRoutes.KNOWLEDGE,
    AppRoutes.SKILLS, AppRoutes.NEWS, AppRoutes.SETTINGS, AppRoutes.MORE,
    AppRoutes.ABOUT, AppRoutes.INSPECTOR, AppRoutes.MCP,
)

/** Regression seam for routes whose active work must outlive a destination entry. */
internal fun isShellScopedLongRunningRoute(route: String): Boolean =
    route == AppRoutes.CHAT || route == AppRoutes.KNOWLEDGE

/**
 * Preserve the host-provided inspector availability across Chat -> Inspector
 * navigation. Legacy hosts without the field retain the old deterministic
 * preview-based fallback.
 */
internal fun requestInspectorAvailability(
    state: runtime.mobileagent.feature.chat.ChatUiState,
    inspectorEnabled: Boolean,
): runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability =
    if (!inspectorEnabled) {
        runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability.DISABLED
    } else {
        state.requestInspectorAvailability ?: when {
            state.requestPreview == null -> runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability.NOT_PREPARED
            else -> runtime.mobileagent.feature.chat.ChatRequestInspectorAvailability.READY
        }
    }

@Composable
private fun MoreHub(chinese: Boolean, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (chinese) "更多" else "More", style = MaterialTheme.typography.headlineSmall)
        moreHubItems(chinese).forEach { item ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(item.route) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(item.icon, contentDescription = item.label)
                    Text(item.label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ChatRoute(vm: runtime.mobileagent.ChatViewModel, chinese: Boolean, onRoute: (String) -> Unit,
    onEditorState: (String, Boolean, (() -> Unit)?) -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    val state = vm.state.value.copy(language = if (chinese) "zh-CN" else "en-US")
    val actions = runtime.mobileagent.feature.chat.ChatActions(
        onInput = vm::input, onSend = vm::send, onCancel = vm::cancel,
        onToggleDegradation = vm::degrade, onSelectSession = vm::selectSession,
        onNewSession = { vm.newSession() }, onSelectAgent = vm::selectAgent,
        onOpenCitation = vm::openCitation, onCloseCitation = vm::closeCitation,
        onToolApproval = { choice -> vm.approveTool(choice == runtime.mobileagent.feature.chat.ToolApprovalChoice.APPROVE) },
        onOpenRequestInspector = { vm.inspector(true); onRoute(AppRoutes.INSPECTOR) },
        onCloseRequestInspector = { vm.inspector(false); onRoute(AppRoutes.CHAT) },
    )
    runtime.mobileagent.feature.chat.ChatScreen(state, actions)
    vm.unknownRetry.value?.let { UnknownOutcomeDialog(chinese, vm::acknowledgeUnknown, vm::cancelUnknownRetry) }
    LaunchedEffect(Unit) { onEditorState(AppRoutes.CHAT, false, null) }
}

@Composable
private fun AgentsRoute(entry: NavBackStackEntry, chinese: Boolean, onRoute: (String) -> Unit,
    onRequestEditorClose: () -> Unit, onEditorState: (String, Boolean, (() -> Unit)?) -> Unit) {
    val vm: runtime.mobileagent.AgentsViewModel = viewModel(viewModelStoreOwner = entry)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    val state = vm.state.value.copy(language = if (chinese) "zh-CN" else "en-US")
    val discard = remember(vm) { { vm.closeEditor() } }
    LaunchedEffect(state.editorDirty) { onEditorState(AppRoutes.AGENTS, state.editorDirty, discard) }
    val actions = runtime.mobileagent.feature.agents.AgentsActions(
        onQuery = vm::query, onSelectAgent = vm::select, onOpenEditor = vm::openEditor,
        onCloseEditor = onRequestEditorClose, onEditorChange = vm::edit,
        onSave = { vm.save() }, onSavePromptRevision = { vm.save() },
        onRestorePrompt = vm::restorePrompt, onToggleResource = vm::toggleResource,
        onSnapshot = { if (vm.createConversation() != null) onRoute(AppRoutes.CHAT) },
    )
    runtime.mobileagent.feature.agents.AgentsScreen(state, actions)
}

@Composable
private fun ProvidersRoute(entry: NavBackStackEntry, chinese: Boolean, onRoute: (String) -> Unit,
    onRequestEditorClose: () -> Unit, onEditorState: (String, Boolean, (() -> Unit)?) -> Unit,
    onOpenMcp: () -> Unit, showBack: Boolean, onBack: () -> Unit) {
    val vm: runtime.mobileagent.ProvidersViewModel = viewModel(viewModelStoreOwner = entry)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var providerDraft by rememberSaveable(stateSaver = providerDraftSaver) { mutableStateOf(runtime.mobileagent.feature.providers.ProviderDraft()) }
    var providerBaseline by rememberSaveable(stateSaver = providerDraftSaver) { mutableStateOf(runtime.mobileagent.feature.providers.ProviderDraft()) }
    var providerError by rememberSaveable { mutableStateOf<String?>(null) }
    var probeModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var probeMessage by rememberSaveable { mutableStateOf("") }
    val providers = vm.providers.toList()
    val models = vm.models.toList()
    val selectedId = selectedProviderId?.takeIf { id -> providers.any { it.id == id } } ?: providers.firstOrNull()?.id
    val dirty = editorOpen && providerDraft != providerBaseline
    val discard = remember(vm) { { editorOpen = false; providerDraft = providerDraft.copy(apiKey = ""); providerError = null } }
    LaunchedEffect(dirty) { onEditorState(AppRoutes.PROVIDERS, dirty, discard) }
    val selectedModels = models.filter { it.providerId == selectedId }
    val app = LocalContext.current.applicationContext as MobileAgentApp
    val mcpState = runtime.mobileagent.McpConfigStore.read(app.container)
    val preview = selectedId?.let(vm::deletePreview)
    val state = runtime.mobileagent.feature.providers.ProvidersUiState(
        providers = providers.map { profile -> providerCardFrom(profile, models) }, selectedProviderId = selectedId,
        models = selectedModels.map(::providerModelFrom), draft = providerDraft,
        probe = when {
            probeModelId == null -> runtime.mobileagent.feature.providers.ProbeUiState()
            probeModelId == "__none__" -> runtime.mobileagent.feature.providers.ProbeUiState(phase = "error", message = probeMessage)
            vm.busy.value -> runtime.mobileagent.feature.providers.ProbeUiState(phase = "running", message = probeMessage)
            else -> runtime.mobileagent.feature.providers.ProbeUiState(phase = "complete", message = vm.status.value)
        }, editorOpen = editorOpen, loading = vm.busy.value, error = providerError, status = vm.status.value,
        language = if (chinese) "zh-CN" else "en-US", mcpReason = if (mcpState.value != null) "MCP 配置入口可用。" else "MCP 尚未配置服务器。",
        mcpEntryEnabled = true, editorError = providerError, deleteModelCount = preview?.modelCount ?: 0,
        deleteSnapshotCount = preview?.snapshotCount ?: 0,
    )
    val actions = runtime.mobileagent.feature.providers.ProvidersActions(
        onSelectProvider = { selectedProviderId = it; probeModelId = null },
        onDraftChange = { nextDraft ->
            if (providerDraft.vision != nextDraft.vision) vm.recordCapabilityToggle("image", nextDraft.vision)
            if (providerDraft.tools != nextDraft.tools) vm.recordCapabilityToggle("tools", nextDraft.tools)
            providerDraft = nextDraft
            providerError = null
        },
        onOpenEditor = { id ->
            providerDraft = providerDraftFrom(providers.firstOrNull { it.id == id }, null)
            providerBaseline = providerDraft; providerError = null; editorOpen = true
        },
        onCloseEditor = onRequestEditorClose,
        onSave = {
            val savingModel = providerDraft.modelId.isNotBlank() || providerDraft.modelProfileId != null
            val role = if (savingModel) runCatching { runtime.mobileagent.domain.ModelRole.valueOf(providerDraft.role.trim().uppercase()) }.getOrNull()
                else runtime.mobileagent.domain.ModelRole.CHAT
            if (savingModel && role == null) {
                providerError = if (chinese) "模型角色必须是 CHAT、VISION、EMBEDDING 或 RERANKER。" else "Model role must be CHAT, VISION, EMBEDDING, or RERANKER."
            } else if (vm.saveDraft(runtime.mobileagent.ProviderDraft(
                    providerId = providerDraft.id, modelProfileId = providerDraft.modelProfileId,
                    name = providerDraft.name, baseUrl = providerDraft.baseUrl, modelId = providerDraft.modelId,
                    apiKey = providerDraft.apiKey, role = role ?: runtime.mobileagent.domain.ModelRole.CHAT,
                    capabilities = buildSet {
                        add("stream"); if (providerDraft.vision) add("image"); if (providerDraft.tools) add("tools")
                        models.firstOrNull { it.id == providerDraft.modelProfileId }?.capabilities.orEmpty()
                            .filter { it !in setOf("image", "tools") }.forEach(::add)
                    }, parametersJson = providerDraft.parametersJson, contextLimit = providerDraft.contextLimit,
                    outputLimit = providerDraft.outputLimit,
                ))) {
                editorOpen = false; providerDraft = providerDraft.copy(apiKey = ""); providerBaseline = providerDraft
                providerError = null; selectedProviderId = providerDraft.id ?: providers.firstOrNull { it.name == providerDraft.name.trim() }?.id
            } else if (vm.status.value.isNotBlank()) providerError = vm.status.value
        },
        onDelete = { selectedId?.let(vm::deleteProvider); selectedProviderId = null },
        onEditModel = { id ->
            val model = models.firstOrNull { it.id == id }
            providerDraft = providerDraftFrom(providers.firstOrNull { it.id == model?.providerId }, model)
            providerBaseline = providerDraft; providerError = null; editorOpen = true
        },
        onDeleteModel = vm::deleteModel,
        onProbe = {
            val model = selectedModels.firstOrNull()
            if (model == null) {
                probeModelId = "__none__"; probeMessage = if (chinese) "当前服务商没有可探测的模型元数据。" else "This provider has no model metadata to probe."
            } else {
                probeModelId = model.id; probeMessage = if (chinese) "正在向已配置端点发送明确批准的探测请求。" else "Sending the explicitly approved probe request."; vm.probe(model.id, approved = true)
            }
        },
        onCloseProbe = { probeModelId = null; probeMessage = "" }, onOpenMcpSettings = onOpenMcp,
    )
    if (showBack) {
        Column(Modifier.fillMaxSize()) {
            BackLabel(onClick = onBack, label = if (chinese) "返回" else "Back")
            runtime.mobileagent.feature.providers.ProvidersScreen(state, actions, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        runtime.mobileagent.feature.providers.ProvidersScreen(state, actions)
    }
}

@Composable
private fun KnowledgeRoute(vm: runtime.mobileagent.KnowledgeViewModel, chinese: Boolean, onRoute: (String) -> Unit) {
    val state = vm.state.value.copy(language = if (chinese) "zh-CN" else "en-US")
    val actions = runtime.mobileagent.feature.knowledge.KnowledgeActions(
        onImport = vm::importUris, onImportZip = vm::importZip, onImportFolder = vm::importTree,
        onSelectBase = vm::selectBase, onOpenEvidence = vm::openEvidence, onRebuild = vm::rebuild,
        onGrantVision = vm::grantVision, onRetryVision = vm::retryVision, onTextOnly = vm::textOnly,
        onConfigureVision = { onRoute(AppRoutes.PROVIDERS) }, onKeepWaiting = vm::keepWaiting,
        onDeleteDocument = vm::deleteDocument, onCloseEvidence = vm::closeEvidence,
        onCreateBase = vm::createBase, onDeleteBase = vm::deleteBase, onCancelJob = vm::cancelJob,
        onConfigureEmbedding = vm::configureEmbedding, onGrantEmbedding = vm::grantEmbedding,
        onRetryEmbedding = vm::retryEmbedding,
        onAuthorizeQueryRetry = { spaceId, queryHash -> vm.requestQueryRetry(spaceId, queryHash) },
    )
    runtime.mobileagent.feature.knowledge.KnowledgeScreen(state, actions)
    vm.visionRequest.value?.let { (jobId, retry) ->
        val waiting = state.waiting.firstOrNull { it.jobId == jobId }
        VisionConsentDialog(chinese, waiting?.displayName.orEmpty(), vm.visionTarget.value, retry, vm::confirmVision, vm::dismissVision)
    }
    vm.embeddingRequest.value?.let { request ->
        EmbeddingConsentDialog(chinese, request.target, request.retry, request.rebind, request.documentCount, vm::confirmEmbedding, vm::dismissEmbedding, request.queryRetry)
    }
}

@Composable
private fun SkillsRoute(entry: NavBackStackEntry, chinese: Boolean) {
    val vm: runtime.mobileagent.SkillsViewModel = viewModel(viewModelStoreOwner = entry)
    val state = vm.state.value.copy(language = if (chinese) "zh-CN" else "en-US")
    val request = vm.permissionRequest.value
    val capability = request?.second.orEmpty()
    val scope = state.detail?.permissions?.firstOrNull { it.capability == capability }?.scope.orEmpty()
    val knowledgeScope = capability in setOf("knowledge.search", "knowledge.read", "document.read")
    val actions = runtime.mobileagent.feature.skills.SkillsActions(
        onImport = vm::importUris, onQuery = vm::query, onFilter = vm::filter, onOpenDetail = vm::openDetail,
        onCloseDetail = vm::closeDetail, onToggle = vm::toggle, onGrantPermission = vm::beginGrant,
        onRevokePermission = vm::revokePermission, onConfirmInstall = vm::confirmInstall,
        onCancelInstall = vm::cancelInstall, onOpenSource = vm::openSource, onCloseSource = vm::closeSource,
    )
    runtime.mobileagent.feature.skills.SkillsScreen(state, actions)
    request?.let {
        SkillPermissionScopeDialog(chinese, capability, scope, knowledgeScope,
            if (knowledgeScope) vm.availableKnowledgeBases() else emptyList(), vm::confirmGrant, vm::cancelGrant)
    }
}

@Composable
private fun AnnouncementsRoute(entry: NavBackStackEntry, chinese: Boolean, showBack: Boolean, onBack: () -> Unit,
    onAppRoute: (String) -> Unit) {
    val vm: runtime.mobileagent.AnnouncementsViewModel = viewModel(viewModelStoreOwner = entry)
    val state = runtime.mobileagent.feature.announcements.AnnouncementsUiState(
        items = vm.visible(), status = vm.status.value,
        filter = when (vm.filter.value) {
            runtime.mobileagent.AnnouncementsViewModel.Filter.ALL -> "all"
            runtime.mobileagent.AnnouncementsViewModel.Filter.HISTORY -> "history"
            runtime.mobileagent.AnnouncementsViewModel.Filter.UNREAD -> "unread"
        }, banner = vm.banner(), modal = vm.modal(), selected = vm.selected.value,
        language = if (chinese) "zh-CN" else "en-US",
    )
    val actions = runtime.mobileagent.feature.announcements.AnnouncementsActions(
        onFilter = { filter ->
            vm.filter.value = when (filter) {
                "all" -> runtime.mobileagent.AnnouncementsViewModel.Filter.ALL
                "history" -> runtime.mobileagent.AnnouncementsViewModel.Filter.HISTORY
                else -> runtime.mobileagent.AnnouncementsViewModel.Filter.UNREAD
            }; vm.reload()
        }, onRefresh = { vm.refresh(force = true) }, onOpen = vm::open,
        onCloseDetail = { vm.selected.value = null }, onMarkAllRead = vm::markAllRead,
        onDismiss = vm::dismiss, onAcknowledge = vm::acknowledge,
        onAppRoute = onAppRoute,
    )
    if (showBack) {
        Column(Modifier.fillMaxSize()) {
            BackLabel(onClick = onBack, label = if (chinese) "返回" else "Back")
            runtime.mobileagent.feature.announcements.AnnouncementsScreen(state, actions, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        runtime.mobileagent.feature.announcements.AnnouncementsScreen(state, actions)
    }
}

@Composable
private fun SettingsRoute(entry: NavBackStackEntry, chinese: Boolean, onRoute: (String) -> Unit,
    onOpenMcp: () -> Unit, onSettingsChanged: () -> Unit, aboutOnly: Boolean = false,
    autoCheckUpdate: Boolean = false, onAutoCheckConsumed: () -> Unit = {}, showBack: Boolean = false,
    onBack: () -> Unit = {}) {
    val vm: runtime.mobileagent.SettingsViewModel = viewModel(viewModelStoreOwner = entry)
    val app = LocalContext.current.applicationContext as MobileAgentApp
    var thirdParty by remember { mutableStateOf(runtime.mobileagent.feature.settings.ThirdPartyNoticesUiState()) }
    var exportChooserOpen by remember { mutableStateOf(false) }
    var exportAgents by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importFrom(it) }
    // Keep the provider-returned READ/WRITE flags with the URI. Providers are
    // allowed to return a read-only tree even when the chooser was launched
    // with both capabilities requested.
    val safTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri -> vm.authorizeSaf(uri, result.data?.flags ?: 0) }
    }
    val launchSafTree = {
        safTreeLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            ),
        )
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { vm.exportTo(it) }
    val diagnosticsExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { vm.exportDiagnosticsTo(it) }
    val mcpConfigured = runtime.mobileagent.McpConfigStore.read(app.container).value != null
    val raw = vm.uiState(app.container.announcements.statsEnabled(), app.container.announcements.records().count { it.state.readAt == null })
    val state = raw.copy(language = if (chinese) "zh-CN" else "en-US",
        mcpConfigured = mcpConfigured, mcpEntryEnabled = true, thirdPartyNotices = thirdParty,
    )
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAuthorities() }
    // Pairing tokens are foreground-only. Leaving/backgrounding this route
    // clears the ViewModel's ephemeral token and asks the adapter to cancel.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.cancelWiredAdbPairing() }
    LaunchedEffect(Unit) { vm.refreshAuthorities() }
    LaunchedEffect(autoCheckUpdate) {
        if (autoCheckUpdate) {
            onAutoCheckConsumed()
            vm.checkUpdates()
        }
    }
    LaunchedEffect(thirdParty.opened, thirdParty.selectedComponentId, thirdParty.components.size, thirdParty.error) {
        if (!thirdParty.opened || thirdParty.loading || thirdParty.error != null) return@LaunchedEffect
        if (thirdParty.components.isEmpty() && thirdParty.overview.isBlank()) {
            thirdParty = thirdParty.copy(loading = true)
            thirdParty = runtime.mobileagent.feature.settings.ThirdPartyNoticeAssets.loadCatalog(context).copy(opened = true)
        } else {
            val selected = thirdParty.components.firstOrNull { it.id == thirdParty.selectedComponentId }
            if (selected != null && thirdParty.selectedLicenseText == null) {
                thirdParty = thirdParty.copy(loading = true)
                thirdParty = runtime.mobileagent.feature.settings.ThirdPartyNoticeAssets.loadComponentText(context, selected).fold(
                    onSuccess = { text -> thirdParty.copy(loading = false, selectedLicenseText = text, error = null) },
                    onFailure = { failure -> thirdParty.copy(loading = false, error = thirdPartyNoticeError(failure, chinese)) },
                )
            }
        }
    }
    val actions = runtime.mobileagent.feature.settings.SettingsActions(
        onLanguage = { vm.language(it); onSettingsChanged() }, onTheme = { vm.theme(it); onSettingsChanged() },
        onStats = { app.container.announcements.setStatsEnabled(it) }, onRequestInspection = vm::inspector,
        onDiagnosticsEnabled = vm::setDiagnosticsEnabled,
        onExportDiagnostics = { diagnosticsExportLauncher.launch("mobile-agent-diagnostics.zip") },
        onClearDiagnostics = vm::clearDiagnostics,
        onExport = { exportAgents = vm.exportAgents(); exportChooserOpen = true },
        onImport = { importLauncher.launch(arrayOf("application/json", "application/zip", "*/*")) },
        onCheckUpdates = vm::checkUpdates, onOpenProviders = { onRoute(AppRoutes.PROVIDERS) },
        onOpenKnowledge = { onRoute(AppRoutes.KNOWLEDGE) }, onOpenSkills = { onRoute(AppRoutes.SKILLS) },
        onOpenAnnouncements = { onRoute(AppRoutes.NEWS) }, onOpenMcpSettings = onOpenMcp,
        onOpenThirdPartyNotices = { thirdParty = thirdParty.copy(opened = true, error = null) },
        onCloseThirdPartyNotices = { thirdParty = thirdParty.copy(opened = false) },
        onSelectThirdPartyNotice = { id -> thirdParty = thirdParty.copy(opened = true, selectedComponentId = id, selectedLicenseText = null, error = null) },
        onUnlockRootPrompt = vm::unlockRootPrompt, onSaveRootPrompt = vm::saveRootPrompt, onRestoreRootPrompt = vm::restoreRootPrompt,
        onSaveWebSearch = vm::saveWebSearch, onWebSearchEnabled = vm::setWebSearchEnabled,
        onClearWebSearch = vm::clearWebSearch,
        onSelectAuthority = vm::selectAuthority,
        onAuthorityIntent = vm::setAuthorityIntent,
        onRefreshAuthority = { vm.refreshAuthorities() },
        onRequestShizukuPermission = vm::requestShizukuPermission,
        onEnableShizuku = vm::enableShizuku,
        onOpenShizuku = { vm.openShizuku() },
        onRequestWiredPairing = vm::requestWiredAdbPairing,
        onCompleteWiredPairing = vm::completeWiredAdbPairing,
        onCancelWiredPairing = vm::cancelWiredAdbPairing,
        onWiredPairingToken = vm::wiredPairingToken,
        onForgetWiredAdb = vm::forgetWiredAdb,
        onSelectSafTree = launchSafTree,
        onReauthorizeSaf = launchSafTree,
        onRevokeSaf = vm::revokeSaf,
        onOpenAgents = { onRoute(AppRoutes.AGENTS) },
        onSetDangerousMode = vm::setDangerousMode,
        onDisableDangerousMode = vm::disableDangerousMode,
    )
    if (showBack) {
        Column(Modifier.fillMaxSize()) {
            BackLabel(onClick = onBack, label = if (chinese) "返回" else "Back")
            if (aboutOnly) runtime.mobileagent.feature.settings.AboutScreen(state, actions, Modifier.weight(1f).fillMaxWidth())
            else runtime.mobileagent.feature.settings.SettingsScreen(state, actions, Modifier.weight(1f).fillMaxWidth())
        }
    } else if (aboutOnly) {
        runtime.mobileagent.feature.settings.AboutScreen(state, actions)
    } else {
        runtime.mobileagent.feature.settings.SettingsScreen(state, actions)
    }
    if (exportChooserOpen) ExportAgentDialog(chinese, exportAgents,
        onConfirm = { agentId, includeSkillPackages, includeKnowledgeContent, includeConversations ->
            exportChooserOpen = false
            vm.prepareExport(agentId, includeSkillPackages, includeKnowledgeContent, includeConversations) { exportLauncher.launch("mobile-agent-$agentId.zip") }
        }, onCancel = { exportChooserOpen = false })
}

@Composable
private fun McpRoute(entry: NavBackStackEntry, chinese: Boolean, returnRoute: String, onRoute: (String) -> Unit) {
    val vm: runtime.mobileagent.McpViewModel = viewModel(viewModelStoreOwner = entry)
    val actions = runtime.mobileagent.ui.McpActions(
        onSaveEndpoint = { endpoint, namespace, password -> vm.saveEndpoint(endpoint, namespace, password) },
        onRequestDiscovery = vm::requestDiscovery, onConfirmDiscovery = vm::confirmDiscovery,
        onCancelDiscovery = vm::cancelDiscovery, onSelectAgent = vm::selectAgent, onToggleTool = vm::toggleTool,
        onRequestGrant = vm::requestGrant, onConfirmGrant = vm::confirmGrant, onCancelGrant = vm::cancelGrant,
        onRevokeGrant = { vm.revokeGrant() }, onClearConfig = vm::clearConfig,
    )
    Column(Modifier.fillMaxSize()) {
        BackLabel(onClick = { onRoute(returnRoute) }, label = if (chinese) "返回" else "Back")
        McpSettingsScreen(vm.state.value, actions, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun InspectorRoute(
    vm: runtime.mobileagent.ChatViewModel,
    chinese: Boolean,
    showBack: Boolean,
    inspectorEnabled: Boolean,
    onBack: () -> Unit,
) {
    val state = vm.state.value.copy(language = if (chinese) "zh-CN" else "en-US")
    val preview = state.requestPreview
    val inspectorAvailability = requestInspectorAvailability(state, inspectorEnabled)
    val close = {
        vm.inspector(false)
        onBack()
    }
    @Composable
    fun InspectorContent(modifier: Modifier = Modifier) {
        // Keep the ChatViewModel's shell-scoped availability as the single
        // source of truth. RequestInspectorScreen owns the stable disabled,
        // not-prepared, context-lost, and ready rendering for nullable data.
        runtime.mobileagent.feature.chat.RequestInspectorScreen(
            request = preview,
            layers = state.promptLayers,
            onClose = close,
            zh = chinese,
            modifier = modifier,
            availability = inspectorAvailability,
        )
    }
    if (showBack) {
        Column(Modifier.fillMaxSize()) {
            BackLabel(onClick = close, label = if (chinese) "返回" else "Back")
            InspectorContent(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        InspectorContent()
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

private fun providerCardFrom(profile: runtime.mobileagent.domain.ProviderProfile, models: List<runtime.mobileagent.domain.ModelProfile>) =
    runtime.mobileagent.feature.providers.ProviderCardUi(profile.id, profile.name, profile.baseUrl, profile.apiFormat.name,
        modelCount = models.count { it.providerId == profile.id }, secretConfigured = profile.secretRef.isNotBlank())

private fun providerModelFrom(model: runtime.mobileagent.domain.ModelProfile) = runtime.mobileagent.feature.providers.ProviderModelUi(
    id = model.id, modelId = model.modelId, role = model.role.name, capabilities = model.capabilities,
    contextLimit = model.contextLimit, outputLimit = model.outputLimit)

private fun providerDraftFrom(provider: runtime.mobileagent.domain.ProviderProfile?, model: runtime.mobileagent.domain.ModelProfile?) =
    runtime.mobileagent.feature.providers.ProviderDraft(id = provider?.id, modelProfileId = model?.id,
        name = provider?.name.orEmpty(), baseUrl = provider?.baseUrl.orEmpty(), apiFormat = provider?.apiFormat?.name ?: "OPENAI_COMPATIBLE",
        modelId = model?.modelId.orEmpty(), role = model?.role?.name ?: "CHAT", parametersJson = model?.parametersJson ?: "{}",
        contextLimit = model?.contextLimit?.toString() ?: "32768", outputLimit = model?.outputLimit?.toString() ?: "4096",
        vision = model?.capabilities?.contains("image") == true, tools = model?.capabilities?.contains("tools") == true)

private val providerDraftSaver: Saver<runtime.mobileagent.feature.providers.ProviderDraft, List<Any?>> = Saver(
    save = { draft -> listOf(draft.id, draft.modelProfileId, draft.name, draft.baseUrl, draft.apiFormat, draft.modelId,
        draft.vision, draft.tools, draft.role, draft.parametersJson, draft.contextLimit, draft.outputLimit) },
    restore = { value -> runtime.mobileagent.feature.providers.ProviderDraft(
        id = value[0] as String?, modelProfileId = value[1] as String?, name = value[2] as String,
        baseUrl = value[3] as String, apiFormat = value[4] as String, modelId = value[5] as String,
        vision = value[6] as Boolean, tools = value[7] as Boolean, role = value[8] as String,
        parametersJson = value[9] as String, contextLimit = value[10]?.toString() ?: "32768",
        outputLimit = value[11]?.toString() ?: "4096") },
)

private fun thirdPartyNoticeError(failure: Throwable, chinese: Boolean): String {
    val fallback = if (chinese) "第三方声明文件读取失败。" else "The third-party notice could not be read."
    return failure.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(256)?.ifBlank { fallback } ?: fallback
}
