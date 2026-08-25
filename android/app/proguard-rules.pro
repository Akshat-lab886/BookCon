# BookCon Android — R8 rules

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.bookcon.app.**$$serializer { *; }
-keepclassmembers class com.bookcon.app.** { *** Companion; }
-keepclasseswithmembers class com.bookcon.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit / OkHttp
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Readium
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# PDFBox-Android: JP2Decoder is an optional JPEG2000 dependency we do not ship
-dontwarn com.gemalto.jp2.**
