plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.dpvoliin.islandtimetable"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dpvoliin.islandtimetable"
        minSdk = 26
        targetSdk = 36
        versionCode = 1112
        versionName = "1.0.12"
    }

    // 固定调试签名：keystore 随仓库走，保证**每次构建签名一致**。
    //
    // 背景：以前 debug 构建用构建机 ~/.android/debug.keystore，换机器/重建后指纹会变，
    // 结果"装不上旧版本，必须先卸载"（丢数据）。固定下来后就都能直接覆盖升级。
    // 这是**公开的调试密钥**，只用于 debug 构建；正式发布请用自己的 release keystore 且不要入库。
    signingConfigs {
        create("stableDebug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":liveupdates"))
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
