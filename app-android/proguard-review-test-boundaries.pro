# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

# Review instrumentation ABI is inferred from compiled tests and test runtime
# libraries by inferReviewInstrumentationKeepRules. Keep manual rules here only
# for reflection or inherited dispatch that direct bytecode references miss.

# Compose's unoptimized test Element inherits getKey from this default interface.
# TraceReferences sees onInfiniteOperation and DefaultImpls bridges, but misses
# the inherited default getter. Preserve exactly that contract in the Review host.
-keep interface kotlin.coroutines.CoroutineContext$Element {
    kotlin.coroutines.CoroutineContext$Key getKey();
}
-keep interface androidx.compose.ui.platform.InfiniteAnimationPolicy {
    kotlin.coroutines.CoroutineContext$Key getKey();
}
