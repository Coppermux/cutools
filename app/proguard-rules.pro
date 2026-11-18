# =============================================================================
# CuTools V1.0.0 — ProGuard / R8 rules (release build)
# -----------------------------------------------------------------------------
# Purposes:
#   1. Protect the CDP JSON-RPC wire models (Moshi reflection adapters) and
#      the protocol schema models so shrinking/obfuscation never breaks
#      deserialization of browser messages.
#   2. Protect kotlinx.serialization artifacts that are (or may be) produced
#      by annotations; the debug of kotlinx.serialization generators is kept
#      safe if it is ever adopted.
#   3. Protect the tool/UI StateFlow models published to Compose so snapshot
#      equality and property access survive shrinking.
#   4. Preserve diagnostics (class/method names for stack traces) and enum
#      semantics used by capability-state maps.
# =============================================================================

# -----------------------------------------------------------------------------
# Kotlin metadata + reflection safety
# -----------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

-keep class kotlin.Metadata { *; }
-keepparameternames
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -----------------------------------------------------------------------------
# CDP JSON-RPC wire models (Moshi kotlin reflection adapters)
# -----------------------------------------------------------------------------
# The CDP wire envelope — Command / Response / Event / ProtocolError — is
# (de)serialized through Moshi's KotlinJsonAdapterFactory, which obtains
# constructors/fields through reflection. Stripping access here silently
# corrupts outbound commands or inbound replies.
-keep class com.coppermux.cutools.core.model.** { *; }
-keep class com.coppermux.cutools.core.error.** { *; }

# Protocol schema models (CdpSchemaModels) are also (de)serialized via Moshi.
-keep class com.coppermux.cutools.core.protocol.schema.** { *; }

# Any class annotated with Moshi's @JsonClass (codegen) is kept reflectively.
-keep @com.squareup.moshi.JsonClass class * { *; }

# Moshi ships consumer rules in its AAR; keep the reflectively loaded parts.
-keepclasseswithmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-dontwarn com.squareup.moshi.kotlin.reflect.**

# -----------------------------------------------------------------------------
# kotlinx.serialization (adopt-if-needed; core protocol uses Moshi today)
# -----------------------------------------------------------------------------
# The kotlinx.serialization runtime is not currently a dependency; this rule
# keeps its generated serializers safe if it is ever adopted. A package
# wildcard on an absent base package is a no-op match and cannot break R8.
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# -----------------------------------------------------------------------------
# Tool layer / UI StateFlow models
# -----------------------------------------------------------------------------
# The tool/application/profiling model classes are published through
# StateFlows and read by Compose; keep them so recomposition equality and
# property access survive shrinking.
-keep class com.coppermux.cutools.tools.**.models.** { <fields>; <init>(...); }
-keep class com.coppermux.cutools.ui.**.models.** { <fields>; <init>(...); }

# -----------------------------------------------------------------------------
# Entry points kicked off from resources/reflection
# -----------------------------------------------------------------------------
-keep class com.coppermux.cutools.ui.shell.ShellActivity { <init>(...); }
-keep,allowshrinking class * extends androidx.appcompat.app.AppCompatActivity { <init>(...); }

# -----------------------------------------------------------------------------
# SEC-02: Production Logging Protection
# -----------------------------------------------------------------------------
# Strip development-only debug/verbose Logcat calls from release builds while
# preserving warning (Log.w) and error (Log.e) diagnostic telemetry.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Strip development logTrace helper calls in release builds so stdout logging is suppressed.
-assumenosideeffects class * {
    private static void logTrace(...);
}

# End of file