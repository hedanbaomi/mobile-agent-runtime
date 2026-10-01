// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

class SettingsViewModelTest {
    @Test
    fun transferValidationReasonIsUsefulAndSecretsAreRedacted() {
        val failure = AppError(
            code = ErrorCode.TRANSFER_INVALID,
            userMessage = "Conversation entry exceeds the 33554432 byte limit; api_key=sk-MARTEST-1234567890",
            retryClass = RetryClass.USER_ACTION,
            stage = "transfer",
            operationId = "export-test",
        ).asException()

        val detail = safeExportFailureDetail(failure)

        assertTrue(detail.contains("33554432 byte limit"))
        assertFalse(detail.contains("MARTEST"))
        assertFalse(detail.contains("sk-MARTEST"))
    }

    @Test
    fun outputFailureDoesNotExposeSafUriOrProviderMessage() {
        val detail = safeExportFailureDetail(IOException("write to content://private.provider/tree/secret failed"))

        assertTrue(detail.contains("无法写入所选位置"))
        assertFalse(detail.contains("content://"))
        assertFalse(detail.contains("private.provider"))
    }
}
