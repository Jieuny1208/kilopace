# Pleos SDK: AIDL·리플렉션·kotlinx.serialization 사용 → 전체 keep (공식 샘플·경쟁 레포 관측 기반)
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# Pleos SDK 내부가 Gson TypeToken을 쓴다 → 제네릭 시그니처와 Gson 전체 보존 (release 실측: 누락 시 capability 호출이 TypeToken 오류로 실패)
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-dontwarn com.google.gson.**

# kotlinx.serialization (NaviHelper 데이터 클래스가 @Serializable)
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.pleos.**$$serializer { *; }

# android.car (useLibrary) — 런타임 제공
-dontwarn android.car.**

# ZXing
-dontwarn com.google.zxing.**
