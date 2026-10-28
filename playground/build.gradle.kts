plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "playground.main"
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        nativeMain.dependencies {
            implementation(project(":kron"))
        }
    }
}
