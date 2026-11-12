// SPDX-FileCopyrightText: Copyright 2020 The Android Open Source Project
// SPDX-License-Identifier: Apache-2.0

package runtime.mobileagent.ui.vendored

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Vendored Material icon vector definitions extracted from the Android Open Source Project
 * `androidx.compose.material:material-icons-extended:1.7.6` artifact under Apache-2.0.
 */
object VendoredMaterialIcons {
    private var _chat: ImageVector? = null
    val Chat: ImageVector
        get() {
            if (_chat != null) return _chat!!
            return materialIcon(name = "Outlined.Chat") {
                materialPath {
                    moveTo(4.0f, 4.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineToRelative(12.0f)
                    lineTo(5.17f, 16.0f)
                    lineTo(4.0f, 17.17f)
                    lineTo(4.0f, 4.0f)
                    moveToRelative(0f, -2.0f)
                    curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                    lineTo(2f, 22.0f)
                    lineToRelative(4.0f, -4.0f)
                    horizontalLineToRelative(14.0f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2.0f)
                    lineTo(22.0f, 4.0f)
                    curveToRelative(0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    lineTo(4.0f, 2f)
                    close()
                    moveTo(6.0f, 12.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(2f)
                    lineTo(6.0f, 14.0f)
                    verticalLineToRelative(-2.0f)
                    close()
                    moveTo(6.0f, 9.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineToRelative(2f)
                    lineTo(6.0f, 11.0f)
                    lineTo(6.0f, 9.0f)
                    close()
                    moveTo(6.0f, 6.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineToRelative(2f)
                    lineTo(6.0f, 8.0f)
                    lineTo(6.0f, 6.0f)
                    close()
                }
            }.also { _chat = it }
        }

    private var _smarttoy: ImageVector? = null
    val SmartToy: ImageVector
        get() {
            if (_smarttoy != null) return _smarttoy!!
            return materialIcon(name = "Outlined.SmartToy") {
                materialPath {
                    moveTo(20.0f, 9.0f)
                    verticalLineTo(7.0f)
                    curveToRelative(0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    horizontalLineToRelative(-3.0f)
                    curveToRelative(0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                    reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                    horizontalLineTo(6.0f)
                    curveTo(4.9f, 5.0f, 4.0f, 5.9f, 4.0f, 7.0f)
                    verticalLineToRelative(2f)
                    curveToRelative(-1.66f, 0f, -3.0f, 1.34f, -3.0f, 3.0f)
                    reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
                    verticalLineToRelative(4.0f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2.0f)
                    verticalLineToRelative(-4.0f)
                    curveToRelative(1.66f, 0f, 3.0f, -1.34f, 3.0f, -3.0f)
                    reflectiveCurveTo(21.66f, 9.0f, 20.0f, 9.0f)
                    close()
                    moveTo(18.0f, 19.0f)
                    lineTo(6.0f, 19.0f)
                    verticalLineTo(7.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineTo(19.0f)
                    close()
                    moveTo(9.0f, 13.0f)
                    curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                    reflectiveCurveTo(8.17f, 10.0f, 9.0f, 10.0f)
                    reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                    reflectiveCurveTo(9.83f, 13.0f, 9.0f, 13.0f)
                    close()
                    moveTo(16.5f, 11.5f)
                    curveToRelative(0f, 0.83f, -0.67f, 1.5f, -1.5f, 1.5f)
                    reflectiveCurveToRelative(-1.5f, -0.67f, -1.5f, -1.5f)
                    reflectiveCurveTo(14.17f, 10.0f, 15.0f, 10.0f)
                    reflectiveCurveTo(16.5f, 10.67f, 16.5f, 11.5f)
                    close()
                    moveTo(8.0f, 15.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(2f)
                    horizontalLineTo(8.0f)
                    verticalLineTo(15.0f)
                    close()
                }
            }.also { _smarttoy = it }
        }

    private var _tune: ImageVector? = null
    val Tune: ImageVector
        get() {
            if (_tune != null) return _tune!!
            return materialIcon(name = "Outlined.Tune") {
                materialPath {
                    moveTo(3.0f, 17.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(3.0f, 17.0f)
                    close()
                    moveTo(3.0f, 5.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(10.0f)
                    lineTo(13.0f, 5.0f)
                    lineTo(3.0f, 5.0f)
                    close()
                    moveTo(13.0f, 21.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(-8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(2f)
                    close()
                    moveTo(7.0f, 9.0f)
                    verticalLineToRelative(2f)
                    lineTo(3.0f, 11.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    lineTo(9.0f, 9.0f)
                    lineTo(7.0f, 9.0f)
                    close()
                    moveTo(21.0f, 13.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(11.0f, 11.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(10.0f)
                    close()
                    moveTo(15.0f, 9.0f)
                    horizontalLineToRelative(2f)
                    lineTo(17.0f, 7.0f)
                    horizontalLineToRelative(4.0f)
                    lineTo(21.0f, 5.0f)
                    horizontalLineToRelative(-4.0f)
                    lineTo(17.0f, 3.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(6.0f)
                    close()
                }
            }.also { _tune = it }
        }

    private var _menubook: ImageVector? = null
    val MenuBook: ImageVector
        get() {
            if (_menubook != null) return _menubook!!
            return materialIcon(name = "Outlined.MenuBook") {
                materialPath {
                    moveTo(21.0f, 5.0f)
                    curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
                    curveToRelative(-1.95f, 0f, -4.05f, 0.4f, -5.5f, 1.5f)
                    curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
                    reflectiveCurveTo(2.45f, 4.9f, 1f, 6.0f)
                    verticalLineToRelative(14.65f)
                    curveToRelative(0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
                    curveToRelative(0.1f, 0f, 0.15f, -0.05f, 0.25f, -0.05f)
                    curveTo(3.1f, 20.45f, 5.05f, 20.0f, 6.5f, 20.0f)
                    curveToRelative(1.95f, 0f, 4.05f, 0.4f, 5.5f, 1.5f)
                    curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                    curveToRelative(1.65f, 0f, 3.35f, 0.3f, 4.75f, 1.05f)
                    curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
                    curveToRelative(0.25f, 0f, 0.5f, -0.25f, 0.5f, -0.5f)
                    verticalLineTo(6.0f)
                    curveTo(22.4f, 5.55f, 21.75f, 5.25f, 21.0f, 5.0f)
                    close()
                    moveTo(21.0f, 18.5f)
                    curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
                    curveToRelative(-1.7f, 0f, -4.15f, 0.65f, -5.5f, 1.5f)
                    verticalLineTo(8.0f)
                    curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                    curveToRelative(1.2f, 0f, 2.4f, 0.15f, 3.5f, 0.5f)
                    verticalLineTo(18.5f)
                    close()
                }
                materialPath {
                    moveTo(17.5f, 10.5f)
                    curveToRelative(0.88f, 0f, 1.73f, 0.09f, 2.5f, 0.26f)
                    verticalLineTo(9.24f)
                    curveTo(19.21f, 9.09f, 18.36f, 9.0f, 17.5f, 9.0f)
                    curveToRelative(-1.7f, 0f, -3.24f, 0.29f, -4.5f, 0.83f)
                    verticalLineToRelative(1.66f)
                    curveTo(14.13f, 10.85f, 15.7f, 10.5f, 17.5f, 10.5f)
                    close()
                }
                materialPath {
                    moveTo(13.0f, 12.49f)
                    verticalLineToRelative(1.66f)
                    curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
                    curveToRelative(0.88f, 0f, 1.73f, 0.09f, 2.5f, 0.26f)
                    verticalLineTo(11.9f)
                    curveToRelative(-0.79f, -0.15f, -1.64f, -0.24f, -2.5f, -0.24f)
                    curveTo(15.8f, 11.66f, 14.26f, 11.96f, 13.0f, 12.49f)
                    close()
                }
                materialPath {
                    moveTo(17.5f, 14.33f)
                    curveToRelative(-1.7f, 0f, -3.24f, 0.29f, -4.5f, 0.83f)
                    verticalLineToRelative(1.66f)
                    curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
                    curveToRelative(0.88f, 0f, 1.73f, 0.09f, 2.5f, 0.26f)
                    verticalLineToRelative(-1.52f)
                    curveTo(19.21f, 14.41f, 18.36f, 14.33f, 17.5f, 14.33f)
                    close()
                }
            }.also { _menubook = it }
        }

    private var _extension: ImageVector? = null
    val Extension: ImageVector
        get() {
            if (_extension != null) return _extension!!
            return materialIcon(name = "Outlined.Extension") {
                materialPath {
                    moveTo(10.5f, 4.5f)
                    curveToRelative(0.28f, 0f, 0.5f, 0.22f, 0.5f, 0.5f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(2f)
                    curveToRelative(0.28f, 0f, 0.5f, 0.22f, 0.5f, 0.5f)
                    reflectiveCurveToRelative(-0.22f, 0.5f, -0.5f, 0.5f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(-2.12f)
                    curveToRelative(-0.68f, -1.75f, -2.39f, -3.0f, -4.38f, -3.0f)
                    reflectiveCurveToRelative(-3.7f, 1.25f, -4.38f, 3.0f)
                    horizontalLineTo(4.0f)
                    verticalLineToRelative(-2.12f)
                    curveToRelative(1.75f, -0.68f, 3.0f, -2.39f, 3.0f, -4.38f)
                    curveToRelative(0f, -1.99f, -1.24f, -3.7f, -2.99f, -4.38f)
                    lineTo(4.0f, 7.0f)
                    horizontalLineToRelative(6.0f)
                    verticalLineTo(5.0f)
                    curveToRelative(0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                    moveToRelative(0f, -2.0f)
                    curveTo(9.12f, 2.5f, 8.0f, 3.62f, 8.0f, 5.0f)
                    horizontalLineTo(4.0f)
                    curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                    verticalLineToRelative(3.8f)
                    horizontalLineToRelative(0.29f)
                    curveToRelative(1.49f, 0f, 2.7f, 1.21f, 2.7f, 2.7f)
                    reflectiveCurveToRelative(-1.21f, 2.7f, -2.7f, 2.7f)
                    horizontalLineTo(2f)
                    verticalLineTo(20.0f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(3.8f)
                    verticalLineToRelative(-0.3f)
                    curveToRelative(0f, -1.49f, 1.21f, -2.7f, 2.7f, -2.7f)
                    reflectiveCurveToRelative(2.7f, 1.21f, 2.7f, 2.7f)
                    verticalLineToRelative(0.3f)
                    horizontalLineTo(17.0f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2.0f)
                    verticalLineToRelative(-4.0f)
                    curveToRelative(1.38f, 0f, 2.5f, -1.12f, 2.5f, -2.5f)
                    reflectiveCurveTo(20.38f, 11.0f, 19.0f, 11.0f)
                    verticalLineTo(7.0f)
                    curveToRelative(0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    horizontalLineToRelative(-4.0f)
                    curveToRelative(0f, -1.38f, -1.12f, -2.5f, -2.5f, -2.5f)
                    close()
                }
            }.also { _extension = it }
        }

    private var _campaign: ImageVector? = null
    val Campaign: ImageVector
        get() {
            if (_campaign != null) return _campaign!!
            return materialIcon(name = "Outlined.Campaign") {
                materialPath {
                    moveTo(18.0f, 11.0f)
                    curveToRelative(0f, 0.67f, 0f, 1.33f, 0f, 2f)
                    curveToRelative(1.2f, 0f, 2.76f, 0f, 4.0f, 0f)
                    curveToRelative(0f, -0.67f, 0f, -1.33f, 0f, -2.0f)
                    curveTo(20.76f, 11.0f, 19.2f, 11.0f, 18.0f, 11.0f)
                    close()
                }
                materialPath {
                    moveTo(16.0f, 17.61f)
                    curveToRelative(0.96f, 0.71f, 2.21f, 1.65f, 3.2f, 2.39f)
                    curveToRelative(0.4f, -0.53f, 0.8f, -1.07f, 1.2f, -1.6f)
                    curveToRelative(-0.99f, -0.74f, -2.24f, -1.68f, -3.2f, -2.4f)
                    curveTo(16.8f, 16.54f, 16.4f, 17.08f, 16.0f, 17.61f)
                    close()
                }
                materialPath {
                    moveTo(20.4f, 5.6f)
                    curveTo(20.0f, 5.07f, 19.6f, 4.53f, 19.2f, 4.0f)
                    curveToRelative(-0.99f, 0.74f, -2.24f, 1.68f, -3.2f, 2.4f)
                    curveToRelative(0.4f, 0.53f, 0.8f, 1.07f, 1.2f, 1.6f)
                    curveTo(18.16f, 7.28f, 19.41f, 6.35f, 20.4f, 5.6f)
                    close()
                }
                materialPath {
                    moveTo(4.0f, 9.0f)
                    curveToRelative(-1.1f, 0f, -2.0f, 0.9f, -2.0f, 2f)
                    verticalLineToRelative(2f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(1f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(1f)
                    lineToRelative(5.0f, 3.0f)
                    verticalLineTo(6.0f)
                    lineTo(8.0f, 9.0f)
                    horizontalLineTo(4.0f)
                    close()
                    moveTo(9.03f, 10.71f)
                    lineTo(11.0f, 9.53f)
                    verticalLineToRelative(4.94f)
                    lineToRelative(-1.97f, -1.18f)
                    lineTo(8.55f, 13.0f)
                    horizontalLineTo(8.0f)
                    horizontalLineTo(4.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(4.0f)
                    horizontalLineToRelative(0.55f)
                    lineTo(9.03f, 10.71f)
                    close()
                }
                materialPath {
                    moveTo(15.5f, 12.0f)
                    curveToRelative(0f, -1.33f, -0.58f, -2.53f, -1.5f, -3.35f)
                    verticalLineToRelative(6.69f)
                    curveTo(14.92f, 14.53f, 15.5f, 13.33f, 15.5f, 12.0f)
                    close()
                }
            }.also { _campaign = it }
        }

    private var _cloud: ImageVector? = null
    val Cloud: ImageVector
        get() {
            if (_cloud != null) return _cloud!!
            return materialIcon(name = "Outlined.Cloud") {
                materialPath {
                    moveTo(12.0f, 6.0f)
                    curveToRelative(2.62f, 0f, 4.88f, 1.86f, 5.39f, 4.43f)
                    lineToRelative(0.3f, 1.5f)
                    lineToRelative(1.53f, 0.11f)
                    curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
                    curveToRelative(0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                    horizontalLineTo(6.0f)
                    curveToRelative(-2.21f, 0f, -4.0f, -1.79f, -4.0f, -4.0f)
                    curveToRelative(0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
                    lineToRelative(1.07f, -0.11f)
                    lineToRelative(0.5f, -0.95f)
                    curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
                    moveToRelative(0f, -2.0f)
                    curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                    curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14.0f)
                    curveToRelative(0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                    horizontalLineToRelative(13.0f)
                    curveToRelative(2.76f, 0f, 5.0f, -2.24f, 5.0f, -5.0f)
                    curveToRelative(0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                    curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                    close()
                }
            }.also { _cloud = it }
        }

    private var _bugreport: ImageVector? = null
    val BugReport: ImageVector
        get() {
            if (_bugreport != null) return _bugreport!!
            return materialIcon(name = "Outlined.BugReport") {
                materialPath {
                    moveTo(20.0f, 8.0f)
                    horizontalLineToRelative(-2.81f)
                    curveToRelative(-0.45f, -0.78f, -1.07f, -1.45f, -1.82f, -1.96f)
                    lineTo(17.0f, 4.41f)
                    lineTo(15.59f, 3.0f)
                    lineToRelative(-2.17f, 2.17f)
                    curveTo(12.96f, 5.06f, 12.49f, 5.0f, 12.0f, 5.0f)
                    reflectiveCurveToRelative(-0.96f, 0.06f, -1.41f, 0.17f)
                    lineTo(8.41f, 3.0f)
                    lineTo(7.0f, 4.41f)
                    lineToRelative(1.62f, 1.63f)
                    curveTo(7.88f, 6.55f, 7.26f, 7.22f, 6.81f, 8.0f)
                    lineTo(4.0f, 8.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2.09f)
                    curveToRelative(-0.05f, 0.33f, -0.09f, 0.66f, -0.09f, 1f)
                    verticalLineToRelative(1f)
                    lineTo(4.0f, 12.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(1f)
                    curveToRelative(0f, 0.34f, 0.04f, 0.67f, 0.09f, 1f)
                    lineTo(4.0f, 16.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2.81f)
                    curveToRelative(1.04f, 1.79f, 2.97f, 3.0f, 5.19f, 3.0f)
                    reflectiveCurveToRelative(4.15f, -1.21f, 5.19f, -3.0f)
                    lineTo(20.0f, 18.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(-2.09f)
                    curveToRelative(0.05f, -0.33f, 0.09f, -0.66f, 0.09f, -1.0f)
                    verticalLineToRelative(-1.0f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-1.0f)
                    curveToRelative(0f, -0.34f, -0.04f, -0.67f, -0.09f, -1.0f)
                    lineTo(20.0f, 10.0f)
                    lineTo(20.0f, 8.0f)
                    close()
                    moveTo(16.0f, 12.0f)
                    verticalLineToRelative(3.0f)
                    curveToRelative(0f, 0.22f, -0.03f, 0.47f, -0.07f, 0.7f)
                    lineToRelative(-0.1f, 0.65f)
                    lineToRelative(-0.37f, 0.65f)
                    curveToRelative(-0.72f, 1.24f, -2.04f, 2f, -3.46f, 2f)
                    reflectiveCurveToRelative(-2.74f, -0.77f, -3.46f, -2.0f)
                    lineToRelative(-0.37f, -0.64f)
                    lineToRelative(-0.1f, -0.65f)
                    curveTo(8.03f, 15.48f, 8.0f, 15.23f, 8.0f, 15.0f)
                    verticalLineToRelative(-4.0f)
                    curveToRelative(0f, -0.23f, 0.03f, -0.48f, 0.07f, -0.7f)
                    lineToRelative(0.1f, -0.65f)
                    lineToRelative(0.37f, -0.65f)
                    curveToRelative(0.3f, -0.52f, 0.72f, -0.97f, 1.21f, -1.31f)
                    lineToRelative(0.57f, -0.39f)
                    lineToRelative(0.74f, -0.18f)
                    curveToRelative(0.31f, -0.08f, 0.63f, -0.12f, 0.94f, -0.12f)
                    curveToRelative(0.32f, 0f, 0.63f, 0.04f, 0.95f, 0.12f)
                    lineToRelative(0.68f, 0.16f)
                    lineToRelative(0.61f, 0.42f)
                    curveToRelative(0.5f, 0.34f, 0.91f, 0.78f, 1.21f, 1.31f)
                    lineToRelative(0.38f, 0.65f)
                    lineToRelative(0.1f, 0.65f)
                    curveToRelative(0.04f, 0.22f, 0.07f, 0.47f, 0.07f, 0.69f)
                    verticalLineToRelative(1f)
                    close()
                    moveTo(10.0f, 14.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(-4.0f)
                    close()
                    moveTo(10.0f, 10.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(-4.0f)
                    close()
                }
            }.also { _bugreport = it }
        }

    private var _error: ImageVector? = null
    val Error: ImageVector
        get() {
            if (_error != null) return _error!!
            return materialIcon(name = "Filled.Error") {
                materialPath {
                    moveTo(12.0f, 2f)
                    curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12.0f)
                    reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                    reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                    reflectiveCurveTo(17.52f, 2f, 12.0f, 2f)
                    close()
                    moveTo(13.0f, 17.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(13.0f, 13.0f)
                    horizontalLineToRelative(-2.0f)
                    lineTo(11.0f, 7.0f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(6.0f)
                    close()
                }
            }.also { _error = it }
        }
}
