pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    // gradle.properties; -PkotlinVersion=2.3.21 builds artifacts Ktor's own build (Kotlin 2.3) can read.
    val kotlinVersion: String by settings
    plugins {
        kotlin("multiplatform") version kotlinVersion
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kron"

include("kron", "glyph11", "playground")
