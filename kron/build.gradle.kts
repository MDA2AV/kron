plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        compilations.getByName("main").cinterops.create("uring") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/uring.def"))
            includeDirs(project.file("src/nativeInterop/include"))
        }

        // BoringSSL, built by scripts/build-boringssl.sh and linked into the binary.
        compilations.getByName("main").cinterops.create("boringssl") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/boringssl.def"))
            includeDirs(rootProject.file("native/include"))
            extraOpts("-libraryPath", rootProject.file("native/linux-x64").absolutePath)
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
