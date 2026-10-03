// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.io.IOException

/**
 * Once createDocument returns, a document exists even if opening, closing or verifying it
 * fails. Without a proven rollback, every failure in that phase is unsafe to retry.
 * Keep this boundary separate from pre-create validation, whose typed errors stay intact.
 */
internal fun <T> completeSafCreatedDocument(action: () -> T): T = try {
    action()
} catch (_: IOException) {
    InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
} catch (_: RuntimeException) {
    InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
}

/** Creation errors retain their classification; only verification runs inside the ambiguous phase. */
internal fun <C, T> completeSafCreatedDocument(create: () -> C, verify: (C) -> T): T {
    val created = create()
    return completeSafCreatedDocument { verify(created) }
}
