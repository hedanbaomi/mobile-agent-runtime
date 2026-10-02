// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.build

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** A bounded downloader for artifacts whose content is pinned by SHA-256. */
object PinnedArtifactDownload {
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_ATTEMPTS = 3
    private const val MAX_TIMEOUT_MILLIS = 300_000
    private const val MAX_INITIAL_BACKOFF_MILLIS = 5_000L
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    /**
     * Downloads [url] to [destination] only after verifying [expectedSha256].
     * A valid existing file is reused without changing it or accessing the network.
     */
    @JvmOverloads
    fun download(
        url: String,
        destination: File,
        expectedSha256: String,
        followRedirects: Boolean = true,
        connectTimeoutMillis: Int = 30_000,
        readTimeoutMillis: Int = 120_000,
        maxAttempts: Int = MAX_ATTEMPTS,
        initialBackoffMillis: Long = 1_000L,
    ): File {
        require(sha256Pattern.matches(expectedSha256)) {
            "expectedSha256 must be 64 lowercase hexadecimal characters"
        }
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "connectTimeoutMillis must be between 1 and $MAX_TIMEOUT_MILLIS"
        }
        require(readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "readTimeoutMillis must be between 1 and $MAX_TIMEOUT_MILLIS"
        }
        require(maxAttempts in 1..MAX_ATTEMPTS) { "maxAttempts must be between 1 and $MAX_ATTEMPTS" }
        require(initialBackoffMillis in 0..MAX_INITIAL_BACKOFF_MILLIS) {
            "initialBackoffMillis must be between 0 and $MAX_INITIAL_BACKOFF_MILLIS"
        }

        val artifactName = destination.name.ifBlank { "artifact" }
        val source = parseUrl(url)
        val target = destination.toPath().toAbsolutePath().normalize()
        val parent = target.parent ?: throw failure(artifactName, "local I/O failure")
        if (Thread.currentThread().isInterrupted) {
            throw failure(artifactName, "cancelled")
        }

        if (Files.exists(target)) {
            val cachedHash = try {
                hashFile(target)
            } catch (_: IOException) {
                throw failure(artifactName, "local I/O failure")
            }
            if (cachedHash == expectedSha256) return target.toFile()
        }

        var temporary: Path? = try {
            Files.createDirectories(parent)
            Files.createTempFile(parent, "pinned-artifact-${safePrefix(artifactName)}-", ".part")
        } catch (_: IOException) {
            throw failure(artifactName, "local I/O failure")
        }

