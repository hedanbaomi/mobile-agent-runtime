// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.WorkspacePickerViewModel
import runtime.mobileagent.domain.WorkspaceIntent
import runtime.mobileagent.domain.WorkspaceTarget
import runtime.mobileagent.feature.agents.WorkspacePickerActions
import runtime.mobileagent.feature.agents.WorkspacePickerAttachPhaseUi
import runtime.mobileagent.feature.agents.WorkspacePickerScreen
import runtime.mobileagent.workspace.CanonicalWorkspaceCoordinator
import runtime.mobileagent.workspace.WorkspaceSelectionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var pendingAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var unsavedDialog by rememberSaveable { mutableStateOf(false) }
    var inspectorReturnRoute by rememberSaveable { mutableStateOf(AppRoutes.MORE) }
    var editorOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var editorDirty by rememberSaveable { mutableStateOf(false) }
    var editorDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var settingsRevision by remember { mutableIntStateOf(0) }
    var pendingUpdateCheck by rememberSaveable { mutableStateOf(false) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var workspacePickerOpen by rememberSaveable { mutableStateOf(false) }
    var workspacePickerAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var workspacePickerThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var workspacePickerLabel by rememberSaveable { mutableStateOf("当前智能体") }
    var shellDetailOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var shellDetailTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var shellDetailBack by remember { mutableStateOf<(() -> Unit)?>(null) }

    val workspacePickerFactory = remember(app.container.runtimeIntegration) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(WorkspacePickerViewModel::class.java))
                return WorkspacePickerViewModel(app, app.container.runtimeIntegration) as T
            }
        }
    }
    val workspacePickerVm: WorkspacePickerViewModel = viewModel(
        viewModelStoreOwner = shellOwner,
        factory = workspacePickerFactory,
    )
    val workspacePickerState by workspacePickerVm.state.collectAsState()

    val workspaceSafLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val uri = data?.data
        if (uri != null) {
            workspacePickerVm.onSafUriSelected(uri, data.flags)
        }
    }

    fun launchWorkspaceSaf() {
        workspaceSafLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            },
        )
    }

    fun closeWorkspacePicker() {
        workspacePickerOpen = false
        workspacePickerVm.clearResult()
    }

    fun openWorkspacePicker(agentId: String?, threadId: String?, label: String) {
        workspacePickerAgentId = agentId?.takeIf { it.isNotBlank() }
        workspacePickerThreadId = threadId?.takeIf { it.isNotBlank() }
        workspacePickerLabel = label.trim().ifBlank { "当前智能体" }.take(128)
        drawerOpen = false
        workspacePickerOpen = true
    }

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
        shellDetailOwner = null
        shellDetailTitle = null
        shellDetailBack = null
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

    fun registerShellDetail(owner: String, title: String?, onBack: (() -> Unit)?) {
        if (owner != route) return
        shellDetailOwner = owner.takeIf { title != null && onBack != null }
        shellDetailTitle = title
        shellDetailBack = onBack
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

    LaunchedEffect(workspacePickerOpen, workspacePickerAgentId, workspacePickerThreadId, workspacePickerLabel) {
        if (workspacePickerOpen) {
            workspacePickerVm.setTarget(
                WorkspacePickerTarget(
                    agentId = workspacePickerAgentId,
                    threadId = workspacePickerThreadId,
                ),
                workspacePickerLabel,
            )
            workspacePickerVm.refresh()
        }
    }
    LaunchedEffect(workspacePickerOpen, workspacePickerState.attachPhase) {
        if (workspacePickerOpen && workspacePickerState.attachPhase == WorkspacePickerAttachPhaseUi.SUCCESS) {
            closeWorkspacePicker()
        }
    }

    val drawerDestinations = globalDrawerDestinations(chinese)
    val drawerDestinationUi = drawerDestinations.map { destination ->
        runtime.mobileagent.feature.chat.ChatDrawerDestinationUi(destination.route, destination.label)
    }
    val drawerChatState = chatVm.state.value.copy(
        language = if (chinese) "zh-CN" else "en-US",
        drawerDestinations = drawerDestinationUi,
    )
    val drawerSelectedAgentLabel = drawerChatState.agents
        .firstOrNull { it.id == drawerChatState.selectedAgentId }
        ?.label
        ?: if (chinese) "当前智能体" else "Current Agent"
    val drawerActions = runtime.mobileagent.feature.chat.ChatActions(
        onSelectSession = { sessionId ->
            chatVm.selectSession(sessionId)
            drawerOpen = false
            requestRoute(AppRoutes.CHAT)
        },
        onNewSession = {
            chatVm.newSession()
            drawerOpen = false
            requestRoute(AppRoutes.CHAT)
        },
        onNewSessionForAgent = { agentId ->
            chatVm.selectAgent(agentId)
            chatVm.newSession()
            drawerOpen = false
            requestRoute(AppRoutes.CHAT)
        },
        onSelectAgent = { agentId ->
            chatVm.selectAgent(agentId)
            drawerOpen = false
            requestRoute(AppRoutes.CHAT)
        },
        onNewSessionForWorkspace = { workspaceId ->
            chatVm.newSession(workspaceId)
            drawerOpen = false
            requestRoute(AppRoutes.CHAT)
        },
        onOpenWorkspacePicker = {
            openWorkspacePicker(
                drawerChatState.selectedAgentId,
                drawerChatState.selectedSessionId,
                drawerSelectedAgentLabel,
            )
        },
        onAuthorizeWorkspaceForAgent = { agentId ->
            val label = drawerChatState.agents.firstOrNull { it.id == agentId }?.label
                ?: if (chinese) "当前智能体" else "Current Agent"
            openWorkspacePicker(agentId, null, label)
        },
        onOpenAgentSettings = { agentId ->
            pendingAgentId = agentId
            drawerOpen = false
            requestRoute(AppRoutes.AGENTS)
        },
    )

    BackHandler(enabled = editorOwner == route && editorDirty) { requestEditorClose() }
    MobileAgentTheme(mode = themeMode) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            val shellDetailOpen = shellDetailOwner == route && shellDetailTitle != null && shellDetailBack != null
            val navigationAffordance = shellNavigationAffordance(
                route = route,
                childDetailOpen = workspacePickerOpen || shellDetailOpen,
            )
            BackHandler(
                    enabled = !(editorOwner == route && editorDirty) &&
                        (workspacePickerOpen || shellDetailOpen || route != AppRoutes.CHAT || navController.previousBackStackEntry != null),
            ) {
                when {
                    workspacePickerOpen -> closeWorkspacePicker()
                    shellDetailOpen -> shellDetailBack?.invoke()
                    else -> handleBack(compact)
                }
            }
            val destinations = drawerDestinations
            AppNavigationScaffold(
                destinations = destinations,
                selectedRoute = route,
                title = shellDetailTitle.takeIf { shellDetailOpen } ?: appShellTitle(
                    route = route,
                    chinese = chinese,
                    childDetailOpen = workspacePickerOpen,
                ),
                navigationAffordance = navigationAffordance,
                onBack = {
                    when {
                        workspacePickerOpen -> closeWorkspacePicker()
                        shellDetailOpen -> shellDetailBack?.invoke()
                        route == AppRoutes.MCP -> requestRoute(if (compact) AppRoutes.MORE else mcpReturnRoute)
                        else -> handleBack(compact)
                    }
                },
                navigationBackLabel = if (chinese) "返回" else "Back",
                drawerOpen = drawerOpen,
                onDrawerOpenChange = { drawerOpen = it },
                // The shell owns the sole leading action. The legacy flag is
                // retained for compatibility but no longer controls a second
                // compact overlay button.
                showCompactMenuButton = false,
                compactMenuButtonLabel = if (chinese) "打开菜单" else "Open menu",
                consumeBottomSystemInsets = route != AppRoutes.CHAT,
                drawerContent = { close ->
                    runtime.mobileagent.feature.chat.GlobalDrawerContent(
                        state = drawerChatState,
                        actions = drawerActions,
                        destinations = drawerDestinationUi,
                        selectedRoute = route,
                        onNavigate = ::requestRoute,
                        onClose = close,
                    )
                },
                onRouteSelected = { target ->
                    requestRoute(target)
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    NavHost(navController = navController, startDestination = initialRoute) {
                        composable(AppRoutes.CHAT) {
                            ChatRoute(
                                chatVm,
                                chinese,
                                ::requestRoute,
                                ::registerEditorState,
                                onOpenDrawer = { drawerOpen = true },
                                onOpenWorkspacePicker = { agentId, threadId, label ->
                                    openWorkspacePicker(agentId, threadId, label)
                                },
                                onOpenAgentSettings = { agentId ->
                                    pendingAgentId = agentId
                                    requestRoute(AppRoutes.AGENTS)
                                },
                            )
                        }
                        composable(AppRoutes.AGENTS) {
                            val targetAgentId = pendingAgentId
                            AgentsRoute(
                                it,
                                chinese,
                                ::requestRoute,
                                ::requestEditorClose,
                                ::registerEditorState,
                                ::registerShellDetail,
                                compact,
                                targetAgentId,
                                onInitialAgentConsumed = { pendingAgentId = null },
                            )
                        }
                        composable(AppRoutes.PROVIDERS) {
                            ProvidersRoute(it, chinese, ::requestRoute, ::requestEditorClose, ::registerEditorState,
                                ::registerShellDetail,
                                { mcpReturnRoute = AppRoutes.PROVIDERS; requestRoute(AppRoutes.MCP) },
                                navigationAffordance == ShellNavigationAffordance.BACK,
                                { handleBack(compact) })
                        }
                        composable(AppRoutes.KNOWLEDGE) { KnowledgeRoute(knowledgeVm, chinese, ::requestRoute) }
                        composable(AppRoutes.SKILLS) { SkillsRoute(it, chinese) }
                        composable(AppRoutes.NEWS) {
                            AnnouncementsRoute(
                                it,
                                chinese,
                                navigationAffordance == ShellNavigationAffordance.BACK,
                                { handleBack(compact) },
                            ) { appRoute ->
                                if (appRoute == "app://update") pendingUpdateCheck = true
                                requestRoute(routeFromAnnouncement(appRoute))
                            }
                        }
                        composable(AppRoutes.SETTINGS) {
                            SettingsRoute(it, chinese, ::requestRoute,
                                { mcpReturnRoute = AppRoutes.SETTINGS; requestRoute(AppRoutes.MCP) },
                                { settingsRevision++ }, autoCheckUpdate = pendingUpdateCheck,
                                onAutoCheckConsumed = { pendingUpdateCheck = false },
                                showBack = navigationAffordance == ShellNavigationAffordance.BACK,
                                onBack = { handleBack(compact) })
                        }
                        composable(AppRoutes.ABOUT) {
                            SettingsRoute(it, chinese, ::requestRoute,
                                { mcpReturnRoute = AppRoutes.ABOUT; requestRoute(AppRoutes.MCP) },
                                { settingsRevision++ }, aboutOnly = true,
                                showBack = navigationAffordance == ShellNavigationAffordance.BACK,
                                onBack = { handleBack(compact) })
                        }
                        composable(AppRoutes.MORE) { MoreHub(chinese, ::requestRoute, showPageTitle = false) }
                        composable(AppRoutes.MCP) { McpRoute(it, chinese, if (compact) AppRoutes.MORE else mcpReturnRoute, ::requestRoute) }
                        composable(AppRoutes.INSPECTOR) {
                            InspectorRoute(
                                vm = chatVm,
                                chinese = chinese,
                                showBack = navigationAffordance == ShellNavigationAffordance.BACK,
                                inspectorEnabled = app.container.uiPreferences.getBoolean("request-inspector", true),
                                onBack = { handleBack(compact) },
                            )
                        }
                    }
                    if (workspacePickerOpen) {
                        BackHandler { closeWorkspacePicker() }
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("global.workspace.picker"),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            WorkspacePickerScreen(
                                state = workspacePickerState,
                                actions = WorkspacePickerActions(
                                    onRefresh = workspacePickerVm::refresh,
                                    onOpenLocation = workspacePickerVm::openLocation,
                                    onOpenBreadcrumb = workspacePickerVm::openBreadcrumb,
                                    onOpenEntry = workspacePickerVm::openEntry,
                                    onGoParent = workspacePickerVm::goParent,
                                    onUseCurrentDirectory = workspacePickerVm::useCurrentDirectory,
                                    onUseSafFallback = {
                                        workspacePickerVm.chooseSafFallback()
                                        launchWorkspaceSaf()
                                    },
                                    onOpenRecent = workspacePickerVm::openRecent,
                                ),
                                modifier = Modifier.fillMaxSize(),
                                showPageTitle = false,
                            )
                        }
                    }
                }
            }
        }
        workspacePickerState.pendingNewThread?.let { pending ->
            AlertDialog(
                onDismissRequest = workspacePickerVm::clearResult,
                title = { Text(if (chinese) "切换工作区" else "Switch workspace") },
                text = {
                    Text(
                        if (chinese) {
                            if (pending.requiresGrantCommit) {
                                "工作区属于当前会话上下文，切换将创建新会话并授权该智能体访问此工作区。"
                            } else {
                                "工作区属于当前会话上下文，切换将创建新会话。"
                            }
                        } else {
                            if (pending.requiresGrantCommit) {
                                "This workspace belongs to the current conversation. Switching creates a new conversation and authorizes this Agent."
                            } else {
                                "This workspace belongs to the current conversation. Switching creates a new conversation."
                            }
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            workspacePickerVm.confirmNewThread(
                                pending = pending,
                                onConfirmed = { confirmedWorkspaceId ->
                                    chatVm.selectAgent(pending.agentId)
                                    chatVm.newSession(confirmedWorkspaceId)
                                    closeWorkspacePicker()
                                    drawerOpen = false
                                    requestRoute(AppRoutes.CHAT)
                                },
                            )
                        },
                    ) { Text(if (chinese) "创建新会话" else "Create conversation") }
                },
                dismissButton = {
                    TextButton(onClick = workspacePickerVm::clearResult) {
                        Text(if (chinese) "取消" else "Cancel")
                    }
                },
            )
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
private fun MoreHub(chinese: Boolean, onOpen: (String) -> Unit, showPageTitle: Boolean = true) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showPageTitle) {
            Text(if (chinese) "更多" else "More", style = MaterialTheme.typography.headlineSmall)
        }
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
    onEditorState: (String, Boolean, (() -> Unit)?) -> Unit,
    onOpenDrawer: () -> Unit = {},
    onOpenWorkspacePicker: (String?, String?, String) -> Unit = { _, _, _ -> },
    onOpenAgentSettings: (String?) -> Unit = {}) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    val state = vm.state.value.copy(language = if (chinese) "zh-CN" else "en-US")
    val selectedAgentLabel = state.agents.firstOrNull { it.id == state.selectedAgentId }?.label
        ?: if (chinese) "当前智能体" else "Current Agent"
    val actions = runtime.mobileagent.feature.chat.ChatActions(
        onInput = vm::input, onSend = vm::send, onCancel = vm::cancel,
        onToggleDegradation = vm::degrade, onSelectSession = vm::selectSession,
        onNewSession = { vm.newSession() }, onSelectAgent = vm::selectAgent,
        onNewSessionForAgent = { agentId -> vm.selectAgent(agentId); vm.newSession(); Unit },
        onNewSessionForWorkspace = { workspaceId -> vm.newSession(workspaceId) },
        onOpenCitation = vm::openCitation, onCloseCitation = vm::closeCitation,
        onToolApproval = { choice -> vm.approveTool(choice == runtime.mobileagent.feature.chat.ToolApprovalChoice.APPROVE) },
        onOpenRequestInspector = { vm.inspector(true); onRoute(AppRoutes.INSPECTOR) },
        onCloseRequestInspector = { vm.inspector(false); onRoute(AppRoutes.CHAT) },
        onOpenWorkspacePicker = {
            onOpenWorkspacePicker(state.selectedAgentId, state.selectedSessionId, selectedAgentLabel)
        },
        onAuthorizeWorkspaceForAgent = { agentId ->
            val label = state.agents.firstOrNull { it.id == agentId }?.label ?: selectedAgentLabel
            onOpenWorkspacePicker(agentId, null, label)
        },
        onOpenAgentSettings = onOpenAgentSettings,
    )
    runtime.mobileagent.feature.chat.ConversationScreen(
        state = state,
        actions = actions,
        onOpenDrawer = onOpenDrawer,
        showGlobalMenu = false,
        onOpenWorkspace = {
            onOpenWorkspacePicker(state.selectedAgentId, state.selectedSessionId, selectedAgentLabel)
        },
    )
    vm.unknownRetry.value?.let { UnknownOutcomeDialog(chinese, vm::acknowledgeUnknown, vm::cancelUnknownRetry) }
    LaunchedEffect(Unit) { onEditorState(AppRoutes.CHAT, false, null) }
}

