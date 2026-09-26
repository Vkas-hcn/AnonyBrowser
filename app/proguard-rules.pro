# Google Mobile Ads (AdMob)
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.ads.** { *; }
-dontwarn com.google.android.gms.ads.**

# JavaScript Interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# OkHttp - Ignore optional platform implementations
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# OkHttp platform used only on JVM and when Conscrypt dependency is available
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.InputMerger
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.work.impl.model.** { *; }

# AndroidX Startup
-keep class * extends androidx.startup.Initializer
-keepclassmembers class * extends androidx.startup.Initializer {
    <init>();
}
-keepnames class androidx.startup.AppInitializer

# LibBox VPN SDK - Prevent ANR from license check
-keep class io.nekohasekai.libbox.** { *; }
-keepclassmembers class com.anony.bro.wser.data.vpn.VpnServer { *; }
-keepnames class com.anony.bro.wser.data.vpn.VpnServer