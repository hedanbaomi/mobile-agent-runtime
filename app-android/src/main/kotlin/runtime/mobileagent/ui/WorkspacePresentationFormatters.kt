// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import java.util.UUID
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.integration.WorkspaceAccessErrorCode
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.skills.tooling.ToolErrorCode

internal fun workspaceBackendLabel(
    backend: WorkspaceBackendType,
    authority: Authority?,
    chinese: Boolean,
): String = when (backend) {
    WorkspaceBackendType.SAF_TREE -> if (chinese) "手机文件夹（SAF）" else "Phone folder (SAF)"
    WorkspaceBackendType.PRIVILEGED -> when (authority) {
        Authority.SHIZUKU -> if (chinese) "Shizuku（ADB 级）" else "Shizuku (ADB-level)"
        Authority.WIRED_ADB -> if (chinese) "电脑 ADB" else "Desktop ADB"
        Authority.ROOT_DIRECT -> if (chinese) "直接 Root" else "Direct Root"
        Authority.SHIZUKU_ROOT -> "Shizuku Root"
        else -> if (chinese) "ADB 级目录" else "ADB-level directory"
    }
    WorkspaceBackendType.INTERNAL -> if (chinese) "应用私有目录" else "App-private directory"
}

internal fun workspaceAccessResultMessage(
    result: WorkspaceAccessResult,
    chinese: Boolean,
): String = when (result) {
    is WorkspaceAccessResult.Success -> if (chinese) {
        "工作区授权已保存；此智能体的所有会话将在下一次运行时使用最新权限。"
    } else {
        "Workspace access is saved; every session of this Agent uses the latest permissions on its next run."
    }
    is WorkspaceAccessResult.NewThreadRequired -> if (chinese) {
        "工作区属于当前会话上下文，切换将创建新会话。"
    } else {
        "This workspace belongs to the current conversation. Switching creates a new conversation."
    }
    is WorkspaceAccessResult.Failure ->
        workspaceAccessFailureMessage(result.code, chinese)
}

