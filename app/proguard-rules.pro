# Room, WorkManager, Play Services Auth and AndroidX Security all ship their own
# consumer-proguard-rules.pro inside their AARs, applied automatically -- nothing needed here
# for them. The rules below only cover what those libraries' consumer rules don't: the Google
# API Client / Drive API classes, which are a plain (non-AAR) jar and rely on reflection
# (@Key-annotated fields read/written by field name, not through a generated accessor) to
# serialize/deserialize JSON. Stripping or renaming those fields silently breaks Drive backup
# without a compile-time error -- it would only surface as a runtime JSON mismatch.
-keep class com.google.api.client.** { *; }
-keepclassmembers class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keepclassmembers class com.google.api.services.drive.** { *; }
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault

# Every GenericJson subclass (Drive API model classes, and any future ones) must keep every
# field, since they're (de)serialized by name via reflection, not by a generated adapter.
-keep class * extends com.google.api.client.json.GenericJson { *; }
-keepclassmembers class * extends com.google.api.client.json.GenericJson { *; }

# SQLCipher's native (JNI) code reads/writes its Java fields (e.g. SQLiteDatabase.mNativeHandle)
# by exact name rather than through a generated accessor, and the library doesn't ship its own
# consumer-proguard-rules.pro -- R8 renaming or stripping any of those fields crashes with a
# NoSuchFieldError the moment the database is opened, not at compile time.
-keep class net.sqlcipher.** { *; }
-keepclassmembers class net.sqlcipher.** { *; }

# Transitive dependencies of the Drive/HTTP client stack that are only ever referenced
# reflectively or on non-Android JVMs; safe to not warn about if R8 can't find them.
-dontwarn org.apache.http.**
-dontwarn org.apache.commons.**
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
