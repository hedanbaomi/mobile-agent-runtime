// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.license

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

class LicenseGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val licenseGuard = project.tasks.register("licenseGuard", LicenseGuardTask::class.java)
        licenseGuard.configure {
            group = "verification"
            description = "Fail if first-party license, SPDX, or About source drift from AGPL-3.0-only."
            repoRoot.set(project.rootDir)
        }
        val reverse = project.tasks.register("licenseGuardReverse", LicenseGuardReverseTask::class.java)
        reverse.configure {
            group = "verification"
            description = "Run reverse license fixtures in a temporary directory."
            repoRoot.set(project.rootDir)
        }
        project.pluginManager.withPlugin("lifecycle-base") {
            project.tasks.named("check").configure {
                dependsOn("licenseGuard", "licenseGuardReverse")
            }
        }
    }
}

@UntrackedTask(because = "Walks the whole repository including untracked first-party files")
abstract class LicenseGuardTask : DefaultTask() {
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val violations = LicenseScanner().scan(repoRoot.get().asFile.toPath())
        if (violations.isNotEmpty()) {
            throw GradleException("licenseGuard failed:\n" + violations.joinToString("\n"))
        }
    }
}

@UntrackedTask(because = "Creates throwaway fixtures outside the task output model")
abstract class LicenseGuardReverseTask : DefaultTask() {
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        LicenseGuardReverseTests.run(repoRoot.get().asFile.toPath())
    }
}
