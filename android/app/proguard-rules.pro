# Keep JS bridge methods used from injected scripts
-keepclassmembers class com.reedsutliff.protoapp.CfAccessJsBridge { public *; }
-dontwarn okhttp3.**
