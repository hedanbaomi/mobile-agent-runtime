// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
enum class RootWorkspaceRootKind {
    DIRECTORY,
    DEVICE_ROOT,
}

fun RootWorkspaceOrigin.canonicalSha256(): String = RootRecoveryLocatorCodec.lengthPrefixedSha256(
    listOf(
        "MAR-ROOT-WORKSPACE-ORIGIN-V1",
        workspaceId,
        authority.name,
        PrivilegeTier.ROOT.name,
        appInstanceId,
        packageName,
        appUid.toString(),
        androidUserId.toString(),
        signingSha256,
        generationId,
    ),
)

/**
 * OS-level FD and stat identity observed directly on an opened file or directory descriptor.
 * `mountId` comes from `/proc/self/fdinfo/<fd>` or `statx(STATX_MNT_ID)`; `<= 0` means
 * mount identity is unavailable and must fail closed. Inode zero is a real value on Android
 * FUSE mount roots; unavailable observations are null, never a fabricated zero inode.
 */
data class RootFdNodeIdentity(
    val deviceId: Long,
    val inode: Long,
    val mountId: Long,
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val isSymlink: Boolean,
    val hardlinkCount: Long,
    val modeOctal: Int,
    val ownerUid: Int,
    val ownerGid: Int,
    val selinuxLabel: String? = null,
) {
    val hasValidKernelIdentity: Boolean
        get() = deviceId != 0L && inode >= 0L && mountId > 0L

    fun sameKernelObjectAs(other: RootFdNodeIdentity): Boolean =
        hasValidKernelIdentity &&
            deviceId == other.deviceId &&
            inode == other.inode &&
            mountId == other.mountId
}

/**
 * Service-issued recovery payload (`locatorVersion = 1`, `<= 8192` bytes).
 *
 * Exchanged only in transient authenticated memory between the Root service and Host
 * before Host encrypts it under Root AAD v2 (`RootWorkspaceBindingAad`). Never expose
 * [absoluteRootPath] in `toString`, logs, or Agent tool arguments.
 */
data class RootRecoveryLocatorPayload(
    val locatorVersion: Int = RootRecoveryLocatorCodec.CURRENT_VERSION,
    val originSha256: String,
    val workspaceId: String,
    val workspaceGenerationId: String,
    val authority: Authority,
    val rootKind: RootWorkspaceRootKind,
    val absoluteRootPath: String,
    val rootDeviceId: Long,
    val rootInode: Long,
    val rootMountId: Long,
) {
    init {
        require(locatorVersion == RootRecoveryLocatorCodec.CURRENT_VERSION) { "Unsupported Root locator version" }
        require(RootPathGuards.isSha256(originSha256)) { "Invalid origin SHA-256" }
        require(RootPathGuards.isSafeId(workspaceId)) { "Invalid workspaceId" }
        require(RootPathGuards.isOpaqueId(workspaceGenerationId)) { "Invalid workspaceGenerationId" }
        require(authority.isRootAuthority) { "Root locator requires a Root authority" }
        require(RootRecoveryLocatorCodec.isValidAbsoluteRootPath(absoluteRootPath)) { "Invalid absolute root path" }
        if (rootKind == RootWorkspaceRootKind.DEVICE_ROOT) {
            require(absoluteRootPath == "/") { "DEVICE_ROOT locator must bind '/'" }
        }
        require(rootDeviceId != 0L && rootInode >= 0L && rootMountId > 0L) {
            "Root locator requires non-zero deviceId, positive mountId, and non-negative inode"
        }
    }

    override fun toString(): String =
        "RootRecoveryLocatorPayload(version=$locatorVersion, workspaceId=$workspaceId, authority=$authority, rootKind=$rootKind)"
}

object RootRecoveryLocatorCodec {
    private val MAGIC = "MAR-ROOT-RECOVERY-LOCATOR".toByteArray(StandardCharsets.US_ASCII)
    const val CURRENT_VERSION: Int = 1
    const val MAX_LOCATOR_BYTES: Int = 8_192
    const val MAX_ROOT_PATH_BYTES: Int = 4_096
    private const val DIGEST_BYTES: Int = 32

