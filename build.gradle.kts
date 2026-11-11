plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.aireview"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// -PliveCheck=<папка>: запуск самопроверки в живой IntelliJ Community (ей не нужна лицензия), см. dev.aireview.selfcheck
val liveCheckDir = providers.gradleProperty("liveCheck").orNull

dependencies {
    intellijPlatform {
        if (liveCheckDir != null) {
            intellijIdeaCommunity("2025.2.4")
        } else {
            // Собираем против установленной IDEA — без скачивания дистрибутива
            local("/Applications/IntelliJ IDEA.app")
        }
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.platform.collaborationTools")
        bundledModule("intellij.platform.vcs.impl")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

tasks.test {
    // Каждый класс тестов в своей JVM: снимкам нужен настоящий загрузчик иконок, включённый до старта платформы
    setForkEvery(1)
}

tasks.runIde {
    if (liveCheckDir != null) {
        jvmArgs(
            "-Daireview.selfCheck=$liveCheckDir",
            "-Daireview.selfCheck.agent=${providers.gradleProperty("liveAgent").getOrElse("cat")}",
            "-Didea.trust.all.projects=true",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Djb.consents.confirmation.enabled=false"
        )
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}
