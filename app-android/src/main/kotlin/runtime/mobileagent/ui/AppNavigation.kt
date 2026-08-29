// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune

object AppRoutes {
    const val CHAT = "chat"
    const val AGENTS = "agents"
    const val PROVIDERS = "providers"
    const val KNOWLEDGE = "knowledge"
    const val SKILLS = "skills"
    const val NEWS = "news"
    const val SETTINGS = "settings"
}

/** The seven product destinations in design order. Labels are localized at the shell boundary. */
fun defaultAppDestinations(chinese: Boolean = true): List<AppNavigationDestination> = listOf(
    AppNavigationDestination(AppRoutes.CHAT, if (chinese) "对话" else "Chat", Icons.Outlined.Chat),
    AppNavigationDestination(AppRoutes.AGENTS, if (chinese) "智能体" else "Agents", Icons.Outlined.SmartToy),
    AppNavigationDestination(AppRoutes.PROVIDERS, if (chinese) "服务商" else "Providers", Icons.Outlined.Tune),
    AppNavigationDestination(AppRoutes.KNOWLEDGE, if (chinese) "知识" else "Knowledge", Icons.Outlined.MenuBook),
    AppNavigationDestination(AppRoutes.SKILLS, if (chinese) "技能" else "Skills", Icons.Outlined.Extension),
    AppNavigationDestination(AppRoutes.NEWS, if (chinese) "公告" else "News", Icons.Outlined.Campaign),
    AppNavigationDestination(AppRoutes.SETTINGS, if (chinese) "设置" else "Settings", Icons.Outlined.Settings),
)

fun appThemeMode(value: String?): AppThemeMode = when (value?.lowercase()) {
    "light" -> AppThemeMode.LIGHT
    "dark" -> AppThemeMode.DARK
    "66ccff" -> AppThemeMode.CC66FF
    else -> AppThemeMode.SYSTEM
}
