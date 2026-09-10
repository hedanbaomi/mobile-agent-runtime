// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.knowledge.PdfParser

/** Explicit opt-in read of task-owned emulator fixtures; never a CI dependency. */
@RunWith(AndroidJUnit4::class)
class PdfParserMemoryDeviceTest {
    @Test(timeout = 240_000)
    fun repeatedRealPdfParsingKeepsMemoryBoundedWithoutEmbeddingOrRasterization() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("userPdfMemory") == "true")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val paths = listOf(
            "/sdcard/Download/QA-Knowledge/books/1+app+book+1+interior.pdf",
            "/sdcard/Download/QA-Knowledge/books/12+adept+12.pdf",
        )
        val baseline = sample(0)
        repeat(4) { round ->
            val descriptor = automation.executeShellCommand("cat ${paths[round % paths.size]}")
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            assertTrue("Expected bounded PDF fixture", bytes.size in 5..20_000_000)
            val parsed = PdfParser.parse(bytes)
            assertTrue(parsed.pages.size > 100)
            val current = sample(round + 1)
            // Stop at a conservative process budget before starving the 4 GiB
            // test emulator. No GC injection may mask retained native memory.
            assertTrue("PDF parser exceeded the 768 MiB incremental PSS budget", current - baseline < 768L * 1024)
        }
    }

    private fun sample(round: Int): Long {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        Log.i("PdfParserMemory", "round=$round pssKiB=${info.totalPss} nativeKiB=${info.nativePss} " +
            "dalvikKiB=${info.dalvikPss} nativeAllocated=${Debug.getNativeHeapAllocatedSize()} " +
            "javaAllocated=${runtime.totalMemory() - runtime.freeMemory()}")
        return info.totalPss.toLong()
    }
}
