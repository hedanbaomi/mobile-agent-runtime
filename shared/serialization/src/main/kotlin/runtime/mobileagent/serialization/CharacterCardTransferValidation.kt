// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import runtime.mobileagent.domain.ImportedCharacterCard

internal object CharacterCardTransferValidation {
    fun validate(card: ImportedCharacterCard?, operationId: String) {
        if (card == null) return
        try { CharacterCardSupport.validate(card) }
        catch (_: IllegalArgumentException) {
            throw runtime.mobileagent.domain.AppError(runtime.mobileagent.domain.ErrorCode.TRANSFER_INVALID,
                "Invalid character card backup", runtime.mobileagent.domain.RetryClass.USER_ACTION, "transfer", operationId).asException()
        }
    }
}
