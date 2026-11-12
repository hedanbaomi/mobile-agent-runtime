// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.SettingsAuthoritySnapshot

/**
 * Narrow read-only query interface for authority snapshot facts.
 * Prevents read-only callers from mutating authority intent or policy.
 */
interface AuthorityQueryPort {
    fun snapshot(): SettingsAuthoritySnapshot
    fun refresh(): SettingsAuthoritySnapshot
}

fun interface AuthorityQueryPortProvider {
    fun authorityQueryPort(): AuthorityQueryPort
}
