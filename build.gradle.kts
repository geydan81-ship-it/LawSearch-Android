plugins {
    // 版本组合照抄 Kyant0/AndroidLiquidGlass（★4.7k）的 gradle/libs.versions.toml：
    //   agp 9.3.2 / kotlin 2.4.10 / compose 1.12.0
    // 关键：AGP 9 使用**内置 Kotlin**，不应用 org.jetbrains.kotlin.android 插件。
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
}
