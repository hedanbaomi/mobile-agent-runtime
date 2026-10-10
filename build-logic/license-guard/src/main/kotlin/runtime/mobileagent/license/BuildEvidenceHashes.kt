// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.license

import java.io.File
import java.security.MessageDigest

/** Byte hashing shared by APK/SBOM generation and the independent root verifier. */
object BuildEvidenceHashes {
    fun sha256(file: File): String {
        require(file.isFile) { "Cannot hash a missing file: ${file.absolutePath}" }
        return file.inputStream().use(::hashStream)
    }

    fun sourceArchiveSha256(repository: File): String {
        val process = ProcessBuilder("git", "archive", "--format=tar", "HEAD")
            .directory(repository).redirectErrorStream(false).start()
        val hash = process.inputStream.use(::hashStream)
        val errors = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        check(process.waitFor() == 0) { "git archive failed: $errors" }
        return hash
    }

    private fun hashStream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
