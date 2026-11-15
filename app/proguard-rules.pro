# Keep Kotlin & Hilt & Compose metadata
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Kotlinx Serialization
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.zehn.assistant.**$$serializer { *; }
-keepclassmembers class com.zehn.assistant.** {
    *** Companion;
}

# Moshi & Retrofit
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}

# Room entities
-keep class com.zehn.assistant.data.db.*Entity { *; }

# LiteRT-LM native JNI classes
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
