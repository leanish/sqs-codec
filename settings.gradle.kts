pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("io.github.leanish.java-conventions") version "0.5.5"
        id("info.solidsoft.pitest") version "1.19.0"
    }
}

rootProject.name = "sqs-codec"
