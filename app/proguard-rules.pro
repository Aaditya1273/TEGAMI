# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.tegami.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.tegami.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tegami.**$$serializer { *; }

# BouncyCastle: only the Ed25519 verifier is used; R8 may warn about unused JCA/LDAP parts.
-dontwarn org.bouncycastle.**
# Ktor references JVM-only classes that are not on Android.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
