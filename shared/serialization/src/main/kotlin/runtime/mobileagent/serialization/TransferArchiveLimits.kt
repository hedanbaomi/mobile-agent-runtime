// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

/**
 * Fixed resource limits for the streaming transfer archive.  The limits are part of the public
 * contract so hosts can reject an archive before allocating unbounded memory or writing a partial
 * database.  A single content entry is intentionally aligned with the knowledge import limit.
 */
object TransferArchiveLimits {
    const val MAX_METADATA_BYTES: Long = 16L * 1024L * 1024L
    const val MAX_ENTRY_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 512L * 1024L * 1024L
    const val MAX_ENTRIES: Int = 8_192
    const val MAX_COMPRESSION_RATIO: Long = 100L
    const val MAX_ENTRY_NAME_LENGTH: Int = 512
}
