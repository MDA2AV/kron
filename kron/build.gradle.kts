plugins {
    kotlin("multiplatform")
    `maven-publish`
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

    // The JVM: the same reactors, with libc and BoringSSL reached through the Foreign Function & Memory API.
    // BoringSSL comes as native/<arch>/libkron_boringssl.so, from the same static libraries the native targets embed.
    jvm()
    jvmToolchain(25)

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            }
        }
        matching { it.name.startsWith("native") || it.name.startsWith("linux") }.all {
            languageSettings {
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The JVM jar carries its BoringSSL: native/<arch>/libkron_boringssl.so, at kron/native/<arch>/ inside it.
val boringsslForJar = tasks.register<Sync>("boringsslForJar") {
    from(rootProject.file("native")) {
        include("*/libkron_boringssl.so")
    }
    into(layout.buildDirectory.dir("generated/jvmResources/kron/native"))
}

kotlin.sourceSets.named("jvmMain") {
    resources.srcDir(boringsslForJar.map { it.destinationDir.parentFile.parentFile })
}

tasks.named<Test>("jvmTest") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("kron.boringssl", rootProject.file("native/linux-x64/libkron_boringssl.so").absolutePath)
}
