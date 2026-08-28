# The game has no reflection, no serialization frameworks and no JNI callbacks,
# so the default optimized rules are sufficient. Keeping line numbers makes the
# Play Console crash reports readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