    fun isValidAbsoluteRootPath(path: String): Boolean {
        if (path.isEmpty() || !path.startsWith("/") || path.contains('\u0000')) return false
        val utf8 = encodeStrictUtf8(path) ?: return false
        if (utf8.isEmpty() || utf8.size > MAX_ROOT_PATH_BYTES) return false
        if (path == "/") return true
        if (path.endsWith("/") || path.contains("//")) return false
        return path.drop(1).split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".."
        }
    }

    fun encode(payload: RootRecoveryLocatorPayload): ByteArray {
        val bodyStream = ByteArrayOutputStream()
        val out = DataOutputStream(bodyStream)
        out.write(MAGIC)
        out.writeInt(payload.locatorVersion)
        writeBytes(out, requireNotNull(encodeStrictUtf8(payload.originSha256)))
        writeBytes(out, requireNotNull(encodeStrictUtf8(payload.workspaceId)))
        writeBytes(out, requireNotNull(encodeStrictUtf8(payload.workspaceGenerationId)))
        writeBytes(out, requireNotNull(encodeStrictUtf8(payload.authority.name)))
        writeBytes(out, requireNotNull(encodeStrictUtf8(payload.rootKind.name)))
        writeBytes(out, requireNotNull(encodeStrictUtf8(payload.absoluteRootPath)))
        out.writeLong(payload.rootDeviceId)
        out.writeLong(payload.rootInode)
        out.writeLong(payload.rootMountId)
        val body = bodyStream.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        val full = ByteBuffer.allocate(body.size + DIGEST_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(body)
            .put(digest)
            .array()
        require(full.size <= MAX_LOCATOR_BYTES) { "Encoded Root recovery locator exceeds 8192 bytes" }
        return full
    }

    fun decode(bytes: ByteArray?): RootRecoveryLocatorPayload? {
        if (bytes == null) return null
        val minBytes = MAGIC.size + 4 + (6 * 4) + (3 * 8) + DIGEST_BYTES
        if (bytes.size !in minBytes..MAX_LOCATOR_BYTES) return null
        val bodySize = bytes.size - DIGEST_BYTES
        val body = bytes.copyOfRange(0, bodySize)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(body)
        val actualDigest = bytes.copyOfRange(bodySize, bytes.size)
        if (!MessageDigest.isEqual(expectedDigest, actualDigest)) return null

        val buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val version = buffer.int
        if (version != CURRENT_VERSION) return null

        val originSha256 = readStrictString(buffer, 64) ?: return null
        val workspaceId = readStrictString(buffer, 256) ?: return null
        val workspaceGenerationId = readStrictString(buffer, 128) ?: return null
        val authorityName = readStrictString(buffer, 64) ?: return null
        val rootKindName = readStrictString(buffer, 32) ?: return null
        val absoluteRootPath = readStrictString(buffer, MAX_ROOT_PATH_BYTES) ?: return null
        if (buffer.remaining() != 24) return null
        val rootDeviceId = buffer.long
        val rootInode = buffer.long
        val rootMountId = buffer.long

        val authority = runCatching { Authority.valueOf(authorityName) }.getOrNull() ?: return null
        val rootKind = runCatching { RootWorkspaceRootKind.valueOf(rootKindName) }.getOrNull() ?: return null
        return runCatching {
            RootRecoveryLocatorPayload(
                locatorVersion = version,
                originSha256 = originSha256,
                workspaceId = workspaceId,
                workspaceGenerationId = workspaceGenerationId,
                authority = authority,
                rootKind = rootKind,
                absoluteRootPath = absoluteRootPath,
                rootDeviceId = rootDeviceId,
                rootInode = rootInode,
                rootMountId = rootMountId,
            )
        }.getOrNull()
    }

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    internal fun lengthPrefixedSha256(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        for (field in fields) {
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            lenBuf.clear()
            lenBuf.putInt(bytes.size)
            digest.update(lenBuf.array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun readStrictString(buffer: ByteBuffer, maxBytes: Int): String? {
        if (buffer.remaining() < 4) return null
        val length = buffer.int
        if (length !in 1..maxBytes || length > buffer.remaining()) return null
        val slice = ByteArray(length)
        buffer.get(slice)
        return decodeStrictUtf8(slice)
    }

    private fun encodeStrictUtf8(value: String): ByteArray? = runCatching {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = encoder.encode(java.nio.CharBuffer.wrap(value))
        ByteArray(out.remaining()).also(out::get)
    }.getOrNull()

    private fun decodeStrictUtf8(bytes: ByteArray): String? = runCatching {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
}
