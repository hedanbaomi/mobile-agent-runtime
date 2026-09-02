// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

/**
 * UI-only projection of a workspace the user selected.
 *
 * This type is never copied into AgentWorkspaceUi, tool schema, prompts or
 * diagnostics. Recovery locators, content URIs and provider tokens are rejected
 * at construction; privileged titles may be a local filesystem path.
 */
enum class WorkspaceUiKind {
    APP_PRIVATE,
    SAF,
    PRIVILEGED_SHIZUKU,
    PRIVILEGED_WIRED,
}

data class WorkspaceUiPresentation(
    val workspaceId: String,
    val kind: WorkspaceUiKind,
    val title: String,
    val breadcrumb: String? = null,
) {
    init {
        require(workspaceId.isNotBlank() && workspaceId.length <= 256) { "Workspace presentation id is invalid" }
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "Workspace presentation title is invalid" }
        require(!containsSensitive(title)) { "Workspace presentation title contains a forbidden locator" }
        require(breadcrumb == null || (breadcrumb.length <= MAX_TITLE && !containsSensitive(breadcrumb))) {
            "Workspace presentation breadcrumb contains a forbidden locator"
        }
    }

    companion object {
        const val MAX_TITLE: Int = 256
        const val APP_PRIVATE_TITLE_ZH: String = "应用私有工作区"
        const val APP_PRIVATE_TITLE_EN: String = "App private workspace"

        fun containsSensitive(value: String): Boolean {
            val lower = value.lowercase()
            return lower.contains("content://") ||
                lower.contains("file://") ||
                lower.contains("android.provider") ||
                TOKEN_MARKERS.any { lower.contains(it) }
        }

        private val TOKEN_MARKERS = listOf("recovery_locator", "recoverylocator", "provider_token")
    }
}

/** Derive a UI-safe folder label that is allowed on AgentWorkspaceUi. */
fun persistedWorkspaceFolderLabel(
    backendType: WorkspaceBackendType,
    requestedName: String,
    ordinal: Int,
    fullDevice: Boolean,
): String {
    if (backendType == WorkspaceBackendType.INTERNAL) return WorkspaceUiPresentation.APP_PRIVATE_TITLE_ZH
    if (fullDevice) return "设备文件区"
    val decoded = runCatching {
        java.net.URLDecoder.decode(requestedName.trim(), Charsets.UTF_8.name())
    }.getOrDefault(requestedName.trim())
    val cleaned = decoded
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringAfterLast(':')
        .trim()
    val usable = cleaned.isNotBlank() &&
        !WorkspaceUiPresentation.containsSensitive(cleaned) &&
        !cleaned.contains("://") &&
        '%' !in cleaned
    return if (usable) {
        cleaned.take(128)
    } else {
        when (backendType) {
            WorkspaceBackendType.PRIVILEGED -> "ADB 目录 $ordinal"
            WorkspaceBackendType.SAF_TREE -> "用户工作区 $ordinal"
            WorkspaceBackendType.INTERNAL -> WorkspaceUiPresentation.APP_PRIVATE_TITLE_ZH
        }
    }
}

fun WorkspaceUiKind.defaultTitle(chinese: Boolean): String = when (this) {
    WorkspaceUiKind.APP_PRIVATE ->
        if (chinese) WorkspaceUiPresentation.APP_PRIVATE_TITLE_ZH else WorkspaceUiPresentation.APP_PRIVATE_TITLE_EN
    WorkspaceUiKind.SAF -> if (chinese) "已授权文件夹" else "Authorized folder"
    WorkspaceUiKind.PRIVILEGED_SHIZUKU -> if (chinese) "Shizuku 目录" else "Shizuku directory"
    WorkspaceUiKind.PRIVILEGED_WIRED -> if (chinese) "电脑 ADB 目录" else "Desktop ADB directory"
}

fun workspaceUiKind(workspace: Workspace, authority: Authority?): WorkspaceUiKind = when (workspace.backendType) {
    WorkspaceBackendType.INTERNAL -> WorkspaceUiKind.APP_PRIVATE
    WorkspaceBackendType.SAF_TREE -> WorkspaceUiKind.SAF
    WorkspaceBackendType.PRIVILEGED -> when (authority) {
        Authority.WIRED_ADB -> WorkspaceUiKind.PRIVILEGED_WIRED
        else -> WorkspaceUiKind.PRIVILEGED_SHIZUKU
    }
}

