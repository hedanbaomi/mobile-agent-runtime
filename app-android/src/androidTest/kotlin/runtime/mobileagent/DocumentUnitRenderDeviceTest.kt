// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.storage.AndroidPdfRendererAdapter
import java.io.File
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class)
class DocumentUnitRenderDeviceTest {
    @Test fun oversizedStandaloneImageUsesBoundedRegionDecode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = AndroidPdfRendererAdapter(context)
        val bitmap = Bitmap.createBitmap(3072, 2048, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(-1)
            ByteArrayOutputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
        assertEquals(3072 to 2048, renderer.imageDimensions(bytes))
        val units = DocumentUnitPlanner().plan("image", listOf(PlanningPage(1, "", true, 3072, 2048)))
        assertTrue(units.size > 1)
        units.forEach { unit ->
            val rendered = requireNotNull(renderer.renderImageUnit(bytes, unit, UnitRenderLimits(512, 262144, 1024 * 1024)))
            assertTrue(rendered.width <= 512 && rendered.height <= 512)
            assertTrue(rendered.width.toLong() * rendered.height <= 262144)
        }
    }

    @Test fun repeatedSyntheticCropsReleaseNativeBuffersAndTemporaryFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = AndroidPdfRendererAdapter(context)
        val bytes = PdfParser.writeSimpleTextPdf("Synthetic memory fixture")
        val units = DocumentUnitPlanner().plan("synthetic", listOf(PlanningPage(1, "caption", true, dense = true)))
        val baseline = Debug.getNativeHeapAllocatedSize()
        repeat(120) { index ->
            val image = requireNotNull(renderer.renderUnit(bytes, units[index % units.size], UnitRenderLimits(512, 262144, 1024 * 1024)))
            assertTrue(image.width.toLong() * image.height <= 262144)
            assertTrue(image.bytes.size <= 1024 * 1024)
            assertTrue("Native images retained across requests", Debug.getNativeHeapAllocatedSize() - baseline < 32L * 1024 * 1024)
        }
        assertTrue(File(context.cacheDir, "pdf-render").listFiles().orEmpty().isEmpty())
    }

    @Test fun encodedBudgetFailureReleasesResourcesAndNextRenderSucceeds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = AndroidPdfRendererAdapter(context)
        val bytes = PdfParser.writeSimpleTextPdf("bounded encoding")
        val unit = DocumentUnitPlanner().plan("synthetic", listOf(PlanningPage(1, "", true))).single()
        assertNull(renderer.renderUnit(bytes, unit, UnitRenderLimits(maxEncodedBytes = 1)))
        assertNotNull(renderer.renderUnit(bytes, unit))
        assertTrue(File(context.cacheDir, "pdf-render").listFiles().orEmpty().isEmpty())
    }
}
