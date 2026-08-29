// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.license

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

// REUSE-IgnoreStart
class LicenseScanner(
    private val expectedLicenseSha256: String = EXPECTED_AGPL_SHA256,
) {
    fun scan(root: Path): List<String> {
        val violations = mutableListOf<String>()
        val license = root.resolve("LICENSE")
        val licenseCopy = root.resolve("LICENSES").resolve("AGPL-3.0-only.txt")
        if (!license.isRegularFile()) {
            violations += "missing LICENSE"
        } else {
            val hash = sha256Hex(license.readBytes())
            if (hash != expectedLicenseSha256) {
                violations += "LICENSE hash $hash does not match locked AGPL-3.0-only hash $expectedLicenseSha256"
            }
        }
        if (!licenseCopy.isRegularFile()) {
            violations += "missing LICENSES/AGPL-3.0-only.txt"
        } else if (license.isRegularFile() && license.readBytes().contentEquals(licenseCopy.readBytes()).not()) {
            violations += "LICENSE and LICENSES/AGPL-3.0-only.txt are not byte-identical"
        }
        for (required in REQUIRED_MARKERS) {
            val file = root.resolve(required.path)
            if (!file.isRegularFile()) {
                violations += "missing ${required.path}"
            } else if (required.marker !in file.readText(Charsets.UTF_8)) {
                violations += "${required.path} is missing required marker ${required.marker}"
            }
        }
        val about = root.resolve(ABOUT_PATH)
        if (!about.isRegularFile()) {
            violations += "missing About source $ABOUT_PATH"
        } else {
            val text = about.readText(Charsets.UTF_8)
            if ("AGPL-3.0-only" !in text) {
                violations += "About source does not declare AGPL-3.0-only"
            }
            if (SOURCE_URL !in text) {
                violations += "About source does not declare source URL $SOURCE_URL"
            }
        }
        val reuse = root.resolve("REUSE.toml")
        if (!reuse.isRegularFile()) {
            violations += "missing REUSE.toml"
        } else {
            val reuseText = reuse.readText(Charsets.UTF_8)
            if ("AGPL-3.0-only" !in reuseText && "SPDX-PackageDownloadLocation" !in reuseText) {
                violations += "REUSE.toml is missing package metadata"
            }
            if (Regex("""SPDX-License-Identifier\s*=\s*"MIT"""").containsMatchIn(reuseText) &&
                Regex("""path\s*=\s*\[[^\]]*(LICENSE_POLICY|AGENTS|agent\.md|build-logic)""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(reuseText)
            ) {
                violations += "REUSE.toml maps first-party paths to MIT"
            }
        }
        val thirdParty = loadThirdPartyPaths(root)
        walk(root, root, thirdParty, violations)
        return violations
    }

    private fun walk(
        root: Path,
        current: Path,
        thirdParty: Set<String>,
        violations: MutableList<String>,
    ) {
        current.forEachDirectoryEntry { child ->
            val rel = child.relativeTo(root).toString().replace('\\', '/')
            if (child.isDirectory()) {
                if (child.name !in SKIP_DIRS && !rel.startsWith(".")) {
                    walk(root, child, thirdParty, violations)
                }
            } else if (child.isRegularFile() && rel in PINNED_LEGAL_ASSETS) {
                // This is an unmodified upstream license document, not first-party HTML.
                // The exact path AND bytes are locked; no directory or extension is exempted.
                if (sha256Hex(child.readBytes()) != PINNED_LEGAL_ASSETS.getValue(rel)) {
                    violations += "$rel: pinned third-party legal text hash mismatch"
                }
                val sidecar = child.resolveSibling(child.fileName.toString() + ".license")
                if (!sidecar.isRegularFile() || spdxLicenseExpression(sidecar.readText(Charsets.UTF_8)) != "MIT") {
                    violations += "$rel: missing original third-party MIT license sidecar"
                }
            } else if (child.isRegularFile() && shouldScan(child, rel, thirdParty)) {
                val header = child.readText(Charsets.UTF_8).lineSequence().take(12).joinToString("\n")
                if ("SPDX-FileCopyrightText:" !in header) {
                    violations += "$rel: missing SPDX-FileCopyrightText"
                }
                val expression = spdxLicenseExpression(header)
                when (expression) {
                    "AGPL-3.0-only" -> Unit
                    "AGPL-3.0-or-later" ->
                        violations += "$rel: AGPL-3.0-or-later is forbidden"
                    "MIT" ->
                        violations += "$rel: first-party SPDX must not be MIT"
                    null -> violations += "$rel: missing SPDX-License-Identifier: AGPL-3.0-only"
                    else ->
                        violations += "$rel: first-party SPDX must be exactly AGPL-3.0-only, found $expression"
                }
            }
        }
    }

    private fun shouldScan(file: Path, rel: String, thirdParty: Set<String>): Boolean {
        if (rel == "LICENSE" || rel.startsWith("LICENSES/")) return false
        if (rel == "local.properties") return false
        if (rel.endsWith(".jar")) return false
        if (rel in THIRD_PARTY_WRAPPER) return false
        if (rel == "vendor" || rel.startsWith("vendor/")) return false
        if (thirdParty.any { rel == it || rel.startsWith("$it/") }) return false
        return file.extension.lowercase() in FIRST_PARTY_EXTENSIONS || file.name in FIRST_PARTY_NAMES
    }

    private fun loadThirdPartyPaths(root: Path): Set<String> {
        val reuse = root.resolve("REUSE.toml")
        if (!reuse.isRegularFile()) return emptySet()
        val text = reuse.readText(Charsets.UTF_8)
        val paths = mutableSetOf<String>()
        val blocks = text.split("[[annotations]]").drop(1)
        for (block in blocks) {
            if (!block.contains("Apache-2.0") && !block.contains("MIT") && !block.contains("BSD")) continue
            if (block.contains("AGPL-3.0-only")) continue
            Regex(""""([^"]+)"""").findAll(block).forEach { match ->
                val value = match.groupValues[1]
                if (isExemptThirdPartyPath(value)) {
                    paths += value
                }
            }
        }
        return paths
    }

    private fun isExemptThirdPartyPath(value: String): Boolean {
        val rel = value.replace('\\', '/').trimEnd('/')
        if (rel in THIRD_PARTY_WRAPPER) return true
        if (rel == "vendor" || rel.startsWith("vendor/")) return true
        return false
    }

    internal fun spdxLicenseExpression(header: String): String? {
        val line = header.lineSequence().firstOrNull { it.contains("SPDX-License-Identifier:") } ?: return null
        return line.substringAfter("SPDX-License-Identifier:")
            .substringBefore("-->")
            .trim()
            .trimStart('/', '*')
            .trim()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private data class MarkerFile(val path: String, val marker: String)

    companion object {
        internal val PINNED_LEGAL_ASSETS = mapOf(
            "app-android/src/main/assets/licenses/maven/org.bouncycastle__bcprov-jdk18on__1.79/LICENSE.html" to
                "edbbb10380b1271998b867a2e36b1cbee226e03d438726e1a91f80c5dde11849",
        )
        const val EXPECTED_AGPL_SHA256 =
            "0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0"
        const val SOURCE_URL = "https://github.com/hedanbaomi/mobile-agent-runtime"
        const val ABOUT_PATH =
            "feature/settings/src/main/kotlin/runtime/mobileagent/feature/settings/AboutScreen.kt"
        const val POLICY_MARKER = "LICENSE_POLICY_AGPL_ONLY"
        private val REQUIRED_MARKERS = listOf(
            MarkerFile("LICENSE_POLICY.md", POLICY_MARKER),
            MarkerFile("AGENTS.md", POLICY_MARKER),
            MarkerFile("agent.md", POLICY_MARKER),
        )
        private val SKIP_DIRS = setOf(
            ".git",
            ".codegraph",
            ".gradle",
            "build",
            "node_modules",
            ".private",
            ".idea",
            ".kotlin",
        )
        private val FIRST_PARTY_EXTENSIONS = setOf(
            "kt", "kts", "java", "md", "py", "ts", "js", "css", "html", "xml",
            "yml", "yaml", "toml", "properties", "sql", "gradle",
        )
        private val FIRST_PARTY_NAMES = setOf("CODEOWNERS", ".gitignore", ".gitattributes")
        private val THIRD_PARTY_WRAPPER = setOf(
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
        )
    }
}
// REUSE-IgnoreEnd