@Composable
private fun AgentsRoute(entry: NavBackStackEntry, chinese: Boolean, onRoute: (String) -> Unit,
    onRequestEditorClose: () -> Unit, onEditorState: (String, Boolean, (() -> Unit)?) -> Unit,
    onShellDetail: (String, String?, (() -> Unit)?) -> Unit, compact: Boolean,
    initialAgentId: String? = null, onInitialAgentConsumed: () -> Unit = {}) {
    val vm: runtime.mobileagent.AgentsViewModel = viewModel(viewModelStoreOwner = entry)
    var detailOpen by rememberSaveable { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as MobileAgentApp
    val integration = app.container.runtimeIntegration
    val workspacePort = integration.workspaceAccessPort
    // The one canonical write seam for workspace selection.  This screen no
    // longer calls attach/grant/default directly; every selection resolves a
    // WorkspaceIntent + WorkspaceTarget here and the coordinator derives grant,
    // Agent default and (never here) Thread binding from that plan.
    val workspaceCoordinator = remember(integration) { CanonicalWorkspaceCoordinator(integration) }
    val coroutineScope = rememberCoroutineScope()
    var workspaceRevision by remember { mutableIntStateOf(0) }
    var workspaceBusy by remember { mutableStateOf(false) }
    var workspaceStatus by remember { mutableStateOf("") }
    var browserPage by remember { mutableStateOf<runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage?>(null) }
    var browserTrail by remember { mutableStateOf<List<String>>(emptyList()) }
    var browserOpen by remember { mutableStateOf(false) }
    var wiredPathOpen by remember { mutableStateOf(false) }
    var wiredPath by remember { mutableStateOf("") }
    var confirmFullDevice by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        integration.refresh()
        workspaceRevision++
        vm.reload()
    }
    LaunchedEffect(initialAgentId) {
        initialAgentId?.takeIf { it.isNotBlank() }?.let {
            vm.select(it)
            detailOpen = true
            vm.openEditor(it)
            onInitialAgentConsumed()
        }
    }
    val baseState = vm.state.value.copy(language = if (chinese) "zh-CN" else "en-US")
    val editorAgentId = baseState.editor?.id
    val authoritySnapshot = remember(editorAgentId, workspaceRevision) { integration.snapshot() }
    val workspaceLoad = remember(editorAgentId, workspaceRevision) {
        runCatching { workspacePort.listWorkspaces(editorAgentId) }
    }
    val workspaceItems = workspaceLoad.getOrDefault(emptyList())
    val selectedWorkspace = baseState.editor?.defaultWorkspaceId?.let { defaultId ->
        workspaceItems.firstOrNull { it.workspaceId == defaultId }
    } ?: selectDurablyAuthorizedWorkspace(
        workspaceItems,
        runtime.mobileagent.domain.WorkspaceScope.SELECTED_DIRECTORY,
    )
    val fullDeviceWorkspace = selectDurablyAuthorizedWorkspace(
        workspaceItems,
        runtime.mobileagent.domain.WorkspaceScope.FULL_DEVICE_FILES,
    )
    val selectedAuthority = authoritySnapshot.selectedAuthority
    val selectedProvider = when (selectedAuthority) {
        runtime.mobileagent.domain.Authority.SHIZUKU -> authoritySnapshot.shizuku
        runtime.mobileagent.domain.Authority.WIRED_ADB -> authoritySnapshot.wiredAdb
        runtime.mobileagent.domain.Authority.NONE -> null
    }
    val authorityReady = selectedProvider?.let { provider ->
        provider.configured &&
            provider.platformGrant == runtime.mobileagent.skills.tooling.PlatformGrant.GRANTED &&
            provider.availability == runtime.mobileagent.skills.tooling.Availability.READY &&
            provider.connection == runtime.mobileagent.skills.tooling.Connection.CONNECTED
    } == true
    val pendingDraft = vm.pendingWorkspaceDraft()
    val selectedPresentation = selectedWorkspace?.let { integration.workspaceUiPresentation(it.workspaceId, chinese) }
    val workspaceAccess = runtime.mobileagent.feature.agents.AgentWorkspaceAccessUi(
        selectedWorkspaceName = pendingDraft?.displayName
            ?: selectedPresentation?.title
            ?: selectedWorkspace?.displayName,
        selectedBackendLabel = selectedWorkspace?.let { workspaceBackendLabel(it.backendType, it.authority, chinese) }
            ?: pendingDraft?.let { if (chinese) "待保存" else "Pending save" },
        availableWorkspaceCount = workspaceItems.count { item ->
            item.status != runtime.mobileagent.integration.WorkspaceAccessStatus.REVOKED &&
                item.status != runtime.mobileagent.integration.WorkspaceAccessStatus.DISABLED
        },
        canChooseSaf = !workspaceBusy,
        canBrowsePrivileged = authorityReady && !workspaceBusy,
        fullDeviceFilesEnabled = fullDeviceWorkspace != null,
        fullDeviceFilesEligible = authorityReady &&
            authoritySnapshot.dangerousModeBuildAllowed &&
            authoritySnapshot.dangerousMode != runtime.mobileagent.domain.DangerousMode.DISABLED && !workspaceBusy,
        status = when {
            workspaceBusy -> if (chinese) "正在更新工作区…" else "Updating workspace…"
            workspaceStatus.isNotBlank() -> workspaceStatus
            workspaceLoad.isFailure -> if (chinese) "读取工作区失败。" else "Unable to read workspaces."
            fullDeviceWorkspace != null && !authorityReady -> if (chinese) {
                "完整设备访问授权已保留；当前连接不可用，连接恢复后继续生效。"
            } else {
                "Full-device access remains authorized; it resumes when the selected connection returns."
            }
            selectedAuthority == runtime.mobileagent.domain.Authority.NONE -> if (chinese) {
                "可直接选择手机文件夹；ADB 级目录需先在设置中选择并连接通道。"
            } else {
                "You can select a phone folder now. Choose and connect an ADB-level channel in Settings for privileged directories."
            }
            !authorityReady -> if (chinese) {
                "ADB 级授权保持不变，但当前连接不可用。"
            } else {
                "ADB-level authorization is retained, but the current connection is unavailable."
            }
            else -> ""
        },
    )
    val state = baseState.copy(
        editor = baseState.editor?.copy(workspaceAccess = workspaceAccess),
        workspaceSelectionBusy = workspaceBusy,
    )
    val discard = remember(vm) { { vm.closeEditor() } }
    LaunchedEffect(state.editorDirty) { onEditorState(AppRoutes.AGENTS, state.editorDirty, discard) }
    LaunchedEffect(state.editor != null, state.editor?.id, detailOpen, compact, chinese) {
        val editor = state.editor
        when {
            editor != null -> onShellDetail(
                AppRoutes.AGENTS,
                if (editor.id == null) {
                    if (chinese) "新建智能体" else "New agent"
                } else {
                    if (chinese) "编辑智能体" else "Edit agent"
                },
                onRequestEditorClose,
            )
            compact && detailOpen && state.summary != null -> onShellDetail(
                AppRoutes.AGENTS,
                if (chinese) "智能体详情" else "Agent details",
                { detailOpen = false },
            )
            else -> onShellDetail(AppRoutes.AGENTS, null, null)
        }
    }

    fun completeWorkspaceOperation(result: runtime.mobileagent.integration.WorkspaceAccessResult) {
        workspaceStatus = workspaceAccessResultMessage(result, chinese)
        workspaceRevision++
        vm.refreshWorkspaceConfiguration()
    }

    fun completeWorkspaceSelection(outcome: WorkspaceSelectionOutcome, expectedEditorSessionToken: Long) {
        when (outcome) {
            is WorkspaceSelectionOutcome.Committed -> {
                workspaceStatus = if (chinese) "已添加工作区并设为默认；新会话将使用该工作区。" else "Workspace added and set as default; new conversations use it."
                workspaceRevision++
                vm.refreshWorkspaceConfiguration()
            }
            is WorkspaceSelectionOutcome.Staged -> {
                // The Agent does not exist yet.  Keep the draft; it is committed
                // atomically when the Agent is saved, and dropped on cancel.
                if (vm.stageWorkspaceDraft(outcome.draft, expectedEditorSessionToken)) {
                    workspaceStatus = if (chinese) "已选择工作区；保存 Agent 后生效。" else "Workspace selected; it takes effect when the Agent is saved."
                    workspaceRevision++
                }
            }
            is WorkspaceSelectionOutcome.Failed -> {
                workspaceStatus = workspaceAccessResultMessage(
                    runtime.mobileagent.integration.WorkspaceAccessResult.Failure(outcome.code),
                    chinese,
                )
                workspaceRevision++
                vm.refreshWorkspaceConfiguration()
            }
        }
    }

    fun launchWorkspaceSelection(block: suspend () -> WorkspaceSelectionOutcome) {
        val expectedEditorSessionToken = vm.editorSessionToken()
        coroutineScope.launch {
            workspaceBusy = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse {
                    WorkspaceSelectionOutcome.Failed(
                        runtime.mobileagent.integration.WorkspaceAccessErrorCode.UNKNOWN_OUTCOME,
                    )
                }
            }
            workspaceBusy = false
            completeWorkspaceSelection(outcome, expectedEditorSessionToken)
        }
    }

    fun launchWorkspaceOperation(block: suspend () -> runtime.mobileagent.integration.WorkspaceAccessResult) {
        coroutineScope.launch {
            workspaceBusy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse {
                    runtime.mobileagent.integration.WorkspaceAccessResult.Failure(
                        runtime.mobileagent.integration.WorkspaceAccessErrorCode.UNKNOWN_OUTCOME,
                    )
                }
            }
            workspaceBusy = false
            completeWorkspaceOperation(result)
        }
    }

    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // A draft Agent (no id yet) selects with a bare target so the
            // coordinator stages the selection for commit on save.
            val target = editorAgentId?.let { WorkspaceTarget(agentId = it) } ?: WorkspaceTarget()
            launchWorkspaceSelection {
                workspaceCoordinator.selectSaf(
                    intent = WorkspaceIntent.SET_AGENT_DEFAULT,
                    uri = uri,
                    target = target,
                )
            }
        }
    }

    fun openPrivilegedWorkspace() {
        if (!authorityReady) {
            workspaceStatus = if (chinese) "请先在设置中连接并选定 ADB 级通道。" else "Connect and select an ADB-level authority in Settings first."
            return
        }
        if (selectedAuthority == runtime.mobileagent.domain.Authority.WIRED_ADB) {
            wiredPath = ""
            wiredPathOpen = true
            return
        }
        coroutineScope.launch {
            workspaceBusy = true
            when (val result = withContext(Dispatchers.IO) {
                workspacePort.browsePrivilegedRoot(selectedAuthority)
            }) {
                is runtime.mobileagent.skills.tooling.WorkspaceResult.Success -> {
                    browserPage = result.value
                    browserTrail = emptyList()
                    browserOpen = true
                    workspaceStatus = ""
                }
                is runtime.mobileagent.skills.tooling.WorkspaceResult.Failure -> {
                    workspaceStatus = workspaceToolErrorMessage(result.error.code, chinese)
                }
            }
            workspaceBusy = false
        }
    }

    val actions = runtime.mobileagent.feature.agents.AgentsActions(
        onQuery = vm::query,
        onSelectAgent = { id -> vm.select(id); detailOpen = true },
        onOpenEditor = { id -> detailOpen = id != null; vm.openEditor(id) },
        onCloseEditor = onRequestEditorClose, onEditorChange = vm::edit,
        onSave = {
            if (workspaceBusy) {
                workspaceStatus = if (chinese) "请等待工作区选择完成。" else "Wait for workspace selection to finish."
            } else {
                vm.save()
            }
        },
        onSavePromptRevision = {
            if (workspaceBusy) {
                workspaceStatus = if (chinese) "请等待工作区选择完成。" else "Wait for workspace selection to finish."
            } else {
                vm.save()
            }
        },
        onRestorePrompt = vm::restorePrompt, onToggleResource = vm::toggleResource,
        onSnapshot = { if (vm.createConversation() != null) onRoute(AppRoutes.CHAT) },
        onSetDefaultWorkspace = { workspaceId ->
            if (workspaceId == null) {
                vm.clearWorkspaceDraft()
                vm.state.value.editor?.let { vm.edit(it.copy(defaultWorkspaceId = null)) }
                workspaceStatus = if (chinese) {
                    "保存后，新会话将明确保持无工作区；已有会话不变。"
                } else {
                    "After saving, new conversations stay explicitly unbound; existing conversations remain unchanged."
                }
            } else {
                val target = editorAgentId?.let { WorkspaceTarget(agentId = it) } ?: WorkspaceTarget()
                launchWorkspaceSelection {
                    workspaceCoordinator.selectRecent(
                        intent = WorkspaceIntent.SET_AGENT_DEFAULT,
                        workspaceId = workspaceId,
                        target = target,
                    )
                }
            }
        },
        onChooseSafWorkspace = { safLauncher.launch(null) },
        onBrowsePrivilegedWorkspace = ::openPrivilegedWorkspace,
        onToggleFullDeviceFiles = { enabled ->
            if (enabled) {
                confirmFullDevice = true
            } else {
                val full = fullDeviceWorkspace
                val revision = full?.let { workspacePort.fullDeviceFilesGrantRevision(it.workspaceId) }
                if (full != null && revision != null) {
                    launchWorkspaceOperation {
                        workspacePort.revokeFullDeviceFiles(full.authority ?: selectedAuthority, full.workspaceId, revision)
                    }
                }
            }
        },
    )
    runtime.mobileagent.feature.agents.AgentsScreen(
        state,
        actions,
        showPageTitle = false,
        compactPane = if (compact) {
            if (detailOpen) runtime.mobileagent.feature.agents.AgentCompactPane.DETAIL
            else runtime.mobileagent.feature.agents.AgentCompactPane.LIST
        } else {
            runtime.mobileagent.feature.agents.AgentCompactPane.BOTH
        },
        renderEditorAsPage = true,
    )

    if (browserOpen) {
        PrivilegedWorkspaceBrowserDialog(
            page = browserPage,
            currentLabel = browserTrail.lastOrNull() ?: if (chinese) "设备根目录" else "Device root",
            busy = workspaceBusy,
            chinese = chinese,
            onOpenDirectory = { entry ->
                val handle = entry.handle ?: return@PrivilegedWorkspaceBrowserDialog
                coroutineScope.launch {
                    workspaceBusy = true
                    when (val result = withContext(Dispatchers.IO) {
                        workspacePort.browsePrivileged(
                            selectedAuthority,
                            runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest(handle),
                        )
                    }) {
                        is runtime.mobileagent.skills.tooling.WorkspaceResult.Success -> {
                            browserPage = result.value
                            browserTrail = browserTrail + entry.name
                        }
                        is runtime.mobileagent.skills.tooling.WorkspaceResult.Failure -> {
                            workspaceStatus = workspaceToolErrorMessage(result.error.code, chinese)
                        }
                    }
                    workspaceBusy = false
                }
            },
            onUp = {
                val parent = browserPage?.parent ?: return@PrivilegedWorkspaceBrowserDialog
                coroutineScope.launch {
                    workspaceBusy = true
                    when (val result = withContext(Dispatchers.IO) {
                        workspacePort.browsePrivileged(
                            selectedAuthority,
                            runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest(parent),
                        )
                    }) {
                        is runtime.mobileagent.skills.tooling.WorkspaceResult.Success -> {
                            browserPage = result.value
                            browserTrail = browserTrail.dropLast(1)
                        }
                        is runtime.mobileagent.skills.tooling.WorkspaceResult.Failure -> {
                            workspaceStatus = workspaceToolErrorMessage(result.error.code, chinese)
                        }
                    }
                    workspaceBusy = false
                }
            },
            onAttach = {
                val page = browserPage ?: return@PrivilegedWorkspaceBrowserDialog
                if (browserTrail.isEmpty()) {
                    workspaceStatus = if (chinese) "设备根目录只能通过“完整设备文件”授权。" else "Device root requires Full device files authorization."
                } else {
                    browserOpen = false
                    val workspaceId = newWorkspaceId("device")
                    val target = editorAgentId?.let { WorkspaceTarget(agentId = it) } ?: WorkspaceTarget()
                    launchWorkspaceSelection {
                        workspaceCoordinator.selectPrivileged(
                            intent = WorkspaceIntent.SET_AGENT_DEFAULT,
                            authority = selectedAuthority,
                            request = runtime.mobileagent.skills.tooling.WorkspaceAttachRequest(
                                workspaceId = workspaceId,
                                displayName = "/" + browserTrail.joinToString("/"),
                                directory = page.current,
                            ),
                            target = target,
                        )
                    }
                }
            },
            onClose = { browserOpen = false },
        )
    }

    if (wiredPathOpen) {
        WiredPathDialog(
            value = wiredPath,
            chinese = chinese,
            onValue = { wiredPath = it },
            onConfirm = {
                val path = wiredPath
                wiredPathOpen = false
                val target = editorAgentId?.let { WorkspaceTarget(agentId = it) } ?: WorkspaceTarget()
                launchWorkspaceSelection {
                    workspaceCoordinator.selectPrivilegedPath(
                        intent = WorkspaceIntent.SET_AGENT_DEFAULT,
                        authority = runtime.mobileagent.domain.Authority.WIRED_ADB,
                        workspaceId = newWorkspaceId("wired"),
                        displayName = path,
                        absolutePath = path,
                        target = target,
                    )
                }
            },
            onClose = { wiredPathOpen = false },
        )
    }

    if (confirmFullDevice) {
        FullDeviceFilesConfirmationDialog(
            chinese = chinese,
            onConfirm = {
                confirmFullDevice = false
                val agentId = editorAgentId ?: return@FullDeviceFilesConfirmationDialog
                val workspaceId = agentFullDeviceWorkspaceId(agentId, selectedAuthority)
                val currentRevision = workspacePort.fullDeviceFilesGrantRevision(workspaceId)
                val nextRevision = (currentRevision ?: 0L) + 1L
                launchWorkspaceSelection {
                    workspaceCoordinator.openFullDeviceFiles(
                        authority = selectedAuthority,
                        request = runtime.mobileagent.skills.tooling.FullDeviceFilesRequest(
                            workspaceId = workspaceId,
                            displayName = if (chinese) "完整设备文件" else "Full device files",
                            grantRevision = nextRevision,
                            confirmedByUser = true,
                        ),
                        target = WorkspaceTarget(agentId = agentId),
                    )
                }
            },
            onClose = { confirmFullDevice = false },
        )
    }
}

