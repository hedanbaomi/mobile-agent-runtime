// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared visual contract for the Android client.
 *
 * Components use these values instead of screen-specific magic numbers.  The
 * values deliberately use minimums and padding rather than fixed heights so a
 * 320 dp screen and large font scale can still expose the whole action.
 */
@Immutable
data class MobileAgentSpacing(
    val screen: Dp = 16.dp,
    val compact: Dp = 8.dp,
    val control: Dp = 12.dp,
    val card: Dp = 16.dp,
    val section: Dp = 24.dp,
)

@Immutable
data class MobileAgentShapeTokens(
    val control: Dp = 14.dp,
    val card: Dp = 20.dp,
    val sheet: Dp = 28.dp,
    val chip: Dp = 10.dp,
)

@Immutable
data class MobileAgentDesignTokens(
    val spacing: MobileAgentSpacing = MobileAgentSpacing(),
    val shapes: MobileAgentShapeTokens = MobileAgentShapeTokens(),
    val minTouchTarget: Dp = 48.dp,
    val listRowMinHeight: Dp = 56.dp,
    val topBarMinHeight: Dp = 64.dp,
)

object AgentDesignDefaults {
    val tokens = MobileAgentDesignTokens()
}
val LocalAgentDesignTokens = staticCompositionLocalOf { AgentDesignDefaults.tokens }

private val AgentCardShape
    @Composable get() = RoundedCornerShape(LocalAgentDesignTokens.current.shapes.card)

private val AgentControlShape
    @Composable get() = RoundedCornerShape(LocalAgentDesignTokens.current.shapes.control)

/** A surface for grouped content, with an optional accessible click action. */
@Composable
fun AgentCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAgentDesignTokens.current
    val interaction = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .heightIn(min = tokens.minTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Card(
        modifier = modifier.fillMaxWidth().then(interaction),
        shape = AgentCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        content = { Column(Modifier.padding(tokens.spacing.card), content = content) },
    )
}

/** A flexible row primitive used for agents, sessions, workspaces and settings. */
@Composable
fun AgentListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = LocalAgentDesignTokens.current
    val shape = RoundedCornerShape(tokens.shapes.control)
    val interaction = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = tokens.listRowMinHeight)
            .then(interaction),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.control, vertical = tokens.spacing.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { it() }
                Spacer(Modifier.width(tokens.spacing.control))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                }
            }
            trailing?.invoke(this)
        }
    }
}

/** Icon action with a guaranteed 48 dp touch target and an explicit label. */
@Composable
fun AgentIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val minTarget = LocalAgentDesignTokens.current.minTouchTarget
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .sizeIn(minWidth = minTarget, minHeight = minTarget)
            .semantics { role = Role.Button },
    ) {
        Icon(imageVector, contentDescription = contentDescription)
    }
}

/** A title bar whose back action remains reachable at large font scales. */
@Composable
fun AgentTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "返回",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = LocalAgentDesignTokens.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.topBarMinHeight)
                .padding(horizontal = tokens.spacing.screen, vertical = tokens.spacing.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                AgentIconButton(Icons.AutoMirrored.Filled.ArrowBack, backLabel, onBack)
                Spacer(Modifier.width(tokens.spacing.compact))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    modifier = Modifier.semantics { heading() },
                )
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            actions()
        }
    }
}

enum class AgentStatusTone { INFO, SUCCESS, WARNING, ERROR }

/** Status copy always has an icon and text, so color is never the only signal. */
@Composable
fun AgentStatusBanner(
    title: String,
    message: String,
    tone: AgentStatusTone = AgentStatusTone.INFO,
    modifier: Modifier = Modifier,
) {
    val palette = when (tone) {
        AgentStatusTone.INFO -> Triple(Icons.Filled.Info, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        AgentStatusTone.SUCCESS -> Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        AgentStatusTone.WARNING -> Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        AgentStatusTone.ERROR -> Triple(Icons.Filled.Error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    val tokens = LocalAgentDesignTokens.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = AgentControlShape,
        color = palette.second,
        contentColor = palette.third,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(tokens.spacing.control),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(palette.first, contentDescription = null)
            Spacer(Modifier.width(tokens.spacing.control))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
            }
        }
    }
}

@Composable
fun AgentRiskNotice(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "需要注意",
) {
    AgentStatusBanner(title, message, AgentStatusTone.WARNING, modifier)
}

/** Empty content with an optional next action; no fixed height is imposed. */
@Composable
fun AgentEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    val tokens = LocalAgentDesignTokens.current
    Column(
        modifier = modifier.fillMaxWidth().padding(tokens.spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.control),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3)
        if (!message.isNullOrBlank()) {
            Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 8, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = tokens.minTouchTarget),
                shape = AgentControlShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text(actionLabel, maxLines = 2) }
        }
    }
}

/** Standard modal surface; the caller owns state and business actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAgentDesignTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = tokens.shapes.sheet, topEnd = tokens.shapes.sheet),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = tokens.spacing.screen, vertical = tokens.spacing.card),
            content = content,
        )
    }
}
