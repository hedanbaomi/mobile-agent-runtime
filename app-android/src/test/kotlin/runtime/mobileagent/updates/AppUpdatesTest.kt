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

    /** Controllable wall clock; the automatic interval is measured against it. */
    @Volatile private var clock = 1_750_000_000_000L

    private fun release(version: String = "1.0.3", content: ByteArray = bytes) = AppRelease(
        version, "$RELEASE_REPOSITORY/releases/download/v$version/mobileAgentRuntime-v$version-arm64-v8a.apk",
        content.size.toLong(), MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
    )
    private class Store : UpdateCheckStore {
        override var checkedAt = 0L
        override var cachedRelease: AppRelease? = null
        override var dismissedPrompt = ""
    }
    private inner class Source : ReleaseSource {
        @Volatile var calls = 0
        @Volatile var downloads = 0
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
        elapsed: () -> Long = { 0 }, now: () -> Long = { clock }, tickMillis: Long = FOREGROUND_TICK_MILLIS,
        compatible: Boolean = true, verify: (File, AppRelease) -> Unit = { _, _ -> }) =
        AppUpdateCoordinator(source, store, scope, temp.resolve("updates").toFile(), "1.0.2", compatible, verify, day, elapsed, now, tickMillis)

    /** Waits for a background ticker without pinning any specific scheduling latency. */
    private fun awaitCalls(source: Source, expected: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (source.calls < expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(source.calls >= expected, "expected at least $expected release calls, saw ${source.calls}")
    }

    @Test fun integerVersionsAndStrictStableTags() {
        assertTrue(ReleaseVersion.parse("1.0.10") > ReleaseVersion.parse("1.0.9"))
        assertTrue(ReleaseVersion.parse("2.0.0") > ReleaseVersion.parse("1.99.99"))
        for (bad in listOf("v1.0.2", "1.0", "1.0.3-beta", "01.0.3", "1.0.999999999999", "-1.0.0")) {
            assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse(bad) }
        }
    }

    @Test fun previewInstallationFindsSamePatchStableWithoutAcceptingPreviewFeeds() = runBlocking<Unit> {
        val source = Source().apply { candidate = release("1.1.3") }
        val store = Store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = AppUpdateCoordinator(source, store, scope, temp.resolve("preview-updates").toFile(),
                "1.1.4preview", true, { _, _ -> }, now = { clock })
            updates.check(manual = true).join()
            assertEquals(UpdatePhase.IDLE, updates.state.value.phase)
            source.candidate = release("1.1.4")
            updates.check(manual = true).join()
            assertEquals(UpdatePhase.AVAILABLE, updates.state.value.phase)
            assertEquals("1.1.4", updates.state.value.release?.version)

            val installed = ApkIdentity("runtime.mobileagent", "1.1.4preview", 9, setOf("same-signer"), 26)
            val candidate = installed.copy(versionName = "1.1.4", versionCode = 10)
            verifyApkIdentity(installed, candidate, source.candidate, 34)
            assertThrows(IllegalArgumentException::class.java) { verifyApkIdentity(installed, candidate.copy(versionCode = 9), source.candidate, 34) }
            assertFalse(isNewerThanInstalled("1.1.4", "1.1.4"))
            assertFalse(isNewerThanInstalled("1.1.3", "1.1.4preview"))
            assertTrue(isNewerThanInstalled("1.1.5", "1.1.4preview"))
            assertTrue(isNewerThanInstalled("1.1.4", "1.1.4.1preview"))
            assertFalse(isNewerThanInstalled("1.1.3", "1.1.4.1preview"))
            verifyApkIdentity(installed.copy(versionName = "1.1.4.1preview", versionCode = 10),
                candidate.copy(versionCode = 11), source.candidate, 34)
            assertThrows(IllegalArgumentException::class.java) { release("1.1.4.1preview").validate() }
            for (invalid in listOf("1.1.4-beta", "1.1.4previewpreview", "01.1.4preview", "1.1.4.01preview", "1.1.4.1.2preview", "1.1.4.1", "preview")) {
                assertThrows(IllegalArgumentException::class.java) { isNewerThanInstalled("1.1.4", invalid) }
            }
            assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("1.1.4preview") }
            assertThrows(IllegalArgumentException::class.java) { release("1.1.4preview").validate() }
            assertThrows(Exception::class.java) { parseLatestRelease(apiBody(release("1.1.4preview"))) }
        } finally { scope.cancel() }
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

    @Test fun automaticChecksRepeatOnlyAfterOneHour() = runBlocking {
        val source = Source(); val store = Store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, store, scope)
            updates.check().join()
            assertEquals(1, source.calls); assertEquals(clock, store.checkedAt)
            clock += UPDATE_CHECK_INTERVAL_MILLIS - 1
            updates.check().join()
            assertEquals(1, source.calls)                       // 59:59.999 is still inside the interval
            clock += 1
            updates.check().join()
            assertEquals(2, source.calls)                       // exactly one hour is due again
            assertEquals(clock, store.checkedAt)
            assertEquals("1.0.3", updates.state.value.release?.version)
            assertEquals(0, source.downloads)                   // a check never starts a download
        } finally { scope.cancel() }
    }

    @Test fun successfulTimeSurvivesProcessRecreationAndRestoreNeedsNoNetwork() = runBlocking {
        val source = Source(); val store = Store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            coordinator(source, store, scope).check().join()
            assertEquals(1, source.calls)
            // A fresh coordinator is what a restarted process builds; the persisted success applies.
            val restored = coordinator(source, store, scope)
            restored.check().join()
            assertEquals(1, source.calls)
            // The installer path restores the persisted candidate without another network request.
            restored.restore().join()
            assertEquals("1.0.3", restored.state.value.release?.version)
            assertEquals(1, source.calls)
            clock += UPDATE_CHECK_INTERVAL_MILLIS
            restored.check().join()
            assertEquals(2, source.calls)
        } finally { scope.cancel() }
    }

    @Test fun manualChecksAlwaysBypassTheHourlyInterval() = runBlocking {
        val source = Source(); val store = Store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, store, scope)
            updates.check().join(); updates.check(manual = true).join()
            assertEquals(2, source.calls)
            updates.check(manual = true).join()
            assertEquals(3, source.calls)
            clock += UPDATE_CHECK_INTERVAL_MILLIS
            updates.check().join()
            assertEquals(4, source.calls)
        } finally { scope.cancel() }
    }

    @Test fun dismissedVersionDoesNotPromptAgainTheSameDayButDoesTheNextDay() = runBlocking {
        val source = Source(); val store = Store(); var today = "2026-10-06"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, store, scope, day = { today })
            updates.check().join()
            assertTrue(updates.state.value.prompt)
            updates.dismiss()
            assertEquals("$today:1.0.3", store.dismissedPrompt)
            // The next due check one hour later keeps the candidate without prompting again.
            clock += UPDATE_CHECK_INTERVAL_MILLIS
            updates.check().join()
            assertEquals(2, source.calls)
            assertEquals("1.0.3", updates.state.value.release?.version)
            assertFalse(updates.state.value.prompt)
            // A restarted process inside the same day still does not re-prompt for the same version.
            val restored = coordinator(source, store, scope, day = { today })
            restored.check().join()
            assertEquals(2, source.calls)
            assertEquals("1.0.3", restored.state.value.release?.version)
            assertFalse(restored.state.value.prompt)
            // A new local day offers the same version again at the next due check.
            today = "2026-10-07"
            clock += UPDATE_CHECK_INTERVAL_MILLIS
            restored.check().join()
            assertEquals(3, source.calls)
            assertTrue(restored.state.value.prompt)
        } finally { scope.cancel() }
    }

    @Test fun futureOrRolledBackSuccessTimeNeverBlocksChecksPermanently() = runBlocking {
        val source = Source(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // A record written while the device clock was ahead must not suppress checks after correction.
            val store = Store().apply { cachedRelease = release(); checkedAt = clock + 86_400_000L }
            val updates = coordinator(source, store, scope)
            updates.check().join()
            assertEquals(1, source.calls)
            // A clock rollback puts the stored success in the future; the check is due, not blocked.
            store.checkedAt = clock + 3_600_000L
            updates.check().join()
            assertEquals(2, source.calls)
            // The success above rewrote a trustworthy time, so the hourly interval applies again.
            clock += 1_000
            updates.check().join()
            assertEquals(2, source.calls)
        } finally { scope.cancel() }
    }

    @Test fun missingCandidateOrLegacyDayOnlyRecordIsImmediatelyDueAgain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // A record written by the previous day-based scheme has no success time at all.
            val legacySource = Source()
            val legacy = Store().apply { cachedRelease = release(); checkedAt = 0L }
            val upgraded = coordinator(legacySource, legacy, scope)
            upgraded.check().join()
            assertEquals(1, legacySource.calls)
            assertEquals(clock, legacy.checkedAt)
            // A missing candidate is due again even when a success time is present.
            val emptySource = Source()
            coordinator(emptySource, Store().apply { checkedAt = clock }, scope).check().join()
            assertEquals(1, emptySource.calls)
        } finally { scope.cancel() }
    }

    @Test fun failuresPreserveCacheDoNotRecordSuccessTimeAndManualBypassesBackoff() = runBlocking {
        val source = Source(); var time = 0L
        val store = Store().apply { cachedRelease = release("1.0.4") }
        source.fail = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, store, scope, elapsed = { time })
            updates.check().join(); updates.check().join()
            assertEquals(1, source.calls); assertEquals(0L, store.checkedAt)
            assertEquals("1.0.4", store.cachedRelease?.version)
            assertEquals("1.0.4", updates.state.value.release?.version)
            assertFalse(updates.state.value.prompt)
            updates.check(manual = true).join(); assertEquals(2, source.calls)
            source.fail = false; time = UPDATE_FAILURE_BACKOFF_MILLIS
            updates.check().join(); assertEquals(3, source.calls)
            assertEquals(clock, store.checkedAt)
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

    @Test fun automaticCheckNeverClobbersAnActiveDownload() = runBlocking {
        val source = Source().apply { downloadGate = CountDownLatch(1) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, Store(), scope)
            updates.check().join()
            val download = updates.downloadOrReady()
            assertTrue(source.downloadEntered.await(5, TimeUnit.SECONDS))
            clock += 3 * UPDATE_CHECK_INTERVAL_MILLIS
            updates.check().join()
            updates.check(manual = true).join()
            assertEquals(UpdatePhase.DOWNLOADING, updates.state.value.phase)
            assertEquals(1, source.calls)
            source.downloadGate!!.countDown()
            assertNotNull(download.await())
        } finally { source.downloadGate?.countDown(); scope.cancel() }
    }

    @Test fun foregroundTickerChecksWhenDueAndStopsAfterBackground() = runBlocking {
        val source = Source()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updates = coordinator(source, Store(), scope, tickMillis = 10)
            updates.foreground()
            awaitCalls(source, 1)
            Thread.sleep(80)                                     // ticks inside the interval stay free
            assertEquals(1, source.calls)
            clock += UPDATE_CHECK_INTERVAL_MILLIS                // the hour expires while still visible
            awaitCalls(source, 2)
            updates.background()
            Thread.sleep(300)
            val settled = source.calls
            clock += UPDATE_CHECK_INTERVAL_MILLIS
            Thread.sleep(300)
            assertEquals(settled, source.calls)                  // no tick survives leaving the foreground
            updates.foreground()                                 // returning foregrounds a due check again
            awaitCalls(source, settled + 1)
        } finally { scope.cancel() }
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
