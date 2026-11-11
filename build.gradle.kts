plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.slopreview"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// -PliveCheck=<папка>: запуск самопроверки в живой IntelliJ Community (ей не нужна лицензия), см. dev.slopreview.selfcheck
val liveCheckDir = providers.gradleProperty("liveCheck").orNull
// -PliveDemo=<папка>: запись кадров демо для README с настоящим Claude Code, см. DemoRecorder и scripts/make-gifs.py
val liveDemoDir = providers.gradleProperty("liveDemo").orNull
// -PliveProject=<папка>: какой проект открыть в песочнице
val liveProjectDir = providers.gradleProperty("liveProject").orNull
val live = liveCheckDir != null || liveDemoDir != null

dependencies {
    intellijPlatform {
        if (live) {
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
    if (liveProjectDir != null) args(liveProjectDir)
    if (live) {
        if (liveCheckDir != null) jvmArgs(
            "-Dslopreview.selfCheck=$liveCheckDir",
            "-Dslopreview.selfCheck.agent=${providers.gradleProperty("liveAgent").getOrElse("cat")}"
        )
        if (liveDemoDir != null) jvmArgs("-Dslopreview.demo=$liveDemoDir")
        jvmArgs(
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
