# Keep Last.fm API response models (Gson needs field names intact)
-keep class com.djfriend.app.api.** { *; }
-keep class com.djfriend.app.model.** { *; }

# Retrofit
-keepattributes Signature, Exceptions
-keep class retrofit2.** { *; }
-keepclassmembernames interface * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
