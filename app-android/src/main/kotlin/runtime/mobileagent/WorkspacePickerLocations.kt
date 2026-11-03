// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

internal object WorkspacePickerLocations {
    fun label(name: String): String = when (name.lowercase()) {
        "storage", "sdcard" -> "内部存储"
        "download", "downloads" -> "下载"
        "documents", "document" -> "文档"
        else -> name
    }
    fun recommended(name: String): Boolean = name.equals("storage", true) || name.equals("sdcard", true)
    fun priority(name: String): Int = if (name.equals("storage", true)) 0 else 1
}
