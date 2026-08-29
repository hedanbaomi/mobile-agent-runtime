// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** User-selectable theme modes. The 66ccff value is intentionally literal. */
enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    CC66FF,
}

@Immutable
data class AppNavigationDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val contentDescription: String = label,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A56DB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EFFE),
    onPrimaryContainer = Color(0xFF1E429F),
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3F4F6),
    onSecondaryContainer = Color(0xFF1F2A37),
    tertiary = Color(0xFF0E9F6E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDEF7EC),
    onTertiaryContainer = Color(0xFF03543F),
    error = Color(0xFFE02424),
    onError = Color.White,
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF9B1C1C),
    background = Color(0xFFF9FAFB),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),
    // ui-tokens.json defines the container/inverse values below; the bright/dim
    // endpoints are derived from this existing neutral surface family.
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE5E7EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFE5E7EB),
    surfaceContainerHighest = Color(0xFFD1D5DB),
    inverseSurface = Color(0xFF1F2937),
    inverseOnSurface = Color(0xFFF9FAFB),
    inversePrimary = Color(0xFF90CDF4),
    surfaceTint = Color(0xFF1A56DB),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF76A9FA),
    onPrimary = Color(0xFF1E429F),
    primaryContainer = Color(0xFF233876),
    onPrimaryContainer = Color(0xFFE1EFFE),
    secondary = Color(0xFF9CA3AF),
    onSecondary = Color(0xFF1F2A37),
    secondaryContainer = Color(0xFF374151),
    onSecondaryContainer = Color(0xFFF3F4F6),
    tertiary = Color(0xFF31C48D),
    onTertiary = Color(0xFF03543F),
    tertiaryContainer = Color(0xFF046C4E),
    onTertiaryContainer = Color(0xFFDEF7EC),
    error = Color(0xFFF98080),
    onError = Color(0xFF9B1C1C),
    errorContainer = Color(0xFF771D1D),
    onErrorContainer = Color(0xFFFDE8E8),
    background = Color(0xFF111827),
    onBackground = Color(0xFFF9FAFB),
    surface = Color(0xFF1F2937),
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF374151),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF4B5563),
    outlineVariant = Color(0xFF374151),
    // ui-tokens.json defines the container/inverse values below; bright/dim
    // use the existing dark background and surfaceVariant endpoints.
    surfaceBright = Color(0xFF374151),
    surfaceDim = Color(0xFF111827),
    surfaceContainerLowest = Color(0xFF0F172A),
    surfaceContainerLow = Color(0xFF182234),
    surfaceContainer = Color(0xFF1F2937),
    surfaceContainerHigh = Color(0xFF283548),
    surfaceContainerHighest = Color(0xFF374151),
    inverseSurface = Color(0xFFF9FAFB),
    inverseOnSurface = Color(0xFF111827),
    inversePrimary = Color(0xFF1A56DB),
    surfaceTint = Color(0xFF76A9FA),
    scrim = Color(0xFF000000),
)

/** The 66ccff theme remains a light surface system, with the accent as its focus color. */
private val Cc66ffColors = lightColorScheme(
    primary = Color(0xFF66CCFF),
    onPrimary = Color(0xFF003B52),
    primaryContainer = Color(0xFFE0F4FF),
    onPrimaryContainer = Color(0xFF004863),
    secondary = Color(0xFF4A6069),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E9F0),
    onSecondaryContainer = Color(0xFF142930),
    tertiary = Color(0xFF0E9F6E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDEF7EC),
    onTertiaryContainer = Color(0xFF03543F),
    error = Color(0xFFE02424),
    onError = Color.White,
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF9B1C1C),
    background = Color(0xFFF2F9FD),
    onBackground = Color(0xFF0E1E24),
    surface = Color.White,
    onSurface = Color(0xFF0E1E24),
    surfaceVariant = Color(0xFFE3EFF5),
    onSurfaceVariant = Color(0xFF3D545E),
    outline = Color(0xFFB8D3E0),
    outlineVariant = Color(0xFFD3E5EE),
    // ui-tokens.json defines the container/inverse values below; bright/dim
    // use the existing 66ccff surface and surfaceVariant endpoints.
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD4E9F3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F9FD),
    surfaceContainer = Color(0xFFE8F4FA),
    surfaceContainerHigh = Color(0xFFDEEFF7),
    surfaceContainerHighest = Color(0xFFD4E9F3),
    inverseSurface = Color(0xFF1E2B30),
    inverseOnSurface = Color(0xFFF0F7FA),
    inversePrimary = Color(0xFF66CCFF),
    surfaceTint = Color(0xFF66CCFF),
    scrim = Color(0xFF000000),
)

@Composable
fun MobileAgentTheme(
    mode: AppThemeMode = AppThemeMode.CC66FF,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT, AppThemeMode.CC66FF -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = when {
        mode == AppThemeMode.CC66FF -> Cc66ffColors
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = colors.surface.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography.copy(
            displaySmall = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp),
            headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        ),
        content = content,
    )
}

/**
 * Responsive seven destination shell. At 600dp and above it uses a rail; on a
 * phone it uses the designed bottom navigation bar. Content owns its own state.
 */
@Composable
fun AppNavigationScaffold(
    destinations: List<AppNavigationDestination>,
    selectedRoute: String,
    onRouteSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    destinations.forEach { destination ->
                        NavigationRailItem(
                            selected = selectedRoute == destination.route,
                            onClick = { onRouteSelected(destination.route) },
                            icon = { Icon(destination.icon, destination.contentDescription) },
                            label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                Scaffold(Modifier.weight(1f).fillMaxHeight()) { padding -> content(padding) }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 0.dp,
                    ) {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = selectedRoute == destination.route,
                                onClick = { onRouteSelected(destination.route) },
                                icon = { Icon(destination.icon, destination.contentDescription) },
                                label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                },
            ) { padding -> content(padding) }
        }
    }
}

/** Stable textual label used by a back affordance without adding decorative glyphs. */
@Composable
fun BackLabel(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = label)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}
