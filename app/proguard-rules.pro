# QuickShare release rules.

# ---------------------------------------------------------------------------
# Gson + reflection
# Every wire model carries @SerializedName on every field, so R8 renaming the
# fields is harmless -- but the *classes* must survive, because Gson looks them
# up by name at runtime. Without these rules the release build crashes with
# ClassNotFoundException while decoding the very first handshake frame.
# ---------------------------------------------------------------------------
-keep class com.quickshare.core.protocol.** { *; }
-keep class com.quickshare.core.net.Peer { *; }
-keep class com.quickshare.core.transfer.TransferRecord { *; }
-keep class com.quickshare.core.data.Settings { *; }
-keepnames class com.quickshare.core.protocol.** { *; }

# Keep the annotations themselves so field names survive obfuscation.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# ---------------------------------------------------------------------------
# Kotlin / coroutines
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlin.Unit
-dontwarn kotlin.jvm.internal.**

# ---------------------------------------------------------------------------
# AndroidX / Material / Compose
# ---------------------------------------------------------------------------
-dontwarn androidx.**
-keep class androidx.appcompat.widget.** { *; }
-dontwarn org.jetbrains.annotations.**

# Compose resolves @Composable functions reflectively in some tooling paths, and the
# runtime needs its own classes intact. These are the rules the Compose team ships.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }
-dontwarn androidx.compose.**

# Strip verbose logging from release builds (Logs.kt already guards on BuildConfig.DEBUG;
# these rules additionally remove the calls that survive the guard).
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
