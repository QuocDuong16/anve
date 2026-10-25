# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in <sdk>/tools/proguard/proguard-android.txt

# Moshi reflection (ADR 0003) inspects DTO metadata via kotlin-reflect; keep
# the data classes and their constructors intact.
-keep class com.anve.app.data.api.** { *; }
-keep class com.anve.app.data.auth.StoredSession { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# OkHttp / Retrofit platform warnings.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**
