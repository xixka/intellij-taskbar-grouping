import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "com.xixka"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1.7")
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
        }
    }
    pluginVerification {
        ides {
            // 支持区间下限（编译目标）与上限（当前最新 IIC 2025.3 = build 253.*）
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.3")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// CI 上无需为插件生成可搜索选项（避免无头 IDE 索引拖慢编译验证）
tasks.matching { it.name == "buildSearchableOptions" }.configureEach {
    enabled = false
}
