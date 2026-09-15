// island-live · 顶层构建脚本（子模块各自声明插件，此处只做版本来源）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
