// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode

class ParameterMergerTest {
    @Test
    fun reservedFieldInCustomJsonIsRejected() {
        val ex = assertThrows(AppException::class.java) {
            ParameterMerger.merge(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                """{"temperature":0.2,"model":"sneaky"}""",
                mapOf("model" to JsonPrimitive("real")),
                "op",
            )
        }
        assertEquals(ErrorCode.INVALID_CONFIG, ex.error.code)
    }

    @Test
    fun nestedApiKeyIsRejected() {
        val ex = assertThrows(AppException::class.java) {
            ParameterMerger.merge(
                emptyMap(), emptyMap(), emptyMap(),
                """{"headers":{"api_key":"x"}}""",
                emptyMap(),
                "op",
            )
        }
        assertEquals(ErrorCode.INVALID_CONFIG, ex.error.code)
    }

    @Test
    fun customObjectMergesTemperature() {
        val result = ParameterMerger.merge(
            emptyMap(), emptyMap(), emptyMap(),
            """{"temperature":0.5}""",
            mapOf("model" to JsonPrimitive("m")),
            "op",
        )
        assertEquals("0.5", result.getValue("temperature").toString())
    }
}
