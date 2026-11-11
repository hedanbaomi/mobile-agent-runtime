// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.feature.chat

/** Host error codes remain usable when their human-readable wording changes language. */
fun hasContextOverflowCode(vararg notices: String?): Boolean =
    notices.any { it?.contains("CONTEXT_OVERFLOW") == true }