/**
 * Safe title when [WorkspaceUiPresentationStore] has no row. This is not an
 * authorization or recovery source of truth; losing SharedPreferences must
 * not disable the workspace. Canonical locator/URI values are rejected.
 */
fun fallbackWorkspaceUiPresentation(
    workspace: Workspace,
    authority: Authority?,
    chinese: Boolean,
): WorkspaceUiPresentation? {
    val kind = workspaceUiKind(workspace, authority)
    val title = when (kind) {
        WorkspaceUiKind.APP_PRIVATE -> kind.defaultTitle(chinese)
        WorkspaceUiKind.SAF -> {
            val candidate = workspace.displayName.trim()
            when {
                candidate.isBlank() || WorkspaceUiPresentation.containsSensitive(candidate) || candidate.contains("://") ->
                    kind.defaultTitle(chinese)
                else -> persistedWorkspaceFolderLabel(
                    backendType = WorkspaceBackendType.SAF_TREE,
                    requestedName = candidate,
                    ordinal = 1,
                    fullDevice = workspace.scope == WorkspaceScope.FULL_DEVICE_FILES,
                )
            }
        }
        WorkspaceUiKind.PRIVILEGED_SHIZUKU,
        WorkspaceUiKind.PRIVILEGED_WIRED,
        -> privilegedUiTitle(workspace.displayName) ?: kind.defaultTitle(chinese)
    }
    return runCatching {
        WorkspaceUiPresentation(workspaceId = workspace.id, kind = kind, title = title)
    }.getOrNull()
}

/**
 * Build a UI title from a user-selected privileged path or browse trail.
 * Paths such as `/storage/emulated/0/...` are allowed; URIs are not.
 */
fun privilegedUiTitle(pathOrTrail: String, trail: List<String> = emptyList()): String? {
    val candidate = pathOrTrail.trim().ifBlank {
        val joined = trail.filter { it.isNotBlank() && it != "根目录" && !it.equals("root", true) }
        if (joined.isEmpty()) "" else "/" + joined.joinToString("/")
    }
    if (candidate.isBlank() || WorkspaceUiPresentation.containsSensitive(candidate)) return null
    if (candidate.contains("://")) return null
    val normalized = if (candidate.startsWith("/")) candidate else "/$candidate"
    return normalized.take(WorkspaceUiPresentation.MAX_TITLE)
}

fun safTreeUiTitle(context: Context, uri: Uri): String {
    val display = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val last = docId.substringAfterLast(':').substringAfterLast('/').trim()
        last.takeIf { it.isNotBlank() && !WorkspaceUiPresentation.containsSensitive(it) && !it.contains("://") }
    }.getOrNull()
    if (!display.isNullOrBlank()) return display.take(WorkspaceUiPresentation.MAX_TITLE)
    val queried = runCatching {
        val tree = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(tree, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim() else null
            }
    }.getOrNull()
    val name = queried?.takeIf { it.isNotBlank() && !WorkspaceUiPresentation.containsSensitive(it) && !it.contains("://") }
    return name?.take(WorkspaceUiPresentation.MAX_TITLE) ?: "已授权文件夹"
}

/**
 * Process-local plus durable UI labels keyed by workspace id. Never read by
 * tool schema, prompt assembly or diagnostic event builders. This store is
 * not the authorization or recovery source of truth; `android:allowBackup`
 * remains false, so a reinstall drops labels and the canonical Workspace row
 * supplies a safe fallback.
 */
class WorkspaceUiPresentationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(presentation: WorkspaceUiPresentation) {
        val encoded = listOf(
            presentation.kind.name,
            presentation.title.replace('\n', ' '),
            presentation.breadcrumb.orEmpty().replace('\n', ' '),
        ).joinToString("\u001f")
        prefs.edit().putString(presentation.workspaceId, encoded).apply()
    }

    fun get(workspaceId: String): WorkspaceUiPresentation? {
        val raw = prefs.getString(workspaceId, null) ?: return null
        val parts = raw.split('\u001f')
        if (parts.size < 2) return null
        val kind = runCatching { WorkspaceUiKind.valueOf(parts[0]) }.getOrNull() ?: return null
        val title = parts[1].trim()
        val breadcrumb = parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
        return runCatching {
            WorkspaceUiPresentation(workspaceId = workspaceId, kind = kind, title = title, breadcrumb = breadcrumb)
        }.getOrNull()
    }

    fun remove(workspaceId: String) {
        prefs.edit().remove(workspaceId).apply()
    }

    companion object {
        private const val PREFS = "workspace-ui-presentation"
    }
}
