plugins {
    java
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "com.example"
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
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
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
