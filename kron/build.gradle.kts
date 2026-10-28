plugins {
    kotlin("multiplatform")
}

kotlin {
    // The two Linux architectures, each with the BoringSSL scripts/build-boringssl.sh built for it in native/.
    val targets = mapOf(
        linuxX64() to "linux-x64",
        linuxArm64() to "linux-arm64",
    )

    for ((target, nativeDir) in targets) {
        target.compilations.getByName("main").cinterops.create("uring") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/uring.def"))
            includeDirs(project.file("src/nativeInterop/include"))
        }

        // BoringSSL, linked into the binary.
        target.compilations.getByName("main").cinterops.create("boringssl") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/boringssl.def"))
            includeDirs(rootProject.file("native/include"))
            extraOpts("-libraryPath", rootProject.file("native/$nativeDir").absolutePath)
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
