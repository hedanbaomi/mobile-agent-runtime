# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

# JNI exports address these exact class and method names.
-keep class runtime.mobileagent.vector.NativeUsearchIndex { native <methods>; }
-keep class runtime.mobileagent.python.PythonNative { native <methods>; }

# These entrypoints are launched by name through Android app_process.
-keep class runtime.mobileagent.bridge.AdbHelperMain { public static void main(java.lang.String[]); }
-keep class runtime.mobileagent.resident.ResidentAdbMain { public static void main(java.lang.String[]); }

# ONNX Runtime's Java API is called back by its native library. Preserve its
# boundary while allowing the rest of the dependency graph to shrink.
-keep class ai.onnxruntime.** { *; }

# kotlinx.serialization supplies consumer rules for generated serializers.
# Retain annotation metadata used by the generated/runtime serializer lookup.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature
