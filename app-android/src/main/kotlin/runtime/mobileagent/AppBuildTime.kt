// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context

internal fun Context.packagedBuildTimeUtc(): String = BuildConfig.BUILD_TIME_UTC.ifEmpty {
    assets.open("mar-build-time-utc.txt").bufferedReader(Charsets.US_ASCII).use { it.readText() }
}
