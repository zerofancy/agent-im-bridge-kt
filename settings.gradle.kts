plugins {
    // settings 自身的 plugins 块早于版本目录加载，无法 alias(libs.plugins.*)，只能内联版本；
    // 其余插件版本统一由 gradle/libs.versions.toml 管理。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "agent-im-bridge-kt"
include("desktop-app")
