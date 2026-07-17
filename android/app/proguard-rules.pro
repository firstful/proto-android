# Talaria ProGuard Rules
-keepattributes *Annotation*
-keep class com.reedsutliff.talaria.** { *; }
-dontwarn org.conscrypt.**
-dontwarn okhttp3.**
