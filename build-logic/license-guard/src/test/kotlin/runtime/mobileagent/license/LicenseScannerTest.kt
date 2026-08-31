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

    @Test
    fun dualLicenseExpressionIsRejected(@TempDir tmp: Path) {
        val license = "AGPL-BODY".repeat(40).toByteArray()
        val scanner = LicenseScanner(expectedLicenseSha256 = sha(license))
        writeMinimalProject(tmp, license)
        tmp.resolve("shared/Dual.kt").writeText(
            """
            // SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
            // SPDX-License-Identifier: AGPL-3.0-only OR Apache-2.0
            class Dual
            """.trimIndent(),
        )
        val violations = scanner.scan(tmp)
        assertTrue(violations.any { it.contains("exactly AGPL-3.0-only") }, violations.joinToString("\n"))
    }

    @Test
    fun apacheAnnotationCannotExemptFirstPartyTree(@TempDir tmp: Path) {
        val license = "AGPL-BODY".repeat(40).toByteArray()
        val scanner = LicenseScanner(expectedLicenseSha256 = sha(license))
        writeMinimalProject(tmp, license)
        tmp.resolve("REUSE.toml").writeText(
            tmp.resolve("REUSE.toml").toFile().readText() + """

            [[annotations]]
            path = ["shared/domain", "shared/domain/**"]
            precedence = "override"
            SPDX-FileCopyrightText = "Vendor"
            SPDX-License-Identifier = "${'"' + "Apache-2.0" + '"'}"
            """.trimIndent(),
        )
        val unmarked = tmp.resolve("shared/domain/Skip.kt")
        unmarked.parent.createDirectories()
        unmarked.writeText("class Skip\n")
        val violations = scanner.scan(tmp)
        assertTrue(violations.any { it.contains("shared/domain/Skip.kt") }, violations.joinToString("\n"))
    }

    @Test
    fun generatedBuildAndCacheTreesAreIgnoredButSourceOmissionStillFails(@TempDir tmp: Path) {
        val license = "AGPL-BODY".repeat(40).toByteArray()
        val scanner = LicenseScanner(expectedLicenseSha256 = sha(license))
        writeMinimalProject(tmp, license)

        val generated = tmp.resolve("build/generated/Missing.kt")
        generated.parent.createDirectories()
        generated.writeText("class Generated\n")

        val nestedGenerated = tmp.resolve("module/build/generated/NestedMissing.kt")
        nestedGenerated.parent.createDirectories()
        nestedGenerated.writeText("class NestedGenerated\n")

        val kotlinCheck = tmp.resolve(".tmp-kotlin-check/recheck/Missing.kt")
        kotlinCheck.parent.createDirectories()
        kotlinCheck.writeText("class KotlinCheckGenerated\n")

        val pythonCache = tmp.resolve("tools/__pycache__/generated.py")
        pythonCache.parent.createDirectories()
        pythonCache.writeText("generated = True\n")

        val source = tmp.resolve("shared/Missing.kt")
        source.writeText("class Missing\n")

        val violations = scanner.scan(tmp)
        assertTrue(
            violations.none { it.contains("build/generated/Missing.kt") },
            violations.joinToString("\n"),
        )
        assertTrue(
            violations.none { it.contains("module/build/generated/NestedMissing.kt") },
            violations.joinToString("\n"),
        )
        assertTrue(
            violations.none { it.contains(".tmp-kotlin-check/recheck/Missing.kt") },
            violations.joinToString("\n"),
        )
        assertTrue(
            violations.none { it.contains("tools/__pycache__/generated.py") },
            violations.joinToString("\n"),
        )
        assertTrue(
            violations.any { it.contains("shared/Missing.kt") },
            violations.joinToString("\n"),
        )
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
