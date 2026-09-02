// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Encodes the service-owned recovery locator exchanged with the app.
 *
 * The locator is a versioned, integrity-checked opaque envelope.  Its path is
 * never rendered or logged; it is decoded only inside the UserService and is
 * checked again against that service's fixed root before a new workspace
 * handle is created.  At-rest encryption of this byte sequence belongs to the
 * app container, not this low-level service.
 */
internal object ShizukuRecoveryLocatorCodec {
    private val magic = "MAR-SHIZUKU-LOCATOR".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val DIGEST_BYTES = 32
    private const val LENGTH_BYTES = 4
    private const val MAX_PATH_BYTES = 4 * 1024
    private const val MAX_FILE_KEY_BYTES = 512

    fun encode(path: Path): ByteArray? {
        val normalized = path.toAbsolutePath().normalize()
        val pathBytes = normalized.toString().toByteArray(StandardCharsets.UTF_8)
        if (pathBytes.isEmpty() || pathBytes.size > MAX_PATH_BYTES) return null
        // A path alone is not an identity: after a directory is replaced at
        // the same location, reopening it would silently bind a different
        // workspace.  Refuse to issue a recoverable locator when this file
        // system cannot provide a stable file key instead of weakening the
        // reattach check.
        val fileKeyBytes = runCatching {
            Files.readAttributes(
                normalized,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).fileKey()?.toString()?.takeIf { it.isNotBlank() }?.toByteArray(StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= MAX_FILE_KEY_BYTES } ?: return null
        val bodySize = magic.size + 1 + LENGTH_BYTES + pathBytes.size + LENGTH_BYTES + fileKeyBytes.size
        val body = ByteBuffer.allocate(bodySize).order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(VERSION)
            .putInt(pathBytes.size)
            .put(pathBytes)
            .putInt(fileKeyBytes.size)
            .put(fileKeyBytes)
            .array()
        val digest = sha256(body)
        return ByteBuffer.allocate(body.size + DIGEST_BYTES)
            .put(body)
            .put(digest)
            .array()
    }

    fun decode(bytes: ByteArray?): DecodedLocator? {
        if (bytes == null) return null
        val minimum = magic.size + 1 + LENGTH_BYTES + LENGTH_BYTES + DIGEST_BYTES
        if (bytes.size < minimum || bytes.size > WorkspaceRecoveryLimits.MAX_BYTES) return null
        val bodySize = bytes.size - DIGEST_BYTES
        val body = bytes.copyOfRange(0, bodySize)
        val expectedDigest = sha256(body)
        val actualDigest = bytes.copyOfRange(bodySize, bytes.size)
        if (!MessageDigest.isEqual(expectedDigest, actualDigest)) return null

        val input = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val observedMagic = ByteArray(magic.size)
        input.get(observedMagic)
        if (!observedMagic.contentEquals(magic) || input.get() != VERSION) return null
        val pathLength = input.int
        if (pathLength <= 0 || pathLength > MAX_PATH_BYTES || input.remaining() < pathLength + LENGTH_BYTES) return null
        val pathBytes = ByteArray(pathLength)
        input.get(pathBytes)
        val fileKeyLength = input.int
        if (fileKeyLength !in 1..MAX_FILE_KEY_BYTES || input.remaining() != fileKeyLength) return null
        val fileKeyBytes = ByteArray(fileKeyLength)
        input.get(fileKeyBytes)
        val pathText = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(pathBytes))
                .toString()
        }.getOrNull() ?: return null
        val path = runCatching { Paths.get(pathText) }.getOrNull() ?: return null
        if (!path.isAbsolute) return null
        val fileKey = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(fileKeyBytes))
                .toString()
                .takeIf { it.isNotBlank() }
        }.getOrNull() ?: return null
        return if (path == path.toAbsolutePath().normalize()) {
            DecodedLocator(path, fileKey)
        } else {
            null
        }
    }

    fun identityMatches(path: Path, expectedFileKey: String?): Boolean {
        if (expectedFileKey.isNullOrBlank()) return false
        val current = runCatching {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).fileKey()?.toString()
        }.getOrNull()
        return current == expectedFileKey
    }

    data class DecodedLocator(
        val path: Path,
        val fileKey: String?,
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}

internal object WorkspaceRecoveryLimits {
    const val MAX_BYTES: Int = 16 * 1024
}
