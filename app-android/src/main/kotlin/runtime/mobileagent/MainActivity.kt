// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import runtime.mobileagent.feature.agents.AgentsScreen
import runtime.mobileagent.feature.announcements.AnnouncementsScreen
import runtime.mobileagent.feature.chat.ChatScreen
import runtime.mobileagent.feature.knowledge.KnowledgeScreen
import runtime.mobileagent.feature.providers.ProvidersScreen
import runtime.mobileagent.feature.settings.AboutScreen
import runtime.mobileagent.feature.skills.SkillsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val nav = rememberNavController()
            val dest = remember { mutableStateOf("chat") }
            val chatVm: ChatViewModel = viewModel()
            val providersVm: ProvidersViewModel = viewModel()
            val knowledgeVm: KnowledgeViewModel = viewModel()
            val tabs = listOf(
                Triple("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
                Triple("agents", "Agents", Icons.Filled.Person),
                Triple("providers", "Providers", Icons.Filled.Settings),
                Triple("knowledge", "Knowledge", Icons.Filled.Folder),
                Triple("skills", "Skills", Icons.Filled.Build),
                Triple("announcements", "News", Icons.Filled.Notifications),
                Triple("about", "About", Icons.Filled.Info),
            )
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        tabs.forEach { (route, label, icon) ->
                            NavigationBarItem(
                                selected = dest.value == route,
                                onClick = {
                                    dest.value = route
                                    nav.navigate(route)
                                },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                },
            ) { padding ->
                NavHost(navController = nav, startDestination = "chat", modifier = Modifier.padding(padding)) {
                    composable("chat") {
                        ChatScreen(
                            lines = chatVm.lines,
                            input = chatVm.input.value,
                            streaming = chatVm.streaming.value,
                            status = chatVm.status.value,
                            onInput = { chatVm.input.value = it },
                            onSend = chatVm::send,
                            onCancel = chatVm::cancel,
                        )
                    }
                    composable("agents") { AgentsScreen() }
                    composable("providers") {
                        ProvidersScreen(
                            providers = providersVm.providers,
                            status = providersVm.status.value,
                            onSave = providersVm::save,
                        )
                    }
                    composable("knowledge") {
                        KnowledgeScreen(
                            jobs = knowledgeVm.jobs,
                            status = knowledgeVm.status.value,
                            onImport = knowledgeVm::importUris,
                        )
                    }
                    composable("skills") { SkillsScreen() }
                    composable("announcements") { AnnouncementsScreen(emptyList()) }
                    composable("about") {
                        AboutScreen(BuildConfig.VERSION_NAME, BuildConfig.GIT_REVISION)
                    }
                }
            }
        }
    }
}
