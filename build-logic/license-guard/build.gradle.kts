// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    `kotlin-dsl`
}

group = "runtime.mobileagent.buildlogic"

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
