// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class ScreenObservationBackupExclusionTest {
    @Test
    fun backupAndDeviceTransferRulesExplicitlyExcludeScreenObservationAndSensitiveStores() {
        val screenObservationXml = "${ScreenObservationRegistry.PREFERENCES_NAME}.xml"
        assertEquals("screen-observation.xml", screenObservationXml)

        val extractionRoot = parseXml(resolveXmlResource("data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", extractionRoot.tagName)
        val cloudBackup = singleChildElement(extractionRoot, "cloud-backup")
        assertEquals("true", cloudBackup.getAttribute("disableIfNoEncryptionCapabilities"))
        val deviceTransfer = singleChildElement(extractionRoot, "device-transfer")
        val fullBackup = parseXml(resolveXmlResource("backup_rules.xml"))
        assertEquals("full-backup-content", fullBackup.tagName)

        val requiredSharedPrefs = setOf(
            screenObservationXml,
            "workspace-ui-presentation.xml",
            "ui-preferences.xml",
            "trpg-atlas-basemap.xml",
            "trpg-media-selection.xml",
            "resident-adb-v1.xml",
            "runtime.mobileagent.wired.adb.trust.v1.xml",
            "runtime.mobileagent.wired.metadata.v1.xml",
            "runtime.mobileagent.identity.v1.xml",
            "diagnostic-preferences.xml",
            ".",
        )
        val requiredFiles = setOf("import-staging", "python-skill-kv", ".")
        val sections = listOf(
            "cloud-backup" to cloudBackup,
            "device-transfer" to deviceTransfer,
            "full-backup-content" to fullBackup,
        )

        for ((sectionName, section) in sections) {
            assertTrue(childElements(section, "include").isEmpty(), "$sectionName must not include overrides")
            val excludes = childElements(section, "exclude")
                .map { it.getAttribute("domain") to it.getAttribute("path") }
                .toSet()

            for (pref in requiredSharedPrefs) {
                assertTrue(
                    ("sharedpref" to pref) in excludes,
                    "$sectionName missing sharedpref exclusion for $pref",
                )
            }
            for (file in requiredFiles) {
                assertTrue(
                    ("file" to file) in excludes,
                    "$sectionName missing file exclusion for $file",
                )
            }
            assertTrue(("database" to ".") in excludes, "$sectionName missing database '.' exclusion")
            assertTrue(("root" to ".") in excludes, "$sectionName missing root '.' exclusion")
            assertTrue(("external" to ".") in excludes, "$sectionName missing external '.' exclusion")
        }
    }

    @Test
    fun targetDeviceWithoutPreferencesDefaultsObservationAndActionsConsentToFalse() {
        val emptyPreferences = MapSharedPreferences()
        val registry = ScreenObservationRegistry("runtime.mobileagent", emptyPreferences)

        val initial = registry.access.value
        assertFalse(initial.consent)
        assertFalse(initial.actionsAllowed)
        assertFalse(initial.ready)
        assertFalse(initial.actionsReady)

        assertFalse(registry.setActionsAllowed(true))
        assertFalse(registry.access.value.actionsAllowed)

        val orphanedActionsPreferences = MapSharedPreferences(mutableMapOf("actions" to true))
        val orphanedRegistry = ScreenObservationRegistry("runtime.mobileagent", orphanedActionsPreferences)
        assertFalse(orphanedRegistry.access.value.consent)
        assertFalse(orphanedRegistry.access.value.actionsAllowed)

        assertTrue(registry.setConsent(true))
        assertTrue(registry.access.value.consent)
        assertFalse(registry.access.value.actionsAllowed)

        assertTrue(registry.setActionsAllowed(true))
        assertTrue(registry.access.value.actionsAllowed)

        assertTrue(registry.setConsent(false))
        assertFalse(registry.access.value.consent)
        assertFalse(registry.access.value.actionsAllowed)
        assertFalse(emptyPreferences.getBoolean("consent", true))
        assertFalse(emptyPreferences.getBoolean("actions", true))
    }

    private fun resolveXmlResource(fileName: String): Path {
        val candidates = listOf(
            Paths.get("src", "main", "res", "xml", fileName),
            Paths.get("app-android", "src", "main", "res", "xml", fileName),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("Missing XML resource $fileName from ${Paths.get("").toAbsolutePath()}")
    }

    private fun parseXml(path: Path): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        return Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it).documentElement }
    }

    private fun singleChildElement(parent: Element, tagName: String): Element {
        val matches = childElements(parent, tagName)
        assertEquals(1, matches.size, "Expected single <$tagName> under <${parent.tagName}>")
        return matches.single()
    }

    private fun childElements(parent: Element, tagName: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .filter { it.tagName == tagName }
    }
}

private class MapSharedPreferences(
    initial: Map<String, Any?> = emptyMap(),
) : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>(initial.filterValues { it != null }.mapValues { it.value!! })

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class EditorImpl : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { staged[key] = values?.toSet() }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { staged[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean {
            applyChanges()
            return true
        }
        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearAll) values.clear()
            for ((k, v) in staged) {
                if (v == null) values.remove(k) else values[k] = v
            }
        }
    }
}
