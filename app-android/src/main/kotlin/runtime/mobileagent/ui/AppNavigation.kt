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
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Tune

object AppRoutes {
    const val CHAT = "chat"
    const val AGENTS = "agents"
    const val PROVIDERS = "providers"
    const val KNOWLEDGE = "knowledge"
    const val SKILLS = "skills"
    const val NEWS = "news"
    const val SETTINGS = "settings"
    const val MORE = "more"
    const val ABOUT = "about"
    const val INSPECTOR = "inspector"
    const val MCP = "mcp"
}

/** Single shell-owned source of truth for the compact leading navigation affordance. */
enum class ShellNavigationAffordance { MENU, BACK, NONE }

private val topLevelMenuRoutes = setOf(
    AppRoutes.CHAT,
    AppRoutes.AGENTS,
    AppRoutes.PROVIDERS,
    AppRoutes.KNOWLEDGE,
    AppRoutes.SKILLS,
    AppRoutes.NEWS,
    AppRoutes.SETTINGS,
    AppRoutes.MORE,
)

private val childBackRoutes = setOf(AppRoutes.ABOUT, AppRoutes.INSPECTOR, AppRoutes.MCP)

/**
 * Pages never independently opt into both Menu and Back. A dialog/detail state can promote an
 * otherwise top-level route to BACK, while ordinary destinations remain MENU-owned.
 */
fun shellNavigationAffordance(
    route: String,
    childDetailOpen: Boolean = false,
): ShellNavigationAffordance = when {
    childDetailOpen || route in childBackRoutes -> ShellNavigationAffordance.BACK
    route in topLevelMenuRoutes -> ShellNavigationAffordance.MENU
    else -> ShellNavigationAffordance.NONE
}

/**
 * Single title policy for the application shell. Feature roots keep their
 * own content labels only when useful inside the page; the shell owns the
 * visible page title and the corresponding leading navigation action.
 */
fun appShellTitle(
    route: String,
    chinese: Boolean = true,
    childDetailOpen: Boolean = false,
): String = if (childDetailOpen) {
    if (chinese) "选择工作区" else "Choose workspace"
} else {
    when (route) {
        AppRoutes.CHAT -> if (chinese) "对话" else "Chat"
        AppRoutes.AGENTS -> if (chinese) "智能体" else "Agents"
        AppRoutes.PROVIDERS -> if (chinese) "服务商" else "Providers"
        AppRoutes.KNOWLEDGE -> if (chinese) "知识" else "Knowledge"
        AppRoutes.SKILLS -> if (chinese) "技能" else "Skills"
        AppRoutes.NEWS -> if (chinese) "公告" else "News"
        AppRoutes.SETTINGS -> if (chinese) "设置" else "Settings"
        AppRoutes.MORE -> if (chinese) "更多" else "More"
        AppRoutes.ABOUT -> if (chinese) "关于" else "About"
        AppRoutes.INSPECTOR -> if (chinese) "请求检查器" else "Request inspector"
        AppRoutes.MCP -> "MCP"
        else -> if (chinese) "MobileAgentRuntime" else "MobileAgentRuntime"
    }
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

/**
 * Destinations available from the application-wide drawer.  Compact layouts
 * no longer hide product areas behind a bottom navigation bar; the drawer is
 * the single navigation surface for both compact and wide windows.
 */
fun globalDrawerDestinations(chinese: Boolean = true): List<AppNavigationDestination> =
    defaultAppDestinations(chinese) + listOf(
        AppNavigationDestination(AppRoutes.MORE, if (chinese) "更多" else "More", Icons.Outlined.MoreHoriz),
    )

fun phonePrimaryDestinations(chinese: Boolean = true): List<AppNavigationDestination> = listOf(
    AppNavigationDestination(AppRoutes.CHAT, if (chinese) "对话" else "Chat", Icons.Outlined.Chat),
    AppNavigationDestination(AppRoutes.AGENTS, if (chinese) "智能体" else "Agents", Icons.Outlined.SmartToy),
    AppNavigationDestination(AppRoutes.KNOWLEDGE, if (chinese) "知识" else "Knowledge", Icons.Outlined.MenuBook),
    AppNavigationDestination(AppRoutes.SKILLS, if (chinese) "技能" else "Skills", Icons.Outlined.Extension),
    AppNavigationDestination(AppRoutes.MORE, if (chinese) "更多" else "More", Icons.Outlined.MoreHoriz),
)

fun moreHubItems(chinese: Boolean = true): List<AppNavigationDestination> = listOf(
    AppNavigationDestination(AppRoutes.PROVIDERS, if (chinese) "服务商" else "Providers", Icons.Outlined.Tune),
    AppNavigationDestination(AppRoutes.NEWS, if (chinese) "公告" else "News", Icons.Outlined.Campaign),
    AppNavigationDestination(AppRoutes.MCP, if (chinese) "MCP" else "MCP", Icons.Outlined.Cloud),
    AppNavigationDestination(AppRoutes.SETTINGS, if (chinese) "设置" else "Settings", Icons.Outlined.Settings),
    AppNavigationDestination(AppRoutes.ABOUT, if (chinese) "关于" else "About", Icons.Outlined.Info),
    AppNavigationDestination(AppRoutes.INSPECTOR, if (chinese) "请求检查器" else "Request inspector", Icons.Outlined.BugReport),
)

/** Returns whether [route] is one of the phone-only destinations opened from More. */
fun isMoreChildRoute(route: String): Boolean = moreHubItems(false).any { it.route == route }

/**
 * Pure back policy for the shell. Navigation history owns ordinary More-child returns;
 * this avoids redirecting a first-level destination opened directly from the drawer.
 * Inspector keeps the explicit source selected by the caller.
 */
fun appBackTarget(
    compact: Boolean,
    currentRoute: String,
    inspectorReturnRoute: String = AppRoutes.MORE,
    hasPreviousEntry: Boolean,
): String? {
    if (compact && currentRoute == AppRoutes.INSPECTOR) {
        return inspectorReturnRoute.takeUnless { it == AppRoutes.INSPECTOR } ?: AppRoutes.MORE
    }
    if (!hasPreviousEntry && currentRoute != AppRoutes.CHAT) return AppRoutes.CHAT
    return null
}

fun appThemeMode(value: String?): AppThemeMode = when (value?.lowercase()) {
    "light" -> AppThemeMode.LIGHT
    "dark" -> AppThemeMode.DARK
    "66ccff" -> AppThemeMode.CC66FF
    else -> AppThemeMode.SYSTEM
}
