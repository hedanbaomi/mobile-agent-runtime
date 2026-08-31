// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    `kotlin-dsl`
}

group = "runtime.mobileagent.buildlogic"

// The main build's `allprojects` block does not cross the composite-build
// boundary.  Keep the build-logic project locked independently so plugin
// implementation/test dependencies cannot drift outside the root lock gate.
dependencyLocking {
    lockAllConfigurations()
}

gradlePlugin {
    plugins {
        create("licenseGuard") {
            id = "runtime.mobileagent.license-guard"
            implementationClass = "runtime.mobileagent.license.LicenseGuardPlugin"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

fun verifyBuildLogicLock(lock: java.io.File) {
    check(lock.isFile) { "Missing included-build dependency lockfile: ${lock.path}" }
    check("This is a Gradle generated file for dependency locking." in lock.readText()) {
        "Invalid included-build dependency lockfile: ${lock.path}"
    }
}

tasks.register("verifyDependencyLock") {
    group = "verification"
    description = "Verify the build-logic included-build lockfiles are present and generated."
    doLast {
        verifyBuildLogicLock(rootProject.file("settings-gradle.lockfile"))
        verifyBuildLogicLock(project.file("gradle.lockfile"))
    }
}

tasks.register("verifyDependencyVerification") {
    group = "verification"
    description = "Verify strict SHA-256 dependency metadata for the build-logic included build."
    doLast {
        val metadata = rootProject.file("gradle/verification-metadata.xml")
        check(metadata.isFile) {
            "Missing included-build dependency verification metadata: ${metadata.path}; " +
                "generate it with --write-verification-metadata sha256"
        }
        val text = metadata.readText()
        check("<verification-metadata" in text && "<configuration>" in text) {
            "Invalid included-build dependency verification metadata"
        }
        check("<verify-metadata>true</verify-metadata>" in text) {
            "Included-build dependency verification must keep verify-metadata=true"
        }
        check(Regex("""<sha256\s+value="[0-9a-fA-F]{64}"""").containsMatchIn(text)) {
            "Included-build dependency verification metadata has no SHA-256 entries"
        }
        check(!Regex("""<trusted-(?:key|artifact)\b[^>]*\*[^>]*/?>""").containsMatchIn(text)) {
            "Included-build dependency verification metadata must not contain wildcard trust"
        }
    }
}
