// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.feature.settings.ThirdPartyNoticeAssets
import runtime.mobileagent.feature.settings.ThirdPartyNoticesUiState

/**
 * Loads the notice catalog from the same signed-in-package assets the UI reads.
 *
 * The index references `modelpacks/distiluse-base-multilingual-cased-v2/LICENSE-NOTICE.txt` and
 * `licenses/aosp-material-icons-1.7.6/{LICENSE.txt,NOTICE.txt}`; every packaged entry must remain
 * accepted and readable without network access.
 */
class ThirdPartyNoticesDeviceTest {
    @Test
    fun bundledCatalogLoadsEveryComponentIncludingModelPackNotices() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state: ThirdPartyNoticesUiState = ThirdPartyNoticeAssets.loadCatalog(context)

        assertNull("every packaged notice path must be accepted: ${state.error}", state.error)
        assertTrue("the bundled index must expose components", state.components.size > 100)
        assertTrue(state.overview.isNotBlank())

        val modelPack = state.components.firstOrNull { it.id == "asset:distiluse-base-multilingual-cased-v2" }
        assertTrue("the bundled model pack component must survive loading", modelPack != null)
        val noticePath = "modelpacks/distiluse-base-multilingual-cased-v2/LICENSE-NOTICE.txt"
        assertTrue(
            "the model pack notice path must be part of the component: ${modelPack!!.files}",
            modelPack.files.any { it.path == noticePath },
        )

        val text = ThirdPartyNoticeAssets.loadComponentText(context, modelPack).getOrThrow()
        assertTrue(text.contains(noticePath))
        assertTrue(text.contains("===== "))

        val vendoredIcons = state.components.firstOrNull { it.id == "vendored:aosp-material-icons-1.7.6" }
        assertTrue("the vendored Material icons attribution must be indexed", vendoredIcons != null)
        assertEquals("Apache-2.0", vendoredIcons!!.license)
        val iconLicensePath = "licenses/aosp-material-icons-1.7.6/LICENSE.txt"
        val iconNoticePath = "licenses/aosp-material-icons-1.7.6/NOTICE.txt"
        assertTrue(vendoredIcons.files.any { it.path == iconLicensePath })
        assertTrue(vendoredIcons.files.any { it.path == iconNoticePath })

        val vendoredText = ThirdPartyNoticeAssets.loadComponentText(context, vendoredIcons).getOrThrow()
        assertTrue(vendoredText.contains("Apache License"))
        assertTrue(vendoredText.contains("Outlined.Chat"))
    }
}
