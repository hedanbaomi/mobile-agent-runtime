// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Outcome of a bounded read; [TooLarge] is reported before unbounded growth. */
sealed interface TrpgMediaImageBytes {
    data class Loaded(val bytes: ByteArray) : TrpgMediaImageBytes

    data object TooLarge : TrpgMediaImageBytes
}

/**
 * Image admission arithmetic. The caller never reads a whole user file into
 * memory: [boundedRead] stops at the byte budget and the decode pass uses
 * [inSampleSize] so both decoded edges stay at or below the limit.
 */
object TrpgMediaImageBudget {
    private const val BUFFER_BYTES = 16 * 1024

    fun boundedRead(input: InputStream, maxBytes: Long = TrpgMediaPolicy.MAX_IMAGE_BYTES): TrpgMediaImageBytes {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return TrpgMediaImageBytes.TooLarge
            output.write(buffer, 0, read)
        }
        return TrpgMediaImageBytes.Loaded(output.toByteArray())
    }

    fun inSampleSize(width: Int, height: Int, maxEdge: Int = TrpgMediaPolicy.MAX_IMAGE_EDGE): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxEdge || height / sample > maxEdge) sample *= 2
        return sample
    }

    fun isWithinPixelBudget(
        width: Int,
        height: Int,
        maxPixels: Long = TrpgMediaPolicy.MAX_IMAGE_PIXELS,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        return width.toLong() * height.toLong() <= maxPixels
    }
}
