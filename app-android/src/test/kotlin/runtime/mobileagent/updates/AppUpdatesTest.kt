// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.updates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppUpdatesTest {
    @TempDir lateinit var temp: Path
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private fun release(version: String = "1.0.3", content: ByteArray = bytes) = AppRelease(
        version, "$RELEASE_REPOSITORY/releases/download/v$version/mobileAgentRuntime-v$version-arm64-v8a.apk",
        content.size.toLong(), MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
    )
    private class Store : UpdateCheckStore {
        override var checkedDay = ""
        override var cachedRelease: AppRelease? = null
        override var dismissedPrompt = ""
    }
    private inner class Source : ReleaseSource {
        var calls = 0
        var downloads = 0
        var fail = false
        var content = bytes
        var candidate = release()
        var gate: CountDownLatch? = null
        var entered = CountDownLatch(1)
        var downloadGate: CountDownLatch? = null
        var downloadEntered = CountDownLatch(1)
        override fun latest(): AppRelease {
            calls++
            entered.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            check(!fail)
            return candidate
        }
        override fun openApk(release: AppRelease): InputStream {
            downloads++
            downloadEntered.countDown()
            downloadGate?.await(5, TimeUnit.SECONDS)
            return ByteArrayInputStream(content)
        }
    }
    private fun coordinator(source: Source, store: Store, scope: CoroutineScope, day: () -> String = { "2026-10-06" },
        elapsed: () -> Long = { 0 }, compatible: Boolean = true, verify: (File, AppRelease) -> Unit = { _, _ -> }) =
        AppUpdateCoordinator(source, store, scope, temp.resolve("updates").toFile(), "1.0.2", compatible, verify, day, elapsed)

    @Test fun integerVersionsAndStrictStableTags() {
        assertTrue(ReleaseVersion.parse("1.0.10") > ReleaseVersion.parse("1.0.9"))
        assertTrue(ReleaseVersion.parse("2.0.0") > ReleaseVersion.parse("1.99.99"))
        for (bad in listOf("v1.0.2", "1.0", "1.0.3-beta", "01.0.3", "1.0.999999999999", "-1.0.0")) {
            assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse(bad) }
        }
    }

    private fun apiBody(candidate: AppRelease = release(), draft: Boolean = false, prerelease: Boolean = false,
        url: String = candidate.apkUrl, digest: String = "sha256:${candidate.sha256}") = buildJsonObject {
        put("tag_name", "v${candidate.version}"); put("html_url", candidate.pageUrl)
        put("draft", draft); put("prerelease", prerelease); put("body", "Release notes")
        put("assets", buildJsonArray { add(buildJsonObject {
            put("name", "mobileAgentRuntime-v${candidate.version}-arm64-v8a.apk"); put("state", "uploaded")
            put("size", candidate.size); put("digest", digest); put("browser_download_url", url)
        }) })
    }.toString()

    @Test fun publishedApkIsDetectedWithoutAnyAnnouncement() {
        assertEquals(release().copy(notes = "Release notes"), parseLatestRelease(apiBody()))
    }
    @Test fun draftPrereleaseMissingDigestAndWrongRepositoryAreRejected() {
        for (body in listOf(apiBody(draft = true), apiBody(prerelease = true), apiBody(digest = "null"),
            apiBody(url = "https://github.com/attacker/runtime/releases/download/v1.0.3/a.apk"),
            apiBody().replace("\"assets\":[", "\"assets_missing\":["))) {
            assertThrows(Exception::class.java) { parseLatestRelease(body) }
        }
    }
    @Test fun redirectPolicyRejectsInsecureCredentialsPortsAndLookalikes() {
        validateDownloadHop(release().apkUrl)
        validateDownloadHop("https://release-assets.githubusercontent.com/github-production-release-asset/123?sig=test")
        for (url in listOf("http://github.com/hedanbaomi/mobile-agent-runtime/releases/download/a.apk",
            "https://user@github.com/hedanbaomi/mobile-agent-runtime/releases/download/a.apk",
            "https://github.com:443/hedanbaomi/mobile-agent-runtime/releases/download/a.apk",
            "https://release-assets.githubusercontent.com.evil.test/a.apk", "https://evil.test/a.apk",
            "https://github.com/other/repo/releases/download/a.apk", "https://github.com/hedanbaomi/mobile-agent-runtime/releases/download/%2e%2e/a.apk")) {
            assertThrows(Exception::class.java) { validateDownloadHop(url) }
        }
    }
    @Test fun unsafeSizesAndHashesAreRejected() {
        for (candidate in listOf(release().copy(size = 0), release().copy(size = MAX_APK_BYTES + 1),
            release().copy(sha256 = "abc"), release().copy(sha256 = "a".repeat(65)))) {
            assertThrows(Exception::class.java) { candidate.validate() }
        }
    }

    @Test fun oncePerLocalDayManualBypassAndRecreationKeepCandidate() = runBlocking {
        val source = Source(); val store = Store(); var today = "2026-10-06"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, store, scope, day = { today })
            updates.check().join(); updates.check().join()
            assertEquals(1, source.calls); assertTrue(updates.state.value.prompt)
            assertEquals(0, source.downloads)
            updates.dismiss()
            val restored = coordinator(source, store, scope, day = { today })
            restored.check().join()
            assertEquals(1, source.calls); assertEquals("1.0.3", restored.state.value.release?.version)
            assertFalse(restored.state.value.prompt)
            restored.check(manual = true).join()
            assertEquals(2, source.calls); assertTrue(restored.state.value.prompt)
            today = "2026-10-07"; restored.check().join()
            assertEquals(3, source.calls)
        } finally { scope.cancel() }
    }

    @Test fun clockRollbackChecksAgainAndCorruptDailyCacheDoesNotClaimLatest() = runBlocking {
        val source = Source(); val store = Store(); var today = "2026-10-07"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, store, scope, day = { today })
            updates.check().join(); today = "2026-10-06"; updates.check().join()
            assertEquals(2, source.calls)
            store.cachedRelease = null
            updates.check().join(); assertEquals(3, source.calls)
        } finally { scope.cancel() }
    }

    @Test fun failuresPreserveCacheDoNotRecordDayAndManualBypassesBackoff() = runBlocking {
        val source = Source(); val store = Store(); var time = 0L
        store.cachedRelease = release("1.0.4"); source.fail = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, store, scope, elapsed = { time })
            updates.check().join(); updates.check().join()
            assertEquals(1, source.calls); assertEquals("", store.checkedDay)
            assertEquals("1.0.4", store.cachedRelease?.version)
            assertEquals("1.0.4", updates.state.value.release?.version)
            assertFalse(updates.state.value.prompt)
            updates.check(manual = true).join(); assertEquals(2, source.calls)
            source.fail = false; time = 15 * 60 * 1000L
            updates.check().join(); assertEquals(3, source.calls)
            assertEquals("2026-10-06", store.checkedDay)
        } finally { scope.cancel() }
    }

    @Test fun simultaneousForegroundAndManualChecksHaveOneInFlightRequest() = runBlocking {
        val source = Source(); val store = Store(); source.gate = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, store, scope)
            val first = updates.check()
            assertTrue(source.entered.await(5, TimeUnit.SECONDS))
            (1..12).map { updates.check(manual = true) }.forEach { it.join() }
            assertEquals(1, source.calls)
            source.gate!!.countDown(); first.join()
        } finally { source.gate?.countDown(); scope.cancel() }
    }

    @Test fun equalAndOlderVersionsNeverOfferDownload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (version in listOf("1.0.2", "1.0.1")) {
                val source = Source().apply { candidate = release(version) }
                val updates = coordinator(source, Store(), scope)
                updates.check(manual = true).join()
                assertNull(updates.state.value.release)
                assertNull(updates.downloadOrReady().await())
                assertEquals(0, source.downloads)
            }
        } finally { scope.cancel() }
    }

    @Test fun wrongChecksumAndShortOrExcessDataLeaveNoInstallableFile() = runBlocking {
        for (content in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1), bytes.copyOf().apply { this[0] = 99 })) {
            val source = Source().apply { this.content = content }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val updates = coordinator(source, Store(), scope)
                updates.check().join()
                assertNull(updates.downloadOrReady().await())
                assertEquals(UpdatePhase.ERROR, updates.state.value.phase)
                assertTrue(temp.resolve("updates").toFile().listFiles().orEmpty().isEmpty())
                assertNull(updates.readyForInstall())
            } finally { scope.cancel() }
        }
    }

    @Test fun verifiedDownloadIsReusedAndAlwaysReverifiedBeforeInstall() = runBlocking {
        val source = Source(); var validations = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, Store(), scope, verify = { file, candidate ->
                assertEquals(candidate.sha256, sha256(file)); validations++
            })
            updates.check().join()
            val file = updates.downloadOrReady().await()!!
            assertEquals(UpdatePhase.READY, updates.state.value.phase)
            assertEquals(file, updates.readyForInstall())
            assertEquals(file, updates.downloadOrReady().await())
            assertEquals(1, source.downloads); assertEquals(3, validations)
        } finally { scope.cancel() }
    }

    @Test fun wrongApkIdentityDeletesCandidateAndAllowsRetry() = runBlocking {
        val source = Source(); var reject = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, Store(), scope, verify = { _, _ -> check(!reject) })
            updates.check().join(); assertNull(updates.downloadOrReady().await())
            assertTrue(temp.resolve("updates").toFile().listFiles().orEmpty().isEmpty())
            reject = false
            assertNotNull(updates.downloadOrReady().await()); assertEquals(2, source.downloads)
        } finally { scope.cancel() }
    }

    @Test fun repeatedDownloadClicksShareTaskAndCancellationCleansPartialAndCanRetry() = runBlocking {
        val source = Source().apply { downloadGate = CountDownLatch(1) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, Store(), scope)
            updates.check().join()
            val first = updates.downloadOrReady()
            assertTrue(source.downloadEntered.await(5, TimeUnit.SECONDS))
            repeat(12) { assertSame(first, updates.downloadOrReady()) }
            updates.cancelDownload(); source.downloadGate!!.countDown()
            first.join()
            assertTrue(first.isCancelled)
            assertEquals(UpdatePhase.AVAILABLE, updates.state.value.phase)
            assertTrue(temp.resolve("updates").toFile().listFiles().orEmpty().isEmpty())
            assertNotNull(updates.downloadOrReady().await())
            assertEquals(2, source.downloads)
        } finally { source.downloadGate?.countDown(); scope.cancel() }
    }

    @Test fun unsupportedDeviceCanCheckButCannotDownload() = runBlocking {
        val source = Source(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, Store(), scope, compatible = false)
            updates.check(manual = true).join()
            assertNotNull(updates.state.value.release); assertFalse(updates.state.value.compatible)
            assertNull(updates.downloadOrReady().await()); assertEquals(0, source.downloads)
        } finally { scope.cancel() }
    }

    @Test fun packageSignerVersionAndSdkMustAllMatch() {
        val installed = ApkIdentity("runtime.mobileagent", "1.0.2", 4, setOf("official"), 26)
        val candidate = installed.copy(versionName = "1.0.3", versionCode = 5)
        verifyApkIdentity(installed, candidate, release(), 34)
        for (invalid in listOf(candidate.copy(packageName = "attacker.app"), candidate.copy(versionName = "1.0.4"),
            candidate.copy(versionCode = 4), candidate.copy(signers = emptySet()), candidate.copy(signers = setOf("other")),
            candidate.copy(signers = setOf("official", "other")), candidate.copy(minSdk = 35))) {
            assertThrows(Exception::class.java) { verifyApkIdentity(installed, invalid, release(), 34) }
        }
    }
}