internal fun workspaceToolErrorMessage(
    code: ToolErrorCode,
    chinese: Boolean,
): String = when (code) {
    ToolErrorCode.FILE_TOO_LARGE ->
        if (chinese) "文件太大，无法作为文本读取。" else "The file is too large to read as text."
    ToolErrorCode.INVALID_CURSOR ->
        if (chinese) "目录内容已发生变化，请从第一页重新打开。" else "The directory changed. Open it again from the first page."
    ToolErrorCode.PERMISSION_DENIED,
    ToolErrorCode.SHIZUKU_PERMISSION_DENIED,
    ToolErrorCode.CAPABILITY_DENIED,
    ToolErrorCode.AUTHORITY_NOT_GRANTED,
        -> if (chinese) "没有权限访问该工作区，请检查授权。" else "Permission to access this workspace is missing. Check its authorization."
    ToolErrorCode.PATH_OUT_OF_SCOPE ->
        if (chinese) "请求路径超出已授权工作区。" else "The requested path is outside the authorized workspace."
    ToolErrorCode.SYMLINK_FORBIDDEN ->
        if (chinese) "不允许从工作区跟随符号链接。" else "Symbolic links cannot be followed from this workspace."
    ToolErrorCode.ROOT_OPERATION_FORBIDDEN ->
        if (chinese) "不能对设备根目录执行此操作。" else "This operation is not allowed on the device root."
    ToolErrorCode.WORKSPACE_NOT_FOUND ->
        if (chinese) "工作区或目录已不可用，请重新选择。" else "The workspace or directory is unavailable. Select it again."
    ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED ->
        if (chinese) "请先在设置中选定 ADB 级通道。" else "Select an ADB-level authority in Settings first."
    ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
    ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE,
    ToolErrorCode.BRIDGE_DISCONNECTED,
    ToolErrorCode.ADB_DEVICE_OFFLINE,
    ToolErrorCode.ADB_DEVICE_DISCONNECTED,
        -> if (chinese) "工作区暂时不可用，请重新连接后重试。" else "The workspace is temporarily unavailable. Reconnect it and try again."
    ToolErrorCode.BRIDGE_NOT_PAIRED,
    ToolErrorCode.ADB_DEVICE_UNAUTHORIZED,
    ToolErrorCode.ADB_APP_NOT_INSTALLED,
        -> if (chinese) "ADB 设备尚未完成配对或授权，请先在设置中处理。" else "The ADB device is not paired or authorized. Complete setup in Settings."
    ToolErrorCode.WORKSPACE_READ_ONLY ->
        if (chinese) "该工作区为只读，无法执行写入操作。" else "This workspace is read-only."
    ToolErrorCode.QUOTA_EXCEEDED ->
        if (chinese) "目录或输出超过安全上限，请缩小范围后重试。" else "The directory or output exceeds its safety limit. Narrow the request and try again."
    ToolErrorCode.CONFLICT ->
        if (chinese) "目标条目已变化，请读取其最新版本后重试。" else "The requested entry changed. Read its latest version and try again."
    ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED ->
        if (chinese) "此操作不支持版本条件，未执行。" else "This operation cannot enforce a version condition. Nothing was executed."
    ToolErrorCode.UNSUPPORTED_ENTRY ->
        if (chinese) "该文件类型暂不支持。" else "This workspace entry type is not supported."
    ToolErrorCode.OPERATION_UNAVAILABLE ->
        if (chinese) "当前工作区后端不支持此操作。" else "This operation is unavailable on the selected workspace backend."
    ToolErrorCode.INVALID_REQUEST,
    ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH,
        -> if (chinese) "目录请求已失效，请重新打开后重试。" else "The directory request is no longer valid. Open it again and retry."
    ToolErrorCode.TIMEOUT,
    ToolErrorCode.IO_ERROR,
        -> if (chinese) "读取目录失败，请重试。" else "The directory could not be read. Try again."
    else -> if (chinese) "工作区运行时发生内部错误。" else "An internal workspace runtime error occurred."
}

internal fun workspaceAccessFailureMessage(
    code: WorkspaceAccessErrorCode,
    chinese: Boolean,
): String = when (code) {
    WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND ->
        if (chinese) "工作区已不可用，请重新选择。" else "The workspace is unavailable. Select it again."
    WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED ->
        if (chinese) "请先在设置中选定 ADB 级通道。" else "Select an ADB-level authority in Settings first."
    WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE ->
        if (chinese) "ADB 级通道暂时不可用，请重新连接后重试。" else "The ADB-level authority is temporarily unavailable. Reconnect it and try again."
    WorkspaceAccessErrorCode.CAPABILITY_DENIED,
    WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED,
        -> if (chinese) "没有权限访问该工作区，请重新授权。" else "Permission to access this workspace is missing. Authorize it again."
    WorkspaceAccessErrorCode.CONFLICT ->
        if (chinese) "工作区状态已变化，请刷新后重试。" else "The workspace state changed. Refresh it and try again."
    WorkspaceAccessErrorCode.UNSUPPORTED ->
        if (chinese) "当前工作区不支持此操作。" else "This workspace does not support the operation."
    WorkspaceAccessErrorCode.INVALID_REQUEST ->
        if (chinese) "工作区请求无效，请重新选择。" else "The workspace request is invalid. Select it again."
    WorkspaceAccessErrorCode.PERSISTENCE_FAILED ->
        if (chinese) "工作区设置未能保存，请重试。" else "The workspace setting could not be saved. Try again."
    WorkspaceAccessErrorCode.UNKNOWN_OUTCOME ->
        if (chinese) "工作区运行时发生内部错误。" else "An internal workspace runtime error occurred."
}

internal fun newWorkspaceId(prefix: String): String =
    "$prefix-${UUID.randomUUID().toString().replace("-", "")}".take(128)
