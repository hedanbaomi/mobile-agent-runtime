// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

internal data class ContentDocument(val path: String, val bytes: ByteArray)
internal data class ManifestItem(val href: String, val mediaType: String)

/** Every declared spine item is retained, including nav and linear=no auxiliaries. */
internal object EpubPackage {
    fun read(entries: Map<String, ByteArray>): List<ContentDocument> {
        val container = entries["META-INF/container.xml"] ?: error("EPUB is missing META-INF/container.xml")
        val roots = ContainerHandler()
        expectXml(roots, decodeUtf8(container, "EPUB container.xml"))
        val declared = roots.path ?: error("EPUB container declares no package")
        check(!declared.contains('#') && !declared.contains('?')) { "Invalid EPUB package document path" }
        val path = resolvePackagePath("container.xml", declared)
            ?: error("EPUB package document path is not allowed")
        val payload = entries[path] ?: error("EPUB package document is missing")
        val packageHandler = EpubPackageHandler()
        expectXml(packageHandler, decodeUtf8(payload, "EPUB package document"))
        check(packageHandler.spine.isNotEmpty()) { "EPUB spine declares no reading order" }
        val seenPaths = HashSet<String>()
        return packageHandler.spine.map { id ->
            val item = packageHandler.manifest[id] ?: error("EPUB spine names an unknown manifest item")
            check(!item.href.contains('#') && !item.href.contains('?')) { "Invalid EPUB manifest href" }
            val resolved = resolvePackagePath(path, item.href) ?: error("EPUB spine content path is not allowed")
            check(seenPaths.add(resolved)) { "EPUB spine repeats a content document" }
            check(item.mediaType == "application/xhtml+xml") { "EPUB spine content type is unsupported" }
            ContentDocument(resolved, entries[resolved] ?: error("EPUB spine content document is missing"))
        }
    }

    private class ContainerHandler : DefaultHandler() {
        var path: String? = null
        private val stack = mutableListOf<String>()
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            require(uri == "urn:oasis:names:tc:opendocument:xmlns:container") { "Invalid EPUB container namespace" }
            val name = localName.orEmpty()
            if (stack.isEmpty()) require(name == "container") { "Invalid EPUB container root" }
            if (name == "rootfile" && stack == listOf("container", "rootfiles") && path == null &&
                attributes.getValue("media-type") == "application/oebps-package+xml") {
                path = attributes.getValue("full-path")?.takeIf { it.isNotBlank() }
            }
            stack += name
        }
        override fun endElement(uri: String?, localName: String?, qName: String?) { stack.removeAt(stack.lastIndex) }
    }
}
