import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "com.xixka"
version = "0.3.0"

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
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            // 上限基于 Plugin Verifier 对 IntelliJ IDEA 2026.2.3 (build 262.*) 的实际验证结果
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides {
            // 支持区间下限：编译目标 ideaIC 2024.1.7（IC 自 2025.3 起不再是可验证目标）
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
            // 支持区间上限：当前最新 IntelliJ IDEA 2026.2.3（build 262.*，新 IntellijIdea 类型）
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
    publishing {
        // JetBrains Marketplace 上传令牌（plugins.jetbrains.com 个人资料页生成），
        // 仅在执行 publishPlugin 任务时读取，构建/验证不受其是否设置影响
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

kotlin {
    jvmToolchain(17)
}

// CI 上无需为插件生成可搜索选项（避免无头 IDE 索引拖慢编译验证）
tasks.matching { it.name == "buildSearchableOptions" }.configureEach {
    enabled = false
}
