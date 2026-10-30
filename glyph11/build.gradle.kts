plugins {
    kotlin("multiplatform")
}

// Glyph11, the hardened HTTP/1.1 parser: Ktron's JVM port (github.com/MDA2AV/Ktron, glyph11/), its array-and-offsets
// API, on Kotlin/Native.
kotlin {
    linuxX64()
    linuxArm64()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
