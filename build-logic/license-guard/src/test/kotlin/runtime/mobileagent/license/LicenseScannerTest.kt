// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.license

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

// REUSE-IgnoreStart
class LicenseScannerTest {
    private val mitSpdx = "SPDX-License-Identifier: " + "MIT"

    @Test
    fun legitimateThirdPartyMitIsNotTreatedAsFirstParty(@TempDir tmp: Path) {
        val license = "AGPL-BODY".repeat(40).toByteArray()
        val scanner = LicenseScanner(expectedLicenseSha256 = sha(license))
        writeMinimalProject(tmp, license)
        tmp.resolve("vendor/lib/NOTICE").apply {
            parent.createDirectories()
            writeText(
                """
                SPDX-FileCopyrightText: Vendor
                $mitSpdx
                """.trimIndent(),
            )
        }
        tmp.resolve("REUSE.toml").writeText(
            tmp.resolve("REUSE.toml").toFile().readText() + """

            [[annotations]]
            path = ["vendor/lib"]
            precedence = "override"
            SPDX-FileCopyrightText = "Vendor"
            SPDX-License-Identifier = "${'"' + "MIT" + '"'}"
            """.trimIndent(),
        )
        val violations = scanner.scan(tmp)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun mitSpdxOnFirstPartyFails(@TempDir tmp: Path) {
        val license = "AGPL-BODY".repeat(40).toByteArray()
        val scanner = LicenseScanner(expectedLicenseSha256 = sha(license))
        writeMinimalProject(tmp, license)
        tmp.resolve("shared/Bad.kt").writeText(
            """
            // SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
            // $mitSpdx
            class Bad
            """.trimIndent(),
        )
        val violations = scanner.scan(tmp)
        assertTrue(violations.any { it.contains("must not be MIT") }, violations.joinToString("\n"))
    }

    private fun writeMinimalProject(root: Path, license: ByteArray) {
        Files.write(root.resolve("LICENSE"), license)
        root.resolve("LICENSES").createDirectories()
        Files.write(root.resolve("LICENSES/AGPL-3.0-only.txt"), license)
        val header = """
            <!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
            <!-- SPDX-License-Identifier: AGPL-3.0-only -->
            <!-- LICENSE_POLICY_AGPL_ONLY: DO NOT REMOVE -->
        """.trimIndent()
        root.resolve("LICENSE_POLICY.md").writeText(header)
        root.resolve("AGENTS.md").writeText(header)
        root.resolve("agent.md").writeText(header)
        root.resolve("REUSE.toml").writeText(
            """
            # SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
            # SPDX-License-Identifier: AGPL-3.0-only
            version = 1
            SPDX-PackageDownloadLocation = "${LicenseScanner.SOURCE_URL}"
            """.trimIndent(),
        )
        val about = root.resolve(LicenseScanner.ABOUT_PATH)
        about.parent.createDirectories()
        about.writeText(
            """
            // SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
            // SPDX-License-Identifier: AGPL-3.0-only
            // AGPL-3.0-only ${LicenseScanner.SOURCE_URL}
            """.trimIndent(),
        )
        val example = root.resolve("shared/Ok.kt")
        example.parent.createDirectories()
        example.writeText(
            """
            // SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
            // SPDX-License-Identifier: AGPL-3.0-only
            class Ok
            """.trimIndent(),
        )
    }

    private fun sha(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
// REUSE-IgnoreEnd