/**
 * Selects persisted Agent configuration without conflating it with current
 * transport readiness. Disconnecting Shizuku or Wired ADB must never make a
 * granted workspace disappear from the editor or remove its revoke control.
 */
internal fun selectDurablyAuthorizedWorkspace(
    workspaces: List<runtime.mobileagent.integration.WorkspaceAccessItem>,
    scope: runtime.mobileagent.domain.WorkspaceScope,
): runtime.mobileagent.integration.WorkspaceAccessItem? = workspaces
    .filter { item ->
        item.scope == scope &&
            item.durablyAuthorized &&
            item.grantedCapabilities.isNotEmpty()
    }
    .maxByOrNull { it.grantRevision ?: 0L }

@Composable
private fun PrivilegedWorkspaceBrowserDialog(
    page: runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage?,
    currentLabel: String,
    busy: Boolean,
    chinese: Boolean,
    onOpenDirectory: (runtime.mobileagent.skills.tooling.WorkspaceDirectoryEntry) -> Unit,
    onUp: () -> Unit,
    onAttach: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("agents.workspace.browser"),
        title = { Text(if (chinese) "选择设备目录" else "Choose device directory") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (chinese) "当前位置：$currentLabel" else "Current: $currentLabel",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (chinese) {
                        "目录名称只在本机界面显示；模型只会得到选中后的工作区标识和相对路径。"
                    } else {
                        "Directory names stay in the local UI; the model receives only the attached workspace and relative paths."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (busy) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                } else {
                    page?.entries.orEmpty()
                        .filter { it.type == runtime.mobileagent.skills.tooling.WorkspaceEntryType.DIRECTORY }
                        .forEach { entry ->
                            Card(
                                Modifier.fillMaxWidth().clickable(enabled = entry.handle != null) { onOpenDirectory(entry) },
                            ) {
                                Text(entry.name, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    if (page?.entries.orEmpty().none { it.type == runtime.mobileagent.skills.tooling.WorkspaceEntryType.DIRECTORY }) {
                        Text(if (chinese) "此处没有可进入的子目录。" else "No child directories are available here.")
                    }
                    if (page?.truncated == true) {
                        Text(if (chinese) "目录过多，仅显示前一部分。" else "Only the first part of this directory is shown.")
                    }
                }
                if (page?.parent != null) {
                    TextButton(onClick = onUp, enabled = !busy) {
                        Text(if (chinese) "返回上一级" else "Up one level")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAttach, enabled = page != null && !busy) {
                Text(if (chinese) "使用当前目录" else "Use this directory")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

@Composable
private fun WiredPathDialog(
    value: String,
    chinese: Boolean,
    onValue: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("agents.workspace.wired_path"),
        title = { Text(if (chinese) "输入 ADB 设备目录" else "Enter an ADB device directory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (chinese) {
                        "请输入由你确认的绝对目录，例如 /sdcard/Download。路径只用于本次前台绑定，不会进入模型、日志或工具参数。"
                    } else {
                        "Enter an absolute directory you verified, such as /sdcard/Download. It is consumed only for this foreground binding and is not exposed to the model or logs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    label = { Text(if (chinese) "设备绝对目录" else "Absolute device directory") },
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = value.startsWith("/") && value != "/") {
                Text(if (chinese) "绑定" else "Attach")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

@Composable
private fun FullDeviceFilesConfirmationDialog(
    chinese: Boolean,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("agents.workspace.full_device_confirm"),
        title = { Text(if (chinese) "开启完整设备文件访问？" else "Enable full-device file access?") },
        text = {
            Text(
                if (chinese) {
                    "此授权允许该智能体在当前工作区之外访问所选 ADB 级通道实际可见的文件。它不等于 Root，并会在断网、电脑离线或服务暂时断开时保留，直到你主动关闭或底层授权真正失效。"
                } else {
                    "This lets the Agent access files outside its current workspace that the selected ADB-level authority can actually see. It is not Root and remains authorized through temporary disconnects until you revoke it or the underlying grant is lost."
                },
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text(if (chinese) "确认开启" else "Enable") } },
        dismissButton = { TextButton(onClick = onClose) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

private fun workspaceBackendLabel(
    backend: runtime.mobileagent.domain.WorkspaceBackendType,
    authority: runtime.mobileagent.domain.Authority?,
    chinese: Boolean,
): String = when (backend) {
    runtime.mobileagent.domain.WorkspaceBackendType.SAF_TREE -> if (chinese) "手机文件夹（SAF）" else "Phone folder (SAF)"
    runtime.mobileagent.domain.WorkspaceBackendType.PRIVILEGED -> when (authority) {
        runtime.mobileagent.domain.Authority.SHIZUKU -> if (chinese) "Shizuku（ADB 级）" else "Shizuku (ADB-level)"
        runtime.mobileagent.domain.Authority.WIRED_ADB -> if (chinese) "电脑 ADB" else "Desktop ADB"
        else -> if (chinese) "ADB 级目录" else "ADB-level directory"
    }
    runtime.mobileagent.domain.WorkspaceBackendType.INTERNAL -> if (chinese) "应用私有目录" else "App-private directory"
}

private fun workspaceAccessResultMessage(
    result: runtime.mobileagent.integration.WorkspaceAccessResult,
    chinese: Boolean,
): String = when (result) {
    is runtime.mobileagent.integration.WorkspaceAccessResult.Success -> if (chinese) {
        "工作区授权已保存；此智能体的所有会话将在下一次运行时使用最新权限。"
    } else {
        "Workspace access is saved; every session of this Agent uses the latest permissions on its next run."
    }
    is runtime.mobileagent.integration.WorkspaceAccessResult.NewThreadRequired -> if (chinese) {
        "工作区属于当前会话上下文，切换将创建新会话。"
    } else {
        "This workspace belongs to the current conversation. Switching creates a new conversation."
    }
    is runtime.mobileagent.integration.WorkspaceAccessResult.Failure ->
        workspaceAccessFailureMessage(result.code, chinese)
}

internal fun workspaceToolErrorMessage(
    code: runtime.mobileagent.skills.tooling.ToolErrorCode,
    chinese: Boolean,
): String = when (code) {
    runtime.mobileagent.skills.tooling.ToolErrorCode.FILE_TOO_LARGE ->
        if (chinese) "文件太大，无法作为文本读取。" else "The file is too large to read as text."
    runtime.mobileagent.skills.tooling.ToolErrorCode.INVALID_CURSOR ->
        if (chinese) "目录内容已发生变化，请从第一页重新打开。" else "The directory changed. Open it again from the first page."
    runtime.mobileagent.skills.tooling.ToolErrorCode.PERMISSION_DENIED,
    runtime.mobileagent.skills.tooling.ToolErrorCode.SHIZUKU_PERMISSION_DENIED,
    runtime.mobileagent.skills.tooling.ToolErrorCode.CAPABILITY_DENIED,
    runtime.mobileagent.skills.tooling.ToolErrorCode.AUTHORITY_NOT_GRANTED,
        -> if (chinese) "没有权限访问该工作区，请检查授权。" else "Permission to access this workspace is missing. Check its authorization."
    runtime.mobileagent.skills.tooling.ToolErrorCode.PATH_OUT_OF_SCOPE ->
        if (chinese) "请求路径超出已授权工作区。" else "The requested path is outside the authorized workspace."
    runtime.mobileagent.skills.tooling.ToolErrorCode.SYMLINK_FORBIDDEN ->
        if (chinese) "不允许从工作区跟随符号链接。" else "Symbolic links cannot be followed from this workspace."
    runtime.mobileagent.skills.tooling.ToolErrorCode.ROOT_OPERATION_FORBIDDEN ->
        if (chinese) "不能对设备根目录执行此操作。" else "This operation is not allowed on the device root."
    runtime.mobileagent.skills.tooling.ToolErrorCode.WORKSPACE_NOT_FOUND ->
        if (chinese) "工作区或目录已不可用，请重新选择。" else "The workspace or directory is unavailable. Select it again."
    runtime.mobileagent.skills.tooling.ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED ->
        if (chinese) "请先在设置中选定 ADB 级通道。" else "Select an ADB-level authority in Settings first."
    runtime.mobileagent.skills.tooling.ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
    runtime.mobileagent.skills.tooling.ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE,
    runtime.mobileagent.skills.tooling.ToolErrorCode.BRIDGE_DISCONNECTED,
    runtime.mobileagent.skills.tooling.ToolErrorCode.ADB_DEVICE_OFFLINE,
    runtime.mobileagent.skills.tooling.ToolErrorCode.ADB_DEVICE_DISCONNECTED,
        -> if (chinese) "工作区暂时不可用，请重新连接后重试。" else "The workspace is temporarily unavailable. Reconnect it and try again."
    runtime.mobileagent.skills.tooling.ToolErrorCode.BRIDGE_NOT_PAIRED,
    runtime.mobileagent.skills.tooling.ToolErrorCode.ADB_DEVICE_UNAUTHORIZED,
    runtime.mobileagent.skills.tooling.ToolErrorCode.ADB_APP_NOT_INSTALLED,
        -> if (chinese) "ADB 设备尚未完成配对或授权，请先在设置中处理。" else "The ADB device is not paired or authorized. Complete setup in Settings."
    runtime.mobileagent.skills.tooling.ToolErrorCode.WORKSPACE_READ_ONLY ->
        if (chinese) "该工作区为只读，无法执行写入操作。" else "This workspace is read-only."
    runtime.mobileagent.skills.tooling.ToolErrorCode.QUOTA_EXCEEDED ->
        if (chinese) "目录或输出超过安全上限，请缩小范围后重试。" else "The directory or output exceeds its safety limit. Narrow the request and try again."
    runtime.mobileagent.skills.tooling.ToolErrorCode.CONFLICT ->
        if (chinese) "工作区内容已变化，请刷新后重试。" else "The workspace changed. Refresh it and try again."
    runtime.mobileagent.skills.tooling.ToolErrorCode.UNSUPPORTED_ENTRY ->
        if (chinese) "该文件类型暂不支持。" else "This workspace entry type is not supported."
    runtime.mobileagent.skills.tooling.ToolErrorCode.OPERATION_UNAVAILABLE ->
        if (chinese) "当前工作区后端不支持此操作。" else "This operation is unavailable on the selected workspace backend."
    runtime.mobileagent.skills.tooling.ToolErrorCode.INVALID_REQUEST,
    runtime.mobileagent.skills.tooling.ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH,
        -> if (chinese) "目录请求已失效，请重新打开后重试。" else "The directory request is no longer valid. Open it again and retry."
    runtime.mobileagent.skills.tooling.ToolErrorCode.TIMEOUT,
    runtime.mobileagent.skills.tooling.ToolErrorCode.IO_ERROR,
        -> if (chinese) "读取目录失败，请重试。" else "The directory could not be read. Try again."
    else -> if (chinese) "工作区运行时发生内部错误。" else "An internal workspace runtime error occurred."
}

private fun workspaceAccessFailureMessage(
    code: runtime.mobileagent.integration.WorkspaceAccessErrorCode,
    chinese: Boolean,
): String = when (code) {
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND ->
        if (chinese) "工作区已不可用，请重新选择。" else "The workspace is unavailable. Select it again."
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED ->
        if (chinese) "请先在设置中选定 ADB 级通道。" else "Select an ADB-level authority in Settings first."
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE ->
        if (chinese) "ADB 级通道暂时不可用，请重新连接后重试。" else "The ADB-level authority is temporarily unavailable. Reconnect it and try again."
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.CAPABILITY_DENIED,
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED,
        -> if (chinese) "没有权限访问该工作区，请重新授权。" else "Permission to access this workspace is missing. Authorize it again."
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.CONFLICT ->
        if (chinese) "工作区状态已变化，请刷新后重试。" else "The workspace state changed. Refresh it and try again."
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.UNSUPPORTED ->
        if (chinese) "当前工作区不支持此操作。" else "This workspace does not support the operation."
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.INVALID_REQUEST ->
        if (chinese) "工作区请求无效，请重新选择。" else "The workspace request is invalid. Select it again."
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.PERSISTENCE_FAILED ->
        if (chinese) "工作区设置未能保存，请重试。" else "The workspace setting could not be saved. Try again."
    runtime.mobileagent.integration.WorkspaceAccessErrorCode.UNKNOWN_OUTCOME ->
        if (chinese) "工作区运行时发生内部错误。" else "An internal workspace runtime error occurred."
}

private fun newWorkspaceId(prefix: String): String =
    "$prefix-${java.util.UUID.randomUUID().toString().replace("-", "")}".take(128)

private fun agentFullDeviceWorkspaceId(agentId: String, authority: runtime.mobileagent.domain.Authority): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(agentId.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
    return "full-${authority.name.lowercase()}-$digest"
}

@Composable
private fun ProvidersRoute(entry: NavBackStackEntry, chinese: Boolean, onRoute: (String) -> Unit,
    onRequestEditorClose: () -> Unit, onEditorState: (String, Boolean, (() -> Unit)?) -> Unit,
    onShellDetail: (String, String?, (() -> Unit)?) -> Unit,
    onOpenMcp: () -> Unit, showBack: Boolean, onBack: () -> Unit) {
    val vm: runtime.mobileagent.ProvidersViewModel = viewModel(viewModelStoreOwner = entry)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var providerDraft by rememberSaveable(stateSaver = providerDraftSaver) { mutableStateOf(runtime.mobileagent.feature.providers.ProviderDraft()) }
    var providerBaseline by rememberSaveable(stateSaver = providerDraftSaver) { mutableStateOf(runtime.mobileagent.feature.providers.ProviderDraft()) }
    var providerError by rememberSaveable { mutableStateOf<String?>(null) }
    var probeModelId by rememberSaveable { mutableStateOf<String?>(null) }
    val providers = vm.providers.toList()
    val models = vm.models.toList()
    val selectedId = selectedProviderId?.takeIf { id -> providers.any { it.id == id } } ?: providers.firstOrNull()?.id
    val dirty = editorOpen && providerDraft != providerBaseline
    val discard = remember(vm) { { editorOpen = false; providerDraft = providerDraft.copy(apiKey = ""); providerError = null } }
    LaunchedEffect(dirty) { onEditorState(AppRoutes.PROVIDERS, dirty, discard) }
    LaunchedEffect(editorOpen, providerDraft.id, providerDraft.modelProfileId, chinese) {
        if (editorOpen) {
            val title = when {
                providerDraft.modelProfileId != null -> if (chinese) "编辑模型" else "Edit model"
                providerDraft.id == null -> if (chinese) "添加服务商" else "Add provider"
                else -> if (chinese) "编辑服务商" else "Edit provider"
            }
            onShellDetail(AppRoutes.PROVIDERS, title, onRequestEditorClose)
        } else {
            onShellDetail(AppRoutes.PROVIDERS, null, null)
        }
    }
    val selectedModels = models.filter { it.providerId == selectedId }
    val app = LocalContext.current.applicationContext as MobileAgentApp
    val mcpState = runtime.mobileagent.McpConfigStore.read(app.container)
    val preview = selectedId?.let(vm::deletePreview)
    val state = runtime.mobileagent.feature.providers.ProvidersUiState(
        providers = providers.map { profile -> providerCardFrom(profile, models) }, selectedProviderId = selectedId,
        models = selectedModels.map(::providerModelFrom), draft = providerDraft,
        // The ViewModel owns the typed operation state.  Do not infer a
        // terminal result from busy=false or parse a status string here.
        probe = if (probeModelId == null) runtime.mobileagent.feature.providers.ProbeUiState()
            else vm.probeState.value,
        editorOpen = editorOpen, loading = vm.busy.value, error = providerError, status = vm.status.value,
        language = if (chinese) "zh-CN" else "en-US", mcpReason = if (mcpState.value != null) "MCP 配置入口可用。" else "MCP 尚未配置服务器。",
        mcpEntryEnabled = true, editorError = providerError, deleteModelCount = preview?.modelCount ?: 0,
        deleteSnapshotCount = preview?.snapshotCount ?: 0,
    )
    val actions = runtime.mobileagent.feature.providers.ProvidersActions(
        onSelectProvider = { selectedProviderId = it; probeModelId = null; vm.clearProbe() },
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
                    name = providerDraft.name, baseUrl = providerDraft.baseUrl, apiFormat = providerDraft.apiFormat,
                    modelId = providerDraft.modelId,
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
                vm.clearProbe()
            } else if (vm.status.value.isNotBlank()) providerError = vm.status.value
        },
        onDelete = { selectedId?.let(vm::deleteProvider); selectedProviderId = null; vm.clearProbe() },
        onEditModel = { id ->
            val model = models.firstOrNull { it.id == id }
            providerDraft = providerDraftFrom(providers.firstOrNull { it.id == model?.providerId }, model)
            providerBaseline = providerDraft; providerError = null; editorOpen = true
        },
        onDeleteModel = vm::deleteModel,
        onProbe = {
            val model = selectedModels.firstOrNull()
            if (model == null) {
                probeModelId = "__none__"
                vm.probe("__missing-model__", approved = true)
            } else {
                probeModelId = model.id
                vm.probe(model.id, approved = true)
            }
        },
        onTestConnection = {
            val model = selectedModels.firstOrNull()
            probeModelId = model?.id ?: "__missing-model__"
            vm.testConnection(model?.id ?: "__missing-model__", approved = true)
        },
        onCloseProbe = { probeModelId = null; vm.clearProbe() }, onOpenMcpSettings = onOpenMcp,
    )
    // Navigation is owned by the app shell. Keep these parameters in the
    // route seam for compatibility with hosts that still pass them, but do
    // not render a second page-level back bar here.
    runtime.mobileagent.feature.providers.ProvidersScreen(
        state,
        actions,
        showPageTitle = false,
        renderEditorAsPage = true,
    )
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
    runtime.mobileagent.feature.knowledge.KnowledgeScreen(state, actions, showPageTitle = false)
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
    runtime.mobileagent.feature.skills.SkillsScreen(state, actions, showPageTitle = false)
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
    // The shell supplies the only route back affordance.
    runtime.mobileagent.feature.announcements.AnnouncementsScreen(state, actions, showPageTitle = false)
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
    if (aboutOnly) {
        runtime.mobileagent.feature.settings.AboutScreen(state, actions, showPageTitle = false)
    } else {
        runtime.mobileagent.feature.settings.SettingsScreen(state, actions, showPageTitle = false)
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
    // The app shell owns the route title and back affordance for this child.
    McpSettingsScreen(vm.state.value, actions, showPageTitle = false)
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
            showPageTitle = false,
            availability = inspectorAvailability,
        )
    }
    // The app shell owns the route title and back affordance for this child.
    InspectorContent()
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
