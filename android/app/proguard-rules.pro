# R8 rules for the release build. Libraries that need keep rules ship their own (Compose,
# WorkManager, Play services, kotlinx.serialization, OkHttp); this file covers only what is ours.

# Error text shown in the shells and written to step_run.last_error falls back to the exception
# class name (`e::class.simpleName`) when there is no message. Keep those names readable.
-keepnames class * extends java.lang.Throwable

# Ktor and Okio reference optional classes that are not on an Android classpath.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
