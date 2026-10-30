plugins {
    kotlin("multiplatform")
}

// A Ktor server engine on kron: HTTP/1.1 parsed by Glyph11, Ktor's pipeline run on the reactor threads.
kotlin {
    linuxX64()
    linuxArm64()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            }
        }
        nativeMain.dependencies {
            api(project(":kron"))
            implementation(project(":glyph11"))
            api("io.ktor:ktor-server-core:3.6.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
