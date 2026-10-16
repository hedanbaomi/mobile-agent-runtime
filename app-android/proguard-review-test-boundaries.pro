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

# ShizukuVersionProjectionTest drives typed RPC through a name-dispatching Proxy
# and probes private parsers/fields. TraceReferences cannot see these string-based
# edges. Preserve only this test ABI in Review; Release still optimizes it normally.
-keepclassmembers interface runtime.mobileagent.shizuku.DeviceServiceBridge {
    *** dispatch*(...);
}
-keep class runtime.mobileagent.shizuku.ShizukuTokenWorkspaceBackend {
    <init>(...);
    *** parseStat(...);
    *** parsePatchPayload(...);
    *** dispatchFailure(...);
    *** payload(...);
}
-keepclassmembers class runtime.mobileagent.shizuku.ShizukuWorkspaceBackendAdapter {
    *** parseOpaqueVersion(...);
    *** dispatchJson(...);
}
-keepclassmembers class runtime.mobileagent.shizuku.ShizukuWorkspaceBackendAdapter$ParsedVersion {
    java.lang.String opaqueVersion;
}
-keepclassmembers class runtime.mobileagent.skills.tooling.WorkspaceFileStat {
    java.lang.Long version;
}
-keepclassmembers class runtime.mobileagent.skills.tooling.WorkspaceMutation {
    java.lang.Long version;
}
