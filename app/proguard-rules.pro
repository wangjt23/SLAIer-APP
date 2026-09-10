# ---------------------------------------------------------------------------
# R8 / ProGuard rules for SLAI Campus
# ---------------------------------------------------------------------------

# Keep line numbers for readable crash reports, but hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin Serialization ---------------------------------------------------
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.slai.campus.**$$serializer { *; }
-keepclassmembers class com.slai.campus.** {
    *** Companion;
}
-keepclasseswithmembers class com.slai.campus.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- kotlinx.serialization runtime -----------------------------------------
-dontwarn kotlinx.serialization.**

# --- OkHttp / Okio ----------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- jsoup ------------------------------------------------------------------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- Room -------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Hilt / Dagger ----------------------------------------------------------
-keep,allowobfuscation @interface dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# --- WorkManager ------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Compose ----------------------------------------------------------------
-dontwarn androidx.compose.**

# --- WebView JavaScript injection targets -----------------------------------
# The WebView extractor calls methods by name from injected JS strings; keep the
# (small) surface referenced reflectively.
-keepclassmembers class com.slai.campus.core.web.** { *; }
