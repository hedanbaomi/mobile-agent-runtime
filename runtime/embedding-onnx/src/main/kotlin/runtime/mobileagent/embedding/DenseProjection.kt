// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** The pinned Sentence Transformers mean -> Dense(768,512,Tanh) head, not executable model code. */
internal class DenseProjection(bytes: ByteArray, private val input: Int, private val output: Int) {
    private val weights: FloatArray
    private val bias: FloatArray

    init {
        require(input in 1..2048 && output in 1..2048 && bytes.size >= 8)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = data.long
        require(headerSize in 2..65_536 && headerSize + 8 <= bytes.size)
        val header = Json.parseToJsonElement(String(bytes, 8, headerSize.toInt(), Charsets.UTF_8)).jsonObject
        require(header.keys.all { it in setOf("__metadata__", "linear.weight", "linear.bias") })
        fun tensor(name: String, shape: List<Int>, start: Int): FloatArray {
            val entry = header.getValue(name).jsonObject
            require(entry.getValue("dtype").jsonPrimitive.content == "F32")
            require(entry.getValue("shape").jsonArray.map { it.jsonPrimitive.int } == shape)
            val count = shape.reduce(Int::times)
            require(entry.getValue("data_offsets").jsonArray.map { it.jsonPrimitive.int } == listOf(start, start + count * 4))
            data.position(8 + headerSize.toInt() + start)
            return FloatArray(count) { data.float.also { require(it.isFinite()) } }
        }
        require(bytes.size == 8 + headerSize.toInt() + (output + input * output) * 4)
        bias = tensor("linear.bias", listOf(output), 0)
        weights = tensor("linear.weight", listOf(output, input), output * 4)
    }

    fun apply(vector: FloatArray): FloatArray {
        require(vector.size == input && vector.all { it.isFinite() })
        return FloatArray(output) { row ->
            var sum = bias[row].toDouble()
            for (column in 0 until input) sum += weights[row * input + column].toDouble() * vector[column]
            kotlin.math.tanh(sum).toFloat()
        }
    }
}
