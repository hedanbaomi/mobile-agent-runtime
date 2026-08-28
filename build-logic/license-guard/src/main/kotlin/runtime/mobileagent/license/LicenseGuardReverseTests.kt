// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.license

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

// REUSE-IgnoreStart
object LicenseGuardReverseTests {
    private val mitSpdx = "SPDX-License-Identifier: " + "MIT"

    fun run(repoRoot: Path) {
        val licenseBytes = repoRoot.resolve("LICENSE").readBytes()
        val tmp = Files.createTempDirectory("mar-license-reverse-")
        try {
            assertFails("first-party SPDX mutated to MIT") {
                val root = fixture(tmp.resolve("mit-spdx"), licenseBytes)
                root.resolve("shared/example.kt").writeText(
                    """
                    // SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
                    // $mitSpdx
                    class Example
                    """.trimIndent(),
                )
                LicenseScanner().scan(root)
            }
            assertFails("LICENSE replaced") {
                val root = fixture(tmp.resolve("license-replaced"), licenseBytes)
                root.resolve("LICENSE").writeText("MIT replacement for reverse test\n")
                LicenseScanner().scan(root)
            }
            assertFails("SPDX header deleted") {
                val root = fixture(tmp.resolve("header-deleted"), licenseBytes)
                root.resolve("shared/example.kt").writeText("class Example\n")
                LicenseScanner().scan(root)
            }
            assertFails("license metadata changed") {
                val root = fixture(tmp.resolve("metadata"), licenseBytes)
                root.resolve("LICENSE_POLICY.md").writeText("# policy without marker\n")
                LicenseScanner().scan(root)
            }
            val thirdPartyRoot = fixture(tmp.resolve("third-party-mit"), licenseBytes)
            val notice = thirdPartyRoot.resolve("vendor/example-mit/NOTICE")
            notice.parent.createDirectories()
            notice.writeText(
                """
                // SPDX-FileCopyrightText: Example Authors
                // $mitSpdx
                Third-party MIT fixture.
                """.trimIndent(),
            )
            val reuse = thirdPartyRoot.resolve("REUSE.toml")
            reuse.writeText(
                reuse.readText(Charsets.UTF_8) + """

                [[annotations]]
                path = ["vendor/example-mit"]
                precedence = "override"
                SPDX-FileCopyrightText = "Example Authors"
                SPDX-License-Identifier = "${'"' + "MIT" + '"'}"
                """.trimIndent(),
            )
            val thirdPartyViolations = LicenseScanner().scan(thirdPartyRoot)
            check(thirdPartyViolations.isEmpty()) {
                "legal third-party MIT should pass, got $thirdPartyViolations"
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun fixture(root: Path, licenseBytes: ByteArray): Path {
        root.createDirectories()
        Files.write(root.resolve("LICENSE"), licenseBytes)
        root.resolve("LICENSES").createDirectories()
        Files.write(root.resolve("LICENSES/AGPL-3.0-only.txt"), licenseBytes)
        val header = """
            <!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
            <!-- SPDX-License-Identifier: AGPL-3.0-only -->
            <!-- LICENSE_POLICY_AGPL_ONLY: DO NOT REMOVE -->
        """.trimIndent()
        root.resolve("LICENSE_POLICY.md").writeText("$header\n# policy\n")
        root.resolve("AGENTS.md").writeText("$header\n# agents\n")
        root.resolve("agent.md").writeText("$header\n# agent\n")
        root.resolve("REUSE.toml").writeText(
            """
            # SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
            # SPDX-License-Identifier: AGPL-3.0-only
            version = 1
            SPDX-PackageName = "mobileAgentRuntime"
            SPDX-PackageDownloadLocation = "${LicenseScanner.SOURCE_URL}"
            """.trimIndent(),
        )
        val about = root.resolve(LicenseScanner.ABOUT_PATH)
        about.parent.createDirectories()
        about.writeText(
            """
            // SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
            // SPDX-License-Identifier: AGPL-3.0-only
            package runtime.mobileagent.feature.settings
            // AGPL-3.0-only ${LicenseScanner.SOURCE_URL}
            """.trimIndent(),
        )
        val example = root.resolve("shared/example.kt")
        example.parent.createDirectories()
        example.writeText(
            """
            // SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
            // SPDX-License-Identifier: AGPL-3.0-only
            class Example
            """.trimIndent(),
        )
        return root
    }

    private fun assertFails(label: String, block: () -> List<String>) {
        val violations = block()
        check(violations.isNotEmpty()) { "expected $label to fail licenseGuard" }
    }
}
// REUSE-IgnoreEnd
