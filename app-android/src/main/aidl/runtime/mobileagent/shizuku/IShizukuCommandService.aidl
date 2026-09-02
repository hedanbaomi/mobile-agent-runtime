// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku;

import android.os.ParcelFileDescriptor;

parcelable ShizukuShellResponse;

/**
 * Narrow UserService interface for the optional Shizuku bridge.
 *
 * The interface exposes the existing typed file operations plus a narrow,
 * session-authenticated one-shot shell transaction.  The shell operation has
 * no executable or UID selector: the UserService always owns that choice.
 */
interface IShizukuCommandService {
    /** Reserved destroy transaction defined by the Shizuku UserService API. */
    void destroy() = 16777114;

    /** Returns a bounded JSON status object without an absolute path. */
    String getStatus() = 1;

    /** Lists a relative path; the empty string addresses the fixed workspace root. */
    String list(String relativePath) = 2;

    /** Reads bounded UTF-8 text from a relative path. */
    String read(String relativePath, int maxBytes) = 3;

    /** Creates or replaces bounded UTF-8 text at a relative path. */
    String write(String relativePath, in byte[] utf8Content, boolean replaceExisting) = 4;

    /** Creates one directory at a relative path. */
    String mkdir(String relativePath) = 5;

    /** Deletes one file or an empty directory at a relative path. */
    String delete(String relativePath) = 6;

    /**
     * Session-bound variants.  The original transaction IDs remain reserved
     * for protocol compatibility, but the app uses these methods exclusively;
     * the UserService rejects calls without a handshake session.
     */
    String listSession(String sessionId, String relativePath) = 9;
    String readSession(String sessionId, String relativePath, int maxBytes) = 10;
    String writeSession(String sessionId, String relativePath, in byte[] utf8Content, boolean replaceExisting) = 11;
    String mkdirSession(String sessionId, String relativePath) = 12;
    String deleteSession(String sessionId, String relativePath) = 13;
    /** Returns bounded metadata for one relative file or directory. */
    String statSession(String sessionId, String relativePath) = 16;
    /** Atomically moves one relative file or directory within the fixed root. */
    String moveSession(String sessionId, String sourcePath, String destinationPath, boolean replaceExisting) = 17;

    /**
     * Starts an asynchronous one-shot /system/bin/sh -s invocation.  Output
     * and the small completion envelope are returned through independent
     * ParcelFileDescriptor pipes; no unbounded output crosses Binder.
     */
    ShizukuShellResponse startShell(
        String sessionId,
        String callId,
        String command,
        String cwd,
        long timeoutMs,
        int maxStdoutBytes,
        int maxStderrBytes
    ) = 14;

    /** Requests cancellation of one previously accepted shell call. */
    boolean cancelShell(String sessionId, String callId) = 15;

    /**
     * Opens the service's typed device-root view.  The response contains only
     * an opaque directory handle and child names; it never contains an
     * absolute path.  The handle is bound to this authenticated UserService
     * session and dies with that service.
     */
    String openDirectoryRootSession(String sessionId, int maxEntries) = 18;

    /** Lists a previously returned opaque directory handle. */
    String browseDirectorySession(String sessionId, String directoryHandle, int maxEntries) = 19;

    /**
     * Binds an opaque directory handle to a current-agent workspace token.
     * The token is also session-bound and is never a model-facing path.
     */
    String attachDirectorySession(String sessionId, String directoryHandle) = 20;

    /** Session-bound typed operations for an attached directory workspace. */
    String listWorkspaceSession(String sessionId, String workspaceHandle, String relativePath, int maxEntries) = 21;
    String readWorkspaceSession(String sessionId, String workspaceHandle, String relativePath, int maxBytes) = 22;
    String writeWorkspaceSession(String sessionId, String workspaceHandle, String relativePath, in byte[] utf8Content, boolean replaceExisting) = 23;
    String mkdirWorkspaceSession(String sessionId, String workspaceHandle, String relativePath) = 24;
    String deleteWorkspaceSession(String sessionId, String workspaceHandle, String relativePath) = 25;
    String statWorkspaceSession(String sessionId, String workspaceHandle, String relativePath) = 26;
    String moveWorkspaceSession(String sessionId, String workspaceHandle, String sourcePath, String destinationPath, boolean replaceExisting) = 27;
}
