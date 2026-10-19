// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The bilingual resource contract for this module: both locales declare the same keys,
 * positional format contracts agree, and every declared key is actually rendered while
 * every rendered key exists. Without this, a missing translation would only surface as a
 * runtime crash inside a localized build.
 */
class WorldBooksResourceParityTest {

    private val moduleDir: File = resolveModuleDir()

    private val english: Map<String, String> = entries(stringsFile("values"))
    private val chinese: Map<String, String> = entries(stringsFile("values-zh-rCN"))

    @Test
    fun bothLocalesDeclareTheSameKeys() {
        assertTrue(english.isNotEmpty(), "English resources must not be empty")
        assertEquals(
            english.keys.sorted(),
            chinese.keys.sorted(),
            "values/strings.xml and values-zh-rCN/strings.xml must declare identical keys",
        )
    }

    @Test
    fun positionalFormatContractsIdenticalPerKey() {
        val mismatched = english.keys.intersect(chinese.keys).filter { formats(english[it].orEmpty()) != formats(chinese[it].orEmpty()) }
        assertTrue(mismatched.isEmpty(), "positional format contract differs for: $mismatched")
    }

    @Test
    fun noKeyIsSilentlyTranslatedToTheSourceLanguage() {
        val untranslated = english.keys.filter { key -> english[key] == chinese[key] }
        assertTrue(
            untranslated.isEmpty(),
            "these keys read identically in both locales and are probably untranslated: $untranslated",
        )
    }

    @Test
    fun everyRenderedKeyExistsAndEveryDeclaredKeyIsRendered() {
        val used = renderedKeys()
        val missing = used - english.keys
        assertTrue(missing.isEmpty(), "rendered but not declared: $missing")
        val unused = english.keys - used
        assertTrue(unused.isEmpty(), "declared but never rendered: $unused")
    }

    private fun resolveModuleDir(): File {
        val candidates = listOf(File("."), File("feature/worldbooks"), File("../feature/worldbooks"))
        return candidates.firstOrNull { File(it, "src/main/res/values/strings.xml").isFile }
            ?: error("worldbooks resources not found from ${File(".").absolutePath}")
    }

    private fun stringsFile(qualifier: String) = File(moduleDir, "src/main/res/$qualifier/strings.xml")

    private fun entries(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            result[element.getAttribute("name")] = element.textContent.orEmpty()
        }
        return result
    }

    private fun formats(text: String): Set<String> =
        POSITIONAL.findAll(text).map { it.groupValues[1] + it.groupValues[2] }.toSet()

    private fun renderedKeys(): Set<String> {
        val sources = File(moduleDir, "src/main/kotlin")
        return sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> USAGE.findAll(file.readText()).map { it.groupValues[1] }.asSequence() }
            .toSet()
    }

    private companion object {
        private val POSITIONAL = Regex("%([1-9]\\d*)\\$([a-zA-Z])")
        private val USAGE = Regex("R\\.string\\.(wb_[A-Za-z0-9_]+)")
    }
}
