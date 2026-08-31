// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.security.MessageDigest
import java.util.Arrays
import java.util.UUID
import runtime.mobileagent.bridge.BridgeEncoding
import runtime.mobileagent.bridge.BridgeIdentity
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.bridge.SecretBytes

class DesktopTrustUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A DPAPI-protected App-level trust record; it is not the ADB RSA key. */
class DesktopTrustRecord(
    val identity: BridgeIdentity,
    canonicalAdbPath: String,
    adbSha256: ByteArray,
    transcriptHash: ByteArray,
    val persistentTrust: SecretBytes,
) : AutoCloseable {
    val canonicalAdbPath: String = canonicalAdbPath
    val adbSha256: ByteArray = adbSha256.copyOf()
    val transcriptHash: ByteArray = transcriptHash.copyOf()
    private var closed = false

    init {
        require(canonicalAdbPath.isNotBlank() && Path.of(canonicalAdbPath).isAbsolute())
        require(this.adbSha256.size == 32)
        require(this.transcriptHash.size == 32)
        require(persistentTrust.size == 32)
    }

    @Synchronized
    fun copyTrust(): SecretBytes {
        check(!closed) { "desktop trust record is closed" }
        val bytes = persistentTrust.copyBytes()
        return try {
            SecretBytes.from(bytes)
        } finally {
            Arrays.fill(bytes, 0)
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            persistentTrust.close()
            Arrays.fill(adbSha256, 0)
            Arrays.fill(transcriptHash, 0)
        }
    }
}

interface DesktopTrustStore {
    fun load(identity: BridgeIdentity): DesktopTrustRecord?
    fun save(record: DesktopTrustRecord)
    fun forget(identity: BridgeIdentity)
}

/** Stable desktop identity used by first-pair challenges and persisted trust keys. */
interface DesktopIdentityStore {
    fun loadOrCreate(): String
}

/** Deterministic test/local identity store; production uses DPAPI below. */
class InMemoryDesktopIdentityStore(initialDesktopId: String? = null) : DesktopIdentityStore {
    private var desktopId: String? = initialDesktopId?.also(::validateDesktopId)

    @Synchronized
    override fun loadOrCreate(): String {
        return desktopId ?: "desktop-${UUID.randomUUID()}".also {
            validateDesktopId(it)
            desktopId = it
        }
    }
}

/**
 * Windows CurrentUser DPAPI store for the stable desktop identity. The id is
 * never accepted from a pairing request and is not regenerated when trust
 * records are absent.
 */
