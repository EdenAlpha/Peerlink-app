# ══════════════════════════════════════════════════════════
# PEERLINK PROGUARD RULES — Maximum Obfuscation
# ══════════════════════════════════════════════════════════

# ── AGGRESSIVE OBFUSCATION ──
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-flattenpackagehierarchy ''
-mergeinterfacesaggressively

# Rename everything to confusing single-letter names
-obfuscationdictionary proguard-dict.txt
-classobfuscationdictionary proguard-dict.txt
-packageobfuscationdictionary proguard-dict.txt

# Remove all source file / line number info from stack traces
-renamesourcefileattribute ''
-keepattributes SourceFile,LineNumberTable

# Strip ALL Log calls in release (no one can read your logs)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Strip Kotlin debug assertions
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}


# ══════════════════════════════════════════════════════════
# PRIME SERVER — app_process entry points
#
# SHIZUKU DOES THIS (proguard-rules.pro):
#
#   # Entrance of Shizuku service
#   -keep class rikka.shizuku.server.ShizukuService {
#       public static void main(java.lang.String[]);
#   }
#
#   # Entrance of user service starter
#   -keep class moe.shizuku.starter.ServiceStarter {
#       public static void main(java.lang.String[]);
#   }
#
# WHY THIS IS MANDATORY:
#   libpeerlinkstarter.so launches PrimeServer with:
#     app_process ... com.peerlink.app.godmode.PrimeServerMain
#   app_process finds the class by its FULL ORIGINAL NAME from the dex.
#   Your -repackageclasses '' and -flattenpackagehierarchy '' move ALL
#   classes to the default package with single-letter names in release builds.
#   PrimeServerMain becomes "a" or "b" — app_process looks for
#   "com.peerlink.app.godmode.PrimeServerMain", finds nothing, crashes.
#
#   Additionally, main(String[]) is never called from within your Kotlin app —
#   only by app_process at runtime. Without an explicit keep rule,
#   -assumenosideeffects and dead code elimination strip it entirely.
#
#   PrimeServer.main() is the fallback entry point (PrimeServer::class.java.name
#   is referenced in older code). Keep it too.
# ══════════════════════════════════════════════════════════

# Entry point called by app_process to start PrimeServer — MUST keep class name
# and main() signature exactly. Mirrors Shizuku's ServiceStarter keep rule.
-keep class com.peerlink.app.godmode.PrimeServerMain {
    public static void main(java.lang.String[]);
}

# Fallback entry point on PrimeServer itself — also called by app_process
# in some code paths. Keep the full class and main() method.
-keep class com.peerlink.app.godmode.PrimeServer {
    public static void main(java.lang.String[]);
}


# ══════════════════════════════════════════════════════════
# KEEP RULES — Only what Android MUST find by name
# ══════════════════════════════════════════════════════════

# ── YOUR ACTIVITY (manifest references it by full name) ──
-keep public class com.peerlink.app.ui.MainActivity

# ── YOUR VPN SERVICE (manifest references it by full name) ──
-keep public class com.peerlink.app.service.PeerLinkVpnService

# ── NSD CALLBACK (Android calls this interface by reflection) ──
-keep class com.peerlink.app.discovery.NsdDiscovery$NsdCallback { *; }


# ══════════════════════════════════════════════════════════
# ANDROID / SYSTEM ESSENTIALS
# ══════════════════════════════════════════════════════════

# Keep all Android components declared in manifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# Keep Parcelable creators
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep NSD system classes
-keep class android.net.nsd.** { *; }


# ══════════════════════════════════════════════════════════
# JETPACK COMPOSE
# ══════════════════════════════════════════════════════════

# Keep Compose internals (uses reflection)
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Material3 + Icons
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.material.icons.** { *; }

# Keep Composable methods
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep Kotlin metadata needed by Compose compiler
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keep class kotlin.Metadata { *; }


# ══════════════════════════════════════════════════════════
# QR / BARCODE SCANNER
# ══════════════════════════════════════════════════════════

-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.journeyapps.**
-dontwarn com.google.zxing.**


# ══════════════════════════════════════════════════════════
# KOTLIN COROUTINES
# ══════════════════════════════════════════════════════════

-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}


# ══════════════════════════════════════════════════════════
# NETWORKING (Java reflection uses these)
# ══════════════════════════════════════════════════════════

-keep class java.net.NetworkInterface { *; }
-keep class java.net.InetAddress { *; }
-keep class java.net.Inet4Address { *; }
-keep class java.net.Inet6Address { *; }
