// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import runtime.mobileagent.domain.SkillMemoryEntry
import runtime.mobileagent.domain.SkillMemorySpace
import runtime.mobileagent.domain.Utc

private fun defaultSkillMemoryStorageRoot(): Path = Path.of(
    System.getProperty("java.io.tmpdir"), "mobileAgentRuntime", "skill-memory",
)

/**
 * Skill memory metadata is durable in SQLite while the Markdown body remains in a private,
 * application-owned sidecar directory.  The database stores only a digest, byte length and
 * opaque relative storage reference; it never stores memory content.
 */
class SkillMemoryRepository(
    private val db: SqlConnection,
    private val storageRoot: Path = defaultSkillMemoryStorageRoot(),
    private val clock: () -> String = { Utc.nowIso() },
) {
    init {
        require(!Files.isSymbolicLink(storageRoot)) { "Skill memory storage root must not be a symbolic link" }
        Files.createDirectories(storageRoot)
        require(Files.isDirectory(storageRoot) && !Files.isSymbolicLink(storageRoot)) {
            "Skill memory storage root is unavailable"
        }
    }

    fun getSpace(spaceId: String): SkillMemorySpace? = db.query(
        "SELECT * FROM skill_memory_spaces WHERE space_id = ?", listOf(spaceId),
    ).singleOrNull()?.toSkillMemorySpace()

    fun space(spaceId: String): SkillMemorySpace? = getSpace(spaceId)

    fun forSkill(installId: String, packageHash: String): SkillMemorySpace? = db.query(
        "SELECT * FROM skill_memory_spaces WHERE install_id = ? AND package_hash = ?",
        listOf(installId, packageHash),
    ).singleOrNull()?.toSkillMemorySpace()

    fun listSpaces(): List<SkillMemorySpace> = db.query(
        "SELECT * FROM skill_memory_spaces ORDER BY install_id, package_hash",
    ).map { it.toSkillMemorySpace() }

    /** Install identity and package hash form an isolation boundary; upgrades get a new space. */
    fun ensureSpace(
        installId: String,
        packageHash: String,
        quotaBytes: Long = SkillMemorySpace.DEFAULT_QUOTA_BYTES,
        maxEntries: Int = SkillMemorySpace.DEFAULT_MAX_ENTRIES,
    ): SkillMemorySpace {
        val existing = forSkill(installId, packageHash)
        if (existing != null) return existing
        val now = clock()
        val space = SkillMemorySpace(
            spaceId = spaceIdFor(installId, packageHash),
            installId = installId,
            packageHash = packageHash,
            quotaBytes = quotaBytes,
            maxEntries = maxEntries,
            createdAt = now,
            updatedAt = now,
        )
        db.transaction {
            db.execute(
                "INSERT INTO skill_memory_spaces(space_id,install_id,package_hash,quota_bytes,max_entries,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(install_id,package_hash) DO NOTHING",
                listOf(space.spaceId, space.installId, space.packageHash, space.quotaBytes, space.maxEntries, space.version, space.createdAt, space.updatedAt),
            )
        }
        return forSkill(installId, packageHash) ?: error("Skill memory space creation failed")
    }

    fun createSpace(
        installId: String,
        packageHash: String,
        quotaBytes: Long = SkillMemorySpace.DEFAULT_QUOTA_BYTES,
        maxEntries: Int = SkillMemorySpace.DEFAULT_MAX_ENTRIES,
    ): SkillMemorySpace = ensureSpace(installId, packageHash, quotaBytes, maxEntries)

    fun getEntry(entryId: String): SkillMemoryEntry? = db.query(
        "SELECT e.*, s.install_id, s.package_hash FROM skill_memory_entries e JOIN skill_memory_spaces s ON s.space_id = e.space_id WHERE e.entry_id = ?",
        listOf(entryId),
    ).singleOrNull()?.let(::readEntryRow)

    fun readEntry(entryId: String): SkillMemoryEntry? = getEntry(entryId)

    fun get(spaceId: String, path: String): SkillMemoryEntry? = db.query(
        "SELECT e.*, s.install_id, s.package_hash FROM skill_memory_entries e JOIN skill_memory_spaces s ON s.space_id = e.space_id WHERE e.space_id = ? AND e.path = ?",
        listOf(spaceId, validateMemoryPath(path)),
    ).singleOrNull()?.let(::readEntryRow)

    fun read(spaceId: String, path: String): SkillMemoryEntry? = get(spaceId, path)

    fun read(installId: String, packageHash: String, path: String): SkillMemoryEntry? =
        forSkill(installId, packageHash)?.let { get(it.spaceId, path) }

    fun listEntries(spaceId: String): List<SkillMemoryEntry> = db.query(
        "SELECT e.*, s.install_id, s.package_hash FROM skill_memory_entries e JOIN skill_memory_spaces s ON s.space_id = e.space_id WHERE e.space_id = ? ORDER BY e.path",
        listOf(spaceId),
    ).map(::readEntryRow)

    fun search(spaceId: String, query: String, limit: Int = 20): List<SkillMemoryEntry> {
        require(query.isNotBlank()) { "Memory search query must not be blank" }
        require(limit in 1..100) { "Memory search limit is outside the allowed range" }
        return listEntries(spaceId).filter { entry ->
            // Search is a bounded literal query.  Do not normalize case or treat the query as
            // SQL/regex; the app-facing adapter is responsible for clipping snippets/results.
            entry.path.contains(query) || entry.content.contains(query)
        }.take(limit)
    }

    fun search(installId: String, packageHash: String, query: String, limit: Int = 20): List<SkillMemoryEntry> =
        forSkill(installId, packageHash)?.let { search(it.spaceId, query, limit) }.orEmpty()

    /** Append to one of the two allowed files, with an optional optimistic version check. */
    fun append(
        installId: String,
        packageHash: String,
        path: String,
        content: String,
        expectedVersion: Long? = null,
    ): SkillMemoryEntry = write(installId, packageHash, path, content, expectedVersion, append = true)

    fun replace(
        installId: String,
        packageHash: String,
        path: String,
        content: String,
        expectedVersion: Long? = null,
    ): SkillMemoryEntry = write(installId, packageHash, path, content, expectedVersion, append = false)

    fun put(
        installId: String,
        packageHash: String,
        path: String,
        content: String,
        expectedVersion: Long? = null,
    ): SkillMemoryEntry = replace(installId, packageHash, path, content, expectedVersion)

    fun write(
        installId: String,
        packageHash: String,
        path: String,
        content: String,
        expectedVersion: Long? = null,
        append: Boolean = false,
    ): SkillMemoryEntry {
        val space = forSkill(installId, packageHash) ?: ensureSpace(installId, packageHash)
        val validPath = validateMemoryPath(path)
        val existing = get(space.spaceId, validPath)
        if (expectedVersion != null) {
            val actual = existing?.version ?: 0L
            if (actual != expectedVersion) throw AuthorityPolicyConflictException("Skill memory version changed")
        }
        val nextContent = if (append && existing != null) existing.content + content else content
        val bytes = nextContent.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_ENTRY_BYTES) { "Skill memory entry exceeds the file limit" }
        val currentBytes = db.query(
            "SELECT COALESCE(SUM(byte_length), 0) AS total FROM skill_memory_entries WHERE space_id = ?",
            listOf(space.spaceId),
        ).singleOrNull()?.long("total") ?: 0L
        val newTotal = currentBytes - (existing?.byteLength ?: 0L) + bytes.size
        require(newTotal <= space.quotaBytes) { "Skill memory quota exceeded" }
        if (existing == null) {
            val count = db.query(
                "SELECT COUNT(*) AS count FROM skill_memory_entries WHERE space_id = ?", listOf(space.spaceId),
            ).singleOrNull()?.long("count") ?: 0L
            require(count < space.maxEntries) { "Skill memory entry quota exceeded" }
        }

        val entryId = existing?.entryId ?: UUID.randomUUID().toString()
        val nextVersion = (existing?.version ?: 0L) + 1L
        val storageRef = "${space.spaceId}/${entryId}-v$nextVersion.memory"
        val now = clock()
        writeSidecar(storageRef, bytes)
        try {
            db.transaction {
                if (existing == null) {
                    db.execute(
                        "INSERT INTO skill_memory_entries(entry_id,space_id,path,content_hash,storage_ref,byte_length,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        listOf(entryId, space.spaceId, validPath, sha256Hex(bytes), storageRef, bytes.size, nextVersion, now, now),
                    )
                } else {
                    db.execute(
                        "UPDATE skill_memory_entries SET content_hash = ?, storage_ref = ?, byte_length = ?, version = ?, updated_at = ? WHERE entry_id = ? AND version = ?",
                        listOf(sha256Hex(bytes), storageRef, bytes.size, nextVersion, now, entryId, existing.version),
                    )
                    val updated = db.query("SELECT version FROM skill_memory_entries WHERE entry_id = ?", listOf(entryId))
                        .singleOrNull()?.long("version")
                    if (updated != nextVersion) throw AuthorityPolicyConflictException("Skill memory version changed")
                }
                db.execute(
                    "UPDATE skill_memory_spaces SET version = version + 1, updated_at = ? WHERE space_id = ?",
                    listOf(now, space.spaceId),
                )
            }
        } catch (failure: Throwable) {
            // The sidecar is content-addressed by entry/version and is safe to remove when the
            // metadata transaction did not commit.  Older versions remain untouched on success.
            runCatching { Files.deleteIfExists(resolveStorageRef(storageRef)) }
            throw failure
        }
        return get(space.spaceId, validPath) ?: error("Skill memory write failed")
    }

    fun delete(spaceId: String, path: String, expectedVersion: Long? = null): Boolean {
        val validPath = validateMemoryPath(path)
        // Keep the opaque sidecar reference at the persistence seam.  It is metadata, not part of
        // the domain entry exposed to callers, and must be captured before the row is deleted.
        val row = db.query(
            "SELECT e.*, s.install_id, s.package_hash FROM skill_memory_entries e JOIN skill_memory_spaces s ON s.space_id = e.space_id WHERE e.space_id = ? AND e.path = ?",
            listOf(spaceId, validPath),
        ).singleOrNull() ?: return false
        val storageRef = row.string("storage_ref")
        val existing = readEntryRow(row)
        if (expectedVersion != null && expectedVersion != existing.version) {
            throw AuthorityPolicyConflictException("Skill memory version changed")
        }
        db.transaction {
            db.execute(
                "DELETE FROM skill_memory_entries WHERE entry_id = ? AND version = ?",
                listOf(existing.entryId, existing.version),
            )
            db.execute(
                "UPDATE skill_memory_spaces SET version = version + 1, updated_at = ? WHERE space_id = ?",
                listOf(clock(), spaceId),
            )
        }
        runCatching { Files.deleteIfExists(resolveStorageRef(storageRef)) }
        return true
    }

    private fun readEntryRow(row: SqlRow): SkillMemoryEntry {
        val storageRef = row.string("storage_ref")
        val target = resolveStorageRef(storageRef)
        require(!Files.isSymbolicLink(target) && Files.isRegularFile(target)) {
            "Skill memory sidecar is not a regular file"
        }
        val expectedLength = row.long("byte_length")
        require(expectedLength in 0..MAX_ENTRY_BYTES) { "Skill memory entry metadata exceeds the file limit" }
        require(Files.size(target) == expectedLength) { "Skill memory sidecar length does not match metadata" }
        val bytes = Files.readAllBytes(target)
        val hash = sha256Hex(bytes)
        require(hash == row.string("content_hash")) {
            "Skill memory sidecar integrity check failed"
        }
        val content = decodeUtf8(bytes)
        return SkillMemoryEntry(
            entryId = row.string("entry_id"),
            spaceId = row.string("space_id"),
            installId = row.string("install_id"),
            packageHash = row.string("package_hash"),
            path = row.string("path"),
            content = content,
            version = row.long("version"),
            byteLength = row.long("byte_length"),
            createdAt = row.string("created_at"),
            updatedAt = row.string("updated_at"),
        )
    }

    private fun writeSidecar(storageRef: String, bytes: ByteArray) {
        val target = resolveStorageRef(storageRef)
        Files.createDirectories(target.parent)
        require(!Files.isSymbolicLink(target.parent)) { "Skill memory sidecar parent is a symbolic link" }
        val temp = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun resolveStorageRef(storageRef: String): Path {
        require(storageRef.length <= 512 && storageRef == storageRef.trim()) { "Memory storage reference is invalid" }
        val resolved = storageRoot.resolve(storageRef).normalize()
        require(resolved.startsWith(storageRoot.normalize())) { "Memory storage reference escapes private storage" }
        var current = storageRoot.normalize()
        require(!Files.isSymbolicLink(current)) { "Memory storage path contains a symbolic link" }
        storageRoot.normalize().relativize(resolved).forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Memory storage path contains a symbolic link" }
        }
        return resolved
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Skill memory content is not valid UTF-8")
    }

    private fun validateMemoryPath(path: String): String {
        if (path == "MEMORY.md") return path
        require(path.matches(Regex("journal/\\d{4}-\\d{2}-\\d{2}\\.md"))) {
            "Skill memory path must be MEMORY.md or journal/YYYY-MM-DD.md"
        }
        runCatching { LocalDate.parse(path.removePrefix("journal/").removeSuffix(".md")) }
            .getOrElse { throw IllegalArgumentException("Skill memory date is invalid", it) }
        return path
    }

    private fun spaceIdFor(installId: String, packageHash: String): String =
        "memory-" + sha256Hex("$installId\u0000$packageHash")

    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val MAX_ENTRY_BYTES = 1L * 1024 * 1024
    }
}

private fun SqlRow.toSkillMemorySpace() = SkillMemorySpace(
    spaceId = string("space_id"),
    installId = string("install_id"),
    packageHash = string("package_hash"),
    quotaBytes = long("quota_bytes"),
    maxEntries = long("max_entries").toInt(),
    version = long("version"),
    createdAt = string("created_at"),
    updatedAt = string("updated_at"),
)
