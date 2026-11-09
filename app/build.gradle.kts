import java.util.Properties

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasCustomKeystore =
    keystorePropsFile.exists() && !keystoreProps.getProperty("storeFile").isNullOrBlank()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.cuteaplane.timerrunner"
    // 本机 SDK 只有 platform 36；AGP 8.7.3 对新 compileSdk 只会警告
    compileSdk = 36
    // AGP 8.7.3 默认找 build-tools 34.0.0，但本机装的是 35.0.0/36.0.0
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.cuteaplane.timerrunner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // 自定义签名：keystore.properties + keystore/timerrunner.jks
        if (hasCustomKeystore) {
            create("custom") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasCustomKeystore) signingConfig = signingConfigs.getByName("custom")
        }
        release {
            isMinifyEnabled = false
            if (hasCustomKeystore) signingConfig = signingConfigs.getByName("custom")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Shizuku API + provider（https://github.com/RikkaApps/Shizuku-API）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
