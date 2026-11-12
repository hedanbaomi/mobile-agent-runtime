// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RunRecord

/**
 * Narrow execution port for Root session stamp validation and run admission.
 */
interface RootExecutionPort {
    fun currentExecutionStamp(authority: Authority, requireShellConsent: Boolean = false): RootExecutionStamp?
    fun admitNewRun(record: RunRecord): RunRecord
}

fun interface RootExecutionPortProvider {
    fun rootExecutionPort(): RootExecutionPort
}