class DpapiDesktopIdentityStore(
    private val directory: Path,
) : DesktopIdentityStore {
    private val file: Path = directory.resolve("desktop-id.bin")

    init {
        if (!Platform.isWindows()) {
            throw DesktopTrustUnavailableException("Windows DPAPI is required for desktop identity")
        }
        require(directory.isAbsolute) { "identity directory must be absolute" }
        WindowsTrustPathSecurity.checkDirectory(directory, createIfMissing = true)
    }

    @Synchronized
    override fun loadOrCreate(): String {
        WindowsTrustPathSecurity.checkDirectory(directory, createIfMissing = true)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return readExisting()
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw DesktopTrustUnavailableException("desktop identity record is inaccessible")
        }
        val generated = "desktop-${UUID.randomUUID()}"
        validateDesktopId(generated)
        val plain = generated.toByteArray(StandardCharsets.UTF_8)
        try {
            val protected = try {
                Crypt32Util.cryptProtectData(plain, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
            } catch (error: Exception) {
                throw DesktopTrustUnavailableException("DPAPI desktop identity encrypt failed", error)
            }
            try {
                val tmp = file.resolveSibling(".${file.fileName}.tmp-${ProcessHandle.current().pid()}-${System.nanoTime()}")
                try {
                    require(protected.size <= MAX_PROTECTED_ID_BYTES) { "desktop identity record is too large" }
                    Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        output.write(protected)
                        output.flush()
                    }
                    FileChannel.open(tmp, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
                    WindowsTrustPathSecurity.checkFile(tmp)
                    try {
                        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp, file)
                    }
                    WindowsTrustPathSecurity.checkFile(file)
                    return generated
                } catch (_: FileAlreadyExistsException) {
                    // Another process won the first-create race; use its
                    // identity instead of replacing it.
                    runCatching { Files.deleteIfExists(tmp) }
                    return readExisting()
                } catch (error: Exception) {
                    runCatching { Files.deleteIfExists(tmp) }
                    throw DesktopTrustUnavailableException("cannot persist desktop identity", error)
                }
            } finally {
                Arrays.fill(protected, 0)
            }
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    private fun readExisting(): String {
        WindowsTrustPathSecurity.checkFile(file)
        require(Files.size(file) <= MAX_PROTECTED_ID_BYTES) { "desktop identity record is too large" }
        val protected = try {
            Files.readAllBytes(file)
        } catch (error: Exception) {
            throw DesktopTrustUnavailableException("cannot read desktop identity", error)
        }
        return try {
            val plain = try {
                Crypt32Util.cryptUnprotectData(protected, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
            } catch (error: Exception) {
                throw DesktopTrustUnavailableException("DPAPI desktop identity decrypt failed", error)
            }
            try {
                val value = decodeTrustUtf8(plain)
                validateDesktopId(value)
                value
            } finally {
                Arrays.fill(plain, 0)
            }
        } finally {
            Arrays.fill(protected, 0)
        }
    }
}

/**
 * Windows CurrentUser DPAPI store.  The non-Windows path fails closed by
 * design; it never substitutes a plaintext or machine-wide key store.
 */
class DpapiDesktopTrustStore(
    private val directory: Path,
) : DesktopTrustStore {
    init {
        if (!Platform.isWindows()) {
            throw DesktopTrustUnavailableException("Windows DPAPI is required for desktop trust")
        }
        require(directory.isAbsolute) { "trust directory must be absolute" }
        WindowsTrustPathSecurity.checkDirectory(directory, createIfMissing = true)
    }

    override fun load(identity: BridgeIdentity): DesktopTrustRecord? {
        val file = recordPath(identity)
        WindowsTrustPathSecurity.checkDirectory(directory, createIfMissing = false)
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { "desktop trust record is inaccessible" }
        WindowsTrustPathSecurity.checkFile(file)
        require(Files.size(file) <= MAX_PROTECTED_RECORD_BYTES) { "desktop trust record is too large" }
        val protected = try {
            Files.readAllBytes(file)
        } catch (error: Exception) {
            throw DesktopTrustUnavailableException("cannot read desktop trust", error)
        }
        return try {
            require(protected.size <= MAX_PROTECTED_RECORD_BYTES) { "desktop trust record is too large" }
            val plain = try {
                Crypt32Util.cryptUnprotectData(protected, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
            } catch (error: Exception) {
                throw DesktopTrustUnavailableException("DPAPI trust decrypt failed", error)
            }
            try {
                decodeRecord(plain, identity)
            } finally {
                Arrays.fill(plain, 0)
            }
        } finally {
            Arrays.fill(protected, 0)
        }
    }

    override fun save(record: DesktopTrustRecord) {
        val plain = encodeRecord(record)
        try {
            val protected = try {
                Crypt32Util.cryptProtectData(plain, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
            } catch (error: Exception) {
                throw DesktopTrustUnavailableException("DPAPI trust encrypt failed", error)
            }
            try {
                val file = recordPath(record.identity)
                val tmp = file.resolveSibling(".${file.fileName}.tmp-${ProcessHandle.current().pid()}-${System.nanoTime()}")
                try {
                    WindowsTrustPathSecurity.checkDirectory(directory, createIfMissing = false)
                    Files.createDirectories(directory)
                    WindowsTrustPathSecurity.checkDirectory(directory, createIfMissing = false)
                    require(protected.size <= MAX_PROTECTED_RECORD_BYTES) { "desktop trust record is too large" }
                    // CREATE_NEW prevents a pre-created symlink/reparse point from
                    // redirecting the protected bytes before the post-write checks.
                    Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        output.write(protected)
                        output.flush()
                    }
                    FileChannel.open(tmp, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
                    WindowsTrustPathSecurity.checkFile(tmp)
                    WindowsTrustPathSecurity.checkFileIfPresent(file)
                    try {
                        Files.move(
                            tmp,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                    }
                    WindowsTrustPathSecurity.checkFile(file)
                } catch (error: Exception) {
                    runCatching { Files.deleteIfExists(tmp) }
                    throw DesktopTrustUnavailableException("cannot persist desktop trust", error)
                }
            } finally {
                Arrays.fill(protected, 0)
            }
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    override fun forget(identity: BridgeIdentity) {
        try {
            WindowsTrustPathSecurity.checkDirectory(directory, createIfMissing = false)
            val file = recordPath(identity)
            WindowsTrustPathSecurity.checkFileIfPresent(file)
            Files.deleteIfExists(file)
        } catch (error: Exception) {
            throw DesktopTrustUnavailableException("cannot forget desktop trust", error)
        }
    }

    private fun recordPath(identity: BridgeIdentity): Path {
        val key = identity.stableKey().toByteArray(StandardCharsets.UTF_8)
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(key)
        } finally {
            Arrays.fill(key, 0)
        }
        return try {
            directory.resolve("trust-${BridgeEncoding.hex(digest)}.bin")
        } finally {
            Arrays.fill(digest, 0)
        }
    }
}

/** JVM tests can exercise orchestration without depending on Windows DPAPI. */
class InMemoryDesktopTrustStore : DesktopTrustStore {
    private val records = LinkedHashMap<BridgeIdentity, DesktopTrustRecord>()

    @Synchronized
    override fun load(identity: BridgeIdentity): DesktopTrustRecord? {
        val current = records[identity] ?: return null
        return DesktopTrustRecord(
            current.identity,
            current.canonicalAdbPath,
            current.adbSha256,
            current.transcriptHash,
            current.copyTrust(),
        )
    }

    @Synchronized
    override fun save(record: DesktopTrustRecord) {
        val previous = records.put(
            record.identity,
            DesktopTrustRecord(
                record.identity,
                record.canonicalAdbPath,
                record.adbSha256,
                record.transcriptHash,
                record.copyTrust(),
            ),
        )
        previous?.close()
    }

    @Synchronized
    override fun forget(identity: BridgeIdentity) {
        records.remove(identity)?.close()
    }

    @Synchronized
    fun clear() {
        records.values.forEach(DesktopTrustRecord::close)
        records.clear()
    }
}

private const val TRUST_MAGIC = 0x4D415254 // MART
private const val TRUST_FORMAT_VERSION = 1
private const val MAX_PROTECTED_RECORD_BYTES = 128 * 1024
private const val MAX_PROTECTED_ID_BYTES = 4 * 1024

private fun validateDesktopId(value: String) {
    require(value.isNotBlank() && value.length <= 256) { "desktopId is invalid" }
    require(value.none { it == '\u0000' || it.code < 0x20 || it == '\u007f' || it.isWhitespace() }) {
        "desktopId contains whitespace/control characters"
    }
    strictTrustUtf8(value)
}

private fun encodeRecord(record: DesktopTrustRecord): ByteArray {
    val output = ByteArrayOutputStream()
    val data = DataOutputStream(output)
    data.writeInt(TRUST_MAGIC)
    data.writeShort(TRUST_FORMAT_VERSION)
    writeString(data, record.identity.desktopId)
    writeString(data, record.identity.appInstanceId)
    data.write(record.identity.serialFingerprint)
    writeString(data, record.canonicalAdbPath)
    data.write(record.adbSha256)
    data.write(record.transcriptHash)
    record.persistentTrust.use(data::write)
    data.flush()
    return output.toByteArray()
}

private fun decodeRecord(bytes: ByteArray, expectedIdentity: BridgeIdentity): DesktopTrustRecord {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    try {
        require(input.readInt() == TRUST_MAGIC) { "desktop trust magic is invalid" }
        require(input.readUnsignedShort() == TRUST_FORMAT_VERSION) { "desktop trust version is invalid" }
        val desktopId = readString(input)
        val appInstanceId = readString(input)
        val fingerprint = readFixed(input, 32)
        val path = readString(input)
        val adbHash = readFixed(input, 32)
        val transcriptHash = readFixed(input, 32)
        val secret = SecretBytes.from(readFixed(input, 32))
        require(input.available() == 0) { "desktop trust has trailing bytes" }
        val identity = BridgeIdentity(desktopId, appInstanceId, fingerprint)
        require(identity == expectedIdentity) { "desktop trust identity mismatch" }
        return try {
            DesktopTrustRecord(identity, path, adbHash, transcriptHash, secret)
        } catch (error: Exception) {
            secret.close()
            throw error
        } finally {
            Arrays.fill(fingerprint, 0)
            Arrays.fill(adbHash, 0)
            Arrays.fill(transcriptHash, 0)
        }
    } catch (error: Exception) {
        if (error is DesktopTrustUnavailableException) throw error
        throw DesktopTrustUnavailableException("desktop trust record is invalid", error)
    }
}

private fun writeString(data: DataOutputStream, value: String) {
    val bytes = strictTrustUtf8(value)
    require(bytes.isNotEmpty() && bytes.size <= 16 * 1024)
    data.writeInt(bytes.size)
    data.write(bytes)
    Arrays.fill(bytes, 0)
}

private fun readString(input: DataInputStream): String {
    val size = input.readInt()
    require(size in 1..16 * 1024)
    val bytes = readFixed(input, size)
    return try {
        decodeTrustUtf8(bytes)
    } finally {
        Arrays.fill(bytes, 0)
    }
}

private fun strictTrustUtf8(value: String): ByteArray {
    require(!value.contains('\u0000')) { "trust text contains NUL" }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(decodeTrustUtf8(bytes) == value) { "trust text is not valid UTF-8" }
    return bytes
}

private fun decodeTrustUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()
} catch (error: java.nio.charset.CharacterCodingException) {
    throw DesktopTrustUnavailableException("desktop trust text is not valid UTF-8", error)
}

private fun readFixed(input: DataInputStream, size: Int): ByteArray {
    val bytes = ByteArray(size)
    input.readFully(bytes)
    return bytes
}

/** Windows-only path hardening for DPAPI records. */
private object WindowsTrustPathSecurity {
    private const val FILE_ATTRIBUTE_REPARSE_POINT = WinNT.FILE_ATTRIBUTE_REPARSE_POINT
    private val broadPrincipals = setOf(
        "everyone",
        "users",
        "authenticated users",
        "builtin\\users",
        "nt authority\\authenticated users",
    )
    private val writePermissions = setOf(
        AclEntryPermission.WRITE_DATA,
        AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.WRITE_NAMED_ATTRS,
        AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER,
    )

    fun checkDirectory(directory: Path, createIfMissing: Boolean) {
        if (!Platform.isWindows()) return
        require(directory.isAbsolute) { "trust directory must be absolute" }
        // Validate existing ancestors before createDirectories traverses them.
        checkAncestorReparsePoints(directory)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (!createIfMissing) return
            } else {
                throw DesktopTrustUnavailableException("trust directory is inaccessible")
            }
            Files.createDirectories(directory)
        }
        require(!Files.isSymbolicLink(directory)) { "trust directory may not be a symlink" }
        require(!isReparsePoint(directory)) { "trust directory may not be a reparse point" }
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "trust path is not a directory" }
        checkAcl(directory)
    }

    fun checkFile(file: Path) {
        if (!Platform.isWindows()) return
        require(!Files.isSymbolicLink(file)) { "trust record may not be a symlink" }
        require(!isReparsePoint(file)) { "trust record may not be a reparse point" }
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "trust record is not a regular file" }
        checkAcl(file)
    }

    fun checkFileIfPresent(file: Path) {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) checkFile(file)
        else if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw DesktopTrustUnavailableException("trust record is inaccessible")
        }
    }

    private fun isReparsePoint(path: Path): Boolean = try {
        val attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString())
        require(attributes != -1) { "cannot inspect Windows file attributes" }
        attributes and FILE_ATTRIBUTE_REPARSE_POINT != 0
    } catch (error: UnsatisfiedLinkError) {
        throw DesktopTrustUnavailableException("Windows file identity inspection is unavailable", error)
    }

    private fun checkAcl(path: Path) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: throw DesktopTrustUnavailableException("Windows ACL inspection is unavailable")
        val owner = view.owner.name.lowercase()
        require(owner !in broadPrincipals) { "trust path owner is not a user" }
        view.acl.forEach { entry ->
            val principal = entry.principal().name.lowercase()
            if (entry.type() == AclEntryType.ALLOW && principal in broadPrincipals &&
                entry.permissions().any { it in writePermissions }
            ) {
                throw DesktopTrustUnavailableException("trust path ACL permits broad writes")
            }
        }
    }

    /** A non-reparse leaf is insufficient if an ancestor redirects the path. */
    private fun checkAncestorReparsePoints(path: Path) {
        var current: Path? = path.toAbsolutePath().normalize().parent
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "trust path ancestor may not be a symlink" }
                require(!isReparsePoint(current)) { "trust path ancestor may not be a reparse point" }
            } else if (!Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw DesktopTrustUnavailableException("trust path ancestor is inaccessible")
            }
            current = current.parent
        }
    }
}
