plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        compilations.getByName("main").cinterops.create("uring") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/uring.def"))
            includeDirs(project.file("src/nativeInterop/include"))
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
