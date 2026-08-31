// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "mobileAgentRuntime"

include(":app-android")
include(":shared:domain")
include(":shared:serialization")
include(":shared:provider-api")
include(":shared:agent-runtime")
include(":shared:knowledge-api")
include(":shared:skills-api")
include(":shared:announcements")
include(":shared:bridge-protocol")
include(":desktop:bridge")
include(":data:sqlite")
include(":runtime:embedding-onnx")
include(":runtime:vector-usearch")
include(":runtime:python-android")
include(":platform:android:storage")
include(":platform:android:security")
include(":platform:android:background")
include(":platform:android:ipc")
include(":feature:chat")
include(":feature:agents")
include(":feature:providers")
include(":feature:knowledge")
include(":feature:skills")
include(":feature:announcements")
include(":feature:settings")