        try {
            var backoffMillis = initialBackoffMillis
            for (attempt in 1..maxAttempts) {
                if (Thread.currentThread().isInterrupted) {
                    throw failure(artifactName, "cancelled")
                }

                try {
                    transfer(
                        source = source,
                        temporary = checkNotNull(temporary),
                        expectedSha256 = expectedSha256,
                        followRedirects = followRedirects,
                        connectTimeoutMillis = connectTimeoutMillis,
                        readTimeoutMillis = readTimeoutMillis,
                    )
                    if (Thread.currentThread().isInterrupted) {
                        throw failure(artifactName, "cancelled")
                    }
                    replaceAtomically(checkNotNull(temporary), target, artifactName)
                    temporary = null
                    return target.toFile()
                } catch (attemptFailure: AttemptFailure) {
                    if (!attemptFailure.retryable || attempt == maxAttempts) {
                        val exhausted = if (attemptFailure.retryable) " (retry limit reached)" else ""
                        throw failure(artifactName, attemptFailure.category + exhausted)
                    }
                    if (backoffMillis > 0) {
                        try {
                            Thread.sleep(backoffMillis)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw failure(artifactName, "cancelled")
                        }
                    }
                    backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_INITIAL_BACKOFF_MILLIS)
                }
            }
            throw failure(artifactName, "network failure")
        } finally {
            temporary?.let { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: IOException) {
                    throw failure(artifactName, "local I/O failure")
                }
            }
        }
    }

    private fun transfer(
        source: URI,
        temporary: Path,
        expectedSha256: String,
        followRedirects: Boolean,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ) {
        val connection = try {
            source.toURL().openConnection() as? HttpURLConnection
                ?: throw AttemptFailure("protocol failure", retryable = false)
        } catch (failure: AttemptFailure) {
            throw failure
        } catch (exception: IOException) {
            throw classifyNetworkFailure(exception)
        }

        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = followRedirects

            val status = try {
                connection.responseCode
            } catch (exception: IOException) {
                throw classifyNetworkFailure(exception)
            }
            if (status !in 200..299) {
                throw AttemptFailure("HTTP $status", retryable = status == 408 || status == 429 || status in 500..599)
            }

            val declaredLength = connection.contentLengthLong
            val input = try {
                connection.inputStream
            } catch (exception: IOException) {
                throw classifyNetworkFailure(exception)
            }
            val output = try {
                Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            } catch (_: IOException) {
                closeQuietly(input)
                throw AttemptFailure("local I/O failure", retryable = false)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var byteCount = 0L
            var copyFailure: AttemptFailure? = null
            try {
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw AttemptFailure("cancelled", retryable = false)
                    }
                    val count = try {
                        input.read(buffer)
                    } catch (exception: IOException) {
                        throw classifyNetworkFailure(exception)
                    }
                    if (Thread.currentThread().isInterrupted) {
                        throw AttemptFailure("cancelled", retryable = false)
                    }
                    if (count < 0) break
                    if (count == 0) continue
                    try {
                        output.write(buffer, 0, count)
                    } catch (_: IOException) {
                        throw AttemptFailure("local I/O failure", retryable = false)
                    }
                    digest.update(buffer, 0, count)
                    byteCount += count
                }
            } catch (failure: AttemptFailure) {
                copyFailure = failure
            } finally {
                try {
                    output.close()
                } catch (_: IOException) {
                    if (copyFailure == null) {
                        copyFailure = AttemptFailure("local I/O failure", retryable = false)
                    }
                }
                try {
                    input.close()
                } catch (exception: IOException) {
                    if (copyFailure == null) {
                        copyFailure = classifyNetworkFailure(exception)
                    }
                }
            }
            copyFailure?.let { throw it }

            if (declaredLength >= 0 && byteCount != declaredLength) {
                val category = if (byteCount < declaredLength) "response truncated" else "protocol failure"
                throw AttemptFailure(category, retryable = byteCount < declaredLength)
            }
            if (hexDigest(digest.digest()) != expectedSha256) {
                throw AttemptFailure("SHA-256 mismatch", retryable = false)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun replaceAtomically(temporary: Path, target: Path, artifactName: String) {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                throw failure(artifactName, "local I/O failure")
            }
        } catch (_: IOException) {
            throw failure(artifactName, "local I/O failure")
        }
    }

    private fun parseUrl(value: String): URI {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("url must be a valid HTTP(S) URL")
        }
        val scheme = uri.scheme
        require((scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) {
            "url must be an HTTP(S) URL without user-info or a fragment"
        }
        return uri
    }

    private fun hashFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return hexDigest(digest.digest())
    }

    private fun classifyNetworkFailure(exception: IOException): AttemptFailure {
        if (Thread.currentThread().isInterrupted) {
            return AttemptFailure("cancelled", retryable = false)
        }
        val chain = generateSequence<Throwable>(exception) { it.cause }.take(12).toList()
        if (chain.any { it is SSLException }) {
            return AttemptFailure("TLS failure", retryable = false)
        }
        if (chain.any { it is ProtocolException }) {
            return AttemptFailure("protocol failure", retryable = false)
        }
        if (chain.any { it is CancellationException }) {
            return AttemptFailure("cancelled", retryable = false)
        }
        if (chain.any { it is SocketTimeoutException || it is UnknownHostException ||
                it is ConnectException || it is NoRouteToHostException ||
                it.javaClass == SocketException::class.java || it is java.io.EOFException }) {
            return AttemptFailure("network failure", retryable = true)
        }
        return AttemptFailure("network/protocol failure", retryable = false)
    }

    private fun closeQuietly(input: InputStream) {
        try {
            input.close()
        } catch (_: IOException) {
            // Preserve the local output failure that caused this cleanup.
        }
    }

    private fun safePrefix(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

    private fun hexDigest(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }

    private fun failure(artifactName: String, category: String): PinnedArtifactDownloadException =
        PinnedArtifactDownloadException("$artifactName: $category")

    private class AttemptFailure(val category: String, val retryable: Boolean) : Exception()
}

class PinnedArtifactDownloadException internal constructor(message: String) : IOException(message)
