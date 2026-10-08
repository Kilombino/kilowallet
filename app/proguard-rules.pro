# The Ark engine's JNI entry points are looked up by class and method name
# (Java_com_kilombino_pyblockwatch_ark_ArkNative_start/stop).
-keep class com.kilombino.pyblockwatch.ark.ArkNative { *; }

# Readable crash reports (CrashLog): keep file names and line numbers.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
