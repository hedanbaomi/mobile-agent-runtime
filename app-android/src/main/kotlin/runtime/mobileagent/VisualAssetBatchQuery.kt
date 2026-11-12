// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.data.SqlConnection

/**
 * Batch query metadata for multiple visual assets in a single SQL operation.
 * Defensively chunks requests by 100 to stay well under SQLite parameter limits.
 */
internal fun queryAssetMetadataBatch(
    db: SqlConnection,
    assetIds: List<String>,
): Map<String, Triple<String, Long, String>> {
    val uniqueIds = assetIds.distinct()
    if (uniqueIds.isEmpty()) return emptyMap()
    return uniqueIds.chunked(100).flatMap { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        val sql = "SELECT a.id, b.media_type, b.byte_length, a.blob_hash " +
            "FROM assets a JOIN blobs b ON b.hash=a.blob_hash " +
            "WHERE a.id IN ($placeholders)"
        db.query(sql, chunk).map { row ->
            row.string("id") to Triple(
                row.string("media_type"),
                row.string("byte_length").toLong(),
                row.string("blob_hash"),
            )
        }
    }.toMap()
}
