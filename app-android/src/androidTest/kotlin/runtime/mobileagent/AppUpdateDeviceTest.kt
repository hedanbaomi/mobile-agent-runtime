// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import runtime.mobileagent.ui.AppUpdateDialog
import runtime.mobileagent.ui.MobileAgentTheme
import runtime.mobileagent.updates.*
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** No Provider calls or production telemetry. Optional signed fixture enables an actual system update. */
@RunWith(AndroidJUnit4::class)
class AppUpdateDeviceTest {
    private val app: MobileAgentApp get() = ApplicationProvider.getApplicationContext()
    /** An older synthetic stable candidate suppresses unrelated update UI, including on a local preview. */
    private fun appRelease() = AppRelease("0.0.0",
        "$RELEASE_REPOSITORY/releases/download/v0.0.0/mobileAgentRuntime-v0.0.0-arm64-v8a.apk",
        1, "0".repeat(64))
    @get:Rule(order = 0) val prepare = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            app.ensureHostInitialized()
            app.getSharedPreferences("notification-permission", Context.MODE_PRIVATE).edit().putBoolean("requested", true).commit()
            app.container.announcements.setStatsEnabled(false)
            // Suppress an unrelated automatic network check in UI fixtures without changing production code.
            val prefs = app.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
            val store = PreferenceUpdateStore(prefs)
            store.cachedRelease = appRelease(); store.checkedAt = System.currentTimeMillis()
            base.evaluate()
        }
    } }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fileProviderIsPrivateNarrowAndReadOnlyWhenGranted() {
        val pm = app.packageManager
        val provider = pm.resolveContentProvider("${app.packageName}.app-updates", 0)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
        val directory = File(app.filesDir, "app-updates").apply { mkdirs() }
        val file = File(directory, "uri-test.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.app-updates", file)
            assertEquals("content", uri.scheme)
            assertArrayEquals(byteArrayOf(1, 2, 3), app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            try {
                FileProvider.getUriForFile(app, "${app.packageName}.app-updates", File(app.filesDir, "user-secret.txt"))
                fail("User directory must not be shared")
            } catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }

    @Suppress("DEPRECATION")
    @Test fun realAndroidCertificateCollectionRejectsTamperedApk() {
        val original = File(app.applicationInfo.sourceDir)
        val tampered = File(app.cacheDir, "tampered-update.apk")
        try {
            ZipFile(original).use { zip ->
                ZipOutputStream(tampered.outputStream()).use { output ->
                    zip.entries().asSequence().forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).use { input ->
                            if (entry.name == "classes.dex") {
                                val bytes = input.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                                output.write(bytes)
                            } else input.copyTo(output)
                        }
                        output.closeEntry()
                    }
                }
            }
            val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            assertNotNull(app.packageManager.getPackageArchiveInfo(original.path, flags))
            assertNull(app.packageManager.getPackageArchiveInfo(tampered.path, flags))
        } finally { tampered.delete() }
    }

    @Test fun successTimePersistsAcrossStoreRecreationAndLegacyDayRecordCannotSuppressChecks() = runBlocking {
        val prefs = app.getSharedPreferences("update-store-device-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = PreferenceUpdateStore(prefs)
        assertEquals(0L, store.checkedAt)
        store.checkedAt = 1_700_000_000_000L
        // A second instance reads the same durable preference file, which is what a restarted process does.
        assertEquals(1_700_000_000_000L, PreferenceUpdateStore(prefs).checkedAt)

        // A record written before the hourly scheme stored only the local day, so it must not suppress a check.
        prefs.edit().clear().commit()
        val legacy = PreferenceUpdateStore(prefs)
        legacy.cachedRelease = appRelease()
        prefs.edit().putString("checked-day", LocalDate.now().toString()).commit()
        val upgraded = PreferenceUpdateStore(prefs)
        assertEquals(0L, upgraded.checkedAt)
        assertEquals(appRelease().version, upgraded.cachedRelease?.version)

        var calls = 0
        val source = object : ReleaseSource {
            override fun latest(): AppRelease { calls++; return appRelease() }
            override fun openApk(release: AppRelease) = ByteArrayInputStream(ByteArray(0))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val updates = AppUpdateCoordinator(source, upgraded, scope, File(app.filesDir, "app-updates"),
                BuildConfig.VERSION_NAME, true, { _, _ -> })
            updates.check(manual = true).join()
            assertEquals(1, calls)
            assertTrue(upgraded.checkedAt > 0L)
            // The same timestamp then throttles the automatic check for one hour.
            updates.check().join()
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun liveOfficialReleaseCanBeCheckedWithoutAnnouncements() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live-update-check") == "true")
        val source = GitHubReleaseSource()
        val release = source.latest().validate()
        assertTrue(release.apkUrl.startsWith(RELEASE_REPOSITORY))
        assertEquals(64, release.sha256.length)
        assertTrue(release.size > 1_000_000)
        source.openApk(release).use { input ->
            val prefix = ByteArray(4)
            var offset = 0
            while (offset < prefix.size) {
                val count = input.read(prefix, offset, prefix.size - offset)
                assertTrue(count > 0); offset += count
            }
            assertArrayEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), prefix)
        }
    }

    @Test fun verifiedFixtureDownloadShowsInstallAndCanOpenSystemConfirmation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val encoded = args.getString("update-fixture-base64")
        assumeTrue(!encoded.isNullOrBlank())
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        File(app.filesDir, "update-preserved-marker.txt").writeText("preserve-on-upgrade")
        val directory = File(app.filesDir, "app-updates").apply { mkdirs() }
        val incoming = File(directory, "fixture-input.apk").apply { writeBytes(bytes) }
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = app.packageManager.getPackageArchiveInfo(incoming.path, flags)!!
        val version = info.versionName!!
        val candidate = AppRelease(version,
            "$RELEASE_REPOSITORY/releases/download/v$version/mobileAgentRuntime-v$version-arm64-v8a.apk", bytes.size.toLong(), sha256(incoming),
            "隔离模拟器的签名更新测试包")
        AndroidApkVerifier(app).verify(incoming, candidate)
        incoming.delete()
        File(directory, "update-${candidate.sha256}.apk").delete()
        val source = object : ReleaseSource {
            override fun latest() = candidate
            override fun openApk(release: AppRelease) = ByteArrayInputStream(bytes)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val updates = AppUpdateCoordinator(source, PreferenceUpdateStore(app.getSharedPreferences("update-fixture", Context.MODE_PRIVATE)),
            scope, directory, BuildConfig.VERSION_NAME, true, AndroidApkVerifier(app)::verify)
        try {
            updates.check(manual = true).join()
            compose.runOnUiThread { compose.activity.setContent { MobileAgentTheme { AppUpdateDialog(updates, true) } } }
            compose.onNodeWithText("下载并安装").assertExists()
            if (args.getString("open-system-installer") == "true") {
                compose.onNodeWithText("下载并安装").performClick()
                compose.waitUntil(30_000) { updates.state.value.phase == UpdatePhase.READY }
                Thread.sleep(120_000)
                // Host then inspects the system permission/installer UI and confirms on this isolated AVD.
            } else {
                assertNotNull(updates.downloadOrReady().await())
                compose.waitForIdle()
                compose.onNodeWithText("安装更新").assertExists()
                assertNotNull(updates.readyForInstall())
            }
        } finally { scope.cancel() }
    }
}
