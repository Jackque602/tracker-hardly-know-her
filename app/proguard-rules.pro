# osmdroid reads tile source classes reflectively.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# kotlinx.serialization generates serializers that R8 cannot see referenced.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.jackque.roamed.core.backup.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.jackque.roamed.core.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}
