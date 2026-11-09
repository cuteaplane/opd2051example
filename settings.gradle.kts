pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 不强制 FAIL_ON_PROJECT_REPOS：本机 ~/.gradle/init.d/mirrors.gradle
    // 会向 project repositories 注入镜像，强制模式会导致配置失败。
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TimerRunner"
include(":app")
