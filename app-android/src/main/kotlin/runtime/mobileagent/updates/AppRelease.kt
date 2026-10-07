// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.updates

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

internal const val RELEASE_REPOSITORY = "https://github.com/hedanbaomi/mobile-agent-runtime"
internal const val LATEST_RELEASE_API = "https://api.github.com/repos/hedanbaomi/mobile-agent-runtime/releases/latest"
internal const val MAX_APK_BYTES = 256L * 1024 * 1024

/** Stable tags are compared as integer triples, never lexicographically or against announcements. */
internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    companion object {
        fun parse(value: String): ReleaseVersion {
            require(Regex("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})").matches(value))
            val parts = value.split('.').map(String::toInt)
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}

/** A local preview can upgrade to its same-patch stable release; release feeds remain stable-only. */
internal fun isNewerThanInstalled(candidate: String, installed: String): Boolean {
    val release = ReleaseVersion.parse(candidate)
    val preview = installed.endsWith("preview")
    val baseline = if (preview) {
        val number = "(?:0|[1-9][0-9]{0,8})"
        require(Regex("$number\\.$number\\.$number(?:\\.$number)?preview").matches(installed))
        ReleaseVersion.parse(installed.removeSuffix("preview").split('.').take(3).joinToString("."))
    } else ReleaseVersion.parse(installed)
    return release > baseline || (preview && release == baseline)
}

@Serializable
internal data class AppRelease(
    val version: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    val notes: String = "",
) {
    val pageUrl: String get() = "$RELEASE_REPOSITORY/releases/tag/v$version"
    fun validate(): AppRelease = apply {
        ReleaseVersion.parse(version)
        require(size in 1..MAX_APK_BYTES)
        require(Regex("[0-9a-f]{64}").matches(sha256))
        require(apkUrl == "$RELEASE_REPOSITORY/releases/download/v$version/mobileAgentRuntime-v$version-arm64-v8a.apk")
        require(notes.length <= 4_000)
    }
}

internal fun parseLatestRelease(body: String): AppRelease {
    require(body.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
    val root = Json.parseToJsonElement(body).jsonObject
    require(!root.getValue("draft").jsonPrimitive.boolean && !root.getValue("prerelease").jsonPrimitive.boolean)
    val tag = root.getValue("tag_name").jsonPrimitive.content
    require(tag.startsWith('v'))
    val version = tag.drop(1)
    ReleaseVersion.parse(version)
    require(root.getValue("html_url").jsonPrimitive.content == "$RELEASE_REPOSITORY/releases/tag/$tag")
    val name = "mobileAgentRuntime-$tag-arm64-v8a.apk"
    val asset = root.getValue("assets").jsonArray.map { it.jsonObject }
        .single { it["name"]?.jsonPrimitive?.content == name }
    require(asset.getValue("state").jsonPrimitive.content == "uploaded")
    val digest = asset.getValue("digest").jsonPrimitive.content
    require(digest.startsWith("sha256:"))
    return AppRelease(
        version, asset.getValue("browser_download_url").jsonPrimitive.content,
        asset.getValue("size").jsonPrimitive.long, digest.removePrefix("sha256:"),
        root["body"]?.jsonPrimitive?.content.orEmpty().take(4_000),
    ).validate()
}

/** Only the repository and GitHub's release storage may receive anonymous download requests. */
internal fun validateDownloadHop(url: String) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null)
    require(uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
    if (uri.host == "github.com") {
        require(uri.rawPath.startsWith("/hedanbaomi/mobile-agent-runtime/releases/download/"))
        require(uri.rawQuery == null && !uri.rawPath.contains('%') && !uri.rawPath.contains(".."))
    }
}

internal interface ReleaseSource {
    fun latest(): AppRelease
    fun openApk(release: AppRelease): InputStream
}

internal class GitHubReleaseSource : ReleaseSource {
    private fun connection(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "MobileAgentRuntime-update")
        setRequestProperty("Accept-Encoding", "identity")
        // Deliberately no Provider credentials, installation identity or shared interceptors.
    }

    override fun latest(): AppRelease {
        val connection = connection(LATEST_RELEASE_API)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            require(connection.responseCode == 200) { "Release service unavailable" }
            val bytes = connection.inputStream.use { readLimited(it, 256 * 1024) }
            return parseLatestRelease(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    override fun openApk(release: AppRelease): InputStream {
        release.validate()
        var url = release.apkUrl
        repeat(6) { hop ->
            validateDownloadHop(url)
            val connection = connection(url)
            try {
                when (connection.responseCode) {
                    200 -> {
                        require(connection.contentLengthLong == -1L || connection.contentLengthLong == release.size)
                        val input = connection.inputStream
                        return object : java.io.FilterInputStream(input) {
                            override fun close() { try { super.close() } finally { connection.disconnect() } }
                        }
                    }
                    301, 302, 303, 307, 308 -> {
                        require(hop < 5)
                        val location = requireNotNull(connection.getHeaderField("Location"))
                        url = URI(url).resolve(location).toString()
                        validateDownloadHop(url)
                        connection.disconnect()
                    }
                    else -> error("APK download unavailable")
                }
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
        }
        error("Too many download redirects")
    }
}

internal fun readLimited(input: InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

internal class ReleaseDownloader(private val directory: File, private val source: ReleaseSource) {
    fun file(release: AppRelease): File = File(directory, "update-${release.validate().sha256}.apk")

    fun download(release: AppRelease, context: CoroutineContext, progress: (Long) -> Unit): File {
        release.validate()
        require(directory.isDirectory || directory.mkdirs())
        require(directory.usableSpace > release.size + 16 * 1024 * 1024)
        val target = file(release)
        // The process coordinator serializes downloads; this directory contains only update files.
        val partial = File(directory, "download.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val deadline = System.nanoTime() + 15L * 60 * 1_000_000_000
            source.openApk(release).use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.ensureActive()
                        require(System.nanoTime() < deadline) { "Download timed out" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= release.size)
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        progress(total)
                    }
                    output.fd.sync()
                }
            }
            context.ensureActive()
            require(total == release.size)
            require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
            if (target.exists()) require(target.delete())
            require(partial.renameTo(target))
            require(target.setReadOnly())
            // Keep just this candidate. The directory is private and never contains user files.
            directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
            return target
        } finally { partial.delete() }
    }
}
