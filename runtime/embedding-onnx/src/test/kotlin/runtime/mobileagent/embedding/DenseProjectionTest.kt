// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DenseProjectionTest {
    private fun fixture(): ByteArray {
        val header = """{"linear.bias":{"dtype":"F32","shape":[2],"data_offsets":[0,8]},"linear.weight":{"dtype":"F32","shape":[2,2],"data_offsets":[8,24]}}""".toByteArray()
        return ByteBuffer.allocate(8+header.size+24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(header.size.toLong()); put(header)
            listOf(0f,1f,1f,0f,0f,2f).forEach { putFloat(it) }
        }.array()
    }
    @Test fun publishedDenseTanhOrderIsPreserved() {
        assertArrayEquals(floatArrayOf(kotlin.math.tanh(1.0).toFloat(),kotlin.math.tanh(5.0).toFloat()),
            DenseProjection(fixture(),2,2).apply(floatArrayOf(1f,2f)), 0.000001f)
    }
    @Test fun truncatedOrWrongShapeProjectionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { DenseProjection(fixture().dropLast(1).toByteArray(),2,2) }
        assertThrows(IllegalArgumentException::class.java) { DenseProjection(fixture(),3,2) }
    }
}
