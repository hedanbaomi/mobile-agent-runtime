// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Structural parents and namespace identity prevent decoy item/itemref matches. */
internal class EpubPackageHandler : DefaultHandler() {
    val manifest = linkedMapOf<String, ManifestItem>()
    val spine = mutableListOf<String>()
    private val stack = mutableListOf<Pair<String?, String>>()
    private var manifestSeen = false
    private var spineSeen = false

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val name = localName.orEmpty()
        if (stack.isEmpty()) {
            require(uri == OPF_NS && name == "package") { "Invalid EPUB package root" }
            require(attributes.getValue("version") in listOf("2.0", "3.0")) { "Unsupported EPUB package version" }
        }
        val parent = stack.lastOrNull()
        if (uri == OPF_NS && parent == (OPF_NS to "package")) {
            when (name) {
                "manifest" -> { require(!manifestSeen) { "Duplicate EPUB manifest" }; manifestSeen = true }
                "spine" -> { require(!spineSeen) { "Duplicate EPUB spine" }; spineSeen = true }
            }
        }
        if (uri == OPF_NS && parent == (OPF_NS to "manifest") && name == "item") addManifest(attributes)
        if (uri == OPF_NS && parent == (OPF_NS to "spine") && name == "itemref") {
            require(spine.size < MAX_PACKAGE_ITEMS) { "EPUB spine exceeds the item limit" }
            val id = attributes.getValue("idref")?.takeIf { it.isNotBlank() } ?: error("EPUB spine has no idref")
            require(id !in spine) { "EPUB spine repeats a manifest item" }
            val linear = attributes.getValue("linear")
            require(linear == null || linear == "yes" || linear == "no") { "Invalid EPUB linear attribute" }
            spine += id
        }
        stack += uri to name
    }

    private fun addManifest(attributes: Attributes) {
        val id = attributes.getValue("id")?.takeIf { it.isNotBlank() } ?: error("EPUB manifest has no id")
        require(manifest.size < MAX_PACKAGE_ITEMS && id !in manifest) { "EPUB manifest has duplicate or excessive items" }
        val href = attributes.getValue("href")?.takeIf { it.isNotBlank() } ?: error("EPUB manifest has no href")
        manifest[id] = ManifestItem(href, attributes.getValue("media-type").orEmpty())
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) { stack.removeAt(stack.lastIndex) }
}
