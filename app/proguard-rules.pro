# ===========================================================================
# Anti-Decompilation & Code Obfuscation Rules (Trusty Yellow Cab)
# ===========================================================================

# 1. Package Flattening & Name Scrambling
# Flattens all classes into a single obfuscated package so directory/package structure is invisible
-repackageclasses 'com.trustyyellowcab.driver.internal'
-allowaccessmodification
-overloadaggressively

# 2. Strip Source File Names, Line Numbers, and Debugging Metadata
# Decompilers (e.g. JADX, Apktool) will not see original .kt filenames or line tables
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable

# 3. Strip Logging Calls to Prevent Information Leaks
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# 4. Android Core Components (Required for OS execution)
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# 5. Room Database & SQLite Persistence
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public abstract <methods>;
}
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.**

# 6. Moshi JSON Serialization
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class *JsonAdapter { *; }
-dontwarn com.squareup.moshi.**

# 7. Retrofit & OkHttp Networking
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# 8. Firebase & Google Services
-keepattributes *Annotation*, EnclosingMethod
-keepclassmembers class com.trustyyellowcab.driver.network.FirestoreDriver {
    <fields>;
    <init>(...);
}
-keepclassmembers class com.trustyyellowcab.driver.network.FirestoreTrip {
    <fields>;
    <init>(...);
}
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# 9. Jetpack Compose UI
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

