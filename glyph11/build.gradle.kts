plugins {
    kotlin("multiplatform")
    `maven-publish`
}

kotlin {
    linuxX64()
    linuxArm64()

    // Plain Kotlin but for one word read, which the JVM makes through a little-endian VarHandle.
    jvm()
    jvmToolchain(25)

    sourceSets {
        matching { it.name.startsWith("native") || it.name.startsWith("linux") }.all {
            languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
