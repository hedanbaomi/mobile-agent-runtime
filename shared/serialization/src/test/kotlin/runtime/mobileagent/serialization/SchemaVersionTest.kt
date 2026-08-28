// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode

class SchemaVersionTest {
    @Test
    fun unknownMajorIsRejected() {
        val ex = assertThrows(AppException::class.java) {
            SchemaVersion.requireSupported(99, "op-1")
        }
        assert(ex.error.code == ErrorCode.SCHEMA_UNSUPPORTED)
    }
}
