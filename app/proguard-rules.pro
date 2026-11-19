# ---- 数据模型（kotlinx.serialization）----
# 序列化类必须保留，否则 JSON 反序列化会因为字段名被混淆而失败
-keep class com.lawsearch.app.data.LawJson { *; }
-keep class com.lawsearch.app.data.LawJson$* { *; }
-keep class com.lawsearch.app.data.Block { *; }
-keep class com.lawsearch.app.data.Block$* { *; }
-keep class com.lawsearch.app.data.LawEntity { *; }
-keep class com.lawsearch.app.data.BookmarkEntity { *; }
-keep class com.lawsearch.app.data.LawListRow { *; }
-keep class com.lawsearch.app.data.MetaFile { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- backdrop（AGSL RuntimeShader 玻璃效果，Kyant0/AndroidLiquidGlass）----
# shader 源码与 RuntimeShader 反射路径不能被混淆，否则折射效果在 release 包里失效
-keep class com.kyant.backdrop.** { *; }
-keep class com.kyant.shapes.** { *; }
-dontwarn com.kyant.backdrop.**
-dontwarn com.kyant.shapes.**

# ---- 其它 ----
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.serialization.**

# 保留行号，崩溃堆栈可读
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
