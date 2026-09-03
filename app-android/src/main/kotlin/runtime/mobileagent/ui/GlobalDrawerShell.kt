// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The app-level drawer owns navigation mechanics.  A caller can provide a
 * richer content projection (for example Agent/session/workspace data) while
 * this component keeps compact and wide layouts identical in information
 * architecture.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GlobalDrawerShell(
    selectedRoute: String,
    destinations: List<AppNavigationDestination>,
    onRouteSelected: (String) -> Unit,
    drawerOpen: Boolean = false,
    onDrawerOpenChange: (Boolean) -> Unit = {},
    showCompactOpenButton: Boolean = false,
    compactOpenButtonLabel: String = "打开菜单",
    title: String = "",
    navigationAffordance: ShellNavigationAffordance = if (showCompactOpenButton) {
        ShellNavigationAffordance.MENU
    } else {
        ShellNavigationAffordance.NONE
    },
    onBack: () -> Unit = {},
    navigationBackLabel: String = "返回",
    consumeBottomSystemInsets: Boolean = true,
    modifier: Modifier = Modifier,
    drawerWidth: Dp = 304.dp,
    content: @Composable (PaddingValues) -> Unit,
    drawerContent: @Composable (onClose: () -> Unit) -> Unit = { onClose ->
        GlobalDrawerDestinationContent(
            destinations = destinations,
            selectedRoute = selectedRoute,
            onRouteSelected = onRouteSelected,
            onClose = onClose,
        )
    },
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(
            if (drawerOpen) DrawerValue.Open else DrawerValue.Closed,
        )
        fun closeDrawer() {
            onDrawerOpenChange(false)
            scope.launch { drawerState.close() }
        }
        LaunchedEffect(drawerOpen) {
            if (drawerOpen && !drawerState.isOpen) drawerState.open()
            if (!drawerOpen && drawerState.isOpen) drawerState.close()
        }
        LaunchedEffect(drawerState.currentValue) {
            val open = drawerState.isOpen
            if (open != drawerOpen) onDrawerOpenChange(open)
        }
        BackHandler(enabled = !wide && drawerState.isOpen) { closeDrawer() }

        val windowInsets = appShellWindowInsets(consumeBottomSystemInsets)
        val showBack = navigationAffordance == ShellNavigationAffordance.BACK
        val showMenu = !wide && navigationAffordance == ShellNavigationAffordance.MENU
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("global.shell"),
            contentWindowInsets = windowInsets,
            topBar = {
                TopAppBar(
                    modifier = Modifier.testTag("global.shell.topBar"),
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("global.shell.title"),
                        )
                    },
                    navigationIcon = {
                        if (!drawerState.isOpen && !drawerOpen) {
                            when {
                                showBack -> IconButton(
                                    onClick = onBack,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("global.shell.navigation.back"),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = navigationBackLabel)
                                }
                                showMenu -> IconButton(
                                    onClick = { onDrawerOpenChange(true) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("global.shell.navigation.menu"),
                                ) {
                                    Icon(Icons.Filled.Menu, contentDescription = compactOpenButtonLabel)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                if (wide) {
                    Row(Modifier.fillMaxSize().testTag("global.shell.wide")) {
                        Surface(
                            modifier = Modifier
                                .width(drawerWidth)
                                .fillMaxHeight()
                                .testTag("global.drawer.permanent"),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                        ) {
                            drawerContent { onDrawerOpenChange(false) }
                        }
                        Surface(Modifier.weight(1f).fillMaxHeight()) {
                            content(PaddingValues(0.dp))
                        }
                    }
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(drawerWidth)
                                    .testTag("global.drawer.modal"),
                            ) {
                                drawerContent(::closeDrawer)
                            }
                        },
                    ) {
                        content(PaddingValues(0.dp))
                    }
                }
            }
        }
    }
}

/** Minimal fallback drawer for routes that have not supplied conversation data yet. */
@Composable
private fun GlobalDrawerDestinationContent(
    destinations: List<AppNavigationDestination>,
    selectedRoute: String,
    onRouteSelected: (String) -> Unit,
    onClose: () -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("global.drawer.destination.content"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MobileAgentRuntime",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Agent 工作台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag("global.drawer.close")) {
                    Text("关闭")
                }
            }
        }
        item(key = "new") {
            OutlinedButton(
                onClick = { onRouteSelected(AppRoutes.CHAT); onClose() },
                modifier = Modifier.fillMaxWidth().testTag("global.drawer.new"),
            ) {
                Text("新对话")
            }
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
        item(key = "title") {
            Text(
                "导航",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(destinations.distinctBy { it.route }, key = { it.route }) { destination ->
            NavigationDrawerItem(
                selected = selectedRoute == destination.route,
                onClick = { onRouteSelected(destination.route); onClose() },
                label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("global.drawer.navigation.${destination.route}"),
            )
        }
    }
}
