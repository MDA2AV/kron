plugins {
    kotlin("multiplatform")
}

kotlin {
    for (target in listOf(linuxX64(), linuxArm64())) {
        target.binaries {
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
