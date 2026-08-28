// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

object SchemaVersion {
    const val CURRENT = 1

    fun requireSupported(schemaVersion: Int, operationId: String) {
        if (schemaVersion != CURRENT) {
            throw AppError(
                code = ErrorCode.SCHEMA_UNSUPPORTED,
                userMessage = "Unsupported schemaVersion $schemaVersion",
                retryClass = RetryClass.USER_ACTION,
                stage = "import",
                operationId = operationId,
            ).asException()
        }
    }
}
