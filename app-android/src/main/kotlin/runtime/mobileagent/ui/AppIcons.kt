// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.ui.graphics.vector.ImageVector
import runtime.mobileagent.ui.vendored.VendoredMaterialIcons

/**
 * First-party icon facade delegating to the vendored Material icon definitions in
 * `vendor/material-icons` (indexed as `vendored:aosp-material-icons-1.7.6` in `licenses/index.json`).
 */
object AppIcons {
    val Chat: ImageVector get() = VendoredMaterialIcons.Chat
    val SmartToy: ImageVector get() = VendoredMaterialIcons.SmartToy
    val Tune: ImageVector get() = VendoredMaterialIcons.Tune
    val MenuBook: ImageVector get() = VendoredMaterialIcons.MenuBook
    val Extension: ImageVector get() = VendoredMaterialIcons.Extension
    val Campaign: ImageVector get() = VendoredMaterialIcons.Campaign
    val Cloud: ImageVector get() = VendoredMaterialIcons.Cloud
    val BugReport: ImageVector get() = VendoredMaterialIcons.BugReport
    val Error: ImageVector get() = VendoredMaterialIcons.Error
}
