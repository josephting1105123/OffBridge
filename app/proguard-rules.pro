# R8 / ProGuard rules for the release build (isMinifyEnabled + isShrinkResources).
#
# Risk profile for THIS app: all wire/persistence JSON goes through org.json with string-literal
# keys (NO Gson/Moshi/kotlinx-serialization reflection), so R8 field/method renaming cannot corrupt
# (de)serialization. Room (KSP) and the AndroidX/Compose/CameraX/ML-Kit/Tink libraries ship their
# own consumer ProGuard rules, so most keeps are handled transitively. The rules below are the
# defensive minimum on top of getDefaultProguardFile("proguard-android-optimize.txt").

# --- Crash deobfuscation -----------------------------------------------------------------------
# Keep source/line info so release stack traces remain mappable (mapping.txt still required to
# de-obfuscate type names; this keeps the line numbers meaningful).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Generic signatures + annotations — needed by anything doing runtime generic/annotation lookup.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# --- Kotlin / Android standard idioms ----------------------------------------------------------
# Enums accessed via values()/valueOf() (reflection under the hood).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Parcelable CREATOR fields (Parcelize / hand-written Parcelables).
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
# JNI / native bridges (none today, but cheap and standard).
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- androidx.security.crypto (Tink) — HIGHEST blast radius ------------------------------------
# EncryptedSharedPreferences backs SecurityVault (paired-node AES keys, hotspot creds). A bad strip
# here would brick the keyset (re-pair-everything). Tink ships consumer rules, but we pin defensively.
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
# Tink loads protobuf-lite via reflection.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.protobuf.**

# --- ZXing (off-grid QR generation AND scanning) -----------------------------------------------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --- Room entities/DAOs ------------------------------------------------------------------------
# Room's generated implementations reference these; the Room consumer rules normally cover them, but
# entities carry no other references so we keep them (and their no-arg/field shape) defensively.
-keep @androidx.room.Entity class * { *; }
-keep class com.EntropicSoftwareLab.ClipboardShare.data.db.entity.** { *; }

# Coroutines ships its own consumer rules; suppress the known benign warnings just in case.
-dontwarn kotlinx.coroutines.**
