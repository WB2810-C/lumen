# PlaizierOS is a personal app: keep the real names (readable crash reports), only shrink and optimise.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Parts of libraries that only exist on other platforms.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
