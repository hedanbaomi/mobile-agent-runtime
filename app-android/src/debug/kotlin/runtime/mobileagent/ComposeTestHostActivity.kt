// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.activity.ComponentActivity

/** Empty internal debug/review activity for isolated Compose instrumentation; absent from release. */
class ComposeTestHostActivity : ComponentActivity()
